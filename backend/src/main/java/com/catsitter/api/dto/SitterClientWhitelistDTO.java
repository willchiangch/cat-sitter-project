package com.catsitter.api.dto;

import com.catsitter.api.entity.SitterClientWhitelist;
import java.time.Instant;
import java.util.UUID;

public class SitterClientWhitelistDTO {
    private UUID id;
    private ProfileMiniDTO clientProfile;
    private String notes;
    private boolean skipQuestionnaire;
    private Instant createdAt;

    public SitterClientWhitelistDTO() {}

    public static SitterClientWhitelistDTO fromEntity(SitterClientWhitelist entity) {
        SitterClientWhitelistDTO dto = new SitterClientWhitelistDTO();
        dto.setId(entity.getId());
        dto.setClientProfile(ProfileMiniDTO.fromEntity(entity.getClientProfile()));
        dto.setNotes(entity.getNotes());
        dto.setSkipQuestionnaire(entity.getSkipQuestionnaire() != null ? entity.getSkipQuestionnaire() : false);
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public ProfileMiniDTO getClientProfile() { return clientProfile; }
    public void setClientProfile(ProfileMiniDTO clientProfile) { this.clientProfile = clientProfile; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isSkipQuestionnaire() { return skipQuestionnaire; }
    public void setSkipQuestionnaire(boolean skipQuestionnaire) { this.skipQuestionnaire = skipQuestionnaire; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
