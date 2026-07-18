package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrustRelationshipDto {
    private UUID relationshipId;
    private UUID sitterId; // 對方 (信任圈成員 or 請求發起/接收方) 的使用者 ID
    private String displayName;
    private String email;
    private String status; // PENDING, ACCEPTED
}
