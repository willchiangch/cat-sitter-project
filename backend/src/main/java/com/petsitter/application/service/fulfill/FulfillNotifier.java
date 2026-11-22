package com.petsitter.application.service.fulfill;

import com.petsitter.application.service.NotificationService;
import com.petsitter.application.service.notify.NotificationCommand;
import com.petsitter.application.service.order.LockedOrder;
import com.petsitter.application.service.order.OrderClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 行程類通知（SD-FULFILL §7.2）：轉移交易內 {@code enqueue()}（MANDATORY），類型代碼由本 SD 定義、範本歸 SD-NOTIFY。
 * 全部 {@code mandatory=false}；站內一律寫入，Email 依收件人 {@code ORDER_AFFAIR} 偏好。通知不含媒體網址。
 */
@Component
@RequiredArgsConstructor
public class FulfillNotifier {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NotificationService notificationService;

    public record NamedVisit(UUID visitId, String serviceDate, String name) {
    }

    /** {@code closeZombieVisits} 本批非空：保母、飼主各 1 則，逐筆列出本批日期與名稱。 */
    public void zombieClosed(LockedOrder order, List<NamedVisit> visits) {
        List<Map<String, Object>> listed = visits.stream().map(v -> Map.<String, Object>of(
                "visitId", v.visitId().toString(), "serviceDate", v.serviceDate(), "name", v.name())).toList();
        Map<String, Object> params = Map.of("orderId", order.orderId().toString(), "visits", listed);
        notificationService.enqueue(new NotificationCommand(order.sitterId(), "SITTER", "VISIT_CLOSED_BY_SYSTEM_SITTER",
                false, order.orderId(), "/sitter/orders/" + order.orderId(), params));
        notificationService.enqueue(new NotificationCommand(order.ownerId(), "OWNER", "VISIT_CLOSED_BY_SYSTEM_OWNER",
                false, order.orderId(), "/owner/orders/" + order.orderId(), params));
    }

    /** FC1：保母每行程一則。 */
    public void autoCompleted(LockedOrder order, NamedVisit visit, OffsetDateTime careLogDeadlineAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", order.orderId().toString());
        params.put("visitId", visit.visitId().toString());
        params.put("serviceDate", visit.serviceDate());
        params.put("name", visit.name());
        params.put("careLogDeadlineAt", careLogDeadlineAt.atZoneSameInstant(OrderClock.TAIPEI).format(MINUTE));
        notificationService.enqueue(new NotificationCommand(order.sitterId(), "SITTER", "VISIT_AUTO_COMPLETED", false,
                order.orderId(), "/sitter/orders/" + order.orderId() + "/visits/" + visit.visitId(), params));
    }

    /** F15：飼主。 */
    public void careLogSubmitted(LockedOrder order, NamedVisit visit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", order.orderId().toString());
        params.put("visitId", visit.visitId().toString());
        params.put("serviceDate", visit.serviceDate());
        params.put("name", visit.name());
        notificationService.enqueue(new NotificationCommand(order.ownerId(), "OWNER", "CARE_LOG_SUBMITTED", false,
                order.orderId(), "/orders/" + order.orderId() + "/visits/" + visit.visitId() + "/care-log", params));
    }
}
