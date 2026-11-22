package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 兩條執行緒真打（Testcontainers PostgreSQL；不 mock 鎖）。 */
@DisplayName("SD-FULFILL VisitConcurrencyIntegrationTest")
class VisitConcurrencyIntegrationTest extends FulfillIntegrationTestBase {

    private UUID proposeRemoval(UUID orderId, UUID visitId) throws Exception {
        return modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "減少",
                "removeVisitIds", List.of(visitId.toString()), "addItems", List.of())).andExpect(status().isCreated()));
    }

    private org.springframework.test.web.servlet.ResultActions accept(UUID orderId, UUID modId) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, modId));
    }

    @Test
    @DisplayName("PRD-008 AC-1.8 情境 1–2 開始 vs 飼主同意減少該行程：恰一方成功；開始先 → 同意 409 VISIT_ALREADY_STARTED；同意先 → 開始 409 VISIT_STATE_CONFLICT（CANCELLED）")
    void startVsAccept() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        List<UUID> vs = visits(orderId);
        UUID modId = proposeRemoval(orderId, vs.get(0));
        freeze(taipei(d, 9, 0));
        int[] codes = race(() -> start(orderId, vs.get(0), sitterToken(), key()), () -> accept(orderId, modId));
        assertThat(codes[0] == 200 ^ codes[1] == 200).isTrue();
        if (codes[0] == 200) {
            assertThat(visitStatus(vs.get(0))).isEqualTo("IN_PROGRESS");
            assertThat(jdbc.queryForObject("SELECT status FROM modification_requests WHERE id = ?", String.class, modId)).isEqualTo("QUOTED");
        } else {
            assertThat(visitStatus(vs.get(0))).isEqualTo("CANCELLED");
            start(orderId, vs.get(0), sitterToken(), key()).andExpect(jsonPath("$.payload.currentVisitStatus").value("CANCELLED"));
        }
    }

    @Test
    @DisplayName("PRD-008 AC-1.8 情境 3–4：ADMIN 先取消（訂單 CANCELLED）→ 開始 409 ORDER_STATE_CONFLICT；鎖逾時 → 409 ORDER_BUSY + Retry-After: 1、行程仍 PENDING")
    void cancelledAndBusy() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        freeze(taipei(d, 9, 0));
        var hold = holdOrderLock(orderId, Duration.ofSeconds(5));
        start(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_BUSY"))
                .andExpect(header().string("Retry-After", "1"));
        hold.join();
        assertThat(visitStatus(vs.get(0))).isEqualTo("PENDING");
        setOrderStatus(orderId, "CANCELLED");
        forceCancelled(vs.get(0), OffsetDateTime.now(clock));
        start(orderId, vs.get(1), sitterToken(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentStatus").value("CANCELLED"));
    }

    @Test
    @DisplayName("PRD-008 AC-2.5 情境 1 開始 V1 與殭屍排程關閉 V2 同時：V1 IN_PROGRESS、V2 CLOSED_BY_SYSTEM、訂單 IN_PROGRESS、只有 1 筆 ORDER_STARTED")
    void startVsZombie() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(3));
        List<UUID> vs = visits(orderId); // V(d) 將逾期、V(d+3) 今天
        freeze(taipei(d.plusDays(3), 9, 0));
        int[] codes = race(() -> start(orderId, vs.get(1), sitterToken(), key()), () -> cronRaw("visits/zombie", null));
        assertThat(codes[0]).isEqualTo(200);
        assertThat(codes[1]).isEqualTo(200);
        if (!"CLOSED_BY_SYSTEM".equals(visitStatus(vs.get(0)))) {
            cron("visits/zombie"); // 排程搶不到鎖時被略過，下一輪補
        }
        assertThat(visitStatus(vs.get(1))).isEqualTo("IN_PROGRESS");
        assertThat(visitStatus(vs.get(0))).isEqualTo("CLOSED_BY_SYSTEM");
        assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");
        assertThat(logCount(orderId, "ORDER_STARTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-008 AC-2.5 情境 2–3 殭屍排程 vs 飼主同意減少逾期行程：排程先 → 同意 409；同意先 → 行程 CANCELLED、排程略過且無關閉通知")
    void zombieVsAccept() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(5));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        List<UUID> vs = visits(orderId);
        UUID modId = proposeRemoval(orderId, vs.get(0));
        freeze(taipei(d, 0, 0).plusHours(73));
        int[] codes = race(() -> accept(orderId, modId), () -> cronRaw("visits/zombie", null));
        String status = visitStatus(vs.get(0));
        if (codes[0] == 200) {
            assertThat(status).isEqualTo("CANCELLED");
            counts(cron("visits/zombie"), 0, 0, 0);
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type LIKE 'VISIT_CLOSED%'", orderId)).isZero();
            assertThat(logCount(orderId, "VISITS_CLOSED_BY_SYSTEM")).isZero();
        } else {
            assertThat(codes[0]).isEqualTo(409);
            if (!"CLOSED_BY_SYSTEM".equals(status)) {
                cron("visits/zombie");
            }
            assertThat(visitStatus(vs.get(0))).isEqualTo("CLOSED_BY_SYSTEM");
        }
    }

    @Test
    @DisplayName("PRD-008 AC-2.4 情境 2–3 / PRD-028 AC-2.2 情境 3 結束 vs FC1、打勾 vs FC1：排程先 → 409 VISIT_STATE_CONFLICT（DONE、SYSTEM，非 ORDER_BUSY）；保母先 → 200、排程不重複處理")
    void completeAndCheckVsTimeout() throws Exception {
        defaultTasks(planA, "[\"I1\"]");
        LocalDate d = today().plusDays(1);
        UUID o1 = confirmedOrder(d);
        UUID o2 = confirmedOrder(d.plusDays(1));
        OffsetDateTime startedAt = OffsetDateTime.parse("2030-12-10T02:00:00Z");
        for (UUID o : List.of(o1, o2)) {
            jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", o);
            forceVisit(visits(o).get(0), "IN_PROGRESS", startedAt, null, null);
        }
        UUID v1 = visits(o1).get(0);
        UUID v2 = visits(o2).get(0);
        freeze(startedAt.plusHours(25));
        int[] codes = race(() -> complete(o1, v1, sitterToken(), key()), () -> cronRaw("visits/in-progress-timeout", null));
        assertThat(codes[1]).isEqualTo(200);
        String source = jdbc.queryForObject("SELECT completion_source FROM visits WHERE id = ?", String.class, v1);
        if (codes[0] == 200) {
            assertThat(source).isEqualTo("SITTER");
            assertThat(logCount(o1, "VISIT_AUTO_COMPLETED")).isZero();
        } else {
            assertThat(codes[0]).isEqualTo(409);
            assertThat(source).isEqualTo("SYSTEM");
            complete(o1, v1, sitterToken(), key()).andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT"))
                    .andExpect(jsonPath("$.payload.completionSource").value("SYSTEM"));
        }
        UUID item = itemIds(v2).get(0);
        if ("IN_PROGRESS".equals(visitStatus(v2))) {
            int[] k = race(() -> check(o2, v2, item, sitterToken(), true), () -> cronRaw("visits/in-progress-timeout", null));
            assertThat(k[0]).isIn(200, 409);
        }
        if (!"DONE".equals(visitStatus(v2))) {
            cron("visits/in-progress-timeout");
        }
        check(o2, v2, item, sitterToken(), false).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT"))
                .andExpect(jsonPath("$.payload.currentVisitStatus").value("DONE"));
    }

    @Test
    @DisplayName("PRD-028 AC-2.2（EX-9）檢核項寫入 vs 同意變更取消目標行程（兩條執行緒）：同意先 → 409 VISIT_STATE_CONFLICT（CANCELLED）；保母先 → 201、之後同意照常；被取消的 PENDING 行程本就不可打勾")
    void checklistVsAccept() throws Exception {
        defaultTasks(planA, "[\"I1\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        List<UUID> vs = visits(orderId);
        UUID modId = proposeRemoval(orderId, vs.get(1));
        UUID item = itemIds(vs.get(1)).get(0);
        check(orderId, vs.get(1), item, sitterToken(), true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.payload.currentVisitStatus").value("PENDING"));
        int[] codes = race(() -> addItem(orderId, vs.get(1), sitterToken(), key(), "新增"), () -> accept(orderId, modId));
        assertThat(codes[1]).isEqualTo(200);
        assertThat(codes[0]).isIn(201, 409);
        assertThat(visitStatus(vs.get(1))).isEqualTo("CANCELLED");
        if (codes[0] == 409) {
            assertThat(itemIds(vs.get(1))).hasSize(1);
        } else {
            assertThat(itemIds(vs.get(1))).hasSize(2);
        }
        addItem(orderId, vs.get(1), sitterToken(), key(), "x").andExpect(jsonPath("$.payload.currentVisitStatus").value("CANCELLED"));
        check(orderId, vs.get(1), item, sitterToken(), true).andExpect(jsonPath("$.payload.currentVisitStatus").value("CANCELLED"));
    }
}
