package com.petsitter.application.service.fulfill;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.common.text.TextRules;
import com.petsitter.application.dto.fulfill.CareLogResponses;
import com.petsitter.application.dto.fulfill.FulfillRequests;
import com.petsitter.application.exception.FulfillErrorCode;
import com.petsitter.application.exception.FulfillException;
import com.petsitter.application.service.order.Actor;
import com.petsitter.application.service.order.CurrentActor;
import com.petsitter.application.service.order.OrderClock;
import com.petsitter.application.service.order.OrderLogWriter;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.fulfill.ServiceReportMedia;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.fulfill.VisitServiceReport;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.ServiceReportMediaRepository;
import com.petsitter.domain.repository.fulfill.VisitRef;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import com.petsitter.domain.repository.fulfill.VisitServiceReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 照護日誌 F13 讀取、F14 暫存、F15 送出（SD-FULFILL §3.2、§4.5；PRD-022）。暫存不寫 log、不通知；送出同交易寫
 * {@code CARE_LOG_SUBMITTED} 並 enqueue 飼主通知。建立者 / 更新者由 Service 寫入 token 保母（BR-24）。
 */
@Service
@RequiredArgsConstructor
public class VisitCareLogService {

    private final FulfillGuard guard;
    private final FulfillTx tx;
    private final FulfillRequestValidator validator;
    private final IdempotencyService idempotencyService;
    private final VisitRepository visitRepository;
    private final VisitServiceReportRepository reportRepository;
    private final ServiceReportMediaRepository mediaRepository;
    private final OrderQueryService orderQueryService;
    private final OrderLogWriter orderLogWriter;
    private final FulfillNotifier notifier;
    private final VisitNameResolver nameResolver;
    private final CareLogSupport support;
    private final MediaUrlSigner urlSigner;
    private final VisitRules rules;
    private final FulfillSettings settings;
    private final OrderClock clock;

    // ------------------------------------------------------------------ F13

    private record ReadData(Visit visit, VisitServiceReport report, List<ServiceReportMedia> media, OrderStatus orderStatus,
                            CareLogSupport.Limits limits, Map<UUID, String> titles) {
    }

