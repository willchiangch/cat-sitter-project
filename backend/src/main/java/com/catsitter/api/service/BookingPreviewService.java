package com.catsitter.api.service;

import com.catsitter.api.dto.sitter.BookingPreviewResponse;
import com.catsitter.api.dto.sitter.QuestionItemResponse;
import com.catsitter.api.dto.sitter.ServicePlanResponse;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.ServiceRepository;
import com.catsitter.api.repository.SitterQuestionRepository;
import com.catsitter.api.repository.SitterClientWhitelistRepository;
import com.catsitter.api.entity.Account;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class BookingPreviewService {

    private final ProfileRepository profileRepository;
    private final ServiceRepository serviceRepository;
    private final SitterQuestionRepository questionRepository;
    private final SitterClientWhitelistRepository whitelistRepository;
    private final com.catsitter.api.service.storage.StorageService storageService;

    public BookingPreviewService(ProfileRepository profileRepository, 
                                 ServiceRepository serviceRepository, 
                                 SitterQuestionRepository questionRepository,
                                 SitterClientWhitelistRepository whitelistRepository,
                                 com.catsitter.api.service.storage.StorageService storageService) {
        this.profileRepository = profileRepository;
        this.serviceRepository = serviceRepository;
        this.questionRepository = questionRepository;
        this.whitelistRepository = whitelistRepository;
        this.storageService = storageService;
    }

    @Transactional(readOnly = true)
    public BookingPreviewResponse getBookingPreview(String slug, Account viewerAccount) {
        Profile sitterProfileEntity = profileRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Sitter not found"));
        
        if (sitterProfileEntity.getRoleType() != RoleType.SITTER) {
            throw new RuntimeException("Not a sitter profile");
        }

        // Determine if viewer is in whitelist
        boolean isWhitelisted = false;
        boolean isSitterSelf = false;

        if (viewerAccount != null) {
            // Check if viewer IS the sitter
            if (viewerAccount.getId().equals(sitterProfileEntity.getAccount().getId())) {
                isSitterSelf = true;
                isWhitelisted = true;
            } else {
                // Check if viewer has a CLIENT profile that is whitelisted by this sitter
                Optional<Profile> viewerClientProfile = profileRepository.findByAccountIdAndRoleType(viewerAccount.getId(), RoleType.CLIENT);
                if (viewerClientProfile.isPresent()) {
                    isWhitelisted = whitelistRepository.findBySitterProfileIdAndClientProfileId(sitterProfileEntity.getId(), viewerClientProfile.get().getId()).isPresent();
                }
            }
        }

        var sitterProfile = new BookingPreviewResponse.SitterPublicProfile(
                sitterProfileEntity.getId(),
                sitterProfileEntity.getName(),
                sitterProfileEntity.getSlug(),
                storageService.getUrl(sitterProfileEntity.getAvatarUrl()),
                sitterProfileEntity.getBioSummary(),
                sitterProfileEntity.getServiceAreas(),
                sitterProfileEntity.getProfessionalLabels()
        );

        final boolean finalIsWhitelisted = isWhitelisted;
        final boolean finalIsSitterSelf = isSitterSelf;

        var services = serviceRepository.findBySitterProfileIdOrderBySortOrderAsc(sitterProfileEntity.getId())
                .stream()
                .filter(s -> s.getIsActive())
                .filter(s -> {
                    // Filter whitelist-only services
                    if (Boolean.TRUE.equals(s.getIsWhitelistOnly())) {
                        return finalIsWhitelisted || finalIsSitterSelf;
                    }
                    return true;
                })
                .map(s -> new ServicePlanResponse(
                        s.getId(),
                        s.getName(),
                        s.getBasePrice(),
                        s.getDurationMinutes(),
                        s.getSupportedPetTypes(),
                        s.getIsActive(),
                        s.getBookableStartDate(),
                        s.getBookableEndDate(),
                        s.getEffectiveStartDate(),
                        s.getEffectiveEndDate(),
                        s.getDescription(),
                        s.getIsWhitelistOnly()
                ))
                .collect(Collectors.toList());

        var questionnaire = questionRepository.findBySitterProfileIdOrderBySortOrderAsc(sitterProfileEntity.getId())
                .stream()
                .filter(q -> q.getIsActive())
                .map(q -> new QuestionItemResponse(
                        q.getId(),
                        q.getTargetPetType(),
                        q.getQuestionText(),
                        q.getType(),
                        q.getRequired(),
                        q.getOptions(),
                        q.getSortOrder(),
                        q.getIsActive()
                ))
                .collect(Collectors.toList());

        return new BookingPreviewResponse(sitterProfile, services, questionnaire);
    }
}
