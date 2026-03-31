package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.booking.BookingDetailResponse;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final BookingService bookingService;

    public OrderController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping
    public ResponseEntity<List<com.catsitter.api.dto.booking.BookingResponse>> getOrders(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(bookingService.getMyOrders(account));
    }

    @PostMapping("/booking")
    public ResponseEntity<com.catsitter.api.dto.booking.BookingResponse> createBooking(
            @AuthenticationPrincipal Account account,
            @Valid @RequestBody com.catsitter.api.dto.booking.CreateBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingService.createBooking(account, request));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<com.catsitter.api.dto.booking.BookingDetailResponse> getOrderDetail(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(bookingService.getBookingDetail(account, orderId));
    }

    @PostMapping("/{orderId}/quote")
    public ResponseEntity<com.catsitter.api.dto.booking.BookingDetailResponse> submitQuote(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID orderId,
            @Valid @RequestBody com.catsitter.api.dto.booking.SubmitQuoteRequest request) {
        return ResponseEntity.ok(bookingService.submitQuote(account, orderId, request));
    }

    @PostMapping("/{orderId}/confirm-payment")
    public ResponseEntity<com.catsitter.api.dto.booking.BookingDetailResponse> confirmPayment(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(bookingService.confirmOfflinePayment(account, orderId));
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
