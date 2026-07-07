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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("SD-009 & SD-014: 內部 Cron 控制器與安全性測試")
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
    @DisplayName("Auto-complete: 不帶 Secret Header 應回傳 401")
    void should_Return401_When_NoSecretHeader() throws Exception {
        mockMvc.perform(post("/api/internal/cron/orders/auto-complete"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Auto-complete: 帶入正確 Secret Header 應回傳 200")
    void should_Return200_When_CorrectSecretHeader() throws Exception {
        mockMvc.perform(post("/api/internal/cron/orders/auto-complete")
                .header("X-Internal-Secret", "local-secret-123"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Auto-complete: 帶入錯誤 Secret Header 應回傳 401")
    void should_Return401_When_WrongSecretHeader() throws Exception {
        mockMvc.perform(post("/api/internal/cron/orders/auto-complete")
                .header("X-Internal-Secret", "wrong-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Notifications cleanup: 不帶 Secret 應回傳 401")
    void should_Return401_When_NoSecretHeader_NotificationCleanup() throws Exception {
        mockMvc.perform(post("/api/internal/cron/notifications/cleanup"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Notifications cleanup: 帶正確 Secret 應回傳 200 並包含 deletedCount")
    void should_Return200WithDeletedCount_When_CorrectSecretHeader() throws Exception {
        mockMvc.perform(post("/api/internal/cron/notifications/cleanup")
                .header("X-Internal-Secret", "local-secret-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.deletedCount").exists());
    }

    @Test
    @DisplayName("Notifications cleanup: 帶錯誤 Secret 應回傳 401")
    void should_Return401_When_WrongSecretHeader_NotificationCleanup() throws Exception {
        mockMvc.perform(post("/api/internal/cron/notifications/cleanup")
                .header("X-Internal-Secret", "wrong-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Media cleanup: 帶正確 Secret 應回傳 200")
    void should_Return200_When_CorrectSecretHeader_MediaCleanup() throws Exception {
        mockMvc.perform(post("/api/internal/cron/media/cleanup")
                .header("X-Internal-Secret", "local-secret-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.deletedCount").exists());
    }

    @Test
    @DisplayName("Media cleanup: 帶錯誤 Secret 應回傳 401")
    void should_Return401_When_WrongSecretHeader_MediaCleanup() throws Exception {
        mockMvc.perform(post("/api/internal/cron/media/cleanup")
                .header("X-Internal-Secret", "wrong-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Media expiry warning: 帶正確 Secret 應回傳 200")
    void should_Return200_When_CorrectSecretHeader_MediaExpiryWarning() throws Exception {
        mockMvc.perform(post("/api/internal/cron/media/expiry-warning")
                .header("X-Internal-Secret", "local-secret-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.warnedCount").exists());
    }

    @Test
    @DisplayName("Media expiry warning: 帶錯誤 Secret 應回傳 401")
    void should_Return401_When_WrongSecretHeader_MediaExpiryWarning() throws Exception {
        mockMvc.perform(post("/api/internal/cron/media/expiry-warning")
                .header("X-Internal-Secret", "wrong-secret"))
                .andExpect(status().isUnauthorized());
    }
}

