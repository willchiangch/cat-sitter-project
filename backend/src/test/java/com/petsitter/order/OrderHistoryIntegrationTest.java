package com.petsitter.order;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
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
    @DisplayName("SD-ORDER E3 / E4 serverMonth：注入 Clock 換算 Asia/Taipei 當下月份（UTC 仍在上月最後一天 16:00 起即為台北新月份），三個分頁籤皆回傳")
    void serverMonthFromInjectedClock() throws Exception {
        clock.freezeAt(java.time.Instant.parse("2027-01-31T15:59:59Z"));
        assertThat(list("/api/orders/owner", ownerToken(), "tab=HISTORY").get("serverMonth").asString()).isEqualTo("2027-01");
        clock.freezeAt(java.time.Instant.parse("2027-01-31T16:00:00Z"));
        for (String tab : java.util.List.of("EVALUATING", "ACTIVE", "HISTORY")) {
            assertThat(list("/api/orders/owner", ownerToken(), "tab=" + tab).get("serverMonth").asString()).as(tab).isEqualTo("2027-02");
            assertThat(list("/api/orders/sitter", sitterToken(), "tab=" + tab).get("serverMonth").asString()).as(tab).isEqualTo("2027-02");
        }
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

    @Autowired
    private EntityManagerFactory emf;

    private long statementsFor(String path, String token, String query) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            list(path, token, query);
            return stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    @Test
    @DisplayName("SD-ORDER E3 / E4 lastServiceDate：多筆訂單各自取最後有效行程日（排除 CANCELLED、無行程退回首日），行程日期整頁一次批次查詢，SQL 數不隨頁大小成長")
    void lastServiceDateBatched() throws Exception {
        LocalDate d = today();
        UUID a = book(d.plusDays(3), d.plusDays(5));
        UUID b = book(d.plusDays(4), d.plusDays(9));
        jdbc.update("UPDATE visits SET status = 'CANCELLED', cancelled_at = now() WHERE order_id = ? AND service_date = ?", b, d.plusDays(9));
        UUID c = fx.order(owner, sitter, "PENDING");
        LocalDate cFirst = jdbc.queryForObject("SELECT first_service_date FROM orders WHERE id = ?", LocalDate.class, c);
        assertThat(count("SELECT count(*) FROM visits WHERE order_id IN (?, ?)", a, b)).as("E1 已建立行程").isEqualTo(4);

        for (String[] call : new String[][]{{"/api/orders/owner", ownerToken()}, {"/api/orders/sitter", sitterToken()}}) {
            JsonNode content = list(call[0], call[1], "tab=EVALUATING").get("content");
            java.util.Map<String, String> last = new java.util.HashMap<>();
            content.forEach(n -> last.put(n.get("orderId").asString(), n.get("lastServiceDate").asString()));
            assertThat(last).as(call[0]).containsEntry(a.toString(), d.plusDays(5).toString())
                    .containsEntry(b.toString(), d.plusDays(4).toString())
                    .containsEntry(c.toString(), cFirst.toString());
        }

        // size=1 / size=2 都需另查 count；size=20 整頁塞得下時 Spring Data 省略 count，故只要求不多於 size=1
        long one = statementsFor("/api/orders/owner", ownerToken(), "tab=EVALUATING&size=1");
        long two = statementsFor("/api/orders/owner", ownerToken(), "tab=EVALUATING&size=2");
        long full = statementsFor("/api/orders/owner", ownerToken(), "tab=EVALUATING&size=20");
        assertThat(two).as("size=1 → %d 條、size=2 → %d 條".formatted(one, two)).isEqualTo(one);
        assertThat(full).as("size=20（3 筆）→ %d 條".formatted(full)).isLessThanOrEqualTo(one);
    }
}
