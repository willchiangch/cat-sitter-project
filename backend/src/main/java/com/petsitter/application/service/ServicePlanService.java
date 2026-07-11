package com.petsitter.application.service;

import com.petsitter.application.dto.ServicePlanDto;
import com.petsitter.application.dto.ServicePlanSortRequest;
import com.petsitter.application.exception.AuthPlanLimitException;
import com.petsitter.application.exception.ServicePlanException;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.ServicePlanRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServicePlanService {

    private final ServicePlanRepository servicePlanRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final GatekeeperService gatekeeperService;

    @Transactional
    public ServicePlanDto createPlan(ServicePlanDto dto, UUID sitterId) {
        User sitter = userRepository.findById(sitterId)
                .orElseThrow(() -> new ServicePlanException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "找不到保母資訊"));

        try {
            checkPlanTierLimit(sitterId, dto.getStartDate(), dto.getEndDate());
        } catch (AuthPlanLimitException e) {
            auditLogService.writeLog(sitterId, "SERVICE_PLAN_CRUD", "CREATE_FAIL", "SaaS Limit validation failed: " + e.getMessage());
            throw e;
        }

        ServicePlan plan = ServicePlan.builder()
                .sitter(sitter)
                .name(dto.getName())
                .price(dto.getPrice().setScale(0, RoundingMode.HALF_UP).longValue())
                .dailyCapacity(dto.getDailyCapacity())
                .defaultTasks(dto.getDefaultTasks() != null ? dto.getDefaultTasks() : List.of())
                .applicablePetTypes(dto.getApplicablePetTypes() != null ? dto.getApplicablePetTypes() : List.of())
                .description(dto.getDescription())
                .startDate(dto.getStartDate())
                .endDate(dto.getEndDate())
                .isRestricted(dto.getIsRestricted() != null && dto.getIsRestricted())
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                .isDeleted(false)
                .build();

        ServicePlan savedPlan = servicePlanRepository.saveAndFlush(plan);

        auditLogService.writeLog(sitterId, "SERVICE_PLAN_CRUD", "CREATE_SUCCESS",
                String.format("Created service plan with ID: %s and name: %s", savedPlan.getId(), savedPlan.getName()));

        return toDto(savedPlan);
    }

    @Transactional
    public ServicePlanDto updatePlan(UUID planId, ServicePlanDto dto, UUID sitterId) {
        ServicePlan plan = servicePlanRepository.findById(planId)
                .orElseThrow(() -> new ServicePlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "方案不存在"));

        if (plan.isDeleted()) {
            throw new ServicePlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "方案不存在");
        }

        // IDOR 越權防禦
        if (!plan.getSitter().getId().equals(sitterId)) {
            throw new ServicePlanException(HttpStatus.FORBIDDEN, "FORBIDDEN", "無權限編輯此方案");
        }

        // 樂觀鎖驗證
        if (dto.getVersion() == null || !dto.getVersion().equals(plan.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(ServicePlan.class, planId);
        }

        try {
            checkPlanTierLimit(sitterId, dto.getStartDate(), dto.getEndDate());
        } catch (AuthPlanLimitException e) {
            auditLogService.writeLog(sitterId, "SERVICE_PLAN_CRUD", "UPDATE_FAIL", "SaaS Limit validation failed: " + e.getMessage());
            throw e;
        }

        plan.setName(dto.getName());
        plan.setPrice(dto.getPrice().setScale(0, RoundingMode.HALF_UP).longValue());
        plan.setDailyCapacity(dto.getDailyCapacity());
        plan.setDefaultTasks(dto.getDefaultTasks() != null ? dto.getDefaultTasks() : List.of());
        plan.setApplicablePetTypes(dto.getApplicablePetTypes() != null ? dto.getApplicablePetTypes() : List.of());
        plan.setDescription(dto.getDescription());
        plan.setStartDate(dto.getStartDate());
        plan.setEndDate(dto.getEndDate());
        plan.setRestricted(dto.getIsRestricted() != null && dto.getIsRestricted());
        if (dto.getSortOrder() != null) {
            plan.setSortOrder(dto.getSortOrder());
        }

        ServicePlan savedPlan = servicePlanRepository.saveAndFlush(plan);

        auditLogService.writeLog(sitterId, "SERVICE_PLAN_CRUD", "UPDATE_SUCCESS",
                String.format("Updated service plan with ID: %s and name: %s", savedPlan.getId(), savedPlan.getName()));

        return toDto(savedPlan);
    }

    @Transactional
    public void deletePlan(UUID planId, UUID sitterId) {
        ServicePlan plan = servicePlanRepository.findById(planId)
                .orElseThrow(() -> new ServicePlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "方案不存在"));

        if (plan.isDeleted()) {
            throw new ServicePlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "方案不存在");
        }

        // IDOR 越權防禦
        if (!plan.getSitter().getId().equals(sitterId)) {
            throw new ServicePlanException(HttpStatus.FORBIDDEN, "FORBIDDEN", "無權限刪除此方案");
        }

        plan.setDeleted(true);
        servicePlanRepository.saveAndFlush(plan);

        auditLogService.writeLog(sitterId, "SERVICE_PLAN_CRUD", "DELETE_SUCCESS",
                String.format("Deleted service plan with ID: %s", planId));
    }

    @Transactional
    public ServicePlanDto setPlanActive(UUID planId, boolean isActive, UUID sitterId) {
        ServicePlan plan = servicePlanRepository.findById(planId)
                .orElseThrow(() -> new ServicePlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "方案不存在"));

        if (plan.isDeleted()) {
            throw new ServicePlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "方案不存在");
        }

        // IDOR 越權防禦
        if (!plan.getSitter().getId().equals(sitterId)) {
            throw new ServicePlanException(HttpStatus.FORBIDDEN, "FORBIDDEN", "無權限操作此方案");
        }

        plan.setActive(isActive);
        ServicePlan savedPlan = servicePlanRepository.saveAndFlush(plan);

        auditLogService.writeLog(sitterId, "SERVICE_PLAN_CRUD", isActive ? "ACTIVATE_SUCCESS" : "DEACTIVATE_SUCCESS",
                String.format("%s service plan with ID: %s", isActive ? "Activated" : "Deactivated", planId));

        return toDto(savedPlan);
    }

    @Transactional
    public void sortPlans(ServicePlanSortRequest request, UUID sitterId) {
        if (request.getPlanIds() == null || request.getPlanIds().isEmpty()) {
            return;
        }

        for (int i = 0; i < request.getPlanIds().size(); i++) {
            UUID planId = request.getPlanIds().get(i);
            ServicePlan plan = servicePlanRepository.findById(planId)
                    .orElseThrow(() -> new ServicePlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "方案不存在"));

            if (plan.isDeleted()) {
                throw new ServicePlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "方案不存在");
            }

            // IDOR 越權防禦
            if (!plan.getSitter().getId().equals(sitterId)) {
                throw new ServicePlanException(HttpStatus.FORBIDDEN, "FORBIDDEN", "無權限編輯此方案");
            }

            plan.setSortOrder(i);
            servicePlanRepository.saveAndFlush(plan);
        }

        auditLogService.writeLog(sitterId, "SERVICE_PLAN_CRUD", "UPDATE_SUCCESS",
                "Sorted service plans for sitter: " + sitterId);
    }

    @Transactional(readOnly = true)
    public List<ServicePlanDto> getPlansForSitter(UUID sitterId) {
        return servicePlanRepository.findBySitterIdAndIsDeletedOrderBySortOrderAsc(sitterId, false)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ServicePlanDto> getActivePlansForOwner(UUID sitterId, UUID currentUserId) {
        if (currentUserId != null && gatekeeperService.isBlocked(sitterId, currentUserId, null)) {
            throw new org.springframework.security.access.AccessDeniedException("保母目前不開放預約");
        }

        List<ServicePlan> plans = servicePlanRepository.findBySitterIdAndIsDeletedOrderBySortOrderAsc(sitterId, false);
        LocalDate now = LocalDate.now();

        return plans.stream()
                // 已下架 (isActive = false) 的方案不對飼主顯示
                .filter(ServicePlan::isActive)
                // 3. 生效日期區間過濾：比對目前系統日期。若有設定且不在範圍內，則排除。
                .filter(plan -> {
                    if (plan.getStartDate() != null && now.isBefore(plan.getStartDate())) {
                        return false;
                    }
                    if (plan.getEndDate() != null && now.isAfter(plan.getEndDate())) {
                        return false;
                    }
                    return true;
                })
                // 4. 門禁白名單/黑名單過濾
                .filter(plan -> {
                    if (currentUserId == null) return true;
                    return !gatekeeperService.isBlocked(sitterId, currentUserId, plan.getId());
                })
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private void checkPlanTierLimit(UUID sitterId, LocalDate startDate, LocalDate endDate) {
        if (startDate != null || endDate != null) {
            String planTier = subscriptionRepository.findBySitterId(sitterId)
                    .map(Subscription::getPlanTier)
                    .orElse("FREE");
            if (!"PRO".equals(planTier) && !"ULTIMATE".equals(planTier)) {
                throw new AuthPlanLimitException("非專業版或頂級版方案保母，無法設定開放日期限制");
            }
        }
    }

    private ServicePlanDto toDto(ServicePlan plan) {
        return ServicePlanDto.builder()
                .id(plan.getId())
                .sitterId(plan.getSitter().getId())
                .name(plan.getName())
                .price(BigDecimal.valueOf(plan.getPrice()))
                .dailyCapacity(plan.getDailyCapacity())
                .defaultTasks(plan.getDefaultTasks())
                .applicablePetTypes(plan.getApplicablePetTypes())
                .description(plan.getDescription())
                .startDate(plan.getStartDate())
                .endDate(plan.getEndDate())
                .isRestricted(plan.isRestricted())
                .sortOrder(plan.getSortOrder())
                .isActive(plan.isActive())
                .version(plan.getVersion())
                .build();
    }
}
