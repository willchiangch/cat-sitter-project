package com.petsitter.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E1 事前問卷（SD-ORDER §3.4、§10.3 BookingQuestionnaireIntegrationTest）。 */
@DisplayName("E1 BookingQuestionnaireIntegrationTest")
class BookingQuestionnaireIntegrationTest extends OrderIntegrationTestBase {

    private UUID q1;
    private UUID q2;
    private UUID q3;
    private UUID q4;

    private UUID question(String text, String type, String options, boolean required, boolean active, int sort) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sitter_questions (id, sitter_id, question_text, answer_type, options, required, is_active, sort_order) "
                + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)", id, sitter, text, type, options, required, active, sort);
        return id;
    }

    @BeforeEach
    void questions() {
        q1 = question("是否有其他寵物", "RADIO", "[\"是\",\"否\"]", true, true, 1);
        q2 = question("餵食方式", "INPUT", "[]", true, true, 2);
        q3 = question("其他備註", "TEXTAREA", "[]", false, true, 3);
        q4 = question("已停用", "INPUT", "[]", true, false, 4);
    }

    private Map<String, Object> withAnswers(List<Map<String, Object>> answers) {
        Map<String, Object> b = new HashMap<>(bookingBody(sitter, List.of(today().plusDays(3)), 1, 500, petA));
        b.put("answers", answers);
        return b;
    }

    private static Map<String, Object> a(UUID q, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put("questionId", q.toString());
        m.put("value", value);
        return m;
    }

    @Test
    @DisplayName("PRD-005 AC-3.5 新客戶填寫：COMPLETED、每題一列快照（題目文字 / 題型 / 答案，未答存 JSON null，實際寫 DB 再讀回）")
    void completedSnapshotRoundTrip() throws Exception {
        JsonNode r = body(mvc.perform(postJson("/api/orders", ownerToken(), key(),
                        withAnswers(List.of(a(q1, "是"), a(q2, " 每天餵兩次 ")))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.questionnaireStatus").value("COMPLETED")));
        UUID orderId = UUID.fromString(r.get("orderId").asString());
        assertThat(jdbc.queryForObject("SELECT questionnaire_exempt_reason FROM orders WHERE id = ?", String.class, orderId)).isNull();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT question_text, question_type, answer::text AS a, jsonb_typeof(answer) AS t FROM order_answers WHERE order_id = ? ORDER BY sort_order", orderId);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("a")).isEqualTo("[\"是\"]");
        assertThat(rows.get(1).get("a")).isEqualTo("\"每天餵兩次\"");
        assertThat(rows.get(2).get("t")).isEqualTo("null");
        assertThat(rows.get(2).get("a")).isEqualTo("null");
        // 題目修改後快照不變；E5 讀回（未答 → 欄位不輸出）
        jdbc.update("UPDATE sitter_questions SET question_text = '改過了' WHERE id = ?", q1);
        JsonNode eval = body(mvc.perform(getAs("/api/orders/{id}/evaluation", sitterToken(), orderId)).andExpect(status().isOk()));
        JsonNode answers = eval.get("questionnaire").get("answers");
        assertThat(answers.get(0).get("questionText").asString()).isEqualTo("是否有其他寵物");
        assertThat(answers.get(0).get("answer").get(0).asString()).isEqualTo("是");
        assertThat(answers.get(2).has("answer")).isFalse();
    }

    @Test
    @DisplayName("PRD-005 AC-3.6 必填缺答 / 只有空白 / 選項不在當下題目 → 409 QUESTIONNAIRE_CHANGED（payload questions）；停用題目答案忽略")
    void questionnaireChanged() throws Exception {
        mvc.perform(postJson("/api/orders", ownerToken(), key(), withAnswers(List.of(a(q1, "是")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTIONNAIRE_CHANGED"))
                .andExpect(jsonPath("$.payload.questions.length()").value(3));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), withAnswers(List.of(a(q1, "是"), a(q2, "　 ")))))
                .andExpect(status().isConflict());
        mvc.perform(postJson("/api/orders", ownerToken(), key(), withAnswers(List.of(a(q1, "也許"), a(q2, "x")))))
                .andExpect(status().isConflict());
        mvc.perform(postJson("/api/orders", ownerToken(), key(), withAnswers(List.of(a(q1, "是"), a(q2, "x"), a(q4, "ignored")))))
                .andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM order_answers WHERE question_id = ?", q4)).isZero();
    }

    @Test
    @DisplayName("PRD-005 AC-3.6 長度：簡答 strip 後 201 字 → 400（payload questionId）、200 字加前後空白 → 201；同題重複 → 400")
    void lengthAndDuplicate() throws Exception {
        String s200 = "字".repeat(200);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), withAnswers(List.of(a(q1, "是"), a(q2, s200 + "多")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.questionId").value(q2.toString()));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), withAnswers(List.of(a(q1, "是"), a(q1, "否"), a(q2, "x")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.questionId").value(q1.toString()));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), withAnswers(List.of(a(q1, "是"), a(q2, "　" + s200 + " ")))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PRD-005 AC-3.7 熟客免填（REGULAR_OWNER，兩者並存亦此）；無啟用題目 → NO_ACTIVE_QUESTIONS；送來的答案忽略")
    void exemptions() throws Exception {
        fx.order(owner, sitter, "COMPLETED");
        JsonNode r = body(mvc.perform(postJson("/api/orders", ownerToken(), key(), withAnswers(List.of(a(q1, "是")))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.questionnaireStatus").value("NOT_REQUIRED")));
        UUID orderId = UUID.fromString(r.get("orderId").asString());
        assertThat(jdbc.queryForObject("SELECT questionnaire_exempt_reason FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("REGULAR_OWNER");
        assertThat(count("SELECT count(*) FROM order_answers WHERE order_id = ?", orderId)).isZero();
    }

    @Test
    @DisplayName("PRD-005 BR-20 舊門禁 NO_QUESTIONNAIRE 規則無效（ULTIMATE 仍需填）")
    void noQuestionnaireRuleIgnored() throws Exception {
        tier(sitter, "ULTIMATE");
        jdbc.update("INSERT INTO gatekeeper_rules (id, sitter_id, rule_type, scope_type, target_user_id) VALUES (?, ?, 'NO_QUESTIONNAIRE', 'GLOBAL', ?)",
                UUID.randomUUID(), sitter, owner);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), withAnswers(List.of())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTIONNAIRE_CHANGED"));
    }

    @Test
    @DisplayName("PRD-005 AC-3.5 E31 對 OWNER 回傳需填與題目（停用題不列）")
    void contextListsQuestions() throws Exception {
        JsonNode ctx = body(mvc.perform(getAs("/api/sitters/{id}/booking-context", ownerToken(), sitter)));
        assertThat(ctx.get("questionnaire").get("required").asBoolean()).isTrue();
        assertThat(ctx.get("questionnaire").get("questions").size()).isEqualTo(3);
        assertThat(LocalDate.now()).isNotNull();
    }
}
