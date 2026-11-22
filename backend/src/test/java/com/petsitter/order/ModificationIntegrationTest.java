package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 變更子流程（SD-ORDER §10.3 ModificationInitiate / Quote / Lifecycle / Query / Concurrency / Idempotency / Settlement，
 * 合併為 nested 類共用 context）。基本情境：CONFIRMED 訂單，planA（500）三天各 1 次，已收淨額 1,500。
 */
@DisplayName("E17–E23 ModificationIntegrationTest")
class ModificationIntegrationTest extends OrderIntegrationTestBase {

    private LocalDate d1() {
        return today().plusDays(5);
    }

    private UUID order3() throws Exception {
        return confirmedOrder(d1(), d1().plusDays(1), d1().plusDays(2));
    }

    private Map<String, Object> change(String reason, List<UUID> remove, List<Map<String, Object>> adds) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "CHANGE");
        m.put("reason", reason);
        m.put("removeVisitIds", remove.stream().map(UUID::toString).toList());
        m.put("addItems", adds);
        return m;
    }

    private ResultActions quoteMod(UUID orderId, UUID modId, Map<String, Object> body) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/modifications/{m}/quote", sitterToken(), key(), body, orderId, modId));
    }

    private ResultActions accept(UUID orderId, UUID modId) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, modId));
    }

    private ResultActions rejectMod(UUID orderId, UUID modId, String token, Map<String, Object> body) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/modifications/{m}/reject", token, key(), body, orderId, modId));
    }

    private ResultActions withdrawMod(UUID orderId, UUID modId, String token) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/modifications/{m}/withdraw", token, key(), Map.of(), orderId, modId));
    }

    private String modStatus(UUID modId) {
        return jdbc.queryForObject("SELECT status FROM modification_requests WHERE id = ?", String.class, modId);
    }

    /** 保母直接發起並報價的變更（T2）。 */
    private UUID sitterProposes(UUID orderId, List<UUID> remove, List<Map<String, Object>> adds) throws Exception {
        return modificationId(modify(orderId, sitterToken(), change("調整", remove, adds)).andExpect(status().isCreated()));
    }

    @Nested
    @DisplayName("ModificationInitiateIntegrationTest")
    class Initiate {

        @Test
        @DisplayName("PRD-016 AC-1.1 飼主發起 CHANGE → REQUESTED、requested_content、expires_at = now + 72h、MODIFICATION_REQUESTED、通知保母")
        void ownerChange() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            OffsetDateTime before = OffsetDateTime.now(clock);
            JsonNode r = body(modify(orderId, ownerToken(), change("改天", List.of(v1), List.of(addItem(d1().plusDays(9), planA, 1, petA))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED")));
            assertThat(r.has("newTotalAmount")).isFalse();
            UUID modId = UUID.fromString(r.get("modificationId").asString());
            Map<String, Object> m = jdbc.queryForMap("SELECT * FROM modification_requests WHERE id = ?", modId);
            assertThat(m.get("initiated_by")).isEqualTo("OWNER");
            assertThat(m.get("requested_content").toString()).contains(v1.toString());
            assertThat(Duration.between(before.toInstant(), ((java.sql.Timestamp) m.get("expires_at")).toInstant()).toHours()).isEqualTo(72);
            assertThat(logActions(orderId)).contains("MODIFICATION_REQUESTED");
            assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'MODIFICATION_REQUESTED'", sitter)).isEqualTo(1);
        }

        @Test
        @DisplayName("PRD-016 AC-1.2 飼主發起 CANCEL_ALL（原因必填）；AC-1.3 保母發起即報價（system / new_total / net / diff）")
        void cancelAllAndSitterQuote() throws Exception {
            UUID orderId = order3();
            modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", " ")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.payload.field").value("reason"));
            UUID modId = modificationId(modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "取消旅行"))
                    .andExpect(status().isCreated()));
            assertThat(jdbc.queryForObject("SELECT jsonb_array_length(requested_content->'removeVisitIds') FROM modification_requests WHERE id = ?",
                    Integer.class, modId)).isEqualTo(3);
            withdrawMod(orderId, modId, ownerToken()).andExpect(status().isOk());

            UUID v1 = visitIds(orderId, "PENDING").get(0);
            JsonNode q = body(modify(orderId, sitterToken(), change("改方案", List.of(v1), List.of(addItem(d1().plusDays(9), planA, 2, petA))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("QUOTED")));
            assertThat(q.get("systemAmount").asInt()).isEqualTo(2000);
            assertThat(q.get("newTotalAmount").asInt()).isEqualTo(2000);
            assertThat(q.get("netPaid").asInt()).isEqualTo(1500);
            assertThat(q.get("diff").asInt()).isEqualTo(500);
            assertThat(logActions(orderId)).contains("MODIFICATION_PROPOSED");
        }

        @Test
        @DisplayName("PRD-016 AC-1.4 不可發起：PENDING_PAYMENT → 409 ORDER_STATE_CONFLICT；無 PENDING 行程 → 409 MODIFICATION_NOT_ALLOWED")
        void notAllowed() throws Exception {
            UUID quoted = book(d1());
            quote(quoted);
            modify(quoted, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
            UUID single = confirmedOrder(d1());
            finishVisit(visitIds(single, "PENDING").get(0), false);
            modify(single, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MODIFICATION_NOT_ALLOWED"));
        }

        @Test
        @DisplayName("PRD-016 AC-1.5 同時只能一筆未結束變更 → 409 MODIFICATION_IN_PROGRESS")
        void onlyOneOpen() throws Exception {
            UUID orderId = order3();
            modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isCreated());
            modify(orderId, sitterToken(), Map.of("type", "CANCEL_ALL", "reason", "y")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MODIFICATION_IN_PROGRESS"));
        }

        @Test
        @DisplayName("PRD-016 AC-1.6 減少目標已開始 → 409 VISIT_ALREADY_STARTED（visitIds）；CHANGE 空內容 → 422 MODIFICATION_EMPTY；目標不屬本訂單 → 400")
        void removeTargets() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            startVisit(v1);
            modify(orderId, ownerToken(), change("x", List.of(v1), List.of())).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VISIT_ALREADY_STARTED")).andExpect(jsonPath("$.payload.visitIds[0]").value(v1.toString()));
            modify(orderId, ownerToken(), change("x", List.of(), List.of())).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("MODIFICATION_EMPTY"));
            modify(orderId, ownerToken(), change("x", List.of(UUID.randomUUID()), List.of())).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.payload.field").value("removeVisitIds"));
        }

        @Test
        @DisplayName("PRD-016 AC-1.8 新增項目：毛孩不在訂單範圍 / 每日次數 4 / 同方案同日合計 > 3 → 400；方案不在訂單 → 422 PLAN_NOT_IN_ORDER")
        void addItemRules() throws Exception {
            UUID orderId = order3();
            UUID pet = fx.pet(owner, "新貓");
            modify(orderId, ownerToken(), change("x", List.of(), List.of(addItem(d1().plusDays(9), planA, 1, pet))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("petIds"));
            modify(orderId, ownerToken(), change("x", List.of(), List.of(addItem(d1().plusDays(9), planA, 4, petA))))
                    .andExpect(status().isBadRequest());
            modify(orderId, ownerToken(), change("x", List.of(), List.of(addItem(d1(), planA, 3, petA))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("dailyCount"));
            modify(orderId, ownerToken(), change("x", List.of(), List.of(addItem(d1().plusDays(9), planB, 1, petA))))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PLAN_NOT_IN_ORDER"))
                    .andExpect(jsonPath("$.payload.planId").value(planB.toString()));
        }

        @Test
        @DisplayName("PRD-016 AC-1.7 越權與角色：他人 403、該單保母以 OWNER token → 403 ROLE_MISMATCH（G4a）、ADMIN → ROLE_MISMATCH")
        void access() throws Exception {
            UUID orderId = order3();
            modify(orderId, bearer(stranger, "OWNER"), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            modify(orderId, bearer(sitter, "OWNER"), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
            modify(orderId, bearer(admin, "ADMIN"), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
            modify(UUID.randomUUID(), ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("ModificationQuoteIntegrationTest")
    class QuoteTests {

        private UUID requested(UUID orderId, List<UUID> remove, List<Map<String, Object>> adds) throws Exception {
            return modificationId(modify(orderId, ownerToken(), change("x", remove, adds)).andExpect(status().isCreated()));
        }

        @Test
        @DisplayName("PRD-016 AC-2.1 / AC-2.8 FREE 系統計價、expires_at 重設、MODIFICATION_QUOTED；AC-2.2 FREE 帶加減 → 403 PLAN_REQUIRED")
        void freeSystemPricing() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            UUID modId = requested(orderId, List.of(v1), List.of());
            tier(sitter, "FREE");
            quoteMod(orderId, modId, Map.of("removeVisitIds", List.of(v1.toString()), "surchargeAmount", 100, "adjustmentReason", "假日"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
            advance(Duration.ofHours(1));
            JsonNode r = body(quoteMod(orderId, modId, Map.of("removeVisitIds", List.of(v1.toString())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUOTED")));
            assertThat(r.get("systemAmount").asInt()).isEqualTo(1000);
            assertThat(r.get("diff").asInt()).isEqualTo(-500);
            assertThat(Duration.between(OffsetDateTime.now(clock), OffsetDateTime.parse(r.get("expiresAt").asString())).toHours()).isBetween(71L, 72L);
            assertThat(logActions(orderId)).contains("MODIFICATION_QUOTED");
        }

        @Test
        @DisplayName("PRD-016 AC-2.3 PRO 加減並附原因；AC-2.7 新總額 > 999,999 → 400；加減缺原因 → 400")
        void proCustom() throws Exception {
            UUID orderId = order3();
            UUID modId = requested(orderId, List.of(visitIds(orderId, "PENDING").get(0)), List.of());
            quoteMod(orderId, modId, Map.of("surchargeAmount", 100)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.payload.field").value("adjustmentReason"));
            quoteMod(orderId, modId, Map.of("surchargeAmount", 999999, "adjustmentReason", "x")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.payload.field").value("newTotalAmount"));
            quoteMod(orderId, modId, Map.of("removeVisitIds", List.of(visitIds(orderId, "PENDING").get(0).toString()),
                    "surchargeAmount", 300, "discountAmount", 100, "adjustmentReason", "加價")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.newTotalAmount").value(1200));
            assertThat(jdbc.queryForObject("SELECT adjustment_reason FROM modification_requests WHERE id = ?", String.class, modId)).isEqualTo("加價");
        }

        @Test
        @DisplayName("PRD-016 AC-2.4 計價範圍含 DONE 與 CLOSED_BY_SYSTEM 行程")
        void pricingIncludesFinished() throws Exception {
            UUID orderId = order3();
            List<UUID> vs = visitIds(orderId, "PENDING");
            finishVisit(vs.get(0), false);
            jdbc.update("UPDATE visits SET status = 'CLOSED_BY_SYSTEM', closed_at = now() WHERE id = ?", vs.get(1));
            UUID modId = requested(orderId, List.of(vs.get(2)), List.of());
            quoteMod(orderId, modId, Map.of("removeVisitIds", List.of(vs.get(2).toString()))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.systemAmount").value(1000));
        }

        @Test
        @DisplayName("PRD-016 AC-2.5 新增日期須 ≥ 今天 + 1 → 422 INVALID_VISIT_DATE；方案區間（PRO）→ reason=PLAN_NOT_IN_RANGE；INVALID_VISIT_DATE 先於 PLAN_REQUIRED")
        void addDateRules() throws Exception {
            UUID orderId = order3();
            UUID modId = requested(orderId, List.of(visitIds(orderId, "PENDING").get(0)), List.of());
            tier(sitter, "FREE");
            quoteMod(orderId, modId, Map.of("addItems", List.of(addItem(today(), planA, 1, petA)), "surchargeAmount", 10, "adjustmentReason", "x"))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_VISIT_DATE"));
            tier(sitter, "PRO");
            jdbc.update("UPDATE service_plans SET start_date = ?, end_date = ? WHERE id = ?", d1(), d1().plusDays(3), planA);
            quoteMod(orderId, modId, Map.of("addItems", List.of(addItem(d1().plusDays(10), planA, 1, petA))))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.payload.reason").value("PLAN_NOT_IN_RANGE"));
        }

        @Test
        @DisplayName("PRD-024 AC-2.8 / PRD-016 AC-2.8 訂閱鎖定時依 FREE 計價（帶加減 → PLAN_REQUIRED），報價本身不擋 SUBSCRIPTION_LOCKED")
        void lockedUsesFree() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            UUID modId = requested(orderId, List.of(v1), List.of());
            jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", OffsetDateTime.now(clock).minusDays(3), sitter);
            quoteMod(orderId, modId, Map.of("removeVisitIds", List.of(v1.toString()), "surchargeAmount", 10, "adjustmentReason", "x"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
            quoteMod(orderId, modId, Map.of("removeVisitIds", List.of(v1.toString()))).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("ModificationLifecycleIntegrationTest")
    class Lifecycle {

        @Test
        @DisplayName("PRD-016 AC-2.6 保母拒絕 REQUESTED（原因必填）；保母拒絕 QUOTED → 409 MODIFICATION_STATE_CONFLICT；PRD-023 AC-1.6 飼主拒絕 QUOTED")
        void rejects() throws Exception {
            UUID orderId = order3();
            UUID modId = modificationId(modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")));
            rejectMod(orderId, modId, sitterToken(), Map.of()).andExpect(status().isBadRequest());
            rejectMod(orderId, modId, sitterToken(), Map.of("reason", "不行")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));
            assertThat(jdbc.queryForObject("SELECT reject_reason FROM modification_requests WHERE id = ?", String.class, modId)).isEqualTo("不行");
            assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'MODIFICATION_REJECTED_BY_SITTER'", owner)).isEqualTo(1);
            UUID q = sitterProposes(orderId, List.of(visitIds(orderId, "PENDING").get(0)), List.of());
            rejectMod(orderId, q, sitterToken(), Map.of("reason", "x")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MODIFICATION_STATE_CONFLICT"));
            rejectMod(orderId, q, ownerToken(), Map.of()).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT ended_at FROM modification_requests WHERE id = ?", OffsetDateTime.class, q)).isNotNull();
        }

        @Test
        @DisplayName("PRD-016 AC-3.1 飼主撤回；AC-3.2 非發起者 403、終態 409")
        void withdraws() throws Exception {
            UUID orderId = order3();
            UUID modId = modificationId(modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")));
            withdrawMod(orderId, modId, sitterToken()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            withdrawMod(orderId, modId, ownerToken()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
            withdrawMod(orderId, modId, ownerToken()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MODIFICATION_STATE_CONFLICT"));
        }

        @Test
        @DisplayName("PRD-016 AC-3.4 期限以時間判斷：已過 expires_at（排程未跑）→ 報價 / 拒絕 / 撤回 / 同意皆 409 DEADLINE_PASSED")
        void deadline() throws Exception {
            UUID orderId = order3();
            UUID req = modificationId(modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")));
            advance(Duration.ofHours(72));
            quoteMod(orderId, req, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.payload.deadline").value("MODIFICATION_EXPIRES_AT"));
            rejectMod(orderId, req, sitterToken(), Map.of("reason", "x")).andExpect(status().isConflict());
            withdrawMod(orderId, req, ownerToken()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEADLINE_PASSED"));
        }
    }

    @Nested
    @DisplayName("ModificationQueryIntegrationTest")
    class Query {

        @Test
        @DisplayName("PRD-016 AC-3.5 歷史 created_at 倒序、空清單；協商頁 allowedActions 依角色 / 狀態，逾期全部隱藏")
        void historyAndActions() throws Exception {
            UUID orderId = order3();
            mvc.perform(getAs("/api/orders/{o}/modifications", ownerToken(), orderId)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
            UUID first = modificationId(modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")));
            withdrawMod(orderId, first, ownerToken());
            advance(Duration.ofMinutes(1));
            UUID second = modificationId(modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "y")));
            JsonNode list = body(mvc.perform(getAs("/api/orders/{o}/modifications", sitterToken(), orderId)));
            assertThat(list.get(0).get("modificationId").asString()).isEqualTo(second.toString());
            JsonNode asSitter = body(mvc.perform(getAs("/api/orders/{o}/modifications/{m}", sitterToken(), orderId, second)));
            assertThat(asSitter.get("allowedActions").toString()).contains("QUOTE", "REJECT").doesNotContain("WITHDRAW");
            JsonNode asOwner = body(mvc.perform(getAs("/api/orders/{o}/modifications/{m}", ownerToken(), orderId, second)));
            assertThat(asOwner.get("allowedActions").toString()).contains("WITHDRAW");
            advance(Duration.ofHours(73));
            JsonNode expired = body(mvc.perform(getAs("/api/orders/{o}/modifications/{m}", ownerToken(), orderId, second)));
            assertThat(expired.get("allowedActions").size()).isZero();
            mvc.perform(getAs("/api/orders/{o}/modifications", bearer(admin, "ADMIN"), orderId)).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("ModificationSettlementIntegrationTest")
    class Settlement {

        @Test
        @DisplayName("PRD-023 AC-1.1 差額 0 立即生效：SETTLED、減少目標取消、新增 MODIFICATION order_items 與行程、total = new_total、MODIFICATION_ACCEPTED")
        void zeroDiff() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            UUID modId = sitterProposes(orderId, List.of(v1), List.of(addItem(d1().plusDays(9), planA, 1, petA)));
            accept(orderId, modId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"))
                    .andExpect(jsonPath("$.totalAmount").value(1500));
            assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, v1)).isEqualTo("CANCELLED");
            assertThat(count("SELECT count(*) FROM order_items WHERE order_id = ? AND source = 'MODIFICATION'", orderId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM visits WHERE order_id = ? AND service_date = ? AND status = 'PENDING'", orderId,
                    java.sql.Date.valueOf(d1().plusDays(9)))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT payload->>'branch' FROM order_logs WHERE order_id = ? AND action_type = 'MODIFICATION_ACCEPTED'",
                    String.class, orderId)).isEqualTo("SETTLED");
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'MODIFICATION_SETTLED'", orderId)).isEqualTo(2);
        }

        @Test
        @DisplayName("PRD-023 AC-1.2 差額 > 0 建補款（due_at = now + 72h、帳號快照）；AC-2.1 補款確認即生效（T7）；AC-2.2 首次付款不被覆寫")
        void supplementFlow() throws Exception {
            UUID orderId = order3();
            UUID modId = sitterProposes(orderId, List.of(), List.of(addItem(d1().plusDays(9), planA, 1, petA)));
            JsonNode r = body(accept(orderId, modId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_SUPPLEMENT")));
            UUID pay = UUID.fromString(r.get("payment").get("paymentId").asString());
            Map<String, Object> p = jdbc.queryForMap("SELECT * FROM payments WHERE id = ?", pay);
            assertThat(p.get("purpose")).isEqualTo("SUPPLEMENT");
            assertThat(p.get("amount")).isEqualTo(500);
            assertThat(p.get("payee_account_snapshot")).isNotNull();
            assertThat(Duration.between(OffsetDateTime.now(clock).toInstant(), ((java.sql.Timestamp) p.get("due_at")).toInstant()).toHours()).isBetween(71L, 72L);
            UUID sub = submitProof(orderId, pay, ownerToken());
            verify(orderId, pay, sub, sitterToken()).andExpect(status().isOk()).andExpect(jsonPath("$.modification.status").value("SETTLED"));
            assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Integer.class, orderId)).isEqualTo(2000);
            assertThat(count("SELECT count(*) FROM visits WHERE order_id = ? AND status = 'PENDING'", orderId)).isEqualTo(4);
            assertThat(count("SELECT count(*) FROM payments WHERE order_id = ? AND purpose = 'INITIAL' AND status = 'VERIFIED'", orderId)).isEqualTo(1);
        }

        @Test
        @DisplayName("PRD-023 AC-1.3 差額 < 0 建退款（無期限、無快照、agreed_*）；AC-3.1 退款確認即結清（T10）；PRD-007 AC-1.2 退款由保母上傳、飼主確認")
        void refundFlow() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            UUID modId = sitterProposes(orderId, List.of(v1), List.of());
            JsonNode r = body(accept(orderId, modId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_REFUND")));
            UUID pay = UUID.fromString(r.get("payment").get("paymentId").asString());
            Map<String, Object> p = jdbc.queryForMap("SELECT * FROM payments WHERE id = ?", pay);
            assertThat(p.get("direction")).isEqualTo("REFUND");
            assertThat(p.get("due_at")).isNull();
            assertThat(p.get("payee_account_snapshot")).isNull();
            assertThat(jdbc.queryForMap("SELECT agreed_net_paid, agreed_diff FROM modification_requests WHERE id = ?", modId))
                    .containsEntry("agreed_net_paid", 1500).containsEntry("agreed_diff", -500);
            upload(orderId, pay, ownerToken(), key(), PNG).andExpect(status().isForbidden());
            UUID sub = submitProof(orderId, pay, sitterToken());
            mvc.perform(postJson("/api/orders/{o}/payments/{p}/reject", ownerToken(), key(),
                    Map.of("submissionId", sub.toString(), "reason", "x"), orderId, pay)).andExpect(status().isForbidden());
            verify(orderId, pay, sub, sitterToken()).andExpect(status().isForbidden());
            verify(orderId, pay, sub, ownerToken()).andExpect(status().isOk());
            assertThat(modStatus(modId)).isEqualTo("SETTLED");
            assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Integer.class, orderId)).isEqualTo(1000);
        }

        @Test
        @DisplayName("PRD-023 AC-3.2 退款前不可確認：UNPAID 退款飼主確認 → 409 PAYMENT_STATE_CONFLICT；退款無逾時：UNPAID / SUBMITTED 超過 30 天排程後變更仍 AWAITING_REFUND、payment 不變")
        void refundNotConfirmableBeforeSubmitAndNeverExpires() throws Exception {
            UUID orderA = order3();
            UUID modA = sitterProposes(orderA, List.of(visitIds(orderA, "PENDING").get(0)), List.of());
            UUID payA = UUID.fromString(body(accept(orderA, modA).andExpect(status().isOk())).get("payment").get("paymentId").asString());
            verify(orderA, payA, UUID.randomUUID(), ownerToken()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payA)).isEqualTo("UNPAID");

            UUID orderB = confirmedOrder(d1().plusDays(3), d1().plusDays(4), d1().plusDays(5));
            UUID modB = sitterProposes(orderB, List.of(visitIds(orderB, "PENDING").get(0)), List.of());
            UUID payB = UUID.fromString(body(accept(orderB, modB).andExpect(status().isOk())).get("payment").get("paymentId").asString());
            submitProof(orderB, payB, sitterToken());

            advance(Duration.ofDays(31));
            for (String path : List.of("payments/reminder", "payments/initial-expiry", "payments/escalation",
                    "modifications/expiry", "modifications/supplement-expiry")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/internal/cron/" + path)
                        .header("X-Internal-Secret", "local-secret-123")).andExpect(status().isOk());
            }
            assertThat(modStatus(modA)).isEqualTo("AWAITING_REFUND");
            assertThat(modStatus(modB)).isEqualTo("AWAITING_REFUND");
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payA)).isEqualTo("UNPAID");
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payB)).isEqualTo("SUBMITTED");
            assertThat(count("SELECT count(*) FROM payments WHERE order_id IN (?, ?) AND status = 'VOID'", orderA, orderB)).isZero();
        }

        @Test
        @DisplayName("PRD-023 AC-1.4 同意時減少目標已開始 → 409 VISIT_ALREADY_STARTED，整筆不生效")
        void acceptWithStartedTarget() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            UUID modId = sitterProposes(orderId, List.of(v1), List.of());
            startVisit(v1);
            accept(orderId, modId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_ALREADY_STARTED"));
            assertThat(modStatus(modId)).isEqualTo("QUOTED");
            assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", orderId)).isEqualTo(1);
        }

        @Test
        @DisplayName("PRD-023 AC-3.3 CONFIRMED 整筆取消退款結清 → O10，另寫 ORDER_CANCELLED_BY_MODIFICATION（modificationId / initiatedBy）")
        void cancelAllRefundThenO10() throws Exception {
            UUID orderId = order3();
            UUID modId = modificationId(modify(orderId, sitterToken(), Map.of("type", "CANCEL_ALL", "reason", "生病")));
            JsonNode r = body(accept(orderId, modId).andExpect(jsonPath("$.status").value("AWAITING_REFUND")));
            UUID pay = UUID.fromString(r.get("payment").get("paymentId").asString());
            verify(orderId, pay, submitProof(orderId, pay, sitterToken()), ownerToken()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderStatus").value("CANCELLED"));
            Map<String, Object> o = jdbc.queryForMap("SELECT cancelled_by, cancel_reason_code FROM orders WHERE id = ?", orderId);
            assertThat(o).containsEntry("cancelled_by", "SYSTEM").containsEntry("cancel_reason_code", "MODIFICATION");
            assertThat(jdbc.queryForMap("SELECT payload->>'modificationId' AS m, payload->>'initiatedBy' AS i FROM order_logs "
                    + "WHERE order_id = ? AND action_type = 'ORDER_CANCELLED_BY_MODIFICATION'", orderId))
                    .containsEntry("m", modId.toString()).containsEntry("i", "SITTER");
        }

        @Test
        @DisplayName("PRD-023 AC-3.4 無差額的整筆取消立即 O10")
        void cancelAllZeroDiff() throws Exception {
            UUID orderId = order3();
            jdbc.update("INSERT INTO payments (id, order_id, direction, purpose, method, amount, status, adjustment_basis, verified_at) "
                    + "VALUES (?, ?, 'REFUND', 'ADMIN_ADJUSTMENT', 'ADMIN_ADJUSTMENT', 1500, 'VERIFIED', '測試：已收淨額歸零', now())", UUID.randomUUID(), orderId);
            UUID modId = modificationId(modify(orderId, sitterToken(), Map.of("type", "CANCEL_ALL", "reason", "x")));
            accept(orderId, modId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"))
                    .andExpect(jsonPath("$.orderStatus").value("CANCELLED"));
            assertThat(logActions(orderId)).contains("ORDER_CANCELLED_BY_MODIFICATION");
        }

        @Test
        @DisplayName("PRD-023 AC-3.5 IN_PROGRESS 整筆取消結清後主狀態不變，completion_basis_at 重算（行程全結束 → 非 NULL）")
        void inProgressCancelAll() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            finishVisit(v1, true);
            assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");
            UUID modId = modificationId(modify(orderId, sitterToken(), Map.of("type", "CANCEL_ALL", "reason", "x")));
            JsonNode r = body(accept(orderId, modId));
            UUID pay = UUID.fromString(r.get("payment").get("paymentId").asString());
            verify(orderId, pay, submitProof(orderId, pay, sitterToken()), ownerToken()).andExpect(status().isOk());
            assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");
            assertThat(jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId)).isNotNull();
        }

        @Test
        @DisplayName("PRD-023 AC-2.3 新增日期在補款確認時已過，仍建立行程（PENDING，交殭屍清理）")
        void pastAddedDateStillCreated() throws Exception {
            UUID orderId = order3();
            LocalDate added = today().plusDays(1);
            UUID modId = sitterProposes(orderId, List.of(), List.of(addItem(added, planA, 1, petA)));
            JsonNode r = body(accept(orderId, modId));
            UUID pay = UUID.fromString(r.get("payment").get("paymentId").asString());
            UUID sub = submitProof(orderId, pay, ownerToken());
            advance(Duration.ofDays(2));
            verify(orderId, pay, sub, sitterToken()).andExpect(status().isOk());
            assertThat(count("SELECT count(*) FROM visits WHERE order_id = ? AND service_date = ? AND status = 'PENDING'", orderId,
                    java.sql.Date.valueOf(added))).isEqualTo(1);
        }

        @Test
        @DisplayName("PRD-023 AC-1.7 同意越權與狀態：SITTER → ROLE_MISMATCH、他人 → 403、REQUESTED → 409 MODIFICATION_STATE_CONFLICT")
        void acceptAccess() throws Exception {
            UUID orderId = order3();
            UUID req = modificationId(modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")));
            mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", sitterToken(), key(), Map.of(), orderId, req))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
            mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", bearer(stranger, "OWNER"), key(), Map.of(), orderId, req))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            accept(orderId, req).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODIFICATION_STATE_CONFLICT"));
        }
    }

    @Nested
    @DisplayName("ModificationConcurrencyIntegrationTest")
    class Concurrency {

        /**
         * 訂單鎖序列化後只有兩種合法順序（PRD-016 T5 / §4.4 / AC-3.1：OWNER 發起者可撤回 {@code REQUESTED} 與 {@code QUOTED}）：
         * (a) 撤回先 → 報價依新狀態重驗 409 {@code MODIFICATION_STATE_CONFLICT}；
         * (b) 報價先（→ {@code QUOTED}）→ 撤回 {@code QUOTED} 合法，兩者皆 200。
         * AC-3.6「恰有一個成功」只描述 (a)；(b) 依 T5 為合法結果（判斷依據見 S2 回報）。兩種順序最終皆 {@code WITHDRAWN}，
         * 且 {@code order_logs} 只記成功的轉移、不重複。
         */
        @Test
        @DisplayName("PRD-016 AC-3.6 撤回 vs 報價兩執行緒：訂單鎖序列化；撤回先 → 報價 409，報價先 → 撤回 QUOTED 合法（T5）；最終 WITHDRAWN、log 只記成功者")
        void withdrawVsQuote() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            UUID modId = modificationId(modify(orderId, ownerToken(), change("x", List.of(v1), List.of())));
            int[] codes = race(() -> withdrawMod(orderId, modId, ownerToken()),
                    () -> quoteMod(orderId, modId, Map.of("removeVisitIds", List.of(v1.toString()))));
            assertThat(codes[0]).as("撤回在兩種順序下都成功").isEqualTo(200);
            assertThat(codes[1]).as("報價：撤回先 → 409；報價先 → 200").isIn(200, 409);
            String status = jdbc.queryForObject("SELECT status FROM modification_requests WHERE id = ?", String.class, modId);
            assertThat(status).isEqualTo("WITHDRAWN");
            List<String> actions = logActions(orderId);
            assertThat(actions.stream().filter("MODIFICATION_WITHDRAWN"::equals).count()).isEqualTo(1);
            long quoted = actions.stream().filter("MODIFICATION_QUOTED"::equals).count();
            if (codes[1] == 200) {
                assertThat(quoted).isEqualTo(1);
                assertThat(actions.indexOf("MODIFICATION_QUOTED")).isLessThan(actions.indexOf("MODIFICATION_WITHDRAWN"));
            } else {
                assertThat(quoted).isZero();
            }
            // 撤回不改行程、訂單狀態（PRD-016 AC-3.1）
            assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, v1)).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("PRD-023 AC-1.5 同意 vs 開始被減少的行程：開始先 → 同意 409 VISIT_ALREADY_STARTED；同意先 → 開始失敗；兩者不同時成立")
        void acceptVsStart() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            UUID modId = sitterProposes(orderId, List.of(v1), List.of());
            makeStartable(v1);
            int[] codes = race(() -> accept(orderId, modId),
                    () -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders/{o}/visits/{v}/start", orderId, v1)
                            .header("Authorization", sitterToken()).header("Idempotency-Key", key())));
            assertThat(codes[0] == 200 ^ codes[1] == 200).isTrue();
            String visit = jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, v1);
            assertThat(visit).isEqualTo(codes[0] == 200 ? "CANCELLED" : "IN_PROGRESS");
        }
    }

    @Nested
    @DisplayName("ModificationVisitStartConcurrencyIntegrationTest")
    class StartVsModification {

        private ResultActions startVisit(UUID orderId, UUID visitId) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders/{o}/visits/{v}/start", orderId, visitId)
                    .header("Authorization", sitterToken()).header("Idempotency-Key", key()));
        }

        private int lockWaiters() {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()", Integer.class);
            return n == null ? 0 : n;
        }

        private void awaitWaiters(int n) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (lockWaiters() < n) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("waiters < " + n);
                }
                Thread.sleep(10);
            }
        }

        /**
         * 兩條執行緒依指定順序排隊取同一訂單鎖：先以獨立連線持有訂單列，first 送出並確認已在鎖上等待後才送 second，
         * 再放鎖（PostgreSQL 依排隊順序授予列鎖），讓兩種加鎖順序都可重現。
         */
        private int[] ordered(java.util.concurrent.Callable<ResultActions> first, java.util.concurrent.Callable<ResultActions> second,
                              UUID orderId) throws Exception {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try (java.sql.Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
                    ps.setObject(1, orderId);
                    ps.executeQuery();
                }
                int base = lockWaiters();
                java.util.concurrent.Future<Integer> fa = pool.submit(() -> first.call().andReturn().getResponse().getStatus());
                awaitWaiters(base + 1);
                java.util.concurrent.Future<Integer> fb = pool.submit(() -> second.call().andReturn().getResponse().getStatus());
                awaitWaiters(base + 2);
                c.rollback();
                return new int[]{fa.get(30, java.util.concurrent.TimeUnit.SECONDS), fb.get(30, java.util.concurrent.TimeUnit.SECONDS)};
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("PRD-016 EX-13 保母開始行程先取得鎖 → 飼主送出含該行程的變更 409 VISIT_ALREADY_STARTED，無變更列、無 MODIFICATION_REQUESTED")
        void startFirstThenOwnerModification() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            makeStartable(v1);
            int[] codes = ordered(() -> startVisit(orderId, v1),
                    () -> modify(orderId, ownerToken(), change("改天", List.of(v1), List.of())), orderId);
            assertThat(codes).containsExactly(200, 409);
            assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, v1)).isEqualTo("IN_PROGRESS");
            assertThat(count("SELECT count(*) FROM modification_requests WHERE order_id = ?", orderId)).isZero();
            assertThat(logActions(orderId)).doesNotContain("MODIFICATION_REQUESTED");
        }

        @Test
        @DisplayName("PRD-016 EX-13 保母送出含該行程的變更先取得鎖（QUOTED）→ 開始行程照常 200（變更未同意前行程仍 PENDING）；之後飼主同意 → 409 VISIT_ALREADY_STARTED")
        void sitterModificationFirstThenStart() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            makeStartable(v1);
            int[] codes = ordered(() -> modify(orderId, sitterToken(), change("調整", List.of(v1), List.of())),
                    () -> startVisit(orderId, v1), orderId);
            assertThat(codes).containsExactly(201, 200);
            UUID modId = jdbc.queryForObject("SELECT id FROM modification_requests WHERE order_id = ?", UUID.class, orderId);
            assertThat(modStatus(modId)).isEqualTo("QUOTED");
            assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, v1)).isEqualTo("IN_PROGRESS");
            accept(orderId, modId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_ALREADY_STARTED"));
            assertThat(modStatus(modId)).isEqualTo("QUOTED");
        }

        @Test
        @DisplayName("PRD-016 EX-13 兩執行緒同時起跑（不指定順序）：只會出現兩種合法結果之一，且結果與行程 / 變更狀態一致")
        void freeRace() throws Exception {
            UUID orderId = order3();
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            makeStartable(v1);
            int[] codes = race(() -> startVisit(orderId, v1),
                    () -> modify(orderId, ownerToken(), change("改天", List.of(v1), List.of())));
            assertThat(codes[0]).as("開始行程兩種順序都成功").isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, v1)).isEqualTo("IN_PROGRESS");
            int mods = count("SELECT count(*) FROM modification_requests WHERE order_id = ?", orderId);
            if (codes[1] == 201) {
                assertThat(mods).isEqualTo(1);
            } else {
                assertThat(codes[1]).isEqualTo(409);
                assertThat(mods).isZero();
            }
        }
    }

    @Nested
    @DisplayName("ModificationIdempotencyIntegrationTest")
    class Idempotency {

        @Test
        @DisplayName("PRD-016 AC-3.7 發起同 key 回放；PRD-023 AC-1.8 同意同 key 回放、換 key 再同意 → 409，差額 payment 只有一筆")
        void replay() throws Exception {
            UUID orderId = order3();
            String k = key();
            Map<String, Object> b = change("x", List.of(), List.of(addItem(d1().plusDays(9), planA, 1, petA)));
            String first = mvc.perform(postJson("/api/orders/{o}/modifications", sitterToken(), k, b, orderId))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String again = mvc.perform(postJson("/api/orders/{o}/modifications", sitterToken(), k, b, orderId))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(again)).isEqualTo(json.readTree(first));
            UUID modId = UUID.fromString(json.readTree(first).get("modificationId").asString());
            String ak = key();
            mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), ak, Map.of(), orderId, modId)).andExpect(status().isOk());
            mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), ak, Map.of(), orderId, modId)).andExpect(status().isOk());
            accept(orderId, modId).andExpect(status().isConflict());
            assertThat(count("SELECT count(*) FROM payments WHERE modification_request_id = ?", modId)).isEqualTo(1);
        }
    }
}
