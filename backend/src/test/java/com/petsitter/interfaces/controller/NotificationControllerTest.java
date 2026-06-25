package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.service.NotificationService;
import com.petsitter.domain.model.Notification;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.NotificationRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.TokenContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-014: 訊息中心 API 控制器測試")
class NotificationControllerTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User userOwner;
    private User userSitter;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        userRepository.deleteAll();

        userOwner = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .role("OWNER")
                .build());

        userSitter = userRepository.save(User.builder()
                .email("sitter-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .role("SITTER")
                .build());
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("Scenario 1: 查詢通知清單與未讀數 (含角色隔離過濾)")
    void should_QueryNotificationsAndUnreadCount() throws Exception {
        TokenContext.setUserId(userOwner.getId());

        // Given: 為 userOwner 建立 1 筆 OWNER 通知與 1 筆 SITTER 通知
        notificationRepository.save(Notification.builder().userId(userOwner.getId()).title("Owner Msg").content("A").category("ORDER_AFFAIR").roleTarget("OWNER").build());
        notificationRepository.save(Notification.builder().userId(userOwner.getId()).title("Sitter Msg").content("B").category("ORDER_AFFAIR").roleTarget("SITTER").build());

        // When & Then: 以 OWNER 身份查詢 notifications，應只回傳 OWNER 通知
        mockMvc.perform(get("/api/notifications")
                .param("role", "OWNER")
                .with(user(userOwner.getId().toString()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Owner Msg"));

        // When & Then: 以 OWNER 身份查詢 unread-count，未讀數應為 1
        mockMvc.perform(get("/api/notifications/unread-count")
                .param("role", "OWNER")
                .with(user(userOwner.getId().toString()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    @DisplayName("Scenario 2: 標示已讀 IDOR 防禦檢驗 — 越權訪問應回傳 404 MSG_DATA_F11")
    void should_Return404_When_UnauthorizedAccessToRead() throws Exception {
        TokenContext.setUserId(userSitter.getId());

        // Given: 屬於 userOwner 的通知
        Notification noti = notificationRepository.save(Notification.builder()
                .userId(userOwner.getId())
                .title("Owner Msg")
                .content("A")
                .category("ORDER_AFFAIR")
                .roleTarget("OWNER")
                .build());

        // When & Then: 以 userSitter 身份嘗試讀取此通知 -> 應回傳 404 (MSG_DATA_F11)
        mockMvc.perform(post("/api/notifications/{id}/read", noti.getId())
                .with(user(userSitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MSG_DATA_F11"));
    }

    @Test
    @DisplayName("Scenario 3: 偏好設定查詢與更新防呆鎖定 (ACCOUNT_AUTH 強制開啟卡控)")
    void should_BlockPreferenceUpdate_When_AccountAuthClosed() throws Exception {
        TokenContext.setUserId(userOwner.getId());

        // Given: 正常更新 SUBSCRIPTION_MAINTENANCE 偏好 -> 應成功
        NotificationController.UpdatePreferenceRequest normalRequest = new NotificationController.UpdatePreferenceRequest();
        normalRequest.setCategory("SUBSCRIPTION_MAINTENANCE");
        normalRequest.setEnableInApp(true);
        normalRequest.setEnableEmail(true);

        mockMvc.perform(put("/api/notifications/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(normalRequest))
                .with(user(userOwner.getId().toString()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // Given: 嘗試關閉 ACCOUNT_AUTH 的 email 偏好 -> 應回傳 400 (MSG_DATA_INVALID_INPUT)
        NotificationController.UpdatePreferenceRequest badRequest = new NotificationController.UpdatePreferenceRequest();
        badRequest.setCategory("ACCOUNT_AUTH");
        badRequest.setEnableInApp(true);
        badRequest.setEnableEmail(false);

        mockMvc.perform(put("/api/notifications/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(badRequest))
                .with(user(userOwner.getId().toString()).roles("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MSG_DATA_INVALID_INPUT"));
    }
}
