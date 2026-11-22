package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E5 評估頁（PRD-006 AC-1.1–1.5、SD-ORDER §10.3 EvaluationQueryIntegrationTest）。 */
@DisplayName("E5 EvaluationQueryIntegrationTest")
class EvaluationQueryIntegrationTest extends OrderIntegrationTestBase {

    private JsonNode eval(UUID orderId) throws Exception {
        return body(mvc.perform(getAs("/api/orders/{id}/evaluation", sitterToken(), orderId)).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("PRD-006 AC-1.1 評估頁內容：群組、日期、毛孩（已刪除者仍列並標示 deleted）、baseAmount、reviewExpiresAt")
    void content() throws Exception {
        UUID orderId = book(today().plusDays(3), today().plusDays(4));
        jdbc.update("UPDATE pets SET is_deleted = true WHERE id = ?", petA);
        JsonNode r = eval(orderId);
        assertThat(r.get("baseAmount").asInt()).isEqualTo(1000);
        assertThat(r.get("groups").get(0).get("dates").size()).isEqualTo(2);
        JsonNode pet = r.get("groups").get(0).get("pets").get(0);
        assertThat(pet.get("petId").asString()).isEqualTo(petA.toString());
        assertThat(pet.get("deleted").asBoolean()).isTrue();
        assertThat(r.get("reviewExpiresAt")).isNotNull();
        assertThat(r.get("serverTime")).isNotNull();
    }

    private UUID question(String text, String type, String options, boolean required, int sort) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sitter_questions (id, sitter_id, question_text, answer_type, options, required, is_active, sort_order) "
                + "VALUES (?, ?, ?, ?, ?::jsonb, ?, true, ?)", id, sitter, text, type, options, required, sort);
        return id;
    }

    private static Map<String, Object> answer(UUID q, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put("questionId", q.toString());
        m.put("value", value);
        return m;
    }

    @Test
    @DisplayName("PRD-006 AC-1.2 問卷答案快照：依題目順序回送單時的題目文字 / 題型 / 答案；之後改題目文字、停用題目、改題型，E5 仍回送單當下內容（未答 JSON null 不輸出）")
    void answersSnapshotUnaffectedByQuestionEdits() throws Exception {
        UUID q1 = question("是否有其他寵物", "RADIO", "[\"是\",\"否\"]", true, 1);
        UUID q2 = question("餵食方式", "INPUT", "[]", true, 2);
        UUID q3 = question("其他備註", "TEXTAREA", "[]", false, 3);
        Map<String, Object> b = new HashMap<>(bookingBody(sitter, List.of(today().plusDays(3)), 1, 500, petA));
        b.put("answers", List.of(answer(q1, "否"), answer(q2, "每天兩餐")));
        JsonNode created = body(mvc.perform(postJson("/api/orders", ownerToken(), key(), b)).andExpect(status().isCreated()));
        UUID orderId = UUID.fromString(created.get("orderId").asString());

        JsonNode before = eval(orderId).get("questionnaire");
        assertThat(before.get("status").asString()).isEqualTo("COMPLETED");

        jdbc.update("UPDATE sitter_questions SET question_text = '改過的題目', options = '[\"有\",\"沒有\"]'::jsonb WHERE id = ?", q1);
        jdbc.update("UPDATE sitter_questions SET is_active = false WHERE id = ?", q2);
        jdbc.update("UPDATE sitter_questions SET answer_type = 'INPUT', question_text = '新備註' WHERE id = ?", q3);

        JsonNode after = eval(orderId).get("questionnaire");
        assertThat(after).isEqualTo(before);
        JsonNode answers = after.get("answers");
        assertThat(answers.size()).isEqualTo(3);
        assertThat(answers.get(0).get("questionId").asString()).isEqualTo(q1.toString());
        assertThat(answers.get(0).get("questionText").asString()).isEqualTo("是否有其他寵物");
        assertThat(answers.get(0).get("questionType").asString()).isEqualTo("RADIO");
        assertThat(answers.get(0).get("answer").get(0).asString()).isEqualTo("否");
        assertThat(answers.get(1).get("questionText").asString()).isEqualTo("餵食方式");
        assertThat(answers.get(1).get("answer").asString()).isEqualTo("每天兩餐");
        assertThat(answers.get(2).get("questionText").asString()).isEqualTo("其他備註");
        assertThat(answers.get(2).get("questionType").asString()).isEqualTo("TEXTAREA");
        assertThat(answers.get(2).has("answer")).isFalse();
    }

    @Test
    @DisplayName("PRD-006 AC-1.3 免填原因與熟客標記即時判定（送單後另一筆訂單結案 → isRegularOwner=true，免填原因不變）")
    void regularOwnerIsLive() throws Exception {
        UUID orderId = book(today().plusDays(3));
        assertThat(eval(orderId).get("isRegularOwner").asBoolean()).isFalse();
        fx.order(owner, sitter, "COMPLETED");
        JsonNode r = eval(orderId);
        assertThat(r.get("isRegularOwner").asBoolean()).isTrue();
        assertThat(r.get("questionnaire").get("exemptReason").asString()).isEqualTo("NO_ACTIVE_QUESTIONS");
    }

    @Test
    @DisplayName("PRD-006 AC-1.5 預檢：鎖定 / 額度滿 / 無收款帳號 / 評估逾期 / 方案可否調價，皆以同一 serverTime 計算")
    void precheck() throws Exception {
        UUID orderId = book(today().plusDays(3));
        JsonNode ok = eval(orderId).get("precheck");
        assertThat(ok.get("subscriptionLocked").asBoolean()).isFalse();
        assertThat(ok.get("quotaReached").asBoolean()).isFalse();
        assertThat(ok.get("payoutAccountMissing").asBoolean()).isFalse();
        assertThat(ok.get("planAllowsAdjustment").asBoolean()).isTrue();
        jdbc.update("UPDATE profiles SET bank_account_info = NULL WHERE user_id = ?", sitter);
        tier(sitter, "FREE");
        for (int i = 0; i < 3; i++) {
            UUID o = fx.order(owner, sitter, "CONFIRMED");
            jdbc.update("UPDATE orders SET confirmed_at = ? WHERE id = ?", OffsetDateTime.now(clock), o);
        }
        jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", OffsetDateTime.now(clock).minusDays(3), sitter);
        advance(Duration.ofDays(4));
        JsonNode bad = eval(orderId).get("precheck");
        assertThat(bad.get("subscriptionLocked").asBoolean()).isTrue();
        assertThat(bad.get("payoutAccountMissing").asBoolean()).isTrue();
        assertThat(bad.get("reviewExpired").asBoolean()).isTrue();
        assertThat(bad.get("planAllowsAdjustment").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("PRD-006 AC-1.4 評估頁越權：他人保母 403 FORBIDDEN、OWNER 403 ROLE_MISMATCH、不存在 404、未登入 401")
    void access() throws Exception {
        UUID orderId = book(today().plusDays(3));
        UUID other = fx.user("os-" + UUID.randomUUID() + "@test.com", "SITTER");
        mvc.perform(getAs("/api/orders/{id}/evaluation", bearer(other, "SITTER"), orderId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(getAs("/api/orders/{id}/evaluation", ownerToken(), orderId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        mvc.perform(getAs("/api/orders/{id}/evaluation", sitterToken(), UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(getAs("/api/orders/{id}/evaluation", null, orderId)).andExpect(status().isUnauthorized());
    }
}
