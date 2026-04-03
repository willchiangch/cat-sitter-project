package com.catsitter.api.service;

import com.catsitter.api.dto.SitterTrustCircleDTO;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.SitterTrustCircle;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.entity.enums.TrustCircleStatus;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.SitterTrustCircleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SitterTrustCircleService {

    private final SitterTrustCircleRepository trustCircleRepository;
    private final ProfileRepository profileRepository;

    public SitterTrustCircleService(SitterTrustCircleRepository trustCircleRepository, ProfileRepository profileRepository) {
        this.trustCircleRepository = trustCircleRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public List<SitterTrustCircleDTO> getTrustCircle(Account account) {
        return profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .map(sitter -> trustCircleRepository.findByOwnerSitterIdAndStatus(sitter.getId(), TrustCircleStatus.ACTIVE)
                        .stream()
                        .map(SitterTrustCircleDTO::fromEntity)
                        .toList())
                .orElse(java.util.Collections.emptyList());
    }

    @Transactional
    public SitterTrustCircleDTO addMember(Account account, UUID trustedSitterId) {
        Profile owner = getSitterProfile(account);
        Profile trusted = profileRepository.findById(trustedSitterId)
                .orElseThrow(() -> new RuntimeException("Trusted sitter profile not found"));

        if (owner.getId().equals(trustedSitterId)) {
            throw new RuntimeException("Cannot add yourself to trust circle");
        }

        SitterTrustCircle trustCircle = new SitterTrustCircle();
        trustCircle.setOwnerSitter(owner);
        trustCircle.setTrustedSitter(trusted);
        trustCircle.setStatus(TrustCircleStatus.ACTIVE);

        return SitterTrustCircleDTO.fromEntity(trustCircleRepository.save(trustCircle));
    }

    @Transactional
    public void removeMember(Account account, UUID trustedSitterId) {
        Profile owner = getSitterProfile(account);
        List<SitterTrustCircle> members = trustCircleRepository.findByOwnerSitterIdAndStatus(owner.getId(), TrustCircleStatus.ACTIVE);
        
        members.stream()
                .filter(m -> m.getTrustedSitter().getId().equals(trustedSitterId))
                .findFirst()
                .ifPresent(trustCircleRepository::delete);
    }

    private Profile getSitterProfile(Account account) {
        return profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));
    }
}
