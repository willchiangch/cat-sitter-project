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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL VisitRenameIntegrationTest（F5）")
class VisitRenameIntegrationTest extends FulfillIntegrationTestBase {

    /** planA × d × 每日 2 次（V1 第 1 次、V2 第 2 次）。 */
    private UUID twoSlots(LocalDate d) throws Exception {
        Map<String, Object> body = bookingBody(sitter, List.of(d), 2, 1000, petA);
        UUID orderId = UUID.fromString(body(mvc.perform(postJson("/api/orders", ownerToken(), key(), body))).get("orderId").asString());
        UUID pay = quote(orderId);
        verify(orderId, pay, submitProof(orderId, pay, ownerToken()), sitterToken());
        return orderId;
    }

    @Test
    @DisplayName("PRD-008 AC-3.6 命名 strip 後寫入、VISIT_RENAMED（舊 null / 新值）；空字串清除回預設「第 n 次」；每日 1 次無「第 n 次」；方案改名後仍用 O2 快照")
    void renameAndDefaults() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = twoSlots(d);
        List<UUID> vs = visits(orderId);
        UUID v1 = vs.get(0);
        UUID v2 = vs.get(1);
        rename(orderId, v2, sitterToken(), "  晚上服務  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("晚上服務")).andExpect(jsonPath("$.customName").value("晚上服務"));
        assertThat(jdbc.queryForMap("SELECT payload->>'oldName' AS o, payload->>'newName' AS n FROM order_logs WHERE order_id = ? AND action_type = 'VISIT_RENAMED'", orderId))
                .containsEntry("o", null).containsEntry("n", "晚上服務");
        JsonNode list = visitList(orderId, ownerToken());
        assertThat(list.get("visits").get(1).get("name").asString()).isEqualTo("晚上服務");
        assertThat(list.get("visits").get(0).get("name").asString()).isEqualTo("到府餵食 第 1 次");

        // O2 快照名稱：報價後保母把方案改名，未命名行程仍顯示快照名稱
        jdbc.update("UPDATE service_plans SET name = '貓咪到府' WHERE id = ?", planA);
        rename(orderId, v2, sitterToken(), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("到府餵食 第 2 次")).andExpect(jsonPath("$.customName").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT custom_name FROM visits WHERE id = ?", String.class, v2)).isNull();

        // 情境 2：DONE 且 completed_at + 24h 前可命名
        OffsetDateTime completedAt = taipei(d, 11, 0);
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        forceVisit(v1, "DONE", completedAt.minusHours(1), completedAt, "SITTER");
        freeze(completedAt.plusHours(24).minusMinutes(1));
        rename(orderId, v1, sitterToken(), "早上服務").andExpect(status().isOk());

        clock.reset();
        UUID single = confirmedOrder(d.plusDays(1));
        assertThat(visitList(single, sitterToken()).get("visits").get(0).get("name").asString()).isEqualTo("貓咪到府");
    }

    @Test
    @DisplayName("PRD-008 AC-3.6 報價前（PENDING 訂單）名稱用 booking_plan_title")
    void beforeQuoteUsesBookingTitle() throws Exception {
        UUID orderId = book(today().plusDays(2));
        jdbc.update("UPDATE service_plans SET name = '改名後' WHERE id = ?", planA);
        assertThat(visitList(orderId, sitterToken()).get("visits").get(0).get("name").asString()).isEqualTo("到府餵食");
    }

    @Test
    @DisplayName("PRD-008 AC-3.7 命名被拒：21 字 400（先於 409）、CLOSED / CANCELLED 409、receivedAt = 期限 409 DEADLINE_PASSED、COMPLETED 409（先於期限）、OWNER / 他人保母 403；皆不改資料")
    void renameRejected() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1), d.plusDays(2));
        List<UUID> vs = visits(orderId);
        String longName = "一".repeat(21);
        rename(orderId, vs.get(0), sitterToken(), "  " + longName + "  ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("name"));
        rename(orderId, vs.get(0), sitterToken(), Map.of("name", 123)).andExpect(status().isBadRequest());
        OffsetDateTime t = taipei(d, 9, 0);
        forceClosed(vs.get(1), t);
        forceCancelled(vs.get(2), t);
        rename(orderId, vs.get(1), sitterToken(), longName).andExpect(status().isBadRequest());
        rename(orderId, vs.get(1), sitterToken(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentVisitStatus").value("CLOSED_BY_SYSTEM"));
        rename(orderId, vs.get(2), sitterToken(), "x").andExpect(jsonPath("$.payload.currentVisitStatus").value("CANCELLED"));

        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        OffsetDateTime completedAt = taipei(d, 11, 0);
        forceVisit(vs.get(0), "DONE", completedAt.minusHours(1), completedAt, "SITTER");
        freeze(completedAt.plusHours(24));
        rename(orderId, vs.get(0), sitterToken(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEADLINE_PASSED"))
                .andExpect(jsonPath("$.payload.deadline").value("CARE_LOG_EDIT_ENDS_AT"))
                .andExpect(jsonPath("$.payload.deadlineAt").value(iso(completedAt.plusHours(24))));
        setOrderStatus(orderId, "COMPLETED");
        rename(orderId, vs.get(0), sitterToken(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentStatus").value("COMPLETED"));
        rename(orderId, vs.get(0), ownerToken(), "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        rename(orderId, vs.get(0), strangerSitterToken(), "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(count("SELECT count(*) FROM visits WHERE order_id = ? AND custom_name IS NOT NULL", orderId)).isZero();
        assertThat(logCount(orderId, "VISIT_RENAMED")).isZero();
    }
}
