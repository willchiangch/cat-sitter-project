package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SD-ORDER §10.3 ModificationPreviewIntegrationTest：E32（對應 E17）/ E33（對應 E20）變更金額試算。
 * 試算 = 送出（同一份輸入與資料狀態下金額相同）、錯誤一致、無副作用、不取鎖、CQ-51 文字欄位規則。
 * 基本情境：CONFIRMED 訂單，planA（500）三天各 1 次，已收淨額 1,500，保母 PRO。
 */
@DisplayName("E32 / E33 ModificationPreviewIntegrationTest")
class ModificationPreviewIntegrationTest extends OrderIntegrationTestBase {

    private static final String E32 = "/api/orders/{o}/modifications/preview";
    private static final String E33 = "/api/orders/{o}/modifications/{m}/quote/preview";
    private static final String E20 = "/api/orders/{o}/modifications/{m}/quote";

    LocalDate d1() {
        return today().plusDays(5);
    }

    UUID order3() throws Exception {
        return confirmedOrder(d1(), d1().plusDays(1), d1().plusDays(2));
    }

    static Map<String, Object> change(String reason, List<UUID> remove, List<Map<String, Object>> adds) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "CHANGE");
        if (reason != null) {
            m.put("reason", reason);
        }
        m.put("removeVisitIds", remove.stream().map(UUID::toString).toList());
        m.put("addItems", adds);
        return m;
    }

    static Map<String, Object> quoteBody(List<UUID> remove, List<Map<String, Object>> adds) {
        Map<String, Object> m = new HashMap<>();
        m.put("removeVisitIds", remove.stream().map(UUID::toString).toList());
        m.put("addItems", adds);
        return m;
    }

    static Map<String, Object> with(Map<String, Object> base, Object... kv) {
        Map<String, Object> m = new HashMap<>(base);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    ResultActions preview(UUID orderId, String token, Object body) throws Exception {
        return mvc.perform(postJson(E32, token, null, body, orderId));
    }

    ResultActions quotePreview(UUID orderId, UUID modId, Object body) throws Exception {
        return mvc.perform(postJson(E33, sitterToken(), null, body, orderId, modId));
    }

    ResultActions quoteSubmit(UUID orderId, UUID modId, Object body) throws Exception {
        return mvc.perform(postJson(E20, sitterToken(), key(), body, orderId, modId));
    }

    UUID ownerRequests(UUID orderId, Map<String, Object> body) throws Exception {
        return modificationId(modify(orderId, ownerToken(), body).andExpect(status().isCreated()));
    }

    /** V1 DONE、V2 CLOSED_BY_SYSTEM、V3 PENDING（單價各 500）。回傳 [V1, V2, V3]。 */
    List<UUID> finishedMix(UUID orderId) throws Exception {
        List<UUID> vs = visitIds(orderId, "PENDING");
        finishVisit(vs.get(0), false);
        jdbc.update("UPDATE visits SET status = 'CLOSED_BY_SYSTEM', closed_at = now() WHERE id = ?", vs.get(1));
        return vs;
    }

    List<Map<String, Object>> manyDates(int n) {
        List<Map<String, Object>> adds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adds.add(addItem(d1().plusDays(3 + i), planA, 1, petA));
        }
        return adds;
    }

    UUID foreignVisit() throws Exception {
        UUID other = confirmedOrder(today().plusDays(20));
        return visitIds(other, "PENDING").get(0);
    }

    void dispute(UUID orderId) {
        jdbc.update("UPDATE orders SET status = 'DISPUTED', disputed_at = now() WHERE id = ?", orderId);
    }

    void lockSubscription() {
        jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", OffsetDateTime.now(clock).minusDays(3), sitter);
    }

    record Counts(int mods, int logs, int notifications, int idem, long version) {
    }

    Counts counts(UUID orderId) {
        return new Counts(count("SELECT count(*) FROM modification_requests"), count("SELECT count(*) FROM order_logs"),
                count("SELECT count(*) FROM notifications"), count("SELECT count(*) FROM idempotency_records"),
                jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, orderId));
    }

    /** 試算與送出回同一個 HTTP 狀態、code、payload（SD-ORDER §10.3 (4)）。 */
    void assertSameError(ResultActions preview, ResultActions submit, int expectedStatus, String expectedCode) throws Exception {
        MvcResult p = preview.andReturn();
        MvcResult s = submit.andReturn();
        JsonNode pj = json.readTree(p.getResponse().getContentAsString());
        JsonNode sj = json.readTree(s.getResponse().getContentAsString());
        assertThat(p.getResponse().getStatus()).as("preview status").isEqualTo(expectedStatus);
        assertThat(pj.get("code").asString()).as("preview code").isEqualTo(expectedCode);
        assertThat(s.getResponse().getStatus()).as("submit status").isEqualTo(p.getResponse().getStatus());
        assertThat(sj.get("code")).as("submit code").isEqualTo(pj.get("code"));
        assertThat(sj.get("payload")).as("submit payload").isEqualTo(pj.get("payload"));
    }

    // ------------------------------------------------------------------ (1)(2)(3) 試算 = 送出

    @Test
    @DisplayName("PRD-016 AC-1.3 / AC-2.4 E32（SITTER）= E17：V1 DONE、V2 CLOSED_BY_SYSTEM、V3 PENDING，減少 V3、新增 2 次、PRO 加價 100；明細合計 = systemAmount")
    void e32EqualsE17() throws Exception {
        UUID orderId = order3();
        List<UUID> vs = finishedMix(orderId);
        Map<String, Object> body = with(change("改天", List.of(vs.get(2)), List.of(addItem(d1().plusDays(9), planA, 2, petA))),
                "surchargeAmount", 100, "adjustmentReason", "假日");
        Counts before = counts(orderId);
        JsonNode p = body(preview(orderId, sitterToken(), body).andExpect(status().isOk()));
        assertThat(counts(orderId)).isEqualTo(before);
        assertThat(p.get("type").asString()).isEqualTo("CHANGE");
        assertThat(p.get("systemAmount").asInt()).isEqualTo(2000);
        assertThat(p.get("surchargeAmount").asInt()).isEqualTo(100);
        assertThat(p.get("discountAmount").asInt()).isZero();
        assertThat(p.get("newTotalAmount").asInt()).isEqualTo(2100);
        assertThat(p.get("currentTotalAmount").asInt()).isEqualTo(1500);
        assertThat(p.get("netPaid").asInt()).isEqualTo(1500);
        assertThat(p.get("diff").asInt()).isEqualTo(600);
        assertThat(p.get("pricingMode").asString()).isEqualTo("CUSTOM_ALLOWED");
        assertThat(p.has("reason")).isFalse();
        assertThat(p.get("computedAt").asString()).isNotBlank();
        JsonNode kept = p.get("breakdown").get("kept");
        assertThat(kept.size()).isEqualTo(2);
        assertThat(kept.get(0).get("visitId").asString()).isEqualTo(vs.get(0).toString());
        assertThat(kept.get(0).get("status").asString()).isEqualTo("DONE");
        assertThat(kept.get(1).get("visitId").asString()).isEqualTo(vs.get(1).toString());
        assertThat(kept.get(1).get("status").asString()).isEqualTo("CLOSED_BY_SYSTEM");
        assertThat(kept.get(1).get("unitPrice").asInt()).isEqualTo(500);
        JsonNode added = p.get("breakdown").get("added").get(0);
        assertThat(added.get("index").asInt()).isZero();
        assertThat(added.get("dailyCount").asInt()).isEqualTo(2);
        assertThat(added.get("unitPrice").asInt()).isEqualTo(500);
        assertThat(added.get("subtotal").asInt()).isEqualTo(1000);
        int sum = 0;
        for (JsonNode k : kept) {
            sum += k.get("unitPrice").asInt();
        }
        for (JsonNode a : p.get("breakdown").get("added")) {
            sum += a.get("subtotal").asInt();
        }
        assertThat(sum).isEqualTo(p.get("systemAmount").asInt());

        JsonNode s = body(modify(orderId, sitterToken(), body).andExpect(status().isCreated()));
        assertPersistedEquals(p, s);
    }

    void assertPersistedEquals(JsonNode preview, JsonNode submit) {
        UUID modId = UUID.fromString(submit.get("modificationId").asString());
        for (String f : List.of("systemAmount", "newTotalAmount", "netPaid", "diff")) {
            assertThat(submit.get(f).asInt()).as(f).isEqualTo(preview.get(f).asInt());
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT system_amount, new_total_amount, quoted_net_paid, quoted_diff, surcharge_amount, discount_amount "
                        + "FROM modification_requests WHERE id = ?", modId);
        assertThat(row.get("system_amount")).isEqualTo(preview.get("systemAmount").asInt());
        assertThat(row.get("new_total_amount")).isEqualTo(preview.get("newTotalAmount").asInt());
        assertThat(row.get("quoted_net_paid")).isEqualTo(preview.get("netPaid").asInt());
        assertThat(row.get("quoted_diff")).isEqualTo(preview.get("diff").asInt());
        assertThat(row.get("surcharge_amount")).isEqualTo(preview.get("surchargeAmount").asInt());
        assertThat(row.get("discount_amount")).isEqualTo(preview.get("discountAmount").asInt());
    }

    @Test
    @DisplayName("PRD-016 AC-2.3 E33 = E20：同 (1) 資料，飼主要求減少 V3，保母以相同內容 + PRO 加價試算後報價")
    void e33EqualsE20() throws Exception {
        UUID orderId = order3();
        List<UUID> vs = finishedMix(orderId);
        UUID modId = ownerRequests(orderId, change("改天", List.of(vs.get(2)), List.of()));
        Map<String, Object> body = with(quoteBody(List.of(vs.get(2)), List.of(addItem(d1().plusDays(9), planA, 2, petA))),
                "surchargeAmount", 300, "discountAmount", 50, "adjustmentReason", "假日");
        Counts before = counts(orderId);
        JsonNode p = body(quotePreview(orderId, modId, body).andExpect(status().isOk()));
        assertThat(counts(orderId)).isEqualTo(before);
        assertThat(p.get("systemAmount").asInt()).isEqualTo(2000);
        assertThat(p.get("newTotalAmount").asInt()).isEqualTo(2250);
        assertThat(p.get("diff").asInt()).isEqualTo(750);
        JsonNode s = body(quoteSubmit(orderId, modId, body).andExpect(status().isOk()));
        assertThat(s.get("modificationId").asString()).isEqualTo(modId.toString());
        assertPersistedEquals(p, s);
    }

    @Test
    @DisplayName("PRD-016 AC-2.4 / AC-2.1 E33 = E20：CANCEL_ALL、BASIC → 新總額 1000、差額 −500、SYSTEM_ONLY；kept 含 DONE 與 CLOSED_BY_SYSTEM")
    void e33CancelAllBasic() throws Exception {
        UUID orderId = order3();
        List<UUID> vs = finishedMix(orderId);
        UUID modId = ownerRequests(orderId, Map.of("type", "CANCEL_ALL", "reason", "取消"));
        tier(sitter, "BASIC");
        JsonNode p = body(quotePreview(orderId, modId, Map.of()).andExpect(status().isOk()));
        assertThat(p.get("type").asString()).isEqualTo("CANCEL_ALL");
        assertThat(p.get("removeVisitIds").size()).isEqualTo(1);
        assertThat(p.get("removeVisitIds").get(0).asString()).isEqualTo(vs.get(2).toString());
        assertThat(p.get("newTotalAmount").asInt()).isEqualTo(1000);
        assertThat(p.get("diff").asInt()).isEqualTo(-500);
        assertThat(p.get("pricingMode").asString()).isEqualTo("SYSTEM_ONLY");
        assertThat(p.get("breakdown").get("kept").toString()).contains("DONE", "CLOSED_BY_SYSTEM");
        JsonNode s = body(quoteSubmit(orderId, modId, Map.of()).andExpect(status().isOk()));
        assertPersistedEquals(p, s);
    }

    @Test
    @DisplayName("PRD-016 AC-2.1 E33 = E20：全 PENDING 的 CANCEL_ALL → 新總額 0、差額 −1500；removeVisitIds 依 serviceDate 升冪")
    void e33CancelAllPending() throws Exception {
        UUID orderId = order3();
        List<UUID> vs = visitIds(orderId, "PENDING");
        UUID modId = ownerRequests(orderId, Map.of("type", "CANCEL_ALL", "reason", "取消"));
        JsonNode p = body(quotePreview(orderId, modId, Map.of("removeVisitIds", List.of(UUID.randomUUID().toString())))
                .andExpect(status().isOk()));
        assertThat(p.get("newTotalAmount").asInt()).isZero();
        assertThat(p.get("diff").asInt()).isEqualTo(-1500);
        assertThat(p.get("breakdown").get("kept").size()).isZero();
        assertThat(p.get("removeVisitIds").toString()).isEqualTo(json.writeValueAsString(vs.stream().map(UUID::toString).toList()));
        JsonNode s = body(quoteSubmit(orderId, modId, Map.of()).andExpect(status().isOk()));
        assertPersistedEquals(p, s);
    }

    @Test
    @DisplayName("PRD-016 AC-1.1 / AC-2.1 OWNER E32 → E17 REQUESTED → 保母相同內容不加減 E20：E32 systemAmount = DB system_amount；OWNER 無 pricingMode、加減 0")
    void ownerPreviewEqualsSitterQuote() throws Exception {
        UUID orderId = order3();
        UUID v1 = visitIds(orderId, "PENDING").get(0);
        List<Map<String, Object>> adds = List.of(addItem(d1().plusDays(9), planA, 1, petA));
        Map<String, Object> body = with(change("改天", List.of(v1), adds), "surchargeAmount", 999, "adjustmentReason", "忽略");
        JsonNode p = body(preview(orderId, ownerToken(), body).andExpect(status().isOk()));
        assertThat(p.has("pricingMode")).isFalse();
        assertThat(p.get("surchargeAmount").asInt()).isZero();
        assertThat(p.get("discountAmount").asInt()).isZero();
        assertThat(p.get("systemAmount").asInt()).isEqualTo(1500);
        assertThat(p.get("newTotalAmount").asInt()).isEqualTo(1500);
        assertThat(p.get("diff").asInt()).isZero();
        UUID modId = ownerRequests(orderId, body);
        quoteSubmit(orderId, modId, quoteBody(List.of(v1), adds)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT system_amount FROM modification_requests WHERE id = ?", Integer.class, modId))
                .isEqualTo(p.get("systemAmount").asInt());
    }

    // ------------------------------------------------------------------ (4) 錯誤一致

    @FunctionalInterface
    interface InitScenario {
        Map<String, Object> prepare(ModificationPreviewIntegrationTest t, UUID orderId) throws Exception;
    }

    @FunctionalInterface
    interface QuoteScenario {
        Map<String, Object> prepare(ModificationPreviewIntegrationTest t, UUID orderId, UUID modId) throws Exception;
    }

    static Stream<Arguments> initiateCases() {
        return Stream.of(
                Arguments.of("每日次數 4", "SITTER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        change("x", List.of(), List.of(t.addItem(t.d1().plusDays(9), t.planA, 4, t.petA)))),
                Arguments.of("同方案同日合計 4", "SITTER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        change("x", List.of(), List.of(t.addItem(t.d1(), t.planA, 3, t.petA)))),
                Arguments.of("日期 61 個", "SITTER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        change("x", List.of(), t.manyDates(58))),
                Arguments.of("訂單外毛孩", "SITTER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        change("x", List.of(), List.of(t.addItem(t.d1().plusDays(9), t.planA, 1, t.fx.pet(t.owner, "新貓"))))),
                Arguments.of("不屬本訂單的行程", "SITTER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        change("x", List.of(t.foreignVisit()), List.of())),
                Arguments.of("方案不在快照", "SITTER", 422, "PLAN_NOT_IN_ORDER", (InitScenario) (t, o) ->
                        change("x", List.of(), List.of(t.addItem(t.d1().plusDays(9), t.planB, 1, t.petA)))),
                Arguments.of("新增日期 = 今天", "SITTER", 422, "INVALID_VISIT_DATE", (InitScenario) (t, o) ->
                        change("x", List.of(), List.of(t.addItem(t.today(), t.planA, 1, t.petA)))),
                Arguments.of("空 CHANGE", "SITTER", 422, "MODIFICATION_EMPTY", (InitScenario) (t, o) ->
                        change("x", List.of(), List.of())),
                Arguments.of("減少 DONE 行程", "SITTER", 409, "VISIT_ALREADY_STARTED", (InitScenario) (t, o) -> {
                    UUID v = t.visitIds(o, "PENDING").get(0);
                    t.finishVisit(v, false);
                    return change("x", List.of(v), List.of());
                }),
                Arguments.of("FREE 帶加價", "SITTER", 403, "PLAN_REQUIRED", (InitScenario) (t, o) -> {
                    t.tier(t.sitter, "FREE");
                    return with(change("x", List.of(t.visitIds(o, "PENDING").get(0)), List.of()),
                            "surchargeAmount", 100, "adjustmentReason", "假日");
                }),
                Arguments.of("加減後 < 0", "SITTER", 422, "INVALID_MODIFICATION_AMOUNT", (InitScenario) (t, o) ->
                        with(change("x", List.of(t.visitIds(o, "PENDING").get(0)), List.of()),
                                "discountAmount", 1500, "adjustmentReason", "折扣")),
                Arguments.of("新總額 1,000,000", "SITTER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        with(change("x", List.of(t.visitIds(o, "PENDING").get(0)), List.of()),
                                "surchargeAmount", 999000, "adjustmentReason", "加價")),
                Arguments.of("DISPUTED 訂單", "SITTER", 409, "ORDER_STATE_CONFLICT", (InitScenario) (t, o) -> {
                    t.dispute(o);
                    return Map.of("type", "CANCEL_ALL", "reason", "x");
                }),
                Arguments.of("已有未結束變更", "SITTER", 409, "MODIFICATION_IN_PROGRESS", (InitScenario) (t, o) -> {
                    t.ownerRequests(o, Map.of("type", "CANCEL_ALL", "reason", "先"));
                    return Map.of("type", "CANCEL_ALL", "reason", "x");
                }),
                Arguments.of("保母缺 reason（CQ-51 照送出驗證）", "SITTER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        Map.of("type", "CANCEL_ALL")),
                Arguments.of("保母加價缺 adjustmentReason（CQ-51）", "SITTER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        with(change("x", List.of(t.visitIds(o, "PENDING").get(0)), List.of()), "surchargeAmount", 100)),
                Arguments.of("飼主：type 非法", "OWNER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        Map.of("type", "OTHER", "reason", "x")),
                Arguments.of("飼主：每日次數 4", "OWNER", 400, "VALIDATION_FAILED", (InitScenario) (t, o) ->
                        change("x", List.of(), List.of(t.addItem(t.d1().plusDays(9), t.planA, 4, t.petA)))),
                Arguments.of("飼主：DISPUTED 訂單", "OWNER", 409, "ORDER_STATE_CONFLICT", (InitScenario) (t, o) -> {
                    t.dispute(o);
                    return Map.of("type", "CANCEL_ALL", "reason", "x");
                }),
                Arguments.of("飼主：新增日期 = 今天", "OWNER", 422, "INVALID_VISIT_DATE", (InitScenario) (t, o) ->
                        change("x", List.of(), List.of(t.addItem(t.today(), t.planA, 1, t.petA)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("initiateCases")
    @DisplayName("PRD-016 AC-1.4–AC-1.8 / AC-2.2 / AC-2.7 E32 與 E17 對相同非法輸入回相同狀態、code、payload，且試算無副作用")
    void e32ErrorsMatchE17(String name, String role, int expectedStatus, String expectedCode, InitScenario scenario) throws Exception {
        UUID orderId = order3();
        Map<String, Object> body = scenario.prepare(this, orderId);
        String token = "OWNER".equals(role) ? ownerToken() : sitterToken();
        Counts before = counts(orderId);
        ResultActions p = preview(orderId, token, body);
        assertThat(counts(orderId)).isEqualTo(before);
        assertSameError(p, modify(orderId, token, body), expectedStatus, expectedCode);
    }

    static Stream<Arguments> quoteCases() {
        return Stream.of(
                Arguments.of("每日次數 4", 400, "VALIDATION_FAILED", (QuoteScenario) (t, o, m) ->
                        quoteBody(List.of(), List.of(t.addItem(t.d1().plusDays(9), t.planA, 4, t.petA)))),
                Arguments.of("同方案同日合計 4", 400, "VALIDATION_FAILED", (QuoteScenario) (t, o, m) ->
                        quoteBody(List.of(), List.of(t.addItem(t.d1(), t.planA, 3, t.petA)))),
                Arguments.of("日期 61 個", 400, "VALIDATION_FAILED", (QuoteScenario) (t, o, m) ->
                        quoteBody(List.of(), t.manyDates(58))),
                Arguments.of("訂單外毛孩", 400, "VALIDATION_FAILED", (QuoteScenario) (t, o, m) ->
                        quoteBody(List.of(), List.of(t.addItem(t.d1().plusDays(9), t.planA, 1, t.fx.pet(t.owner, "新貓"))))),
                Arguments.of("不屬本訂單的行程", 400, "VALIDATION_FAILED", (QuoteScenario) (t, o, m) ->
                        quoteBody(List.of(t.foreignVisit()), List.of())),
                Arguments.of("方案不在快照", 422, "PLAN_NOT_IN_ORDER", (QuoteScenario) (t, o, m) ->
                        quoteBody(List.of(), List.of(t.addItem(t.d1().plusDays(9), t.planB, 1, t.petA)))),
                Arguments.of("新增日期 = 今天", 422, "INVALID_VISIT_DATE", (QuoteScenario) (t, o, m) ->
                        quoteBody(List.of(), List.of(t.addItem(t.today(), t.planA, 1, t.petA)))),
                Arguments.of("空 CHANGE", 422, "MODIFICATION_EMPTY", (QuoteScenario) (t, o, m) ->
                        quoteBody(List.of(), List.of())),
                Arguments.of("減少 DONE 行程", 409, "VISIT_ALREADY_STARTED", (QuoteScenario) (t, o, m) -> {
                    UUID v = t.visitIds(o, "PENDING").get(1);
                    t.finishVisit(v, false);
                    return quoteBody(List.of(v), List.of());
                }),
                Arguments.of("FREE 帶加價", 403, "PLAN_REQUIRED", (QuoteScenario) (t, o, m) -> {
                    t.tier(t.sitter, "FREE");
                    return with(quoteBody(List.of(t.visitIds(o, "PENDING").get(0)), List.of()),
                            "surchargeAmount", 100, "adjustmentReason", "假日");
                }),
                Arguments.of("加減後 < 0", 422, "INVALID_MODIFICATION_AMOUNT", (QuoteScenario) (t, o, m) ->
                        with(quoteBody(List.of(t.visitIds(o, "PENDING").get(0)), List.of()),
                                "discountAmount", 1500, "adjustmentReason", "折扣")),
                Arguments.of("新總額 1,000,000", 400, "VALIDATION_FAILED", (QuoteScenario) (t, o, m) ->
                        with(quoteBody(List.of(t.visitIds(o, "PENDING").get(0)), List.of()),
                                "surchargeAmount", 999000, "adjustmentReason", "加價")),
                Arguments.of("DISPUTED 訂單", 409, "ORDER_STATE_CONFLICT", (QuoteScenario) (t, o, m) -> {
                    t.dispute(o);
                    return quoteBody(List.of(t.visitIds(o, "PENDING").get(0)), List.of());
                }),
                Arguments.of("變更已 QUOTED", 409, "MODIFICATION_STATE_CONFLICT", (QuoteScenario) (t, o, m) -> {
                    Map<String, Object> b = quoteBody(List.of(t.visitIds(o, "PENDING").get(0)), List.of());
                    t.quoteSubmit(o, m, b).andExpect(status().isOk());
                    return b;
                }),
                Arguments.of("變更已逾期", 409, "DEADLINE_PASSED", (QuoteScenario) (t, o, m) -> {
                    t.advance(Duration.ofHours(72));
                    return quoteBody(List.of(t.visitIds(o, "PENDING").get(0)), List.of());
                }),
                Arguments.of("加價缺 adjustmentReason（CQ-51）", 400, "VALIDATION_FAILED", (QuoteScenario) (t, o, m) ->
                        with(quoteBody(List.of(t.visitIds(o, "PENDING").get(0)), List.of()), "surchargeAmount", 100)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quoteCases")
    @DisplayName("PRD-016 AC-2.2 / AC-2.5 / AC-2.7 / AC-3.4 E33 與 E20 對相同非法輸入回相同狀態、code、payload，且試算無副作用")
    void e33ErrorsMatchE20(String name, int expectedStatus, String expectedCode, QuoteScenario scenario) throws Exception {
        UUID orderId = order3();
        UUID modId = ownerRequests(orderId, change("改", List.of(visitIds(orderId, "PENDING").get(0)), List.of()));
        Map<String, Object> body = scenario.prepare(this, orderId, modId);
        Counts before = counts(orderId);
        ResultActions p = quotePreview(orderId, modId, body);
        assertThat(counts(orderId)).isEqualTo(before);
        assertSameError(p, quoteSubmit(orderId, modId, body), expectedStatus, expectedCode);
    }

    // ------------------------------------------------------------------ (5) 無副作用 / (6) 不取鎖 / (7) 鎖定 / (8) CQ-51

    @Test
    @DisplayName("SD-ORDER §6.3 E32 / E33 無副作用：成功後 modification_requests / order_logs / notifications / idempotency_records 列數與 orders.version 不變；帶 Idempotency-Key 也忽略")
    void noSideEffects() throws Exception {
        UUID orderId = order3();
        UUID v1 = visitIds(orderId, "PENDING").get(0);
        Counts before = counts(orderId);
        preview(orderId, ownerToken(), Map.of("type", "CANCEL_ALL")).andExpect(status().isOk());
        mvc.perform(postJson(E32, sitterToken(), key(), Map.of("type", "CANCEL_ALL", "reason", "x"), orderId))
                .andExpect(status().isOk());
        assertThat(counts(orderId)).isEqualTo(before);
        UUID modId = ownerRequests(orderId, change("改", List.of(v1), List.of()));
        Counts afterRequest = counts(orderId);
        mvc.perform(postJson(E33, sitterToken(), key(), quoteBody(List.of(v1), List.of()), orderId, modId))
                .andExpect(status().isOk());
        quotePreview(orderId, modId, quoteBody(List.of(v1), List.of())).andExpect(status().isOk());
        assertThat(counts(orderId)).isEqualTo(afterRequest);
        assertThat(jdbc.queryForObject("SELECT status FROM modification_requests WHERE id = ?", String.class, modId)).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("SD-ORDER §11.4 試算不取訂單鎖：另一交易持有訂單列鎖時 E32 / E33 於 1 秒內回 200（不回 ORDER_BUSY）")
    void worksWhileOrderLocked() throws Exception {
        UUID orderId = order3();
        UUID v1 = visitIds(orderId, "PENDING").get(0);
        CompletableFuture<Void> holder = holdOrderLock(orderId, Duration.ofSeconds(3));
        long start = System.nanoTime();
        preview(orderId, sitterToken(), change("x", List.of(v1), List.of())).andExpect(status().isOk());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        holder.join();

        UUID modId = ownerRequests(orderId, change("改", List.of(v1), List.of()));
        holder = holdOrderLock(orderId, Duration.ofSeconds(3));
        start = System.nanoTime();
        quotePreview(orderId, modId, quoteBody(List.of(v1), List.of())).andExpect(status().isOk());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        holder.join();
    }

    @Test
    @DisplayName("PRD-024 AC-2.8 / PRD-016 AC-2.8 鎖定保母 E32 → pricingMode=SYSTEM_ONLY；帶加價 → 403 PLAN_REQUIRED")
    void lockedSitter() throws Exception {
        UUID orderId = order3();
        UUID v1 = visitIds(orderId, "PENDING").get(0);
        lockSubscription();
        preview(orderId, sitterToken(), change("x", List.of(v1), List.of())).andExpect(status().isOk())
                .andExpect(jsonPath("$.pricingMode").value("SYSTEM_ONLY"));
        preview(orderId, sitterToken(), with(change("x", List.of(v1), List.of()), "surchargeAmount", 10, "adjustmentReason", "x"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
    }

    @Test
    @DisplayName("SD-ORDER CQ-51 OWNER 呼叫 E32 不驗證 reason（缺漏 / 超長仍 200、不回傳）；同輸入送 E17 → 400 reason")
    void ownerReasonNotValidated() throws Exception {
        UUID orderId = order3();
        JsonNode p = body(preview(orderId, ownerToken(), Map.of("type", "CANCEL_ALL")).andExpect(status().isOk()));
        assertThat(p.has("reason")).isFalse();
        assertThat(p.get("newTotalAmount").asInt()).isZero();
        preview(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "長".repeat(300))).andExpect(status().isOk());
        modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("reason"));
    }
}
