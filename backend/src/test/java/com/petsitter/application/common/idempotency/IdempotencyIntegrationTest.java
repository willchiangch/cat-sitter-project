package com.petsitter.application.common.idempotency;

import com.petsitter.infrastructure.security.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import testsupport.sharedmech.MutableClock;
import testsupport.sharedmech.SharedMechSupport;
import testsupport.sharedmech.SharedMechTestConfig;
import testsupport.sharedmech.module.SharedMechModuleController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冪等共用機制（SD-GLOBAL-SPEC §2.4、SD-ORDER §3.6.2 / §10.2）：真 Postgres + 真 JWT + 真 SecurityConfig filter chain。
 * 端點為 {@link SharedMechModuleController}（模擬重建模組，B 階段各端點照同一流程）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@Import(SharedMechTestConfig.class)
@DisplayName("SD-GLOBAL §2.4 冪等共用機制（三段式、回放、認領、接手）")
class IdempotencyIntegrationTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JsonMapper jsonMapper;

    private UUID ownerId;
    private String owner;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        SharedMechSupport.createTables(jdbc);
        SharedMechModuleController.resetLatches();
        clock.reset();
        ownerId = UUID.randomUUID();
        owner = SharedMechSupport.bearer(jwtUtils, ownerId, "OWNER");
        orderId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        SharedMechModuleController.RELEASE.countDown();
        clock.reset();
    }

    private MockHttpServletRequestBuilder widget(UUID order, String key, String json) {
        return widgetAs(owner, order, key, json);
    }

    private MockHttpServletRequestBuilder widgetAs(String token, UUID order, String key, String json) {
        MockHttpServletRequestBuilder b = post("/api/test-shared/orders/{orderId}/widgets", order)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        return key == null ? b : b.header("Idempotency-Key", key);
    }

    private int widgets() {
        return jdbc.queryForObject("SELECT count(*) FROM shared_mech_widgets", Integer.class);
    }

    private int records() {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return jsonMapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("SD-ORDER §10.2 同 key 同內容 → 回放相同狀態碼與 body，資料列數不變")
    void replaySameKeySameBody() throws Exception {
        MvcResult first = mockMvc.perform(widget(orderId, "k-replay", "{\"name\":\"a\"}"))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mockMvc.perform(widget(orderId, "k-replay", "{\"name\":\"a\"}"))
                .andExpect(status().isCreated()).andReturn();

        assertThat(json(second)).isEqualTo(json(first));
        assertThat(widgets()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM idempotency_records", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT scope FROM idempotency_records", String.class))
                .isEqualTo("POST /api/test-shared/orders/{orderId}/widgets");
    }

    @Test
    @DisplayName("SD-ORDER §10.2 JSON key 順序不同、多空白 → 視為同內容回放")
    void canonicalJsonReplay() throws Exception {
        mockMvc.perform(widget(orderId, "k-canon", "{\"name\":\"a\",\"qty\":2}")).andExpect(status().isCreated());
        mockMvc.perform(widget(orderId, "k-canon", "{ \"qty\" : 2 ,\n  \"name\":\"a\" }")).andExpect(status().isCreated());
        assertThat(widgets()).isEqualTo(1);
    }

    @Test
    @DisplayName("SD-ORDER §10.2 同 key 不同內容 → 422 IDEMPOTENCY_KEY_REUSED")
    void reusedKeyDifferentBody() throws Exception {
        mockMvc.perform(widget(orderId, "k-reuse", "{\"name\":\"a\"}")).andExpect(status().isCreated());
        mockMvc.perform(widget(orderId, "k-reuse", "{\"name\":\"b\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(widgets()).isEqualTo(1);
    }

    @Test
    @DisplayName("SD-ORDER §10.2 同 key 用在兩張訂單（路徑變數不同）→ 422")
    void sameKeyDifferentPathVariable() throws Exception {
        mockMvc.perform(widget(orderId, "k-path", "{\"name\":\"a\"}")).andExpect(status().isCreated());
        mockMvc.perform(widget(UUID.randomUUID(), "k-path", "{\"name\":\"a\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("SD-ORDER §10.2 缺 key / key 格式不符 → 400 VALIDATION_FAILED field=Idempotency-Key")
    void missingOrInvalidKey() throws Exception {
        mockMvc.perform(widget(orderId, null, "{\"name\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.payload.field").value("Idempotency-Key"));
        mockMvc.perform(widget(orderId, "x".repeat(129), "{\"name\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("Idempotency-Key"));
        mockMvc.perform(widget(orderId, "含中文", "{\"name\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("Idempotency-Key"));
        assertThat(widgets()).isZero();
        assertThat(records()).isZero();
    }

    @Test
    @DisplayName("SD-ORDER §10.2 業務錯誤 409 被記錄並回放（換內容重送同 key 仍回放錯誤、不重做）")
    void businessErrorRecordedAndReplayed() throws Exception {
        mockMvc.perform(widget(orderId, "k-biz", "{\"name\":\"a\"}").header("X-Test-Mode", "conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_STATE_CONFLICT"))
                .andExpect(jsonPath("$.payload.currentStatus").value("CANCELLED"));
        mockMvc.perform(widget(orderId, "k-biz", "{\"name\":\"a\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_STATE_CONFLICT"));
        assertThat(widgets()).isZero();
        assertThat(jdbc.queryForObject("SELECT response_status FROM idempotency_records", Integer.class)).isEqualTo(409);
    }

    @Test
    @DisplayName("SD-ORDER §10.1 / §10.2 鎖逾時 → 409 ORDER_BUSY + Retry-After: 1、不記錄；鎖釋放後同 key 重試成功")
    void orderBusyNotRecordedThenRetrySucceeds() throws Exception {
        jdbc.update("INSERT INTO shared_mech_lock_rows (id) VALUES (?)", orderId);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "SELECT id FROM shared_mech_lock_rows WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, orderId);
                ps.executeQuery();
            }
            long started = System.nanoTime();
            mockMvc.perform(widget(orderId, "k-busy", "{\"name\":\"a\"}").header("X-Test-Mode", "lock"))
                    .andExpect(status().isConflict())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("ORDER_BUSY"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(2900));
            holder.rollback();
        }
        assertThat(records()).isZero();
        assertThat(widgets()).isZero();

        mockMvc.perform(widget(orderId, "k-busy", "{\"name\":\"a\"}").header("X-Test-Mode", "lock"))
                .andExpect(status().isCreated());
        assertThat(widgets()).isEqualTo(1);
    }

    @Test
    @DisplayName("GLOBAL §2.4 5xx / STORAGE_UNAVAILABLE → 刪紀錄，同 key 重試可成功")
    void serverErrorDeletesRecord() throws Exception {
        mockMvc.perform(widget(orderId, "k-500", "{\"name\":\"a\"}").header("X-Test-Mode", "boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(records()).isZero();
        assertThat(widgets()).isZero();

        mockMvc.perform(widget(orderId, "k-503", "{\"name\":\"a\"}").header("X-Test-Mode", "storage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        assertThat(records()).isZero();

        mockMvc.perform(widget(orderId, "k-500", "{\"name\":\"a\"}")).andExpect(status().isCreated());
        assertThat(widgets()).isEqualTo(1);
    }

    @Test
    @DisplayName("SD-ORDER §10.2 commit 後出錯 → 帶 token 的 DELETE 影響 0 列，同 key 重試回放、不重做")
    void failureAfterCommitStillReplays() throws Exception {
        mockMvc.perform(widget(orderId, "k-after", "{\"name\":\"a\"}").header("X-Test-Mode", "afterCommitFail"))
                .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT status FROM idempotency_records", String.class)).isEqualTo("COMPLETED");

        mockMvc.perform(widget(orderId, "k-after", "{\"name\":\"a\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));
        assertThat(widgets()).isEqualTo(1);
    }

    @Test
    @DisplayName("SD-ORDER §10.2 並行同 key：恰一方成功，另一方 409 IDEMPOTENCY_IN_PROGRESS，資料只有一份")
    void concurrentSameKey() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<MvcResult>> futures = new ArrayList<>();
            // 兩邊都是 block：先認領者在交易 B 內等待，另一方必在認領時得到 409；收到第一個回應才放行
            for (String mode : List.of("block", "block")) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return mockMvc.perform(widget(orderId, "k-race", "{\"name\":\"a\"}")
                                .header("X-Test-Mode", mode)).andReturn();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, pool));
            }
            start.countDown();
            CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0])).get(20, TimeUnit.SECONDS);
            SharedMechModuleController.RELEASE.countDown();
            List<Integer> statuses = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (CompletableFuture<MvcResult> f : futures) {
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                statuses.add(r.getResponse().getStatus());
                if (r.getResponse().getStatus() != 201) {
                    codes.add(json(r).get("code").asString());
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(codes).containsExactly("IDEMPOTENCY_IN_PROGRESS");
        } finally {
            pool.shutdownNow();
        }
        assertThat(widgets()).isEqualTo(1);
        assertThat(records()).isEqualTo(1);
    }

    @Test
    @DisplayName("SD-ORDER §10.2 locked_until 過期 → 接手成功；原請求完成時 claim_token 不符 → 409 IN_PROGRESS 並回滾")
    void takeoverAfterLockExpiry() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<MvcResult> original = CompletableFuture.supplyAsync(() -> {
                try {
                    return mockMvc.perform(widget(orderId, "k-take", "{\"name\":\"a\"}")
                            .header("X-Test-Mode", "block")).andReturn();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, pool);
            assertThat(SharedMechModuleController.ENTERED.await(20, TimeUnit.SECONDS)).isTrue();
            UUID originalToken = jdbc.queryForObject("SELECT claim_token FROM idempotency_records", UUID.class);

            // 處理中（30 秒內）→ 409
            mockMvc.perform(widget(orderId, "k-take", "{\"name\":\"a\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));

            clock.advance(Duration.ofSeconds(31));
            MvcResult taker = mockMvc.perform(widget(orderId, "k-take", "{\"name\":\"a\"}"))
                    .andExpect(status().isCreated()).andReturn();
            UUID takerToken = jdbc.queryForObject("SELECT claim_token FROM idempotency_records", UUID.class);
            assertThat(takerToken).isNotEqualTo(originalToken);

            SharedMechModuleController.RELEASE.countDown();
            MvcResult originalResult = original.get(30, TimeUnit.SECONDS);
            assertThat(originalResult.getResponse().getStatus()).isEqualTo(409);
            assertThat(json(originalResult).get("code").asString()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");

            assertThat(widgets()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT id::text FROM shared_mech_widgets", String.class))
                    .isEqualTo(json(taker).get("widgetId").asString());
            assertThat(jdbc.queryForObject("SELECT status FROM idempotency_records", String.class)).isEqualTo("COMPLETED");
        } finally {
            SharedMechModuleController.RELEASE.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("GLOBAL §2.4 有效期 IDEMPOTENCY_KEY_TTL_HOURS 過後同 key 視為新請求（不同內容也可認領）")
    void expiredRecordIsReplaced() throws Exception {
        mockMvc.perform(widget(orderId, "k-ttl", "{\"name\":\"a\"}")).andExpect(status().isCreated());
        UUID firstId = jdbc.queryForObject("SELECT id FROM idempotency_records", UUID.class);
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        mockMvc.perform(widget(orderId, "k-ttl", "{\"name\":\"b\"}")).andExpect(status().isCreated());
        assertThat(widgets()).isEqualTo(2);
        assertThat(records()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM idempotency_records", UUID.class)).isNotEqualTo(firstId);
    }

    @Test
    @DisplayName("SD-ORDER §6.1 G1→G2：401 先於冪等回放（無 token / 無效 token 不回放）")
    void unauthenticatedBeforeReplay() throws Exception {
        mockMvc.perform(widget(orderId, "k-401", "{\"name\":\"a\"}")).andExpect(status().isCreated());

        mockMvc.perform(post("/api/test-shared/orders/{orderId}/widgets", orderId)
                        .header("Idempotency-Key", "k-401")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        mockMvc.perform(post("/api/test-shared/orders/{orderId}/widgets", orderId)
                        .header("Authorization", "Bearer not-a-jwt")
                        .header("Idempotency-Key", "k-401")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("SD-ORDER §6.1 G2→G3：回放先於 @PreAuthorize（同一使用者切到錯角色仍回放；未完成時錯角色 403 ROLE_MISMATCH）")
    void replayBeforeRoleCheck() throws Exception {
        mockMvc.perform(widget(orderId, "k-role", "{\"name\":\"a\"}")).andExpect(status().isCreated());
        String sameUserAsSitter = SharedMechSupport.bearer(jwtUtils, ownerId, "SITTER");

        mockMvc.perform(widgetAs(sameUserAsSitter, orderId, "k-role", "{\"name\":\"a\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(widgetAs(sameUserAsSitter, orderId, "k-role-new", "{\"name\":\"a\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        assertThat(widgets()).isEqualTo(1);
    }

    @Test
    @DisplayName("GLOBAL §2.4 範圍含 token 使用者：他人用同 key 不會拿到回放")
    void scopeIsPerUser() throws Exception {
        mockMvc.perform(widget(orderId, "k-user", "{\"name\":\"a\"}")).andExpect(status().isCreated());
        String other = SharedMechSupport.bearer(jwtUtils, UUID.randomUUID(), "OWNER");
        mockMvc.perform(widgetAs(other, orderId, "k-user", "{\"name\":\"a\"}"))
                .andExpect(status().isCreated());
        assertThat(widgets()).isEqualTo(2);
    }

    @Test
    @DisplayName("GLOBAL §2.4 body 超過 64 KB → 400 VALIDATION_FAILED（帶 key 的 @Idempotent 端點）")
    void bodyTooLarge() throws Exception {
        String big = "{\"name\":\"a\",\"pad\":\"" + "x".repeat(64 * 1024) + "\"}";
        mockMvc.perform(widget(orderId, "k-big", big))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.payload.field").value("body"));
        assertThat(records()).isZero();

        // 剛好 64 KB 以內照常處理
        String fits = "{\"name\":\"a\",\"pad\":\"" + "x".repeat(64 * 1024 - 22) + "\"}";
        assertThat(fits.getBytes().length).isLessThanOrEqualTo(64 * 1024);
        mockMvc.perform(widget(orderId, "k-fit", fits)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("GLOBAL §2.4 Content-Type 既不是 JSON 也不是 multipart → 400 VALIDATION_FAILED")
    void unsupportedContentType() throws Exception {
        mockMvc.perform(post("/api/test-shared/orders/{orderId}/widgets", orderId)
                        .header("Authorization", owner)
                        .header("Idempotency-Key", "k-ct")
                        .contentType(MediaType.TEXT_PLAIN).content("name=a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.payload.field").value("Content-Type"));
        assertThat(records()).isZero();
    }

    @Test
    @DisplayName("GLOBAL §2.4 multipart：同檔同欄位 → 回放；同 key 換檔案 → 422")
    void multipartHash() throws Exception {
        MockMultipartFile fileA = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});
        MockMultipartFile fileB = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 4});

        MvcResult first = mockMvc.perform(multipart("/api/test-shared/orders/{orderId}/uploads", orderId)
                        .file(fileA).param("note", "n1")
                        .header("Authorization", owner).header("Idempotency-Key", "k-mp"))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mockMvc.perform(multipart("/api/test-shared/orders/{orderId}/uploads", orderId)
                        .file(fileA).param("note", "n1")
                        .header("Authorization", owner).header("Idempotency-Key", "k-mp"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(second)).isEqualTo(json(first));

        mockMvc.perform(multipart("/api/test-shared/orders/{orderId}/uploads", orderId)
                        .file(fileB).param("note", "n1")
                        .header("Authorization", owner).header("Idempotency-Key", "k-mp"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(widgets()).isEqualTo(1);
    }

    @Test
    @DisplayName("GLOBAL §2.4 claim 傳播 NEVER：呼叫端已在交易中 → 拒絕")
    void claimRejectsExistingTransaction(@Autowired IdempotencyService service,
                                         @Autowired org.springframework.transaction.PlatformTransactionManager tm) {
        IdempotencyContext ctx = new IdempotencyContext(ownerId, "POST /x", "k", "0".repeat(64));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new org.springframework.transaction.support.TransactionTemplate(tm)
                                .executeWithoutResult(s -> service.claim(ctx)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.complete(new IdempotencyClaim(UUID.randomUUID(), UUID.randomUUID(), false, ctx), 200, null))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
}
