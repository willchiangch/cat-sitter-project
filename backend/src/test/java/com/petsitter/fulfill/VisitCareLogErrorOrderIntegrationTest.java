package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.fulfill.MediaFiles.gif;
import static testsupport.fulfill.MediaFiles.jpeg;

@DisplayName("SD-FULFILL VisitCareLogErrorOrderIntegrationTest（判斷順序 §6.3）")
class VisitCareLogErrorOrderIntegrationTest extends VisitCareLogTestBase {

    @Test
    @DisplayName("PRD-022 AC-3.8 唯一結果五例：SUBMITTED 且逾期 → CARE_LOG_STATE_CONFLICT、1001 字 → 400、他人 → 403；逾期且額度滿 → DEADLINE_PASSED；逾期 + 101 字說明 → 400；COMPLETED + 逾期 → ORDER_STATE_CONFLICT；2 MB GIF → TYPE_UNSUPPORTED（格式先於大小）")
    void errorOrder() throws Exception {
        UUID orderId = runningOrder("BASIC", 2);
        List<UUID> vs = visits(orderId);
        OffsetDateTime completed = OffsetDateTime.now(clock).minusMinutes(5);
        forceVisit(vs.get(1), "IN_PROGRESS", completed.minusHours(1), null, null);
        for (int i = 0; i < 5; i++) {
            uploadJpeg(orderId, vs.get(1), 1000 + i).andExpect(status().isCreated());
        }
        requestUpload(orderId, vs.get(1), sitterToken(), key(), file(gif(2_000_000), "IMAGE", "image/gif", null), null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CARE_LOG_MEDIA_TYPE_UNSUPPORTED"));
        done(vs.get(0), completed);
        done(vs.get(1), completed);
        submit(orderId, vs.get(0), sitterToken(), key(), "ok").andExpect(status().isOk());
        freeze(completed.plusHours(25));
        draft(orderId, vs.get(0), sitterToken(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARE_LOG_STATE_CONFLICT"));
        draft(orderId, vs.get(0), sitterToken(), "字".repeat(1001)).andExpect(status().isBadRequest());
        draft(orderId, vs.get(0), strangerSitterToken(), "x").andExpect(status().isForbidden());
        int objects = storage.objectCount();
        requestUpload(orderId, vs.get(1), sitterToken(), key(), file(jpeg(500), "IMAGE", "image/jpeg", null), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEADLINE_PASSED"));
        requestUpload(orderId, vs.get(1), sitterToken(), key(), file(jpeg(500), "IMAGE", "image/jpeg", null), "字".repeat(101))
                .andExpect(status().isBadRequest());
        assertThat(storage.objectCount()).isEqualTo(objects);
        setOrderStatus(orderId, "COMPLETED");
        draft(orderId, vs.get(1), sitterToken(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
    }
}
