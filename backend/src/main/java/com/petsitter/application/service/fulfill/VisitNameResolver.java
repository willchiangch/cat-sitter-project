package com.petsitter.application.service.fulfill;

import com.petsitter.domain.model.fulfill.Visit;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 行程顯示名稱（SD-FULFILL §4.3、PRD-008 BR-14）：{@code custom_name} → O2 報價快照方案名稱（{@code planTitles[plan_id]}）
 * → {@code booking_plan_title}（報價前）；{@code slots_in_day > 1} 時加「 第 n 次」。讀取時組成、不落地。
 */
@Component
public class VisitNameResolver {

    public String name(Visit v, Map<UUID, String> planTitles) {
        if (v.getCustomName() != null) {
            return v.getCustomName();
        }
        return defaultName(v, planTitles);
    }

    public String defaultName(Visit v, Map<UUID, String> planTitles) {
        String title = planTitles == null ? null : planTitles.get(v.getPlanId());
        if (title == null) {
            title = v.getBookingPlanTitle();
        }
        return v.getSlotsInDay() > 1 ? title + " 第 " + v.getSequenceInDay() + " 次" : title;
    }
}
