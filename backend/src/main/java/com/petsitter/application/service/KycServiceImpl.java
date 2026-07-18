package com.petsitter.application.service;

import com.petsitter.application.dto.SitterTrustScoreDto;
import com.petsitter.application.exception.KycException;
import com.petsitter.domain.event.KycReviewedEvent;
import com.petsitter.domain.event.SitterSuspendedEvent;
import com.petsitter.domain.event.SitterUnsuspendedEvent;
import com.petsitter.domain.model.KycRecord;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.UserActionLog;
import com.petsitter.domain.repository.KycRecordRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.UserActionLogRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KycServiceImpl implements KycService {

    private static final int TRUST_SCORE_HIGH_RISK_THRESHOLD = 60;

    private final KycRecordRepository kycRecordRepository;
    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final MediaStorageService mediaStorageService;
    private final IdempotencyService idempotencyService;
    private final UserActionLogRepository userActionLogRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public KycRecord submitKyc(UUID sitterId, MultipartFile idCardFrontFile, MultipartFile selfieFile, String idempotencyKey) {
        log.info("Submitting KYC for sitter: {}", sitterId);

        // 1. 狀態與重複性檢查
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));

        String kycStatus = profile.getKycStatus();
        if ("PENDING_REVIEW".equals(kycStatus) || "VERIFIED".equals(kycStatus) || "SUSPENDED".equals(kycStatus)) {
            throw new KycException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_STATE_CONFLICT", "資料審核中，請勿重複提交");
        }

        // 2. 冪等性校驗
        idempotencyService.checkAndConsume(idempotencyKey, sitterId);

        // 3. 檔案校驗
        validateFile(idCardFrontFile);
        validateFile(selfieFile);

        // 4. 上傳照片
        String idCardFrontKey = mediaStorageService.uploadKycFile(sitterId, "id-front", idCardFrontFile);
        String selfieKey = mediaStorageService.uploadKycFile(sitterId, "selfie", selfieFile);

        // 5. 寫入 KYC 紀錄
        KycRecord record = KycRecord.builder()
                .sitterId(sitterId)
                .idCardFrontKey(idCardFrontKey)
                .selfieKey(selfieKey)
                .status("PENDING")
                .build();
        kycRecordRepository.save(record);

        // 6. 更新 Profile 狀態
        profile.setKycStatus("PENDING_REVIEW");
        profileRepository.save(profile);

        // 7. 寫入審計日誌
        UserActionLog auditLog = UserActionLog.builder()
                .funcCode("SITTER_KYC_SUBMIT")
                .actionType("CREATE")
                .operatorId(sitterId)
                .targetId(record.getId())
                .targetTable("kyc_records")
                .build();
        userActionLogRepository.save(auditLog);

        log.info("Successfully submitted KYC for sitter: {}, record: {}", sitterId, record.getId());
        return record;
    }

    @Override
    @Transactional(readOnly = true)
    public void checkRateLimit(UUID sitterId) {
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        long count = kycRecordRepository.countBySitterIdAndCreatedAtAfter(sitterId, since);
        if (count >= 5) {
            throw new KycException(HttpStatus.TOO_MANY_REQUESTS, "MSG_DATA_RATE_LIMIT_EXCEEDED", "提交次數過於頻繁，請於一小時後再試");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public KycRecord getKycStatus(UUID sitterId) {
        profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));
        return kycRecordRepository.findFirstBySitterIdOrderByCreatedAtDesc(sitterId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public String generateSignedUrl(UUID currentUserId, String mediaType) {
        KycRecord record = kycRecordRepository.findFirstBySitterIdOrderByCreatedAtDesc(currentUserId)
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該 KYC 紀錄"));

        String objectKey = getObjectKeyByMediaType(record, mediaType);
        return mediaStorageService.generateSignedUrl(objectKey, Duration.ofMinutes(10));
    }

    @Override
    @Transactional(readOnly = true)
    public String generateAdminSignedUrl(UUID sitterId, String mediaType) {
        KycRecord record = kycRecordRepository.findFirstBySitterIdOrderByCreatedAtDesc(sitterId)
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該 KYC 紀錄"));

        String objectKey = getObjectKeyByMediaType(record, mediaType);
        return mediaStorageService.generateSignedUrl(objectKey, Duration.ofMinutes(10));
    }

    @Override
    @Transactional(readOnly = true)
    public KycRecord getKycRecordDetail(UUID recordId) {
        return kycRecordRepository.findById(recordId)
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該 KYC 紀錄"));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<KycRecord> getPendingKycRecords(Pageable pageable) {
        return kycRecordRepository.findByStatus("PENDING", pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Object[]> getPendingKycRecordsWithUser(Pageable pageable) {
        return kycRecordRepository.findPendingWithUser(pageable);
    }

    @Override
    @Transactional
    public void reviewKyc(UUID recordId, UUID adminId, String action, String rejectReason, String idempotencyKey) {
        log.info("Reviewing KYC record: {} by admin: {}, action: {}", recordId, adminId, action);

        idempotencyService.checkAndConsume(idempotencyKey, adminId);

        KycRecord record = kycRecordRepository.findById(recordId)
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該 KYC 紀錄"));

        if (!"PENDING".equals(record.getStatus())) {
            throw new KycException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "該紀錄非待審核狀態，無法執行審核動作");
        }

        Profile profile = profileRepository.findByUserIdAndType(record.getSitterId(), "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));

        if ("APPROVE".equalsIgnoreCase(action)) {
            record.setStatus("APPROVED");
            profile.setKycStatus("VERIFIED");
        } else if ("REJECT".equalsIgnoreCase(action)) {
            if (rejectReason == null || rejectReason.trim().isEmpty()) {
                throw new KycException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "駁回原因不可為空");
            }
            record.setStatus("REJECTED");
            record.setRejectReason(rejectReason);
            profile.setKycStatus("REJECTED");
            profile.setOpen(false); // 強制關閉接單
        } else {
            throw new KycException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "無效的審核動作");
        }

        record.setReviewedBy(adminId);
        record.setReviewedAt(OffsetDateTime.now(ZoneOffset.UTC));

        kycRecordRepository.save(record);
        profileRepository.save(profile);

        // 寫入審計日誌
        UserActionLog auditLog = UserActionLog.builder()
                .funcCode("ADMIN_KYC_REVIEW")
                .actionType("UPDATE")
                .operatorId(adminId)
                .targetId(record.getId())
                .targetTable("kyc_records")
                .build();
        userActionLogRepository.save(auditLog);

        // 發布非同步通知事件
        eventPublisher.publishEvent(new KycReviewedEvent(record.getId(), record.getSitterId(), record.getStatus(), record.getRejectReason()));
        log.info("Successfully reviewed KYC record: {} status is now: {}", recordId, record.getStatus());
    }

    @Override
    @Transactional
    public void suspendSitter(UUID sitterId, UUID adminId, String reason, String idempotencyKey) {
        log.info("Suspending sitter: {} by admin: {}, reason: {}", sitterId, adminId, reason);

        idempotencyService.checkAndConsume(idempotencyKey, adminId);

        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));

        if (!"VERIFIED".equals(profile.getKycStatus())) {
            throw new KycException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "該保母未處於已驗證接單狀態，無法停權");
        }

        if (reason == null || reason.trim().isEmpty()) {
            throw new KycException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "停權原因不可為空");
        }

        profile.setKycStatus("SUSPENDED");
        profile.setOpen(false); // 強制關閉接單
        profileRepository.save(profile);

        // 寫入審計日誌
        UserActionLog auditLog = UserActionLog.builder()
                .funcCode("ADMIN_SITTER_SUSPEND")
                .actionType("UPDATE")
                .operatorId(adminId)
                .targetId(profile.getId())
                .targetTable("profiles")
                .build();
        userActionLogRepository.save(auditLog);

        eventPublisher.publishEvent(new SitterSuspendedEvent(sitterId, reason));
        log.info("Successfully suspended sitter: {}", sitterId);
    }

    @Override
    @Transactional
    public void unsuspendSitter(UUID sitterId, UUID adminId, String idempotencyKey) {
        log.info("Unsuspending sitter: {} by admin: {}", sitterId, adminId);

        idempotencyService.checkAndConsume(idempotencyKey, adminId);

        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));

        if (!"SUSPENDED".equals(profile.getKycStatus())) {
            throw new KycException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "該保母未被停權，無法執行解除操作");
        }

        profile.setKycStatus("VERIFIED");
        // 解除停權不自動開啟 is_open，維持 false
        profileRepository.save(profile);

        // 寫入審計日誌
        UserActionLog auditLog = UserActionLog.builder()
                .funcCode("ADMIN_SITTER_UNSUSPEND")
                .actionType("UPDATE")
                .operatorId(adminId)
                .targetId(profile.getId())
                .targetTable("profiles")
                .build();
        userActionLogRepository.save(auditLog);

        eventPublisher.publishEvent(new SitterUnsuspendedEvent(sitterId));
        log.info("Successfully unsuspended sitter: {}", sitterId);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new KycException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "證件照片不可為空");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new KycException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "證件照片格式僅限 JPG/PNG 且大小需在 5MB 以內");
        }
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/jpg"))) {
            throw new KycException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "證件照片格式僅限 JPG/PNG 且大小需在 5MB 以內");
        }
    }

    private String getObjectKeyByMediaType(KycRecord record, String mediaType) {
        if ("ID_CARD_FRONT".equalsIgnoreCase(mediaType)) {
            return record.getIdCardFrontKey();
        } else if ("SELFIE".equalsIgnoreCase(mediaType)) {
            return record.getSelfieKey();
        } else {
            throw new KycException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "無效的媒體類型");
        }
    }

    @Override
    @Transactional
    public void updateSitterOpenStatus(UUID sitterId, boolean isOpen) {
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));
        
        if (isOpen && !"VERIFIED".equals(profile.getKycStatus())) {
            throw new KycException(HttpStatus.FORBIDDEN, "MSG_DATA_INVALID_STATUS", "保母需先通過實名審查才能開啟接單狀態");
        }
        
        profile.setOpen(isOpen);
        profileRepository.save(profile);
        log.info("Successfully updated open status to {} for sitter {}", isOpen, sitterId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean getSitterOpenStatus(UUID sitterId) {
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));
        return profile.isOpen();
    }

    @Override
    @Transactional
    public void adjustTrustScore(UUID sitterId, UUID adminId, int delta, String reason, String idempotencyKey) {
        log.info("Adjusting trust score for sitter: {} by admin: {}, delta: {}", sitterId, adminId, delta);

        idempotencyService.checkAndConsume(idempotencyKey, adminId);

        if (reason == null || reason.trim().isEmpty()) {
            throw new KycException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "異動原因不可為空");
        }

        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));

        int previousScore = profile.getTrustScore();
        profile.setTrustScore(previousScore + delta);
        profileRepository.save(profile);

        auditLogService.writeLog(adminId, "ADMIN_TRUST_SCORE_ADJUST", "SUCCESS",
                String.format("sitterId=%s, delta=%+d, previousScore=%d, newScore=%d, reason=%s",
                        sitterId, delta, previousScore, profile.getTrustScore(), reason));

        log.info("Trust score for sitter {} adjusted from {} to {}", sitterId, previousScore, profile.getTrustScore());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SitterTrustScoreDto> listSitterTrustScores() {
        return profileRepository.findAllSitterProfilesOrderByTrustScoreAsc().stream()
                .map(profile -> {
                    User user = userRepository.findById(profile.getUserId()).orElse(null);
                    return SitterTrustScoreDto.builder()
                            .sitterId(profile.getUserId())
                            .fullName(user != null ? user.getFullName() : "")
                            .email(user != null ? user.getEmail() : "")
                            .trustScore(profile.getTrustScore())
                            .highRisk(profile.getTrustScore() < TRUST_SCORE_HIGH_RISK_THRESHOLD)
                            .kycStatus(profile.getKycStatus())
                            .build();
                })
                .toList();
    }
}
