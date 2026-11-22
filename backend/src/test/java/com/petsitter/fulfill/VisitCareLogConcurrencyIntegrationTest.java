package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.fulfill.MediaFiles.jpeg;

@DisplayName("SD-FULFILL VisitCareLogConcurrencyIntegrationTest（兩條執行緒真打）")
class VisitCareLogConcurrencyIntegrationTest extends VisitCareLogTestBase {

    @Test
    @DisplayName("PRD-022 AC-2.7 BASIC 已 4 張、兩個 key 各申請 1 張後同時確認：恰 1 筆 201、另 1 筆 422 QUOTA_EXCEEDED（used 5）、被拒者物件刪除")
    void quotaRace() throws Exception {
        UUID orderId = runningOrder("BASIC", 1);
        UUID v = visits(orderId).get(0);
        for (int i = 0; i < 4; i++) {
            uploadJpeg(orderId, v, 1000 + i).andExpect(status().isCreated());
        }
        byte[] a = jpeg(5000);
        byte[] b = jpeg(5001);
        UUID ua = applyAndPut(orderId, v, a, a, "IMAGE", "image/jpeg", null, null, key());
        UUID ub = applyAndPut(orderId, v, b, b, "IMAGE", "image/jpeg", null, null, key());
        int[] codes = race(() -> completeUpload(orderId, v, ua, sitterToken()), () -> completeUpload(orderId, v, ub, sitterToken()));
        assertThat(codes).containsExactlyInAnyOrder(201, 422);
        assertThat(mediaCount(v)).isEqualTo(5);
        UUID loser = codes[0] == 422 ? ua : ub;
        assertThat(slotStatus(loser)).isEqualTo("FAILED");
        assertThat(storage.exists(objectKey(loser))).isFalse();
        assertThat(jdbc.queryForObject("SELECT failure_payload->>'used' FROM care_log_media_uploads WHERE id = ?", String.class, loser)).isEqualTo("5");
    }

    @Test
    @DisplayName("PRD-022 AC-2.7（§3.3）同 uploadId 兩條執行緒同時 F18：恰 1 筆媒體、兩者皆 201 同一媒體、物件仍存在")
    void sameUploadRace() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        byte[] img = jpeg(4000);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        String[] bodies = new String[2];
        int[] codes = race(() -> {
            var r = completeUpload(orderId, v, u, sitterToken());
            bodies[0] = r.andReturn().getResponse().getContentAsString();
            return r;
        }, () -> {
            var r = completeUpload(orderId, v, u, sitterToken());
            bodies[1] = r.andReturn().getResponse().getContentAsString();
            return r;
        });
        assertThat(codes).containsExactly(201, 201);
        assertThat(json.readTree(bodies[0]).get("mediaId")).isEqualTo(json.readTree(bodies[1]).get("mediaId"));
        assertThat(mediaCount(v)).isEqualTo(1);
        assertThat(storage.exists(objectKey(u))).isTrue();
        assertThat(slotStatus(u)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("PRD-022 AC-3.7 情境 1–2 送出 vs 調解結案 / 飼主確認結案依取得鎖先後：只有先者成功、日誌狀態一致")
    void submitVsCompletion() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        draft(orderId, v, sitterToken(), "草稿").andExpect(status().isOk());
        done(v, OffsetDateTime.now(clock).minusMinutes(1));
        jdbc.update("UPDATE orders SET completion_basis_at = now() + interval '1 day' WHERE id = ?", orderId);
        int[] codes = race(() -> submit(orderId, v, sitterToken(), key(), "送出"),
                () -> mvc.perform(postJson("/api/orders/{o}/complete", ownerToken(), key(), Map.of(), orderId)));
        String log = jdbc.queryForObject("SELECT status FROM visit_service_reports WHERE visit_id = ?", String.class, v);
        if (codes[1] == 200) {
            assertThat(codes[0]).isEqualTo(200);
            assertThat(log).isEqualTo("SUBMITTED");
            assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        } else {
            assertThat(codes[1]).isEqualTo(409);
            assertThat(codes[0]).isEqualTo(200);
            assertThat(log).isEqualTo("SUBMITTED");
            assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");
        }
    }

    @Test
    @DisplayName("PRD-022 AC-3.7 情境 3–4 鎖逾時：暫存 409 ORDER_BUSY + Retry-After；F18 ORDER_BUSY 物件保留、槽位 PENDING、無媒體，鎖釋放後同槽位直接重打 201")
    void lockTimeout() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        byte[] img = jpeg(4000);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        var hold = holdOrderLock(orderId, Duration.ofSeconds(8));
        draft(orderId, v, sitterToken(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_BUSY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "1"));
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_BUSY"));
        assertThat(slotStatus(u)).isEqualTo("PENDING");
        assertThat(storage.exists(objectKey(u))).isTrue();
        assertThat(mediaCount(v)).isZero();
        hold.join();
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated());
        assertThat(mediaCount(v)).isEqualTo(1);
    }
}
