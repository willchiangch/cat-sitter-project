package com.petsitter.application.dto;

/**
 * 保母審核變更並提供差額報價 (PRD-016 主流程 B / SD-016 §2.2)
 */
public record ModificationQuoteRequest(
    Integer newTotalAmount,
    Integer version
) {}
