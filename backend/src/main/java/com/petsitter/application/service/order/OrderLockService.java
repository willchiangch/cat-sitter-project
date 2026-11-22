package com.petsitter.application.service.order;

import com.petsitter.application.exception.OrderException;
import com.petsitter.application.exception.OrderLockNotHeldException;
import com.petsitter.domain.model.order.Order;
import com.petsitter.infrastructure.lock.LockTarget;
import com.petsitter.infrastructure.lock.RowLockSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 訂單鎖唯一入口（SD-ORDER §2.3.1、§3.6.1）。
 * <ul>
 *   <li>{@link #lockForTransition}：{@code SET LOCAL lock_timeout = '3s'} + {@code FOR NO KEY UPDATE}（{@link RowLockSupport}）；
 *       不存在 → 404；逾時 / 死結 → {@code OrderBusyException}（409 {@code ORDER_BUSY}）。</li>
 *   <li>鎖後新鮮度：該 {@code Order} 若在鎖前已受管則 {@code refresh}，回傳一律為鎖後的值。</li>
 *   <li>{@link #tryLockForCron}：{@code SKIP LOCKED}，鎖不到或不存在回空。</li>
 * </ul>
 * 鎖順序：任何交易先鎖 {@code orders} 列，之後才讀寫子列。
 */
@Service
@RequiredArgsConstructor
public class OrderLockService {

    private static final Object RESOURCE_KEY = OrderLockService.class.getName() + ".issued";

    private final RowLockSupport rowLockSupport;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedOrder lockForTransition(UUID orderId) {
        LockedOrder existing = issued().get(orderId);
        if (existing != null) {
            return existing;
        }
        if (!rowLockSupport.lockForNoKeyUpdate(LockTarget.ORDERS, orderId)) {
            throw OrderException.notFound();
        }
        return issue(orderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedOrder> tryLockForCron(UUID orderId) {
        LockedOrder existing = issued().get(orderId);
        if (existing != null) {
            return Optional.of(existing);
        }
        if (!rowLockSupport.tryLockSkipLocked(LockTarget.ORDERS, orderId)) {
            return Optional.empty();
        }
        return Optional.of(issue(orderId));
    }

    /** {@code order} 必須是同一交易內由本服務發出的檢視，否則 {@link OrderLockNotHeldException}（500）。 */
    public void requireHeld(LockedOrder order) {
        if (order == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new OrderLockNotHeldException("no active transaction holding the order lock");
        }
        @SuppressWarnings("unchecked")
        Map<UUID, LockedOrder> map = (Map<UUID, LockedOrder>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (map == null || map.get(order.orderId()) != order) {
            throw new OrderLockNotHeldException("LockedOrder " + order.orderId() + " was not issued in this transaction");
        }
    }

    private LockedOrder issue(UUID orderId) {
        boolean wasManaged = isManaged(orderId);
        Order order = entityManager.find(Order.class, orderId);
        if (order == null) {
            throw OrderException.notFound();
        }
        if (wasManaged) {
            entityManager.refresh(order);
        }
        Map<UUID, LockedOrder> map = issued();
        LockedOrder locked = new LockedOrder(order, map);
        map.put(orderId, locked);
        return locked;
    }

    private boolean isManaged(UUID orderId) {
        SessionImplementor session = entityManager.unwrap(SessionImplementor.class);
        EntityPersister persister = session.getFactory().getMappingMetamodel().getEntityDescriptor(Order.class);
        EntityKey key = session.generateEntityKey(orderId, persister);
        return session.getPersistenceContextInternal().getEntity(key) != null;
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, LockedOrder> issued() {
        Map<UUID, LockedOrder> map = (Map<UUID, LockedOrder>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (map == null) {
            Map<UUID, LockedOrder> created = new HashMap<>();
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                        TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
                    }
                }
            });
            map = created;
        }
        return map;
    }
}
