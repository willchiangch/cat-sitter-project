package com.catsitter.api.controller.v1;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ApiV1StubController {

    private ResponseEntity<Map<String, Object>> notImplemented(String endpoint) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of(
                        "code", "NOT_IMPLEMENTED",
                        "endpoint", endpoint,
                        "message", "Endpoint stub is created. Implement service logic next."
                ));
    }


    @PatchMapping("/bookings/{bookingId}/reschedule")
    public ResponseEntity<Map<String, Object>> rescheduleBooking(@PathVariable UUID bookingId, @RequestBody Map<String, Object> body) {
        return notImplemented("PATCH /api/v1/bookings/{bookingId}/reschedule");
    }

    @GetMapping("/sitters/me/bookings")
    public ResponseEntity<Map<String, Object>> listSitterBookings(@RequestParam(required = false) String status) {
        return notImplemented("GET /api/v1/sitters/me/bookings");
    }




    @PostMapping("/bookings/{bookingId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelBooking(@PathVariable UUID bookingId, @RequestBody(required = false) Map<String, Object> body) {
        return notImplemented("POST /api/v1/bookings/{bookingId}/cancel");
    }

    // Trust circle/referral
    @GetMapping("/sitters/me/trust-circle/search")
    public ResponseEntity<Map<String, Object>> searchTrustCircle(@RequestParam String q) {
        return notImplemented("GET /api/v1/sitters/me/trust-circle/search");
    }

    @PostMapping("/sitters/me/trust-circle")
    public ResponseEntity<Map<String, Object>> addTrustCircle(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/sitters/me/trust-circle");
    }

    @PatchMapping("/sitters/me/trust-circle/{partnerId}")
    public ResponseEntity<Map<String, Object>> updateTrustCircle(@PathVariable UUID partnerId, @RequestBody Map<String, Object> body) {
        return notImplemented("PATCH /api/v1/sitters/me/trust-circle/{partnerId}");
    }

    @DeleteMapping("/sitters/me/trust-circle/{partnerId}")
    public ResponseEntity<Map<String, Object>> removeTrustCircle(@PathVariable UUID partnerId) {
        return notImplemented("DELETE /api/v1/sitters/me/trust-circle/{partnerId}");
    }

    @PostMapping("/bookings/{bookingId}/referrals")
    public ResponseEntity<Map<String, Object>> createReferral(@PathVariable UUID bookingId, @RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/bookings/{bookingId}/referrals");
    }

    @GetMapping("/bookings/{bookingId}/referrals")
    public ResponseEntity<Map<String, Object>> listReferrals(@PathVariable UUID bookingId) {
        return notImplemented("GET /api/v1/bookings/{bookingId}/referrals");
    }

    // Visits



    @PostMapping("/visits/{visitId}/checklist/custom-items")
    public ResponseEntity<Map<String, Object>> createCustomChecklistItem(@PathVariable UUID visitId, @RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/visits/{visitId}/checklist/custom-items");
    }

    @PostMapping("/visits/{visitId}/media")
    public ResponseEntity<Map<String, Object>> uploadVisitMedia(@PathVariable UUID visitId, @RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/visits/{visitId}/media");
    }

    @PutMapping("/visits/{visitId}/notes")
    public ResponseEntity<Map<String, Object>> updateVisitNotes(@PathVariable UUID visitId, @RequestBody Map<String, Object> body) {
        return notImplemented("PUT /api/v1/visits/{visitId}/notes");
    }


    @GetMapping("/clients/me/visits/{visitId}/live-status")
    public ResponseEntity<Map<String, Object>> liveVisitStatus(@PathVariable UUID visitId) {
        return notImplemented("GET /api/v1/clients/me/visits/{visitId}/live-status");
    }


    // Billing/Notification
    @GetMapping("/sitters/me/payouts")
    public ResponseEntity<Map<String, Object>> monthlyPayout(@RequestParam String month) {
        return notImplemented("GET /api/v1/sitters/me/payouts");
    }

    @GetMapping("/sitters/me/subscription")
    public ResponseEntity<Map<String, Object>> getSubscription() {
        return notImplemented("GET /api/v1/sitters/me/subscription");
    }

    @PostMapping("/sitters/me/subscription/checkout")
    public ResponseEntity<Map<String, Object>> createSubscriptionCheckout(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/sitters/me/subscription/checkout");
    }

    @PostMapping("/payments/webhooks/ecpay")
    public ResponseEntity<Map<String, Object>> ecpayWebhook(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/payments/webhooks/ecpay");
    }

    @GetMapping("/notifications")
    public ResponseEntity<Map<String, Object>> listNotifications(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return notImplemented("GET /api/v1/notifications");
    }

    @PatchMapping("/notifications/{notificationId}/read")
    public ResponseEntity<Map<String, Object>> markNotificationAsRead(@PathVariable UUID notificationId) {
        return notImplemented("PATCH /api/v1/notifications/{notificationId}/read");
    }
}
