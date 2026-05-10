package com.petsitter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing // 開啟自動填充 created_at, updated_at
public class PetSitterApplication {
    public static void main(String[] args) {
        SpringApplication.run(PetSitterApplication.class, args);
    }
}
