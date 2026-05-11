package com.petsitter.interfaces.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("SD-009: 內部 Cron 控制器安全測試")
class InternalCronControllerTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("不帶 Secret Header 應回傳 401")
    void should_Return401_When_NoSecretHeader() throws Exception {
        mockMvc.perform(post("/api/internal/cron/orders/auto-complete"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("帶入正確 Secret Header 應回傳 200")
    void should_Return200_When_CorrectSecretHeader() throws Exception {
        mockMvc.perform(post("/api/internal/cron/orders/auto-complete")
                .header("X-Internal-Secret", "local-secret-123"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("帶入錯誤 Secret Header 應回傳 401")
    void should_Return401_When_WrongSecretHeader() throws Exception {
        mockMvc.perform(post("/api/internal/cron/orders/auto-complete")
                .header("X-Internal-Secret", "wrong-secret"))
                .andExpect(status().isUnauthorized());
    }
}
