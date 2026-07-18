package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * PRD-019：我的最愛保母清單項目
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteSitterDto {
    private UUID sitterId;
    private String displayName;
    private String avatarUrl;
    private List<String> tags;
    private boolean removed; // 帳號已被註銷/軟刪除
    private boolean hidden; // isOpen=false 或 isVisible=false，前端顯示「休息中/隱藏中」
}
