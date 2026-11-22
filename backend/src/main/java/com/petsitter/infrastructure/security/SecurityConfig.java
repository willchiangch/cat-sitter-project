package com.petsitter.infrastructure.security;

import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.CommonErrorCode;
import com.petsitter.application.exception.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalSecretFilter internalSecretFilter;
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;
    private final Environment environment;
    private final JsonMapper jsonMapper;

    @Value("${app.security.rate-limit.user-per-minute:300}")
    private int userPerMinute;
    @Value("${app.security.rate-limit.anonymous-per-minute:60}")
    private int anonymousPerMinute;
    @Value("${app.security.rate-limit.anonymous-enforce:false}")
    private boolean anonymousEnforce;
    @Value("${app.security.cors.allowed-origins:}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(this::configureHeaders)
            // SD-GLOBAL-SPEC §2.6 / SD-ORDER §8.3 D7：401 / 403 皆為 {code, message, traceId, timestamp} 格式
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) ->
                    writeError(request, response, CommonErrorCode.UNAUTHENTICATED))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    writeError(request, response, CommonErrorCode.FORBIDDEN))
            )
            .authorizeHttpRequests(auth -> {
                permitAnonymousEndpoints(auth);
                auth.requestMatchers(AnonymousEndpoints.INTERNAL_PATTERN).hasRole("INTERNAL")
                    .anyRequest().authenticated();
            });

        RateLimitFilter rateLimitFilter = new RateLimitFilter(clientIpResolver, clock, jsonMapper,
                userPerMinute, anonymousPerMinute, anonymousEnforce);
        http.addFilterBefore(internalSecretFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        // §2.8：JwtAuthenticationFilter 之後（才知道是否帶有效 token）、授權判斷之前
        http.addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * §3.4.1：只逐條放行 {@link AnonymousEndpoints}（單一來源）；不得對整個 {@code /api/auth/**} 或靜態檔路徑 permitAll。
     * 本方法是 {@code SecurityConfig} 內唯一呼叫 {@code permitAll()} 之處（{@code EndpointAccessRuleArchTest} 斷言）。
     */
    private void permitAnonymousEndpoints(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        for (AnonymousEndpoints.Entry entry : AnonymousEndpoints.active(environment)) {
            auth.requestMatchers(entry.matchers().toArray(RequestMatcher[]::new)).permitAll();
        }
    }

    /** §3.5：6 個 API 資安標頭，對所有經 filter chain 的回應生效；HSTS 不依賴 {@code request.isSecure()}。 */
    private void configureHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
            .contentTypeOptions(Customizer.withDefaults())
            .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
            .cacheControl(HeadersConfigurer.CacheControlConfig::disable)
            // Spring 預設 HSTS writer 只在 secure 請求輸出；Cloud Run 前端終結 TLS 且 forward-headers-strategy=none，
            // 改以靜態 writer 對所有請求輸出（CQ-23，不加 preload）
            .httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable)
            .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            .addHeaderWriter(new StaticHeadersWriter("Strict-Transport-Security", "max-age=31536000; includeSubDomains"))
            .addHeaderWriter(new StaticHeadersWriter("Cache-Control", "no-store"))
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(apiCspMatcher(),
                new StaticHeadersWriter("Content-Security-Policy", API_CSP)));
    }

    /** 非 prod 的 Swagger 路徑不套 API CSP（§3.5 備註），其餘一律套。 */
    private RequestMatcher apiCspMatcher() {
        if (environment.matchesProfiles("prod")) {
            return AnyRequestMatcher.INSTANCE;
        }
        PathPatternRequestMatcher.Builder b = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> swagger = AnonymousEndpoints.SWAGGER_PATTERNS.stream()
            .map(p -> (RequestMatcher) b.matcher(p)).toList();
        return new NegatedRequestMatcher(new OrRequestMatcher(swagger));
    }

    private void writeError(jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response,
                            CommonErrorCode code) throws java.io.IOException {
        ErrorResponse body = ApiErrors.build(request, code.name(), code.defaultMessage());
        response.setStatus(code.status().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }

    /**
     * §3.5 CORS：{@code app.security.cors.allowed-origins} 逐一列出，不得用 {@code *} 或 pattern；prod 缺值 → 啟動失敗。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = validatedOrigins(allowedOrigins, environment.matchesProfiles("prod"));
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Retry-After")); // 409 ORDER_BUSY、429 RATE_LIMITED
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    static List<String> validatedOrigins(List<String> configured, boolean prod) {
        List<String> origins = configured == null ? List.of()
            : configured.stream().filter(o -> o != null && !o.isBlank()).map(String::strip).toList();
        for (String o : origins) {
            if (o.contains("*")) {
                throw new IllegalStateException("app.security.cors.allowed-origins must not contain wildcards");
            }
        }
        if (prod && origins.isEmpty()) {
            throw new IllegalStateException("app.security.cors.allowed-origins is required in prod");
        }
        return origins;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
