package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-022: 行程照護日誌 API 控制器測試")
class VisitReportControllerTest {

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
    private OrderRepository orderRepository;

    @Autowired
    private OrderSnapshotRepository orderSnapshotRepository;

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private VisitServiceReportRepository reportRepository;

    @Autowired
    private ServiceReportMediaRepository mediaRepository;

    @Autowired
    private OrderLogRepository orderLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private MediaStorageService mediaStorageService;

    private User sitter;
    private User owner;
    private User unrelatedUser;
    private Order order;
    private OrderSnapshot snapshot;
    private Visit visit;

    @BeforeEach
    void setUp() {
        mediaRepository.deleteAll();
        reportRepository.deleteAll();
        orderLogRepository.deleteAll();
        visitRepository.deleteAll();
        orderSnapshotRepository.deleteAll();
        orderRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        sitter = userRepository.save(User.builder().email("sitter@test.com").passwordHash("hash").role("SITTER").build());
        owner = userRepository.save(User.builder().email("owner@test.com").passwordHash("hash").role("OWNER").build());
        unrelatedUser = userRepository.save(User.builder().email("unrelated@test.com").passwordHash("hash").role("SITTER").build());

        subscriptionRepository.save(Subscription.builder().sitter(sitter).planTier("PRO").build());

        order = orderRepository.save(Order.builder()
                .sitter(sitter)
                .owner(owner)
                .items(List.of())
                .status("CONFIRMED")
                .planId(UUID.randomUUID())
                .build());

        snapshot = orderSnapshotRepository.save(OrderSnapshot.builder()
                .order(order)
                .snapshotPlanTitle("Pro")
                .snapshotUnitPrice(500)
                .snapshotOriginalTotal(500)
                .adjustmentAmount(0)
                .mediaRetentionDays(90)
                .maxPhotos(10)
                .maxVideos(2)
                .maxVideoSeconds(30)
                .planTier("PRO")
                .snapshotData(List.of())
                .build());

        visit = visitRepository.save(Visit.builder()
                .order(order)
                .status("IN_PROGRESS")
                .planId(order.getPlanId())
                .snapshotPlanTitle("Pro")
                .scheduledAt(OffsetDateTime.now())
                .finishedAt(OffsetDateTime.now())
                .build());
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("Scenario 1: 保母暫存文字草稿成功")
    void should_SaveDraft_Successfully() throws Exception {
        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(put("/api/visits/{visitId}/report", visit.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "今日餵食正常", "version", 0)))
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.content").value("今日餵食正常"));
    }

    @Test
    @DisplayName("Scenario 2: 逾期 24 小時後暫存拒絕 (403 REPORT_EXPIRED)")
    void should_Return403_When_DraftIsExpired() throws Exception {
        TokenContext.setUserId(sitter.getId());

        visit.setFinishedAt(OffsetDateTime.now().minus(25, java.time.temporal.ChronoUnit.HOURS));
        visitRepository.save(visit);

        mockMvc.perform(put("/api/visits/{visitId}/report", visit.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "今日餵食正常", "version", 0)))
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("MSG_DATA_REPORT_EXPIRED"));
    }

    @Test
    @DisplayName("Scenario 3: 重複冪等 Key 上傳媒體拒絕 (409)")
    void should_Return409_When_DuplicateIdempotencyKeyOnMediaUpload() throws Exception {
        TokenContext.setUserId(sitter.getId());

        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "content".getBytes());
        String expectedUrl = "https://storage.googleapis.com/test/pro/image.jpg";
        when(mediaStorageService.uploadReportMedia(any(), any(), any(), any(), any()))
                .thenReturn(expectedUrl);

        String idempotencyKey = "upload-media-idemp-123";

        mockMvc.perform(multipart("/api/visits/{visitId}/media", visit.getId())
                .file(file)
                .header("Idempotency-Key", idempotencyKey)
                .param("mediaType", "IMAGE")
                .param("caption", "貓咪喝水")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(multipart("/api/visits/{visitId}/media", visit.getId())
                .file(file)
                .header("Idempotency-Key", idempotencyKey)
                .param("mediaType", "IMAGE")
                .param("caption", "貓咪喝水")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MSG_DATA_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("Scenario 4: 照片上傳額度超限拒絕 (403 AUTH_PLAN_LIMIT)")
    void should_Return403_When_PhotoUploadExceedsLimit() throws Exception {
        TokenContext.setUserId(sitter.getId());

        snapshot.setMaxPhotos(0);
        orderSnapshotRepository.save(snapshot);

        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "content".getBytes());

        mockMvc.perform(multipart("/api/visits/{visitId}/media", visit.getId())
                .file(file)
                .header("Idempotency-Key", "upload-media-limit-test")
                .param("mediaType", "IMAGE")
                .param("caption", "貓咪喝水")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("MSG_DATA_PLAN_LIMIT"));
    }

    @Test
    @DisplayName("Scenario 5: 飼主端唯讀隔離 — 未送出草稿不可見 (404)")
    void should_Return404_When_OwnerReadsDraftReport() throws Exception {
        // 先建立草稿
        TokenContext.setUserId(sitter.getId());
        mockMvc.perform(put("/api/visits/{visitId}/report", visit.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "今日餵食正常", "version", 0)))
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk());

        // 飼主嘗試讀取
        TokenContext.setUserId(owner.getId());
        mockMvc.perform(get("/api/visits/{visitId}/report", visit.getId())
                .with(user(owner.getId().toString()).roles("OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MSG_DATA_F11"));
    }

    @Test
    @DisplayName("Scenario 6: 進行中行程送出日誌拒絕 (422 VISIT_NOT_FINISHED)")
    void should_Return422_When_SubmittingInProgressReport() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 先建立草稿
        mockMvc.perform(put("/api/visits/{visitId}/report", visit.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "今日餵食正常", "version", 0)))
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk());

        // 行程仍在 IN_PROGRESS 點選送出
        mockMvc.perform(post("/api/visits/{visitId}/report/submit", visit.getId())
                .header("Idempotency-Key", "submit-test-key")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MSG_DATA_VISIT_NOT_FINISHED"));
    }

    @Test
    @DisplayName("Scenario 7: 越權防禦 — 無關保母讀取或編輯日誌 (403)")
    void should_Return403_When_UnrelatedSitterAccessesReport() throws Exception {
        TokenContext.setUserId(unrelatedUser.getId());

        mockMvc.perform(get("/api/visits/{visitId}/report", visit.getId())
                .with(user(unrelatedUser.getId().toString()).roles("SITTER")))
                .andExpect(status().isForbidden());
    }
}
