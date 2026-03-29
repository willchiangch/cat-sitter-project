package com.catsitter.api.security;

import com.catsitter.api.entity.Account;
import com.catsitter.api.repository.AccountRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtService jwtService;
    private final AccountRepository accountRepository;

    @Value("${application.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    public OAuth2AuthenticationSuccessHandler(JwtService jwtService, AccountRepository accountRepository) {
        this.jwtService = jwtService;
        this.accountRepository = accountRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");

        if (email != null) {
            Optional<Account> accountOptional = accountRepository.findByEmail(email);
            if (accountOptional.isPresent()) {
                Account account = accountOptional.get();
                String jwt = jwtService.generateToken(account);
                
                String targetUrl = frontendUrl + "/login/callback?token=" + jwt;
                getRedirectStrategy().sendRedirect(request, response, targetUrl);
                return;
            }
        }

        // Fallback for missing email or account
        getRedirectStrategy().sendRedirect(request, response, frontendUrl + "/login?error=account_not_found");
    }
}
