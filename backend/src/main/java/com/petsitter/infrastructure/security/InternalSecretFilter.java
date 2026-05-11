package com.petsitter.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * 專用於校驗內部 Cron 呼叫的 Secret Header
 */
@Slf4j
@Component
public class InternalSecretFilter extends OncePerRequestFilter {

    @Value("${app.security.internal-secret}")
    private String internalSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String path = request.getRequestURI();
        log.debug("[InternalSecretFilter] Checking path: {}", path);
        
        if (path.startsWith("/api/internal/")) {
            String secret = request.getHeader("X-Internal-Secret");
            log.debug("[InternalSecretFilter] Header Secret: {}", secret);
            
            if (internalSecret.equals(secret)) {
                log.info("[InternalSecretFilter] Internal secret verified for path: {}", path);
                // 驗證成功，賦予內部專用權限
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        "INTERNAL_CRON", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_INTERNAL")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                log.warn("[InternalSecretFilter] Invalid internal secret for path: {}", path);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Internal Secret");
                return;
            }
        }
        
        filterChain.doFilter(request, response);
    }
}
