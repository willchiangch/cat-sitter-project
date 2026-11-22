package com.petsitter.infrastructure.security;

import com.petsitter.order.OrderIntegrationTestBase;
import org.springframework.context.annotation.Import;
import testsupport.security.SecurityTestConfig;

/**
 * NFR-003 S1 安全整合測試共用：沿用訂單整合測試世界（Testcontainers、真 JWT、真 SecurityConfig filter chain），
 * 另掛觸發例外的測試端點（{@link SecurityTestConfig}）。
 */
@Import(SecurityTestConfig.class)
public abstract class SecurityIntegrationTestBase extends OrderIntegrationTestBase {
}
