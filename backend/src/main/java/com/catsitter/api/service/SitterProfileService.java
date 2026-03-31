package com.catsitter.api.service;

import com.catsitter.api.dto.sitter.SitterProfileResponse;
import com.catsitter.api.dto.sitter.UpdateSitterProfileRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SitterProfileService {

    private final ProfileRepository profileRepository;
    private final com.catsitter.api.service.storage.StorageService storageService;

    @org.springframework.beans.factory.annotation.Value("${application.subscription.notification-days}")
    private Integer globalNotificationDays;

    public SitterProfileService(ProfileRepository profileRepository, 
                               com.catsitter.api.service.storage.StorageService storageService) {
        this.profileRepository = profileRepository;
        this.storageService = storageService;
    }

    @Transactional(readOnly = true)
    public SitterProfileResponse getSitterProfile(Account account) {
        Profile profile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));
        
        return mapToResponse(profile);
    }

    @Transactional
    public SitterProfileResponse updateSitterProfile(Account account, UpdateSitterProfileRequest request) {
        Profile profile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));

        profile.setName(request.name());
        profile.setAvatarUrl(request.avatarUrl());
        profile.setPhone(request.phone());
        profile.setServiceAreas(request.serviceAreas());
        profile.setBioSummary(request.bioSummary());
        profile.setProfessionalLabels(request.professionalLabels());
        profile.setBankCode(request.bankCode());
        profile.setBankAccount(request.bankAccount());
        profile.setBankAccountHolder(request.bankAccountHolder());

        return mapToResponse(profileRepository.save(profile));
    }

    private SitterProfileResponse mapToResponse(Profile profile) {
        return new SitterProfileResponse(
                profile.getId(),
                profile.getName(),
                storageService.getUrl(profile.getAvatarUrl()),
                profile.getPhone(),
                profile.getServiceAreas(),
                profile.getBioSummary(),
                profile.getIsVerified(),
                storageService.getSignedUrl(profile.getIdCardFrontUrl()),
                storageService.getSignedUrl(profile.getIdCardBackUrl()),
                profile.getProfessionalLabels(),
                profile.getBankCode(),
                profile.getBankAccount(),
                profile.getBankAccountHolder(),
                globalNotificationDays
        );
    }
}
