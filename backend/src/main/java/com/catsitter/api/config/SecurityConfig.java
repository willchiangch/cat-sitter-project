package com.catsitter.api.config;

import com.catsitter.api.security.JwtAuthenticationFilter;
import com.catsitter.api.security.MdcLogFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthFilter;
  private final MdcLogFilter mdcLogFilter;
  private final UserDetailsService userDetailsService;
  private final com.catsitter.api.security.CustomOAuth2UserService customOAuth2UserService;
  private final com.catsitter.api.security.OAuth2AuthenticationSuccessHandler oauth2SuccessHandler;
  private final com.catsitter.api.security.SmokeMockAuthFilter smokeMockAuthFilter;

  private final org.springframework.core.env.Environment env;

  public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter, 
                        MdcLogFilter mdcLogFilter,
                        UserDetailsService userDetailsService,
                        org.springframework.core.env.Environment env,
                        com.catsitter.api.security.CustomOAuth2UserService customOAuth2UserService,
                        com.catsitter.api.security.OAuth2AuthenticationSuccessHandler oauth2SuccessHandler,
                        @org.springframework.beans.factory.annotation.Autowired(required = false) com.catsitter.api.security.SmokeMockAuthFilter smokeMockAuthFilter) {
    this.jwtAuthFilter = jwtAuthFilter;
    this.mdcLogFilter = mdcLogFilter;
    this.userDetailsService = userDetailsService;
    this.env = env;
    this.customOAuth2UserService = customOAuth2UserService;
    this.oauth2SuccessHandler = oauth2SuccessHandler;
    this.smokeMockAuthFilter = smokeMockAuthFilter;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    boolean isSmoke = java.util.Arrays.asList(env.getActiveProfiles()).contains("smoke");

    http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
      .authorizeHttpRequests(req -> {
              req.requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                       .requestMatchers("/api/v1/sitters/{slug}/availability/public").permitAll()
                       .requestMatchers("/api/v1/sitters/{slug}/booking-preview").permitAll()
                       .requestMatchers("/api/v1/calendar/feed/**").permitAll()
                       .requestMatchers("/api/v1/payments/payuni/webhook").permitAll()
                       .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                       .requestMatchers("/api/v1/auth/me").authenticated()
                       .anyRequest().authenticated();
      })
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
          response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
          response.setContentType("application/json");
          response.getWriter().write("{\"error\": \"Unauthorized\", \"message\": \"" + authException.getMessage() + "\"}");
      }))
      .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(oauth2SuccessHandler)
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(mdcLogFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    if (smokeMockAuthFilter != null) {
        http.addFilterAfter(smokeMockAuthFilter, JwtAuthenticationFilter.class);
    }

    return http.build();
  }

  @Bean
  public AuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
    authProvider.setUserDetailsService(userDetailsService);
    authProvider.setPasswordEncoder(passwordEncoder());
    return authProvider;
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(java.util.List.of("http://localhost:5173", "http://localhost:4173"));
    configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
    configuration.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "X-Requested-With", "X-Smoke-Auth"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
