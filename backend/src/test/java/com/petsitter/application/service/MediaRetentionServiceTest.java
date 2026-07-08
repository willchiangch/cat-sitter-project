package com.petsitter.application.service;

import com.petsitter.domain.event.MediaExpiryWarningEvent;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-013: 多媒體生命週期與保留策略整合測試")
class MediaRetentionServiceTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MediaRetentionService mediaRetentionService;

    @Autowired
    private UserRepository userRepository;

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
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private MediaStorageService mediaStorageService;

    @MockitoBean
    private NotificationService notificationService;

    private User owner;
    private User sitter;
    private User admin;

    @BeforeEach
    void setUp() {
        mediaRepository.deleteAll();
        reportRepository.deleteAll();
        visitRepository.deleteAll();
        orderSnapshotRepository.deleteAll();
        orderRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        // 1. 建立測試使用者 (SITTER 與 OWNER)
        owner = User.builder()
                .email("owner-" + UUID.randomUUID() + "@test.com")
                .passwordHash("password")
                .role("OWNER")
                .isDeleted(false)
                .build();
        owner = userRepository.save(owner);

        sitter = User.builder()
                .email("sitter-" + UUID.randomUUID() + "@test.com")
                .passwordHash("password")
                .role("SITTER")
                .isDeleted(false)
                .build();
        sitter = userRepository.save(sitter);

        admin = User.builder()
                .email("admin-" + UUID.randomUUID() + "@test.com")
                .passwordHash("password")
                .role("ADMIN")
                .isDeleted(false)
                .build();
        admin = userRepository.save(admin);
    }

    @Test
    @DisplayName("1. 物理清理過期媒體測試 - 體驗版保母 (7天過期)")
    void testCleanupExpiredMedia_FreePlan() {
        // 1. 建立結案 8 天前的訂單與媒體 (體驗版保留天數為 7 天)
        Order order = createOrder(sitter, owner, "COMPLETED");
        
        // 建立快照
        createSnapshot(order, 7, "FREE");

        // 建立行程、報告與媒體
        Visit visit = createVisit(order);
        VisitServiceReport report = createReport(visit);
        ServiceReportMedia media = createMedia(report, "https://gcs/media1.jpg");

        // 透過 jdbcTemplate 將 completed_at 物理修正為 8 天前
        jdbcTemplate.update("UPDATE orders SET completed_at = ? WHERE id = ?", 
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(8), order.getId());

        // 2. 執行物理清理排程
        int deletedCount = mediaRetentionService.cleanupExpiredMedia();
        assertThat(deletedCount).isEqualTo(1);

        // 3. 驗證外部 GCS deleteMedia 被呼叫，且 DB 狀態成功更新
        verify(mediaStorageService).deleteMedia("https://gcs/media1.jpg");
        
        ServiceReportMedia updatedMedia = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(updatedMedia.isPurged()).isTrue();
        assertThat(updatedMedia.getPurgedAt()).isNotNull();
    }

    @Test
    @DisplayName("2. 逾期前三天警告通知測試與 EXISTS 防禦")
    void testExpiryWarningAndExistsGating() {
        // 建立訂單，結案 4 天前（7天保留，所以在第4天時剛好是到期前3天）
        Order order = createOrder(sitter, owner, "COMPLETED");
        createSnapshot(order, 7, "FREE");
        Visit visit = createVisit(order);
        VisitServiceReport report = createReport(visit);
        ServiceReportMedia media = createMedia(report, "https://gcs/warn.jpg");

        // 透過 jdbcTemplate 將 completed_at 物理修正為 4 天前
        jdbcTemplate.update("UPDATE orders SET completed_at = ? WHERE id = ?", 
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(4), order.getId());

        // 執行警告通知發送
        int warnedCount = mediaRetentionService.sendExpiryWarnings();
        assertThat(warnedCount).isEqualTo(1);

        // 驗證標記已更新
        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.isMediaExpiryWarned()).isTrue();

        // --- EXISTS 防禦測試 ---
        // 將該訂單的媒體標記為已清理 (is_purged = true)
        media.setPurged(true);
        mediaRepository.save(media);

        // 重置訂單的警告標記為 false 模擬未發送，completed_at 仍是 4 天前
        jdbcTemplate.update("UPDATE orders SET media_expiry_warned = false WHERE id = ?", order.getId());

        // 再次執行警告發送，因為所有媒體皆已 is_purged = true，所以應被 EXISTS 排除，不重複發送
        int newWarnedCount = mediaRetentionService.sendExpiryWarnings();
        assertThat(newWarnedCount).isEqualTo(0);
    }

    @Test
    @DisplayName("3. 方案升級追溯展延測試")
    void testUpgradeSitterMediaRetention() {
        // 建立已結案 5 天的體驗版訂單 (保留 7 天，此時尚未過期，is_purged = false)
        Order order = createOrder(sitter, owner, "COMPLETED");
        createSnapshot(order, 7, "FREE");
        Visit visit = createVisit(order);
        VisitServiceReport report = createReport(visit);
        createMedia(report, "https://gcs/upgrade.jpg");

        jdbcTemplate.update("UPDATE orders SET completed_at = ? WHERE id = ?", 
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(5), order.getId());

        // 方案升級為 Pro (90天)
        mediaRetentionService.upgradeSitterMediaRetention(sitter.getId(), admin.getId(), "PRO");

        // 驗證該訂單快照保留天數已展延為 90 天
        OrderSnapshot updatedSnapshot = orderSnapshotRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(updatedSnapshot.getMediaRetentionDays()).isEqualTo(90);
        assertThat(updatedSnapshot.getPlanTier()).isEqualTo("PRO");

        // 再次執行清理，此時 completed_at (5天前) + 90天 > NOW，所以檔案不應被清理
        int deletedCount = mediaRetentionService.cleanupExpiredMedia();
        assertThat(deletedCount).isEqualTo(0);
    }

    @Test
    @DisplayName("4. 方案降級合約保護隔離測試")
    void testDowngradeSitterMediaRetention_NoEffectOnExistingContract() {
        // 建立已結案 10 天的 PRO 版訂單 (保留 90 天)
        Order order = createOrder(sitter, owner, "COMPLETED");
        createSnapshot(order, 90, "PRO");
        Visit visit = createVisit(order);
        VisitServiceReport report = createReport(visit);
        createMedia(report, "https://gcs/downgrade.jpg");

        jdbcTemplate.update("UPDATE orders SET completed_at = ? WHERE id = ?", 
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(10), order.getId());

        // 保母方案降級為 FREE (7天)。呼叫該方法，驗證降級保護防呆
        mediaRetentionService.upgradeSitterMediaRetention(sitter.getId(), admin.getId(), "FREE");

        // 即使保母此時被降級，我們驗證原訂單快照依然是 90 天
        OrderSnapshot snapshot = orderSnapshotRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(snapshot.getMediaRetentionDays()).isEqualTo(90);
        assertThat(snapshot.getPlanTier()).isEqualTo("PRO");

        // 執行清理，completed_at (10天前) + 90天 > NOW，原合約受到保護，不應被物理刪除
        int deletedCount = mediaRetentionService.cleanupExpiredMedia();
        assertThat(deletedCount).isEqualTo(0);
    }

    // Helper Methods
    private Order createOrder(User sitter, User owner, String status) {
        Order order = Order.builder()
                .sitter(sitter)
                .owner(owner)
                .status(status)
                .planId(UUID.randomUUID())
                .items(new ArrayList<>())
                .totalAmount(2000)
                .build();
        return orderRepository.save(order);
    }

    private void createSnapshot(Order order, int retentionDays, String planTier) {
        OrderSnapshot snapshot = OrderSnapshot.builder()
                .order(order)
                .snapshotPlanTitle("保母日託方案")
                .snapshotUnitPrice(1000)
                .snapshotOriginalTotal(1000)
                .adjustmentAmount(0)
                .mediaRetentionDays(retentionDays)
                .maxPhotos(20)
                .maxVideos(5)
                .maxVideoSeconds(60)
                .planTier(planTier)
                .snapshotData(new ArrayList<>())
                .build();
        orderSnapshotRepository.save(snapshot);
    }

    private Visit createVisit(Order order) {
        Visit visit = Visit.builder()
                .order(order)
                .status("DONE")
                .planId(order.getPlanId())
                .snapshotPlanTitle("保母日託方案")
                .scheduledAt(OffsetDateTime.now(ZoneOffset.UTC))
                .finishedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        return visitRepository.save(visit);
    }

    private VisitServiceReport createReport(Visit visit) {
        VisitServiceReport report = VisitServiceReport.builder()
                .visitId(visit.getId())
                .status("SUBMITTED")
                .content("今天的照護日誌：毛孩一切良好，吃飽睡足！")
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        return reportRepository.save(report);
    }

    private ServiceReportMedia createMedia(VisitServiceReport report, String mediaUrl) {
        ServiceReportMedia media = ServiceReportMedia.builder()
                .reportId(report.getId())
                .mediaUrl(mediaUrl)
                .mediaType("IMAGE")
                .caption("毛孩午休")
                .isPurged(false)
                .build();
        return mediaRepository.save(media);
    }
}
