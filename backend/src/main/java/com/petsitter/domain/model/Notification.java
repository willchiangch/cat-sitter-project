package com.petsitter.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public class Notification extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 50)
    private String category;

    @Builder.Default
    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "link_url", length = 255)
    private String linkUrl;

    @Builder.Default
    @Column(name = "role_target", nullable = false, length = 20)
    private String roleTarget = "ALL";

    // --- outbox 投遞欄位（V20261122_02；SD-ORDER §2.3.3）---

    /** 通知類型代碼（SD-ORDER §7.2）；舊 createNotification 寫入者為 null。 */
    @Column(name = "type", length = 48)
    private String type;

    /** 不可關閉類別（baseline CQ-73）：Email 不受偏好影響。 */
    @Builder.Default
    @Column(name = "mandatory", nullable = false)
    private boolean mandatory = false;

    @Column(name = "related_order_id")
    private UUID relatedOrderId;

    /** PENDING / SENT / SKIPPED_PREFERENCE / FAILED / NOT_APPLICABLE / SUPPRESSED_TEST（SD-ORDER §10.4）。 */
    @Builder.Default
    @Column(name = "email_status", nullable = false, length = 24)
    private String emailStatus = "NOT_APPLICABLE";

    @Builder.Default
    @Column(name = "email_attempts", nullable = false)
    private short emailAttempts = 0;

    @Column(name = "email_next_attempt_at")
    private OffsetDateTime emailNextAttemptAt;

    @Column(name = "email_last_error", length = 500)
    private String emailLastError;
}
