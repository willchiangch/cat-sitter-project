package com.catsitter.api.security;

import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.AccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * In 'smoke' profile, this filter auto-authenticates the request as the smoke test sitter
 * if no other authentication is present. This allows E2E tests to bypass registration/login.
 */
@Component
@Profile("smoke")
public class SmokeMockAuthFilter extends OncePerRequestFilter {

    private final AccountRepository accountRepository;

    public SmokeMockAuthFilter(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String mockUser = request.getHeader("X-Smoke-Auth");
            UUID accountId;
            
            if ("JAMES".equalsIgnoreCase(mockUser)) {
                // James Wilson (Client)
                accountId = UUID.fromString("efefefef-0000-0000-0000-000000000002");
            } else {
                // Default to Sitter 1 (Sophia)
                accountId = UUID.fromString("efefefef-0000-0000-0000-000000000001");
            }

            Optional<Account> accountOpt = accountRepository.findById(accountId);
            
            if (accountOpt.isPresent()) {
                Account account = accountOpt.get();
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        account, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        
        filterChain.doFilter(request, response);
    }
}
