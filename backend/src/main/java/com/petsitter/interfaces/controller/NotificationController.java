package com.petsitter.interfaces.controller;

import com.petsitter.application.service.NotificationService;
import com.petsitter.domain.model.Notification;
import com.petsitter.infrastructure.security.TokenContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(required = false) String role) {

        UUID userId = TokenContext.getUserId();
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> notifications = notificationService.getNotifications(userId, role, isRead, pageable);

        List<Map<String, Object>> content = notifications.getContent().stream()
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "title", n.getTitle(),
                        "content", n.getContent(),
                        "category", n.getCategory(),
                        "isRead", n.isRead(),
                        "createdAt", n.getCreatedAt(),
                        "linkUrl", n.getLinkUrl() != null ? n.getLinkUrl() : "",
                        "roleTarget", n.getRoleTarget()
                ))
                .collect(Collectors.toList());

        Map<String, Object> data = Map.of(
                "content", content,
                "page", notifications.getNumber(),
                "size", notifications.getSize(),
                "totalElements", notifications.getTotalElements(),
                "totalPages", notifications.getTotalPages()
        );

        return successResponse("OK", data);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(
            @RequestParam(required = false) String role) {
        UUID userId = TokenContext.getUserId();
        long count = notificationService.getUnreadCount(userId, role);
        return successResponse("OK", count);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markAsRead(
            @PathVariable UUID id) {
        UUID userId = TokenContext.getUserId();
        notificationService.markAsRead(id, userId);
        return successResponse("標示已讀成功", null);
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllAsRead(
            @RequestParam(required = false) String role) {
        UUID userId = TokenContext.getUserId();
        notificationService.markAllAsRead(userId, role);
        return successResponse("全部標示已讀成功", null);
    }

    @GetMapping("/preferences")
    public ResponseEntity<Map<String, Object>> getPreferences() {
        UUID userId = TokenContext.getUserId();
        List<NotificationService.PreferenceDto> preferences = notificationService.getPreferences(userId);
        return successResponse("OK", preferences);
    }

    @PutMapping("/preferences")
    public ResponseEntity<Map<String, Object>> updatePreference(
            @Valid @RequestBody UpdatePreferenceRequest request) {
        UUID userId = TokenContext.getUserId();
        notificationService.updatePreference(
                userId,
                request.getCategory(),
                request.isEnableInApp(),
                request.isEnableEmail()
        );
        return successResponse("通知偏好更新成功", null);
    }

    /**
     * 封裝回應結構之 Helper 方法，使用傳統 HashMap 容許 data 屬性為 null，防禦 Map.of 拋出 NullPointerException
     */
    private ResponseEntity<Map<String, Object>> successResponse(String message, Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", message);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    @Data
    public static class UpdatePreferenceRequest {
        @NotBlank(message = "通知類別不可為空")
        private String category;
        private boolean enableInApp;
        private boolean enableEmail;
    }
}
