package com.catsitter.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class DebugLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String path = request.getServletPath();
        String method = request.getMethod();
        String authHeader = request.getHeader("Authorization");
        
        filterChain.doFilter(request, response);
        
        int status = response.getStatus();
        if (status == 401) {
            System.out.println("[DEBUG-AUTH] 401 Unauthorized for " + method + " " + path);
            System.out.println("  - Auth Header: " + (authHeader != null ? (authHeader.substring(0, Math.min(authHeader.length(), 15)) + "...") : "MISSING"));
            System.out.println("  - Security Context Principal: " + SecurityContextHolder.getContext().getAuthentication());
        }
    }
}
