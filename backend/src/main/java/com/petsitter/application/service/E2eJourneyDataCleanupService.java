package com.petsitter.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 硬刪除 frontend/e2e/journeys/ 真後端 journey 測試在正式站產生的假資料。
 * 只認 email 網域 @e2e-journey.test（見 E2eJourneyAccountProvisioningService），
 * 不影響其他任何真實使用者或既有種子測試帳號（sitter@test.com/owner@test.com，
 * 那批由既有的 TestDataCleanupService 處理，兩者職責分開）。
 *
 * 因為這批資料本來就是假的、彼此之間也沒有真實業務關聯，這裡用真的 hard DELETE
 * （不像既有 TestDataCleanupService 只 soft-delete），並在刪除 DB 紀錄前，
 * 先把對應的 GCS 媒體物件（KYC 證件照、寵物頭像、日誌照片）一併清掉，避免正式
 * bucket 累積測試垃圾。刪除順序依照現有 FK 依賴關係手動排序（多數 FK 未設定
 * ON DELETE CASCADE），若之後新增的 journey 會碰到這裡未涵蓋的資料表，需要照樣
 * 補一段刪除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class E2eJourneyDataCleanupService {

    private static final String EMAIL_PATTERN = "%@e2e-journey.test";

    @PersistenceContext
    private EntityManager entityManager;

    private final MediaStorageService mediaStorageService;

    @Transactional
    public int cleanup() {
        List<UUID> userIds = findTestUserIds();
        if (userIds.isEmpty()) {
            log.info("[E2eJourneyDataCleanupService] No @e2e-journey.test accounts found, nothing to clean up");
            return 0;
        }

        deleteOrphanedMedia(userIds);

        nativeDelete("DELETE FROM order_logs WHERE order_id IN "
                + "(SELECT id FROM orders WHERE owner_id IN (:ids) OR sitter_id IN (:ids))", userIds);
        nativeDelete("DELETE FROM order_snapshots WHERE order_id IN "
                + "(SELECT id FROM orders WHERE owner_id IN (:ids) OR sitter_id IN (:ids))", userIds);
        nativeDelete("DELETE FROM visits WHERE order_id IN "
                + "(SELECT id FROM orders WHERE owner_id IN (:ids) OR sitter_id IN (:ids))", userIds);
        nativeDelete("DELETE FROM orders WHERE owner_id IN (:ids) OR sitter_id IN (:ids)", userIds);

        nativeDelete("DELETE FROM gatekeeper_rules WHERE sitter_id IN (:ids) OR target_user_id IN (:ids)", userIds);
        nativeDelete("DELETE FROM kyc_records WHERE sitter_id IN (:ids)", userIds);
        nativeDelete("DELETE FROM service_plans WHERE sitter_id IN (:ids)", userIds);
        nativeDelete("DELETE FROM pets WHERE owner_id IN (:ids)", userIds);
        nativeDelete("DELETE FROM notifications WHERE user_id IN (:ids)", userIds);
        nativeDelete("DELETE FROM notification_preferences WHERE user_id IN (:ids)", userIds);
        nativeDelete("DELETE FROM profiles WHERE user_id IN (:ids)", userIds);
        nativeDelete("DELETE FROM log_user_action WHERE operator_id IN (:ids)", userIds);
        nativeDelete("DELETE FROM subscriptions WHERE sitter_id IN (:ids)", userIds);

        // refresh_tokens / care_notes / care_note_items / care_logs / care_media 都對 users(id)
        // 設定了 ON DELETE CASCADE 或 SET NULL，刪 users 時會自動處理，不用手動清
        int deletedUsers = nativeDelete("DELETE FROM users WHERE id IN (:ids)", userIds);

        log.info("[E2eJourneyDataCleanupService] Deleted {} @e2e-journey.test accounts and their data", deletedUsers);
        return deletedUsers;
    }

    private List<UUID> findTestUserIds() {
        List<?> rows = entityManager.createNativeQuery("SELECT id FROM users WHERE email LIKE :pattern")
                .setParameter("pattern", EMAIL_PATTERN)
                .getResultList();
        return rows.stream().map(row -> UUID.fromString(row.toString())).toList();
    }

    /**
     * 刪除 DB 紀錄前先把對應的 GCS 媒體物件清掉，單一物件刪除失敗不中斷整個清理流程
     * （沿用 deleteMedia 可同時接受完整 URL 或 GCS object key 的既有行為）
     */
    private void deleteOrphanedMedia(List<UUID> userIds) {
        selectStrings("SELECT id_card_front_key FROM kyc_records WHERE sitter_id IN (:ids)", userIds)
                .forEach(this::deleteMediaSafely);
        selectStrings("SELECT selfie_key FROM kyc_records WHERE sitter_id IN (:ids)", userIds)
                .forEach(this::deleteMediaSafely);
        selectStrings("SELECT photo_url FROM pets WHERE owner_id IN (:ids) AND photo_url IS NOT NULL", userIds)
                .forEach(this::deleteMediaSafely);
        selectStrings("SELECT srm.media_url FROM service_report_media srm "
                + "JOIN visit_service_reports vsr ON srm.report_id = vsr.id "
                + "JOIN visits v ON vsr.visit_id = v.id "
                + "JOIN orders o ON v.order_id = o.id "
                + "WHERE o.owner_id IN (:ids) OR o.sitter_id IN (:ids)", userIds)
                .forEach(this::deleteMediaSafely);
    }

    private void deleteMediaSafely(String mediaUrlOrKey) {
        try {
            mediaStorageService.deleteMedia(mediaUrlOrKey);
        } catch (Exception e) {
            log.warn("[E2eJourneyDataCleanupService] Failed to delete GCS object: {}", mediaUrlOrKey, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> selectStrings(String sql, List<UUID> userIds) {
        return (List<String>) entityManager.createNativeQuery(sql)
                .setParameter("ids", userIds)
                .getResultList();
    }

    private int nativeDelete(String sql, List<UUID> userIds) {
        Query query = entityManager.createNativeQuery(sql).setParameter("ids", userIds);
        return query.executeUpdate();
    }
}
