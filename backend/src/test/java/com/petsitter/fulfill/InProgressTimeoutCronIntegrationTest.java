package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL InProgressTimeoutCronIntegrationTest（FC1）")
class InProgressTimeoutCronIntegrationTest extends FulfillIntegrationTestBase {

    private static final String JOB = "visits/in-progress-timeout";

    private UUID runningVisit(UUID orderId, OffsetDateTime startedAt) {
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        UUID v = visits(orderId).get(0);
        forceVisit(v, "IN_PROGRESS", startedAt, null, null);
        return v;
    }

    @Test
    @DisplayName("PRD-008 AC-2.3 逾時自動結束：DONE、completed_at = 排程時刻、SYSTEM；VISIT_AUTO_COMPLETED（SYSTEM）；保母 1 則通知（含截止時間）、飼主無；計入 completion_basis_at")
    void autoComplete() throws Exception {
        UUID orderId = confirmedOrder(today().plusDays(1));
        OffsetDateTime startedAt = OffsetDateTime.parse("2030-12-10T02:00:00Z");
        UUID v = runningVisit(orderId, startedAt);
        rename(orderId, v, sitterToken(), "早上服務").andExpect(status().isOk());
        OffsetDateTime runAt = OffsetDateTime.parse("2030-12-11T02:10:00Z");
        freeze(runAt);
        counts(cron(JOB), 1, 0, 0);
        assertThat(jdbc.queryForMap("SELECT status, completion_source, completed_at FROM visits WHERE id = ?", v))
                .containsEntry("status", "DONE").containsEntry("completion_source", "SYSTEM");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM visits WHERE id = ?", OffsetDateTime.class, v).toInstant()).isEqualTo(runAt.toInstant());
        assertThat(jdbc.queryForObject("SELECT operator_role FROM order_logs WHERE order_id = ? AND action_type = 'VISIT_AUTO_COMPLETED'", String.class, orderId))
                .isEqualTo("SYSTEM");
        Map<String, Object> n = jdbc.queryForMap("SELECT content, link_url FROM notifications WHERE user_id = ? AND type = 'VISIT_AUTO_COMPLETED'", sitter);
        assertThat((String) n.get("content")).contains("系統已自動結束").contains("請於 2030-12-12 10:10 前送出照護日誌").contains("早上服務");
        assertThat(n.get("link_url")).isEqualTo("/sitter/orders/" + orderId + "/visits/" + v);
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'VISIT_AUTO_COMPLETED'", owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId).toInstant())
                .isEqualTo(runAt.plusHours(24).toInstant());
    }

    @Test
    @DisplayName("PRD-008 AC-2.4 邊界（started_at + 24h 前不處理、等於即處理）；保母先結束則排程無動作；排程先結束則 F4 409（completionSource=SYSTEM）；DISPUTED 照常、主狀態不變")
    void boundariesAndRace() throws Exception {
        UUID o1 = confirmedOrder(today().plusDays(1));
        UUID o2 = confirmedOrder(today().plusDays(2));
        UUID o3 = confirmedOrder(today().plusDays(3));
        OffsetDateTime startedAt = OffsetDateTime.parse("2030-12-10T02:00:00Z");
        UUID v1 = runningVisit(o1, startedAt);
        UUID v2 = runningVisit(o2, startedAt);
        UUID v3 = runningVisit(o3, startedAt);
        setOrderStatus(o3, "DISPUTED");
        freeze(startedAt.plusHours(24).minusMinutes(1));
        counts(cron(JOB), 0, 0, 0);
        assertThat(visitStatus(v1)).isEqualTo("IN_PROGRESS");
        freeze(startedAt.plusHours(24).plusMinutes(5));
        complete(o2, v2, sitterToken(), key()).andExpect(status().isOk()).andExpect(jsonPath("$.completionSource").value("SITTER"));
        freeze(startedAt.plusHours(24));
        counts(cron(JOB), 2, 0, 0);
        assertThat(jdbc.queryForObject("SELECT completion_source FROM visits WHERE id = ?", String.class, v2)).isEqualTo("SITTER");
        assertThat(logCount(o2, "VISIT_AUTO_COMPLETED")).isZero();
        assertThat(visitStatus(v3)).isEqualTo("DONE");
        assertThat(orderStatus(o3)).isEqualTo("DISPUTED");
        complete(o1, v1, sitterToken(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.payload.currentVisitStatus").value("DONE")).andExpect(jsonPath("$.payload.completionSource").value("SYSTEM"));
    }

    @Test
    @DisplayName("PRD-008 AC-2.6 鎖住略過（skipped）、單筆失敗隔離（failed=1、其他照常）、下次重試、重跑不重複、一般 token 403、orderId 範圍")
    void isolationAndRerun() throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.parse("2030-12-10T02:00:00Z");
        UUID a = confirmedOrder(today().plusDays(1));
        UUID b = confirmedOrder(today().plusDays(2));
        UUID c = confirmedOrder(today().plusDays(3));
        UUID va = runningVisit(a, startedAt);
        UUID vb = runningVisit(b, startedAt);
        runningVisit(c, startedAt);
        freeze(startedAt.plusHours(25));
        jdbc.execute("CREATE OR REPLACE FUNCTION fulfill_test_fc1_fail() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.id = '" + va + "' THEN RAISE EXCEPTION 'boom'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fulfill_test_fc1_fail_trg BEFORE UPDATE ON visits FOR EACH ROW EXECUTE FUNCTION fulfill_test_fc1_fail()");
        var hold = holdOrderLock(b, java.time.Duration.ofSeconds(3));
        try {
            counts(cron(JOB), 1, 1, 1);
        } finally {
            jdbc.execute("DROP TRIGGER fulfill_test_fc1_fail_trg ON visits");
            jdbc.execute("DROP FUNCTION fulfill_test_fc1_fail()");
        }
        hold.join();
        assertThat(visitStatus(va)).isEqualTo("IN_PROGRESS");
        assertThat(logCount(a, "VISIT_AUTO_COMPLETED")).isZero();
        assertThat(visitStatus(vb)).isEqualTo("IN_PROGRESS");
        counts(cron(JOB), 2, 0, 0);
        counts(cron(JOB), 0, 0, 0);
        assertThat(count("SELECT count(*) FROM notifications WHERE type = 'VISIT_AUTO_COMPLETED'")).isEqualTo(3);
        mvc.perform(post("/api/internal/cron/" + JOB).header("Authorization", sitterToken())).andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/cron/" + JOB).header("X-Internal-Secret", "wrong")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PRD-008 AC-2.6（§6.3）orderId 只處理該測試帳號訂單；非測試帳號訂單帶 orderId → 404；自動觸發跳過 @e2e-journey.test 訂單")
    void scope() throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.parse("2030-12-10T02:00:00Z");
        UUID normal = confirmedOrder(today().plusDays(1));
        UUID vn = runningVisit(normal, startedAt);
        cronRaw(JOB, "orderId=" + normal).andExpect(status().isNotFound());
        UUID testOrder = testAccountConfirmedOrder(today().plusDays(2));
        UUID vt = runningVisit(testOrder, startedAt);
        freeze(startedAt.plusHours(25));
        JsonNode auto = cron(JOB);
        assertThat(visitStatus(vt)).isEqualTo("IN_PROGRESS");
        assertThat(visitStatus(vn)).isEqualTo("DONE");
        counts(auto, 1, 0, 0);
        counts(body(cronRaw(JOB, "orderId=" + testOrder).andExpect(status().isOk())), 1, 0, 0);
        assertThat(visitStatus(vt)).isEqualTo("DONE");
    }
}
