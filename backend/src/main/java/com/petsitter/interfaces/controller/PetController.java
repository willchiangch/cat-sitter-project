package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.PetDto;
import com.petsitter.application.dto.PetNotesDto;
import com.petsitter.application.service.PetService;
import com.petsitter.domain.model.PetEditLog;
import com.petsitter.infrastructure.security.TokenContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/pets")
@RequiredArgsConstructor
public class PetController {

    private final PetService petService;

    // GET /api/pets
    @GetMapping
    public ResponseEntity<Map<String, Object>> getPets() {
        UUID ownerId = TokenContext.getUserId();
        List<PetDto> pets = petService.getPetsByOwner(ownerId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", pets
        ));
    }

    // GET /api/pets/{petId}
    @GetMapping("/{petId}")
    public ResponseEntity<Map<String, Object>> getPetById(@PathVariable UUID petId) {
        UUID userId = TokenContext.getUserId();
        PetDto pet = petService.getPetById(petId, userId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", pet
        ));
    }

    // POST /api/pets
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPet(@RequestBody PetDto dto) {
        UUID ownerId = TokenContext.getUserId();
        PetDto created = petService.createPet(ownerId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 201,
                "message", "OK",
                "data", created
        ));
    }

    // PUT /api/pets/{petId}
    @PutMapping("/{petId}")
    public ResponseEntity<Map<String, Object>> updatePet(
            @PathVariable UUID petId,
            @RequestBody PetDto dto) {
        UUID ownerId = TokenContext.getUserId();
        PetDto updated = petService.updatePet(ownerId, petId, dto);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", updated
        ));
    }

    // DELETE /api/pets/{petId}
    @DeleteMapping("/{petId}")
    public ResponseEntity<Map<String, Object>> deletePet(@PathVariable UUID petId) {
        UUID ownerId = TokenContext.getUserId();
        petService.deletePet(ownerId, petId);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "刪除成功");
        response.put("data", null);
        return ResponseEntity.ok(response);
    }

    // PUT /api/pets/{petId}/notes
    @PutMapping("/{petId}/notes")
    public ResponseEntity<Map<String, Object>> updatePetNotes(
            @PathVariable UUID petId,
            @RequestBody PetNotesDto dto) {
        UUID userId = TokenContext.getUserId();
        PetDto updated = petService.updatePetNotes(userId, petId, dto);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", updated
        ));
    }

    // GET /api/pets/{petId}/edit-logs
    @GetMapping("/{petId}/edit-logs")
    public ResponseEntity<Map<String, Object>> getEditLogs(@PathVariable UUID petId) {
        UUID userId = TokenContext.getUserId();
        List<PetEditLog> logs = petService.getEditLogs(petId, userId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", logs
        ));
    }

    // POST /api/pets/{petId}/avatar
    @PostMapping("/{petId}/avatar")
    public ResponseEntity<Map<String, Object>> uploadAvatar(
            @PathVariable UUID petId,
            @RequestParam("file") MultipartFile file) {
        UUID ownerId = TokenContext.getUserId();
        String photoUrl = petService.uploadAvatar(ownerId, petId, file);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "上傳成功",
                "data", Map.of("photoUrl", photoUrl)
        ));
    }
}
