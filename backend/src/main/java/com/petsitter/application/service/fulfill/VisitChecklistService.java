package com.petsitter.application.service.fulfill;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.dto.fulfill.FulfillRequests;
import com.petsitter.application.dto.fulfill.VisitResponses;
import com.petsitter.application.exception.FulfillException;
import com.petsitter.application.service.order.CurrentActor;
import com.petsitter.application.service.order.LockedOrder;
import com.petsitter.application.service.order.OrderClock;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.fulfill.VisitChecklistItem;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.VisitChecklistItemRepository;
import com.petsitter.domain.repository.fulfill.VisitRef;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SOP 檢核項 F9–F12（SD-FULFILL §3.4、PRD-028）。不寫 {@code order_logs}、不通知（PRD-028 BR-11）；
 * {@code created_by} / {@code updated_by} 由 Service 寫入 token 保母。
 */
@Service
@RequiredArgsConstructor
public class VisitChecklistService {

    private final FulfillGuard guard;
    private final FulfillTx tx;
    private final FulfillRequestValidator validator;
    private final IdempotencyService idempotencyService;
    private final VisitRepository visitRepository;
    private final VisitChecklistItemRepository checklistRepository;
    private final FulfillSettings settings;
    private final OrderClock clock;

    // ------------------------------------------------------------------ F9

