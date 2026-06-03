package com.petsitter.application.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface MediaStorageService {
    /**
     * 上傳媒體檔案
     * @param sitterId 保母 ID
     * @param ownerId 飼主 ID
     * @param mediaId 媒體 ID (UUID)
     * @param file 檔案
     * @return 檔案可存取的 URL
     */
    String uploadMedia(UUID sitterId, UUID ownerId, UUID mediaId, MultipartFile file);

    /**
     * 上傳服務日誌多媒體檔案 (SD-022)
     */
    String uploadReportMedia(String planTier, String date, UUID orderId, UUID mediaId, MultipartFile file);

    /**
     * 上傳寵物大頭照 (SD-002)
     */
    String uploadPetAvatar(UUID ownerId, UUID petId, MultipartFile file);

    /**
     * 上傳付款憑證 (SD-007)
     */
    String uploadPaymentProof(UUID ownerId, UUID orderId, MultipartFile file);

    /**
     * 刪除媒體檔案
     * @param mediaUrl 檔案的完整 URL
     */
    void deleteMedia(String mediaUrl);
}
