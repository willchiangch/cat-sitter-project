package com.petsitter.interfaces.controller;

import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.CareMediaRepository;
import com.petsitter.domain.repository.CareNoteRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.JwtUtils;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * fix-batch12 J1：照護記事本 / 照護媒體的讀寫必須「保母與飼主之間有過任何狀態的訂單」（SD-021 §1.3）。
 * <p>
 * 過去 PUT /api/care-notes/{sitterId}/{ownerId}、POST /api/care-media/{sitterId}/{ownerId} 只檢查
 * sitterId == token，保母可以對任意 ownerId 建記事本 / 上傳媒體，那位飼主讀取時就會看到。
 * <p>
 * 比照 OrderIdentityFromTokenSecurityTest：請求帶真 JWT（JwtUtils 簽發），走完整 SecurityConfig
 * filter chain → JwtAuthenticationFilter 寫 TokenContext → controller → 真 DB，不用 TokenContext.setUserId。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("J1: 照護記事本 / 媒體需雙方有過訂單（真 JWT + 真 SecurityConfig）")
class CareRelationshipSecurityTest {

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
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CareNoteRepository careNoteRepository;

    @Autowired
    private CareMediaRepository careMediaRepository;

    @MockitoBean
    private MediaStorageService mediaStorageService;

    private User sitter;
    private User ownerWithOrder;
    private User stranger;

    @BeforeEach
    void setUp() {
        sitter = saveUser("sitter", "SITTER");
        ownerWithOrder = saveUser("owner-with-order", "OWNER");
        stranger = saveUser("stranger-owner", "OWNER");
        // 「任何狀態」：用已取消的訂單證明不限進行中
        saveOrder(ownerWithOrder, "CANCELLED", false);
    }

    private User saveUser(String prefix, String role) {
        return userRepository.saveAndFlush(User.builder()
                .email("j1-" + prefix + "-" + System.nanoTime() + "@test.com")
                .passwordHash("hash").fullName("J1 " + prefix).role(role).build());
    }

    private void saveOrder(User owner, String status, boolean softDeleted) {
        orderRepository.saveAndFlush(Order.builder()
                .owner(owner).sitter(sitter).status(status)
                .items(List.of()).planId(UUID.randomUUID())
                .isDeleted(softDeleted)
                .build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtils.generateToken(user.getEmail(), user.getRole(),
                Map.<String, Object>of("userId", user.getId().toString()), 3_600_000L);
    }

    private MockHttpServletRequestBuilder saveNote(User owner) {
        return put("/api/care-notes/{sitterId}/{ownerId}", sitter.getId(), owner.getId())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"sectionType\":\"SERVICE\",\"title\":\"J1\",\"content\":\"內容\"}]}");
    }

    private MockMultipartHttpServletRequestBuilder uploadMedia(User owner) {
        MockMultipartFile file = new MockMultipartFile("file", "j1.jpg", "image/jpeg", "img".getBytes());
        return multipart("/api/care-media/{sitterId}/{ownerId}", sitter.getId(), owner.getId())
                .file(file)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .param("caption", "J1")
                .param("mediaType", "IMAGE");
    }

    @Test
    @DisplayName("保母對沒有任何訂單的飼主寫記事本 → 403，DB 沒有記事本")
    void saveNote_noOrder_403() throws Exception {
        mockMvc.perform(saveNote(stranger).header("Authorization", bearer(sitter)))
                .andExpect(status().isForbidden());
        assertThat(careNoteRepository.findBySitterIdAndOwnerId(sitter.getId(), stranger.getId())).isEmpty();
    }

    @Test
    @DisplayName("保母對沒有任何訂單的飼主上傳媒體 → 403，沒有呼叫 GCS、DB 沒有媒體")
    void uploadMedia_noOrder_403() throws Exception {
        mockMvc.perform(uploadMedia(stranger).header("Authorization", bearer(sitter)))
                .andExpect(status().isForbidden());
        verify(mediaStorageService, never()).uploadMedia(any(), any(), any(), any());
        assertThat(careMediaRepository.countBySitterIdAndOwnerId(sitter.getId(), stranger.getId())).isZero();
    }

    @Test
    @DisplayName("保母對沒有任何訂單的飼主套用模板 → 403（模板 ID 存不存在都先擋）")
    void applyTemplate_noOrder_403() throws Exception {
        mockMvc.perform(post("/api/care-notes/{sitterId}/{ownerId}/apply-template/{templateId}",
                                sitter.getId(), stranger.getId(), UUID.randomUUID())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", bearer(sitter)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("讀取同規則：沒有訂單時，保母本人與飼主本人讀記事本 / 媒體都 → 403")
    void read_noOrder_403_bothSides() throws Exception {
        for (User reader : List.of(sitter, stranger)) {
            mockMvc.perform(get("/api/care-notes/{sitterId}/{ownerId}", sitter.getId(), stranger.getId())
                            .header("Authorization", bearer(reader)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/care-media/{sitterId}/{ownerId}", sitter.getId(), stranger.getId())
                            .header("Authorization", bearer(reader)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("e2e 清理用的軟刪除訂單不算關係 → 403")
    void softDeletedOrder_doesNotCount() throws Exception {
        saveOrder(stranger, "COMPLETED", true);
        mockMvc.perform(saveNote(stranger).header("Authorization", bearer(sitter)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("有過訂單（CANCELLED 也算）→ 保母寫記事本 200、上傳媒體 201；飼主讀得到")
    void withAnyOrder_allowed() throws Exception {
        when(mediaStorageService.uploadMedia(any(), any(), any(), any())).thenReturn("https://gcs.test/j1.jpg");

        mockMvc.perform(saveNote(ownerWithOrder).header("Authorization", bearer(sitter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.careNoteId").exists());
        mockMvc.perform(uploadMedia(ownerWithOrder).header("Authorization", bearer(sitter)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/care-notes/{sitterId}/{ownerId}", sitter.getId(), ownerWithOrder.getId())
                        .header("Authorization", bearer(ownerWithOrder)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections.SERVICE[0].title").value("J1"));
        mockMvc.perform(get("/api/care-media/{sitterId}/{ownerId}", sitter.getId(), ownerWithOrder.getId())
                        .header("Authorization", bearer(ownerWithOrder)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("有訂單時飼主仍唯讀（AC-5）：飼主寫自己的記事本 → 403")
    void owner_cannotWrite_evenWithOrder() throws Exception {
        mockMvc.perform(saveNote(ownerWithOrder).header("Authorization", bearer(ownerWithOrder)))
                .andExpect(status().isForbidden());
    }
}
