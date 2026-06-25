package com.petsitter.application.dto;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicProfileResponse {
    private boolean gated;
    private UUID sitterId;
    private String displayName;
    private String avatarUrl;
    private String bio;
    private List<String> tags;
    private List<ServiceAreaDto> serviceAreas;
    private boolean isOpen;
    private String kycStatus;
    private int trustScore;
    private Integer version;
    private Boolean isVisible;
}
