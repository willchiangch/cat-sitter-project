package com.petsitter.application.service.fulfill;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.common.text.TextRules;
import com.petsitter.application.dto.fulfill.CareLogResponses;
import com.petsitter.application.dto.fulfill.FulfillRequests;
import com.petsitter.application.exception.FulfillErrorCode;
import com.petsitter.application.exception.FulfillException;
import com.petsitter.application.exception.MediaInspectionException;
import com.petsitter.application.exception.StorageUnavailableException;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.application.service.order.Actor;
import com.petsitter.application.service.order.CurrentActor;
import com.petsitter.application.service.order.OrderClock;
import com.petsitter.application.service.order.OrderLogWriter;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.fulfill.ServiceReportMedia;
import com.petsitter.domain.model.fulfill.ServiceReportUpload;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.fulfill.VisitServiceReport;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.repository.fulfill.ServiceReportMediaRepository;
import com.petsitter.domain.repository.fulfill.ServiceReportUploadRepository;
import com.petsitter.domain.repository.fulfill.VisitRef;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import com.petsitter.domain.repository.fulfill.VisitServiceReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 日誌媒體三段式上傳（SD-FULFILL §3.3、§4.4、§4.5）：F16 預檢建槽位 → 瀏覽器以 F17 簽名網址直傳 GCS（交易外）→
 * F18 串流檢驗 + 訂單鎖重驗；F19 刪除媒體。槽位轉移一律條件式 UPDATE；只有本請求把槽位轉成 FAILED 時才提早刪除物件，
 * {@code ORDER_BUSY} / 5xx 保留物件與 PENDING 槽位（同槽位直接重打 F18）。所有 GCS I/O 皆在交易外。
 */
