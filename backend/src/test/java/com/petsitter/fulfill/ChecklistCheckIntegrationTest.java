package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL ChecklistCheckIntegrationTest（F12）")
class ChecklistCheckIntegrationTest extends FulfillIntegrationTestBase {

    @Test
    @DisplayName("PRD-028 AC-2.1 打勾寫 checked_at、取消清空、updated_by；字串 \"true\" → 400；結束行程不看打勾；DONE 後 409 VISIT_STATE_CONFLICT")
    void checkAndUncheck() throws Exception {
        defaultTasks(planA, "[\"I1\",\"I2\",\"I3\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        UUID v = visits(orderId).get(0);
        List<UUID> items = itemIds(v);
        freeze(taipei(d, 9, 0));
        start(orderId, v, sitterToken(), key()).andExpect(status().isOk());
        OffsetDateTime at = taipei(d, 9, 30);
        freeze(at);
        check(orderId, v, items.get(0), sitterToken(), true).andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompleted").value(true)).andExpect(jsonPath("$.checkedAt").value(iso(at)));
        assertThat(jdbc.queryForObject("SELECT updated_by FROM visit_checklist_items WHERE id = ?", UUID.class, items.get(0))).isEqualTo(sitter);
        freeze(at.plusMinutes(5));
        check(orderId, v, items.get(0), sitterToken(), true).andExpect(jsonPath("$.checkedAt").value(iso(at)));
        check(orderId, v, items.get(0), sitterToken(), false).andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompleted").value(false)).andExpect(jsonPath("$.checkedAt").doesNotExist());
        check(orderId, v, items.get(0), sitterToken(), "true").andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("checked"));
        check(orderId, v, items.get(1), sitterToken(), true).andExpect(status().isOk());
        complete(orderId, v, sitterToken(), key()).andExpect(status().isOk());
        check(orderId, v, items.get(1), sitterToken(), false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentVisitStatus").value("DONE"));
        assertThat(jdbc.queryForObject("SELECT is_completed FROM visit_checklist_items WHERE id = ?", Boolean.class, items.get(1))).isTrue();
    }

    @Test
    @DisplayName("PRD-028 AC-2.2 情境 1–2、4：CONFIRMED → ORDER_STATE_CONFLICT；IN_PROGRESS 訂單 PENDING 行程 → VISIT_STATE_CONFLICT；鎖逾時 → 409 ORDER_BUSY + Retry-After")
    void checkWindowAndBusy() throws Exception {
        defaultTasks(planA, "[\"I1\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        check(orderId, vs.get(0), itemIds(vs.get(0)).get(0), sitterToken(), true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentStatus").value("CONFIRMED"));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        check(orderId, vs.get(0), itemIds(vs.get(0)).get(0), sitterToken(), true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentVisitStatus").value("PENDING"));
        forceVisit(vs.get(1), "IN_PROGRESS", taipei(d, 9, 0), null, null);
        var hold = holdOrderLock(orderId, java.time.Duration.ofSeconds(5));
        check(orderId, vs.get(1), itemIds(vs.get(1)).get(0), sitterToken(), true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_BUSY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "1"));
        hold.join();
        assertThat(count("SELECT count(*) FROM visit_checklist_items WHERE visit_id = ? AND is_completed", vs.get(1))).isZero();
    }
}
