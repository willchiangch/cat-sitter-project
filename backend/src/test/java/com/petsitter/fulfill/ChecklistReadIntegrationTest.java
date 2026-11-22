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

@DisplayName("SD-FULFILL ChecklistReadIntegrationTest（F1 checklist、F8）")
class ChecklistReadIntegrationTest extends FulfillIntegrationTestBase {

    @Test
    @DisplayName("PRD-028 AC-2.3 飼主唯讀檢視：F1 checklist.items[] 含打勾狀態（含 PENDING 行程）、無項目為空；F8 依 sort_order、含 createdSource")
    void ownerReadOnly() throws Exception {
        defaultTasks(planA, "[\"I1\",\"I2\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        List<UUID> vs = visits(orderId);
        OffsetDateTime t = taipei(d, 9, 0);
        forceVisit(vs.get(0), "IN_PROGRESS", t, null, null);
        jdbc.update("UPDATE visit_checklist_items SET is_completed = true, checked_at = now() WHERE id = ?", itemIds(vs.get(0)).get(0));
        jdbc.update("DELETE FROM visit_checklist_items WHERE visit_id = ?", vs.get(1));
        JsonNode rows = visitList(orderId, ownerToken()).get("visits");
        assertThat(rows.get(0).get("checklist").get("total").asInt()).isEqualTo(2);
        assertThat(rows.get(0).get("checklist").get("completed").asInt()).isEqualTo(1);
        assertThat(rows.get(0).get("checklist").get("items").get(0).get("isCompleted").asBoolean()).isTrue();
        assertThat(rows.get(0).get("checklist").get("items").get(1).get("isCompleted").asBoolean()).isFalse();
        assertThat(rows.get(1).get("checklist").get("items")).isEmpty();
        mvc.perform(getAs(VISIT + "/checklist", ownerToken(), orderId, vs.get(0))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("I1")).andExpect(jsonPath("$.items[0].createdSource").value("SYSTEM"))
                .andExpect(jsonPath("$.items[1].sortOrder").value(1));
    }

    @Test
    @DisplayName("PRD-028 AC-2.4 讀取越權：他人飼主 403、飼主呼叫打勾 / 新增 403 ROLE_MISMATCH、ADMIN DISPUTED 200 / 從未有待辦 403、不存在行程 404")
    void readAuthorization() throws Exception {
        defaultTasks(planA, "[\"I1\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        UUID v = visits(orderId).get(0);
        mvc.perform(getAs(VISIT + "/checklist", strangerOwnerToken(), orderId, v)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        check(orderId, v, itemIds(v).get(0), ownerToken(), true).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        addItem(orderId, v, ownerToken(), key(), "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        mvc.perform(getAs(VISIT + "/checklist", adminToken(), orderId, v)).andExpect(status().isForbidden());
        setOrderStatus(orderId, "DISPUTED");
        mvc.perform(getAs(VISIT + "/checklist", adminToken(), orderId, v)).andExpect(status().isOk());
        mvc.perform(getAs(VISIT + "/checklist", sitterToken(), orderId, UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
