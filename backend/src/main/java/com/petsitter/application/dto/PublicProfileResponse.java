package com.petsitter.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicProfileResponse {
    // trustScore 依 PRD-001/SD-001 為內部極高隱私指標，不得對外揭露，此 DTO 故意不宣告該欄位
    private boolean gated;
    private UUID sitterId;
    private String displayName;
    private String avatarUrl;
    private String bio;
    private List<String> tags;
    private List<ServiceAreaDto> serviceAreas;
    @JsonProperty("isOpen")
    private boolean isOpen;
    private String kycStatus;
    private Integer version;
    private Boolean isVisible;
}
