package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 內部信用指標管理清單項目 (PRD-020 主流程 E)，僅供管理後台使用，飼主/保母前台不可查看
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SitterTrustScoreDto {
    private UUID sitterId;
    private String fullName;
    private String email;
    private int trustScore;
    private boolean highRisk;
    private String kycStatus;
}
