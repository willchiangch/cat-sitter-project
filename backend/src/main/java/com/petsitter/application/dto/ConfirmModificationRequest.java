package com.petsitter.application.dto;

/**
 * 飼主確認同意變更 (PRD-016 主流程 C / SD-016 §2.4)
 */
public record ConfirmModificationRequest(
    Integer agreedDiffAmount,
    Integer version
) {}
