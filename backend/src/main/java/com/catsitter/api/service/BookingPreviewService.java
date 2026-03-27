package com.catsitter.api.service;

import com.catsitter.api.dto.sitter.BookingPreviewResponse;
import com.catsitter.api.dto.sitter.QuestionItemResponse;
import com.catsitter.api.dto.sitter.ServicePlanResponse;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.ServiceRepository;
import com.catsitter.api.repository.SitterQuestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BookingPreviewService {

    private final ProfileRepository profileRepository;
    private final ServiceRepository serviceRepository;
    private final SitterQuestionRepository questionRepository;

    public BookingPreviewService(ProfileRepository profileRepository, 
                                 ServiceRepository serviceRepository, 
                                 SitterQuestionRepository questionRepository) {
        this.profileRepository = profileRepository;
        this.serviceRepository = serviceRepository;
        this.questionRepository = questionRepository;
    }

    @Transactional(readOnly = true)
    public BookingPreviewResponse getBookingPreview(String slug) {
        Profile profile = profileRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Sitter not found"));
        
        if (profile.getRoleType() != RoleType.SITTER) {
            throw new RuntimeException("Not a sitter profile");
        }

        var sitterProfile = new BookingPreviewResponse.SitterPublicProfile(
                profile.getName(),
                profile.getAvatarUrl(),
                profile.getBioSummary(),
                profile.getServiceAreas()
        );

        var services = serviceRepository.findBySitterProfileIdOrderBySortOrderAsc(profile.getId())
                .stream()
                .filter(s -> s.getIsActive())
                .map(s -> new ServicePlanResponse(
                        s.getId(),
                        s.getName(),
                        s.getBasePrice(),
                        s.getDurationMinutes(),
                        s.getSupportedPetTypes(),
                        s.getIsActive()
                ))
                .collect(Collectors.toList());

        var questionnaire = questionRepository.findBySitterProfileIdOrderBySortOrderAsc(profile.getId())
                .stream()
                .filter(q -> q.getIsActive())
                .map(q -> new QuestionItemResponse(
                        q.getId(),
                        q.getTargetPetType(),
                        q.getQuestionText(),
                        q.getSortOrder(),
                        q.getIsActive()
                ))
                .collect(Collectors.toList());

        return new BookingPreviewResponse(sitterProfile, services, questionnaire);
    }
}
