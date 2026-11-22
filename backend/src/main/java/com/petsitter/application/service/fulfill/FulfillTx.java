package com.petsitter.application.service.fulfill;

import com.petsitter.application.service.order.LockedOrder;
import com.petsitter.application.service.order.OrderLockService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 交易邊界（SD-FULFILL §2.5）：鎖前唯讀不在業務交易內（只用投影）；交易 B = {@code lockForTransition} 後首次載入實體
 * → 重驗 → 寫入 → log / enqueue → 冪等完成 → commit。外部 I/O 一律在這些交易之外。
 */
@Component
public class FulfillTx {

    private final OrderLockService orderLockService;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public FulfillTx(OrderLockService orderLockService, PlatformTransactionManager transactionManager) {
        this.orderLockService = orderLockService;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** 交易 B：取訂單鎖（{@code FOR NO KEY UPDATE} + {@code lock_timeout 3s}）後執行。 */
    public <T> T inLockedTx(UUID orderId, Function<LockedOrder, T> body) {
        return tx.execute(status -> body.apply(orderLockService.lockForTransition(orderId)));
    }

    /** 不取訂單鎖的短寫入交易（槽位建立 / 條件式轉移）。 */
    public <T> T write(Supplier<T> body) {
        return tx.execute(status -> body.get());
    }

    public <T> T readOnly(Supplier<T> body) {
        return readTx.execute(status -> body.get());
    }
}