    public VisitResponses.ItemCreated add(UUID orderId, UUID visitId, FulfillRequests.AddItem req) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        String title = validator.strippedString(req == null ? null : req.title(), "title", 1, settings.getChecklistTitleMax());
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        IdempotencyClaim claim = idempotencyService.claim(ctx);
        return tx.inLockedTx(orderId, locked -> {
            requireEditable(locked, visitId);
            List<VisitChecklistItem> items = checklistRepository.findByVisitIdOrderBySortOrderAsc(visitId);
            int max = settings.getChecklistMaxItems();
            if (items.size() >= max) {
                throw FulfillException.validation("items", Map.of("max", max));
            }
            int sort = items.isEmpty() ? 0 : items.get(items.size() - 1).getSortOrder() + 1;
            VisitChecklistItem item = checklistRepository.saveAndFlush(VisitChecklistItem.builder()
                    .visitId(visitId).title(title).sortOrder((short) sort).completed(false)
                    .createdSource(VisitChecklistItem.SOURCE_SITTER).createdBy(actor.userId()).updatedBy(actor.userId())
                    .build());
            VisitResponses.ItemCreated resp = new VisitResponses.ItemCreated(item.getId(), title, sort, false);
            idempotencyService.complete(claim, 201, resp);
            return resp;
        });
    }

    // ------------------------------------------------------------------ F10

    public VisitResponses.Checklist delete(UUID orderId, UUID visitId, UUID itemId) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        requireItemOf(itemId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        return tx.inLockedTx(orderId, locked -> {
            VisitChecklistItem target = checklistRepository.findById(itemId).filter(i -> i.getVisitId().equals(visitId))
                    .orElseThrow(FulfillException::notFound);
            requireEditable(locked, visitId);
            checklistRepository.delete(target);
            checklistRepository.flush();
            List<VisitChecklistItem> rest = checklistRepository.findByVisitIdOrderBySortOrderAsc(visitId);
            renumber(rest, actor.userId());
            return new VisitResponses.Checklist(visitId, rest.stream().map(i -> VisitReadService.item(i, true)).toList());
        });
    }

    // ------------------------------------------------------------------ F11

    public VisitResponses.Checklist reorder(UUID orderId, UUID visitId, FulfillRequests.Reorder req) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        List<UUID> ids = parseIds(req == null ? null : req.itemIds());
        // PRD-028 BR-8：id 清單列在第 6 步，以鎖外讀值比對；鎖後重驗
        requireSameSet(ids, checklistRepository.findIdsByVisitId(visitId));
        return tx.inLockedTx(orderId, locked -> {
            List<VisitChecklistItem> items = checklistRepository.findByVisitIdOrderBySortOrderAsc(visitId);
            requireSameSet(ids, items.stream().map(VisitChecklistItem::getId).toList());
            requireEditable(locked, visitId);
            Map<UUID, VisitChecklistItem> byId = new HashMap<>();
            items.forEach(i -> byId.put(i.getId(), i));
            List<VisitChecklistItem> ordered = new ArrayList<>();
            ids.forEach(id -> ordered.add(byId.get(id)));
            renumber(ordered, actor.userId());
            return new VisitResponses.Checklist(visitId, ordered.stream().map(i -> VisitReadService.item(i, true)).toList());
        });
    }

    // ------------------------------------------------------------------ F12

    public VisitResponses.ItemChecked check(UUID orderId, UUID visitId, UUID itemId, FulfillRequests.Check req) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        requireItemOf(itemId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        boolean checked = validator.bool(req == null ? null : req.checked(), "checked");
        return tx.inLockedTx(orderId, locked -> {
            OffsetDateTime now = clock.now();
            if (!VisitRules.RUNNING_ORDER.contains(locked.status())) {
                throw FulfillException.orderState(locked.status());
            }
            Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
            if (v.getStatus() != VisitStatus.IN_PROGRESS) {
                throw FulfillException.visitState(v.getStatus(), v.getCompletionSource());
            }
            VisitChecklistItem item = checklistRepository.findById(itemId).filter(i -> i.getVisitId().equals(visitId))
                    .orElseThrow(FulfillException::notFound);
            if (item.isCompleted() != checked) {
                item.setCompleted(checked);
                item.setCheckedAt(checked ? now : null);
                item.setUpdatedBy(actor.userId());
                checklistRepository.flush();
            }
            return new VisitResponses.ItemChecked(itemId, item.isCompleted(), item.getCheckedAt());
        });
    }

    // ------------------------------------------------------------------ 共用

    /** 新增 / 刪除 / 排序：訂單 CONFIRMED / IN_PROGRESS / DISPUTED → 行程 PENDING / IN_PROGRESS（§3.4）。 */
    private void requireEditable(LockedOrder locked, UUID visitId) {
        if (!VisitRules.ACTIVE_ORDER.contains(locked.status())) {
            throw FulfillException.orderState(locked.status());
        }
        Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
        if (v.getStatus() != VisitStatus.PENDING && v.getStatus() != VisitStatus.IN_PROGRESS) {
            throw FulfillException.visitState(v.getStatus(), v.getCompletionSource());
        }
    }

    /** G4：項目存在且屬於路徑行程（鎖前投影）。 */
    private void requireItemOf(UUID itemId, UUID visitId) {
        checklistRepository.findVisitIdById(itemId).filter(visitId::equals).orElseThrow(FulfillException::notFound);
    }

    /** sort_order 依序重編 0..n-1（{@code ux_checklist_sort} DEFERRABLE，交易結束才檢查）；有變動者寫 updated_by。 */
    private void renumber(List<VisitChecklistItem> ordered, UUID userId) {
        for (int i = 0; i < ordered.size(); i++) {
            VisitChecklistItem it = ordered.get(i);
            if (it.getSortOrder() != i) {
                it.setSortOrder((short) i);
                it.setUpdatedBy(userId);
            }
        }
        checklistRepository.flush();
    }

    private List<UUID> parseIds(JsonNode node) {
        if (!FulfillRequestValidator.present(node) || !node.isArray()) {
            throw FulfillException.validation("itemIds");
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode e : node) {
            if (e == null || !e.isString()) {
                throw FulfillException.validation("itemIds");
            }
            try {
                UUID id = UUID.fromString(e.stringValue());
                if (!id.toString().equalsIgnoreCase(e.stringValue())) {
                    throw FulfillException.validation("itemIds");
                }
                ids.add(id);
            } catch (IllegalArgumentException ex) {
                throw FulfillException.validation("itemIds");
            }
        }
        return ids;
    }

    private static void requireSameSet(List<UUID> requested, List<UUID> current) {
        Set<UUID> unique = new HashSet<>(requested);
        if (unique.size() != requested.size() || !unique.equals(new HashSet<>(current))) {
            throw FulfillException.validation("itemIds");
        }
    }
}
