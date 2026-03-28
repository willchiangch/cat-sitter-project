package com.catsitter.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderLifecycleJob {

    private static final Logger logger = LoggerFactory.getLogger(OrderLifecycleJob.class);

    private final OrderLifecycleService orderLifecycleService;

    public OrderLifecycleJob(OrderLifecycleService orderLifecycleService) {
        this.orderLifecycleService = orderLifecycleService;
    }

    /**
     * Run every hour to cancel unpaid quotes
     */
    @Scheduled(cron = "0 0 * * * *")
    public void runUnpaidOrderCancellation() {
        logger.info("[JOB START] Unpaid order cancellation");
        try {
            orderLifecycleService.cancelExpiredQuotedOrders();
            logger.info("[JOB END] Unpaid order cancellation - Completed");
        } catch (Exception e) {
            logger.error("[JOB ERROR] Unpaid order cancellation - Reason: {}", e.getMessage(), e);
        }
    }

    /**
     * Run every day at 01:00 to auto-complete finished orders
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void runAutoOrderCompletion() {
        logger.info("[JOB START] Auto order completion");
        try {
            orderLifecycleService.autoCompleteFinishedOrders();
            logger.info("[JOB END] Auto order completion - Completed");
        } catch (Exception e) {
            logger.error("[JOB ERROR] Auto order completion - Reason: {}", e.getMessage(), e);
        }
    }
}
