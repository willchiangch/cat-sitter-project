package com.petsitter.application.service;

import com.petsitter.application.dto.SitterTrustScoreDto;
import com.petsitter.domain.model.KycRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface KycService {
    KycRecord submitKyc(UUID sitterId, MultipartFile idCardFrontFile, MultipartFile selfieFile, String idempotencyKey);
    
    void checkRateLimit(UUID sitterId);
    
    KycRecord getKycStatus(UUID sitterId);
    
    String generateSignedUrl(UUID currentUserId, String mediaType);
    
    String generateAdminSignedUrl(UUID sitterId, String mediaType);
    
    KycRecord getKycRecordDetail(UUID recordId);
    
    Page<KycRecord> getPendingKycRecords(Pageable pageable);
    
    Page<Object[]> getPendingKycRecordsWithUser(Pageable pageable);
    
    void reviewKyc(UUID recordId, UUID adminId, String action, String rejectReason, String idempotencyKey);
    
    void suspendSitter(UUID sitterId, UUID adminId, String reason, String idempotencyKey);
    
    void unsuspendSitter(UUID sitterId, UUID adminId, String idempotencyKey);

    void updateSitterOpenStatus(UUID sitterId, boolean isOpen);

    boolean getSitterOpenStatus(UUID sitterId);

    /**
     * 內部信用指標管理 (PRD-020 主流程 E)：管理員手動增減保母信用點數
     */
    void adjustTrustScore(UUID sitterId, UUID adminId, int delta, String reason, String idempotencyKey);

    /**
     * 列出所有保母的信用指標，依分數由低到高排序，供管理後台標註高風險名單
     */
    List<SitterTrustScoreDto> listSitterTrustScores();
}
