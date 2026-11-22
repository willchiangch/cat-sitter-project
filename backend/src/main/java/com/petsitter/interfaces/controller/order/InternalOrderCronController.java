package com.petsitter.interfaces.controller.order;

import com.petsitter.application.exception.OrderException;
import com.petsitter.application.service.notify.NotificationEmailDispatcher;
import com.petsitter.application.service.order.OrderCronService;
import com.petsitter.application.service.order.OrderSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 排程端點 C1–C11（SD-ORDER §6.3 C1–C10、§8.1；C11 歸 SD-NOTIFY，過渡掛此）。{@code /api/internal/**} 由
 * {@code InternalSecretFilter} 驗證 {@code X-Internal-Secret}（不符 403）。可選 {@code orderId} 只處理該測試訂單；
 * C9 / C10 不接受 {@code orderId}（帶了 → 400）。
 */
@RestController
@RequestMapping("/api/internal/cron")
@RequiredArgsConstructor
public class InternalOrderCronController {

    private final OrderCronService cronService;
    private final NotificationEmailDispatcher emailDispatcher;
    private final OrderSettings settings;

    @PostMapping("/orders/review-expiry")
    public OrderCronService.CronResult reviewExpiry(@RequestParam(required = false) UUID orderId) {
        return cronService.reviewExpiry(orderId);
    }

    @PostMapping("/payments/reminder")
    public OrderCronService.CronResult paymentReminder(@RequestParam(required = false) UUID orderId) {
        return cronService.paymentReminder(orderId);
    }

    @PostMapping("/payments/initial-expiry")
    public OrderCronService.CronResult initialExpiry(@RequestParam(required = false) UUID orderId) {
        return cronService.initialPaymentExpiry(orderId);
    }

    @PostMapping("/payments/escalation")
    public OrderCronService.CronResult escalation(@RequestParam(required = false) UUID orderId) {
        return cronService.paymentEscalation(orderId);
    }

    @PostMapping("/modifications/expiry")
    public OrderCronService.CronResult modificationExpiry(@RequestParam(required = false) UUID orderId) {
        return cronService.modificationExpiry(orderId);
    }

    @PostMapping("/modifications/supplement-expiry")
    public OrderCronService.CronResult supplementExpiry(@RequestParam(required = false) UUID orderId) {
        return cronService.supplementExpiry(orderId);
    }

    @PostMapping("/visits/zombie")
    public OrderCronService.CronResult zombie(@RequestParam(required = false) UUID orderId) {
        return cronService.zombieVisits(orderId);
    }

    @PostMapping("/orders/auto-complete")
    public OrderCronService.CronResult autoComplete(@RequestParam(required = false) UUID orderId) {
        return cronService.autoComplete(orderId);
    }

    @PostMapping("/idempotency/cleanup")
    public OrderCronService.CronResult idempotencyCleanup(@RequestParam(required = false) String orderId) {
        if (orderId != null) {
            throw OrderException.validation("orderId");
        }
        return cronService.idempotencyCleanup();
    }

    @PostMapping("/storage/payment-proof-orphans")
    public OrderCronService.CronResult paymentProofOrphans(@RequestParam(required = false) String orderId) {
        if (orderId != null) {
            throw OrderException.validation("orderId");
        }
        return cronService.paymentProofOrphans();
    }

    /** C11 Email 投遞（SD-NOTIFY 過渡）。 */
    @PostMapping("/notifications/email-dispatch")
    public NotificationEmailDispatcher.DispatchResult emailDispatch(@RequestParam(required = false) UUID orderId) {
        return emailDispatcher.dispatch(orderId, settings.getCronTimeBudget(), settings.getCronBatchSize());
    }
}
