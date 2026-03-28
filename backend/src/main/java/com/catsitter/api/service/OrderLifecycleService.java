package com.catsitter.api.service;

import com.catsitter.api.entity.Order;
import com.catsitter.api.entity.OrderActionLog;
import com.catsitter.api.entity.Visit;
import com.catsitter.api.entity.enums.ActionType;
import com.catsitter.api.entity.enums.OrderStatus;
import com.catsitter.api.entity.enums.PaymentStatus;
import com.catsitter.api.entity.enums.VisitStatus;
import com.catsitter.api.repository.OrderActionLogRepository;
import com.catsitter.api.repository.OrderRepository;
import com.catsitter.api.repository.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class OrderLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(OrderLifecycleService.class);

    private final OrderRepository orderRepository;
    private final VisitRepository visitRepository;
    private final OrderActionLogRepository actionLogRepository;
    private final CalendarSyncService calendarSyncService;

    @Value("${application.order.pending-payment-timeout-hours:24}")
    private int pendingPaymentTimeoutHours;

    @Value("${application.order.auto-complete-days:3}")
    private int autoCompleteDays;

    public OrderLifecycleService(OrderRepository orderRepository,
                                  VisitRepository visitRepository,
                                  OrderActionLogRepository actionLogRepository,
                                  CalendarSyncService calendarSyncService) {
        this.orderRepository = orderRepository;
        this.visitRepository = visitRepository;
        this.actionLogRepository = actionLogRepository;
        this.calendarSyncService = calendarSyncService;
    }

    /**
     * Auto-cancel orders that were quoted but remains unpaid for too long.
     */
    @Transactional
    public void cancelExpiredQuotedOrders() {
        Instant threshold = Instant.now().minus(pendingPaymentTimeoutHours, ChronoUnit.HOURS);
        List<Order> candidates = orderRepository.findByOrderStatusAndPaymentStatusAndUpdatedAtBefore(
                OrderStatus.QUOTED, PaymentStatus.UNPAID, threshold);

        for (Order order : candidates) {
            String prevStatus = order.getOrderStatus().name();
            order.setOrderStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);

            // Sync to Sitter's Calendar (to remove events)
            calendarSyncService.syncOrderEvents(order);

            logSystemAction(order, ActionType.ORDER_CANCELLED, prevStatus, OrderStatus.CANCELLED.name(), 
                      "{\"reason\": \"System: Pending payment timeout (" + pendingPaymentTimeoutHours + "h)\"}");
            
            logger.info("Auto-cancelled unpaid order: {}", order.getId());
        }
    }

    /**
     * Auto-complete orders where all visits are done and the buffer period has passed.
     */
    @Transactional
    public void autoCompleteFinishedOrders() {
        List<Order> candidates = orderRepository.findByOrderStatus(OrderStatus.CONFIRMED);

        for (Order order : candidates) {
            List<Visit> visits = visitRepository.findByOrderId(order.getId());
            if (visits.isEmpty()) continue;

            boolean allDone = visits.stream().allMatch(v -> v.getStatus() == VisitStatus.DONE);
            if (!allDone) continue;

            // Find the latest visit end time
            OffsetDateTime latestVisitEnd = visits.stream()
                    .map(Visit::getVisitEndTime)
                    .max(OffsetDateTime::compareTo)
                    .orElse(OffsetDateTime.now());

            if (latestVisitEnd.plusDays(autoCompleteDays).isBefore(OffsetDateTime.now())) {
                String prevStatus = order.getOrderStatus().name();
                order.setOrderStatus(OrderStatus.COMPLETED);
                orderRepository.save(order);

                logSystemAction(order, ActionType.ORDER_COMPLETED, prevStatus, OrderStatus.COMPLETED.name(), 
                          "{\"reason\": \"System: Auto-completion after " + autoCompleteDays + " days\"}");
                
                logger.info("Auto-completed finished order: {}", order.getId());
            }
        }
    }

    private void logSystemAction(Order order, ActionType type, String prev, String next, String metadata) {
        OrderActionLog log = new OrderActionLog();
        log.setOrder(order);
        log.setActionType(type);
        log.setPreviousStatus(prev);
        log.setNewStatus(next);
        log.setActorProfile(null); // System action has no actor profile
        log.setMetadata(metadata);
        actionLogRepository.save(log);
    }
}
