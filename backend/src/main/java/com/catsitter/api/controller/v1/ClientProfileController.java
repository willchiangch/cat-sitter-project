package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.client.ClientProfileResponse;
import com.catsitter.api.dto.client.UpdateClientProfileRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.ClientPetService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/clients/me/profile")
public class ClientProfileController {

    private final ClientPetService clientPetService;

    public ClientProfileController(ClientPetService clientPetService) {
        this.clientPetService = clientPetService;
    }

    @GetMapping
    public ResponseEntity<ClientProfileResponse> getProfile(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(clientPetService.getClientProfile(account));
    }

    @PutMapping
    public ResponseEntity<ClientProfileResponse> updateProfile(
            @AuthenticationPrincipal Account account,
            @Valid @RequestBody UpdateClientProfileRequest request) {
        return ResponseEntity.ok(clientPetService.updateClientProfile(account, request));
    }
}
