package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL OrderVisitListIntegrationTest（F1）")
class OrderVisitListIntegrationTest extends FulfillIntegrationTestBase {

    @Test
    @DisplayName("PRD-008 AC-3.3 F1 為物件 { orderId, orderStatus, visits }，含 CANCELLED；開始 / 結束時間、完成來源；zombieAt 只在 PENDING")
    void listShape() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1), d.plusDays(2), d.plusDays(3), d.plusDays(4));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        List<UUID> vs = visits(orderId);
        OffsetDateTime s = taipei(d, 9, 0);
        forceVisit(vs.get(0), "DONE", s, s.plusHours(2), "SITTER");
        forceVisit(vs.get(1), "DONE", s, s.plusHours(25), "SYSTEM");
        forceClosed(vs.get(2), s.plusDays(3));
        forceCancelled(vs.get(4), s);
        JsonNode r = visitList(orderId, ownerToken());
        assertThat(r.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(r.get("orderStatus").asString()).isEqualTo("IN_PROGRESS");
        JsonNode rows = r.get("visits");
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).get("status").asString()).isEqualTo("DONE");
        assertThat(rows.get(0).get("startedAt").asString()).isEqualTo(iso(s));
        assertThat(rows.get(0).get("completedAt").asString()).isEqualTo(iso(s.plusHours(2)));
        assertThat(rows.get(1).get("completionSource").asString()).isEqualTo("SYSTEM");
        assertThat(rows.get(2).get("status").asString()).isEqualTo("CLOSED_BY_SYSTEM");
        assertThat(rows.get(3).get("status").asString()).isEqualTo("PENDING");
        assertThat(rows.get(3).has("zombieAt")).isTrue();
        assertThat(rows.get(3).has("startedAt")).isFalse();
        assertThat(rows.get(4).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(rows.get(0).has("zombieAt")).isFalse();
    }

    @Test
    @DisplayName("PRD-008 AC-3.5 讀取越權：他人飼主 / 他人保母 403、不存在 404、保母以 OWNER 身分 ROLE_MISMATCH；ADMIN：DISPUTED 200、曾有已解決待辦 200、從未有待辦 403")
    void readAuthorization() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        mvc.perform(getAs("/api/orders/{o}/visits", strangerOwnerToken(), orderId)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(getAs("/api/orders/{o}/visits", strangerSitterToken(), orderId)).andExpect(status().isForbidden());
        mvc.perform(getAs("/api/orders/{o}/visits", sitterToken(), UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(getAs("/api/orders/{o}/visits", bearer(sitter, "OWNER"), orderId)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        mvc.perform(getAs("/api/orders/{o}/visits", adminToken(), orderId)).andExpect(status().isForbidden());
        setOrderStatus(orderId, "DISPUTED");
        mvc.perform(getAs("/api/orders/{o}/visits", adminToken(), orderId)).andExpect(status().isOk());
        mvc.perform(getAs(VISIT, adminToken(), orderId, visits(orderId).get(0))).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions").isEmpty());
        setOrderStatus(orderId, "COMPLETED");
        openAdminTask(orderId, "RESOLVED");
        mvc.perform(getAs("/api/orders/{o}/visits", adminToken(), orderId)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PRD-009 AC-1.2 / AC-1.8 / AC-2.2（行程端）：飼主只見 careLog.submitted 與 deadlineAt（不見草稿狀態），保母另見 status / expired")
    void careLogSummaryByRole() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        List<UUID> vs = visits(orderId);
        OffsetDateTime done = taipei(d, 11, 0);
        forceVisit(vs.get(0), "DONE", done.minusHours(1), done, "SITTER");
        forceVisit(vs.get(1), "DONE", done.minusHours(1), done, "SITTER");
        jdbc.update("INSERT INTO visit_service_reports (id, visit_id, status, content, version) VALUES (?, ?, 'DRAFT', '草稿', 0)", UUID.randomUUID(), vs.get(0));
        jdbc.update("INSERT INTO visit_service_reports (id, visit_id, status, content, submitted_at, version) VALUES (?, ?, 'SUBMITTED', 'ok', now(), 0)",
                UUID.randomUUID(), vs.get(1));
        freeze(done.plusHours(25));
        JsonNode ownerRows = visitList(orderId, ownerToken()).get("visits");
        assertThat(ownerRows.get(0).get("careLog").get("submitted").asBoolean()).isFalse();
        assertThat(ownerRows.get(0).get("careLog").has("status")).isFalse();
        assertThat(ownerRows.get(0).get("careLog").has("expired")).isFalse();
        assertThat(ownerRows.get(0).get("careLog").get("deadlineAt").asString()).isEqualTo(iso(done.plusHours(24)));
        assertThat(ownerRows.get(1).get("careLog").get("submitted").asBoolean()).isTrue();
        JsonNode sitterRows = visitList(orderId, sitterToken()).get("visits");
        assertThat(sitterRows.get(0).get("careLog").get("status").asString()).isEqualTo("DRAFT");
        assertThat(sitterRows.get(0).get("careLog").get("expired").asBoolean()).isTrue();
    }
}
