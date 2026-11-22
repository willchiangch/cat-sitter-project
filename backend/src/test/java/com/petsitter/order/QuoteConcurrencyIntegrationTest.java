package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E6 併發（PRD-006 AC-2.6；SD-ORDER §10.3 QuoteConcurrencyIntegrationTest）。 */
@DisplayName("E6 QuoteConcurrencyIntegrationTest")
class QuoteConcurrencyIntegrationTest extends OrderIntegrationTestBase {

    @Test
    @DisplayName("PRD-006 AC-2.6 報價 vs 撤回兩執行緒真打：恰一方成功、另一方 409，order_logs 只有成功那筆")
    void quoteVsWithdraw() throws Exception {
        UUID orderId = book(today().plusDays(5));
        int[] codes = race(
                () -> mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), key(), Map.of("expectedAmount", 500), orderId)),
                () -> mvc.perform(postJson("/api/orders/{id}/withdraw", ownerToken(), key(), Map.of("expectedStatus", "PENDING"), orderId)));
        assertThat(Arrays.stream(codes).filter(c -> c == 200).count()).isEqualTo(1);
        assertThat(Arrays.stream(codes).filter(c -> c == 409).count()).isEqualTo(1);
        long transitions = logActions(orderId).stream().filter(a -> a.equals("ORDER_QUOTED") || a.equals("ORDER_WITHDRAWN")).count();
        assertThat(transitions).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-006 AC-2.6 訂單列被持有 > 3 秒 → 409 ORDER_BUSY + Retry-After: 1、資料不變、冪等紀錄刪除（同 key 重試可成功）")
    void lockTimeout() throws Exception {
        UUID orderId = book(today().plusDays(5));
        String k = key();
        CompletableFuture<Void> holder = holdOrderLock(orderId, Duration.ofSeconds(4));
        mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), k, Map.of("expectedAmount", 500), orderId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_BUSY"))
                .andExpect(header().string("Retry-After", "1"));
        holder.join();
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", k)).isZero();
        mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), k, Map.of("expectedAmount", 500), orderId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PRD-006 AC-2.6 同 key 重送報價 → 回放相同 body、payment 一筆；換 key → 409")
    void replay() throws Exception {
        UUID orderId = book(today().plusDays(5));
        String k = key();
        String first = mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), k, Map.of("expectedAmount", 500), orderId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), k, Map.of("expectedAmount", 500), orderId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", orderId)).isEqualTo(1);
        mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), key(), Map.of("expectedAmount", 500), orderId))
                .andExpect(status().isConflict());
    }
}
