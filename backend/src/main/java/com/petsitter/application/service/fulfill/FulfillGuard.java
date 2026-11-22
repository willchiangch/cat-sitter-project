package com.petsitter.application.service.fulfill;

import com.petsitter.application.exception.FulfillException;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.service.order.CurrentActor;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.repository.fulfill.VisitRef;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * 鎖前歸屬判斷（SD-FULFILL §2.5 第 1 點、§6.1）：G4（404）→ G4a（混合角色端點 ROLE_MISMATCH）→ G5（FORBIDDEN）。
 * 只用投影查詢，不載入行程 / 日誌 / 檢核項 / 槽位實體；不在交易內。
 */
@Component
@RequiredArgsConstructor
public class FulfillGuard {

    private final VisitRepository visitRepository;
    private final OrderQueryService orderQueryService;

    /** G4：行程存在且屬於路徑訂單。 */
    public VisitRef visit(UUID orderId, UUID visitId) {
        return visitRepository.findRef(visitId).filter(r -> r.orderId().equals(orderId))
                .orElseThrow(FulfillException::notFound);
    }

    /** G4：訂單存在（F1）。 */
    public OrderQueryService.OrderParticipants order(UUID orderId) {
        try {
            return orderQueryService.getParticipants(orderId);
        } catch (OrderException e) {
            throw FulfillException.notFound();
        }
    }

    /** G5（單一 SITTER 端點；G3 已由 {@code @PreAuthorize} 擋角色）。 */
    public void requireSitter(UUID sitterId, CurrentActor actor) {
        if (!sitterId.equals(actor.userId())) {
            throw FulfillException.forbidden();
        }
    }

    /**
     * 混合角色 GET（F1、F2、F8、F13）：ADMIN 依 {@code adminReadable}；當事人但 currentRole 不是其在此訂單的角色 →
     * ROLE_MISMATCH（G4a）；非當事人 → FORBIDDEN。回傳讀取者角色。
     */
    public ActorRole readerRole(UUID orderId, UUID ownerId, UUID sitterId, CurrentActor actor) {
        if (actor.isAdminRole()) {
            OrderQueryService.OrderContextView ctx = orderQueryService.getOrderContexts(Set.of(orderId)).get(orderId);
            if (ctx == null || !ctx.adminReadable()) {
                throw FulfillException.forbidden();
            }
            return ActorRole.ADMIN;
        }
        boolean isOwner = ownerId.equals(actor.userId());
        boolean isSitter = sitterId.equals(actor.userId());
        if (!isOwner && !isSitter) {
            throw FulfillException.forbidden();
        }
        if (isOwner && actor.isOwnerRole()) {
            return ActorRole.OWNER;
        }
        if (isSitter && actor.isSitterRole()) {
            return ActorRole.SITTER;
        }
        throw FulfillException.roleMismatch();
    }
}
