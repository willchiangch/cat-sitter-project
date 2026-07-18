package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * PRD-019：以 ID 或 Email 搜尋保母的結果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SitterSearchResultDto {
    private UUID sitterId;
    private String displayName;
    private String email;
}
