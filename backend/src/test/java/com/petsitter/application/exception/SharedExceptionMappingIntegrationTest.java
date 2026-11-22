package com.petsitter.application.exception;

import com.petsitter.infrastructure.lock.LockTarget;
import com.petsitter.infrastructure.lock.RowLockSupport;
import com.petsitter.infrastructure.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import testsupport.sharedmech.SharedMechSupport;
import testsupport.sharedmech.SharedMechTestConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 錯誤回應格式與例外對映（SD-GLOBAL-SPEC §2.6、SD-ORDER §6.4）、列鎖（GLOBAL §2.3、SD-ORDER §3.6.1）。
 * 真 Postgres 產生真 SQLSTATE；真 JWT + 真 filter chain。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@Import(SharedMechTestConfig.class)
@DisplayName("SD-GLOBAL §2.3 / §2.6 例外對映與列鎖")
class SharedExceptionMappingIntegrationTest {

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
    private RowLockSupport rowLockSupport;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private String owner;

    @BeforeEach
    void setUp() {
        SharedMechSupport.createTables(jdbc);
        owner = SharedMechSupport.bearer(jwtUtils, UUID.randomUUID(), "OWNER");
    }

    // ------------------------------------------------------------------ 模組 advice：鎖與資料完整性

    @Test
    @DisplayName("GLOBAL §2.6 SQLSTATE 23505 具名約束 → 模組碼（409 TEST_NAMED_CONFLICT）")
    void uniqueNamedConstraint() throws Exception {
        mockMvc.perform(post("/api/test-shared/db/unique-named").header("Authorization", owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_NAMED_CONFLICT"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"unique-unnamed", "unique-jpa", "unique-deferred"})
    @DisplayName("GLOBAL §2.6 其他 23505（含 JPA 路徑與 commit 時的延遲唯一約束）→ 409 ORDER_BUSY + Retry-After: 1")
    void uniqueUnnamedIsOrderBusy(String scenario) throws Exception {
        mockMvc.perform(post("/api/test-shared/db/" + scenario).header("Authorization", owner))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("ORDER_BUSY"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"check", "not-null", "fk"})
    @DisplayName("GLOBAL §2.6 23514 / 23502 / 23503 確定性錯誤 → 500 INTERNAL_ERROR（不得對映可重試碼）")
    void deterministicIntegrityErrorsAre500(String scenario) throws Exception {
        mockMvc.perform(post("/api/test-shared/db/" + scenario).header("Authorization", owner))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.message").value(CommonErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"deadlock", "lock-not-available", "version"})
    @DisplayName("GLOBAL §2.6 SQLSTATE 40P01 / 55P03 / @Version 衝突 → 409 ORDER_BUSY + Retry-After: 1")
    void lockErrorsAreOrderBusy(String scenario) throws Exception {
        mockMvc.perform(post("/api/test-shared/db/" + scenario).header("Authorization", owner))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("ORDER_BUSY"));
    }

    // ------------------------------------------------------------------ 列鎖

    @Test
    @DisplayName("SD-ORDER §10.1 鎖逾時：另一連線鎖住列超過 3 秒 → 409 ORDER_BUSY + Retry-After: 1，資料不變")
    void lockTimeoutIsOrderBusy() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shared_mech_lock_rows (id) VALUES (?)", id);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "SELECT id FROM shared_mech_lock_rows WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, id);
                ps.executeQuery();
            }
            long started = System.nanoTime();
            mockMvc.perform(post("/api/test-shared/lock-rows/{id}/touch", id).header("Authorization", owner))
                    .andExpect(status().isConflict())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("ORDER_BUSY"));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(2900)).isLessThan(Duration.ofSeconds(10));
            holder.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT counter FROM shared_mech_lock_rows WHERE id = ?", Integer.class, id))
                .isZero();

        mockMvc.perform(post("/api/test-shared/lock-rows/{id}/touch", id).header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true));
        assertThat(jdbc.queryForObject("SELECT counter FROM shared_mech_lock_rows WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SD-ORDER §3.6.1 FOR NO KEY UPDATE 與子表 FK 檢查（FOR KEY SHARE）相容：鎖父列時子表 INSERT 不等待")
    void noKeyUpdateAllowsChildInsert() throws Exception {
        UUID parent = UUID.randomUUID();
        jdbc.update("INSERT INTO shared_mech_lock_rows (id) VALUES (?)", parent);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(rowLockSupport.lockForNoKeyUpdate(LockTarget.of("shared_mech_lock_rows"), parent)).isTrue();
            try (Connection other = dataSource.getConnection()) {
                other.setAutoCommit(false);
                try (Statement st = other.createStatement()) {
                    st.execute("SET LOCAL lock_timeout = '1s'");
                }
                try (PreparedStatement ps = other.prepareStatement(
                        "INSERT INTO shared_mech_children (id, parent_id) VALUES (?, ?)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, parent);
                    assertThat(ps.executeUpdate()).isEqualTo(1);
                }
                // 對照：一般 FOR UPDATE 才會互斥
                try (PreparedStatement ps = other.prepareStatement(
                        "SELECT id FROM shared_mech_lock_rows WHERE id = ? FOR UPDATE NOWAIT")) {
                    ps.setObject(1, parent);
                    assertThatThrownBy(ps::executeQuery).hasMessageContaining("could not obtain lock");
                }
                other.rollback();
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("SD-ORDER §3.6.1 tryLockForCron：SKIP LOCKED 鎖不到回 false、不等待；不存在回 false")
    void skipLocked() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shared_mech_lock_rows (id) VALUES (?)", id);
        LockTarget target = LockTarget.of("shared_mech_lock_rows");
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "SELECT id FROM shared_mech_lock_rows WHERE id = ? FOR NO KEY UPDATE")) {
                ps.setObject(1, id);
                ps.executeQuery();
            }
            long started = System.nanoTime();
            Boolean locked = new TransactionTemplate(transactionManager)
                    .execute(s -> rowLockSupport.tryLockSkipLocked(target, id));
            assertThat(locked).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            holder.rollback();
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Boolean free = tx.execute(s -> rowLockSupport.tryLockSkipLocked(target, id));
        Boolean missing = tx.execute(s -> rowLockSupport.tryLockSkipLocked(target, UUID.randomUUID()));
        Boolean missingBlocking = tx.execute(s -> rowLockSupport.lockForNoKeyUpdate(target, UUID.randomUUID()));
        assertThat(free).isTrue();
        assertThat(missing).isFalse();
        assertThat(missingBlocking).isFalse();
    }

    @Test
    @DisplayName("SD-ORDER §2.4 鎖工具傳播 MANDATORY：不在交易中呼叫 → IllegalTransactionStateException；lock_timeout 只在交易內有效")
    void lockRequiresTransaction() {
        assertThatThrownBy(() -> rowLockSupport.lockForNoKeyUpdate(LockTarget.ORDERS, UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
        String inTx = new TransactionTemplate(transactionManager).execute(s -> {
            rowLockSupport.lockForNoKeyUpdate(LockTarget.of("shared_mech_lock_rows"), UUID.randomUUID());
            return jdbc.queryForObject("SHOW lock_timeout", String.class);
        });
        assertThat(inTx).isEqualTo("3s");
        assertThatThrownBy(() -> LockTarget.of("orders; DROP TABLE x")).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 全域 GlobalExceptionHandler

    @Test
    @DisplayName("GLOBAL §2.6 @PreAuthorize 拒絕 → 403 ROLE_MISMATCH，格式 {code, message, traceId, timestamp}，null 欄位不輸出")
    void preAuthorizeDeniedIsRoleMismatch() throws Exception {
        mockMvc.perform(get("/api/test-global/sitter-only").header("Authorization", owner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.payload").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("GLOBAL §2.6 其他 AccessDeniedException → 403 FORBIDDEN")
    void accessDeniedIsForbidden() throws Exception {
        mockMvc.perform(get("/api/test-global/ownership").header("Authorization", owner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("GLOBAL §2.6 authenticationEntryPoint → 401 UNAUTHENTICATED；trace id 取自 X-Cloud-Trace-Context（須符合 ^[0-9a-f]{32}$，否則重新產生）")
    void entryPointFormat() throws Exception {
        mockMvc.perform(get("/api/test-global/sitter-only")
                        .header("X-Cloud-Trace-Context", "0123456789abcdef0123456789abcdef/456;o=1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").value("0123456789abcdef0123456789abcdef"));
        mockMvc.perform(get("/api/test-global/sitter-only")
                        .header("X-Cloud-Trace-Context", "abc123def/456;o=1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{32}$")));
    }

    @Test
    @DisplayName("GLOBAL §2.6 進方法前格式錯誤（JSON 無法解析、路徑 UUID 錯、小數送整數欄、不支援的 media type）→ 400 VALIDATION_FAILED")
    void formatErrorsAreValidationFailed() throws Exception {
        mockMvc.perform(post("/api/test-global/amount").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/test-global/amount").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/test-global/amount").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/test-global/amount").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100));
        mockMvc.perform(post("/api/test-global/amount").header("Authorization", owner)
                        .contentType(MediaType.TEXT_PLAIN).content("100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/test-global/items/not-a-uuid").header("Authorization", owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.payload.field").value("id"));
    }

    @Test
    @DisplayName("GLOBAL §2.6 未處理例外 → 500 INTERNAL_ERROR（不含堆疊與內部訊息）；未知路由 → 404 NOT_FOUND")
    void unhandledIs500() throws Exception {
        mockMvc.perform(get("/api/test-global/boom").header("Authorization", owner))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(CommonErrorCode.INTERNAL_ERROR.defaultMessage()))
                .andExpect(jsonPath("$.trace").doesNotExist());
        mockMvc.perform(get("/api/test-global/no-such-route").header("Authorization", owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
