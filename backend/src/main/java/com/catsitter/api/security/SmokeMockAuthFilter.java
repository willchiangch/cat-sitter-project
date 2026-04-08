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
@Profile({"smoke", "uat"})
public class SmokeMockAuthFilter extends OncePerRequestFilter {

    private final AccountRepository accountRepository;

    public SmokeMockAuthFilter(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String mockUser = request.getHeader("X-Smoke-Auth");
        if (SecurityContextHolder.getContext().getAuthentication() == null && mockUser != null) {
            UUID accountId;
            
            System.out.println("SmokeMockAuthFilter triggered for user: " + mockUser);
            if ("JAMES".equalsIgnoreCase(mockUser) || "CLIENT".equalsIgnoreCase(mockUser)) {
                // James Wilson (Client)
                accountId = UUID.fromString("efefefef-0000-0000-0000-000000000002");
            } else if ("NEWBIE".equalsIgnoreCase(mockUser)) {
                // New User (No profiles yet) — aligned with V14 migration
                accountId = UUID.fromString("efefefef-0000-0000-0000-000000000003");
            } else if ("SOPHIA".equalsIgnoreCase(mockUser) || "SITTER".equalsIgnoreCase(mockUser)) {
                // Sophia (Sitter)
                accountId = UUID.fromString("efefefef-0000-0000-0000-000000000001");
            } else {
                System.out.println("Unknown mock user: " + mockUser);
                filterChain.doFilter(request, response);
                return;
            }

            Optional<Account> accountOpt = accountRepository.findById(accountId);
            
            if (accountOpt.isPresent()) {
                System.out.println("Authenticated smoke user: " + accountOpt.get().getEmail());
                Account account = accountOpt.get();
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        account, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                System.out.println("Failed to find account for UUID: " + accountId);
            }
        }
        
        filterChain.doFilter(request, response);
    }
}
