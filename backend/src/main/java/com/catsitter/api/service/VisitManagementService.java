package com.catsitter.api.service;

import com.catsitter.api.dto.visit.UpdateChecklistRequest;
import com.catsitter.api.dto.visit.VisitDetailResponse;
import com.catsitter.api.dto.visit.VisitSummaryResponse;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.Visit;
import com.catsitter.api.entity.VisitService;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.entity.enums.VisitStatus;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.VisitRepository;
import com.catsitter.api.repository.VisitServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.catsitter.api.dto.visit.AddVisitMediaRequest;
import com.catsitter.api.entity.VisitMedia;
import com.catsitter.api.repository.VisitMediaRepository;

@Service
public class VisitManagementService {

    private final VisitRepository visitRepository;
    private final VisitServiceRepository visitServiceRepository;
    private final ProfileRepository profileRepository;
    private final VisitMediaRepository visitMediaRepository;

    public VisitManagementService(VisitRepository visitRepository,
                                  VisitServiceRepository visitServiceRepository,
                                  ProfileRepository profileRepository,
                                  VisitMediaRepository visitMediaRepository) {
        this.visitRepository = visitRepository;
        this.visitServiceRepository = visitServiceRepository;
        this.profileRepository = profileRepository;
        this.visitMediaRepository = visitMediaRepository;
    }

    @Transactional(readOnly = true)
    public List<VisitSummaryResponse> listSitterVisits(Account sitterAccount, LocalDate date) {
        Profile sitter = profileRepository.findByAccountAndRoleType(sitterAccount, RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));

        OffsetDateTime start = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        return visitRepository.findBySitterAndDate(sitter, start, end).stream()
                .map(v -> new VisitSummaryResponse(
                        v.getId(),
                        v.getOrder().getId(),
                        v.getOrder().getClientProfile().getName(),
                        v.getOrder().getServiceName(),
                        v.getVisitStartTime(),
                        v.getVisitEndTime(),
                        v.getStatus()
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public VisitDetailResponse getVisitDetail(Account account, UUID visitId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new RuntimeException("Visit not found"));

        // Ownership check
        boolean isSitter = visit.getOrder().getCurrentSitter().getAccount().getId().equals(account.getId());
        boolean isClient = visit.getOrder().getClientProfile().getAccount().getId().equals(account.getId());
        
        if (!isSitter && !isClient) {
            throw new RuntimeException("Unauthorized to view this visit");
        }

        List<VisitDetailResponse.ChecklistItemResponse> items = visitServiceRepository.findByVisitIdOrderBySortOrderAsc(visitId).stream()
                .map(item -> new VisitDetailResponse.ChecklistItemResponse(
                        item.getId(),
                        item.getServiceType().name(),
                        item.getDescription(),
                        item.getIsCompleted(),
                        item.getPhotoUrl(),
                        item.getCompletedAt()
                ))
                .collect(Collectors.toList());

        List<VisitDetailResponse.VisitMediaResponse> moments = visitMediaRepository.findByVisitIdOrderByCreatedAtAsc(visitId).stream()
                .map(media -> new VisitDetailResponse.VisitMediaResponse(
                        media.getId(),
                        media.getMediaUrl(),
                        media.getCaption(),
                        media.getMediaType() != null ? media.getMediaType().name() : "IMAGE",
                        media.getCreatedAt()
                ))
                .collect(Collectors.toList());

        return new VisitDetailResponse(
                visit.getId(),
                visit.getOrder().getId(),
                visit.getVisitStartTime(),
                visit.getVisitEndTime(),
                visit.getStatus(),
                visit.getSitterNotes(),
                items,
                moments
        );
    }

    @Transactional
    public VisitDetailResponse addVisitMedia(Account sitterAccount, UUID visitId, AddVisitMediaRequest request) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new RuntimeException("Visit not found"));

        if (!visit.getOrder().getCurrentSitter().getAccount().getId().equals(sitterAccount.getId())) {
            throw new RuntimeException("Unauthorized: You are not the sitter for this visit");
        }

        VisitMedia media = new VisitMedia();
        media.setVisit(visit);
        media.setMediaUrl(request.mediaUrl());
        media.setCaption(request.caption());
        media.setMediaType(com.catsitter.api.entity.enums.MediaType.valueOf(request.mediaType() != null ? request.mediaType() : "IMAGE"));
        media.setCreatedAt(OffsetDateTime.now());
        
        visitMediaRepository.save(media);

        return getVisitDetail(sitterAccount, visitId);
    }

    @Transactional
    public VisitDetailResponse updateChecklistItem(Account setterAccount, UUID visitId, UpdateChecklistRequest request) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new RuntimeException("Visit not found"));

        if (!visit.getOrder().getCurrentSitter().getAccount().getId().equals(setterAccount.getId())) {
            throw new RuntimeException("Unauthorized: You are not the sitter for this visit");
        }

        VisitService item = visitServiceRepository.findById(request.itemId())
                .orElseThrow(() -> new RuntimeException("Checklist item not found"));

        if (!item.getVisit().getId().equals(visitId)) {
            throw new RuntimeException("Checklist item does not belong to this visit");
        }

        item.setIsCompleted(request.isCompleted());
        item.setPhotoUrl(request.photoUrl());
        if (request.isCompleted()) {
            item.setCompletedAt(OffsetDateTime.now());
        } else {
            item.setCompletedAt(null);
        }
        visitServiceRepository.save(item);

        return getVisitDetail(setterAccount, visitId);
    }

    @Transactional
    public VisitDetailResponse completeVisit(Account setterAccount, UUID visitId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new RuntimeException("Visit not found"));

        if (!visit.getOrder().getCurrentSitter().getAccount().getId().equals(setterAccount.getId())) {
            throw new RuntimeException("Unauthorized: You are not the sitter for this visit");
        }

        visit.setStatus(VisitStatus.DONE);
        visitRepository.save(visit);

        return getVisitDetail(setterAccount, visitId);
    }
}
