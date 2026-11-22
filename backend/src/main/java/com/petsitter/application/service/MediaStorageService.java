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
     * V4 簽名 GET，另簽入 {@code response-content-type={contentType}} 與 {@code response-content-disposition=inline}
     * （SD-FULFILL §8.2、NFR-003 U-7）：GCS 回應型別固定為伺服器判定的格式，不受物件 metadata 影響。
     */
    String generateSignedUrl(String objectKey, java.time.Duration ttl, String contentType);

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

    // ------------------------------------------------------------------ SD-ORDER §3.6.6 付款憑證（私有物件）

    /** 物件清單項目（C10 孤兒檔掃描用）。 */
    record StoredObject(String key, java.time.OffsetDateTime createdAt) {
    }

    /**
     * 上傳私有物件，{@code doesNotExist} 前置條件：物件已存在（接手的重試）視為已上傳，不覆寫。
     * 失敗拋 {@link com.petsitter.application.exception.StorageUnavailableException}。
     */
    void uploadPrivateIfAbsent(String objectKey, byte[] content, String contentType);

    /** best-effort 刪除私有物件；回傳是否刪除（不存在 → false）。失敗拋例外由呼叫端記 log。 */
    boolean deleteObject(String objectKey);

    /** 列出前綴下物件與建立時間。 */
    java.util.List<StoredObject> listObjects(String prefix);

    // ------------------------------------------------------------------ SD-FULFILL §8.2 日誌媒體直傳（私有物件）

    /** 私有物件串流（交易外讀取一次；呼叫端負責關閉）。 */
    interface PrivateObject extends java.io.Closeable {
        long generation();

        java.io.InputStream stream();
    }

    /**
     * V4 簽名 PUT：綁定 {@code Content-Type}、{@code x-goog-content-length-range: {exactBytes},{exactBytes}}、
     * {@code x-goog-if-generation-match: 0}；不帶 {@code x-goog-acl}（套 bucket 預設）。失敗拋
     * {@link com.petsitter.application.exception.StorageUnavailableException}。
     */
    String createSignedUploadUrl(String objectKey, String contentType, long exactBytes, java.time.Duration ttl);

    /** 串流讀取並回傳 generation；不存在回空；讀取失敗拋 {@link com.petsitter.application.exception.MediaInspectionException}。 */
    java.util.Optional<PrivateObject> openPrivateObject(String objectKey);

    /**
     * 刪除私有物件；{@code generation} 非 null 時帶前置條件（物件已被重寫則不刪、回 false）；不存在視為成功（回 true）。
     * 其他失敗拋例外，由呼叫端記 log 或計次。
     */
    boolean deletePrivateObject(String objectKey, Long generation);
}
