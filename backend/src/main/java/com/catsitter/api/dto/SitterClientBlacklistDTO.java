package com.catsitter.api.dto;

import com.catsitter.api.entity.SitterClientBlacklist;
import java.time.Instant;
import java.util.UUID;

public class SitterClientBlacklistDTO {
    private UUID id;
    private ProfileMiniDTO clientProfile;
    private String reason;
    private Instant createdAt;

    public static SitterClientBlacklistDTO fromEntity(SitterClientBlacklist entity) {
        SitterClientBlacklistDTO dto = new SitterClientBlacklistDTO();
        dto.id = entity.getId();
        dto.clientProfile = ProfileMiniDTO.fromEntity(entity.getClientProfile());
        dto.reason = entity.getReason();
        dto.createdAt = entity.getCreatedAt();
        return dto;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public ProfileMiniDTO getClientProfile() { return clientProfile; }
    public void setClientProfile(ProfileMiniDTO clientProfile) { this.clientProfile = clientProfile; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
