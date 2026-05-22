package com.petsitter.application.service;

import com.petsitter.application.dto.CareMediaDto;
import com.petsitter.domain.model.CareMedia;
import com.petsitter.domain.repository.AdvisoryLockRepository;
import com.petsitter.domain.repository.CareMediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CareMediaService {

    private final CareMediaRepository careMediaRepository;
    private final AdvisoryLockRepository advisoryLockRepository;
    private final MediaStorageService mediaStorageService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final SystemConfigService systemConfigService;

    @Transactional(readOnly = true)
    public List<CareMediaDto> getMediaList(UUID sitterId, UUID ownerId) {
        List<CareMedia> mediaList = careMediaRepository.findBySitterIdAndOwnerIdOrderByCreatedAtDesc(sitterId, ownerId);
        return mediaList.stream()
                .map(m -> CareMediaDto.builder()
                        .id(m.getId())
                        .caption(m.getCaption())
                        .mediaUrl(m.getMediaUrl())
                        .mediaType(m.getMediaType())
                        .createdAt(m.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public CareMediaDto uploadMedia(UUID sitterId, UUID ownerId, String caption, String mediaType, MultipartFile file) {
        long lockId = Math.abs((sitterId.toString() + ownerId.toString()).hashCode());
        advisoryLockRepository.acquireXactLock(lockId);

        int currentCount = careMediaRepository.countBySitterIdAndOwnerId(sitterId, ownerId);
        int limit = systemConfigService.getMediaLimit();
        if (currentCount >= limit) {
            throw new IllegalArgumentException("媒體數量已達上限 (" + limit + " 筆)");
        }

        UUID newMediaId = UUID.randomUUID();
        String mediaUrl;
        
        try {
            mediaUrl = mediaStorageService.uploadMedia(sitterId, ownerId, newMediaId, file);
        } catch (Exception e) {
            log.error("Failed to upload media to storage", e);
            throw new RuntimeException("媒體上傳失敗", e);
        }

        CareMedia careMedia = CareMedia.builder()
                .id(newMediaId)
                .sitterId(sitterId)
                .ownerId(ownerId)
                .caption(caption)
                .mediaType(mediaType)
                .mediaUrl(mediaUrl)
                .build();

        try {
            careMedia = careMediaRepository.save(careMedia);
            auditLogService.writeLog(sitterId, "UPLOAD_MEDIA", "SUCCESS", "Media ID: " + careMedia.getId());
            
            try {
                notificationService.sendNotification(ownerId, "媒體庫有新上傳內容");
            } catch (Exception ne) {
                log.error("Failed to send media notification", ne);
            }
            
        } catch (Exception e) {
            // GCS 補償清除 (DB 寫入失敗)
            log.warn("DB save failed, triggering GCS rollback for: {}", mediaUrl);
            try {
                mediaStorageService.deleteMedia(mediaUrl);
            } catch (Exception deleteEx) {
                log.error("CRITICAL: Failed to rollback GCS file after DB failure: {}", mediaUrl, deleteEx);
            }
            auditLogService.writeLog(sitterId, "UPLOAD_MEDIA", "FAILED", e.getMessage());
            throw new RuntimeException("DB save failed, media uploaded was rolled back.", e);
        }

        return CareMediaDto.builder()
                .id(careMedia.getId())
                .caption(careMedia.getCaption())
                .mediaUrl(careMedia.getMediaUrl())
                .mediaType(careMedia.getMediaType())
                .createdAt(careMedia.getCreatedAt())
                .build();
    }

    @Transactional
    public void deleteMedia(UUID sitterId, UUID mediaId) {
        CareMedia careMedia = careMediaRepository.findByIdAndSitterId(mediaId, sitterId)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Media not found or not owned"));
        
        UUID ownerId = careMedia.getOwnerId();
        String mediaUrl = careMedia.getMediaUrl();

        careMediaRepository.delete(careMedia);
        auditLogService.writeLog(sitterId, "DELETE_MEDIA", "SUCCESS", "Media ID: " + mediaId);

        // GCS 反向補償區塊 (同步執行)
        try {
            mediaStorageService.deleteMedia(mediaUrl);
        } catch (Exception e) {
            log.error("GCS 刪除失敗，不 Rollback DB. Media URL: {}", mediaUrl, e);
        }

        try {
            notificationService.sendNotification(ownerId, "保母已移除部分媒體");
        } catch (Exception e) {
            log.error("通知發送失敗", e);
        }
    }
}
