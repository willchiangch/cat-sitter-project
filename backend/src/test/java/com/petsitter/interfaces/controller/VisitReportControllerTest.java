package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.application.service.NotificationService;
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
import static org.mockito.Mockito.*;
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

    @MockitoBean
    private NotificationService notificationService;

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

    @Test
    @DisplayName("Scenario 2: 保母上傳媒體成功（IMAGE + 稽核日誌）")
    void should_UploadMedia_Successfully() throws Exception {
        TokenContext.setUserId(sitter.getId());

        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "content".getBytes());
        String expectedUrl = "https://storage.googleapis.com/test-bucket/PRO/2026-05-24/order-uuid/media-uuid.jpg";
        when(mediaStorageService.uploadReportMedia(any(), any(), any(), any(), any()))
                .thenReturn(expectedUrl);

        mockMvc.perform(multipart("/api/visits/{visitId}/media", visit.getId())
                .file(file)
                .header("Idempotency-Key", "upload-media-success-key")
                .param("mediaType", "IMAGE")
                .param("caption", "貓咪很可愛")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mediaId").exists())
                .andExpect(jsonPath("$.data.mediaUrl").value(expectedUrl));

        // 驗證 DB 數量與稽核日誌
        List<ServiceReportMedia> mediaList = mediaRepository.findAll();
        org.junit.jupiter.api.Assertions.assertEquals(1, mediaList.size());
        org.junit.jupiter.api.Assertions.assertFalse(mediaList.get(0).isDeleted());

        List<OrderLog> logs = orderLogRepository.findAll();
        boolean hasUploadLog = logs.stream().anyMatch(l -> "UPLOAD_REPORT_MEDIA".equals(l.getActionType()));
        org.junit.jupiter.api.Assertions.assertTrue(hasUploadLog);
    }

    @Test
    @DisplayName("Scenario 3: 保母邏輯刪除媒體成功")
    void should_DeleteMedia_Successfully() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 先建立一個草稿
        VisitServiceReport report = reportRepository.save(VisitServiceReport.builder()
                .visitId(visit.getId())
                .status("DRAFT")
                .content("")
                .version(0)
                .isDeleted(false)
                .createdBy(sitter.getId())
                .updatedBy(sitter.getId())
                .build());

        // 新增一個 media 檔案到 DB
        ServiceReportMedia media = mediaRepository.save(ServiceReportMedia.builder()
                .reportId(report.getId())
                .mediaUrl("http://temp.jpg")
                .mediaType("IMAGE")
                .version(0)
                .isDeleted(false)
                .createdBy(sitter.getId())
                .updatedBy(sitter.getId())
                .build());

        mockMvc.perform(delete("/api/visits/media/{mediaId}", media.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("version", 0)))
                .header("Idempotency-Key", "delete-media-key")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 驗證邏輯刪除與 GCS delete 不被呼叫
        ServiceReportMedia deletedMedia = mediaRepository.findById(media.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(deletedMedia.isDeleted());
        verify(mediaStorageService, times(0)).deleteMedia(any());

        List<OrderLog> logs = orderLogRepository.findAll();
        boolean hasDeleteLog = logs.stream().anyMatch(l -> "DELETE_REPORT_MEDIA".equals(l.getActionType()));
        org.junit.jupiter.api.Assertions.assertTrue(hasDeleteLog);
    }

    @Test
    @DisplayName("Scenario 4: 保母正式送出日誌成功（非同步通知 + 稽核）")
    void should_SubmitReport_Successfully() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 建立草稿
        VisitServiceReport report = reportRepository.save(VisitServiceReport.builder()
                .visitId(visit.getId())
                .status("DRAFT")
                .content("今日餵食正常")
                .version(0)
                .isDeleted(false)
                .createdBy(sitter.getId())
                .updatedBy(sitter.getId())
                .build());

        // 設定 visit 狀態為 DONE 且 finishedAt 在 24 小時內
        visit.setStatus("DONE");
        visit.setFinishedAt(OffsetDateTime.now().minusMinutes(30));
        visitRepository.save(visit);

        mockMvc.perform(post("/api/visits/{visitId}/report/submit", visit.getId())
                .header("Idempotency-Key", "submit-report-key")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 驗證 DB 狀態
        VisitServiceReport submittedReport = reportRepository.findById(report.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("SUBMITTED", submittedReport.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(submittedReport.getSubmittedAt());

        // 驗證稽核日誌
        List<OrderLog> logs = orderLogRepository.findAll();
        boolean hasSubmitLog = logs.stream().anyMatch(l -> "SUBMIT_SERVICE_REPORT".equals(l.getActionType()));
        org.junit.jupiter.api.Assertions.assertTrue(hasSubmitLog);

        // 驗證通知被呼叫 1 次
        verify(notificationService, times(1)).sendNotification(eq(owner.getId()), any());
    }

    @Test
    @DisplayName("Scenario 5: 飼主讀取已送出日誌（SUBMITTED-only Gate 通過）")
    void should_Return200_When_OwnerReadsSubmittedReport() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 建立 SUBMITTED 日誌
        VisitServiceReport report = reportRepository.save(VisitServiceReport.builder()
                .visitId(visit.getId())
                .status("SUBMITTED")
                .content("已完成任務")
                .submittedAt(OffsetDateTime.now())
                .version(0)
                .isDeleted(false)
                .createdBy(sitter.getId())
                .updatedBy(sitter.getId())
                .build());

        // 建立 media，一個正常，一個已邏輯刪除
        ServiceReportMedia media1 = mediaRepository.save(ServiceReportMedia.builder()
                .reportId(report.getId())
                .mediaUrl("http://normal.jpg")
                .mediaType("IMAGE")
                .isDeleted(false)
                .createdBy(sitter.getId())
                .updatedBy(sitter.getId())
                .version(0)
                .build());

        ServiceReportMedia media2 = mediaRepository.save(ServiceReportMedia.builder()
                .reportId(report.getId())
                .mediaUrl("http://deleted.jpg")
                .mediaType("IMAGE")
                .isDeleted(true)
                .createdBy(sitter.getId())
                .updatedBy(sitter.getId())
                .version(0)
                .build());

        // 飼主身份讀取
        TokenContext.setUserId(owner.getId());

        mockMvc.perform(get("/api/visits/{visitId}/report", visit.getId())
                .with(user(owner.getId().toString()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.isEditable").value(false))
                .andExpect(jsonPath("$.data.media.length()").value(1))
                .andExpect(jsonPath("$.data.media[0].mediaUrl").value("http://normal.jpg"));
    }

    @Test
    @DisplayName("Scenario 11: 樂觀鎖版本衝突 (409 VERSION_CONFLICT)")
    void should_Return409_When_VersionConflict() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 手動建立一個 version 為 1 的日誌在 DB 中
        VisitServiceReport report = reportRepository.save(VisitServiceReport.builder()
                .visitId(visit.getId())
                .status("DRAFT")
                .content("原本的內容")
                .version(1)
                .isDeleted(false)
                .createdBy(sitter.getId())
                .updatedBy(sitter.getId())
                .build());

        // 帶 version = 0 再次暫存
        mockMvc.perform(put("/api/visits/{visitId}/report", visit.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "衝突的暫存", "version", 0)))
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MSG_DATA_VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("Scenario 12: SUBMITTED 狀態後再修改拒絕 (409 REPORT_STATE_CONFLICT)")
    void should_Return409_When_ReportAlreadySubmitted() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 建立 SUBMITTED 日誌
        reportRepository.save(VisitServiceReport.builder()
                .visitId(visit.getId())
                .status("SUBMITTED")
                .content("已完成任務")
                .submittedAt(OffsetDateTime.now())
                .version(0)
                .isDeleted(false)
                .createdBy(sitter.getId())
                .updatedBy(sitter.getId())
                .build());

        // 試圖 PUT 暫存
        mockMvc.perform(put("/api/visits/{visitId}/report", visit.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "想偷改", "version", 0)))
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MSG_DATA_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("Scenario 13: 媒體格式/大小驗證失敗 (400 INVALID_MEDIA_FORMAT)")
    void should_Return400_When_InvalidMediaFormat() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // GIF 不支援
        MockMultipartFile gifFile = new MockMultipartFile("file", "test.gif", "image/gif", "content".getBytes());

        mockMvc.perform(multipart("/api/visits/{visitId}/media", visit.getId())
                .file(gifFile)
                .header("Idempotency-Key", "upload-gif-key")
                .param("mediaType", "IMAGE")
                .param("caption", "GIF圖片")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MSG_DATA_INVALID_MEDIA"));

        // 圖片大小過大 (1MB = 1024*1024 限制，所以 1024*1024 + 1 bytes 就過大)
        byte[] largeBytes = new byte[1024 * 1024 + 1];
        MockMultipartFile largeFile = new MockMultipartFile("file", "large.jpg", "image/jpeg", largeBytes);

        mockMvc.perform(multipart("/api/visits/{visitId}/media", visit.getId())
                .file(largeFile)
                .header("Idempotency-Key", "upload-large-key")
                .param("mediaType", "IMAGE")
                .param("caption", "大圖片")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MSG_DATA_INVALID_MEDIA"));
    }

    @Test
    @DisplayName("Scenario 14: GCS 上傳故障觸發事務回滾 (503 STORAGE_SERVICE_UNAVAILABLE)")
    void should_Return503_When_GcsUploadFails() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // Mock 讓 uploadReportMedia 丟出 RuntimeException
        when(mediaStorageService.uploadReportMedia(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("GCS connection timeout"));

        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "content".getBytes());

        mockMvc.perform(multipart("/api/visits/{visitId}/media", visit.getId())
                .file(file)
                .header("Idempotency-Key", "gcs-fail-key")
                .param("mediaType", "IMAGE")
                .param("caption", "故障測試")
                .with(user(sitter.getId().toString()).roles("SITTER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("MSG_DATA_STORAGE_ERROR"));

        // 驗證 DB 沒有新增 media
        List<ServiceReportMedia> mediaList = mediaRepository.findAll();
        org.junit.jupiter.api.Assertions.assertEquals(0, mediaList.size());

        // 驗證 order_logs 寫入 UPLOAD_REPORT_MEDIA_FAIL
        List<OrderLog> logs = orderLogRepository.findAll();
        boolean hasFailLog = logs.stream().anyMatch(l -> "UPLOAD_REPORT_MEDIA_FAIL".equals(l.getActionType()));
        org.junit.jupiter.api.Assertions.assertTrue(hasFailLog);
    }

    @Test
    @DisplayName("Scenario 15b: 越權防禦 — 飼主嘗試呼叫寫入端點 (403)")
    void should_Return403_When_OwnerAttemptsToWrite() throws Exception {
        TokenContext.setUserId(owner.getId());

        mockMvc.perform(put("/api/visits/{visitId}/report", visit.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "飼主想寫日誌", "version", 0)))
                .with(user(owner.getId().toString()).roles("OWNER")))
                .andExpect(status().isForbidden());
    }
}
