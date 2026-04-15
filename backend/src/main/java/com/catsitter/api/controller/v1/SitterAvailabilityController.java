package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.sitter.AvailabilityResponse;
import com.catsitter.api.dto.sitter.BookingPreviewResponse;
import com.catsitter.api.dto.sitter.UpdateAvailabilityRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.BookingPreviewService;
import com.catsitter.api.service.SitterAvailabilityService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sitters")
public class SitterAvailabilityController {

    private final SitterAvailabilityService availabilityService;
    private final BookingPreviewService bookingPreviewService;

    public SitterAvailabilityController(SitterAvailabilityService availabilityService, BookingPreviewService bookingPreviewService) {
        this.availabilityService = availabilityService;
        this.bookingPreviewService = bookingPreviewService;
    }

    @GetMapping("/me/availability")
    public ResponseEntity<AvailabilityResponse> getMyAvailability(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(availabilityService.getAvailability(account));
    }

    @PutMapping("/me/availability")
    public ResponseEntity<AvailabilityResponse> updateMyAvailability(
            @AuthenticationPrincipal Account account,
            @Valid @RequestBody UpdateAvailabilityRequest request) {
        return ResponseEntity.ok(availabilityService.updateAvailability(account, request));
    }

    @GetMapping("/{sitterSlug}/availability/public")
    public ResponseEntity<AvailabilityResponse> getPublicAvailability(@PathVariable String sitterSlug) {
        return ResponseEntity.ok(availabilityService.getPublicAvailability(sitterSlug));
    }

    @GetMapping("/{sitterSlug}/booking-preview")
    public ResponseEntity<BookingPreviewResponse> getBookingPreview(
            @PathVariable String sitterSlug,
            @AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(bookingPreviewService.getBookingPreview(sitterSlug, account));
    }
}
