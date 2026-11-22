package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL VisitCompleteIntegrationTest（F4）")
class VisitCompleteIntegrationTest extends FulfillIntegrationTestBase {

    @Test
    @DisplayName("PRD-008 AC-1.5 結束不看打勾與日誌：DONE、completion_source=SITTER、VISIT_COMPLETED；唯一行程 → completion_basis_at = completed_at + 24h；主狀態不變")
    void completeIgnoresChecklistAndLog() throws Exception {
        defaultTasks(planA, "[\"餵乾糧\",\"換貓砂\",\"清水碗\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        UUID v = visits(orderId).get(0);
        freeze(taipei(d, 9, 0));
        start(orderId, v, sitterToken(), key()).andExpect(status().isOk());
        OffsetDateTime at = taipei(d, 11, 0);
        freeze(at);
        complete(orderId, v, sitterToken(), key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completionSource").value("SITTER"))
                .andExpect(jsonPath("$.careLogDeadlineAt").value(iso(at.plusHours(24))));
        assertThat(count("SELECT count(*) FROM visit_checklist_items WHERE visit_id = ? AND is_completed = false", v)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM visit_service_reports WHERE visit_id = ?", v)).isZero();
        assertThat(jdbc.queryForObject("SELECT completion_source FROM visits WHERE id = ?", String.class, v)).isEqualTo("SITTER");
        assertThat(logCount(orderId, "VISIT_COMPLETED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId).toInstant())
                .isEqualTo(at.plusHours(24).toInstant());
        assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("PRD-008 AC-1.4 情境 2 結束非 IN_PROGRESS 行程 → 409 VISIT_STATE_CONFLICT（PENDING / DONE），不寫 log")
    void completeWrongState() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        freeze(taipei(d, 9, 0));
        start(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isOk());
        complete(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isOk());
        int logs = logActions(orderId).size();
        complete(orderId, vs.get(1), sitterToken(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentVisitStatus").value("PENDING"));
        complete(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.payload.currentVisitStatus").value("DONE")).andExpect(jsonPath("$.payload.completionSource").value("SITTER"));
        assertThat(logActions(orderId)).hasSize(logs);
    }
}
