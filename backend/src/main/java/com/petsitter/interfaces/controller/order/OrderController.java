package com.petsitter.interfaces.controller.order;

import com.petsitter.application.common.idempotency.Idempotent;
import com.petsitter.application.dto.order.CreateOrderRequest;
import com.petsitter.application.dto.order.CreateOrderResponse;
import com.petsitter.application.dto.order.EvaluationResponse;
import com.petsitter.application.dto.order.OrderCard;
import com.petsitter.application.dto.order.OrderCommandRequests;
import com.petsitter.application.dto.order.OrderCommandResponses;
import com.petsitter.application.dto.order.OrderDetailResponse;
import com.petsitter.application.dto.order.PageResponse;
import com.petsitter.application.service.order.BookingOrchestrator;
import com.petsitter.application.service.order.OrderCancelService;
import com.petsitter.application.service.order.OrderDetailService;
import com.petsitter.application.service.order.QuoteService;
import com.petsitter.application.dto.order.ModificationRequests;
import com.petsitter.application.dto.order.ModificationResponses;
import com.petsitter.application.service.order.OrderCompletionService;
import com.petsitter.application.service.order.SitterLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * 訂單端點 E1–E9（SD-ORDER §6.2 / §6.3）。DTO 不掛 {@code @Valid}、{@code Idempotency-Key} 非必填宣告（判斷順序，§6.1）；
 * 身分一律取自 token。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    static final String KEY = "Idempotency-Key";

    private final BookingOrchestrator bookingOrchestrator;
    private final OrderDetailService orderDetailService;
    private final QuoteService quoteService;
    private final OrderCancelService orderCancelService;
    private final OrderCompletionService orderCompletionService;
    private final SitterLedgerService sitterLedgerService;

    /** E30 保母帳務總覽。 */
    @GetMapping("/sitter/ledger")
    @PreAuthorize("hasRole('SITTER')")
    public ModificationResponses.Ledger ledger(@RequestParam(required = false) String month) {
        return sitterLedgerService.ledger(month);
    }

    /** E24 O14 款項爭議。 */
    @PostMapping("/{orderId}/payment-disputes")
    @Idempotent
    @PreAuthorize("hasAnyRole('OWNER','SITTER')")
    public ModificationResponses.Disputed paymentDispute(@PathVariable UUID orderId,
                                                         @RequestHeader(value = KEY, required = false) String key,
                                                         @RequestBody(required = false) ModificationRequests.Description body) {
        return orderCompletionService.paymentDispute(orderId, body);
    }

    /** E25 O11 確認結案。 */
    @PostMapping("/{orderId}/complete")
    @Idempotent
    @PreAuthorize("hasRole('OWNER')")
    public ModificationResponses.Completed complete(@PathVariable UUID orderId,
                                                    @RequestHeader(value = KEY, required = false) String key) {
        return orderCompletionService.complete(orderId);
    }

    /** E26 O13 回報服務問題。 */
    @PostMapping("/{orderId}/service-disputes")
    @Idempotent
    @PreAuthorize("hasRole('OWNER')")
    public ModificationResponses.Disputed serviceDispute(@PathVariable UUID orderId,
                                                         @RequestHeader(value = KEY, required = false) String key,
                                                         @RequestBody(required = false) ModificationRequests.Description body) {
        return orderCompletionService.serviceDispute(orderId, body);
    }

    /** E1 O1 送出預約。 */
    @PostMapping
    @Idempotent
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<CreateOrderResponse> create(@RequestHeader(value = KEY, required = false) String key,
                                                      @RequestBody(required = false) CreateOrderRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingOrchestrator.create(body));
    }

    /** E3 飼主訂單列表。 */
    @GetMapping("/owner")
    @PreAuthorize("hasRole('OWNER')")
    public PageResponse<OrderCard> ownerOrders(@RequestParam(required = false) String tab,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String month,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return orderDetailService.list(true, tab, status, month, page, size);
    }

    /** E4 保母訂單列表。 */
    @GetMapping("/sitter")
    @PreAuthorize("hasRole('SITTER')")
    public PageResponse<OrderCard> sitterOrders(@RequestParam(required = false) String tab,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String month,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return orderDetailService.list(false, tab, status, month, page, size);
    }

    /** E2 訂單詳情。 */
    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('OWNER','SITTER')")
    public OrderDetailResponse detail(@PathVariable UUID orderId) {
        return orderDetailService.detail(orderId);
    }

    /** E5 評估頁。 */
    @GetMapping("/{orderId}/evaluation")
    @PreAuthorize("hasRole('SITTER')")
    public EvaluationResponse evaluation(@PathVariable UUID orderId) {
        return orderDetailService.evaluation(orderId);
    }

    /** E6 O2 報價。 */
    @PostMapping("/{orderId}/quote")
    @Idempotent
    @PreAuthorize("hasRole('SITTER')")
    public OrderCommandResponses.Quote quote(@PathVariable UUID orderId,
                                             @RequestHeader(value = KEY, required = false) String key,
                                             @RequestBody(required = false) OrderCommandRequests.Quote body) {
        return quoteService.quote(orderId, body);
    }

    /** E7 O3 保母婉拒。 */
    @PostMapping("/{orderId}/decline")
    @Idempotent
    @PreAuthorize("hasRole('SITTER')")
    public OrderCommandResponses.Cancelled decline(@PathVariable UUID orderId,
                                                   @RequestHeader(value = KEY, required = false) String key,
                                                   @RequestBody(required = false) OrderCommandRequests.Reason body) {
        return orderCancelService.decline(orderId, body);
    }

    /** E8 O3 / O6 飼主撤回。 */
    @PostMapping("/{orderId}/withdraw")
    @Idempotent
    @PreAuthorize("hasRole('OWNER')")
    public OrderCommandResponses.Cancelled withdraw(@PathVariable UUID orderId,
                                                    @RequestHeader(value = KEY, required = false) String key,
                                                    @RequestBody(required = false) OrderCommandRequests.Withdraw body) {
        return orderCancelService.withdraw(orderId, body);
    }

    /** E9 O7 付款前保母取消。 */
    @PostMapping("/{orderId}/sitter-cancel")
    @Idempotent
    @PreAuthorize("hasRole('SITTER')")
    public OrderCommandResponses.Cancelled sitterCancel(@PathVariable UUID orderId,
                                                        @RequestHeader(value = KEY, required = false) String key,
                                                        @RequestBody(required = false) OrderCommandRequests.Reason body) {
        return orderCancelService.sitterCancel(orderId, body);
    }
}
