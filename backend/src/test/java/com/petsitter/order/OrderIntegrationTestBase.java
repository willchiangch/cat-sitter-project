package com.petsitter.order;

import com.petsitter.application.dto.UpdateSitterPaymentInfoRequest;
import com.petsitter.application.service.PaymentService;
import com.petsitter.infrastructure.security.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import testsupport.order.OrderFixtures;
import testsupport.order.OrderTestConfig;
import testsupport.sharedmech.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 訂單整合測試共用基礎（SD-ORDER §10.1）：Testcontainers PostgreSQL（單例，所有子類共用同一 Spring context）、
 * 真 Flyway、真 JWT + 真 SecurityConfig filter chain（不用 {@code @WithMockUser}）、可設定時間的 Clock。
 * 每個測試前 {@code DatabaseCleanupListener} 清空所有表。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(OrderTestConfig.class)
public abstract class OrderIntegrationTestBase {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    protected static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    protected static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JwtUtils jwtUtils;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected PaymentService paymentService;

    protected OrderFixtures fx;

    /** 預設世界：保母（KYC 通過、公開、已設收款帳號、PRO）、飼主、兩隻毛孩、兩個方案。 */
    protected UUID sitter;
    protected UUID owner;
    protected UUID stranger;
    protected UUID admin;
    protected UUID petA;
    protected UUID petB;
    protected UUID planA;
    protected UUID planB;

    @BeforeEach
    void baseSetUp() {
        clock.reset();
        fx = new OrderFixtures(jdbc);
        sitter = fx.user("sitter-" + UUID.randomUUID() + "@test.com", "SITTER");
        fx.profile(sitter, "SITTER", "VERIFIED");
        owner = fx.user("owner-" + UUID.randomUUID() + "@test.com", "OWNER");
        fx.profile(owner, "CLIENT", "UNVERIFIED");
        stranger = fx.user("stranger-" + UUID.randomUUID() + "@test.com", "OWNER");
        admin = fx.user("admin-" + UUID.randomUUID() + "@test.com", "ADMIN");
        petA = fx.pet(owner, "咪咪");
        petB = fx.pet(owner, "阿福");
        planA = fx.plan(sitter, "到府餵食", 500);
        planB = fx.plan(sitter, "陪玩", 800);
        tier(sitter, "PRO");
        payout(sitter);
    }

    @AfterEach
    void baseTearDown() {
        clock.reset();
    }

    // ------------------------------------------------------------------ 世界設定

    protected void tier(UUID sitterId, String tier) {
        jdbc.update("DELETE FROM subscriptions WHERE sitter_id = ?", sitterId);
        jdbc.update("INSERT INTO subscriptions (id, sitter_id, plan_tier) VALUES (?, ?, ?)", UUID.randomUUID(), sitterId, tier);
    }

    protected void payout(UUID sitterId) {
        paymentService.updateSitterPaymentInfo(sitterId, new UpdateSitterPaymentInfoRequest("812", "台北分行", "1234567890123", "王小明"));
    }

    protected LocalDate today() {
        return OffsetDateTime.now(clock).atZoneSameInstant(TAIPEI).toLocalDate();
    }

    protected void advance(Duration d) {
        clock.advance(d);
    }

    // ------------------------------------------------------------------ token 與請求

    protected String bearer(UUID userId, String role) {
        return "Bearer " + jwtUtils.generateToken(userId + "@t.test", role,
                Map.<String, Object>of("userId", userId.toString()), 3_600_000L);
    }

    protected String ownerToken() {
        return bearer(owner, "OWNER");
    }

    protected String sitterToken() {
        return bearer(sitter, "SITTER");
    }

    protected MockHttpServletRequestBuilder postJson(String url, String token, String key, Object body, Object... vars) {
        MockHttpServletRequestBuilder b = post(url, vars).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : json.writeValueAsString(body));
        if (token != null) {
            b.header("Authorization", token);
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return b;
    }

    protected MockHttpServletRequestBuilder getAs(String url, String token, Object... vars) {
        MockHttpServletRequestBuilder b = get(url, vars);
        if (token != null) {
            b.header("Authorization", token);
        }
        return b;
    }

