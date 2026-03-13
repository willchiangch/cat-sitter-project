package com.catsitter.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

  /**
   * 初版：固定回傳 "SYSTEM"。
   * 待引入 Spring Security 後，改從 SecurityContextHolder 取 authentication.getName()。
   */
  @Bean
  public AuditorAware<String> auditorAware() {
    return () -> Optional.of("SYSTEM");
  }
}
