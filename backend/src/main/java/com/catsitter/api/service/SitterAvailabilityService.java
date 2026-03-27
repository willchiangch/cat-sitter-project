package com.catsitter.api.service;

import com.catsitter.api.dto.sitter.AvailabilityResponse;
import com.catsitter.api.dto.sitter.UpdateAvailabilityRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SitterAvailabilityService {

    private final ProfileRepository profileRepository;

    public SitterAvailabilityService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(Account account) {
        Profile profile = getSitterProfile(account);
        return mapToResponse(profile);
    }

    @Transactional
    public AvailabilityResponse updateAvailability(Account account, UpdateAvailabilityRequest request) {
        Profile profile = getSitterProfile(account);
        profile.setBookingOpenStart(request.bookingOpenStart());
        profile.setBookingOpenEnd(request.bookingOpenEnd());
        profile.setWeeklyAvailability(request.weeklyAvailability());
        profile.setSpecificExclusions(request.specificExclusions());
        return mapToResponse(profileRepository.save(profile));
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse getPublicAvailability(String slug) {
        Profile profile = profileRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Sitter not found"));
        if (profile.getRoleType() != RoleType.SITTER) {
            throw new RuntimeException("Not a sitter profile");
        }
        return mapToResponse(profile);
    }

    private Profile getSitterProfile(Account account) {
        return profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));
    }

    private AvailabilityResponse mapToResponse(Profile profile) {
        return new AvailabilityResponse(
                profile.getBookingOpenStart(),
                profile.getBookingOpenEnd(),
                profile.getWeeklyAvailability(),
                profile.getSpecificExclusions()
        );
    }
}
