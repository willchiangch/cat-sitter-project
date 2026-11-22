package com.petsitter.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * 匿名白名單的單一來源（SD-GLOBAL-SPEC §3.4.1、NFR-003 SEC-AZ-2.3）。{@link SecurityConfig} 與
 * {@code EndpointAccessRuleArchTest} / {@code AnonymousAccessIntegrationTest} 都讀這張表；變更須同步 §3.4.1 與 NFR-003。
 * <p>
 * 不在本表、不在 {@code /api/internal/**} 的路由一律 {@code authenticated()}（匿名 → 401 {@code UNAUTHENTICATED}）。
 */
public final class AnonymousEndpoints {

    /** 生效範圍。 */
    public enum Scope {
        /** 所有 profile。 */
        ALL,
        /** 只限 local profile（SD-FULFILL §8.2 模擬 GCS 直傳）。 */
        LOCAL_ONLY,
        /** 只限非 prod profile（§6.2 Swagger）。 */
        NON_PROD
    }

    /**
     * 一條白名單。{@code method == null} 表示任何方法。
     */
    public record Entry(HttpMethod method, List<String> patterns, Scope scope, String description) {

        public boolean appliesTo(Environment env) {
            return switch (scope) {
                case ALL -> true;
                case LOCAL_ONLY -> env.matchesProfiles("local");
                case NON_PROD -> !env.matchesProfiles("prod");
            };
        }

        public List<RequestMatcher> matchers() {
            PathPatternRequestMatcher.Builder b = PathPatternRequestMatcher.withDefaults();
            return patterns.stream().map(p -> (RequestMatcher) (method == null ? b.matcher(p) : b.matcher(method, p))).toList();
        }
    }

    /** §3.4.1 白名單，共 20 條（順序同文件）。 */
    public static final List<Entry> ENTRIES = List.of(
            new Entry(HttpMethod.OPTIONS, List.of("/**"), Scope.ALL, "CORS 預檢"),
            new Entry(HttpMethod.GET, List.of("/api/sitters/*/plans"), Scope.ALL, "公開方案"),
            new Entry(HttpMethod.GET, List.of("/api/sitters/*/questions"), Scope.ALL, "公開問卷題目"),
            new Entry(HttpMethod.GET, List.of("/api/sitters/*/booking-context"), Scope.ALL, "SD-ORDER E31"),
            new Entry(HttpMethod.GET, List.of("/api/sitter/profile/*"), Scope.ALL, "保母公開頁"),
            new Entry(HttpMethod.POST, List.of("/api/auth/login"), Scope.ALL, "密碼登入"),
            new Entry(HttpMethod.POST, List.of("/api/auth/register"), Scope.ALL, "註冊（寄 OTP）"),
            new Entry(HttpMethod.POST, List.of("/api/auth/register/verify-otp"), Scope.ALL, "驗證 OTP"),
            new Entry(HttpMethod.POST, List.of("/api/auth/register/resend-otp"), Scope.ALL, "重送 OTP"),
            new Entry(HttpMethod.POST, List.of("/api/auth/refresh"), Scope.ALL, "換發"),
            new Entry(HttpMethod.POST, List.of("/api/auth/logout"), Scope.ALL, "清除 __session（SD-IDENTITY 新增）"),
            new Entry(HttpMethod.POST, List.of("/api/auth/forgot-password"), Scope.ALL, "申請重設信"),
            new Entry(HttpMethod.POST, List.of("/api/auth/reset-password"), Scope.ALL, "以重設憑證改密碼"),
            new Entry(HttpMethod.POST, List.of("/api/auth/google"), Scope.ALL, "Google 登入"),
            new Entry(HttpMethod.POST, List.of("/api/auth/webauthn/login/options"), Scope.ALL, "WebAuthn 登入 challenge"),
            new Entry(HttpMethod.POST, List.of("/api/auth/webauthn/login/verify"), Scope.ALL, "WebAuthn 登入驗證"),
            new Entry(HttpMethod.GET, List.of("/actuator/health"), Scope.ALL, "健康檢查"),
            new Entry(null, List.of("/error"), Scope.ALL, "容器錯誤轉發"),
            new Entry(HttpMethod.PUT, List.of("/local-storage/**"), Scope.LOCAL_ONLY, "local 模擬 GCS 直傳"),
            new Entry(HttpMethod.GET, List.of("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"), Scope.NON_PROD, "Swagger"));

    /** 內部端點前綴（不在白名單，由 {@link InternalSecretFilter} 認證，§3.7）。 */
    public static final String INTERNAL_PATTERN = "/api/internal/**";

    /** Swagger 路徑（非 prod 不套 API CSP，§3.5）。 */
    public static final List<String> SWAGGER_PATTERNS = List.of("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**");

    private AnonymousEndpoints() {
    }

    public static List<Entry> active(Environment env) {
        return ENTRIES.stream().filter(e -> e.appliesTo(env)).toList();
    }

    /** 請求是否落在目前 profile 的白名單內。 */
    public static boolean matches(Environment env, HttpServletRequest request) {
        return active(env).stream().flatMap(e -> e.matchers().stream()).anyMatch(m -> m.matches(request));
    }
}