    public CareLogResponses.CareLogView read(UUID orderId, UUID visitId) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        if (!actor.isSitterRole()) {
            // OWNER / ADMIN 讀取時日誌不存在或非 SUBMITTED → 404（G4，依 token 角色判斷；§6.3 F13 已知邊界）
            String status = reportRepository.findStatusByVisitId(visitId).orElse(null);
            if (!VisitServiceReport.SUBMITTED.equals(status)) {
                throw FulfillException.notFound();
            }
        }
        ActorRole role = guard.readerRole(orderId, ref.ownerId(), ref.sitterId(), actor);
        OffsetDateTime now = clock.now();
        ReadData data = tx.readOnly(() -> {
            Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
            VisitServiceReport r = reportRepository.findByVisitId(visitId).orElse(null);
            List<ServiceReportMedia> media = r == null ? List.of() : mediaRepository.findByReportIdOrderByUploadedAtAscIdAsc(r.getId());
            OrderQueryService.OrderContextView ctx = orderQueryService.getOrderContexts(Set.of(orderId)).get(orderId);
            return new ReadData(v, r, media, ctx.status(), support.limits(orderId), ctx.planTitles());
        });
        if (role != ActorRole.SITTER && (data.report == null || !VisitServiceReport.SUBMITTED.equals(data.report.getStatus()))) {
            throw FulfillException.notFound();
        }
        // 簽名在讀取交易結束後執行（§2.5 第 4 點）
        List<CareLogResponses.MediaItem> items = data.media.stream().map(this::mediaItem).toList();
        Visit v = data.visit;
        String name = nameResolver.name(v, data.titles);
        VisitServiceReport r = data.report;
        if (role != ActorRole.SITTER) {
            return new CareLogResponses.CareLogView(visitId, name, v.getServiceDate(), r.getId(), null, r.getContent(),
                    r.getSubmittedAt(), null, null, null, null, null, items);
        }
        boolean expired = rules.careLogExpired(v, now);
        boolean open = r == null || !VisitServiceReport.SUBMITTED.equals(r.getStatus());
        boolean running = VisitRules.RUNNING_ORDER.contains(data.orderStatus);
        boolean editable = running && open && !expired && (v.getStatus() == VisitStatus.IN_PROGRESS || v.getStatus() == VisitStatus.DONE);
        boolean submittable = running && open && !expired && v.getStatus() == VisitStatus.DONE;
        return new CareLogResponses.CareLogView(visitId, name, v.getServiceDate(), r == null ? null : r.getId(),
                r == null ? null : r.getStatus(), r == null ? null : r.getContent(), r == null ? null : r.getSubmittedAt(),
                v.getStatus() == VisitStatus.DONE ? rules.careLogDeadline(v) : null, expired, editable, submittable,
                support.quota(data.limits, data.media), items);
    }

    private CareLogResponses.MediaItem mediaItem(ServiceReportMedia m) {
        Integer duration = m.getDurationSeconds() == null ? null : m.getDurationSeconds().intValue();
        if (m.isPurged()) {
            return new CareLogResponses.MediaItem(m.getId(), m.getMediaType(), m.getCaption(), m.getSizeBytes(), duration,
                    m.getUploadedAt(), null, null, true);
        }
        MediaUrlSigner.SignedUrl url = urlSigner.sign(m.getObjectKey());
        return new CareLogResponses.MediaItem(m.getId(), m.getMediaType(), m.getCaption(), m.getSizeBytes(), duration,
                m.getUploadedAt(), url.url(), url.expiresAt(), false);
    }

    // ------------------------------------------------------------------ F14

    public CareLogResponses.Draft saveDraft(UUID orderId, UUID visitId, FulfillRequests.CareLogText req, OffsetDateTime receivedAt) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        String text = validator.text(req == null ? null : req.text(), "text", settings.getCareLogTextMax());
        return tx.inLockedTx(orderId, locked -> {
            Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
            VisitServiceReport r = reportRepository.findByVisitId(visitId).orElse(null);
            support.requireWritable(locked.status(), v, r, receivedAt, false);
            if (r == null) {
                r = VisitServiceReport.builder().visitId(visitId).status(VisitServiceReport.DRAFT).createdBy(actor.userId()).build();
            }
            r.setContent(text);
            r.setUpdatedBy(actor.userId());
            VisitServiceReport saved = reportRepository.saveAndFlush(r);
            return new CareLogResponses.Draft(saved.getId(), VisitServiceReport.DRAFT, text, saved.getUpdatedAt());
        });
    }

    // ------------------------------------------------------------------ F15

    public CareLogResponses.Submitted submit(UUID orderId, UUID visitId, FulfillRequests.CareLogText req, OffsetDateTime receivedAt) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        String text = validator.text(req == null ? null : req.text(), "text", settings.getCareLogTextMax());
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        IdempotencyClaim claim = idempotencyService.claim(ctx);
        return tx.inLockedTx(orderId, locked -> {
            OffsetDateTime now = clock.now();
            Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
            VisitServiceReport r = reportRepository.findByVisitId(visitId).orElse(null);
            support.requireWritable(locked.status(), v, r, receivedAt, true);
            if (TextRules.isBlank(text)) {
                throw new FulfillException(FulfillErrorCode.CARE_LOG_CONTENT_EMPTY);
            }
            if (r == null) {
                r = VisitServiceReport.builder().visitId(visitId).createdBy(actor.userId()).build();
            }
            r.setContent(text);
            r.setStatus(VisitServiceReport.SUBMITTED);
            r.setSubmittedAt(now);
            r.setUpdatedBy(actor.userId());
            VisitServiceReport saved = reportRepository.saveAndFlush(r);
            List<ServiceReportMedia> media = mediaRepository.findByReportIdOrderByUploadedAtAscIdAsc(saved.getId());
            long images = media.stream().filter(m -> CareLogSupport.IMAGE.equals(m.getMediaType())).count();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visitId", visitId.toString());
            payload.put("careLogId", saved.getId().toString());
            payload.put("submittedAt", FulfillException.ts(now));
            payload.put("textLength", TextRules.codePointLength(text));
            payload.put("imageCount", images);
            payload.put("videoCount", media.size() - images);
            orderLogWriter.write(locked, "CARE_LOG_SUBMITTED", Actor.sitter(actor.userId()), payload);
            OrderQueryService.OrderContextView octx = orderQueryService.getOrderContexts(Set.of(orderId)).get(orderId);
            notifier.careLogSubmitted(locked, new FulfillNotifier.NamedVisit(visitId, v.getServiceDate().toString(),
                    nameResolver.name(v, octx == null ? Map.of() : octx.planTitles())));
            CareLogResponses.Submitted resp = new CareLogResponses.Submitted(saved.getId(), VisitServiceReport.SUBMITTED, now);
            idempotencyService.complete(claim, 200, resp);
            return resp;
        });
    }
}
