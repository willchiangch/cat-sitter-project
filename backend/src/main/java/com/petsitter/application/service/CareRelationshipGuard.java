package com.petsitter.application.service;

import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.infrastructure.security.TokenContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SD-021 §1.3 照護記事本 / 照護媒體的關係檢查（fix-batch12 J1）。
 * <p>
 * 路徑 {sitterId}/{ownerId} 除了「其中一方必須是 token 本人」之外，雙方之間還必須「有過任何狀態的訂單」
 * （含 PENDING、CANCELLED 等；e2e 清理用的軟刪除訂單不算）。過去只比對 sitterId == token，
 * 保母可以對任何 ownerId 建立記事本、上傳媒體，該飼主讀取時就會看到。
 * 不符合一律拋 AccessDeniedException → 403。
 */
@Component
@RequiredArgsConstructor
public class CareRelationshipGuard {

    private final OrderRepository orderRepository;

    /** 讀取：token 必須是 sitterId 或 ownerId，且雙方有過訂單 */
    public void requireReadable(UUID sitterId, UUID ownerId) {
        UUID userId = TokenContext.getUserId();
        if (!sitterId.equals(userId) && !ownerId.equals(userId)) {
            throw new AccessDeniedException("權限不足");
        }
        requireOrderRelationship(sitterId, ownerId);
    }

    /** 寫入：token 必須是 sitterId（飼主唯讀，AC-5），且雙方有過訂單 */
    public void requireWritable(UUID sitterId, UUID ownerId) {
        if (!sitterId.equals(TokenContext.getUserId())) {
            throw new AccessDeniedException("權限不足");
        }
        requireOrderRelationship(sitterId, ownerId);
    }

    private void requireOrderRelationship(UUID sitterId, UUID ownerId) {
        if (!orderRepository.existsOrderBetween(sitterId, ownerId)) {
            throw new AccessDeniedException("您與此對象之間沒有任何訂單，無法存取照護記事本與媒體");
        }
    }
}