    protected JsonNode body(ResultActions r) throws Exception {
        MvcResult res = r.andReturn();
        String s = res.getResponse().getContentAsString();
        return s.isEmpty() ? json.readTree("{}") : json.readTree(s);
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ 業務步驟

    protected Map<String, Object> bookingBody(UUID sitterId, List<LocalDate> dates, int daily, int expected, UUID... pets) {
        return Map.of("sitterId", sitterId.toString(),
                "groups", List.of(Map.of("planId", planA.toString(), "dates", dates.stream().map(LocalDate::toString).toList(),
                        "dailyCount", daily, "petIds", java.util.Arrays.stream(pets).map(UUID::toString).toList())),
                "expectedAmount", expected);
    }

    /** E1：planA × dates × 1 次，回傳 orderId。 */
    protected UUID book(LocalDate... dates) throws Exception {
        JsonNode r = body(mvc.perform(postJson("/api/orders", ownerToken(), key(),
                bookingBody(sitter, List.of(dates), 1, 500 * dates.length, petA))));
        return UUID.fromString(r.get("orderId").asString());
    }

    /** E6 原價報價，回傳 INITIAL paymentId。 */
    protected UUID quote(UUID orderId) throws Exception {
        int base = jdbc.queryForObject("SELECT base_amount FROM orders WHERE id = ?", Integer.class, orderId);
        JsonNode r = body(mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), key(),
                Map.of("expectedAmount", base), orderId)));
        return UUID.fromString(r.get("payment").get("paymentId").asString());
    }

    protected ResultActions upload(UUID orderId, UUID paymentId, String token, String key, byte[] file) throws Exception {
        var b = multipart("/api/orders/{o}/payments/{p}/submissions", orderId, paymentId)
                .file(new MockMultipartFile("file", "proof.png", "image/png", file))
                .param("disclaimerAgreed", "true")
                .param("lastFive", "12345")
                .header("Authorization", token);
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return mvc.perform(b);
    }

    /** E11 + 回傳 submissionId。 */
    protected UUID submitProof(UUID orderId, UUID paymentId, String token) throws Exception {
        return UUID.fromString(body(upload(orderId, paymentId, token, key(), PNG)).get("submissionId").asString());
    }

    protected ResultActions verify(UUID orderId, UUID paymentId, UUID submissionId, String token) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/payments/{p}/verify", token, key(),
                Map.of("submissionId", submissionId.toString()), orderId, paymentId));
    }

    /** 預約 → 報價 → 上傳 → 確認：回傳 CONFIRMED 訂單 id。 */
    protected UUID confirmedOrder(LocalDate... dates) throws Exception {
        UUID orderId = book(dates);
        UUID paymentId = quote(orderId);
        UUID sub = submitProof(orderId, paymentId, ownerToken());
        verify(orderId, paymentId, sub, sitterToken());
        return orderId;
    }

    protected String orderStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected List<String> logActions(UUID orderId) {
        return jdbc.queryForList("SELECT action_type FROM order_logs WHERE order_id = ? ORDER BY created_at, id", String.class, orderId);
    }

    @Autowired
    protected javax.sql.DataSource dataSource;

    /** 以獨立連線開交易鎖住訂單列 {@code hold} 後才釋放（SD-ORDER §10.1「鎖逾時」）；回傳可 join 的 future。 */
    protected java.util.concurrent.CompletableFuture<Void> holdOrderLock(UUID orderId, Duration hold) throws Exception {
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Void> f = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (java.sql.Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
                    ps.setObject(1, orderId);
                    ps.executeQuery();
                }
                locked.countDown();
                Thread.sleep(hold.toMillis());
                c.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrueWithin(locked);
        return f;
    }

    private static void assertTrueWithin(java.util.concurrent.CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("lock not acquired");
        }
    }

    /** 兩條執行緒以 CountDownLatch 同時起跑、各自獨立請求真打 API（SD-ORDER §10.1「併發」）；回傳兩個狀態碼。 */
    protected int[] race(java.util.concurrent.Callable<org.springframework.test.web.servlet.ResultActions> a,
                         java.util.concurrent.Callable<org.springframework.test.web.servlet.ResultActions> b) throws Exception {
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Integer> fa = pool.submit(() -> {
                start.await();
                return a.call().andReturn().getResponse().getStatus();
            });
            java.util.concurrent.Future<Integer> fb = pool.submit(() -> {
                start.await();
                return b.call().andReturn().getResponse().getStatus();
            });
            start.countDown();
            return new int[]{fa.get(30, java.util.concurrent.TimeUnit.SECONDS), fb.get(30, java.util.concurrent.TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 行程（SD-FULFILL 過渡端點）

    protected List<UUID> visitIds(UUID orderId, String status) {
        return jdbc.queryForList("SELECT id FROM visits WHERE order_id = ? AND status = ? ORDER BY service_date, sequence_in_day",
                UUID.class, orderId, status);
    }

    protected void startVisit(UUID visitId) throws Exception {
        mvc.perform(post("/api/visits/{id}/start", visitId).header("Authorization", sitterToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    protected void endVisit(UUID visitId) throws Exception {
        mvc.perform(post("/api/visits/{id}/end", visitId).header("Authorization", sitterToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    protected void submitCareLog(UUID visitId) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/visits/{id}/report", visitId)
                        .header("Authorization", sitterToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"ok\",\"version\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(post("/api/visits/{id}/report/submit", visitId).header("Authorization", sitterToken())
                        .header("Idempotency-Key", key()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /** 開始並結束（DONE）行程；{@code withLog} 時送出日誌。 */
    protected void finishVisit(UUID visitId, boolean withLog) throws Exception {
        startVisit(visitId);
        endVisit(visitId);
        if (withLog) {
            submitCareLog(visitId);
        }
    }

    protected org.springframework.test.web.servlet.ResultActions modify(UUID orderId, String token, Map<String, Object> body) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/modifications", token, key(), body, orderId));
    }

    protected Map<String, Object> addItem(LocalDate date, UUID plan, int daily, UUID... pets) {
        return Map.of("date", date.toString(), "planId", plan.toString(), "dailyCount", daily,
                "petIds", java.util.Arrays.stream(pets).map(UUID::toString).toList());
    }

    protected UUID modificationId(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return UUID.fromString(body(r).get("modificationId").asString());
    }

    protected UUID initialPayment(UUID orderId) {
        return jdbc.queryForObject("SELECT id FROM payments WHERE order_id = ? AND purpose = 'INITIAL'", UUID.class, orderId);
    }
}
