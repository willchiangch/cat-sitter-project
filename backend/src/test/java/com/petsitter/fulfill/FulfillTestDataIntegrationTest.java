package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL FulfillTestDataIntegrationTest（FT1 / FT2 / inspect fulfill 區塊）")
class FulfillTestDataIntegrationTest extends FulfillIntegrationTestBase {

    private org.springframework.test.web.servlet.ResultActions ft(String path, UUID visitId, Object body) throws Exception {
        return mvc.perform(post("/api/internal/test-data/visits/{v}/" + path, visitId).header("X-Internal-Secret", SECRET)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    @DisplayName("§6.3 FT1 force-status：PENDING → IN_PROGRESS 觸發 O9（SYSTEM）、→ DONE 寫 started ≤ completed 與 SUBMITTED 日誌並重算基準、→ CLOSED / CANCELLED；不允許的轉換 409；非測試帳號 404；TEST_VISIT_FORCED、不通知")
    void forceStatus() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID normal = confirmedOrder(d);
        ft("force-status", visits(normal).get(0), Map.of("status", "IN_PROGRESS")).andExpect(status().isNotFound());
        UUID orderId = testAccountConfirmedOrder(d, d.plusDays(1), d.plusDays(2), d.plusDays(3));
        List<UUID> vs = visits(orderId);
        int notes = count("SELECT count(*) FROM notifications WHERE related_order_id = ?", orderId);
        ft("force-status", vs.get(0), Map.of("status", "IN_PROGRESS")).andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("IN_PROGRESS"));
        assertThat(jdbc.queryForObject("SELECT operator_role FROM order_logs WHERE order_id = ? AND action_type = 'ORDER_STARTED'", String.class, orderId))
                .isEqualTo("SYSTEM");
        ft("force-status", vs.get(0), Map.of("status", "IN_PROGRESS")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT"));
        OffsetDateTime completed = OffsetDateTime.parse("2030-01-01T03:00:00Z");
        ft("force-status", vs.get(1), Map.of("status", "DONE", "completedAt", completed.toString(), "completionSource", "SYSTEM",
                "careLogSubmitted", true)).andExpect(status().isOk()).andExpect(jsonPath("$.completionSource").value("SYSTEM"));
        assertThat(jdbc.queryForObject("SELECT status FROM visit_service_reports WHERE visit_id = ?", String.class, vs.get(1))).isEqualTo("SUBMITTED");
        ft("force-status", vs.get(0), Map.of("status", "DONE")).andExpect(status().isOk());
        ft("force-status", vs.get(2), Map.of("status", "CLOSED_BY_SYSTEM")).andExpect(status().isOk());
        ft("force-status", vs.get(3), Map.of("status", "CANCELLED")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId)).isNotNull();
        ft("force-status", vs.get(3), Map.of("status", "DONE")).andExpect(status().isConflict());
        ft("force-status", vs.get(0), Map.of("status", "BOGUS")).andExpect(status().isBadRequest());
        assertThat(logCount(orderId, "TEST_VISIT_FORCED")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ?", orderId)).isEqualTo(notes);

        // PENDING_PAYMENT：只允許 CANCELLED（不改主狀態）
        UUID testOwner = jdbc.queryForObject("SELECT owner_id FROM orders WHERE id = ?", UUID.class, orderId);
        UUID pet = jdbc.queryForObject("SELECT id FROM pets WHERE owner_id = ?", UUID.class, testOwner);
        UUID pp = UUID.fromString(body(mvc.perform(postJson("/api/orders", bearer(testOwner, "OWNER"), key(),
                bookingBody(sitter, List.of(d, d.plusDays(1)), 1, 1000, pet)))).get("orderId").asString());
        quote(pp);
        List<UUID> pvs = visits(pp);
        ft("force-status", pvs.get(0), Map.of("status", "IN_PROGRESS")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        ft("force-status", pvs.get(0), Map.of("status", "CANCELLED")).andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("§6.3 FT2 shift-time：STARTED_AT / COMPLETED_AT 位移並重算基準；SERVICE_DATE 整群組平移（不撞 ux_visits_slot）；PENDING_PAYMENT 只允許 SERVICE_DATE；欄位為空 400；TEST_TIME_SHIFTED")
    void shiftTime() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = testAccountConfirmedOrder(d, d.plusDays(1), d.plusDays(2));
        List<UUID> vs = visits(orderId);
        ft("shift-time", vs.get(0), Map.of("target", "SERVICE_DATE", "days", 1)).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT service_date FROM visits WHERE order_id = ? ORDER BY service_date", LocalDate.class, orderId))
                .containsExactly(d.plusDays(1), d.plusDays(2), d.plusDays(3));
        ft("shift-time", vs.get(0), Map.of("target", "SERVICE_DATE", "days", -3)).andExpect(status().isOk());
        assertThat(serviceDate(vs.get(0))).isEqualTo(d.minusDays(2));
        ft("shift-time", vs.get(0), Map.of("target", "STARTED_AT", "minutes", 5)).andExpect(status().isBadRequest());
        ft("force-status", vs.get(0), Map.of("status", "DONE", "completedAt", "2030-01-01T03:00:00Z")).andExpect(status().isOk());
        ft("force-status", vs.get(1), Map.of("status", "DONE", "completedAt", "2030-01-01T03:00:00Z")).andExpect(status().isOk());
        ft("force-status", vs.get(2), Map.of("status", "DONE", "completedAt", "2030-01-01T03:00:00Z")).andExpect(status().isOk());
        ft("shift-time", vs.get(0), Map.of("target", "COMPLETED_AT", "minutes", 60)).andExpect(status().isOk())
                .andExpect(jsonPath("$.after").value("2030-01-01T04:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId).toInstant())
                .isEqualTo(OffsetDateTime.parse("2030-01-02T04:00:00Z").toInstant());
        assertThat(logCount(orderId, "TEST_TIME_SHIFTED")).isEqualTo(3);
        setOrderStatus(orderId, "COMPLETED");
        ft("shift-time", vs.get(0), Map.of("target", "COMPLETED_AT", "minutes", 1)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("§10.2 inspect 的 fulfill 區塊：visits / careLogs / media / checklistItems / uploads 欄位齊全、不含簽名網址")
    void inspect() throws Exception {
        defaultTasks(planA, "[\"I1\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = testAccountConfirmedOrder(d);
        UUID v = visits(orderId).get(0);
        ft("force-status", v, Map.of("status", "DONE", "careLogSubmitted", true)).andExpect(status().isOk());
        JsonNode f = body(mvc.perform(get("/api/internal/test-data/orders/{o}/inspect", orderId).header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())).get("fulfill");
        JsonNode visit = f.get("visits").get(0);
        for (String k : List.of("visitId", "serviceDate", "planId", "status", "completionSource", "startedAt", "completedAt")) {
            assertThat(visit.has(k)).as(k).isTrue();
        }
        assertThat(f.get("careLogs").get(0).get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(f.get("careLogs").get(0).get("createdBy").asString()).isEqualTo(sitter.toString());
        JsonNode item = f.get("checklistItems").get(0);
        for (String k : List.of("itemId", "visitId", "title", "sortOrder", "isCompleted", "createdSource", "updatedAt")) {
            assertThat(item.has(k)).as(k).isTrue();
        }
        assertThat(f.get("media")).isEmpty();
        assertThat(f.get("uploads")).isEmpty();
        assertThat(f.toString()).doesNotContain("X-Goog");
    }
}
