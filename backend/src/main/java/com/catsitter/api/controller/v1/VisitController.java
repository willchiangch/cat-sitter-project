package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.visit.VisitDetailResponse;
import com.catsitter.api.dto.visit.VisitSummaryResponse;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.VisitManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class VisitController {

    private final VisitManagementService visitManagementService;

    public VisitController(VisitManagementService visitManagementService) {
        this.visitManagementService = visitManagementService;
    }

    @GetMapping("/sitters/me/visits")
    public ResponseEntity<List<VisitSummaryResponse>> listMyVisits(
            @AuthenticationPrincipal Account account,
            @RequestParam String date) {
        return ResponseEntity.ok(visitManagementService.listSitterVisits(account, LocalDate.parse(date)));
    }

    @GetMapping("/visits/{visitId}")
    public ResponseEntity<VisitDetailResponse> getVisit(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID visitId) {
        return ResponseEntity.ok(visitManagementService.getVisitDetail(account, visitId));
    }

    @PatchMapping("/visits/{visitId}/checklist")
    public ResponseEntity<VisitDetailResponse> updateChecklist(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID visitId,
            @RequestBody com.catsitter.api.dto.visit.UpdateChecklistRequest request) {
        return ResponseEntity.ok(visitManagementService.updateChecklistItem(account, visitId, request));
    }

    @PostMapping("/visits/{visitId}/complete")
    public ResponseEntity<VisitDetailResponse> completeVisit(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID visitId) {
        return ResponseEntity.ok(visitManagementService.completeVisit(account, visitId));
    }
}
