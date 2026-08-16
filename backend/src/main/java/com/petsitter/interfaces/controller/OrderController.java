package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.BookingRequest;
import com.petsitter.application.dto.ModificationPayloadDto;
import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.dto.RejectOrderRequest;
import com.petsitter.application.service.*;
import com.petsitter.infrastructure.security.gating.PlanTier;
import com.petsitter.infrastructure.security.gating.RequirePlan;
import com.petsitter.infrastructure.security.TokenContext;
import com.petsitter.application.dto.OrderDetailResponseDto;
import com.petsitter.application.dto.OrderSummaryDto;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final BookingService bookingService;
    private final ConfirmOrderService confirmOrderService;
    private final EvaluationService evaluationService;
    private final CompletionService completionService;
    private final ModificationService modificationService;
    private final PaymentService paymentService;
    private final OrderQueryService orderQueryService;

    /**
     * 飼主送出預約申請
     */
    @PostMapping("/booking")
    public ResponseEntity<Map<String, UUID>> createBooking(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BookingRequest request) {
        
        request.setIdempotencyKey(idempotencyKey);
        UUID orderId = bookingService.createBooking(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("orderId", orderId));
    }

    /**
     * 發起變更請求 (SD-016)
     */
    @PreAuthorize("hasAnyRole('OWNER', 'SITTER')")
    @PostMapping("/{orderId}/modify")
    public ResponseEntity<Map<String, String>> modifyOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID orderId,
            @RequestParam String requestedBy,
            @Valid @RequestBody ModificationPayloadDto request) {

        UUID requesterId = TokenContext.getUserId();
        modificationService.proposeModification(requesterId, orderId, request, requestedBy);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "變更請求已提交"));
    }

    /**
     * 查詢訂單目前進行中的變更請求 (PRD-016)
     */
    @PreAuthorize("hasAnyRole('OWNER', 'SITTER')")
    @GetMapping("/{orderId}/modification")
    public ResponseEntity<com.petsitter.application.dto.ModificationRequestDetailDto> getActiveModificationRequest(
            @PathVariable UUID orderId) {
        UUID requesterId = TokenContext.getUserId();
        return ResponseEntity.ok(modificationService.getActiveModificationRequest(requesterId, orderId));
    }

    /**
     * 保母審核變更並提供差額報價 (PRD-016 主流程 B)
     */
    @PreAuthorize("hasRole('SITTER')")
    @PostMapping("/{orderId}/modification/quote")
    public ResponseEntity<Map<String, String>> quoteModification(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID orderId,
            @RequestParam UUID modRequestId,
            @Valid @RequestBody com.petsitter.application.dto.ModificationQuoteRequest request) {

        UUID sitterId = TokenContext.getUserId();
        modificationService.quoteModification(sitterId, orderId, modRequestId, request.newTotalAmount(), request.version());
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "報價已送出，等待飼主確認"));
    }

    /**
     * 保母拒絕變更請求 (PRD-016 主流程 B.3)
     */
    @PreAuthorize("hasRole('SITTER')")
    @PostMapping("/{orderId}/modification/reject")
    public ResponseEntity<Map<String, String>> rejectModification(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID orderId,
            @RequestParam UUID modRequestId) {

        UUID sitterId = TokenContext.getUserId();
        modificationService.rejectModification(sitterId, orderId, modRequestId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已拒絕此變更請求，訂單恢復原狀態"));
    }

    /**
     * 確認同意變更 (SD-016)
     */
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/{orderId}/modification/confirm")
    public ResponseEntity<Map<String, String>> confirmModification(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID orderId,
            @RequestParam UUID modRequestId,
            @Valid @RequestBody com.petsitter.application.dto.ConfirmModificationRequest request) {

        UUID ownerId = TokenContext.getUserId();
        modificationService.confirmModification(ownerId, orderId, modRequestId, request.agreedDiffAmount());
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "變更已生效"));
    }

    /**
     * 保母確認接單 (僅狀態轉換)
     */
    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<Map<String, String>> confirmOrder(
            @RequestParam UUID sitterId,
            @PathVariable UUID orderId) {
        
        confirmOrderService.confirmOrder(sitterId, orderId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "訂單已確認"));
    }

    /**
     * 保母送出報價與調價 (SD-006)
     */
    @RequirePlan(PlanTier.FREE)
    @PostMapping("/{orderId}/quote")
    public ResponseEntity<Map<String, String>> sendQuote(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam UUID sitterId,
            @PathVariable UUID orderId,
            @Valid @RequestBody QuoteRequest request) {

        evaluationService.sendQuote(sitterId, orderId, request, idempotencyKey);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "報價已送出"));
    }

    /**
     * 保母拒絕接單 (PRD-006 AC-4 / SD-006 §2.3)
     */
    @PostMapping("/{orderId}/reject")
    public ResponseEntity<Map<String, String>> rejectOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam UUID sitterId,
            @PathVariable UUID orderId,
            @RequestBody RejectOrderRequest request) {

        evaluationService.rejectOrder(sitterId, orderId, request, idempotencyKey);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已拒絕此訂單"));
    }

    /**
     * 飼主手動結案 (SD-009)
     */
    @PostMapping("/{orderId}/complete")
    public ResponseEntity<Map<String, String>> completeOrder(
            @RequestParam UUID ownerId,
            @PathVariable UUID orderId) {
        
        completionService.manualComplete(orderId, ownerId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "訂單已成功結案"));
    }

    /**
     * 飼主回報爭議 (SD-009)
     */
    @PostMapping("/{orderId}/dispute")
    public ResponseEntity<Map<String, String>> disputeOrder(
            @PathVariable UUID orderId,
            @RequestParam UUID ownerId,
            @RequestBody Map<String, String> payload) {
        String category = payload.get("category");
        String description = payload.get("description");
        completionService.disputeOrder(orderId, ownerId, category, description);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已成功提交爭議申請"));
    }

    /**
     * 管理員強制結案 (Admin Resolve - SD-009)
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{orderId}/admin-resolve")
    public ResponseEntity<Map<String, String>> resolveDisputedOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody com.petsitter.application.dto.AdminResolveRequest request) {
        completionService.resolveDisputedOrder(orderId, request);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "爭議已由管理員調解結案"));
    }

    /**
     * 保母上傳退款憑證 (SD-016)
     * 2026-08 修復 BOLA：身份過去吃前端傳的 sitterId query param（未驗證呼叫者是否真的是該
     * sitterId 本人），任何已登入使用者只要知道目標訂單的真實 sitterId 就能冒充該保母上傳
     * 憑證。改為與同檔案內 propose/quote/reject/confirm 四支端點一致的作法：角色限制 +
     * TokenContext.getUserId() 取真實身份。
     */
    @PreAuthorize("hasRole('SITTER')")
    @PostMapping("/{orderId}/modification/refund-proof")
    public ResponseEntity<Map<String, String>> uploadRefundProof(
            @PathVariable UUID orderId,
            @RequestBody Map<String, String> payload) {
        UUID sitterId = TokenContext.getUserId();
        String refundProofUrl = payload.get("refundProofUrl");
        modificationService.uploadRefundProof(orderId, sitterId, refundProofUrl);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "退款憑證已成功上傳"));
    }

    /**
     * 飼主確認收到退款 (SD-016)
     * 2026-08 修復 BOLA：同上，過去吃前端傳的 ownerId query param，該訂單的保母本來就合法
     * 看得到 ownerId，等於保母自己就能呼叫此端點冒充飼主「確認已收到退款」，繞過這一步驟
     * 原本設計要飼主本人二次確認才能放行的把關意義。
     */
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/{orderId}/modification/refund-confirm")
    public ResponseEntity<Map<String, String>> confirmRefund(@PathVariable UUID orderId) {
        UUID ownerId = TokenContext.getUserId();
        modificationService.confirmRefund(orderId, ownerId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已確認收到退款，訂單變更生效"));
    }

    /**
     * 飼主上傳付款憑證 (SD-007)
     */
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/{orderId}/payment-proof")
    public ResponseEntity<Map<String, String>> uploadPaymentProof(
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("file") MultipartFile file,
            @RequestParam("lastFive") String lastFive,
            @RequestParam("disclaimerAgreed") boolean disclaimerAgreed) {
        
        if (idempotencyKey != null && idempotencyKey.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key 長度不得超過 100 字元");
        }
        UUID ownerId = TokenContext.getUserId();
        paymentService.submitPaymentProof(ownerId, orderId, lastFive, file, disclaimerAgreed, idempotencyKey);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "付款憑證已上傳，等待保母核對"));
    }

    /**
     * 保母確認入帳 (SD-007)
     */
    @PreAuthorize("hasRole('SITTER')")
    @PostMapping("/{orderId}/verify-payment")
    public ResponseEntity<Map<String, String>> verifyPayment(
            @PathVariable UUID orderId) {
        
        UUID sitterId = TokenContext.getUserId();
        paymentService.verifyPayment(sitterId, orderId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "入帳確認成功，訂單已生效"));
    }

    /**
     * 保母駁回憑證 (SD-007)
     */
    @PreAuthorize("hasRole('SITTER')")
    @PostMapping("/{orderId}/reject-payment")
    public ResponseEntity<Map<String, String>> rejectPayment(
            @PathVariable UUID orderId,
            @RequestBody Map<String, String> payload) {
        
        UUID sitterId = TokenContext.getUserId();
        String rejectReason = payload.get("rejectReason");
        paymentService.rejectPayment(sitterId, orderId, rejectReason);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "憑證已駁回，訂單退回待付款狀態"));
    }

    /**
     * 查詢訂單詳情 (SD-007)
     */
    @PreAuthorize("hasAnyRole('OWNER', 'SITTER')")
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDetailResponseDto> getOrderDetail(
            @PathVariable UUID orderId) {
        
        UUID requesterId = TokenContext.getUserId();
        OrderDetailResponseDto dto = orderQueryService.getOrderDetail(orderId, requesterId);
        return ResponseEntity.ok(dto);
    }

    /**
     * 查詢訂單底下的行程清單，供前端串接打卡/日誌回報頁面（SD-008）。
     * CONFIRMED 之後訂單列表/詳情頁本身不帶 visitId，需要靠這支端點才找得到對應的
     * /visit-reports/manage|view/{visitId} 頁面。
     */
    @PreAuthorize("hasAnyRole('OWNER', 'SITTER')")
    @GetMapping("/{orderId}/visits")
    public ResponseEntity<List<com.petsitter.application.dto.VisitSummaryDto>> getOrderVisits(
            @PathVariable UUID orderId) {

        UUID requesterId = TokenContext.getUserId();
        return ResponseEntity.ok(orderQueryService.getOrderVisits(orderId, requesterId));
    }

    /**
     * 飼主查詢自己的訂單清單
     */
    @PreAuthorize("hasRole('OWNER')")
    @GetMapping("/owner")
    public ResponseEntity<List<OrderSummaryDto>> getMyOrdersAsOwner() {
        UUID ownerId = TokenContext.getUserId();
        return ResponseEntity.ok(orderQueryService.getMyOrdersAsOwner(ownerId));
    }

    /**
     * 保母查詢自己的訂單清單
     */
    @PreAuthorize("hasRole('SITTER')")
    @GetMapping("/sitter")
    public ResponseEntity<List<OrderSummaryDto>> getMyOrdersAsSitter() {
        UUID sitterId = TokenContext.getUserId();
        return ResponseEntity.ok(orderQueryService.getMyOrdersAsSitter(sitterId));
    }

    /**
     * 保母帳務總覽 (PRD-009 主流程 C)
     */
    @PreAuthorize("hasRole('SITTER')")
    @GetMapping("/sitter/ledger")
    public ResponseEntity<com.petsitter.application.dto.SitterLedgerResponse> getSitterLedger(
            @RequestParam(required = false) String month) {
        UUID sitterId = TokenContext.getUserId();
        java.time.YearMonth targetMonth = month != null
                ? java.time.YearMonth.parse(month)
                : java.time.YearMonth.now();
        return ResponseEntity.ok(orderQueryService.getSitterLedger(sitterId, targetMonth));
    }
}