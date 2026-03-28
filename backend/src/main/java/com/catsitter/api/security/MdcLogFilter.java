package com.catsitter.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter to inject a correlation ID (traceId) into the MDC (Mapped Diagnostic Context) 
 * for improved log traceability across components within a single request.
 */
@Component
public class MdcLogFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // Generate a compact 12-char request ID
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Clean up to prevent context leak of threads in the pool
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
