package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.BookingContextResponse;
import com.petsitter.application.port.GatekeeperQuery;
import com.petsitter.application.port.PublicProfileQuery;
import com.petsitter.application.port.ServicePlanQuery.PlanView;
import com.petsitter.application.port.SitterQuestionQuery;
import com.petsitter.application.port.SitterQuestionQuery.QuestionView;
import com.petsitter.application.port.SitterStandingQuery;
import com.petsitter.application.port.SubscriptionQuery.SubscriptionStatus;
import com.petsitter.domain.model.order.QuestionnaireExemptReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** E1 / E31 共用的可預約判斷（PRD-005 §4.1 / BR-6–BR-13；SD-ORDER §6.3 E31 判斷順序）。 */
@Component
@RequiredArgsConstructor
public class BookingRules {

    public enum Bookability { BOOKABLE, SELF, NOT_VERIFIED, UNAVAILABLE, PAUSED }

    private final SitterStandingQuery sitterStandingQuery;
    private final PublicProfileQuery publicProfileQuery;
    private final GatekeeperQuery gatekeeperQuery;
    private final SitterQuestionQuery sitterQuestionQuery;
    private final OrderQueryService orderQueryService;
    private final OrderClock clock;

    public record Gate(Bookability bookability, SubscriptionStatus subscription, GatekeeperQuery.GatekeeperResult gatekeeper,
                       boolean quotaReached) {
    }

    /** SELF → NOT_VERIFIED → UNAVAILABLE（隱藏 / 全域黑名單 / 全域白名單外）→ PAUSED（額度滿 / 鎖定 / 停權）→ BOOKABLE。 */
    public Gate evaluate(UUID sitterId, UUID viewerId, SubscriptionStatus sub) {
        GatekeeperQuery.GatekeeperResult gate = gatekeeperQuery.evaluate(sitterId, viewerId, sub.tier());
        boolean quotaReached = orderQueryService.countConfirmedInMonth(sitterId, clock.monthTaipei())
                >= PlanPolicy.quotaLimit(sub.tier());
        Bookability b;
        if (sitterId.equals(viewerId)) {
            b = Bookability.SELF;
        } else if (!sitterStandingQuery.get(sitterId).verified()) {
            b = Bookability.NOT_VERIFIED;
        } else if (!publicProfileQuery.isVisible(sitterId) || gate.globallyBlocked()) {
            b = Bookability.UNAVAILABLE;
        } else if (quotaReached || sub.locked()) {
            b = Bookability.PAUSED;
        } else {
            b = Bookability.BOOKABLE;
        }
        return new Gate(b, sub, gate, quotaReached);
    }

    /** 方案是否可對此檢視者預約（存在、屬此保母、上架、未刪除、門禁未隱藏）。 */
    public static boolean planBookable(PlanView p, UUID sitterId, GatekeeperQuery.GatekeeperResult gate) {
        return p != null && p.sitterId().equals(sitterId) && p.active() && !p.deleted()
                && !gate.hiddenPlanIds().contains(p.planId());
    }

    public record QuestionnaireDecision(boolean required, QuestionnaireExemptReason exemptReason, List<QuestionView> questions) {
    }

    /** 熟客 → REGULAR_OWNER（兩者並存亦此）；無啟用題目 → NO_ACTIVE_QUESTIONS；其他需填（baseline §3.5、BR-13）。 */
    public QuestionnaireDecision questionnaire(UUID ownerId, UUID sitterId) {
        List<QuestionView> active = sitterQuestionQuery.listActive(sitterId);
        if (ownerId != null && orderQueryService.isRegularOwner(ownerId, sitterId)) {
            return new QuestionnaireDecision(false, QuestionnaireExemptReason.REGULAR_OWNER, List.of());
        }
        if (active.isEmpty()) {
            return new QuestionnaireDecision(false, QuestionnaireExemptReason.NO_ACTIVE_QUESTIONS, List.of());
        }
        return new QuestionnaireDecision(true, null, active);
    }

    public static List<BookingContextResponse.Question> toQuestions(List<QuestionView> qs) {
        return qs.stream().map(q -> new BookingContextResponse.Question(q.questionId(), q.text(), q.type(), q.options(),
                q.required(), q.sortOrder())).toList();
    }
}
