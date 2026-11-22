package com.petsitter.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 時間來源（SD-ORDER §11.4）：所有 {@code now} 由注入的 {@link Clock} 取得；整合測試以 {@code @Primary}
 * 的可設定時間實作替換（SD-ORDER §10.1）。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
