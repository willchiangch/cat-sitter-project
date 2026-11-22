package com.petsitter.order;

import com.petsitter.application.exception.OrderBusyException;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.exception.OrderLockNotHeldException;
import com.petsitter.application.service.order.Actor;
import com.petsitter.application.service.order.LockedOrder;
import com.petsitter.application.service.order.OrderLockService;
import com.petsitter.application.service.order.OrderLogWriter;
import com.petsitter.application.service.order.OrderNotifier;
import com.petsitter.domain.model.order.Order;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 共用機制（SD-ORDER §10.3 OrderLockServiceTest / OrderLogWriterTest / LockFreshnessIntegrationTest）。 */
@DisplayName("訂單鎖與稽核共用機制")
class OrderLockAndLogIntegrationTest extends OrderIntegrationTestBase {

    @Autowired
    private OrderLockService lockService;
    @Autowired
    private OrderLogWriter logWriter;
    @Autowired
    private PlatformTransactionManager txm;
    @Autowired
    private EntityManager em;
    @MockitoSpyBean
    private OrderNotifier notifier;

    private Optional<LockedOrder> tryCron(UUID id) {
        return tx().execute(s -> lockService.tryLockForCron(id));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txm);
    }

    @Nested
    @DisplayName("OrderLockServiceTest")
    class Lock {

        @Test
        @DisplayName("§3.6.1 lock_timeout 3s → OrderBusyException；SKIP LOCKED 鎖不到 → 空；不存在 → 404 / 空")
        void timeoutAndSkipLocked() throws Exception {
            UUID orderId = book(today().plusDays(3));
            CompletableFuture<Void> holder = holdOrderLock(orderId, Duration.ofSeconds(5));
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> tx().execute(s -> lockService.lockForTransition(orderId))).isInstanceOf(OrderBusyException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0).toMillis()).isBetween(2500L, 4900L);
            assertThat(tryCron(orderId)).isEmpty();
            holder.join();
            assertThat(tryCron(orderId)).isPresent();
            assertThatThrownBy(() -> tx().execute(s -> lockService.lockForTransition(UUID.randomUUID()))).isInstanceOf(OrderException.class);
            assertThat(tryCron(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("§2.4.1 MANDATORY：無交易呼叫 → IllegalTransactionStateException；他交易發出的 LockedOrder → OrderLockNotHeldException")
        void mandatoryAndHeld() throws Exception {
            UUID orderId = book(today().plusDays(3));
            assertThatThrownBy(() -> lockService.lockForTransition(orderId)).isInstanceOf(IllegalTransactionStateException.class);
            LockedOrder leaked = tx().execute(s -> lockService.lockForTransition(orderId));
            assertThatThrownBy(() -> tx().executeWithoutResult(s ->
                    logWriter.write(leaked, "X", Actor.SYSTEM, Map.of()))).isInstanceOf(OrderLockNotHeldException.class);
        }
    }

    @Nested
    @DisplayName("LockFreshnessIntegrationTest")
    class Freshness {

        @Test
        @DisplayName("§3.6.1 鎖前已受管的 Order 於 lockForTransition 後 refresh，回傳鎖後的值")
        void refreshAfterLock() throws Exception {
            UUID orderId = book(today().plusDays(3));
            String status = tx().execute(s -> {
                Order stale = em.find(Order.class, orderId);
                assertThat(stale.getStatus().name()).isEqualTo("PENDING");
                // 另一連線在鎖前改了值
                CompletableFuture.runAsync(() -> jdbc.update(
                        "UPDATE orders SET status = 'CANCELLED', cancelled_by = 'SYSTEM', cancelled_at = now(), cancel_reason_code = 'REVIEW_EXPIRED' WHERE id = ?",
                        orderId)).join();
                return lockService.lockForTransition(orderId).status().name();
            });
            assertThat(status).isEqualTo("CANCELLED");
        }
    }

    @Nested
    @DisplayName("OrderLogWriterTest")
    class LogWriter {

        @Test
        @DisplayName("§3.6.3 交易 B 寫完 order_logs 後拋例外 → 主資料、order_logs、notifications 都不存在（500）")
        void rollbackRemovesLog() throws Exception {
            UUID orderId = book(today().plusDays(3));
            int logs = count("SELECT count(*) FROM order_logs WHERE order_id = ?", orderId);
            int notes = count("SELECT count(*) FROM notifications WHERE related_order_id = ?", orderId);
            doThrow(new RuntimeException("boom")).when(notifier).owner(any(), eq("BOOKING_DECLINED"), anyBoolean(), any());
            mvc.perform(postJson("/api/orders/{id}/decline", sitterToken(), key(), Map.of("reason", "x"), orderId))
                    .andExpect(status().isInternalServerError());
            assertThat(orderStatus(orderId)).isEqualTo("PENDING");
            assertThat(count("SELECT count(*) FROM order_logs WHERE order_id = ?", orderId)).isEqualTo(logs);
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ?", orderId)).isEqualTo(notes);
            assertThat(count("SELECT count(*) FROM visits WHERE order_id = ? AND status = 'CANCELLED'", orderId)).isZero();
        }

        @Test
        @DisplayName("§3.6.3 payload 共通：transitions[]（entity / id / from / to）與 idempotencyKey")
        void payloadShape() throws Exception {
            UUID orderId = book(today().plusDays(3));
            String k = key();
            mvc.perform(postJson("/api/orders/{id}/decline", sitterToken(), k, Map.of("reason", "x"), orderId)).andExpect(status().isOk());
            Map<String, Object> row = jdbc.queryForMap("SELECT operator_role, operator_user_id, payload->>'idempotencyKey' AS k, "
                    + "payload->'transitions'->0->>'entity' AS e, payload->'transitions'->0->>'to' AS t "
                    + "FROM order_logs WHERE order_id = ? AND action_type = 'ORDER_REJECTED'", orderId);
            assertThat(row).containsEntry("operator_role", "SITTER").containsEntry("operator_user_id", sitter)
                    .containsEntry("k", k).containsEntry("e", "ORDER").containsEntry("t", "CANCELLED");
            List<Map<String, Object>> transitions = json.readValue(jdbc.queryForObject(
                    "SELECT payload->>'transitions' FROM order_logs WHERE order_id = ? AND action_type = 'ORDER_REJECTED'", String.class, orderId),
                    List.class);
            assertThat(transitions).anyMatch(t -> "VISIT".equals(t.get("entity")));
            assertThat(Optional.ofNullable(row.get("k"))).isPresent();
        }
    }
}
