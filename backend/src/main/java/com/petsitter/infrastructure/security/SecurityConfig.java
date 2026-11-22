package com.petsitter.infrastructure.security;

import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.CommonErrorCode;
import com.petsitter.application.exception.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalSecretFilter internalSecretFilter;
    private final Environment environment;
    private final JsonMapper jsonMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Swagger UI / OpenAPI 規格書只在非 prod 環境匿名開放，避免正式環境的完整 API 規格公開暴露給任何人
        boolean isProd = environment.matchesProfiles("prod");

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // SD-GLOBAL-SPEC §2.6 / SD-ORDER §8.3 D7：401 / 403 皆為 {code, message, traceId, timestamp} 格式
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) ->
                    writeError(request, response, CommonErrorCode.UNAUTHENTICATED))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    writeError(request, response, CommonErrorCode.FORBIDDEN))
            )
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**", "/static/**", "/*.js", "/*.css", "/*.png", "/*.svg", "/*.ico").permitAll()
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll();
                if (!isProd) {
                    auth.requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll();
                }
                auth.requestMatchers(HttpMethod.GET, "/api/sitters/*/plans").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/sitters/*/questions").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/sitter/profile/*").permitAll()
                    .requestMatchers("/api/internal/**").hasRole("INTERNAL")
                    .requestMatchers("/error").permitAll()
                    .anyRequest().authenticated();
            });

        http.addFilterBefore(internalSecretFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response,
                            CommonErrorCode code) throws java.io.IOException {
        ErrorResponse body = ApiErrors.build(request, code.name(), code.defaultMessage());
        response.setStatus(code.status().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*")); // 生產環境應限制來源
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("authorization", "content-type", "x-auth-token", "idempotency-key"));
        configuration.setExposedHeaders(List.of("x-auth-token", "retry-after")); // retry-after：409 ORDER_BUSY（SD-ORDER §6.1）
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
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
