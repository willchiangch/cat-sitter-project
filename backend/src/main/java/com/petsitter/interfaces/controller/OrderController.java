package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.BookingRequest;
import com.petsitter.application.dto.ModificationPayloadDto;
import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.service.*;
import com.petsitter.infrastructure.security.gating.PlanTier;
import com.petsitter.infrastructure.security.gating.RequirePlan;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    @PostMapping("/{orderId}/modify")
    public ResponseEntity<Map<String, String>> modifyOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID orderId,
            @RequestParam String requestedBy,
            @Valid @RequestBody ModificationPayloadDto request) {
        
        modificationService.proposeModification(orderId, request, requestedBy);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "變更請求已提交"));
    }

    /**
     * 確認同意變更 (SD-016)
     */
    @PostMapping("/{orderId}/modification/confirm")
    public ResponseEntity<Map<String, String>> confirmModification(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID orderId,
            @RequestParam UUID modRequestId,
            @Valid @RequestBody ModificationPayloadDto request) {
        
        modificationService.confirmModification(orderId, modRequestId, request.getDates());
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
            @RequestParam UUID sitterId,
            @PathVariable UUID orderId,
            @Valid @RequestBody QuoteRequest request) {
        
        evaluationService.sendQuote(sitterId, orderId, request);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "報價已送出"));
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
}