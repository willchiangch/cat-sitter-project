package com.petsitter.interfaces.controller;

import com.petsitter.application.service.CompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/internal/cron")
@RequiredArgsConstructor
public class InternalCronController {

    private final CompletionService completionService;

    /**
     * 外部排程器觸發自動結案
     */
    @PostMapping("/orders/auto-complete")
    public ResponseEntity<Map<String, String>> triggerAutoComplete() {
        log.info("[InternalCronController] Received auto-complete trigger");
        completionService.triggerAutoCompletion();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Auto-completion task triggered"));
    }
}
