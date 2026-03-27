package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.booking.BookingDetailResponse;
import com.catsitter.api.dto.booking.BookingResponse;
import com.catsitter.api.dto.booking.CreateBookingRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.BookingService;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @AuthenticationPrincipal Account account,
            @Valid @RequestBody CreateBookingRequest request) {
        return ResponseEntity.ok(bookingService.createBooking(account, request));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingDetailResponse> getBooking(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingService.getBookingDetail(account, bookingId));
    }

    @PostMapping("/{bookingId}/quote")
    public ResponseEntity<BookingDetailResponse> submitQuote(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID bookingId,
            @Valid @RequestBody com.catsitter.api.dto.booking.SubmitQuoteRequest request) {
        return ResponseEntity.ok(bookingService.submitQuote(account, bookingId, request));
    }

    @PostMapping("/{bookingId}/payment-proofs")
    public ResponseEntity<BookingDetailResponse> uploadPaymentProof(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID bookingId,
            @RequestBody Map<String, Object> proofData) {
        return ResponseEntity.ok(bookingService.uploadPaymentProof(account, bookingId, proofData));
    }

    @PostMapping("/{bookingId}/payments/confirm-offline")
    public ResponseEntity<BookingDetailResponse> confirmOfflinePayment(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingService.confirmOfflinePayment(account, bookingId));
    }
}