@Slf4j
@Service
public class VisitCareLogMediaService {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            CareLogSupport.IMAGE, Set.of("image/jpeg", "image/png", "image/webp"),
            CareLogSupport.VIDEO, Set.of("video/mp4", "video/quicktime"));
    private static final Map<String, String> EXT = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp",
            "video/mp4", "mp4", "video/quicktime", "mov");
    /** F18 鎖前 / 鎖後的確定性錯誤：條件式轉 FAILED（§3.3 表）。 */
    private static final Set<FulfillErrorCode> FAILS_SLOT = EnumSet.of(FulfillErrorCode.ORDER_STATE_CONFLICT,
            FulfillErrorCode.VISIT_STATE_CONFLICT, FulfillErrorCode.CARE_LOG_STATE_CONFLICT, FulfillErrorCode.DEADLINE_PASSED,
            FulfillErrorCode.CARE_LOG_MEDIA_TYPE_UNSUPPORTED, FulfillErrorCode.CARE_LOG_MEDIA_TOO_LARGE,
            FulfillErrorCode.CARE_LOG_VIDEO_DURATION_INVALID, FulfillErrorCode.CARE_LOG_MEDIA_QUOTA_EXCEEDED);

    private final FulfillGuard guard;
    private final FulfillTx tx;
    private final FulfillRequestValidator validator;
    private final IdempotencyService idempotencyService;
    private final VisitRepository visitRepository;
    private final VisitServiceReportRepository reportRepository;
    private final ServiceReportMediaRepository mediaRepository;
    private final ServiceReportUploadRepository uploadRepository;
    private final OrderQueryService orderQueryService;
    private final OrderLogWriter orderLogWriter;
    private final MediaStorageService storage;
    private final MediaInspector inspector;
    private final MediaUrlSigner urlSigner;
    private final CareLogSupport support;
    private final FulfillSettings settings;
    private final OrderClock clock;
    private final JsonMapper jsonMapper;

    public VisitCareLogMediaService(FulfillGuard guard, FulfillTx tx, FulfillRequestValidator validator,
                                    IdempotencyService idempotencyService, VisitRepository visitRepository,
                                    VisitServiceReportRepository reportRepository, ServiceReportMediaRepository mediaRepository,
                                    ServiceReportUploadRepository uploadRepository, OrderQueryService orderQueryService,
                                    OrderLogWriter orderLogWriter, MediaStorageService storage, MediaInspector inspector,
                                    MediaUrlSigner urlSigner, CareLogSupport support, FulfillSettings settings, OrderClock clock,
                                    JsonMapper jsonMapper) {
        this.guard = guard;
        this.tx = tx;
        this.validator = validator;
        this.idempotencyService = idempotencyService;
        this.visitRepository = visitRepository;
        this.reportRepository = reportRepository;
        this.mediaRepository = mediaRepository;
        this.uploadRepository = uploadRepository;
        this.orderQueryService = orderQueryService;
        this.orderLogWriter = orderLogWriter;
        this.storage = storage;
        this.inspector = inspector;
        this.urlSigner = urlSigner;
        this.support = support;
        this.settings = settings;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
    }

    record Declared(String mediaType, String contentType, int sizeBytes, String sha256, Integer durationSeconds, String caption) {
    }

    // ------------------------------------------------------------------ F16

    public CareLogResponses.UploadSlot requestUpload(UUID orderId, UUID visitId, FulfillRequests.MediaUpload req,
                                                     OffsetDateTime receivedAt) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        Declared d = declared(req);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        IdempotencyClaim claim = idempotencyService.claim(ctx);
        // 預檢（不取鎖）：訂單 / 行程 / 日誌狀態 → 期限（receivedAt）→ 宣告格式 / 大小 / 時長 → 額度
        CareLogSupport.Limits limits = tx.readOnly(() -> {
            OrderStatus orderStatus = orderQueryService.getParticipants(orderId).status();
            Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
            VisitServiceReport r = reportRepository.findByVisitId(visitId).orElse(null);
            support.requireWritable(orderStatus, v, r, receivedAt, false);
            checkFormat(d.mediaType(), d.contentType(), d.sizeBytes(), d.durationSeconds());
            CareLogSupport.Limits l = support.limits(orderId);
            checkQuota(d.mediaType(), l, support.used(r, d.mediaType()));
            return l;
        });
        UUID uploadId = UUID.randomUUID();
        String objectKey = limits.planTier().toLowerCase() + "/" + receivedAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
                + "/" + orderId + "/" + uploadId + "." + EXT.get(d.contentType());
        OffsetDateTime expiresAt = receivedAt.plus(settings.getUploadSlotTtl());
        return tx.write(() -> {
            OffsetDateTime now = clock.now();
            uploadRepository.saveAndFlush(ServiceReportUpload.builder()
                    .id(uploadId).visitId(visitId).orderId(orderId).sitterId(actor.userId())
                    .mediaType(d.mediaType()).contentType(d.contentType()).declaredSize(d.sizeBytes())
                    .declaredSha256(d.sha256())
                    .declaredDurationSeconds(d.durationSeconds() == null ? null : d.durationSeconds().shortValue())
                    .caption(d.caption()).objectKey(objectKey).status(ServiceReportUpload.PENDING)
                    .receivedAt(receivedAt).expiresAt(expiresAt).createdAt(now).updatedAt(now).build());
            CareLogResponses.UploadSlot resp = new CareLogResponses.UploadSlot(uploadId, receivedAt, expiresAt);
            idempotencyService.complete(claim, 201, resp);
            return resp;
        });
    }

    private Declared declared(FulfillRequests.MediaUpload req) {
        JsonNode file = req == null ? null : req.file();
        if (!FulfillRequestValidator.present(file) || !file.isObject()) {
            throw FulfillException.validation("file");
        }
        String mediaType = validator.rawString(file.get("mediaType"), "file.mediaType");
        if (!ALLOWED.containsKey(mediaType)) {
            throw FulfillException.validation("file.mediaType");
        }
        String contentType = validator.rawString(file.get("contentType"), "file.contentType");
        if (contentType.isEmpty() || contentType.length() > 32) {
            throw FulfillException.validation("file.contentType");
        }
        JsonNode size = file.get("sizeBytes");
        if (!FulfillRequestValidator.present(size) || !size.isIntegralNumber() || !size.canConvertToInt() || size.intValue() < 1) {
            throw FulfillException.validation("file.sizeBytes");
        }
        String sha = validator.rawString(file.get("sha256"), "file.sha256");
        if (!sha.matches("^[0-9a-f]{64}$")) {
            throw FulfillException.validation("file.sha256");
        }
        Integer duration = null;
        if (CareLogSupport.VIDEO.equals(mediaType)) {
            JsonNode dur = file.get("durationSeconds");
            if (!FulfillRequestValidator.present(dur) || !dur.isNumber() || dur.decimalValue().signum() < 0
                    || dur.decimalValue().compareTo(BigDecimal.valueOf(Short.MAX_VALUE)) > 0) {
                throw FulfillException.validation("file.durationSeconds");
            }
            duration = dur.decimalValue().setScale(0, RoundingMode.HALF_UP).intValueExact();
        }
        String caption = null;
        JsonNode cap = req.caption();
        if (FulfillRequestValidator.present(cap)) {
            caption = validator.text(cap, "caption", settings.getCaptionMax());
            if (caption.isEmpty()) {
                caption = null;
            }
        }
        return new Declared(mediaType, contentType, size.intValue(), sha, duration, caption);
    }

    /** 宣告值 / 實際值共用：格式 → 大小 → 時長（PRD-022 BR-12 / 13；§4.4 檔案檢驗順序）。 */
    private void checkFormat(String mediaType, String contentType, long sizeBytes, Integer durationSeconds) {
        if (contentType == null || !ALLOWED.get(mediaType).contains(contentType)) {
            throw new FulfillException(FulfillErrorCode.CARE_LOG_MEDIA_TYPE_UNSUPPORTED, Map.of("mediaType", mediaType));
        }
        long max = CareLogSupport.IMAGE.equals(mediaType) ? settings.getPhotoMaxBytesExclusive() : settings.getVideoMaxBytesExclusive();
        if (sizeBytes >= max) {
            throw new FulfillException(FulfillErrorCode.CARE_LOG_MEDIA_TOO_LARGE, Map.of("maxBytes", max));
        }
        if (CareLogSupport.VIDEO.equals(mediaType)) {
            int min = settings.getVideoMinSeconds();
            int maxS = settings.getVideoMaxSeconds();
            if (durationSeconds == null || durationSeconds < min || durationSeconds > maxS) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("minSeconds", min);
                p.put("maxSeconds", maxS);
                p.put("actualSeconds", durationSeconds);
                throw new FulfillException(FulfillErrorCode.CARE_LOG_VIDEO_DURATION_INVALID, p);
            }
        }
    }

    private static void checkQuota(String mediaType, CareLogSupport.Limits limits, long used) {
        int limit = limits.limit(mediaType);
        if (used >= limit) {
            throw new FulfillException(FulfillErrorCode.CARE_LOG_MEDIA_QUOTA_EXCEEDED, Map.of("mediaType", mediaType, "limit", limit, "used", used));
        }
    }

    // ------------------------------------------------------------------ F17

    public CareLogResponses.UploadTarget target(UUID orderId, UUID visitId, UUID uploadId) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        ServiceReportUploadRepository.UploadRef up = uploadRef(visitId, uploadId);
        guard.requireSitter(ref.sitterId(), actor);
        guard.requireSitter(up.getSitterId(), actor);
        ServiceReportUpload slot = tx.readOnly(() -> uploadRepository.findById(uploadId).orElseThrow(FulfillException::notFound));
        switch (slot.getStatus()) {
            case ServiceReportUpload.COMPLETED -> {
                return new CareLogResponses.UploadTarget(uploadId, true, null, null, null, null);
            }
            case ServiceReportUpload.FAILED -> throw stored(slot);
            case ServiceReportUpload.DISCARDED -> throw FulfillException.notFound();
            default -> {
            }
        }
        OffsetDateTime now = clock.now();
        if (ServiceReportUpload.EXPIRED.equals(slot.getStatus()) || !now.isBefore(slot.getExpiresAt())) {
            throw uploadExpired(slot);
        }
        Duration remaining = Duration.between(now, slot.getExpiresAt());
        Duration ttl = remaining.compareTo(settings.getUploadUrlTtl()) < 0 ? remaining : settings.getUploadUrlTtl();
        String url = storage.createSignedUploadUrl(slot.getObjectKey(), slot.getContentType(), slot.getDeclaredSize(), ttl);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", slot.getContentType());
        headers.put("x-goog-content-length-range", slot.getDeclaredSize() + "," + slot.getDeclaredSize());
        headers.put("x-goog-if-generation-match", "0");
        return new CareLogResponses.UploadTarget(uploadId, false, url, "PUT", headers, now.plus(ttl));
    }

    // ------------------------------------------------------------------ F18

    private record Outcome(CareLogResponses.MediaCreated body) {
    }

    public CareLogResponses.MediaCreated completeUpload(UUID orderId, UUID visitId, UUID uploadId, OffsetDateTime receivedAt) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        ServiceReportUploadRepository.UploadRef up = uploadRef(visitId, uploadId);
        guard.requireSitter(ref.sitterId(), actor);
        guard.requireSitter(up.getSitterId(), actor);

        // 鎖前：槽位狀態 → 預檢（訂單 / 行程 / 日誌 → 30 分鐘窗以本請求 receivedAt → 期限以槽位 received_at）
        ServiceReportUpload slot;
        try {
            slot = tx.readOnly(() -> {
                ServiceReportUpload s = uploadRepository.findById(uploadId).orElseThrow(FulfillException::notFound);
                Optional<CareLogResponses.MediaCreated> settled = settledOutcome(orderId, s);
                if (settled.isPresent()) {
                    throw new SettledSignal(settled.get());
                }
                preflight(orderId, visitId, s, receivedAt);
                return s;
            });
        } catch (SettledSignal done) {
            return done.body;
        } catch (FulfillException e) {
            failSlot(uploadId, e, null, null);
            throw e;
        }

        // 交易外：單次串流讀取物件（不持有 DB 連線）
        MediaInspector.Result result;
        long generation;
        try (MediaStorageService.PrivateObject obj = storage.openPrivateObject(slot.getObjectKey())
                .orElseThrow(() -> FulfillException.validation("file", Map.of("reason", "OBJECT_MISSING")))) {
            generation = obj.generation();
            result = inspector.inspect(obj.stream());
        } catch (IOException | MediaInspectionException e) {
            throw new StorageUnavailableException("care log media read failed", e);
        }
        if (storage.isPubliclyReadable(slot.getObjectKey())) {
            storage.revokePublicRead(slot.getObjectKey());
            log.warn("care log media object was publicly readable, revoked: uploadId={}", uploadId);
        }
        try {
            if (!result.sha256().equals(slot.getDeclaredSha256())) {
                throw FulfillException.validation("file", Map.of("reason", "SHA256_MISMATCH"));
            }
            checkActual(slot, result);
            checkQuota(slot.getMediaType(), support.limits(orderId),
                    mediaRepository.countByVisitIdAndMediaType(visitId, slot.getMediaType()));
        } catch (FulfillException e) {
            failSlot(uploadId, e, slot.getObjectKey(), generation);
            throw e;
        }

        // 交易 B：訂單鎖 → 鎖後載入槽位 → 重驗 → 建立媒體（首次上傳同交易建 DRAFT）→ 槽位 COMPLETED → MEDIA_UPLOADED
        try {
            Outcome outcome = tx.inLockedTx(orderId, locked -> {
                OffsetDateTime now = clock.now();
                ServiceReportUpload s = uploadRepository.findById(uploadId).orElseThrow(FulfillException::notFound);
                Optional<CareLogResponses.MediaCreated> settled = settledOutcome(orderId, s);
                if (settled.isPresent()) {
                    return new Outcome(settled.get());
                }
                Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
                VisitServiceReport r = reportRepository.findByVisitId(visitId).orElse(null);
                support.requireWritable(locked.status(), v, r, s.getReceivedAt(), false);
                CareLogSupport.Limits limits = support.limits(orderId);
                checkQuota(s.getMediaType(), limits, support.used(r, s.getMediaType()));
                boolean created = r == null;
                if (created) {
                    r = VisitServiceReport.builder().visitId(visitId).status(VisitServiceReport.DRAFT).createdBy(actor.userId()).build();
                }
                r.setUpdatedBy(actor.userId());
                VisitServiceReport report = reportRepository.saveAndFlush(r);
                ServiceReportMedia media = mediaRepository.saveAndFlush(ServiceReportMedia.builder()
                        .reportId(report.getId()).uploadId(uploadId).objectKey(s.getObjectKey()).objectGeneration(generation)
                        .mediaType(s.getMediaType()).sizeBytes((int) result.sizeBytes())
                        .durationSeconds(result.durationSeconds() == null ? null : result.durationSeconds().shortValue())
                        .caption(s.getCaption()).uploadedBy(actor.userId()).uploadedAt(now).build());
                if (uploadRepository.markCompleted(uploadId, now) != 1) {
                    // 槽位在鎖外被 FC2 轉 EXPIRED（寬限後）；回滾，依重讀結果回應
                    throw uploadExpired(s);
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("visitId", visitId.toString());
                payload.put("careLogId", report.getId().toString());
                payload.put("mediaId", media.getId().toString());
                payload.put("uploadId", uploadId.toString());
                payload.put("objectKey", s.getObjectKey());
                payload.put("mediaType", s.getMediaType());
                payload.put("sizeBytes", media.getSizeBytes());
                payload.put("durationSeconds", result.durationSeconds());
                payload.put("careLogCreated", created);
                orderLogWriter.write(locked, "MEDIA_UPLOADED", Actor.sitter(actor.userId()), payload);
                return new Outcome(created(media, report.getId(), support.quota(limits, report)));
            });
            return outcome.body();
        } catch (FulfillException e) {
            failSlot(uploadId, e, slot.getObjectKey(), generation);
            throw e;
        }
    }

    private void preflight(UUID orderId, UUID visitId, ServiceReportUpload s, OffsetDateTime receivedAt) {
        OrderStatus orderStatus = orderQueryService.getParticipants(orderId).status();
        if (!VisitRules.RUNNING_ORDER.contains(orderStatus)) {
            throw FulfillException.orderState(orderStatus);
        }
        Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
        VisitServiceReport r = reportRepository.findByVisitId(visitId).orElse(null);
        if (v.getStatus() != com.petsitter.domain.model.order.VisitStatus.IN_PROGRESS
                && v.getStatus() != com.petsitter.domain.model.order.VisitStatus.DONE) {
            throw FulfillException.visitState(v.getStatus(), v.getCompletionSource());
        }
        CareLogSupport.requireNotSubmitted(r);
        if (ServiceReportUpload.EXPIRED.equals(s.getStatus()) || !receivedAt.isBefore(s.getExpiresAt())) {
            throw uploadExpired(s);
        }
        support.requireWritable(orderStatus, v, r, s.getReceivedAt(), false);
    }

    /** 實際物件：magic bytes 屬允許格式、與 mediaType 及宣告 contentType 相符 → 實際大小 → 影片時長。 */
    private void checkActual(ServiceReportUpload s, MediaInspector.Result r) {
        String actual = r.contentType();
        boolean sameFamily = actual != null && (actual.equals(s.getContentType())
                // MP4 / MOV 同為 ISO BMFF 容器，ftyp brand 與瀏覽器宣告常不一致，視為同一格式族
                || (actual.startsWith("video/") && s.getContentType().startsWith("video/")));
        if (!sameFamily || !ALLOWED.get(s.getMediaType()).contains(actual)) {
            throw new FulfillException(FulfillErrorCode.CARE_LOG_MEDIA_TYPE_UNSUPPORTED, Map.of("mediaType", s.getMediaType()));
        }
        checkFormat(s.getMediaType(), s.getContentType(), r.sizeBytes(), r.durationSeconds());
    }

    /** 槽位已 COMPLETED → 既有媒體；FAILED → 存下的錯誤；DISCARDED → 404；其他回空。 */
    private Optional<CareLogResponses.MediaCreated> settledOutcome(UUID orderId, ServiceReportUpload s) {
        switch (s.getStatus()) {
            case ServiceReportUpload.COMPLETED -> {
                ServiceReportMedia m = mediaRepository.findByUploadId(s.getId()).orElseThrow(FulfillException::notFound);
                VisitServiceReport r = reportRepository.findById(m.getReportId()).orElseThrow(FulfillException::notFound);
                return Optional.of(created(m, r.getId(), support.quota(support.limits(orderId), r)));
            }
            case ServiceReportUpload.FAILED -> throw stored(s);
            case ServiceReportUpload.DISCARDED -> throw FulfillException.notFound();
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * 確定性錯誤：條件式 UPDATE 槽位 PENDING → FAILED（存狀態碼 / code / payload）；只有本請求轉成 FAILED（影響 1 列）時才
     * best-effort 提早刪除物件（不寫 object_deleted_at，最終清除由 FC2）。{@code ORDER_BUSY} / 5xx / 逾時 / 物件不存在不走這裡。
     */
    private void failSlot(UUID uploadId, FulfillException e, String objectKey, Long generation) {
        boolean deterministic = FAILS_SLOT.contains(e.getCode())
                || (e.getCode() == FulfillErrorCode.VALIDATION_FAILED && "SHA256_MISMATCH".equals(e.getPayload().get("reason")));
        if (!deterministic) {
            return;
        }
        String payload = jsonMapper.writeValueAsString(e.getPayload());
        Integer affected = tx.write(() -> uploadRepository.markFailed(uploadId, (short) e.getCode().status().value(),
                e.getCode().name(), payload, clock.now()));
        if (affected != null && affected == 1 && objectKey != null) {
            deleteQuietly(objectKey, generation);
        } else if (affected != null && affected == 1) {
            String key = tx.readOnly(() -> uploadRepository.findById(uploadId).map(ServiceReportUpload::getObjectKey).orElse(null));
            if (key != null) {
                deleteQuietly(key, null);
            }
        }
    }

    private void deleteQuietly(String objectKey, Long generation) {
        try {
            storage.deletePrivateObject(objectKey, generation);
        } catch (RuntimeException ex) {
            log.warn("early delete failed, FC2 will retry: {}", objectKey);
        }
    }

    @SuppressWarnings("unchecked")
    private FulfillException stored(ServiceReportUpload s) {
        FulfillErrorCode code;
        try {
            code = FulfillErrorCode.valueOf(s.getFailureCode());
        } catch (IllegalArgumentException | NullPointerException e) {
            code = FulfillErrorCode.VALIDATION_FAILED;
        }
        return new FulfillException(code, s.getFailurePayload() == null ? Map.of() : s.getFailurePayload());
    }

    private static FulfillException uploadExpired(ServiceReportUpload s) {
        return new FulfillException(FulfillErrorCode.CARE_LOG_UPLOAD_EXPIRED, Map.of("expiresAt", FulfillException.ts(s.getExpiresAt())));
    }

    private ServiceReportUploadRepository.UploadRef uploadRef(UUID visitId, UUID uploadId) {
        return uploadRepository.findRef(uploadId).filter(u -> u.getVisitId().equals(visitId)).orElseThrow(FulfillException::notFound);
    }

    private static CareLogResponses.MediaCreated created(ServiceReportMedia m, UUID careLogId, CareLogResponses.Quota quota) {
        return new CareLogResponses.MediaCreated(m.getId(), m.getMediaType(), m.getCaption(), m.getSizeBytes(),
                m.getDurationSeconds() == null ? null : m.getDurationSeconds().intValue(), m.getUploadedAt(), careLogId, quota);
    }

    /** 鎖前發現槽位已完成：跳出唯讀交易直接回既有結果。 */
    private static final class SettledSignal extends RuntimeException {
        final CareLogResponses.MediaCreated body;

        SettledSignal(CareLogResponses.MediaCreated body) {
            super(null, null, false, false);
            this.body = body;
        }
    }

    // ------------------------------------------------------------------ F19

    private record Deleted(CareLogResponses.MediaDeleted body, String objectKey, long generation) {
    }

    public CareLogResponses.MediaDeleted deleteMedia(UUID orderId, UUID visitId, UUID mediaId, OffsetDateTime receivedAt) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        mediaRepository.findVisitIdById(mediaId).filter(visitId::equals).orElseThrow(FulfillException::notFound);
        guard.requireSitter(ref.sitterId(), actor);
        Deleted d = tx.inLockedTx(orderId, locked -> {
            ServiceReportMedia m = mediaRepository.findById(mediaId).orElseThrow(FulfillException::notFound);
            Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
            VisitServiceReport r = reportRepository.findByVisitId(visitId).orElseThrow(FulfillException::notFound);
            support.requireWritable(locked.status(), v, r, receivedAt, false);
            OffsetDateTime now = clock.now();
            mediaRepository.delete(m);
            mediaRepository.flush();
            uploadRepository.markDiscarded(m.getUploadId(), now);
            r.setUpdatedBy(actor.userId());
            reportRepository.saveAndFlush(r);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visitId", visitId.toString());
            payload.put("careLogId", r.getId().toString());
            payload.put("mediaId", mediaId.toString());
            payload.put("objectKey", m.getObjectKey());
            payload.put("mediaType", m.getMediaType());
            orderLogWriter.write(locked, "MEDIA_DELETED", Actor.sitter(actor.userId()), payload);
            return new Deleted(new CareLogResponses.MediaDeleted(mediaId, support.quota(support.limits(orderId), r)),
                    m.getObjectKey(), m.getObjectGeneration());
        });
        // commit 後同一請求內 best-effort 提早刪除（帶 generation 前置條件；不寫 object_deleted_at，FC2 最終清除）
        urlSigner.invalidate(d.objectKey());
        deleteQuietly(d.objectKey(), d.generation());
        return d.body();
    }
}
