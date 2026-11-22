package com.petsitter.domain.model.fulfill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 上傳槽位（{@code care_log_media_uploads}，SD-FULFILL §3.3 / §5.1 第 16 段）。不用 auditing。 */
@Entity
@Table(name = "care_log_media_uploads")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceReportUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "visit_id", nullable = false, updatable = false)
    private UUID visitId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "sitter_id", nullable = false, updatable = false)
    private UUID sitterId;

    @Column(name = "media_type", nullable = false, updatable = false)
    private String mediaType;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "declared_size", nullable = false, updatable = false)
    private int declaredSize;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "declared_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String declaredSha256;

    @Column(name = "declared_duration_seconds", updatable = false)
    private Short declaredDurationSeconds;

    @Column(length = 100, updatable = false)
    private String caption;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(nullable = false)
    private String status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "object_deleted_at")
    private OffsetDateTime objectDeletedAt;

    @Column(name = "delete_attempts", nullable = false)
    private short deleteAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
