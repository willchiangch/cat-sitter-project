package com.catsitter.api.service;

import com.catsitter.api.dto.SitterClientWhitelistDTO;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.SitterClientWhitelist;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.SitterClientWhitelistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class WhitelistService {

    private final SitterClientWhitelistRepository whitelistRepository;
    private final ProfileRepository profileRepository;

    public WhitelistService(SitterClientWhitelistRepository whitelistRepository, ProfileRepository profileRepository) {
        this.whitelistRepository = whitelistRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public List<SitterClientWhitelistDTO> getWhitelistedClients(Account sitterAccount) {
        return profileRepository.findByAccountIdAndRoleType(sitterAccount.getId(), RoleType.SITTER)
                .map(sitter -> whitelistRepository.findBySitterProfileId(sitter.getId())
                        .stream()
                        .map(SitterClientWhitelistDTO::fromEntity)
                        .toList())
                .orElse(java.util.Collections.emptyList());
    }

    @Transactional
    public SitterClientWhitelistDTO toggleSkipQuestionnaire(Account sitterAccount, UUID clientId, Boolean skip) {
        Profile sitter = getSitterProfile(sitterAccount);
        Profile client = profileRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client profile not found"));

        SitterClientWhitelist whitelist = whitelistRepository.findBySitterProfileIdAndClientProfileId(sitter.getId(), client.getId())
                .orElseGet(() -> {
                    SitterClientWhitelist newWl = new SitterClientWhitelist();
                    newWl.setSitterProfile(sitter);
                    newWl.setClientProfile(client);
                    return newWl;
                });

        whitelist.setSkipQuestionnaire(skip);
        return SitterClientWhitelistDTO.fromEntity(whitelistRepository.save(whitelist));
    }

    @Transactional
    public void removeFromWhitelist(Account sitterAccount, UUID clientId) {
        Profile sitter = getSitterProfile(sitterAccount);
        whitelistRepository.findBySitterProfileIdAndClientProfileId(sitter.getId(), clientId)
                .ifPresent(whitelistRepository::delete);
    }

    private Profile getSitterProfile(Account account) {
        return profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));
    }
}
