package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真 JWT + 真 SecurityConfig filter chain。 */
@DisplayName("SD-FULFILL ChecklistAuthorizationIntegrationTest（F9–F12）")
class ChecklistAuthorizationIntegrationTest extends FulfillIntegrationTestBase {

    @Test
    @DisplayName("PRD-028 AC-1.6 他人保母 403 FORBIDDEN；OWNER / ADMIN 403 ROLE_MISMATCH；以 V1 路徑刪 V2 項目 404；不存在行程不帶 key 404（先於 400）；皆不改資料")
    void authorization() throws Exception {
        defaultTasks(planA, "[\"a\",\"b\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        UUID item = itemIds(vs.get(0)).get(0);
        UUID item2 = itemIds(vs.get(1)).get(0);
        String other = strangerSitterToken();
        addItem(orderId, vs.get(0), other, key(), "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        deleteAs(VISIT + "/checklist/items/{i}", other, orderId, vs.get(0), item).andExpect(status().isForbidden());
        putJson(VISIT + "/checklist/order", other, Map.of("itemIds", List.of()), orderId, vs.get(0)).andExpect(status().isForbidden());
        check(orderId, vs.get(0), item, other, true).andExpect(status().isForbidden());
        addItem(orderId, vs.get(0), ownerToken(), key(), "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        addItem(orderId, vs.get(0), adminToken(), key(), "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        deleteAs(VISIT + "/checklist/items/{i}", sitterToken(), orderId, vs.get(0), item2).andExpect(status().isNotFound());
        check(orderId, vs.get(0), item2, sitterToken(), true).andExpect(status().isNotFound());
        addItem(orderId, UUID.randomUUID(), other, null, "x").andExpect(status().isNotFound());
        assertThat(itemIds(vs.get(0))).hasSize(2);
        assertThat(itemIds(vs.get(1))).hasSize(2);
    }
}
