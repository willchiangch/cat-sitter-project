package com.petsitter.application.port.transitional;

import com.petsitter.application.port.ServicePlanQuery;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.repository.ServicePlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** SD-SUPPLY 未重建前的過渡實作。 */
@Component
@RequiredArgsConstructor
public class TransitionalServicePlanQuery implements ServicePlanQuery {

    private final ServicePlanRepository servicePlanRepository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<PlanView> findForBooking(UUID sitterId, Set<UUID> planIds) {
        return servicePlanRepository.findAllById(planIds).stream().map(TransitionalServicePlanQuery::view).toList();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<PlanView> listForSitter(UUID sitterId) {
        return servicePlanRepository.findBySitterIdAndIsDeletedOrderBySortOrderAsc(sitterId, false).stream()
                .map(TransitionalServicePlanQuery::view).toList();
    }

    static PlanView view(ServicePlan p) {
        return new PlanView(p.getId(), p.getSitter().getId(), p.getName(), Math.toIntExact(p.getPrice()), p.isActive(),
                p.isDeleted(), p.getStartDate(), p.getEndDate(),
                p.getApplicablePetTypes() == null ? List.of() : List.copyOf(p.getApplicablePetTypes()), p.getSortOrder());
    }
}
