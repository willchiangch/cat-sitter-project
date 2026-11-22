package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.exception.OrderException;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentDirection;
import com.petsitter.domain.model.order.PaymentPurpose;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.OrderRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Function;

/**
 * 寫入端點共通骨架（SD-ORDER §3.6.2、§6.1 判斷順序）：鎖前唯讀（G4 / G4a / G5，不在交易內、不污染交易 B 的
 * persistence context）→ G6 → G7 認領（交易 A）→ 交易 B（{@link #inLockedTx}：鎖 → 重驗 → 寫入 → complete）。
 * 失敗段由 {@code IdempotencyInterceptor.afterCompletion} 依回應自動處理。
 */
@Component
public class OrderCommandSupport {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ModificationRequestRepository modificationRequestRepository;
    private final IdempotencyService idempotencyService;
    private final OrderLockService orderLockService;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public OrderCommandSupport(OrderRepository orderRepository, PaymentRepository paymentRepository,
                               ModificationRequestRepository modificationRequestRepository,
                               IdempotencyService idempotencyService, OrderLockService orderLockService,
                               PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.modificationRequestRepository = modificationRequestRepository;
        this.idempotencyService = idempotencyService;
        this.orderLockService = orderLockService;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** G4：訂單（唯讀，鎖前）。 */
    public Order loadOrder(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow(OrderException::notFound);
    }

    /** G4：payment 必須屬路徑訂單，否則 404。 */
    public Payment loadPayment(Order order, UUID paymentId) {
        return paymentRepository.findById(paymentId).filter(p -> p.getOrderId().equals(order.getId()))
                .orElseThrow(OrderException::notFound);
    }

    public ModificationRequest loadModification(Order order, UUID modificationId) {
        return modificationRequestRepository.findById(modificationId).filter(m -> m.getOrderId().equals(order.getId()))
                .orElseThrow(OrderException::notFound);
    }

    public IdempotencyClaim claim(IdempotencyContext ctx) {
        return idempotencyService.claim(ctx);
    }

    /** 交易 B：取訂單鎖後執行 {@code body}；body 內須呼叫 {@link #complete}。 */
    public <T> T inLockedTx(UUID orderId, Function<LockedOrder, T> body) {
        return tx.execute(status -> body.apply(orderLockService.lockForTransition(orderId)));
    }

    public <T> T readOnly(java.util.function.Supplier<T> body) {
        return readTx.execute(status -> body.get());
    }

    public void complete(IdempotencyClaim claim, int status, Object body) {
        idempotencyService.complete(claim, status, body);
    }

    /** payment 的付款方（baseline §3.3）：CHARGE → OWNER；REFUND → SITTER。 */
    public static ActorRole payer(Payment p) {
        return p.getDirection() == PaymentDirection.CHARGE ? ActorRole.OWNER : ActorRole.SITTER;
    }

    /** 收款方：CHARGE → SITTER；REFUND → OWNER。 */
    public static ActorRole payee(Payment p) {
        return p.getDirection() == PaymentDirection.CHARGE ? ActorRole.SITTER : ActorRole.OWNER;
    }

    public static boolean isAdminAdjustment(Payment p) {
        return p.getPurpose() == PaymentPurpose.ADMIN_ADJUSTMENT;
    }
}
