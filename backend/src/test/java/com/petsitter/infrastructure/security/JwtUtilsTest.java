package com.petsitter.infrastructure.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtUtils 核心功能測試")
class JwtUtilsTest {

    private JwtUtils jwtUtils;
    private final String secret = "this-is-a-very-long-secret-key-for-testing-purposes-only-1234567890";

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", secret);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpiration", 3600000L); // 1 hour
    }

    @Test
    @DisplayName("應能成功生成並解析 Token")
    void should_GenerateAndParseToken_Successfully() {
        // Given
        String email = "test@example.com";
        String role = "SITTER";

        // When
        String token = jwtUtils.generateToken(email, role, new HashMap<>(), 3600000L);
        String parsedEmail = jwtUtils.getEmailFromToken(token);
        Claims claims = jwtUtils.getClaims(token);

        // Then
        assertThat(token).isNotBlank();
        assertThat(parsedEmail).isEqualTo(email);
        assertThat(claims.get("role")).isEqualTo(role);
    }

    @Test
    @DisplayName("過期的 Token 驗證應失敗")
    void should_Fail_When_TokenIsExpired() throws InterruptedException {
        // Given: 設定 1 毫秒過期
        String token = jwtUtils.generateToken("expired@test.com", "OWNER", new HashMap<>(), 1L);
        
        Thread.sleep(5); // 等待過期

        // When
        boolean isValid = jwtUtils.validateToken(token);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("無效的簽名驗證應失敗")
    void should_Fail_When_SignatureIsInvalid() {
        // Given
        String token = jwtUtils.generateToken("valid@test.com", "OWNER", new HashMap<>(), 3600000L);
        String invalidToken = token + "corrupted";

        // When
        boolean isValid = jwtUtils.validateToken(invalidToken);

        // Then
        assertThat(isValid).isFalse();
    }
}
