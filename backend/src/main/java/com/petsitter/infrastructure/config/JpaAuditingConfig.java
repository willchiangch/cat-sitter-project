package com.petsitter.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "dateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider dateTimeProvider() {
        // 明確指定使用 OffsetDateTime.now()，解決 Auditing 自動填充的型別轉換問題
        return () -> Optional.of(OffsetDateTime.now());
    }
}

/**
 * 測試用的 AuditorAware
 */
@Component
class MockAuditorAware implements AuditorAware<UUID> {
    @Override
    public Optional<UUID> getCurrentAuditor() {
        return Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    }
}
