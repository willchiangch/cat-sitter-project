package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SD-ORDER §10.4 測試支援端點（SD-ORDER §10.3 InternalTestDataIntegrationTest / E2eJourneyCleanupIntegrationTest）。 */
@DisplayName("§10.4 InternalTestDataIntegrationTest")
class InternalTestDataIntegrationTest extends OrderIntegrationTestBase {

    private static final String SECRET = "local-secret-123";

    private ResultActions internalPost(String path, Object body) throws Exception {
        return mvc.perform(post("/api/internal/test-data/" + path).header("X-Internal-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private String email(String role) {
        return "journey-t-" + role + "-" + UUID.randomUUID() + "@e2e-journey.test";
    }

    private UUID provision(String email, List<String> roles, Boolean sitterProfile) throws Exception {
        Map<String, Object> b = new java.util.HashMap<>(Map.of("email", email, "password", "Passw0rd!", "displayName", "測試", "roles", roles));
        if (sitterProfile != null) {
            b.put("sitterProfile", sitterProfile);
        }
        return UUID.fromString(body(internalPost("provision-account", b).andExpect(status().isCreated())).get("userId").asString());
    }

    @Test
    @DisplayName("§10.4 provision-account：roles[] 雙角色、sitterProfile=false、ADMIN；非測試網域 403；缺 secret 403")
    void provisionAccount() throws Exception {
        UUID dual = provision(email("dual"), List.of("OWNER", "SITTER"), null);
        assertThat(count("SELECT count(*) FROM profiles WHERE user_id = ?", dual)).isEqualTo(2);
        UUID noProfile = provision(email("np"), List.of("SITTER"), false);
        assertThat(count("SELECT count(*) FROM profiles WHERE user_id = ?", noProfile)).isZero();
        UUID adm = provision(email("admin"), List.of("ADMIN"), null);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, adm)).isEqualTo("ADMIN");
        internalPost("provision-account", Map.of("email", "x@gmail.com", "password", "p", "roles", List.of("OWNER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/test-data/provision-account").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("§10.4 sitter-setup / owner-setup upsert（依 key 逐項，未出現的欄位不變）→ 可走完整預約；inspect 只對測試帳號訂單、含 log / 通知 / payment 時間欄 / fulfill")
    void setupAndInspect() throws Exception {
        UUID s = provision(email("sitter"), List.of("SITTER"), null);
        UUID o = provision(email("owner"), List.of("OWNER"), null);
        JsonNode setup = body(internalPost("sitter-setup", Map.of("userId", s.toString(), "tier", "PRO", "kycStatus", "VERIFIED",
                "payoutAccount", Map.of("bankCode", "812", "branch", "b", "accountNumber", "123456", "accountName", "n"),
                "plans", List.of(Map.of("key", "p1", "title", "餵食", "unitPrice", 500)))).andExpect(status().isOk()));
        UUID plan = UUID.fromString(setup.get("plans").get(0).get("planId").asString());
        JsonNode again = body(internalPost("sitter-setup", Map.of("userId", s.toString(),
                "plans", List.of(Map.of("key", "p1", "unitPrice", 600)))).andExpect(status().isOk()));
        assertThat(again.get("plans").get(0).get("planId").asString()).isEqualTo(plan.toString());
        assertThat(jdbc.queryForMap("SELECT name, price FROM service_plans WHERE id = ?", plan))
                .containsEntry("name", "餵食").containsEntry("price", 600L);
        JsonNode owners = body(internalPost("owner-setup", Map.of("userId", o.toString(),
                "pets", List.of(Map.of("key", "cat", "name", "咪", "type", "CAT")), "favoriteFillerCount", 2)).andExpect(status().isOk()));
        UUID pet = UUID.fromString(owners.get("pets").get(0).get("petId").asString());
        assertThat(owners.get("fillerSitterIds").size()).isEqualTo(2);

        JsonNode created = body(mvc.perform(postJson("/api/orders", bearer(o, "OWNER"), key(), Map.of("sitterId", s.toString(),
                "groups", List.of(Map.of("planId", plan.toString(), "dates", List.of(today().plusDays(3).toString()),
                        "dailyCount", 1, "petIds", List.of(pet.toString()))), "expectedAmount", 600))).andExpect(status().isCreated()));
        UUID orderId = UUID.fromString(created.get("orderId").asString());
        JsonNode inspect = body(mvc.perform(get("/api/internal/test-data/orders/{id}/inspect", orderId).header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk()));
        assertThat(inspect.get("status").asString()).isEqualTo("PENDING");
        assertThat(inspect.get("orderLogs").get(0).get("actionType").asString()).isEqualTo("ORDER_CREATED");
        assertThat(inspect.get("notifications").get(0).get("type").asString()).isEqualTo("NEW_BOOKING_REQUEST");
        assertThat(inspect.get("fulfill").get("visits").size()).isEqualTo(1);
        UUID other = book(today().plusDays(3));
        mvc.perform(get("/api/internal/test-data/orders/{id}/inspect", other).header("X-Internal-Secret", SECRET)).andExpect(status().isNotFound());

        // shift-time 寫 TEST_TIME_SHIFTED 並平移
        mvc.perform(post("/api/internal/test-data/orders/{id}/shift-time", orderId).header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"REVIEW_EXPIRES_AT\",\"minutes\":-10000}"))
                .andExpect(status().isOk());
        assertThat(logActions(orderId)).contains("TEST_TIME_SHIFTED");
        mvc.perform(post("/api/internal/cron/orders/review-expiry?orderId=" + orderId).header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk());
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("E2eJourneyCleanupIntegrationTest：全套訂單資料（變更、ADMIN_ADJUSTMENT、待辦、submission）cleanup 後不違反 FK 且清空；手動帳號保留")
    void cleanup() throws Exception {
        jdbc.update("UPDATE users SET email = ? WHERE id = ?", email("owner"), owner);
        jdbc.update("UPDATE users SET email = ? WHERE id = ?", email("sitter"), sitter);
        UUID orderId = confirmedOrder(today().plusDays(5), today().plusDays(6));
        UUID v1 = visitIds(orderId, "PENDING").get(0);
        UUID mod = modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "x", "removeVisitIds", List.of(v1.toString()))));
        JsonNode r = body(mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, mod)));
        UUID refund = UUID.fromString(r.get("payment").get("paymentId").asString());
        submitProof(orderId, refund, sitterToken());
        mvc.perform(postJson("/api/orders/{o}/payment-disputes", ownerToken(), key(), Map.of("description", "x"), orderId)).andExpect(status().isOk());
        mvc.perform(postJson("/api/admin/orders/{o}/mediation", bearer(admin, "ADMIN"), key(),
                Map.of("refundDecisions", List.of(Map.of("paymentId", refund.toString(), "exported", true))), orderId)).andExpect(status().isOk());
        UUID manual = fx.user("journey-manual-keep@e2e-journey.test", "OWNER");
        mvc.perform(post("/api/internal/cron/test-data/cleanup-e2e-journeys").header("X-Internal-Secret", SECRET)).andExpect(status().isOk());
        for (String table : List.of("orders", "order_items", "order_item_pets", "payments", "payment_submissions", "modification_requests",
                "order_logs", "admin_tasks", "visits", "order_plan_snapshots", "order_snapshots")) {
            assertThat(count("SELECT count(*) FROM " + table)).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM users WHERE id IN (?, ?)", owner, sitter)).isZero();
        assertThat(count("SELECT count(*) FROM users WHERE id = ?", manual)).isEqualTo(1);
    }
}
