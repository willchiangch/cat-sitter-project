package com.petsitter.interfaces.controller;

import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
// SD-GLOBAL-SPEC §3.4.1：不在匿名白名單的 handler 必須掛 @PreAuthorize（EndpointAccessRuleArchTest）
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(required = false) String role) {

        UUID userId = TokenContext.getUserId();
        // GLOBAL §2.8：size 上限 100，超過 400（不靜默截斷）
        com.petsitter.application.common.validation.PageParams pp = com.petsitter.application.common.validation.PageParams.of(page, size);
        PageRequest pageable = PageRequest.of(pp.page(), pp.size(), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> notifications = notificationService.getNotifications(userId, role, isRead, pageable);

        // SD-GLOBAL-SPEC §4.4：不用 Map.of（不接受 null）；type 為 null（舊 createNotification 寫入）時不輸出
        List<Map<String, Object>> content = notifications.getContent().stream()
                .map(NotificationController::toItem)
                .collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        data.put("page", notifications.getNumber());
        data.put("size", notifications.getSize());
        data.put("totalElements", notifications.getTotalElements());
        data.put("totalPages", notifications.getTotalPages());

        return successResponse("OK", data);
    }

    static Map<String, Object> toItem(Notification n) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", n.getId());
        if (n.getType() != null) {
            item.put("type", n.getType());
        }
        item.put("title", n.getTitle());
        item.put("content", n.getContent());
        item.put("category", n.getCategory());
        item.put("isRead", n.isRead());
        item.put("createdAt", n.getCreatedAt());
        item.put("linkUrl", n.getLinkUrl() != null ? n.getLinkUrl() : "");
        item.put("roleTarget", n.getRoleTarget());
        return item;
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
