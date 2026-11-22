package com.petsitter.infrastructure.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.CommonErrorCode;
import com.petsitter.application.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 全域限流（SD-GLOBAL-SPEC §2.8「限流」、NFR-003 SEC-INPUT-6、CQ-28 ~ 30）。
 * <ul>
 *   <li>位置：security filter chain 內、{@link JwtAuthenticationFilter} 之後、授權判斷之前；只套 {@code /api/**}，
 *       不套 {@code /api/internal/**} 與 {@code OPTIONS}。</li>
 *   <li>帶有效 token（{@link TokenContext} 有身分）→ 鍵 {@code U:{userId}}、每分鐘 300 次；
 *       否則 → 鍵 {@code IP:{來源 IP}}（{@link ClientIpResolver}）、每分鐘 60 次。</li>
 *   <li>每個鍵「首次請求起算」的 60 秒固定窗；時間取注入的 {@link Clock}；計數放本實例記憶體（Caffeine，
 *       {@code expireAfterWrite} 2 分鐘、{@code maximumSize} 100,000）。</li>
 *   <li>超限 → 429 {@code RATE_LIMITED} + {@code Retry-After}（窗結束剩餘秒數無條件進位，≥ 1），不進 controller；
 *       同一鍵每窗最多記 1 筆 WARN。</li>
 *   <li>匿名限流受 {@code app.security.rate-limit.anonymous-enforce} 控制：false（預設）時只計數並記 WARN、不回 429。</li>
 * </ul>
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    static final Duration WINDOW = Duration.ofSeconds(60);

    private final ClientIpResolver ipResolver;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final int userLimit;
    private final int anonymousLimit;
    private final boolean anonymousEnforce;
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(100_000)
            .build();

    public RateLimitFilter(ClientIpResolver ipResolver, Clock clock, JsonMapper jsonMapper,
                           int userLimit, int anonymousLimit, boolean anonymousEnforce) {
        this.ipResolver = ipResolver;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.userLimit = userLimit;
        this.anonymousLimit = anonymousLimit;
        this.anonymousEnforce = anonymousEnforce;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || !path.startsWith("/api/")
                || path.startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UUID> userId = TokenContext.tryGetUserId();
        String key;
        int limit;
        boolean enforce;
        if (userId.isPresent()) {
            key = "U:" + userId.get();
            limit = userLimit;
            enforce = true;
        } else {
            key = "IP:" + ipResolver.resolve(request);
            limit = anonymousLimit;
            enforce = anonymousEnforce;
        }
        long now = clock.millis();
        Decision d = hit(key, now, limit);
        if (d.exceeded()) {
            if (d.firstRejectionInWindow()) {
                log.warn("[RateLimit] limit exceeded traceId={} path={} keyType={} key={} enforced={}",
                        ApiErrors.traceIdOf(request), request.getRequestURI(), userId.isPresent() ? "USER" : "IP",
                        key.substring(key.indexOf(':') + 1), enforce);
            }
            if (enforce) {
                writeTooManyRequests(request, response, d.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    Decision hit(String key, long nowMillis, int limit) {
        Window w = windows.asMap().compute(key, (k, existing) -> {
            if (existing == null || nowMillis < existing.startMillis
                    || nowMillis >= existing.startMillis + WINDOW.toMillis()) {
                return new Window(nowMillis, 1);
            }
            return new Window(existing.startMillis, existing.count + 1);
        });
        if (w.count <= limit) {
            return new Decision(false, false, 0);
        }
        long remaining = w.startMillis + WINDOW.toMillis() - nowMillis;
        long seconds = Math.max(1, (remaining + 999) / 1000);
        return new Decision(true, w.count == limit + 1, seconds);
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response, long retryAfter)
            throws IOException {
        CommonErrorCode code = CommonErrorCode.RATE_LIMITED;
        ErrorResponse body = ApiErrors.build(request, code.name(), code.defaultMessage());
        response.setStatus(code.status().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }

    private record Window(long startMillis, int count) {
    }

    record Decision(boolean exceeded, boolean firstRejectionInWindow, long retryAfterSeconds) {
    }
}
