package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.CareNoteItemDto;
import com.petsitter.application.dto.CareNoteRequest;
import com.petsitter.application.service.CareNoteService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-021: 照護記事 API 控制器測試")
class CareNoteControllerTest {

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

    @Autowired
    private CareNoteService careNoteService;

    private ObjectMapper objectMapper = new ObjectMapper();

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
    @DisplayName("Scenario 8a: 飼主唯讀強制 (AC-5) — 寫入端點拒絕")
    void should_Return403_When_OwnerAttemptsToWrite() throws Exception {
        TokenContext.setUserId(ownerA.getId());

        CareNoteRequest request = new CareNoteRequest();
        request.setItems(List.of(
                CareNoteItemDto.builder().sectionType("SERVICE").title("標題").content("內容").build()
        ));

        mockMvc.perform(put("/api/care-notes/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .with(user(ownerA.getId().toString()).roles("OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Scenario 8b: IDOR 越權防護 — 無關保母讀取")
    void should_Return403_When_UnrelatedSitterAccesses() throws Exception {
        TokenContext.setUserId(sitterB.getId());

        mockMvc.perform(get("/api/care-notes/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .with(user(sitterB.getId().toString()).roles("SITTER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Scenario 8c: OR 邏輯驗證 — 飼主合法讀取 GET")
    void should_Return200_When_OwnerReadsOwnCareNote() throws Exception {
        TokenContext.setUserId(ownerA.getId());

        mockMvc.perform(get("/api/care-notes/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .with(user(ownerA.getId().toString()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ownerId").value(ownerA.getId().toString()))
                .andExpect(jsonPath("$.data.sitterId").value(sitterA.getId().toString()));
    }

    @Test
    @DisplayName("Scenario 8c: OR 邏輯驗證 — 保母合法讀取 GET")
    void should_Return200_When_SitterReadsOwnCareNote() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        mockMvc.perform(get("/api/care-notes/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .with(user(sitterA.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("Scenario 9: Idempotency 重複請求 → 409")
    void should_Return409_When_DuplicateIdempotencyKey() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        CareNoteRequest request = new CareNoteRequest();
        request.setItems(List.of(
                CareNoteItemDto.builder().sectionType("SERVICE").title("標題").content("內容").build()
        ));

        String idempotencyKey = "idemp-key-test-123";

        mockMvc.perform(put("/api/care-notes/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .header("Idempotency-Key", idempotencyKey)
                .with(user(sitterA.getId().toString()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.careNoteId").exists());

        mockMvc.perform(put("/api/care-notes/{sitterId}/{ownerId}", sitterA.getId(), ownerA.getId())
                .header("Idempotency-Key", idempotencyKey)
                .with(user(sitterA.getId().toString()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }
}
