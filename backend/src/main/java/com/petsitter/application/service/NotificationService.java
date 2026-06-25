package com.petsitter.application.service;

import com.petsitter.application.exception.NotificationException;
import com.petsitter.domain.model.Notification;
import com.petsitter.domain.model.NotificationPreference;
import com.petsitter.domain.repository.NotificationPreferenceRepository;
import com.petsitter.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;

    /**
     * 既有方法向下相容，將其導向為 ACCOUNT_AUTH 強制類別通知
     */
    public void sendNotification(UUID userId, String message) {
        createNotification(userId, "系統通知", message, "ACCOUNT_AUTH", null, "ALL");
    }

    /**
     * 建立通知業務邏輯
     */
    @Transactional
    public void createNotification(UUID userId, String title, String content, String category, String linkUrl, String roleTarget) {
        log.info("Creating notification - User: {}, Category: {}, Title: {}", userId, category, title);

        boolean enableInApp = true;
        boolean enableEmail = true;

        // ACCOUNT_AUTH 強制開啟，不需查詢 preferences
        if (!"ACCOUNT_AUTH".equals(category)) {
            NotificationPreference pref = notificationPreferenceRepository
                    .findByUserIdAndCategoryAndIsDeletedFalse(userId, category)
                    .orElse(null);

            if (pref != null) {
                enableInApp = pref.isEnableInApp();
                enableEmail = pref.isEnableEmail();
            } else {
                NotificationPreferenceDefaults.PreferenceValue def = NotificationPreferenceDefaults.DEFAULTS.get(category);
                if (def != null) {
                    enableInApp = def.isEnableInApp();
                    enableEmail = def.isEnableEmail();
                }
            }
        }

        if (enableInApp) {
            Notification notification = Notification.builder()
                    .userId(userId)
                    .title(title)
                    .content(content)
                    .category(category)
                    .linkUrl(linkUrl)
                    .roleTarget(roleTarget != null ? roleTarget : "ALL")
                    .build();
            notificationRepository.save(notification);
            log.info("Saved in-app notification to database. ID: {}", notification.getId());
        }

        if (enableEmail) {
            log.info("MOCK EMAIL SENT to user {}: Title: [{}], Content: [{}]", userId, title, content);
        }
    }

    /**
     * 查詢通知清單 (含角色過濾以實作角色隔離)
     */
    @Transactional(readOnly = true)
    public Page<Notification> getNotifications(UUID userId, String role, Boolean isRead, Pageable pageable) {
        Collection<String> roleTargets = getRoleTargets(role);
        return notificationRepository.findNotifications(userId, roleTargets, isRead, pageable);
    }

    /**
     * 查詢未讀通知數
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId, String role) {
        Collection<String> roleTargets = getRoleTargets(role);
        return notificationRepository.countUnreadNotifications(userId, roleTargets);
    }

    /**
     * 標示已讀 (含防範 IDOR 漏洞與時序枚舉攻擊)
     */
    @Transactional
    public void markAsRead(UUID id, UUID currentUserId) {
        Notification noti = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該通知紀錄"));

        if (!noti.getUserId().equals(currentUserId)) {
            // 故意回傳 404，隱藏資料存在性以防 IDOR 枚舉時序攻擊
            throw new NotificationException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該通知紀錄");
        }

        if (!noti.isRead()) {
            noti.setRead(true);
            noti.setReadAt(OffsetDateTime.now(ZoneOffset.UTC));
            notificationRepository.save(noti);
        }
    }

    /**
     * 一鍵標示已讀 (Bulk Update, 刻意不更新 updated_at / version 以防止 N+1)
     */
    @Transactional
    public void markAllAsRead(UUID userId, String role) {
        Collection<String> roleTargets = getRoleTargets(role);
        notificationRepository.markAllAsRead(userId, roleTargets, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 查詢偏好設定 (動態補齊)
     */
    @Transactional(readOnly = true)
    public List<PreferenceDto> getPreferences(UUID userId) {
        List<NotificationPreference> dbPrefs = notificationPreferenceRepository.findByUserIdAndIsDeletedFalse(userId);
        Map<String, NotificationPreference> dbPrefMap = dbPrefs.stream()
                .collect(Collectors.toMap(NotificationPreference::getCategory, p -> p));

        List<PreferenceDto> result = new ArrayList<>();
        List<String> categories = Arrays.asList("ORDER_AFFAIR", "ACCOUNT_AUTH", "SUBSCRIPTION_MAINTENANCE", "SERVICE_RECORD");
        for (String category : categories) {
            NotificationPreference dbPref = dbPrefMap.get(category);
            if (dbPref != null) {
                result.add(new PreferenceDto(category, dbPref.isEnableInApp(), dbPref.isEnableEmail()));
            } else {
                NotificationPreferenceDefaults.PreferenceValue def = NotificationPreferenceDefaults.DEFAULTS.get(category);
                result.add(new PreferenceDto(category, def.isEnableInApp(), def.isEnableEmail()));
            }
        }
        return result;
    }

    /**
     * 更新偏好設定 (含 ACCOUNT_AUTH 強制開啟防呆)
     */
    @Transactional
    public void updatePreference(UUID userId, String category, boolean enableInApp, boolean enableEmail) {
        if (!NotificationPreferenceDefaults.DEFAULTS.containsKey(category)) {
            throw new NotificationException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "無效的通知類別");
        }

        if ("ACCOUNT_AUTH".equals(category) && (!enableInApp || !enableEmail)) {
            throw new NotificationException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "安全與認證通知為系統核心功能，無法關閉");
        }

        NotificationPreference pref = notificationPreferenceRepository
                .findByUserIdAndCategoryAndIsDeletedFalse(userId, category)
                .orElseGet(() -> NotificationPreference.builder()
                        .userId(userId)
                        .category(category)
                        .build());

        pref.setEnableInApp(enableInApp);
        pref.setEnableEmail(enableEmail);
        notificationPreferenceRepository.save(pref);
    }

    private Collection<String> getRoleTargets(String role) {
        if (role == null || role.trim().isEmpty()) {
            return Arrays.asList("ALL", "SITTER", "OWNER");
        }
        return Arrays.asList("ALL", role.trim().toUpperCase());
    }

    @Value
    public static class PreferenceDto {
        String category;
        boolean enableInApp;
        boolean enableEmail;
    }
}
