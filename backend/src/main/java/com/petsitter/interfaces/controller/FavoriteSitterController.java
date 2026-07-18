package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.FavoriteSitterDto;
import com.petsitter.application.dto.SitterSearchResultDto;
import com.petsitter.application.service.FavoriteSitterService;
import com.petsitter.infrastructure.security.TokenContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/owner/favorites")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class FavoriteSitterController {

    private final FavoriteSitterService favoriteSitterService;

    @GetMapping
    public ResponseEntity<List<FavoriteSitterDto>> listFavorites() {
        UUID ownerId = TokenContext.getUserId();
        return ResponseEntity.ok(favoriteSitterService.listFavorites(ownerId));
    }

    @GetMapping("/search")
    public ResponseEntity<SitterSearchResultDto> searchSitter(@RequestParam String query) {
        return ResponseEntity.ok(favoriteSitterService.searchSitter(query));
    }

    @PostMapping("/{sitterId}")
    public ResponseEntity<Map<String, String>> addFavorite(@PathVariable UUID sitterId) {
        UUID ownerId = TokenContext.getUserId();
        favoriteSitterService.addFavorite(ownerId, sitterId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已加入我的最愛"));
    }

    @DeleteMapping("/{sitterId}")
    public ResponseEntity<Map<String, String>> removeFavorite(@PathVariable UUID sitterId) {
        UUID ownerId = TokenContext.getUserId();
        favoriteSitterService.removeFavorite(ownerId, sitterId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "已移除收藏"));
    }
}
