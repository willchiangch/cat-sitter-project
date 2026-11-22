package com.petsitter.interfaces.controller.order;

import com.petsitter.application.common.idempotency.Idempotent;
import com.petsitter.application.dto.order.OrderCommandRequests;
import com.petsitter.application.dto.order.OrderCommandResponses;
import com.petsitter.application.dto.order.PaymentView;
import com.petsitter.application.service.order.OrderClock;
import com.petsitter.application.service.order.PaymentQueryService;
import com.petsitter.application.service.order.PaymentReviewService;
import com.petsitter.application.service.order.PaymentSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 付款端點 E10–E15（SD-ORDER §6.2 / §6.3）。 */
@RestController
@RequestMapping("/api/orders/{orderId}/payments")
@RequiredArgsConstructor
public class OrderPaymentController {

    private final PaymentQueryService paymentQueryService;
    private final PaymentSubmissionService paymentSubmissionService;
    private final PaymentReviewService paymentReviewService;
    private final OrderClock clock;

    /** E10 付款頁。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','SITTER','ADMIN')")
    public List<PaymentView> list(@PathVariable UUID orderId) {
        return paymentQueryService.list(orderId);
    }

    /** E11 P1 上傳憑證（multipart）。{@code receivedAt} 於進入時取得（PRD-007 BR-12）。 */
    @PostMapping(value = "/{paymentId}/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Idempotent
    @PreAuthorize("hasAnyRole('OWNER','SITTER')")
    public ResponseEntity<OrderCommandResponses.Submitted> submit(
            @PathVariable UUID orderId, @PathVariable UUID paymentId,
            @RequestHeader(value = OrderController.KEY, required = false) String key,
            @RequestParam(value = "disclaimerAgreed", required = false) String disclaimerAgreed,
            @RequestParam(value = "lastFive", required = false) String lastFive,
            @RequestPart(value = "file", required = false) List<MultipartFile> file) {
        OffsetDateTime receivedAt = clock.now();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentSubmissionService.submit(orderId, paymentId, disclaimerAgreed, lastFive, file, receivedAt));
    }

    /** E12 submission 歷史。 */
    @GetMapping("/{paymentId}/submissions")
    @PreAuthorize("hasAnyRole('OWNER','SITTER','ADMIN')")
    public List<OrderCommandResponses.SubmissionItem> submissions(@PathVariable UUID orderId, @PathVariable UUID paymentId) {
        return paymentQueryService.submissions(orderId, paymentId);
    }

    /** E13 憑證簽名網址（TTL 5 分鐘）。 */
    @GetMapping("/{paymentId}/submissions/{submissionId}/proof-url")
    @PreAuthorize("hasAnyRole('OWNER','SITTER','ADMIN')")
    public OrderCommandResponses.ProofUrl proofUrl(@PathVariable UUID orderId, @PathVariable UUID paymentId,
                                                   @PathVariable UUID submissionId) {
        return paymentQueryService.proofUrl(orderId, paymentId, submissionId);
    }

    /** E14 P2 確認收款 / 退款。 */
    @PostMapping("/{paymentId}/verify")
    @Idempotent
    @PreAuthorize("hasAnyRole('OWNER','SITTER')")
    public OrderCommandResponses.Verified verify(@PathVariable UUID orderId, @PathVariable UUID paymentId,
                                                 @RequestHeader(value = OrderController.KEY, required = false) String key,
                                                 @RequestBody(required = false) OrderCommandRequests.Verify body) {
        return paymentReviewService.verify(orderId, paymentId, body);
    }

    /** E15 P3 駁回。 */
    @PostMapping("/{paymentId}/reject")
    @Idempotent
    @PreAuthorize("hasAnyRole('OWNER','SITTER')")
    public OrderCommandResponses.Rejected reject(@PathVariable UUID orderId, @PathVariable UUID paymentId,
                                                 @RequestHeader(value = OrderController.KEY, required = false) String key,
                                                 @RequestBody(required = false) OrderCommandRequests.Reject body) {
        return paymentReviewService.reject(orderId, paymentId, body);
    }
}
