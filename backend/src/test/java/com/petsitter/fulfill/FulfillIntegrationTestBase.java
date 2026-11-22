package com.petsitter.fulfill;

import com.petsitter.order.OrderIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import testsupport.fulfill.FakeMediaStorageService;
import testsupport.fulfill.FulfillTestConfig;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * SD-FULFILL 整合測試共用基礎（§10.1）：沿用 SD-ORDER 基礎（Testcontainers、真 JWT filter chain、可設定 Clock），
 * 另以 {@link FakeMediaStorageService} 取代 GCS。訂單一律走真 E1 → E6 → E11 → E10 建立（O1 時建立行程與檢核項）。
 */
@Import(FulfillTestConfig.class)
public abstract class FulfillIntegrationTestBase extends OrderIntegrationTestBase {

    protected static final String VISIT = "/api/orders/{o}/visits/{v}";

    @Autowired
    protected FakeMediaStorageService storage;

    @BeforeEach
    void fulfillSetUp() {
        storage.reset();
    }

    // ------------------------------------------------------------------ 時間

    protected static OffsetDateTime taipei(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(TAIPEI).toOffsetDateTime();
    }

    protected void freeze(OffsetDateTime at) {
        clock.freezeAt(at.toInstant());
    }

    protected void freezeUtc(String iso) {
        clock.freezeAt(OffsetDateTime.parse(iso).toInstant());
    }

    // ------------------------------------------------------------------ 資料

    protected void defaultTasks(UUID planId, String jsonArray) {
        jdbc.update("UPDATE service_plans SET default_tasks = ?::jsonb WHERE id = ?", jsonArray, planId);
    }

    protected List<UUID> visits(UUID orderId) {
        return jdbc.queryForList("SELECT id FROM visits WHERE order_id = ? ORDER BY service_date, sequence_in_day", UUID.class, orderId);
    }

