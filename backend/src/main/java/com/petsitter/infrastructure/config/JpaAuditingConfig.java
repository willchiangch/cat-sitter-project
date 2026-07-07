package com.petsitter.infrastructure.config;

import com.petsitter.infrastructure.security.TokenContext;
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

@Component
class RequestAuditorAware implements AuditorAware<UUID> {
    @Override
    public Optional<UUID> getCurrentAuditor() {
        // 沒有登入者時回傳 empty，讓 @CreatedBy/@LastModifiedBy 保持原值（通常是 null），
        // 避免用全零 dummy UUID 覆寫掉 service 層手動設定的值，導致外鍵約束違反
        return TokenContext.tryGetUserId();
    }
}
