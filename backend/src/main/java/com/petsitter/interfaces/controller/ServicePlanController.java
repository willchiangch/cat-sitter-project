package com.petsitter.interfaces.controller;

import com.petsitter.application.dto.ServicePlanDto;
import com.petsitter.application.dto.ServicePlanSortRequest;
import com.petsitter.application.service.ServicePlanService;
import com.petsitter.infrastructure.security.TokenContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ServicePlanController {

    private final ServicePlanService servicePlanService;

    // 1. 建立服務方案
    @PostMapping("/sitter/plans")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> createPlan(@Valid @RequestBody ServicePlanDto dto) {
        UUID sitterId = TokenContext.getUserId();
        ServicePlanDto result = servicePlanService.createPlan(dto, sitterId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "新增成功",
                "data", result
        ));
    }

    // 2. 編輯服務方案
    @PutMapping("/sitter/plans/{planId}")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> updatePlan(
            @PathVariable UUID planId,
            @Valid @RequestBody ServicePlanDto dto) {
        UUID sitterId = TokenContext.getUserId();
        ServicePlanDto result = servicePlanService.updatePlan(planId, dto, sitterId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "修改成功",
                "data", result
        ));
    }

    // 3. 下架/邏輯刪除服務方案
    @DeleteMapping("/sitter/plans/{planId}")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> deletePlan(@PathVariable UUID planId) {
        UUID sitterId = TokenContext.getUserId();
        servicePlanService.deletePlan(planId, sitterId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "刪除成功");
        response.put("data", null);
        return ResponseEntity.ok(response);
    }

    // 3-1. 上架/下架切換 (可逆狀態，與第 3 點的邏輯刪除分離)
    @PatchMapping("/sitter/plans/{planId}/active")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> setPlanActive(
            @PathVariable UUID planId,
            @RequestBody Map<String, Boolean> body) {
        UUID sitterId = TokenContext.getUserId();
        boolean isActive = Boolean.TRUE.equals(body.get("isActive"));
        ServicePlanDto result = servicePlanService.setPlanActive(planId, isActive, sitterId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", isActive ? "已上架" : "已下架",
                "data", result
        ));
    }

    // 4. 方案排序調整
    @PostMapping("/sitter/plans/sort")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> sortPlans(@RequestBody ServicePlanSortRequest request) {
        UUID sitterId = TokenContext.getUserId();
        servicePlanService.sortPlans(request, sitterId);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "修改成功");
        response.put("data", null);
        return ResponseEntity.ok(response);
    }

    // 5. 保母查詢自訂方案列表
    @GetMapping("/sitter/plans")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<Map<String, Object>> getPlansForSitter() {
        UUID sitterId = TokenContext.getUserId();
        List<ServicePlanDto> result = servicePlanService.getPlansForSitter(sitterId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", result
        ));
    }

    // 6. 前台飼主查詢保母開放方案列表
    @GetMapping("/sitters/{sitterId}/plans")
    public ResponseEntity<Map<String, Object>> getActivePlansForOwner(
            @PathVariable UUID sitterId) {
        UUID currentUserId = null;
        try {
            currentUserId = TokenContext.getUserId();
        } catch (Exception e) {
            // 免登入時可為 null
        }
        List<ServicePlanDto> result = servicePlanService.getActivePlansForOwner(sitterId, currentUserId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "OK",
                "data", result
        ));
    }
}
