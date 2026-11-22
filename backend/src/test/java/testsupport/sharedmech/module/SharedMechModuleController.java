package testsupport.sharedmech.module;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.common.idempotency.Idempotent;
import com.petsitter.application.exception.StorageUnavailableException;
import com.petsitter.infrastructure.lock.LockTarget;
import com.petsitter.infrastructure.lock.RowLockSupport;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 模擬重建模組端點（只在共用機制整合測試中以 @Import 註冊）。寫入目標是測試自建的 {@code shared_mech_*} 表。
 * <p>
 * {@code X-Test-Mode}（不入 hash）控制交易 B 的行為：
 * {@code conflict}（認領後業務 409）、{@code storage}（503）、{@code boom}（交易內 500）、
 * {@code lock}（交易內鎖 {@code shared_mech_lock_rows} 的 orderId 列，可被測試佔住造成逾時）、
 * {@code block}（交易 B 內等待 {@link #RELEASE}）、{@code afterCommitFail}（交易 B 已提交才拋 500）。
 */
@RestController
@RequestMapping("/api/test-shared")
@RequiredArgsConstructor
public class SharedMechModuleController {

    public static volatile CountDownLatch ENTERED = new CountDownLatch(1);
    public static volatile CountDownLatch RELEASE = new CountDownLatch(1);

    private final IdempotencyService idempotencyService;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;
    private final RowLockSupport rowLockSupport;
    private final EntityManager entityManager;

    public static void resetLatches() {
        ENTERED = new CountDownLatch(1);
        RELEASE = new CountDownLatch(1);
    }

    // ------------------------------------------------------------------ 冪等

    @PostMapping("/orders/{orderId}/widgets")
    @Idempotent
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, Object>> createWidget(
            @PathVariable UUID orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Test-Mode", required = false) String mode,
            @RequestBody(required = false) Map<String, Object> body) {
        IdempotencyContext ctx = idempotencyService.requireContext();          // G6
        IdempotencyClaim claim = idempotencyService.claim(ctx);                // G7（交易 A）
        String name = body == null ? null : String.valueOf(body.get("name"));
        if ("conflict".equals(mode)) {
            throw new SharedMechModuleAdvice.TestBusinessConflictException();
        }
        if ("storage".equals(mode)) {
            throw new StorageUnavailableException("upload failed", new RuntimeException("gcs"));
        }
        Map<String, Object> response = new TransactionTemplate(transactionManager).execute(status -> {
            if ("lock".equals(mode)) {
                rowLockSupport.lockForNoKeyUpdate(LockTarget.of("shared_mech_lock_rows"), orderId);
            }
            UUID widgetId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO shared_mech_widgets (id, order_id, name) VALUES (?, ?, ?)",
                    widgetId, orderId, name);
            if ("boom".equals(mode)) {
                throw new RuntimeException("boom");
            }
            if ("block".equals(mode)) {
                ENTERED.countDown();
                try {
                    RELEASE.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("widgetId", widgetId.toString());
            resp.put("orderId", orderId.toString());
            resp.put("name", name);
            resp.put("note", null);
            idempotencyService.complete(claim, 201, resp);                     // 交易 B 內
            return resp;
        });
        if ("afterCommitFail".equals(mode)) {
            throw new RuntimeException("failed after commit");
        }
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/orders/{orderId}/uploads")
    @Idempotent
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, Object>> upload(
            @PathVariable UUID orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "note", required = false) String note) {
        IdempotencyContext ctx = idempotencyService.requireContext();
        IdempotencyClaim claim = idempotencyService.claim(ctx);
        Map<String, Object> response = new TransactionTemplate(transactionManager).execute(status -> {
            UUID widgetId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO shared_mech_widgets (id, order_id, name) VALUES (?, ?, ?)",
                    widgetId, orderId, note);
            Map<String, Object> resp = Map.of("uploadId", claim.recordId().toString(),
                    "size", file == null ? 0 : file.getSize());
            idempotencyService.complete(claim, 201, resp);
            return resp;
        });
        return ResponseEntity.status(201).body(response);
    }

    // ------------------------------------------------------------------ 例外對映（AbstractModuleExceptionHandler）

    @PostMapping("/db/{scenario}")
    public Map<String, Object> dbError(@PathVariable String scenario) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            switch (scenario) {
                case "unique-named" -> {
                    jdbcTemplate.update("INSERT INTO shared_mech_constraints (id, named_code, other_code, positive) VALUES (?, 'A', 'x1', 1)", UUID.randomUUID());
                    jdbcTemplate.update("INSERT INTO shared_mech_constraints (id, named_code, other_code, positive) VALUES (?, 'A', 'x2', 1)", UUID.randomUUID());
                }
                case "unique-unnamed" -> {
                    jdbcTemplate.update("INSERT INTO shared_mech_constraints (id, named_code, other_code, positive) VALUES (?, 'B1', 'same', 1)", UUID.randomUUID());
                    jdbcTemplate.update("INSERT INTO shared_mech_constraints (id, named_code, other_code, positive) VALUES (?, 'B2', 'same', 1)", UUID.randomUUID());
                }
                case "unique-jpa" -> {
                    entityManager.createNativeQuery("INSERT INTO shared_mech_constraints (id, named_code, other_code, positive) VALUES (gen_random_uuid(), 'J1', 'jsame', 1)").executeUpdate();
                    entityManager.createNativeQuery("INSERT INTO shared_mech_constraints (id, named_code, other_code, positive) VALUES (gen_random_uuid(), 'J2', 'jsame', 1)").executeUpdate();
                }
                case "unique-deferred" -> {
                    jdbcTemplate.update("INSERT INTO shared_mech_deferred (id, sort_order) VALUES (?, 1)", UUID.randomUUID());
                    jdbcTemplate.update("INSERT INTO shared_mech_deferred (id, sort_order) VALUES (?, 1)", UUID.randomUUID());
                    // 延遲唯一約束：違反在 commit 時才拋
                }
                case "check" -> jdbcTemplate.update("INSERT INTO shared_mech_constraints (id, named_code, other_code, positive) VALUES (?, 'C', 'c', -1)", UUID.randomUUID());
                case "not-null" -> jdbcTemplate.update("INSERT INTO shared_mech_constraints (id, named_code, other_code, positive) VALUES (?, NULL, 'n', 1)", UUID.randomUUID());
                case "fk" -> jdbcTemplate.update("INSERT INTO shared_mech_children (id, parent_id) VALUES (?, ?)", UUID.randomUUID(), UUID.randomUUID());
                case "deadlock" -> jdbcTemplate.execute("DO $$ BEGIN RAISE EXCEPTION 'simulated deadlock' USING ERRCODE = '40P01'; END $$");
                case "lock-not-available" -> jdbcTemplate.execute("DO $$ BEGIN RAISE EXCEPTION 'simulated lock timeout' USING ERRCODE = '55P03'; END $$");
                case "version" -> throw new ObjectOptimisticLockingFailureException("Order", UUID.randomUUID());
                default -> throw new IllegalArgumentException(scenario);
            }
        });
        return Map.of("ok", true);
    }

    /** 交易內以 RowLockSupport 鎖列後改值（鎖逾時則不改）。 */
    @PostMapping("/lock-rows/{id}/touch")
    public Map<String, Object> touch(@PathVariable UUID id) {
        boolean found = Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            if (!rowLockSupport.lockForNoKeyUpdate(LockTarget.of("shared_mech_lock_rows"), id)) {
                return false;
            }
            jdbcTemplate.update("UPDATE shared_mech_lock_rows SET counter = counter + 1 WHERE id = ?", id);
            return true;
        }));
        return Map.of("found", found);
    }
}
