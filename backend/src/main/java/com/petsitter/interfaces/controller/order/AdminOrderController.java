package com.petsitter.interfaces.controller.order;

import com.petsitter.application.common.idempotency.Idempotent;
import com.petsitter.application.dto.order.ModificationRequests;
import com.petsitter.application.dto.order.ModificationResponses;
import com.petsitter.application.dto.order.OrderDetailResponse;
import com.petsitter.application.dto.order.PageResponse;
import com.petsitter.application.service.order.AdminOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADMIN 端點 E16、E27、E28、E29（SD-ORDER §6.2 / §6.3）。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    /** E16 O16 作廢首次付款並取消訂單。 */
    @PostMapping("/orders/{orderId}/payments/{paymentId}/void")
    @Idempotent
    public ModificationResponses.AdminVoided voidPayment(@PathVariable UUID orderId, @PathVariable UUID paymentId,
                                                         @RequestHeader(value = OrderController.KEY, required = false) String key,
                                                         @RequestBody(required = false) ModificationRequests.Void body) {
        return adminOrderService.voidInitialPayment(orderId, paymentId, body);
    }

    /** E27 唯讀詳情。 */
    @GetMapping("/orders/{orderId}")
    public OrderDetailResponse detail(@PathVariable UUID orderId) {
        return adminOrderService.detail(orderId);
    }

    /** E28 待辦列表。 */
    @GetMapping("/tasks")
    public PageResponse<ModificationResponses.AdminTaskItem> tasks(@RequestParam(required = false) String status,
                                                                    @RequestParam(required = false) String type,
                                                                    @RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer size) {
        return adminOrderService.tasks(status, type, page, size);
    }

    /** E29 O15 / O17 調解。 */
    @PostMapping("/orders/{orderId}/mediation")
    @Idempotent
    public ModificationResponses.Mediated mediate(@PathVariable UUID orderId,
                                                  @RequestHeader(value = OrderController.KEY, required = false) String key,
                                                  @RequestBody(required = false) ModificationRequests.Mediation body) {
        return adminOrderService.mediate(orderId, body);
    }
}
