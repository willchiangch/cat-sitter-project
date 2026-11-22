package com.petsitter.application.service.fulfill;

import com.petsitter.application.dto.fulfill.CareLogResponses;
import com.petsitter.application.exception.FulfillException;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.fulfill.ServiceReportMedia;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.fulfill.VisitServiceReport;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.ServiceReportMediaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 照護日誌共用規則（SD-FULFILL §3.2、§4.5；PRD-022 判斷順序第 8–10 步與 BR-9 額度）。 */
@Component
@RequiredArgsConstructor
public class CareLogSupport {

    public static final String IMAGE = "IMAGE";
    public static final String VIDEO = "VIDEO";

    private final OrderQueryService orderQueryService;
    private final ServiceReportMediaRepository mediaRepository;
    private final VisitRules rules;

    /** O2 快照額度；無快照視為 FREE（0 / 0）。 */
    public record Limits(String planTier, int imageLimit, int videoLimit) {

        public int limit(String mediaType) {
            return IMAGE.equals(mediaType) ? imageLimit : videoLimit;
        }
    }

    public Limits limits(UUID orderId) {
        return orderQueryService.getMediaQuota(orderId)
                .map(q -> new Limits(q.planTier(), q.maxPhotos(), q.maxVideos()))
                .orElse(new Limits("FREE", 0, 0));
    }

    public long used(VisitServiceReport report, String mediaType) {
        return report == null ? 0 : mediaRepository.countByReportIdAndMediaType(report.getId(), mediaType);
    }

    public CareLogResponses.Quota quota(Limits limits, VisitServiceReport report) {
        return new CareLogResponses.Quota(limits.planTier(), limits.imageLimit(), used(report, IMAGE), limits.videoLimit(), used(report, VIDEO));
    }

    public CareLogResponses.Quota quota(Limits limits, List<ServiceReportMedia> media) {
        long images = media.stream().filter(m -> IMAGE.equals(m.getMediaType())).count();
        return new CareLogResponses.Quota(limits.planTier(), limits.imageLimit(), images, limits.videoLimit(), media.size() - images);
    }

    /**
     * 日誌寫入窗口（F14 / F16 / F18 / F19；F15 另要求 DONE）：訂單 IN_PROGRESS / DISPUTED → 行程 IN_PROGRESS / DONE →
     * 日誌非 SUBMITTED → DONE 時 {@code at < completed_at + CARE_LOG_EDIT_HOURS}。
     */
    public void requireWritable(OrderStatus orderStatus, Visit v, VisitServiceReport report, OffsetDateTime at, boolean requireDone) {
        if (!VisitRules.RUNNING_ORDER.contains(orderStatus)) {
            throw FulfillException.orderState(orderStatus);
        }
        boolean ok = requireDone ? v.getStatus() == VisitStatus.DONE
                : v.getStatus() == VisitStatus.IN_PROGRESS || v.getStatus() == VisitStatus.DONE;
        if (!ok) {
            throw FulfillException.visitState(v.getStatus(), v.getCompletionSource());
        }
        requireNotSubmitted(report);
        if (rules.careLogExpired(v, at)) {
            throw FulfillException.deadline(VisitRules.DEADLINE_CARE_LOG, rules.careLogDeadline(v));
        }
    }

    public static void requireNotSubmitted(VisitServiceReport report) {
        if (report != null && VisitServiceReport.SUBMITTED.equals(report.getStatus())) {
            throw FulfillException.careLogState(VisitServiceReport.SUBMITTED);
        }
    }
}
