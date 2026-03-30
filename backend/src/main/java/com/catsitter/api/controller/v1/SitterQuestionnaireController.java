package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.sitter.CreateQuestionRequest;
import com.catsitter.api.dto.sitter.QuestionItemResponse;
import com.catsitter.api.dto.sitter.ReorderQuestionRequest;
import com.catsitter.api.dto.sitter.UpdateQuestionRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.service.SitterQuestionnaireService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sitters/me/questionnaires")
public class SitterQuestionnaireController {

    private final SitterQuestionnaireService questionnaireService;

    public SitterQuestionnaireController(SitterQuestionnaireService questionnaireService) {
        this.questionnaireService = questionnaireService;
    }

    @GetMapping
    public ResponseEntity<List<QuestionItemResponse>> listQuestions(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(questionnaireService.getSitterQuestions(account));
    }

    @PostMapping
    public ResponseEntity<QuestionItemResponse> createQuestion(
            @AuthenticationPrincipal Account account,
            @Valid @RequestBody CreateQuestionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(questionnaireService.createQuestion(account, request));
    }

    @PutMapping("/{questionId}")
    public ResponseEntity<QuestionItemResponse> updateQuestion(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID questionId,
            @Valid @RequestBody UpdateQuestionRequest request) {
        return ResponseEntity.ok(questionnaireService.updateQuestion(account, questionId, request));
    }

    @PatchMapping("/reorder")
    public ResponseEntity<Void> reorderQuestions(
            @AuthenticationPrincipal Account account,
            @RequestBody ReorderQuestionRequest request) {
        questionnaireService.reorderQuestions(account, request.questionIds());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{questionId}")
    public ResponseEntity<Void> deleteQuestion(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID questionId) {
        questionnaireService.deleteQuestion(account, questionId);
        return ResponseEntity.noContent().build();
    }
}
