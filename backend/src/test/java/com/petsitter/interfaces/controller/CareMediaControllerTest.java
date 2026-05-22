package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.service.CareMediaService;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.SubscriptionRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-021: 照護媒體 API 控制器測試")
class CareMediaControllerTest {

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
    private SubscriptionRepository subscriptionRepository;

    @MockitoBean
    private MediaStorageService mediaStorageService;

    private User sitterA;
    private User ownerA;
    private User sitterB;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        sitterA = userRepository.save(User.builder().email("sitterA@test.com").passwordHash("hash").role("SITTER").build());
        subscriptionRepository.save(Subscription.builder().sitter(sitterA).planTier("FREE").build());

        sitterB = userRepository.save(User.builder().email("sitterB@test.com").passwordHash("hash").role("SITTER").build());
        subscriptionRepository.save(Subscription.builder().sitter(sitterB).planTier("FREE").build());

        ownerA = userRepository.save(User.builder().email("ownerA@test.com").passwordHash("hash").role("OWNER").build());
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("Scenario 8a: 飼主唯讀強制 (AC-5) — 媒體上傳端點拒絕")
    void should_Return403_When_OwnerAttemptsToUploadMedia() throws Exception {
        TokenContext.setUserId(ownerA.getId());

        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "test image content".getBytes());

        mockMvc.perform(multipart("/api/care-media/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .file(file)
                .with(user(ownerA.getId().toString()).roles("OWNER"))
                .param("caption", "測試")
                .param("mediaType", "IMAGE"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Scenario 8b: IDOR 越權防護 — 無關保母讀取媒體庫")
    void should_Return403_When_UnrelatedSitterAccessesMedia() throws Exception {
        TokenContext.setUserId(sitterB.getId());

        mockMvc.perform(get("/api/care-media/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .with(user(sitterB.getId().toString()).roles("SITTER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Scenario 8c: OR 邏輯驗證 — 飼主合法讀取媒體庫")
    void should_Return200_When_OwnerReadsOwnMedia() throws Exception {
        TokenContext.setUserId(ownerA.getId());

        mockMvc.perform(get("/api/care-media/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .with(user(ownerA.getId().toString()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("Scenario 8c: OR 邏輯驗證 — 保母合法讀取媒體庫")
    void should_Return200_When_SitterReadsOwnMedia() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        mockMvc.perform(get("/api/care-media/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .with(user(sitterA.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("Scenario 9: Idempotency 重複上傳 → 409")
    void should_Return409_When_DuplicateIdempotencyKey() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "test image content".getBytes());
        String expectedUrl = "https://gcs.com/success.jpg";

        when(mediaStorageService.uploadMedia(eq(sitterA.getId()), eq(ownerA.getId()), any(UUID.class), any()))
                .thenReturn(expectedUrl);

        String idempotencyKey = "media-idemp-key-test-456";

        mockMvc.perform(multipart("/api/care-media/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .file(file)
                .header("Idempotency-Key", idempotencyKey)
                .with(user(sitterA.getId().toString()).roles("SITTER"))
                .param("caption", "測試")
                .param("mediaType", "IMAGE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.mediaId").exists())
                .andExpect(jsonPath("$.data.mediaUrl").value(expectedUrl));

        mockMvc.perform(multipart("/api/care-media/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .file(file)
                .header("Idempotency-Key", idempotencyKey)
                .with(user(sitterA.getId().toString()).roles("SITTER"))
                .param("caption", "測試")
                .param("mediaType", "IMAGE"))
                .andExpect(status().isConflict());
    }
}
