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

    // Auth
    @PostMapping("/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/auth/register");
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/auth/login");
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/auth/refresh");
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/auth/logout");
    }

    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> me() {
        return notImplemented("GET /api/v1/auth/me");
    }

    // Sitter profile
    @GetMapping("/sitters/me/profile")
    public ResponseEntity<Map<String, Object>> getSitterProfile() {
        return notImplemented("GET /api/v1/sitters/me/profile");
    }

    @PutMapping("/sitters/me/profile")
    public ResponseEntity<Map<String, Object>> updateSitterProfile(@RequestBody Map<String, Object> body) {
        return notImplemented("PUT /api/v1/sitters/me/profile");
    }

    @PostMapping("/sitters/me/services")
    public ResponseEntity<Map<String, Object>> createService(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/sitters/me/services");
    }

    @GetMapping("/sitters/me/services")
    public ResponseEntity<Map<String, Object>> listServices() {
        return notImplemented("GET /api/v1/sitters/me/services");
    }

    @PutMapping("/sitters/me/services/{serviceId}")
    public ResponseEntity<Map<String, Object>> updateService(@PathVariable UUID serviceId, @RequestBody Map<String, Object> body) {
        return notImplemented("PUT /api/v1/sitters/me/services/{serviceId}");
    }

    @DeleteMapping("/sitters/me/services/{serviceId}")
    public ResponseEntity<Map<String, Object>> deleteService(@PathVariable UUID serviceId) {
        return notImplemented("DELETE /api/v1/sitters/me/services/{serviceId}");
    }

    @PostMapping("/sitters/me/questionnaires")
    public ResponseEntity<Map<String, Object>> createQuestion(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/sitters/me/questionnaires");
    }

    @GetMapping("/sitters/me/questionnaires")
    public ResponseEntity<Map<String, Object>> listQuestions() {
        return notImplemented("GET /api/v1/sitters/me/questionnaires");
    }

    @PutMapping("/sitters/me/questionnaires/{questionId}")
    public ResponseEntity<Map<String, Object>> updateQuestion(@PathVariable UUID questionId, @RequestBody Map<String, Object> body) {
        return notImplemented("PUT /api/v1/sitters/me/questionnaires/{questionId}");
    }

    @PatchMapping("/sitters/me/questionnaires/reorder")
    public ResponseEntity<Map<String, Object>> reorderQuestions(@RequestBody Map<String, Object> body) {
        return notImplemented("PATCH /api/v1/sitters/me/questionnaires/reorder");
    }

    @GetMapping("/sitters/{sitterSlug}/booking-preview")
    public ResponseEntity<Map<String, Object>> bookingPreview(@PathVariable String sitterSlug) {
        return notImplemented("GET /api/v1/sitters/{sitterSlug}/booking-preview");
    }

    @GetMapping("/sitters/{sitterSlug}/availability/public")
    public ResponseEntity<Map<String, Object>> publicAvailability(@PathVariable String sitterSlug) {
        return notImplemented("GET /api/v1/sitters/{sitterSlug}/availability/public");
    }

    // Client/Pet
    @GetMapping("/clients/me/profile")
    public ResponseEntity<Map<String, Object>> getClientProfile() {
        return notImplemented("GET /api/v1/clients/me/profile");
    }

    @PutMapping("/clients/me/profile")
    public ResponseEntity<Map<String, Object>> updateClientProfile(@RequestBody Map<String, Object> body) {
        return notImplemented("PUT /api/v1/clients/me/profile");
    }

    @PostMapping("/clients/me/pets")
    public ResponseEntity<Map<String, Object>> createPet(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/clients/me/pets");
    }

    @GetMapping("/clients/me/pets")
    public ResponseEntity<Map<String, Object>> listPets() {
        return notImplemented("GET /api/v1/clients/me/pets");
    }

    @GetMapping("/clients/me/pets/{petId}")
    public ResponseEntity<Map<String, Object>> getPet(@PathVariable UUID petId) {
        return notImplemented("GET /api/v1/clients/me/pets/{petId}");
    }

    @PutMapping("/clients/me/pets/{petId}")
    public ResponseEntity<Map<String, Object>> updatePet(@PathVariable UUID petId, @RequestBody Map<String, Object> body) {
        return notImplemented("PUT /api/v1/clients/me/pets/{petId}");
    }

    @DeleteMapping("/clients/me/pets/{petId}")
    public ResponseEntity<Map<String, Object>> deletePet(@PathVariable UUID petId) {
        return notImplemented("DELETE /api/v1/clients/me/pets/{petId}");
    }

    // Booking/Order
    @PostMapping("/bookings")
    public ResponseEntity<Map<String, Object>> createBooking(@RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/bookings");
    }

    @GetMapping("/bookings/{bookingId}")
    public ResponseEntity<Map<String, Object>> getBooking(@PathVariable UUID bookingId) {
        return notImplemented("GET /api/v1/bookings/{bookingId}");
    }

    @PatchMapping("/bookings/{bookingId}/reschedule")
    public ResponseEntity<Map<String, Object>> rescheduleBooking(@PathVariable UUID bookingId, @RequestBody Map<String, Object> body) {
        return notImplemented("PATCH /api/v1/bookings/{bookingId}/reschedule");
    }

    @GetMapping("/sitters/me/bookings")
    public ResponseEntity<Map<String, Object>> listSitterBookings(@RequestParam(required = false) String status) {
        return notImplemented("GET /api/v1/sitters/me/bookings");
    }

    @PostMapping("/bookings/{bookingId}/quote")
    public ResponseEntity<Map<String, Object>> submitQuote(@PathVariable UUID bookingId, @RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/bookings/{bookingId}/quote");
    }

    @PostMapping("/bookings/{bookingId}/payment-proofs")
    public ResponseEntity<Map<String, Object>> uploadPaymentProof(@PathVariable UUID bookingId, @RequestBody Map<String, Object> body) {
        return notImplemented("POST /api/v1/bookings/{bookingId}/payment-proofs");
    }

    @PostMapping("/bookings/{bookingId}/payments/confirm-offline")
    public ResponseEntity<Map<String, Object>> confirmOfflinePayment(@PathVariable UUID bookingId) {
        return notImplemented("POST /api/v1/bookings/{bookingId}/payments/confirm-offline");
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
    @GetMapping("/sitters/me/visits")
    public ResponseEntity<Map<String, Object>> listVisits(@RequestParam String date) {
        return notImplemented("GET /api/v1/sitters/me/visits");
    }

    @GetMapping("/visits/{visitId}")
    public ResponseEntity<Map<String, Object>> getVisit(@PathVariable UUID visitId) {
        return notImplemented("GET /api/v1/visits/{visitId}");
    }

    @PatchMapping("/visits/{visitId}/checklist")
    public ResponseEntity<Map<String, Object>> updateChecklist(@PathVariable UUID visitId, @RequestBody Map<String, Object> body) {
        return notImplemented("PATCH /api/v1/visits/{visitId}/checklist");
    }

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

    @PostMapping("/visits/{visitId}/complete")
    public ResponseEntity<Map<String, Object>> completeVisit(@PathVariable UUID visitId) {
        return notImplemented("POST /api/v1/visits/{visitId}/complete");
    }

    @GetMapping("/clients/me/visits/{visitId}/live-status")
    public ResponseEntity<Map<String, Object>> liveVisitStatus(@PathVariable UUID visitId) {
        return notImplemented("GET /api/v1/clients/me/visits/{visitId}/live-status");
    }

    @PostMapping("/orders/{orderId}/complete")
    public ResponseEntity<Map<String, Object>> completeOrder(@PathVariable UUID orderId) {
        return notImplemented("POST /api/v1/orders/{orderId}/complete");
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
