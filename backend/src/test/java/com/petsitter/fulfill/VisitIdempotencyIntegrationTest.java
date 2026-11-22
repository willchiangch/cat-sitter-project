package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL VisitIdempotencyIntegrationTest（F3 / F4 冪等）")
class VisitIdempotencyIntegrationTest extends FulfillIntegrationTestBase {

    @Test
    @DisplayName("PRD-008 AC-1.7 同 key 回放不重複 log；409 被記錄並回放；同 key 換行程 422；訂閱鎖定 FREE 開始 / 結束 / 命名 200")
    void replayAndLockedPlan() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        freeze(taipei(d, 9, 0));
        String k = key();
        tools.jackson.databind.JsonNode first = body(start(orderId, vs.get(0), sitterToken(), k).andExpect(status().isOk()));
        OffsetDateTime startedAt = jdbc.queryForObject("SELECT started_at FROM visits WHERE id = ?", OffsetDateTime.class, vs.get(0));
        freeze(taipei(d, 9, 5));
        assertThat(body(start(orderId, vs.get(0), sitterToken(), k).andExpect(status().isOk()))).isEqualTo(first);
        assertThat(logCount(orderId, "ORDER_STARTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT started_at FROM visits WHERE id = ?", OffsetDateTime.class, vs.get(0))).isEqualTo(startedAt);

        String k2 = key();
        start(orderId, vs.get(0), sitterToken(), k2).andExpect(status().isConflict()).andExpect(jsonPath("$.payload.currentVisitStatus").value("IN_PROGRESS"));
        start(orderId, vs.get(0), sitterToken(), k2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT"));
        start(orderId, vs.get(1), sitterToken(), k).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // 訂閱鎖定（FREE 且逾期）不擋：不用 @RequirePlan
        tier(sitter, "FREE");
        jdbc.update("UPDATE subscriptions SET expired_at = now() - interval '30 days' WHERE sitter_id = ?", sitter);
        complete(orderId, vs.get(0), sitterToken(), key()).andExpect(status().isOk());
        rename(orderId, vs.get(0), sitterToken(), "早上服務").andExpect(status().isOk());
        freeze(taipei(d.plusDays(1), 9, 0));
        start(orderId, vs.get(1), sitterToken(), key()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PRD-008 AC-1.7（EX-16）同 key 第一個請求處理中 → 409 IDEMPOTENCY_IN_PROGRESS（F3、F4）")
    void inProgress() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        UUID v = visits(orderId).get(0);
        freeze(taipei(d, 9, 0));
        String k = key();
        var hold = holdOrderLock(orderId, java.time.Duration.ofSeconds(2));
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return start(orderId, v, sitterToken(), k).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(500);
        start(orderId, v, sitterToken(), k).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        hold.join();
        assertThat(first.join()).isEqualTo(200);

        String k2 = key();
        var hold2 = holdOrderLock(orderId, java.time.Duration.ofSeconds(2));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return complete(orderId, v, sitterToken(), k2).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(500);
        complete(orderId, v, sitterToken(), k2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        hold2.join();
        assertThat(second.join()).isEqualTo(200);
    }
}
