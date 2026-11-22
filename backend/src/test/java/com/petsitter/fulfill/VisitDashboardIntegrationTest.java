package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL VisitDashboardIntegrationTest（F6 / F7）")
class VisitDashboardIntegrationTest extends FulfillIntegrationTestBase {

    private UUID twoGroups(LocalDate d) throws Exception {
        Map<String, Object> body = Map.of("sitterId", sitter.toString(), "expectedAmount", 500 * 2 + 800,
                "groups", List.of(
                        Map.of("planId", planA.toString(), "dates", List.of(d.toString()), "dailyCount", 2, "petIds", List.of(petA.toString())),
                        Map.of("planId", planB.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of(petB.toString()))));
        UUID orderId = UUID.fromString(body(mvc.perform(postJson("/api/orders", ownerToken(), key(), body))).get("orderId").asString());
        UUID pay = quote(orderId);
        verify(orderId, pay, submitProof(orderId, pay, ownerToken()), sitterToken());
        return orderId;
    }

    private static List<String> ids(JsonNode cards) {
        List<String> out = new ArrayList<>();
        cards.forEach(c -> out.add(c.get("visitId").asString()));
        return out;
    }

    @Test
    @DisplayName("PRD-008 AC-3.1 今日行程：訂單建立時間 → 群組 → 第 n 次排序；預設名稱「第 n 次」；排除 PENDING_PAYMENT / CANCELLED；含飼主與毛孩；他人行程不出現（AC-3.5 情境 5）")
    void sitterToday() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID y = confirmedOrder(d);
        UUID x = twoGroups(d);
        UUID z = book(d);
        quote(z);
        List<UUID> xs = visits(x);
        UUID v1 = jdbc.queryForObject("SELECT id FROM visits WHERE order_id = ? AND plan_id = ? AND sequence_in_day = 1", UUID.class, x, planA);
        UUID v2 = jdbc.queryForObject("SELECT id FROM visits WHERE order_id = ? AND plan_id = ? AND sequence_in_day = 2", UUID.class, x, planA);
        UUID v5 = jdbc.queryForObject("SELECT id FROM visits WHERE order_id = ? AND plan_id = ?", UUID.class, x, planB);
        assertThat(xs).hasSize(3);
        UUID v3 = visits(y).get(0);
        OffsetDateTime at = taipei(d, 9, 0);
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", x);
        forceVisit(v1, "DONE", at, at.plusHours(1), "SITTER");
        forceCancelled(v5, at);
        // 另一保母的行程不得出現
        UUID otherSitter = fx.user("s3-" + UUID.randomUUID() + "@test.com", "SITTER");
        freeze(at.plusHours(2));
        JsonNode r = body(mvc.perform(getAs("/api/visits/sitter/dashboard", sitterToken())).andExpect(status().isOk()));
        assertThat(ids(r.get("today"))).containsExactly(v3.toString(), v1.toString(), v2.toString());
        assertThat(r.get("today").get(1).get("name").asString()).isEqualTo("到府餵食 第 1 次");
        assertThat(r.get("today").get(1).get("status").asString()).isEqualTo("DONE");
        assertThat(r.get("today").get(2).get("name").asString()).isEqualTo("到府餵食 第 2 次");
        assertThat(r.get("today").get(0).get("counterpart").get("userId").asString()).isEqualTo(owner.toString());
        assertThat(r.get("today").get(0).get("pets").get(0).get("name").asString()).isEqualTo("咪咪");
        assertThat(r.get("overdue")).isEmpty();
        JsonNode empty = body(mvc.perform(getAs("/api/visits/sitter/dashboard", bearer(otherSitter, "SITTER"))).andExpect(status().isOk()));
        assertThat(empty.get("today")).isEmpty();
        assertThat(empty.get("overdue")).isEmpty();
    }

    @Test
    @DisplayName("PRD-008 AC-3.2 待處理：過去日期 PENDING / IN_PROGRESS（不含 DONE）、zombieAt；過了 VISIT_ZOMBIE_AT 未關閉 → zombieDeadlinePassed；關閉後消失")
    void overdue() throws Exception {
        LocalDate a = today().plusDays(1);
        Map<String, Object> body = bookingBody(sitter, List.of(a, a.plusDays(1)), 2, 2000, petA);
        UUID orderId = UUID.fromString(body(mvc.perform(postJson("/api/orders", ownerToken(), key(), body))).get("orderId").asString());
        UUID pay = quote(orderId);
        verify(orderId, pay, submitProof(orderId, pay, ownerToken()), sitterToken());
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        List<UUID> vs = visits(orderId); // a#1, a#2, a+1#1, a+1#2
        OffsetDateTime t = taipei(a.plusDays(1), 9, 0);
        forceCancelled(vs.get(1), t);
        forceVisit(vs.get(2), "IN_PROGRESS", t, null, null);
        forceVisit(vs.get(3), "DONE", t, t.plusHours(1), "SITTER");
        freeze(taipei(a.plusDays(2), 10, 0));
        JsonNode r = body(mvc.perform(getAs("/api/visits/sitter/dashboard", sitterToken())).andExpect(status().isOk()));
        assertThat(ids(r.get("overdue"))).containsExactly(vs.get(0).toString(), vs.get(2).toString());
        JsonNode v6 = r.get("overdue").get(0);
        assertThat(v6.get("zombieAt").asString()).isEqualTo(iso(taipei(a, 0, 0).plusHours(72)));
        assertThat(v6.get("zombieDeadlinePassed").asBoolean()).isFalse();
        assertThat(r.get("overdue").get(1).has("zombieAt")).isFalse();
        freeze(taipei(a, 0, 0).plusHours(72));
        r = body(mvc.perform(getAs("/api/visits/sitter/dashboard", sitterToken())));
        assertThat(r.get("overdue").get(0).get("zombieDeadlinePassed").asBoolean()).isTrue();
        forceClosed(vs.get(0), OffsetDateTime.now(clock));
        r = body(mvc.perform(getAs("/api/visits/sitter/dashboard", sitterToken())));
        assertThat(ids(r.get("overdue"))).containsExactly(vs.get(2).toString());
    }

    @Test
    @DisplayName("PRD-008 AC-3.4 飼主今日到府：只含今天、非 PENDING_PAYMENT 訂單；顯示保母；無行程時空陣列")
    void ownerToday() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID x = confirmedOrder(d, d.plusDays(1));
        UUID y = book(d);
        quote(y);
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", x);
        List<UUID> xs = visits(x);
        forceVisit(xs.get(0), "IN_PROGRESS", taipei(d, 9, 0), null, null);
        freeze(taipei(d, 10, 0));
        JsonNode r = body(mvc.perform(getAs("/api/visits/owner/today", ownerToken())).andExpect(status().isOk()));
        assertThat(ids(r.get("today"))).containsExactly(xs.get(0).toString());
        assertThat(r.get("today").get(0).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(r.get("today").get(0).get("counterpart").get("userId").asString()).isEqualTo(sitter.toString());
        assertThat(body(mvc.perform(getAs("/api/visits/owner/today", strangerOwnerToken()))).get("today")).isEmpty();
        mvc.perform(getAs("/api/visits/owner/today", sitterToken())).andExpect(status().isForbidden());
        mvc.perform(getAs("/api/visits/sitter/dashboard", ownerToken())).andExpect(status().isForbidden());
    }
}
