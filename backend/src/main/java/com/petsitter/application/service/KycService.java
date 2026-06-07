package com.petsitter.application.service;

import com.petsitter.domain.model.KycRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

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
}