    protected String visitStatus(UUID visitId) {
        return jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, visitId);
    }

    protected LocalDate serviceDate(UUID visitId) {
        return jdbc.queryForObject("SELECT service_date FROM visits WHERE id = ?", LocalDate.class, visitId);
    }

    /** 直接改訂單主狀態（造前置狀態），一併滿足 ck_orders_disputed / completed / cancelled。 */
    protected void setOrderStatus(UUID orderId, String status) {
        jdbc.update("UPDATE orders SET status = ?, "
                + "disputed_at = CASE WHEN ? = 'DISPUTED' THEN COALESCE(disputed_at, now()) ELSE disputed_at END, "
                + "completed_at = CASE WHEN ? = 'COMPLETED' THEN now() ELSE NULL END, "
                + "completed_by = CASE WHEN ? = 'COMPLETED' THEN 'ADMIN' ELSE NULL END, "
                + "cancelled_at = CASE WHEN ? = 'CANCELLED' THEN now() ELSE NULL END, "
                + "cancelled_by = CASE WHEN ? = 'CANCELLED' THEN 'ADMIN' ELSE NULL END, "
                + "cancel_reason_code = CASE WHEN ? = 'CANCELLED' THEN 'ADMIN_MEDIATED' ELSE NULL END "
                + "WHERE id = ?", status, status, status, status, status, status, status, orderId);
    }

    /** OffsetDateTime → API 回應格式（UTC、秒位一律輸出）。 */
    protected static String iso(OffsetDateTime t) {
        return t.withOffsetSameInstant(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** 直接把行程設為 IN_PROGRESS / DONE（造前置狀態；正式路徑由各自測試驗證）。 */
    protected void forceVisit(UUID visitId, String status, OffsetDateTime startedAt, OffsetDateTime completedAt, String source) {
        jdbc.update("UPDATE visits SET status = ?, started_at = ?, completed_at = ?, completion_source = ? WHERE id = ?",
                status, startedAt == null ? null : java.sql.Timestamp.from(startedAt.toInstant()),
                completedAt == null ? null : java.sql.Timestamp.from(completedAt.toInstant()), source, visitId);
    }

    protected void forceClosed(UUID visitId, OffsetDateTime at) {
        jdbc.update("UPDATE visits SET status = 'CLOSED_BY_SYSTEM', closed_at = ? WHERE id = ?", java.sql.Timestamp.from(at.toInstant()), visitId);
    }

    protected void forceCancelled(UUID visitId, OffsetDateTime at) {
        jdbc.update("UPDATE visits SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?", java.sql.Timestamp.from(at.toInstant()), visitId);
    }

    protected void openAdminTask(UUID orderId, String status) {
        jdbc.update("INSERT INTO admin_tasks (id, type, order_id, status, opened_by_role, resolved_at, resolution) VALUES (?, 'SERVICE_DISPUTE', ?, ?, 'OWNER', "
                        + ("RESOLVED".equals(status) ? "now(), 'ADMIN_MEDIATED'" : "NULL, NULL") + ")",
                UUID.randomUUID(), orderId, status);
    }

    protected int logCount(UUID orderId, String action) {
        return count("SELECT count(*) FROM order_logs WHERE order_id = ? AND action_type = ?", orderId, action);
    }

    protected String adminToken() {
        return bearer(admin, "ADMIN");
    }

    protected String strangerSitterToken() {
        UUID other = fx.user("sitter2-" + UUID.randomUUID() + "@test.com", "SITTER");
        return bearer(other, "SITTER");
    }

    protected String strangerOwnerToken() {
        return bearer(stranger, "OWNER");
    }

    // ------------------------------------------------------------------ 請求

    protected MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token, String key) {
        if (token != null) {
            b.header("Authorization", token);
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return b;
    }

    protected ResultActions start(UUID orderId, UUID visitId, String token, String key) throws Exception {
        return mvc.perform(auth(post(VISIT + "/start", orderId, visitId), token, key));
    }

    protected ResultActions complete(UUID orderId, UUID visitId, String token, String key) throws Exception {
        return mvc.perform(auth(post(VISIT + "/complete", orderId, visitId), token, key));
    }

    protected ResultActions putJson(String url, String token, Object body, Object... vars) throws Exception {
        return mvc.perform(auth(put(url, vars).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : json.writeValueAsString(body)), token, null));
    }

    protected ResultActions deleteAs(String url, String token, Object... vars) throws Exception {
        return mvc.perform(auth(delete(url, vars), token, null));
    }

    protected ResultActions rename(UUID orderId, UUID visitId, String token, Object name) throws Exception {
        return putJson(VISIT + "/name", token, name instanceof String s ? Map.of("name", s) : name, orderId, visitId);
    }

    protected JsonNode visitList(UUID orderId, String token) throws Exception {
        return body(mvc.perform(getAs("/api/orders/{o}/visits", token, orderId)));
    }

    protected ResultActions addItem(UUID orderId, UUID visitId, String token, String key, Object title) throws Exception {
        return mvc.perform(auth(post(VISIT + "/checklist/items", orderId, visitId).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(title instanceof Map<?, ?> ? title : Map.of("title", title))), token, key));
    }

    protected ResultActions check(UUID orderId, UUID visitId, UUID itemId, String token, Object checked) throws Exception {
        return putJson(VISIT + "/checklist/items/{i}/check", token, Map.of("checked", checked), orderId, visitId, itemId);
    }

    protected List<UUID> itemIds(UUID visitId) {
        return jdbc.queryForList("SELECT id FROM visit_checklist_items WHERE visit_id = ? ORDER BY sort_order", UUID.class, visitId);
    }

    protected List<String> itemTitles(UUID visitId) {
        return jdbc.queryForList("SELECT title FROM visit_checklist_items WHERE visit_id = ? ORDER BY sort_order", String.class, visitId);
    }

    protected void advanceTo(OffsetDateTime t) {
        clock.advance(Duration.between(OffsetDateTime.now(clock), t));
    }
}
