package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.SitterQuestionDto;
import com.petsitter.application.dto.SitterQuestionSortRequest;
import com.petsitter.application.service.SitterQuestionService;
import com.petsitter.infrastructure.security.TokenContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SitterQuestionController {

    private final SitterQuestionService sitterQuestionService;

    // 1. 保母查詢自己的問卷題目（含停用中的）
    @GetMapping("/api/sitter/questions")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<List<SitterQuestionDto>> getMyQuestions() {
        UUID sitterId = TokenContext.getUserId();
        return ResponseEntity.ok(sitterQuestionService.getMyQuestions(sitterId));
    }

    // 2. 供 PRD-005 預約流程查詢指定保母目前啟用中的問卷題目（公開）
    @GetMapping("/api/sitters/{sitterId}/questions")
    public ResponseEntity<List<SitterQuestionDto>> getActiveQuestionsForBooking(@PathVariable UUID sitterId) {
        return ResponseEntity.ok(sitterQuestionService.getActiveQuestionsForBooking(sitterId));
    }

    // 3. 新增問題
    @PostMapping("/api/sitter/questions")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> createQuestion(@Valid @RequestBody SitterQuestionDto dto) {
        UUID sitterId = TokenContext.getUserId();
        SitterQuestionDto result = sitterQuestionService.createQuestion(sitterId, dto);
        return ResponseEntity.ok(Map.of("code", 200, "message", "新增成功", "data", result));
    }

    // 4. 編輯問題
    @PutMapping("/api/sitter/questions/{questionId}")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> updateQuestion(
            @PathVariable UUID questionId,
            @Valid @RequestBody SitterQuestionDto dto) {
        UUID sitterId = TokenContext.getUserId();
        SitterQuestionDto result = sitterQuestionService.updateQuestion(sitterId, questionId, dto);
        return ResponseEntity.ok(Map.of("code", 200, "message", "修改成功", "data", result));
    }

    // 5. 刪除問題（邏輯刪除）
    @DeleteMapping("/api/sitter/questions/{questionId}")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> deleteQuestion(@PathVariable UUID questionId) {
        UUID sitterId = TokenContext.getUserId();
        sitterQuestionService.deleteQuestion(sitterId, questionId);
        return ResponseEntity.ok(Map.of("code", 200, "message", "刪除成功", "data", (Object) null));
    }

    // 6. 啟用/停用問題
    @PutMapping("/api/sitter/questions/{questionId}/active")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> toggleActive(
            @PathVariable UUID questionId,
            @RequestBody Map<String, Boolean> body) {
        UUID sitterId = TokenContext.getUserId();
        boolean active = Boolean.TRUE.equals(body.get("active"));
        SitterQuestionDto result = sitterQuestionService.toggleActive(sitterId, questionId, active);
        return ResponseEntity.ok(Map.of("code", 200, "message", "已更新狀態", "data", result));
    }

    // 7. 調整問題排序
    @PostMapping("/api/sitter/questions/sort")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> reorderQuestions(@RequestBody SitterQuestionSortRequest request) {
        UUID sitterId = TokenContext.getUserId();
        sitterQuestionService.reorderQuestions(sitterId, request);
        return ResponseEntity.ok(Map.of("code", 200, "message", "排序已更新", "data", (Object) null));
    }
}
