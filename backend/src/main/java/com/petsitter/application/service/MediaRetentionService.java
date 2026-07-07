package com.petsitter.application.service;

public interface MediaRetentionService {
    /**
     * 物理清理已過期的日誌媒體檔案，並在資料庫中標記為 is_purged = true
     * @return 成功清理的筆數
     */
    int cleanupExpiredMedia();

    /**
     * 掃描即將過期的日誌（前 3 天），並發送提醒通知
     * @return 成功發送通知的筆數
     */
    int sendExpiryWarnings();

    /**
     * 當保母方案升級時，追溯展延名下所有尚未物理清理的訂單媒體保留期限
     * @param sitterId 保母 ID
     * @param operatorId 執行此覆寫操作的使用者 ID (管理員)
     * @param newPlanTier 新方案等級
     */
    void upgradeSitterMediaRetention(java.util.UUID sitterId, java.util.UUID operatorId, String newPlanTier);
}

