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

@DisplayName("SD-FULFILL VisitStartIntegrationTest（F3）")
class VisitStartIntegrationTest extends FulfillIntegrationTestBase {

    @Test
    @DisplayName("PRD-008 AC-1.1 第一次開始觸發 O9：只寫 ORDER_STARTED（visitId、cause=STARTED），無 VISIT_STARTED、不通知飼主")
    void firstStartTriggersO9() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        int ownerNotes = count("SELECT count(*) FROM notifications WHERE user_id = ?", owner);
        OffsetDateTime at = taipei(d, 9, 0);
        freeze(at);
        start(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.orderStatus").value("IN_PROGRESS"));
        assertThat(visitStatus(vs.get(0))).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT started_at FROM visits WHERE id = ?", OffsetDateTime.class, vs.get(0)).toInstant())
                .isEqualTo(at.toInstant());
        assertThat(visitStatus(vs.get(1))).isEqualTo("PENDING");
        assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");
        assertThat(logCount(orderId, "ORDER_STARTED")).isEqualTo(1);
        assertThat(logCount(orderId, "VISIT_STARTED")).isZero();
        assertThat(jdbc.queryForMap("SELECT payload->>'visitId' AS v, payload->>'cause' AS c, operator_role AS r, operator_user_id AS u "
                + "FROM order_logs WHERE order_id = ? AND action_type = 'ORDER_STARTED'", orderId))
                .containsEntry("v", vs.get(0).toString()).containsEntry("c", "STARTED").containsEntry("r", "SITTER").containsEntry("u", sitter);
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", owner)).isEqualTo(ownerNotes);
    }

    @Test
    @DisplayName("PRD-008 AC-1.2 進行中 / 爭議中訂單開始不改主狀態，寫 VISIT_STARTED、無 ORDER_STARTED")
    void startInProgressOrDisputed() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        freeze(taipei(d, 9, 0));
        start(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isOk());
        complete(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isOk());
        freeze(taipei(d.plusDays(1), 9, 0));
        start(orderId, vs.get(1), sitterToken(), key()).andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("IN_PROGRESS"));
        assertThat(logCount(orderId, "VISIT_STARTED")).isEqualTo(1);
        assertThat(logCount(orderId, "ORDER_STARTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload->>'visitId' FROM order_logs WHERE order_id = ? AND action_type = 'VISIT_STARTED'",
                String.class, orderId)).isEqualTo(vs.get(1).toString());

        clock.reset();
        UUID disputed = confirmedOrder(d);
        UUID v3 = visits(disputed).get(0);
        setOrderStatus(disputed, "DISPUTED");
        freeze(taipei(d, 10, 0));
        start(disputed, v3, sitterToken(), key()).andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("DISPUTED"));
        assertThat(orderStatus(disputed)).isEqualTo("DISPUTED");
        assertThat(logCount(disputed, "ORDER_STARTED")).isZero();
        assertThat(logCount(disputed, "VISIT_STARTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-008 AC-1.3 日期未到 422 VISIT_DATE_NOT_REACHED；未逾 72h 可開始；VISIT_ZOMBIE_AT 等於即拒 409 DEADLINE_PASSED")
    void dateAndZombieBoundaries() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        UUID v = visits(orderId).get(0);
        freeze(taipei(d.minusDays(1), 23, 59));
        start(orderId, v, sitterToken(), key()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VISIT_DATE_NOT_REACHED"))
                .andExpect(jsonPath("$.payload.serviceDate").value(d.toString()));
        OffsetDateTime zombieAt = taipei(d, 0, 0).plusHours(72);
        freeze(zombieAt);
        start(orderId, v, sitterToken(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEADLINE_PASSED"))
                .andExpect(jsonPath("$.payload.deadline").value("VISIT_ZOMBIE_AT"))
                .andExpect(jsonPath("$.payload.deadlineAt").value(iso(zombieAt)));
        assertThat(visitStatus(v)).isEqualTo("PENDING");
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        freeze(zombieAt.minusMinutes(1));
        start(orderId, v, sitterToken(), key()).andExpect(status().isOk());
        assertThat(visitStatus(v)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("PRD-008 AC-1.4 狀態不允許：行程非 PENDING → VISIT_STATE_CONFLICT；PENDING_PAYMENT / COMPLETED → ORDER_STATE_CONFLICT（訂單先於行程），不寫 log")
    void stateNotAllowed() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1), d.plusDays(2), d.plusDays(3));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        List<UUID> vs = visits(orderId);
        OffsetDateTime at = taipei(d, 9, 0);
        forceVisit(vs.get(0), "IN_PROGRESS", at, null, null);
        forceVisit(vs.get(1), "DONE", at, at.plusHours(1), "SYSTEM");
        forceClosed(vs.get(2), at);
        forceCancelled(vs.get(3), at);
        freeze(at.plusHours(2));
        int logs = logActions(orderId).size();
        start(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentVisitStatus").value("IN_PROGRESS"));
        start(orderId, vs.get(1), sitterToken(), key()).andExpect(jsonPath("$.payload.currentVisitStatus").value("DONE"))
                .andExpect(jsonPath("$.payload.completionSource").value("SYSTEM"));
        start(orderId, vs.get(2), sitterToken(), key()).andExpect(jsonPath("$.payload.currentVisitStatus").value("CLOSED_BY_SYSTEM"));
        start(orderId, vs.get(3), sitterToken(), key()).andExpect(jsonPath("$.payload.currentVisitStatus").value("CANCELLED"));
        assertThat(logActions(orderId)).hasSize(logs);

        clock.reset();
        UUID pendingPayment = book(d);
        quote(pendingPayment);
        freeze(at);
        start(pendingPayment, visits(pendingPayment).get(0), sitterToken(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentStatus").value("PENDING_PAYMENT"));

        setOrderStatus(orderId, "COMPLETED");
        start(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentStatus").value("COMPLETED"));
        complete(orderId, vs.get(1), sitterToken(), key()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertThat(logActions(orderId)).hasSize(logs);
    }
}
