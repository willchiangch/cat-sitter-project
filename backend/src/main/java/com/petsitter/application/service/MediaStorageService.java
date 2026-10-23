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
     * 上傳付款憑證 (SD-007 §5.1)。財務憑證含飼主轉帳帳號/姓名等個資，一律以私有物件上傳，
     * 只回傳 Object Key（例如 payment-proofs/2026-10-24/{orderId}_{uuid}.jpg），
     * 讀取時一律透過 {@link #generateSignedUrl} 取短效簽名網址，不得回傳公開網址。
     */
    String uploadPaymentProof(UUID ownerId, UUID orderId, MultipartFile file);

    /**
     * 上傳訂單變更的退款轉帳憑證 (SD-016 §2.5)。與付款憑證同為財務個資，一律私有物件，
     * 回傳 Object Key（refund-proofs/2026-10-24/{orderId}_{uuid}.jpg），檢視走 {@link #generateSignedUrl}。
     */
    String uploadRefundProof(UUID sitterId, UUID orderId, MultipartFile file);

    /**
     * 上傳保母 KYC 證件照 (SD-017)
     * @param sitterId 保母 ID
     * @param type 證件類型 (e.g. "id-front", "selfie")
     * @param file 檔案
     * @return 儲存的 Object Key (例如: kyc/sitter-id/id-front.jpg)
     */
    String uploadKycFile(UUID sitterId, String type, MultipartFile file);

    /**
     * 上傳保母大頭照 (SD-018)
     */
    String uploadAvatar(UUID sitterId, MultipartFile file);

    /**
     * 刪除媒體檔案
     * @param mediaUrl 檔案的完整 URL
     */
    void deleteMedia(String mediaUrl);


    /**
     * 產生私有儲存空間物件之短效安全簽名 URL
     * @param objectKey 儲存物件 Key
     * @param ttl 有效期限
     * @return 簽名 URL
     */
    String generateSignedUrl(String objectKey, java.time.Duration ttl);

    /**
     * 將 DB 內存放的媒體參照轉為 Object Key。2026-10 之前的付款憑證存的是公開網址
     * (https://storage.googleapis.com/{bucket}/payment-proofs/...)，新資料存的是 Object Key，
     * 讀取端一律先過這裡再簽名，兩種格式都能相容。
     */
    String resolveObjectKey(String storedRef);

    /**
     * 列出指定前綴下的所有 Object Key（供一次性遷移/維運端點使用）
     */
    java.util.List<String> listObjectKeys(String prefix);

    /**
     * 該物件目前是否帶有 allUsers READER ACL（任何人可公開讀取）
     */
    boolean isPubliclyReadable(String objectKey);

    /**
     * 移除該物件的 allUsers ACL，使其變成私有物件
     * @return 是否真的有移除（原本就不是公開的回 false）
     */
    boolean revokePublicRead(String objectKey);
}
