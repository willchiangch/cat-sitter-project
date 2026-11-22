package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.BookingContextResponse;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.port.PublicProfileQuery;
import com.petsitter.application.port.ServicePlanQuery;
import com.petsitter.application.port.SubscriptionQuery.SubscriptionStatus;
import com.petsitter.infrastructure.security.TokenContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** E31 {@code GET /api/sitters/{sitterId}/booking-context}（匿名可；SD-ORDER §6.3）。 */
@Service
@RequiredArgsConstructor
public class BookingContextService {

    private final PublicProfileQuery publicProfileQuery;
    private final ServicePlanQuery servicePlanQuery;
    private final PlanPolicy planPolicy;
    private final BookingRules bookingRules;
    private final OrderClock clock;

    @Transactional(readOnly = true)
    public BookingContextResponse get(UUID sitterId) {
        if (!publicProfileQuery.sitterExists(sitterId)) {
            throw OrderException.notFound();
        }
        UUID viewerId = TokenContext.tryGetUserId().orElse(null);
        SubscriptionStatus sub = planPolicy.status(sitterId);
        BookingRules.Gate gate = bookingRules.evaluate(sitterId, viewerId, sub);
        List<BookingContextResponse.Plan> plans = List.of();
        if (gate.bookability() != BookingRules.Bookability.NOT_VERIFIED
                && gate.bookability() != BookingRules.Bookability.UNAVAILABLE) {
            boolean range = PlanPolicy.bookableRangeApplies(sub);
            plans = servicePlanQuery.listForSitter(sitterId).stream()
                    .filter(p -> BookingRules.planBookable(p, sitterId, gate.gatekeeper()))
                    .map(p -> new BookingContextResponse.Plan(p.planId(), p.title(), p.unitPrice(), p.petTypes(),
                            range ? p.bookableFrom() : null, range ? p.bookableTo() : null))
                    .toList();
        }
        BookingContextResponse.Questionnaire questionnaire = null;
        if (viewerId != null && "OWNER".equals(CurrentActor.currentRole())) {
            BookingRules.QuestionnaireDecision d = bookingRules.questionnaire(viewerId, sitterId);
            questionnaire = new BookingContextResponse.Questionnaire(d.required(),
                    d.exemptReason() == null ? null : d.exemptReason().name(), BookingRules.toQuestions(d.questions()));
        }
        return new BookingContextResponse(sitterId, gate.bookability().name(), plans, questionnaire, clock.now());
    }
}
