package com.petsitter.domain.model.order;

import java.util.List;
import java.util.UUID;

/**
 * 變更內容（{@code requested_content} / {@code quoted_content} JSONB，SD-ORDER §3.3「內容儲存」）：
 * 減少的行程 id + 新增項目（日期 YYYY-MM-DD、方案、每日次數、毛孩）。
 */
public record ModificationContent(List<UUID> removeVisitIds, List<AddItem> addItems) {

    public ModificationContent {
        removeVisitIds = removeVisitIds == null ? List.of() : List.copyOf(removeVisitIds);
        addItems = addItems == null ? List.of() : List.copyOf(addItems);
    }

    public static ModificationContent empty() {
        return new ModificationContent(List.of(), List.of());
    }

    public boolean isEmpty() {
        return removeVisitIds.isEmpty() && addItems.isEmpty();
    }

    public record AddItem(String date, UUID planId, int dailyCount, List<UUID> petIds) {
        public AddItem {
            petIds = petIds == null ? List.of() : List.copyOf(petIds);
        }
    }
}
