package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.ForbiddenKeywordDto;
import com.petsitter.application.service.SitterPublicProfileService;
import com.petsitter.infrastructure.security.TokenContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/forbidden-keywords")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminForbiddenKeywordController {

    private final SitterPublicProfileService publicProfileService;

    /**
     * 分頁查詢敏感詞 (Admin)
     */
    @GetMapping
    public ResponseEntity<Page<ForbiddenKeywordDto>> getForbiddenKeywords(
            @RequestParam(value = "q", required = false) String q,
            @PageableDefault(size = 10) Pageable pageable) {
        
        log.info("Admin querying forbidden keywords, q: {}, pageable: {}", q, pageable);
        Page<ForbiddenKeywordDto> result = publicProfileService.getForbiddenKeywords(q, pageable);
        return ResponseEntity.ok(result);
    }

    /**
     * 新增敏感詞 (Admin)
     */
    @PostMapping
    public ResponseEntity<ForbiddenKeywordDto> addForbiddenKeyword(
            @RequestBody Map<String, String> request) {
        
        String keyword = request.get("keyword");
        UUID adminId = TokenContext.getUserId();
        log.info("Admin {} adding forbidden keyword: {}", adminId, keyword);
        ForbiddenKeywordDto dto = publicProfileService.addForbiddenKeyword(keyword, adminId);
        return ResponseEntity.ok(dto);
    }

    /**
     * 刪除敏感詞 (Admin)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteForbiddenKeyword(
            @PathVariable("id") UUID id) {
        
        UUID adminId = TokenContext.getUserId();
        log.info("Admin {} deleting forbidden keyword: {}", adminId, id);
        publicProfileService.deleteForbiddenKeyword(id, adminId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "敏感詞已成功刪除"));
    }
}
