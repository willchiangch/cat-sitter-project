package com.petsitter.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 方案查詢（SD-ORDER §2.4.2；SD-SUPPLY 實作，過渡）。 */
public interface ServicePlanQuery {

    /** 指定 id 的方案（不存在者不回；他人方案照回，由呼叫端判斷）。 */
    List<PlanView> findForBooking(UUID sitterId, Set<UUID> planIds);

    /** 該保母所有未刪除方案（E31 方案清單；過渡補充方法，SD-SUPPLY 重建時接手）。 */
    List<PlanView> listForSitter(UUID sitterId);

    /**
     * 方案預設任務（SD-FULFILL §2.4、§4.8；PRD-028 BR-2）：一次查詢；方案已刪除或下架 → 空清單；JSONB 中的非字串 / null
     * 元素略過、不拋例外。回傳 map 含每個傳入的 planId。
     */
    java.util.Map<UUID, List<String>> findDefaultTasks(Set<UUID> planIds);

    record PlanView(UUID planId, UUID sitterId, String title, int unitPrice, boolean active, boolean deleted,
                    LocalDate bookableFrom, LocalDate bookableTo, List<String> petTypes, int sortOrder) {

        /** 方案可預約區間（只在 PRO / ULTIMATE 且未鎖定時套用，由呼叫端決定是否檢查）。 */
        public boolean inRange(LocalDate date) {
            if (bookableFrom != null && date.isBefore(bookableFrom)) {
                return false;
            }
            return bookableTo == null || !date.isAfter(bookableTo);
        }
    }
}
