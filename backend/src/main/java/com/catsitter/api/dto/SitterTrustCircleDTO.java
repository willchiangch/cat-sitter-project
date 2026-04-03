package com.catsitter.api.dto;

import com.catsitter.api.entity.SitterTrustCircle;
import java.time.Instant;
import java.util.UUID;

public class SitterTrustCircleDTO {
    private UUID id;
    private ProfileMiniDTO trustedSitter;
    private String status;
    private Instant createdAt;

    public SitterTrustCircleDTO() {}

    public static SitterTrustCircleDTO fromEntity(SitterTrustCircle entity) {
        SitterTrustCircleDTO dto = new SitterTrustCircleDTO();
        dto.setId(entity.getId());
        dto.setTrustedSitter(ProfileMiniDTO.fromEntity(entity.getTrustedSitter()));
        dto.setStatus(entity.getStatus() != null ? entity.getStatus().name() : null);
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public ProfileMiniDTO getTrustedSitter() { return trustedSitter; }
    public void setTrustedSitter(ProfileMiniDTO trustedSitter) { this.trustedSitter = trustedSitter; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
