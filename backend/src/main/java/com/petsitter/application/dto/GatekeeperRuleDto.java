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
public class GatekeeperRuleDto {
    private UUID id;
    private UUID sitterId;
    private String ruleType;
    private String scopeType;
    private UUID planId;
    private UUID targetUserId;
    private String targetEmail; // 遮蔽後的 Email
}
