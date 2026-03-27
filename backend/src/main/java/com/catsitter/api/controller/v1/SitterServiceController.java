package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.sitter.CreateServiceRequest;
import com.catsitter.api.dto.sitter.ServicePlanResponse;
import com.catsitter.api.dto.sitter.UpdateServiceRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.SitterServiceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sitters/me/services")
public class SitterServiceController {

    private final SitterServiceService sitterServiceService;

    public SitterServiceController(SitterServiceService sitterServiceService) {
        this.sitterServiceService = sitterServiceService;
    }

    @GetMapping
    public ResponseEntity<List<ServicePlanResponse>> listServices(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(sitterServiceService.getSitterServices(account));
    }

    @PostMapping
    public ResponseEntity<ServicePlanResponse> createService(
            @AuthenticationPrincipal Account account,
            @Valid @RequestBody CreateServiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sitterServiceService.createService(account, request));
    }

    @PutMapping("/{serviceId}")
    public ResponseEntity<ServicePlanResponse> updateService(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID serviceId,
            @Valid @RequestBody UpdateServiceRequest request) {
        return ResponseEntity.ok(sitterServiceService.updateService(account, serviceId, request));
    }

    @DeleteMapping("/{serviceId}")
    public ResponseEntity<Void> deleteService(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID serviceId) {
        sitterServiceService.deleteService(account, serviceId);
        return ResponseEntity.noContent().build();
    }
}
