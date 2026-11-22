package com.petsitter.application.service;

import com.petsitter.domain.event.MediaExpiryWarningEvent;
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
import java.time.LocalDate;
import testsupport.order.OrderFixtures;

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
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private MediaStorageService mediaStorageService;

    @MockitoBean
    private NotificationService notificationService;

    private OrderFixtures fx;
    private UUID owner;
    private UUID sitter;
    private UUID admin;

    @BeforeEach
    void setUp() {
        fx = new OrderFixtures(jdbcTemplate);
        owner = fx.user("owner-" + UUID.randomUUID() + "@test.com", "OWNER");
        sitter = fx.user("sitter-" + UUID.randomUUID() + "@test.com", "SITTER");
        admin = fx.user("admin-" + UUID.randomUUID() + "@test.com", "ADMIN");
    }

    /** 已結案訂單 + 快照 + 一個 DONE 行程 + 已送出日誌 + 一筆媒體；回傳 [orderId, mediaId]。 */
    private UUID[] completedOrderWithMedia(int retentionDays, String tier, String objectKey, int completedDaysAgo) {
        UUID plan = fx.plan(sitter, "保母日託方案", 1000);
        UUID order = fx.order(owner, sitter, "COMPLETED");
        fx.snapshot(order, tier, 20, 5, retentionDays);
        UUID item = fx.bookingItem(order, plan, 0, LocalDate.now());
        UUID visit = fx.visit(order, item, owner, sitter, plan, LocalDate.now(), "DONE");
        UUID report = fx.submittedReport(visit);
        UUID media = fx.media(order, visit, sitter, report, objectKey);
        jdbcTemplate.update("UPDATE orders SET completed_at = ? WHERE id = ?",
                OrderFixtures.ts(OffsetDateTime.now(ZoneOffset.UTC).minusDays(completedDaysAgo)), order);
        return new UUID[]{order, media};
    }

    private int retention(UUID order) {
        return jdbcTemplate.queryForObject("SELECT media_retention_days FROM order_snapshots WHERE order_id = ?", Integer.class, order);
    }

    @Test
    @DisplayName("1. 物理清理過期媒體測試 - 體驗版保母 (7天過期)")
    void testCleanupExpiredMedia_FreePlan() {
        UUID[] ids = completedOrderWithMedia(7, "FREE", "https://gcs/media1.jpg", 8);

        int deletedCount = mediaRetentionService.cleanupExpiredMedia();
        assertThat(deletedCount).isEqualTo(1);

        verify(mediaStorageService).deleteMedia("https://gcs/media1.jpg");
        assertThat(jdbcTemplate.queryForObject("SELECT is_purged FROM service_report_media WHERE id = ?", Boolean.class, ids[1])).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT purged_at FROM service_report_media WHERE id = ?", OffsetDateTime.class, ids[1])).isNotNull();
    }

    @Test
    @DisplayName("2. 逾期前三天警告通知（M32 兩段候選：訂單側 + 仍有未清除媒體）；標記以單欄 UPDATE 寫入（M15）")
    void testExpiryWarningAndExistsGating() {
        UUID[] ids = completedOrderWithMedia(7, "FREE", "https://gcs/warn.jpg", 4);

        assertThat(mediaRetentionService.sendExpiryWarnings()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT media_expiry_warned FROM orders WHERE id = ?", Boolean.class, ids[0])).isTrue();

        // 媒體都已清除 → 不再提醒
        jdbcTemplate.update("UPDATE service_report_media SET is_purged = true, purged_at = now() WHERE id = ?", ids[1]);
        jdbcTemplate.update("UPDATE orders SET media_expiry_warned = false WHERE id = ?", ids[0]);
        assertThat(mediaRetentionService.sendExpiryWarnings()).isEqualTo(0);
    }

    @Test
    @DisplayName("3. 方案升級追溯展延（M16：經 OrderSnapshotService，只延長保留天數）")
    void testUpgradeSitterMediaRetention() {
        UUID[] ids = completedOrderWithMedia(7, "FREE", "https://gcs/upgrade.jpg", 5);

        mediaRetentionService.upgradeSitterMediaRetention(sitter, admin, "PRO");

        assertThat(retention(ids[0])).isEqualTo(90);
        assertThat(mediaRetentionService.cleanupExpiredMedia()).isEqualTo(0);
    }

    @Test
    @DisplayName("4. 方案降級合約保護隔離測試（只延長不縮短）")
    void testDowngradeSitterMediaRetention_NoEffectOnExistingContract() {
        UUID[] ids = completedOrderWithMedia(90, "PRO", "https://gcs/downgrade.jpg", 10);

        mediaRetentionService.upgradeSitterMediaRetention(sitter, admin, "FREE");

        assertThat(retention(ids[0])).isEqualTo(90);
        assertThat(jdbcTemplate.queryForObject("SELECT plan_tier FROM order_snapshots WHERE order_id = ?", String.class, ids[0])).isEqualTo("PRO");
        assertThat(mediaRetentionService.cleanupExpiredMedia()).isEqualTo(0);
    }
}
