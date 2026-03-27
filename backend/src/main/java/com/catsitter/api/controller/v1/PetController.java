package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.client.CreatePetRequest;
import com.catsitter.api.dto.client.PetResponse;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.ClientPetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clients/me/pets")
public class PetController {

    private final ClientPetService clientPetService;

    public PetController(ClientPetService clientPetService) {
        this.clientPetService = clientPetService;
    }

    @GetMapping
    public ResponseEntity<List<PetResponse>> listPets(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(clientPetService.getClientPets(account));
    }

    @PostMapping
    public ResponseEntity<PetResponse> createPet(
            @AuthenticationPrincipal Account account,
            @Valid @RequestBody CreatePetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientPetService.createPet(account, request));
    }

    @GetMapping("/{petId}")
    public ResponseEntity<PetResponse> getPet(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID petId) {
        return ResponseEntity.ok(clientPetService.getPet(account, petId));
    }

    @PutMapping("/{petId}")
    public ResponseEntity<PetResponse> updatePet(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID petId,
            @Valid @RequestBody CreatePetRequest request) {
        return ResponseEntity.ok(clientPetService.updatePet(account, petId, request));
    }

    @DeleteMapping("/{petId}")
    public ResponseEntity<Void> deletePet(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID petId) {
        clientPetService.deletePet(account, petId);
        return ResponseEntity.noContent().build();
    }
}
