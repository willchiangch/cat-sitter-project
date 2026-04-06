package com.catsitter.api.service;

import com.catsitter.api.dto.ProfileMiniDTO;
import com.catsitter.api.dto.SitterClientBlacklistDTO;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.SitterClientBlacklist;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.SitterClientBlacklistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BlacklistService {

    private final SitterClientBlacklistRepository blacklistRepository;
    private final ProfileRepository profileRepository;

    public BlacklistService(SitterClientBlacklistRepository blacklistRepository, ProfileRepository profileRepository) {
        this.blacklistRepository = blacklistRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public List<SitterClientBlacklistDTO> getBlacklistedClients(Account sitterAccount) {
        Profile sitter = getSitterProfile(sitterAccount);
        return blacklistRepository.findBySitterProfileId(sitter.getId()).stream()
                .map(SitterClientBlacklistDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public SitterClientBlacklistDTO addToBlacklist(Account sitterAccount, UUID clientId) {
        Profile sitter = getSitterProfile(sitterAccount);
        Profile client = profileRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client profile not found: " + clientId));

        SitterClientBlacklist entry = blacklistRepository
                .findBySitterProfileIdAndClientProfileId(sitter.getId(), clientId)
                .orElseGet(() -> {
                    SitterClientBlacklist e = new SitterClientBlacklist();
                    e.setSitterProfile(sitter);
                    e.setClientProfile(client);
                    return e;
                });
        blacklistRepository.save(entry);
        return SitterClientBlacklistDTO.fromEntity(entry);
    }

    @Transactional
    public void removeFromBlacklist(Account sitterAccount, UUID clientId) {
        Profile sitter = getSitterProfile(sitterAccount);
        blacklistRepository.findBySitterProfileIdAndClientProfileId(sitter.getId(), clientId)
                .ifPresent(blacklistRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<ProfileMiniDTO> searchClients(Account sitterAccount, String query) {
        if (query == null || query.isBlank()) return List.of();
        return profileRepository.findByRoleTypeAndNameContainingIgnoreCase(RoleType.CLIENT, query)
                .stream()
                .map(ProfileMiniDTO::fromEntity)
                .collect(Collectors.toList());
    }

    private Profile getSitterProfile(Account account) {
        return profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));
    }
}
