package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.fulfill.MediaFiles.jpeg;

@DisplayName("SD-FULFILL VisitCareLogDraftIntegrationTest（F14）")
class VisitCareLogDraftIntegrationTest extends CareLogTestBase {

    @Test
    @DisplayName("PRD-022 AC-1.1 開放條件：PENDING / CLOSED / CANCELLED → 409 VISIT_STATE_CONFLICT；CONFIRMED 訂單 → ORDER_STATE_CONFLICT；IN_PROGRESS 可暫存")
    void openConditions() throws Exception {
        UUID orderId = runningOrder("PRO", 4);
        List<UUID> vs = visits(orderId);
        draft(orderId, vs.get(1), sitterToken(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentVisitStatus").value("PENDING"));
        OffsetDateTime t = OffsetDateTime.now(clock);
        forceClosed(vs.get(2), t);
        forceCancelled(vs.get(3), t);
        draft(orderId, vs.get(2), sitterToken(), "x").andExpect(jsonPath("$.payload.currentVisitStatus").value("CLOSED_BY_SYSTEM"));
        draft(orderId, vs.get(3), sitterToken(), "x").andExpect(jsonPath("$.payload.currentVisitStatus").value("CANCELLED"));
        assertThat(count("SELECT count(*) FROM visit_service_reports")).isZero();
        draft(orderId, vs.get(0), sitterToken(), "ok").andExpect(status().isOk());
        UUID confirmed = confirmedOrder(today().plusDays(2));
        draft(confirmed, visits(confirmed).get(0), sitterToken(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentStatus").value("CONFIRMED"));
    }

    @Test
    @DisplayName("PRD-022 AC-1.2 首次暫存建 DRAFT（created_by / updated_by = 保母）、整份覆寫仍 1 份；不寫 log、不通知")
    void firstDraftAndOverwrite() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        int logs = logActions(orderId).size();
        draft(orderId, v, sitterToken(), "乾乾吃完半碗").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.text").value("乾乾吃完半碗"));
        draft(orderId, v, sitterToken(), "乾乾吃完一碗").andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM visit_service_reports WHERE visit_id = ?", v)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT content, status, created_by, updated_by FROM visit_service_reports WHERE visit_id = ?", v))
                .containsEntry("content", "乾乾吃完一碗").containsEntry("status", "DRAFT").containsEntry("created_by", sitter).containsEntry("updated_by", sitter);
        assertThat(logActions(orderId)).hasSize(logs);
        assertThat(count("SELECT count(*) FROM notifications WHERE type = 'CARE_LOG_SUBMITTED'")).isZero();
    }

    @Test
    @DisplayName("PRD-022 AC-1.2 情境 3 首次暫存與首次上傳確認同時送出（兩條執行緒）：皆成功、只 1 份 DRAFT 且含該照片")
    void firstDraftVsFirstUploadRace() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        byte[] img = jpeg(5000);
        UUID uploadId = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        int[] codes = race(() -> draft(orderId, v, sitterToken(), "同時"), () -> completeUpload(orderId, v, uploadId, sitterToken()));
        assertThat(codes).containsExactlyInAnyOrder(200, 201);
        assertThat(count("SELECT count(*) FROM visit_service_reports WHERE visit_id = ?", v)).isEqualTo(1);
        assertThat(mediaCount(v)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM visit_service_reports WHERE visit_id = ?", String.class, v)).isEqualTo("同時");
    }

    @Test
    @DisplayName("PRD-022 AC-1.3 文字以 code point 計：1001 字 400、1000 字（含 emoji）200；非字串 400")
    void textLength() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        draft(orderId, v, sitterToken(), "字".repeat(1001)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("text"));
        draft(orderId, v, sitterToken(), "😺".repeat(1000)).andExpect(status().isOk());
        draft(orderId, v, sitterToken(), java.util.Map.of("text", 5)).andExpect(status().isBadRequest());
        draft(orderId, v, sitterToken(), "").andExpect(status().isOk()).andExpect(jsonPath("$.text").value(""));
    }
}
