package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C7 端點測試歸本 SD（SD-FULFILL §10.1 歸屬）：經 C7 → closeZombieVisits。 */
@DisplayName("SD-FULFILL CloseZombieVisitsIntegrationTest（C7 → closeZombieVisits）")
class CloseZombieVisitsIntegrationTest extends FulfillIntegrationTestBase {

    private static final String JOB = "visits/zombie";

    @Test
    @DisplayName("PRD-008 AC-2.1 殭屍關閉觸發 O9：V1 CLOSED_BY_SYSTEM、V2 仍 PENDING、訂單 IN_PROGRESS；VISITS_CLOSED_BY_SYSTEM + SYSTEM 的 ORDER_STARTED（cause=CLOSED_BY_SYSTEM）；雙方各 1 則（PRD 字面）且排入 Email；processed=1")
    void closeTriggersO9() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        OffsetDateTime runAt = taipei(d, 0, 0).plusHours(72).plusMinutes(5);
        freeze(runAt);
        counts(cron(JOB), 1, 0, 0);
        assertThat(visitStatus(vs.get(0))).isEqualTo("CLOSED_BY_SYSTEM");
        assertThat(jdbc.queryForObject("SELECT closed_at FROM visits WHERE id = ?", OffsetDateTime.class, vs.get(0)).toInstant()).isEqualTo(runAt.toInstant());
        assertThat(visitStatus(vs.get(1))).isEqualTo("PENDING");
        assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");
        assertThat(logCount(orderId, "VISITS_CLOSED_BY_SYSTEM")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT operator_role AS r, payload->>'cause' AS c FROM order_logs WHERE order_id = ? AND action_type = 'ORDER_STARTED'", orderId))
                .containsEntry("r", "SYSTEM").containsEntry("c", "CLOSED_BY_SYSTEM");
        Map<String, Object> s = jdbc.queryForMap("SELECT content, email_status FROM notifications WHERE user_id = ? AND type = 'VISIT_CLOSED_BY_SYSTEM_SITTER'", sitter);
        assertThat(s.get("content")).isEqualTo(d + " 到府餵食 未於期限內開始，已由系統關閉");
        assertThat(s.get("email_status")).isEqualTo("PENDING");
        Map<String, Object> o = jdbc.queryForMap("SELECT content, email_status FROM notifications WHERE user_id = ? AND type = 'VISIT_CLOSED_BY_SYSTEM_OWNER'", owner);
        assertThat(o.get("content")).isEqualTo("保母未於期限內開始 " + d + " 到府餵食，系統已關閉此行程");
        assertThat(o.get("email_status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("PRD-008 AC-2.2 邊界（VISIT_ZOMBIE_AT 前一刻不處理、等於即關閉）；IN_PROGRESS 訂單無新 ORDER_STARTED 且基準 = closed_at；DISPUTED 照常；PENDING_PAYMENT 不處理；同批 2 筆合併為各 1 則、列出 2 個日期")
    void scopeAndBoundaries() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID inProgress = confirmedOrder(d);
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", inProgress);
        UUID disputed = confirmedOrder(d);
        setOrderStatus(disputed, "DISPUTED");
        UUID pendingPayment = book(d);
        quote(pendingPayment);
        UUID two = confirmedOrder(d, d.plusDays(1));
        OffsetDateTime zombieAt = taipei(d, 0, 0).plusHours(72);
        freeze(zombieAt.minusMinutes(1));
        counts(cron(JOB), 0, 0, 0);
        freeze(zombieAt.plusDays(1));
        counts(cron(JOB), 3, 0, 0);
        UUID v = visits(inProgress).get(0);
        assertThat(visitStatus(v)).isEqualTo("CLOSED_BY_SYSTEM");
        assertThat(orderStatus(inProgress)).isEqualTo("IN_PROGRESS");
        assertThat(logCount(inProgress, "ORDER_STARTED")).isZero();
        assertThat(jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, inProgress).toInstant())
                .isEqualTo(zombieAt.plusDays(1).toInstant());
        assertThat(orderStatus(disputed)).isEqualTo("DISPUTED");
        assertThat(visitStatus(visits(disputed).get(0))).isEqualTo("CLOSED_BY_SYSTEM");
        assertThat(visitStatus(visits(pendingPayment).get(0))).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type LIKE 'VISIT_CLOSED%'", pendingPayment)).isZero();
        assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'VISIT_CLOSED_BY_SYSTEM_SITTER'", two)).isEqualTo(1);
        String content = jdbc.queryForObject("SELECT content FROM notifications WHERE related_order_id = ? AND type = 'VISIT_CLOSED_BY_SYSTEM_OWNER'", String.class, two);
        assertThat(content.split("\n")).hasSize(2);
        assertThat(content).contains(d.toString()).contains(d.plusDays(1).toString());
    }

    @Test
    @DisplayName("PRD-008 AC-2.6 鎖住略過（skipped、不通知、下次重試）；重跑不重複；一般 token 403；自動觸發跳過測試帳號訂單、orderId 只處理該筆")
    void rerunAndScope() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        UUID testOrder = testAccountConfirmedOrder(d);
        freeze(taipei(d, 0, 0).plusHours(80));
        counts(cron(JOB), 1, 0, 0);
        counts(cron(JOB), 0, 0, 0);
        assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type LIKE 'VISIT_CLOSED%'", orderId)).isEqualTo(2);
        assertThat(visitStatus(visits(testOrder).get(0))).isEqualTo("PENDING");
        counts(body(cronRaw(JOB, "orderId=" + testOrder).andExpect(status().isOk())), 1, 0, 0);
        mvc.perform(post("/api/internal/cron/" + JOB).header("Authorization", sitterToken())).andExpect(status().isForbidden());

        // 訂單列被鎖住 → 略過（skipped）、不通知，下次重試（AC-2.6 情境 5）
        clock.reset();
        UUID locked = confirmedOrder(d);
        freeze(taipei(d, 0, 0).plusHours(80));
        var hold = holdOrderLock(locked, java.time.Duration.ofSeconds(2));
        counts(cron(JOB), 0, 1, 0);
        assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type LIKE 'VISIT_CLOSED%'", locked)).isZero();
        hold.join();
        counts(cron(JOB), 1, 0, 0);
    }
}
