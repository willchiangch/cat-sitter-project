package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 越權矩陣（SD-ORDER §10.3 OrderIdentitySecurityTest）：真 JWT + 真 filter chain。每支端點：未登入 401、錯角色 403
 * ROLE_MISMATCH、他人 403 FORBIDDEN、不存在 404、混合角色端點當事人錯角色 403 ROLE_MISMATCH（G4a）、本人成功
 * （body 夾帶亂填的身分欄位被忽略）。
 */
@DisplayName("OrderIdentitySecurityTest")
class OrderIdentitySecurityTest extends OrderIntegrationTestBase {

    /** (token, orderId) → request。 */
    private record Endpoint(String name, String role, BiFunction<String, UUID, MockHttpServletRequestBuilder> req) {
    }

    private void assertMatrix(Endpoint e, UUID orderId, String partyWrongRoleToken) throws Exception {
        String wrongRole = "OWNER".equals(e.role()) ? bearer(owner, "SITTER") : bearer(sitter, "ADMIN");
        mvc.perform(e.req().apply(null, orderId)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(e.req().apply(wrongRole, orderId)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        String strangerToken = "OWNER".equals(e.role()) ? bearer(stranger, "OWNER") : bearer(fx.user("x-" + UUID.randomUUID() + "@t.test", "SITTER"), "SITTER");
        mvc.perform(e.req().apply(strangerToken, orderId)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        String self = "OWNER".equals(e.role()) ? ownerToken() : sitterToken();
        mvc.perform(e.req().apply(self, UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        if (partyWrongRoleToken != null) {
            mvc.perform(e.req().apply(partyWrongRoleToken, orderId)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        }
    }

    @Test
    @DisplayName("PRD-006 AC-1.4 / AC-3.1、PRD-026 AC-1.6、PRD-027 AC-1.3 / AC-3.4：E2 / E5 / E6 / E7 / E8 / E9 越權矩陣")
    void orderEndpoints() throws Exception {
        UUID orderId = book(today().plusDays(5));
        List<Endpoint> eps = List.of(
                new Endpoint("E2", "OWNER", (t, id) -> getAs("/api/orders/{id}", t, id)),
                new Endpoint("E5", "SITTER", (t, id) -> getAs("/api/orders/{id}/evaluation", t, id)),
                new Endpoint("E6", "SITTER", (t, id) -> postJson("/api/orders/{id}/quote", t, key(), Map.of("expectedAmount", 500), id)),
                new Endpoint("E7", "SITTER", (t, id) -> postJson("/api/orders/{id}/decline", t, key(), Map.of("reason", "x"), id)),
                new Endpoint("E8", "OWNER", (t, id) -> postJson("/api/orders/{id}/withdraw", t, key(), Map.of("expectedStatus", "PENDING"), id)),
                new Endpoint("E9", "SITTER", (t, id) -> postJson("/api/orders/{id}/sitter-cancel", t, key(), Map.of("reason", "x"), id)));
        for (Endpoint e : eps) {
            // E2 為混合角色：該單保母以 OWNER token → ROLE_MISMATCH（G4a）
            assertMatrix(e, orderId, "E2".equals(e.name()) ? bearer(sitter, "OWNER") : null);
        }
        // 本人成功（E2 兩種角色）
        mvc.perform(getAs("/api/orders/{id}", ownerToken(), orderId)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        mvc.perform(getAs("/api/orders/{id}", sitterToken(), orderId)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("SITTER"));
    }

    @Test
    @DisplayName("PRD-007 AC-1.3 / AC-2.5：E10–E15 越權矩陣與 G4a（該單保母以 OWNER token 上傳 / 確認 → ROLE_MISMATCH）；子資源不屬路徑訂單 404")
    void paymentEndpoints() throws Exception {
        UUID orderId = book(today().plusDays(5));
        UUID paymentId = quote(orderId);
        UUID sub = submitProof(orderId, paymentId, ownerToken());
        UUID otherOrder = book(today().plusDays(6));
        String wrongPartyRole = bearer(sitter, "OWNER");
        mvc.perform(getAs("/api/orders/{o}/payments", null, orderId)).andExpect(status().isUnauthorized());
        mvc.perform(getAs("/api/orders/{o}/payments", bearer(stranger, "OWNER"), orderId)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(getAs("/api/orders/{o}/payments", wrongPartyRole, orderId)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        mvc.perform(getAs("/api/orders/{o}/payments/{p}/submissions", ownerToken(), otherOrder, paymentId)).andExpect(status().isNotFound());
        mvc.perform(getAs("/api/orders/{o}/payments/{p}/submissions/{s}/proof-url", ownerToken(), orderId, paymentId, UUID.randomUUID()))
                .andExpect(status().isNotFound());
        verify(orderId, paymentId, sub, wrongPartyRole).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        upload(orderId, paymentId, wrongPartyRole, key(), PNG).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        mvc.perform(postJson("/api/orders/{o}/payments/{p}/verify", bearer(admin, "ADMIN"), key(), Map.of("submissionId", sub.toString()), orderId, paymentId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        mvc.perform(postJson("/api/orders/{o}/payments/{p}/verify", sitterToken(), key(), Map.of("submissionId", sub.toString()), otherOrder, paymentId))
                .andExpect(status().isNotFound());
        verify(orderId, paymentId, sub, sitterToken()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PRD-005 AC-1.8：E1 body 夾帶 ownerId 等身分欄位被忽略（身分取自 token）")
    void identityFieldsIgnored() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(bookingBody(sitter, List.of(today().plusDays(3)), 1, 500, petA));
        body.put("ownerId", stranger.toString());
        JsonNode r = body(mvc.perform(postJson("/api/orders", ownerToken(), key(), body)).andExpect(status().isCreated()));
        assertThat(jdbc.queryForObject("SELECT owner_id FROM orders WHERE id = ?", UUID.class, UUID.fromString(r.get("orderId").asString())))
                .isEqualTo(owner);
    }
}
