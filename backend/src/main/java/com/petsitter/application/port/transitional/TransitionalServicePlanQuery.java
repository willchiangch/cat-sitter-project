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
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedJdbc;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public java.util.Map<UUID, List<String>> findDefaultTasks(Set<UUID> planIds) {
        java.util.Map<UUID, List<String>> out = new java.util.LinkedHashMap<>();
        planIds.forEach(id -> out.put(id, List.of()));
        if (planIds.isEmpty()) {
            return out;
        }
        namedJdbc.query("SELECT id, default_tasks::text AS tasks FROM service_plans "
                        + "WHERE id IN (:ids) AND is_deleted = false AND is_active = true",
                java.util.Map.of("ids", planIds), rs -> {
                    out.put(rs.getObject("id", UUID.class), parseTasks(rs.getString("tasks")));
                });
        return out;
    }

    private List<String> parseTasks(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            tools.jackson.databind.JsonNode node = jsonMapper.readTree(json);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> tasks = new java.util.ArrayList<>();
            for (tools.jackson.databind.JsonNode e : node) {
                if (e != null && e.isString()) {
                    tasks.add(e.stringValue());
                }
            }
            return tasks;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

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
