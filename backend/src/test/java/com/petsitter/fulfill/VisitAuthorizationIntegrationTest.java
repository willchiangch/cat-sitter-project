package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真 JWT + 真 SecurityConfig filter chain（不用 @WithMockUser）。 */
@DisplayName("SD-FULFILL VisitAuthorizationIntegrationTest（F3 / F4 / F5）")
class VisitAuthorizationIntegrationTest extends FulfillIntegrationTestBase {

    @Test
    @DisplayName("PRD-008 AC-1.6 他人保母 403 FORBIDDEN、OWNER / ADMIN 403 ROLE_MISMATCH、不存在 404、缺 key 400、404 先於 400；皆不改資料")
    void authorization() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        UUID v = visits(orderId).get(0);
        freeze(taipei(d, 9, 0));
        String other = strangerSitterToken();
        start(orderId, v, other, key()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        complete(orderId, v, other, key()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        rename(orderId, v, other, "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        start(orderId, v, bearer(sitter, "OWNER"), key()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        rename(orderId, v, ownerToken(), "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        start(orderId, v, adminToken(), key()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        start(orderId, UUID.randomUUID(), sitterToken(), key()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        start(UUID.randomUUID(), v, sitterToken(), key()).andExpect(status().isNotFound());
        start(orderId, v, sitterToken(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.payload.field").value("Idempotency-Key"));
        start(orderId, UUID.randomUUID(), other, null).andExpect(status().isNotFound());
        start(orderId, v, null, key()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(visitStatus(v)).isEqualTo("PENDING");
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        start(orderId, v, sitterToken(), key()).andExpect(status().isOk());
    }
}
