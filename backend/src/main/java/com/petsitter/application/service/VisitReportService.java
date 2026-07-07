package com.petsitter.application.service;

import com.petsitter.application.dto.ReportMediaDto;
import com.petsitter.application.dto.VisitServiceReportDto;
import com.petsitter.application.exception.VisitReportException;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderSnapshot;
import com.petsitter.domain.model.ServiceReportMedia;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.model.VisitServiceReport;
import com.petsitter.application.event.VisitNotificationEvent;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.OrderSnapshotRepository;
import com.petsitter.domain.repository.ServiceReportMediaRepository;
import com.petsitter.domain.repository.VisitRepository;
import com.petsitter.domain.repository.VisitServiceReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitReportService {

    private final VisitServiceReportRepository reportRepository;
    private final ServiceReportMediaRepository mediaRepository;
    private final VisitRepository visitRepository;
    private final OrderRepository orderRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final IdempotencyService idempotencyService;
    private final MediaStorageService mediaStorageService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    // 懶加載逾期判定
    public boolean isExpired(VisitServiceReport report, Visit visit) {
        if (report != null && "SUBMITTED".equals(report.getStatus())) {
            return false;
        }
        return visit.getFinishedAt() != null &&
                Instant.now().isAfter(visit.getFinishedAt().toInstant().plus(24, ChronoUnit.HOURS));
    }

    // 取得或建立草稿
    private VisitServiceReport getOrCreateDraftReport(UUID visitId, UUID sitterId) {
        Optional<VisitServiceReport> optReport = reportRepository.findByVisitId(visitId);
        if (optReport.isPresent()) {
            return optReport.get();
        }

        VisitServiceReport report = VisitServiceReport.builder()
                .visitId(visitId)
                .status("DRAFT")
                .content("")
                .version(0)
                .isDeleted(false)
                .createdBy(sitterId)
                .updatedBy(sitterId)
                .build();

        return reportRepository.save(report);
    }

    @Transactional
    public VisitServiceReportDto saveDraft(UUID visitId, String content, Integer version, UUID sitterId, String idempotencyKey) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));

        if (!visit.getOrder().getSitter().getId().equals(sitterId)) {
            throw new org.springframework.security.access.AccessDeniedException("權限不足");
        }

        String visitStatus = visit.getStatus();
        if (!"IN_PROGRESS".equals(visitStatus) && !"DONE".equals(visitStatus)) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_VISIT_STATUS", "行程尚未開始，無法編輯日誌");
        }

        Optional<VisitServiceReport> optReport = reportRepository.findByVisitId(visitId);
        if (optReport.isPresent()) {
            VisitServiceReport report = optReport.get();
            if (isExpired(report, visit)) {
                throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_REPORT_EXPIRED", "已逾期 24 小時，無法編輯");
            }
            if ("SUBMITTED".equals(report.getStatus())) {
                throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_STATE_CONFLICT", "日誌已送出，無法修改");
            }
        } else {
            if (isExpired(null, visit)) {
                throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_REPORT_EXPIRED", "已逾期 24 小時，無法編輯");
            }
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.checkAndConsume(idempotencyKey, sitterId);
            } catch (DataIntegrityViolationException e) {
                throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_IDEMPOTENCY_CONFLICT", "系統已受理此請求，請勿重複提交");
            }
        }

        VisitServiceReport report = getOrCreateDraftReport(visitId, sitterId);
        if (!report.getVersion().equals(version)) {
            throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_VERSION_CONFLICT", "內容已被更新，請重新整理後再試");
        }

        report.setContent(content);
        report.setUpdatedBy(sitterId);
        report = reportRepository.save(report);

        auditLogService.writeOrderLog(
                visit.getOrder(),
                sitterId.toString(),
                "UPDATE_SERVICE_REPORT_DRAFT",
                Map.of("reportId", report.getId(), "visitId", visitId)
        );

        return convertToDto(report, visit);
    }

    @Transactional
    public ReportMediaDto uploadMedia(UUID visitId, MultipartFile file, String caption, String mediaType, UUID sitterId, String idempotencyKey) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));

        if (!visit.getOrder().getSitter().getId().equals(sitterId)) {
            throw new org.springframework.security.access.AccessDeniedException("權限不足");
        }

        String visitStatus = visit.getStatus();
        if (!"IN_PROGRESS".equals(visitStatus) && !"DONE".equals(visitStatus)) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_VISIT_STATUS", "行程尚未開始，無法上傳媒體");
        }

        Optional<VisitServiceReport> optReport = reportRepository.findByVisitId(visitId);
        VisitServiceReport report;
        if (optReport.isPresent()) {
            report = optReport.get();
            if (isExpired(report, visit)) {
                throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_REPORT_EXPIRED", "已逾期 24 小時，無法編輯");
            }
            if ("SUBMITTED".equals(report.getStatus())) {
                throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_STATE_CONFLICT", "日誌已送出，無法修改");
            }
        } else {
            if (isExpired(null, visit)) {
                throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_REPORT_EXPIRED", "已逾期 24 小時，無法編輯");
            }
            report = getOrCreateDraftReport(visitId, sitterId);
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.checkAndConsume(idempotencyKey, sitterId);
            } catch (DataIntegrityViolationException e) {
                throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_IDEMPOTENCY_CONFLICT", "系統已受理此請求，請勿重複提交");
            }
        }

        OrderSnapshot snapshot = orderSnapshotRepository.findByOrderId(visit.getOrder().getId())
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到方案快照"));

        int currentPhotos = mediaRepository.countByReportIdAndMediaTypeAndIsDeletedFalse(report.getId(), "IMAGE");
        int currentVideos = mediaRepository.countByReportIdAndMediaTypeAndIsDeletedFalse(report.getId(), "VIDEO");

        if ("IMAGE".equalsIgnoreCase(mediaType)) {
            if (currentPhotos >= snapshot.getMaxPhotos()) {
                throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_PLAN_LIMIT", "照片上傳數量已達方案上限");
            }
        } else if ("VIDEO".equalsIgnoreCase(mediaType)) {
            if (currentVideos >= snapshot.getMaxVideos()) {
                throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_PLAN_LIMIT", "影片上傳數量已達方案上限");
            }
        } else {
            throw new VisitReportException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "不支援的媒體類型");
        }

        // 多媒體格式與大小防禦
        if ("IMAGE".equalsIgnoreCase(mediaType)) {
            String contentType = file.getContentType();
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase() : "";

            boolean validFormat = "image/jpeg".equals(contentType) || "image/png".equals(contentType) || "image/webp".equals(contentType) ||
                    Arrays.asList("jpg", "jpeg", "png", "webp").contains(extension);

            if (!validFormat || file.getSize() > 1024 * 1024) { // 1MB
                throw new VisitReportException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "圖片僅支援 JPG/PNG/WebP 且大小不可超過 1MB");
            }
        } else {
            String contentType = file.getContentType();
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase() : "";

            boolean validFormat = "video/mp4".equals(contentType) || "video/quicktime".equals(contentType) ||
                    Arrays.asList("mp4", "mov").contains(extension);

            if (!validFormat || file.getSize() > 50 * 1024 * 1024) { // 50MB
                throw new VisitReportException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "影片僅支援 MP4/MOV 且大小不可超過 50MB");
            }
        }

        UUID fileUuid = UUID.randomUUID();
        String dateStr = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String mediaUrl;
        try {
            mediaUrl = mediaStorageService.uploadReportMedia(
                    snapshot.getPlanTier(),
                    dateStr,
                    visit.getOrder().getId(),
                    fileUuid,
                    file
            );
        } catch (Exception e) {
            log.error("Storage service unavailable during report media upload", e);
            auditLogService.writeOrderLog(
                    visit.getOrder(),
                    sitterId.toString(),
                    "UPLOAD_REPORT_MEDIA_FAIL",
                    Map.of("visitId", visitId, "error", e.getMessage())
            );
            throw new VisitReportException(HttpStatus.SERVICE_UNAVAILABLE, "MSG_DATA_STORAGE_ERROR", "儲存服務暫時無法使用，請稍候重試");
        }

        ServiceReportMedia reportMedia = ServiceReportMedia.builder()
                .reportId(report.getId())
                .mediaUrl(mediaUrl)
                .mediaType(mediaType.toUpperCase())
                .caption(caption)
                .version(0)
                .isDeleted(false)
                .createdBy(sitterId)
                .updatedBy(sitterId)
                .build();

        try {
            reportMedia = mediaRepository.save(reportMedia);

            auditLogService.writeOrderLog(
                    visit.getOrder(),
                    sitterId.toString(),
                    "UPLOAD_REPORT_MEDIA",
                    Map.of("reportId", report.getId(), "mediaId", reportMedia.getId())
            );
        } catch (Exception e) {
            log.error("Failed to save report media to database, rolling back storage file: {}", mediaUrl);
            try {
                mediaStorageService.deleteMedia(mediaUrl);
            } catch (Exception ex) {
                log.error("CRITICAL: Failed to rollback storage file after database save failure", ex);
            }
            auditLogService.writeOrderLog(
                    visit.getOrder(),
                    sitterId.toString(),
                    "UPLOAD_REPORT_MEDIA_FAIL",
                    Map.of("visitId", visitId, "error", "Database save failed: " + e.getMessage())
            );
            throw new VisitReportException(HttpStatus.SERVICE_UNAVAILABLE, "MSG_DATA_STORAGE_ERROR", "日誌媒體保存失敗，儲存已回滾");
        }

        return ReportMediaDto.builder()
                .mediaId(reportMedia.getId())
                .mediaUrl(reportMedia.getMediaUrl())
                .mediaType(reportMedia.getMediaType())
                .caption(reportMedia.getCaption())
                .version(reportMedia.getVersion())
                .build();
    }

    @Transactional
    public void deleteMedia(UUID mediaId, Integer version, UUID sitterId, String idempotencyKey) {
        ServiceReportMedia media = mediaRepository.findByIdAndIsDeletedFalse(mediaId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到媒體檔案"));

        VisitServiceReport report = reportRepository.findById(media.getReportId())
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到服務日誌"));

        Visit visit = visitRepository.findById(report.getVisitId())
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));

        if (!visit.getOrder().getSitter().getId().equals(sitterId)) {
            throw new org.springframework.security.access.AccessDeniedException("權限不足");
        }

        if ("SUBMITTED".equals(report.getStatus()) || isExpired(report, visit)) {
            throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_STATE_CONFLICT", "日誌已送出或已逾期，不可刪除媒體");
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.checkAndConsume(idempotencyKey, sitterId);
            } catch (DataIntegrityViolationException e) {
                throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_IDEMPOTENCY_CONFLICT", "系統已受理此請求，請勿重複提交");
            }
        }

        if (!media.getVersion().equals(version)) {
            throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_VERSION_CONFLICT", "媒體已被修改，請重新整理後再試");
        }

        media.setDeleted(true);
        media.setUpdatedBy(sitterId);
        mediaRepository.save(media);

        auditLogService.writeOrderLog(
                visit.getOrder(),
                sitterId.toString(),
                "DELETE_REPORT_MEDIA",
                Map.of("reportId", report.getId(), "mediaId", mediaId)
        );
    }

    @Transactional
    public void submitReport(UUID visitId, UUID sitterId, String idempotencyKey) {
        VisitServiceReport report = reportRepository.findByVisitId(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到服務日誌"));

        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));

        if (!visit.getOrder().getSitter().getId().equals(sitterId)) {
            throw new org.springframework.security.access.AccessDeniedException("權限不足");
        }

        if (!"DONE".equals(visit.getStatus())) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_VISIT_NOT_FINISHED", "行程尚未結束，不可送出日誌");
        }

        if (isExpired(report, visit)) {
            throw new VisitReportException(HttpStatus.FORBIDDEN, "MSG_DATA_REPORT_EXPIRED", "已逾期 24 小時，無法送出");
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.checkAndConsume(idempotencyKey, sitterId);
            } catch (DataIntegrityViolationException e) {
                throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_IDEMPOTENCY_CONFLICT", "系統已受理此請求，請勿重複提交");
            }
        }

        if (!"DRAFT".equals(report.getStatus())) {
            throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_STATE_CONFLICT", "日誌非草稿狀態，無法送出");
        }

        report.setStatus("SUBMITTED");
        report.setSubmittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        report.setUpdatedBy(sitterId);

        reportRepository.save(report);

        auditLogService.writeOrderLog(
                visit.getOrder(),
                sitterId.toString(),
                "SUBMIT_SERVICE_REPORT",
                Map.of("reportId", report.getId(), "visitId", visitId)
        );

        try {
            String scheduledDate = visit.getScheduledAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String msg = String.format("%s 的行程日誌已由保母回報，快來看看毛孩的近況吧！", scheduledDate);
            notificationService.sendNotification(visit.getOrder().getOwner().getId(), msg);
        } catch (Exception e) {
            log.error("Failed to send submission notification async", e);
        }
    }

    @Transactional(readOnly = true)
    public VisitServiceReportDto getReport(UUID visitId, UUID userId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));

        UUID sitterId = visit.getOrder().getSitter().getId();
        UUID ownerId = visit.getOrder().getOwner().getId();

        if (!sitterId.equals(userId) && !ownerId.equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("權限不足");
        }

        Optional<VisitServiceReport> optReport = reportRepository.findByVisitId(visitId);

        if (ownerId.equals(userId)) {
            if (optReport.isEmpty()) {
                throw new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "查無服務日誌");
            }
            VisitServiceReport report = optReport.get();
            if (!"SUBMITTED".equals(report.getStatus())) {
                throw new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "查無服務日誌");
            }
            return convertToDto(report, visit);
        } else {
            if (optReport.isEmpty()) {
                throw new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "日誌尚未建立");
            }
            return convertToDto(optReport.get(), visit);
        }
    }

    private VisitServiceReportDto convertToDto(VisitServiceReport report, Visit visit) {
        List<ServiceReportMedia> mediaList = mediaRepository.findByReportIdAndIsDeletedFalse(report.getId());
        List<ReportMediaDto> mediaDtos = mediaList.stream()
                .map(m -> ReportMediaDto.builder()
                        .mediaId(m.getId())
                        .mediaUrl(m.getMediaUrl())
                        .mediaType(m.getMediaType())
                        .caption(m.getCaption())
                        .version(m.getVersion())
                        .isPurged(m.isPurged())
                        .build())
                .collect(Collectors.toList());

        boolean isEditable = "DRAFT".equals(report.getStatus()) && !isExpired(report, visit);
        boolean isPurged = mediaList.stream().anyMatch(ServiceReportMedia::isPurged);

        Order order = visit.getOrder();
        java.util.Optional<OrderSnapshot> optSnapshot = orderSnapshotRepository.findByOrderId(order.getId());
        Integer mediaRetentionDays = null;
        OffsetDateTime completedAt = order.getCompletedAt();
        OffsetDateTime expiryTime = null;

        if (optSnapshot.isPresent()) {
            OrderSnapshot snapshot = optSnapshot.get();
            mediaRetentionDays = snapshot.getMediaRetentionDays();
            if (completedAt != null && mediaRetentionDays != -1) {
                expiryTime = completedAt.plusDays(mediaRetentionDays);
            }
        }

        return VisitServiceReportDto.builder()
                .reportId(report.getId())
                .visitId(report.getVisitId())
                .status(report.getStatus())
                .content(report.getContent())
                .submittedAt(report.getSubmittedAt())
                .media(mediaDtos)
                .isEditable(isEditable)
                .version(report.getVersion())
                .visitStatus(visit.getStatus())
                .mediaRetentionDays(mediaRetentionDays)
                .completedAt(completedAt)
                .expiryTime(expiryTime)
                .isPurged(isPurged)
                .build();

    }
    @Transactional
    public Map<String, String> startVisit(UUID visitId, UUID sitterId, String idempotencyKey) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));

        if (!visit.getOrder().getSitter().getId().equals(sitterId)) {
            throw new org.springframework.security.access.AccessDeniedException("權限不足");
        }

        if (!"PENDING".equals(visit.getStatus())) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "行程非待執行狀態，無法開始");
        }

        Order order = visit.getOrder();
        if (!"CONFIRMED".equals(order.getStatus()) && !"IN_PROGRESS".equals(order.getStatus())) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "訂單狀態不符合執行要求");
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.checkAndConsume(idempotencyKey, sitterId);
            } catch (DataIntegrityViolationException e) {
                throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_IDEMPOTENCY_CONFLICT", "系統已受理此請求，請勿重複提交");
            }
        }

        visit.setStatus("IN_PROGRESS");
        visitRepository.save(visit);

        if ("CONFIRMED".equals(order.getStatus())) {
            order.setStatus("IN_PROGRESS");
        }
        orderRepository.save(order);

        auditLogService.writeOrderLog(
                order,
                sitterId.toString(),
                "START_VISIT",
                Map.of("visitId", visitId)
        );

        eventPublisher.publishEvent(new VisitNotificationEvent(order.getOwner().getId(), "您的保母已開始今日的照護服務！"));

        return Map.of(
                "visitId", visitId.toString(),
                "visitStatus", visit.getStatus(),
                "orderStatus", order.getStatus()
        );
    }

    @Transactional
    public Map<String, String> endVisit(UUID visitId, UUID sitterId, String idempotencyKey) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new VisitReportException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到行程"));

        if (!visit.getOrder().getSitter().getId().equals(sitterId)) {
            throw new org.springframework.security.access.AccessDeniedException("權限不足");
        }

        if (!"IN_PROGRESS".equals(visit.getStatus())) {
            throw new VisitReportException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "行程非執行中狀態，無法結束");
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.checkAndConsume(idempotencyKey, sitterId);
            } catch (DataIntegrityViolationException e) {
                throw new VisitReportException(HttpStatus.CONFLICT, "MSG_DATA_IDEMPOTENCY_CONFLICT", "系統已受理此請求，請勿重複提交");
            }
        }

        visit.setStatus("DONE");
        visit.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        visitRepository.save(visit);

        auditLogService.writeOrderLog(
                visit.getOrder(),
                sitterId.toString(),
                "END_VISIT",
                Map.of("visitId", visitId)
        );

        eventPublisher.publishEvent(new VisitNotificationEvent(visit.getOrder().getOwner().getId(), "您的保母已完成今日的照護服務，服務報告稍後會送出！"));

        return Map.of(
                "visitId", visitId.toString(),
                "visitStatus", visit.getStatus(),
                "finishedAt", visit.getFinishedAt().toString()
        );
    }}
