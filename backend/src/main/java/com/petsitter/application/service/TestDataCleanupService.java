package com.petsitter.application.service;

import com.petsitter.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 清理 E2E 種子測試帳號累積的訂單資料，避免 Supabase 持久化導致清單頁越跑越髒
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestDataCleanupService {

    private static final List<String> SEED_TEST_EMAILS = List.of(
            "sitter@test.com",
            "owner@test.com"
    );

    private final OrderRepository orderRepository;

    @Transactional
    public int cleanupSeedTestOrders() {
        int updated = orderRepository.softDeleteByPartyEmails(SEED_TEST_EMAILS);
        log.info("[TestDataCleanupService] Soft-deleted {} seed test orders", updated);
        return updated;
    }
}
