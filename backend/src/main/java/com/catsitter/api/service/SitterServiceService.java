package com.catsitter.api.service;

import com.catsitter.api.dto.sitter.CreateServiceRequest;
import com.catsitter.api.dto.sitter.ServicePlanResponse;
import com.catsitter.api.dto.sitter.UpdateServiceRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.ServiceRepository;
import com.catsitter.api.repository.SitterSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@org.springframework.stereotype.Service
public class SitterServiceService {

    private final ServiceRepository serviceRepository;
    private final ProfileRepository profileRepository;
    private final SitterSubscriptionRepository subscriptionRepository;

    public SitterServiceService(ServiceRepository serviceRepository, 
                               ProfileRepository profileRepository,
                               SitterSubscriptionRepository subscriptionRepository) {
        this.serviceRepository = serviceRepository;
        this.profileRepository = profileRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional(readOnly = true)
    public List<ServicePlanResponse> getSitterServices(Account account) {
        Profile profile = getSitterProfile(account);
        return serviceRepository.findBySitterProfileIdOrderBySortOrderAsc(profile.getId())
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ServicePlanResponse createService(Account account, CreateServiceRequest request) {
        Profile profile = getSitterProfile(account);
        
        // Check PREMIUM plan for Whitelist feature
        if (Boolean.TRUE.equals(request.isWhitelistOnly())) {
            validatePremiumPlan(profile.getId());
        }

        com.catsitter.api.entity.Service service = new com.catsitter.api.entity.Service();
        service.setSitterProfile(profile);
        service.setName(request.name());
        service.setBasePrice(request.basePrice());
        service.setDurationMinutes(request.durationMinutes());
        service.setSupportedPetTypes(request.supportedPetTypes());
        service.setIsActive(true);
        service.setSortOrder(0); // Default
        service.setBookableStartDate(request.bookableStartDate());
        service.setBookableEndDate(request.bookableEndDate());
        service.setEffectiveStartDate(request.effectiveStartDate());
        service.setEffectiveEndDate(request.effectiveEndDate());
        service.setDescription(request.description());
        service.setIsWhitelistOnly(request.isWhitelistOnly() != null && request.isWhitelistOnly());

        return mapToResponse(serviceRepository.save(service));
    }

    @Transactional
    public ServicePlanResponse updateService(Account account, UUID serviceId, UpdateServiceRequest request) {
        Profile profile = getSitterProfile(account);
        com.catsitter.api.entity.Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new RuntimeException("Service not found"));

        if (!service.getSitterProfile().getId().equals(profile.getId())) {
            throw new RuntimeException("Unauthorized to update this service");
        }

        // Check PREMIUM plan for Whitelist feature
        if (Boolean.TRUE.equals(request.isWhitelistOnly())) {
            validatePremiumPlan(profile.getId());
        }

        service.setName(request.name());
        service.setBasePrice(request.basePrice());
        service.setDurationMinutes(request.durationMinutes());
        service.setSupportedPetTypes(request.supportedPetTypes());
        service.setIsActive(request.isActive());
        service.setBookableStartDate(request.bookableStartDate());
        service.setBookableEndDate(request.bookableEndDate());
        service.setEffectiveStartDate(request.effectiveStartDate());
        service.setEffectiveEndDate(request.effectiveEndDate());
        service.setDescription(request.description());
        service.setIsWhitelistOnly(request.isWhitelistOnly() != null && request.isWhitelistOnly());

        return mapToResponse(serviceRepository.save(service));
    }

    private void validatePremiumPlan(UUID profileId) {
        var sub = subscriptionRepository.findTopBySitterProfileIdOrderByCreatedAtDesc(profileId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));
        if (!"PREMIUM".equals(sub.getPlan().getPlanCode()) || !"ACTIVE".equals(sub.getStatus())) {
            throw new RuntimeException("只有 1299 頂級方案用戶可使用白名單限定功能");
        }
    }

    @Transactional
    public void deleteService(Account account, UUID serviceId) {
        Profile profile = getSitterProfile(account);
        com.catsitter.api.entity.Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new RuntimeException("Service not found"));

        if (!service.getSitterProfile().getId().equals(profile.getId())) {
            throw new RuntimeException("Unauthorized to delete this service");
        }

        serviceRepository.delete(service);
    }

    private Profile getSitterProfile(Account account) {
        return profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));
    }

    private ServicePlanResponse mapToResponse(com.catsitter.api.entity.Service service) {
        return new ServicePlanResponse(
                service.getId(),
                service.getName(),
                service.getBasePrice(),
                service.getDurationMinutes(),
                service.getSupportedPetTypes(),
                service.getIsActive(),
                service.getBookableStartDate(),
                service.getBookableEndDate(),
                service.getEffectiveStartDate(),
                service.getEffectiveEndDate(),
                service.getDescription(),
                service.getIsWhitelistOnly()
        );
    }
}
