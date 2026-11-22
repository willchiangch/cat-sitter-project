package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SD-GLOBAL-SPEC §2.8「限流」演算法：首次請求起算的 60 秒固定窗、Retry-After 無條件進位 ≥ 1、鍵互不影響。 */
@DisplayName("NFR-003 SEC-INPUT-6 RateLimitFilterTest")
class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(new ClientIpResolver(List.of()), Clock.systemUTC(),
            JsonMapper.builder().build(), 300, 60, true);

    @Test
    @DisplayName("§2.8 帶 token 的鍵每分鐘 300 次：第 301 次起拒絕，Retry-After = 窗結束剩餘秒數無條件進位")
    void userLimit() {
        long t0 = 1_000_000L;
        for (int i = 1; i <= 300; i++) {
            assertThat(filter.hit("U:a", t0 + i, 300).exceeded()).isFalse();
        }
        RateLimitFilter.Decision d = filter.hit("U:a", t0 + 59_000L + 1, 300);
        assertThat(d.exceeded()).isTrue();
        assertThat(d.firstRejectionInWindow()).isTrue();
        assertThat(d.retryAfterSeconds()).isEqualTo(1);
        RateLimitFilter.Decision d2 = filter.hit("U:a", t0 + 10_500L, 300);
        assertThat(d2.firstRejectionInWindow()).isFalse();
        assertThat(d2.retryAfterSeconds()).isEqualTo(50); // 49.5 秒 → 50
        // 窗結束後歸零
        assertThat(filter.hit("U:a", t0 + 60_001L, 300).exceeded()).isFalse();
    }

    @Test
    @DisplayName("§2.8 匿名鍵每分鐘 60 次；不同鍵互不影響；時鐘倒退視為新窗")
    void anonymousAndIsolation() {
        long t0 = 5_000_000L;
        for (int i = 0; i < 60; i++) {
            filter.hit("IP:1.1.1.1", t0, 60);
        }
        assertThat(filter.hit("IP:1.1.1.1", t0, 60).exceeded()).isTrue();
        assertThat(filter.hit("IP:2.2.2.2", t0, 60).exceeded()).isFalse();
        assertThat(filter.hit("U:b", t0, 300).exceeded()).isFalse();
        assertThat(filter.hit("IP:1.1.1.1", t0 - 1, 60).exceeded()).isFalse();
    }
}
