package com.petsitter.application.service;

import com.petsitter.application.dto.ReportMediaDto;
import com.petsitter.application.dto.VisitServiceReportDto;
import com.petsitter.application.event.VisitNotificationEvent;
import com.petsitter.application.exception.VisitReportException;
import com.petsitter.application.service.fulfill.VisitExitCause;
import com.petsitter.application.service.order.Actor;
import com.petsitter.application.service.order.LockedOrder;
import com.petsitter.application.service.order.OrderLifecycleService;
import com.petsitter.application.service.order.OrderLockService;
import com.petsitter.application.service.order.OrderLogWriter;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.fulfill.ServiceReportMedia;
import com.petsitter.domain.model.fulfill.ServiceReportUpload;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.fulfill.VisitServiceReport;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.ServiceReportMediaRepository;
import com.petsitter.domain.repository.fulfill.ServiceReportUploadRepository;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import com.petsitter.domain.repository.fulfill.VisitServiceReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 行程與照護日誌（SD-FULFILL 範圍）的 B 階段過渡版：欄位對齊新 schema，並依 SD-ORDER M1–M3 改為
 * 「不加鎖取 orderId → {@code OrderLockService.lockForTransition} → 鎖後重讀 visit → 改寫 → OrderLogWriter（同交易）」，
 * 開始 / 結束行程經 {@code OrderLifecycleService}。端點與契約不變。
 * TODO(C 階段 F-M1 / F-M2)：依 SD-FULFILL 全面重寫（新端點、共用冪等、檢核項、媒體三段式上傳）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitReportService {

    private final VisitServiceReportRepository reportRepository;
    private final ServiceReportMediaRepository mediaRepository;
    private final ServiceReportUploadRepository uploadRepository;
    private final VisitRepository visitRepository;
    private final OrderLockService orderLockService;
    private final OrderLifecycleService orderLifecycleService;
    private final OrderLogWriter orderLogWriter;
    private final OrderQueryService orderQueryService;
    private final LegacyIdempotencyService legacyIdempotencyService;
    private final MediaStorageService mediaStorageService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    public boolean isExpired(VisitServiceReport report, Visit visit) {
        if (report != null && "SUBMITTED".equals(report.getStatus())) {
            return false;
        }
        return visit.getCompletedAt() != null && !now().isBefore(visit.getCompletedAt().plusHours(24));
    }

    /** 鎖前只取 orderId（不載入 visit），鎖後才載入 visit（SD-FULFILL §2.5 第 1 點）。 */
    private Locked lockByVisit(UUID visitId) {
        UUID orderId = visitRepository.findOrderIdById(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));
        LockedOrder order = orderLockService.lockForTransition(orderId);
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));
        return new Locked(order, visit);
    }

    private record Locked(LockedOrder order, Visit visit) {
    }

    private static void requireSitter(Visit visit, UUID sitterId) {
        if (!visit.getSitterId().equals(sitterId)) {
            throw new AccessDeniedException("權限不足");
        }
    }

    private void consumeLegacyKey(String idempotencyKey, UUID userId) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                legacyIdempotencyService.checkAndConsume(idempotencyKey, userId);
            } catch (DataIntegrityViolationException e) {
                throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_IDEMPOTENCY_CONFLICT", "系統已受理此請求，請勿重複提交");
            }
        }
    }

    private VisitServiceReport getOrCreateDraftReport(UUID visitId, UUID sitterId) {
        return reportRepository.findByVisitId(visitId).orElseGet(() -> reportRepository.save(VisitServiceReport.builder()
                .visitId(visitId)
                .status("DRAFT")
                .content("")
                .createdBy(sitterId)
                .updatedBy(sitterId)
                .build()));
    }

    private void requireEditable(Visit visit, Optional<VisitServiceReport> report, String notStartedMessage) {
        if (visit.getStatus() != VisitStatus.IN_PROGRESS && visit.getStatus() != VisitStatus.DONE) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_VISIT_STATUS", notStartedMessage);
        }
        if (isExpired(report.orElse(null), visit)) {
            throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_REPORT_EXPIRED", "已逾期 24 小時，無法編輯");
        }
        if (report.isPresent() && "SUBMITTED".equals(report.get().getStatus())) {
            throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_STATE_CONFLICT", "日誌已送出，無法修改");
        }
    }

    @Transactional
    public VisitServiceReportDto saveDraft(UUID visitId, String content, Integer version, UUID sitterId, String idempotencyKey) {
        Locked l = lockByVisit(visitId);
        requireSitter(l.visit(), sitterId);
        requireEditable(l.visit(), reportRepository.findByVisitId(visitId), "行程尚未開始，無法編輯日誌");
        consumeLegacyKey(idempotencyKey, sitterId);
        VisitServiceReport report = getOrCreateDraftReport(visitId, sitterId);
        if (report.getVersion() != null && version != null && !report.getVersion().equals(version)) {
            throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_VERSION_CONFLICT", "內容已被更新，請重新整理後再試");
        }
        report.setContent(content);
        report.setUpdatedBy(sitterId);
        report = reportRepository.saveAndFlush(report);
        return convertToDto(report, l.visit());
    }

    @Transactional
    public ReportMediaDto uploadMedia(UUID visitId, MultipartFile file, String caption, String mediaType, UUID sitterId, String idempotencyKey) {
        Locked l = lockByVisit(visitId);
        Visit visit = l.visit();
        requireSitter(visit, sitterId);
        requireEditable(visit, reportRepository.findByVisitId(visitId), "行程尚未開始，無法上傳媒體");
        consumeLegacyKey(idempotencyKey, sitterId);
        VisitServiceReport report = getOrCreateDraftReport(visitId, sitterId);

        OrderQueryService.MediaQuotaSnapshot quota = orderQueryService.getMediaQuota(visit.getOrderId())
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到方案快照"));
        String type = mediaType == null ? "" : mediaType.toUpperCase();
        int photos = mediaRepository.countByReportIdAndMediaType(report.getId(), "IMAGE");
        int videos = mediaRepository.countByReportIdAndMediaType(report.getId(), "VIDEO");
        if ("IMAGE".equals(type)) {
            if (photos >= quota.maxPhotos()) {
                throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_PLAN_LIMIT", "照片上傳數量已達方案上限");
            }
        } else if ("VIDEO".equals(type)) {
            if (videos >= quota.maxVideos()) {
                throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_PLAN_LIMIT", "影片上傳數量已達方案上限");
            }
        } else {
            throw new VisitReportException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "不支援的媒體類型");
        }
        String contentType = validateFile(file, type);

        UUID fileUuid = UUID.randomUUID();
        OffsetDateTime now = now();
        String objectRef;
        try {
            objectRef = mediaStorageService.uploadReportMedia(quota.planTier(),
                    now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), visit.getOrderId(), fileUuid, file);
        } catch (Exception e) {
            log.error("Storage service unavailable during report media upload", e);
            throw new VisitReportException(HttpStatus.SERVICE_UNAVAILABLE, "MSG_DATA_STORAGE_ERROR", "儲存服務暫時無法使用，請稍候重試");
        }
        ServiceReportUpload upload = uploadRepository.save(ServiceReportUpload.builder()
                .visitId(visitId)
                .orderId(visit.getOrderId())
                .sitterId(sitterId)
                .mediaType(type)
                .contentType(contentType)
                .declaredSize((int) file.getSize())
                .declaredSha256(sha256(file))
                .declaredDurationSeconds("VIDEO".equals(type) ? (short) 0 : null)
                .caption(caption)
                .objectKey(objectRef)
                .status("COMPLETED")
                .receivedAt(now)
                .expiresAt(now.plusMinutes(30))
                .completedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
        ServiceReportMedia media = mediaRepository.save(ServiceReportMedia.builder()
                .reportId(report.getId())
                .uploadId(upload.getId())
                .objectKey(objectRef)
                .objectGeneration(0L)
                .mediaType(type)
                .sizeBytes((int) file.getSize())
                .durationSeconds("VIDEO".equals(type) ? (short) 0 : null)
                .caption(caption)
                .uploadedBy(sitterId)
                .uploadedAt(now)
                .build());
        orderLogWriter.write(l.order(), "MEDIA_UPLOADED", Actor.sitter(sitterId),
                Map.of("visitId", visitId.toString(), "careLogId", report.getId().toString(),
                        "mediaId", media.getId().toString(), "mediaType", type));
        return ReportMediaDto.builder()
                .mediaId(media.getId())
                .mediaUrl(media.getObjectKey())
                .mediaType(media.getMediaType())
                .caption(media.getCaption())
                .build();
    }

    private static String validateFile(MultipartFile file, String type) {
        String contentType = file.getContentType();
        String name = file.getOriginalFilename();
        String ext = name != null && name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if ("IMAGE".equals(type)) {
            List<String> allowed = List.of("image/jpeg", "image/png", "image/webp");
            boolean ok = allowed.contains(contentType) || Arrays.asList("jpg", "jpeg", "png", "webp").contains(ext);
            if (!ok || file.getSize() > 1024 * 1024) {
                throw new VisitReportException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "圖片僅支援 JPG/PNG/WebP 且大小不可超過 1MB");
            }
            return allowed.contains(contentType) ? contentType : ("png".equals(ext) ? "image/png" : "webp".equals(ext) ? "image/webp" : "image/jpeg");
        }
        List<String> allowed = List.of("video/mp4", "video/quicktime");
        boolean ok = allowed.contains(contentType) || Arrays.asList("mp4", "mov").contains(ext);
        if (!ok || file.getSize() > 50L * 1024 * 1024) {
            throw new VisitReportException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "影片僅支援 MP4/MOV 且大小不可超過 50MB");
        }
        return allowed.contains(contentType) ? contentType : ("mov".equals(ext) ? "video/quicktime" : "video/mp4");
    }

    private static String sha256(MultipartFile file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes()));
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }

    @Transactional
    public void deleteMedia(UUID mediaId, Integer version, UUID sitterId, String idempotencyKey) {
        ServiceReportMedia probe = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到媒體檔案"));
        VisitServiceReport probeReport = reportRepository.findById(probe.getReportId())
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到服務日誌"));
        Locked l = lockByVisit(probeReport.getVisitId());
        requireSitter(l.visit(), sitterId);
        VisitServiceReport report = reportRepository.findById(probe.getReportId()).orElseThrow();
        if ("SUBMITTED".equals(report.getStatus()) || isExpired(report, l.visit())) {
            throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_STATE_CONFLICT", "日誌已送出或已逾期，不可刪除媒體");
        }
        consumeLegacyKey(idempotencyKey, sitterId);
        mediaRepository.delete(probe);
        orderLogWriter.write(l.order(), "MEDIA_DELETED", Actor.sitter(sitterId),
                Map.of("visitId", l.visit().getId().toString(), "careLogId", report.getId().toString(),
                        "mediaId", mediaId.toString()));
    }

    @Transactional
    public void submitReport(UUID visitId, UUID sitterId, String idempotencyKey) {
        Locked l = lockByVisit(visitId);
        Visit visit = l.visit();
        requireSitter(visit, sitterId);
        VisitServiceReport report = reportRepository.findByVisitId(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到服務日誌"));
        if (visit.getStatus() != VisitStatus.DONE) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_VISIT_NOT_FINISHED", "行程尚未結束，不可送出日誌");
        }
        if (isExpired(report, visit)) {
            throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_REPORT_EXPIRED", "已逾期 24 小時，無法送出");
        }
        consumeLegacyKey(idempotencyKey, sitterId);
        if (!"DRAFT".equals(report.getStatus())) {
            throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_STATE_CONFLICT", "日誌非草稿狀態，無法送出");
        }
        report.setStatus("SUBMITTED");
        report.setSubmittedAt(now());
        report.setUpdatedBy(sitterId);
        orderLogWriter.write(l.order(), "CARE_LOG_SUBMITTED", Actor.sitter(sitterId),
                Map.of("visitId", visitId.toString(), "careLogId", report.getId().toString()));
        // 照護日誌送出通知（站內；TODO(C 階段 F-M9) 改 enqueue CARE_LOG_SUBMITTED）
        notificationService.sendNotification(visit.getOwnerId(),
                visit.getServiceDate() + " 的行程日誌已由保母回報，快來看看毛孩的近況吧！");
    }

    @Transactional(readOnly = true)
    public VisitServiceReportDto getReport(UUID visitId, UUID userId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));
        if (!visit.getSitterId().equals(userId) && !visit.getOwnerId().equals(userId)) {
            throw new AccessDeniedException("權限不足");
        }
        Optional<VisitServiceReport> optReport = reportRepository.findByVisitId(visitId);
        if (visit.getOwnerId().equals(userId)) {
            VisitServiceReport report = optReport.filter(r -> "SUBMITTED".equals(r.getStatus()))
                    .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "查無服務日誌"));
            return convertToDto(report, visit);
        }
        if (optReport.isEmpty()) {
            return VisitServiceReportDto.builder()
                    .visitId(visitId)
                    .status("DRAFT")
                    .content("")
                    .media(List.of())
                    .isEditable(visit.getStatus() == VisitStatus.IN_PROGRESS
                            || (visit.getStatus() == VisitStatus.DONE && !isExpired(null, visit)))
                    .version(0)
                    .visitStatus(visit.getStatus().name())
                    .isPurged(false)
                    .build();
        }
        return convertToDto(optReport.get(), visit);
    }

    private VisitServiceReportDto convertToDto(VisitServiceReport report, Visit visit) {
        List<ServiceReportMedia> mediaList = mediaRepository.findByReportIdOrderByUploadedAtAsc(report.getId());
        List<ReportMediaDto> mediaDtos = mediaList.stream()
                .map(m -> ReportMediaDto.builder()
                        .mediaId(m.getId())
                        .mediaUrl(m.getObjectKey())
                        .mediaType(m.getMediaType())
                        .caption(m.getCaption())
                        .isPurged(m.isPurged())
                        .build())
                .toList();
        Optional<OrderQueryService.MediaQuotaSnapshot> quota = orderQueryService.getMediaQuota(visit.getOrderId());
        Integer retention = quota.map(OrderQueryService.MediaQuotaSnapshot::mediaRetentionDays).orElse(null);
        return VisitServiceReportDto.builder()
                .reportId(report.getId())
                .visitId(report.getVisitId())
                .status(report.getStatus())
                .content(report.getContent())
                .submittedAt(report.getSubmittedAt())
                .media(mediaDtos)
                .isEditable("DRAFT".equals(report.getStatus()) && !isExpired(report, visit))
                .version(report.getVersion())
                .visitStatus(visit.getStatus().name())
                .mediaRetentionDays(retention)
                .isPurged(mediaList.stream().anyMatch(ServiceReportMedia::isPurged))
                .build();
    }

    /** M1：開始行程經訂單鎖與 {@code onVisitLeftPending(STARTED)}（O9 由 SD-ORDER 寫 ORDER_STARTED）。 */
    @Transactional
    public Map<String, String> startVisit(UUID visitId, UUID sitterId, String idempotencyKey) {
        Locked l = lockByVisit(visitId);
        Visit visit = l.visit();
        requireSitter(visit, sitterId);
        if (visit.getStatus() != VisitStatus.PENDING) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "行程非待執行狀態，無法開始");
        }
        OrderStatus orderStatus = l.order().status();
        if (orderStatus != OrderStatus.CONFIRMED && orderStatus != OrderStatus.IN_PROGRESS && orderStatus != OrderStatus.DISPUTED) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "訂單狀態不符合執行要求");
        }
        consumeLegacyKey(idempotencyKey, sitterId);
        visit.setStatus(VisitStatus.IN_PROGRESS);
        visit.setStartedAt(now());
        visitRepository.flush();
        OrderStatus before = l.order().status();
        OrderStatus after = orderLifecycleService.onVisitLeftPending(l.order(), visitId, VisitExitCause.STARTED, Actor.sitter(sitterId));
        if (before == after) {
            orderLogWriter.write(l.order(), "VISIT_STARTED", Actor.sitter(sitterId), Map.of("visitId", visitId.toString()));
        }
        eventPublisher.publishEvent(new VisitNotificationEvent(visit.getOwnerId(), visit.getOrderId(), "您的保母已開始今日的照護服務！"));
        return Map.of("visitId", visitId.toString(), "visitStatus", visit.getStatus().name(), "orderStatus", after.name());
    }

    /** M2：結束行程經訂單鎖與 {@code onVisitFinished}（重算結案基準）。 */
    @Transactional
    public Map<String, String> endVisit(UUID visitId, UUID sitterId, String idempotencyKey) {
        Locked l = lockByVisit(visitId);
        Visit visit = l.visit();
        requireSitter(visit, sitterId);
        if (visit.getStatus() != VisitStatus.IN_PROGRESS) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "行程非執行中狀態，無法結束");
        }
        consumeLegacyKey(idempotencyKey, sitterId);
        visit.setStatus(VisitStatus.DONE);
        visit.setCompletedAt(now());
        visit.setCompletionSource("SITTER");
        visitRepository.flush();
        orderLifecycleService.onVisitFinished(l.order());
        orderLogWriter.write(l.order(), "VISIT_COMPLETED", Actor.sitter(sitterId),
                Map.of("visitId", visitId.toString(), "completedAt", visit.getCompletedAt().toString()));
        eventPublisher.publishEvent(new VisitNotificationEvent(visit.getOwnerId(), visit.getOrderId(), "您的保母已完成今日的照護服務，服務報告稍後會送出！"));
        return Map.of("visitId", visitId.toString(), "visitStatus", visit.getStatus().name(),
                "finishedAt", visit.getCompletedAt().toString());
    }
}
