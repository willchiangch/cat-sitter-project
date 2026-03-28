package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.booking.BookingDetailResponse;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final BookingService bookingService;

    public OrderController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/{orderId}/complete")
    public ResponseEntity<?> completeOrder(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID orderId) {
        try {
            return ResponseEntity.ok(bookingService.completeOrder(account, orderId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "BAD_REQUEST",
                    "message", e.getMessage()
            ));
        }
    }
}
