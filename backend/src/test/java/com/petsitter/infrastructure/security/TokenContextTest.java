package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("J2: TokenContext 沒有身分時不再回 dummy UUID")
class TokenContextTest {

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("沒有身分 → getUserId 拋 AuthenticationCredentialsNotFoundException（→ 401），不是 0000")
    void getUserId_noIdentity_throws() {
        TokenContext.clear();
        assertThatThrownBy(TokenContext::getUserId)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(TokenContext.tryGetUserId()).isEmpty();
    }

    @Test
    @DisplayName("有身分 → 原樣回傳；clear 後又變成沒有身分")
    void getUserId_withIdentity_returnsIt() {
        UUID id = UUID.randomUUID();
        TokenContext.setUserId(id);
        assertThat(TokenContext.getUserId()).isEqualTo(id);
        assertThat(TokenContext.tryGetUserId()).contains(id);
        TokenContext.clear();
        assertThatThrownBy(TokenContext::getUserId)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
