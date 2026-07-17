package com.petsitter.application.dto;

/**
 * 管理員爭議裁決請求 (SD-009 Scenario 2)
 */
public record AdminResolveRequest(
    Integer finalAmount,
    String receiptUrl,
    String reason,
    String adminPassword
) {}
