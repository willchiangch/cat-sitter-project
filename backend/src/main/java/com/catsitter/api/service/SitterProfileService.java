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

    @org.springframework.beans.factory.annotation.Value("${application.subscription.notification-days}")
    private Integer globalNotificationDays;

    public SitterProfileService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
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

        return mapToResponse(profileRepository.save(profile));
    }

    private SitterProfileResponse mapToResponse(Profile profile) {
        return new SitterProfileResponse(
                profile.getId(),
                profile.getName(),
                profile.getAvatarUrl(),
                profile.getPhone(),
                profile.getServiceAreas(),
                profile.getBioSummary(),
                globalNotificationDays
        );
    }
}
