package com.catsitter.api.security;

import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.enums.AccountStatus;
import com.catsitter.api.entity.enums.OAuthProvider;
import com.catsitter.api.repository.AccountRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final AccountRepository accountRepository;

    public CustomOAuth2UserService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        
        return processOAuth2User(registrationId, oAuth2User);
    }

    private OAuth2User processOAuth2User(String registrationId, OAuth2User oAuth2User) {
        Map<String, Object> attributes = oAuth2User.getAttributes();
        String email = (String) attributes.get("email");
        String oauthId;
        OAuthProvider provider;

        if ("google".equalsIgnoreCase(registrationId)) {
            oauthId = (String) attributes.get("sub");
            provider = OAuthProvider.GOOGLE;
        } else if ("facebook".equalsIgnoreCase(registrationId)) {
            oauthId = (String) attributes.get("id");
            provider = OAuthProvider.FACEBOOK;
        } else if ("apple".equalsIgnoreCase(registrationId)) {
            oauthId = (String) attributes.get("sub");
            provider = OAuthProvider.APPLE;
        } else {
            throw new OAuth2AuthenticationException("Unsupported provider: " + registrationId);
        }

        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException("Email not found from OAuth2 provider");
        }

        Optional<Account> accountOptional = accountRepository.findByEmail(email);
        Account account;

        if (accountOptional.isPresent()) {
            account = accountOptional.get();
            // Update existing account with new OAuth info if not set
            if (account.getOauthProvider() == OAuthProvider.LOCAL) {
                account.setOauthProvider(provider);
                account.setOauthId(oauthId);
            } else if (account.getOauthProvider() != provider) {
                // Potential issue: same email different providers. 
                // For now, we allow linking or we could throw error. 
                // Let's update to the most recent provider if it's the same email.
                account.setOauthProvider(provider);
                account.setOauthId(oauthId);
            }
        } else {
            // Register new account
            account = new Account();
            account.setEmail(email);
            account.setOauthProvider(provider);
            account.setOauthId(oauthId);
            account.setStatus(AccountStatus.ACTIVE);
            // passwordHash remains null for OAuth accounts
        }

        accountRepository.save(account);
        return oAuth2User;
    }
}
