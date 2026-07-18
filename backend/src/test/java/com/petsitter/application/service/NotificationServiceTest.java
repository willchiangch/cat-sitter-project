package com.petsitter.application.service;

import com.petsitter.application.exception.NotificationException;
import com.petsitter.domain.model.Notification;
import com.petsitter.domain.model.NotificationPreference;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.NotificationPreferenceRepository;
import com.petsitter.domain.repository.NotificationRepository;
import com.petsitter.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-014: 訊息中心服務層與安全性防禦測試")
class NotificationServiceTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationCleanupService notificationCleanupService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationPreferenceRepository notificationPreferenceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        notificationPreferenceRepository.deleteAll();
        userRepository.deleteAll();

        userA = userRepository.save(User.builder()
                .email("usera-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .role("OWNER")
                .build());

        userB = userRepository.save(User.builder()
                .email("userb-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .role("SITTER")
                .build());
    }

    @Test
    @DisplayName("Scenario 1: 標示已讀與 IDOR 安全防禦 (404 模糊化驗證)")
    void should_Throw404_When_IDOR_Detected_Or_NotFound() {
        // Given: 存在屬於 userA 的通知
        Notification noti = notificationRepository.save(Notification.builder()
                .userId(userA.getId())
                .title("A")
                .content("A Content")
                .category("ORDER_AFFAIR")
                .roleTarget("ALL")
                .build());

        // When & Then: userA 讀取自己的通知 -> 成功
        notificationService.markAsRead(noti.getId(), userA.getId());
        Notification readNoti = notificationRepository.findById(noti.getId()).orElseThrow();
        assertThat(readNoti.isRead()).isTrue();
        assertThat(readNoti.getReadAt()).isNotNull();

        // When & Then: userB 越權讀取 userA 的通知 -> 拋出 404 (MSG_DATA_F11)
        assertThatThrownBy(() -> notificationService.markAsRead(noti.getId(), userB.getId()))
                .isInstanceOf(NotificationException.class)
                .satisfies(ex -> {
                    NotificationException nEx = (NotificationException) ex;
                    assertThat(nEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(nEx.getError()).isEqualTo("MSG_DATA_F11");
                });

        // When & Then: 讀取不存在的通知 -> 拋出 404 (MSG_DATA_F11)
        UUID nonExistentId = UUID.randomUUID();
        assertThatThrownBy(() -> notificationService.markAsRead(nonExistentId, userA.getId()))
                .isInstanceOf(NotificationException.class)
                .satisfies(ex -> {
                    NotificationException nEx = (NotificationException) ex;
                    assertThat(nEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(nEx.getError()).isEqualTo("MSG_DATA_F11");
                });
    }

    @Test
    @DisplayName("Scenario 2: 查詢偏好設定動態補齊預設值")
    void should_ReturnDynamicDefaults_When_NoPreferenceRecordExists() {
        // Given: 新註冊的 userA (資料庫無 preferences)
        // When: 查詢偏好
        List<NotificationService.PreferenceDto> prefs = notificationService.getPreferences(userA.getId());

        // Then: 應回傳預設的 5 個類別且設定無缺漏
        assertThat(prefs).hasSize(5);
        
        // 驗證預設值對齊：SUBSCRIPTION_MAINTENANCE 的 Email 預設應為 false
        NotificationService.PreferenceDto subPref = prefs.stream()
                .filter(p -> "SUBSCRIPTION_MAINTENANCE".equals(p.getCategory()))
                .findFirst().orElseThrow();
        assertThat(subPref.isEnableInApp()).isTrue();
        assertThat(subPref.isEnableEmail()).isFalse();

        NotificationService.PreferenceDto authPref = prefs.stream()
                .filter(p -> "ACCOUNT_AUTH".equals(p.getCategory()))
                .findFirst().orElseThrow();
        assertThat(authPref.isEnableInApp()).isTrue();
        assertThat(authPref.isEnableEmail()).isTrue();
        
        // 驗證無資料庫資料污染 (動態補齊不應寫入 DB)
        assertThat(notificationPreferenceRepository.count()).isZero();
    }

    @Test
    @DisplayName("Scenario 3: 偏好更新安全防護鎖 (ACCOUNT_AUTH 強制開啟卡控)")
    void should_BlockPreferenceUpdate_When_ClosingAccountAuth() {
        // Given: 嘗試將 ACCOUNT_AUTH 的 email 設為 false -> 拋出 400 (MSG_DATA_INVALID_INPUT)
        assertThatThrownBy(() -> notificationService.updatePreference(userA.getId(), "ACCOUNT_AUTH", true, false))
                .isInstanceOf(NotificationException.class)
                .satisfies(ex -> {
                    NotificationException nEx = (NotificationException) ex;
                    assertThat(nEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(nEx.getError()).isEqualTo("MSG_DATA_INVALID_INPUT");
                });

        // 嘗試將 ACCOUNT_AUTH 的 inApp 設為 false -> 拋出 400 (MSG_DATA_INVALID_INPUT)
        assertThatThrownBy(() -> notificationService.updatePreference(userA.getId(), "ACCOUNT_AUTH", false, true))
                .isInstanceOf(NotificationException.class)
                .satisfies(ex -> {
                    NotificationException nEx = (NotificationException) ex;
                    assertThat(nEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(nEx.getError()).isEqualTo("MSG_DATA_INVALID_INPUT");
                });

        // 更新無效類別 -> 拋出 400 (MSG_DATA_INVALID_INPUT)
        assertThatThrownBy(() -> notificationService.updatePreference(userA.getId(), "INVALID_TYPE", true, true))
                .isInstanceOf(NotificationException.class)
                .satisfies(ex -> {
                    NotificationException nEx = (NotificationException) ex;
                    assertThat(nEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(nEx.getError()).isEqualTo("MSG_DATA_INVALID_INPUT");
                });

        // 正常更新其他偏好 -> 成功
        notificationService.updatePreference(userA.getId(), "SUBSCRIPTION_MAINTENANCE", true, true);
        List<NotificationService.PreferenceDto> prefs = notificationService.getPreferences(userA.getId());
        NotificationService.PreferenceDto updated = prefs.stream()
                .filter(p -> "SUBSCRIPTION_MAINTENANCE".equals(p.getCategory()))
                .findFirst().orElseThrow();
        assertThat(updated.isEnableEmail()).isTrue();
    }

    @Test
    @DisplayName("Scenario 3-3: DB CHECK constraint 阻擋 ACCOUNT_AUTH 直接寫入 false (Defense-in-depth)")
    void should_BlockDirectSqlInsert_When_AccountAuthDisabled() {
        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO notification_preferences (id, user_id, category, enable_in_app, enable_email, version) " +
                "VALUES (gen_random_uuid(), ?, 'ACCOUNT_AUTH', true, false, 0)",
                userA.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Scenario 4: 90 天物理清理排程與 LIMIT 分批測試")
    void should_PhysicallyDeleteOldNotificationsInBatches() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        
        // 建立 10 筆 91 天前的通知 (應被清理)
        for (int i = 0; i < 10; i++) {
            Notification oldNoti = Notification.builder()
                    .userId(userA.getId())
                    .title("Old " + i)
                    .content("Content")
                    .category("ORDER_AFFAIR")
                    .roleTarget("ALL")
                    .build();
            notificationRepository.save(oldNoti);
            
            // 使用 jdbcTemplate 物理修改 created_at 避開 JPA updatable = false 與 @PrePersist/PreUpdate 限制
            jdbcTemplate.update("UPDATE notifications SET created_at = ? WHERE id = ?",
                    now.minusDays(91).minusMinutes(i),
                    oldNoti.getId());
        }

        // 建立 5 筆今天的通知 (不應被清理)
        for (int i = 0; i < 5; i++) {
            Notification newNoti = Notification.builder()
                    .userId(userA.getId())
                    .title("New " + i)
                    .content("Content")
                    .category("ORDER_AFFAIR")
                    .roleTarget("ALL")
                    .build();
            notificationRepository.save(newNoti);
        }

        assertThat(notificationRepository.count()).isEqualTo(15);

        // 執行清理任務
        int deleted = notificationCleanupService.cleanupOldNotifications();
        
        // 驗證應被物理刪除 10 筆，剩餘 5 筆
        assertThat(deleted).isEqualTo(10);
        assertThat(notificationRepository.count()).isEqualTo(5);
        
        // 驗證剩餘的都是新通知
        List<Notification> remaining = notificationRepository.findAll();
        assertThat(remaining).allMatch(n -> n.getCreatedAt().isAfter(now.minusDays(90)));
    }

    @Test
    @DisplayName("Scenario 5: 訊息查詢的角色過濾隔離驗證")
    void should_IsolateNotificationsByRoleTarget() {
        // Given: 為同一個使用者 A 建立不同的角色目標通知
        notificationRepository.save(Notification.builder().userId(userA.getId()).title("All Target").content("C").category("ORDER_AFFAIR").roleTarget("ALL").build());
        notificationRepository.save(Notification.builder().userId(userA.getId()).title("Sitter Target").content("C").category("ORDER_AFFAIR").roleTarget("SITTER").build());
        notificationRepository.save(Notification.builder().userId(userA.getId()).title("Owner Target").content("C").category("ORDER_AFFAIR").roleTarget("OWNER").build());

        // When & Then: 以 OWNER 角色查詢
        Page<Notification> ownerPage = notificationService.getNotifications(userA.getId(), "OWNER", null, PageRequest.of(0, 10));
        assertThat(ownerPage.getContent()).hasSize(2); // 應只有 ALL 與 OWNER
        assertThat(ownerPage.getContent()).noneMatch(n -> "SITTER".equals(n.getRoleTarget()));

        // 以 SITTER 角色查詢
        Page<Notification> sitterPage = notificationService.getNotifications(userA.getId(), "SITTER", null, PageRequest.of(0, 10));
        assertThat(sitterPage.getContent()).hasSize(2); // 應只有 ALL 與 SITTER
        assertThat(sitterPage.getContent()).noneMatch(n -> "OWNER".equals(n.getRoleTarget()));

        // 不指定角色查詢 -> 應返回全部 3 筆
        Page<Notification> allPage = notificationService.getNotifications(userA.getId(), null, null, PageRequest.of(0, 10));
        assertThat(allPage.getContent()).hasSize(3);
    }
}
