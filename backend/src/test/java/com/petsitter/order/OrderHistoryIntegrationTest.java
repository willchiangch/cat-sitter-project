package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E3 / E4 列表（PRD-009 AC-3.6 / AC-3.7；SD-ORDER §10.3 OrderHistoryIntegrationTest）。 */
@DisplayName("E3/E4 OrderHistoryIntegrationTest")
class OrderHistoryIntegrationTest extends OrderIntegrationTestBase {

    private JsonNode list(String path, String token, String query) throws Exception {
        return body(mvc.perform(getAs(path + "?" + query, token)).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("PRD-009 AC-3.6 分頁籤歸屬：EVALUATING / ACTIVE / HISTORY 只含本人訂單，created_at 倒序，每批 20 筆可載入更多")
    void tabsAndPaging() throws Exception {
        UUID confirmed = confirmedOrder(today().plusDays(4));
        // PRD-005 BR-26：同一飼主對同一保母同時 PENDING 上限 3 張，API 只建 2 張，其餘以 fixture 直接寫入
        for (int i = 0; i < 2; i++) {
            book(today().plusDays(3));
        }
        for (int i = 0; i < 20; i++) {
            fx.order(owner, sitter, "PENDING");
        }
        fx.order(stranger, sitter, "PENDING");
        JsonNode eval = list("/api/orders/owner", ownerToken(), "tab=EVALUATING");
        assertThat(eval.get("content").size()).isEqualTo(20);
        assertThat(eval.get("totalElements").asLong()).isEqualTo(22);
        assertThat(list("/api/orders/owner", ownerToken(), "tab=EVALUATING&page=1").get("content").size()).isEqualTo(2);
        JsonNode active = list("/api/orders/owner", ownerToken(), "tab=ACTIVE");
        assertThat(active.get("content").get(0).get("orderId").asString()).isEqualTo(confirmed.toString());
        assertThat(active.get("content").get(0).get("initialPaymentStatus").asString()).isEqualTo("VERIFIED");
        assertThat(list("/api/orders/sitter", sitterToken(), "tab=EVALUATING").get("totalElements").asLong()).isEqualTo(23);
        mvc.perform(getAs("/api/orders/owner?tab=NOPE", ownerToken())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("tab"));
        // SD-GLOBAL §2.8：共用 PageParams，400 帶 field=size、max
        mvc.perform(getAs("/api/orders/owner?tab=ACTIVE&size=51", ownerToken())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.payload.field").value("size")).andExpect(jsonPath("$.payload.max").value(50));
        mvc.perform(getAs("/api/orders/sitter?tab=ACTIVE&size=51", sitterToken())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.max").value(50));
        mvc.perform(getAs("/api/orders/owner?tab=ACTIVE&page=-1", ownerToken())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("page"));
        mvc.perform(getAs("/api/orders/owner?tab=ACTIVE", sitterToken())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
    }

    @Test
    @DisplayName("PRD-009 AC-3.7 歷史：status / month（終態時間 Asia/Taipei 月）篩選、terminalAt 新到舊、netPaid、空結果")
    void historyFilters() throws Exception {
        UUID withdrawn = book(today().plusDays(3));
        mvc.perform(postJson("/api/orders/{id}/withdraw", ownerToken(), key(), java.util.Map.of("expectedStatus", "PENDING"), withdrawn))
                .andExpect(status().isOk());
        UUID completed = fx.order(owner, sitter, "COMPLETED");
        jdbc.update("UPDATE orders SET completed_at = ? WHERE id = ?", OffsetDateTime.now(clock).minusMonths(2), completed);
        JsonNode all = list("/api/orders/owner", ownerToken(), "tab=HISTORY");
        assertThat(all.get("content").size()).isEqualTo(2);
        assertThat(all.get("content").get(0).get("orderId").asString()).isEqualTo(withdrawn.toString());
        assertThat(all.get("content").get(0).get("netPaid").asInt()).isZero();
        assertThat(all.get("content").get(0).get("terminalAt")).isNotNull();
        assertThat(list("/api/orders/owner", ownerToken(), "tab=HISTORY&status=COMPLETED").get("content").size()).isEqualTo(1);
        String month = OffsetDateTime.now(clock).atZoneSameInstant(TAIPEI).toLocalDate().toString().substring(0, 7);
        JsonNode thisMonth = list("/api/orders/owner", ownerToken(), "tab=HISTORY&month=" + month);
        assertThat(thisMonth.get("content").size()).isEqualTo(1);
        assertThat(list("/api/orders/owner", ownerToken(), "tab=HISTORY&month=2001-01").get("content").size()).isZero();
        mvc.perform(getAs("/api/orders/owner?tab=HISTORY&month=2026-13", ownerToken())).andExpect(status().isBadRequest());
        mvc.perform(getAs("/api/orders/owner?tab=HISTORY&status=PENDING", ownerToken())).andExpect(status().isBadRequest());
    }
}
