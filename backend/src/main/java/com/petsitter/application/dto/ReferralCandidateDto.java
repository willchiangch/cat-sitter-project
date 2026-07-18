package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * PRD-010 主流程 B：發起轉介時可選的信任圈候選名單，已依黑名單前置過濾
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralCandidateDto {
    private UUID sitterId;
    private String displayName;
    private boolean available; // false 代表對方停權/休息中，前端應標註但仍可選 (依 PRD-010 例外處理表可標註或隱藏，這裡選擇標註)
}
