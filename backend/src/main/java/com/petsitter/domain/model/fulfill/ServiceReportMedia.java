package com.petsitter.domain.model.fulfill;

import com.petsitter.domain.model.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 日誌媒體（SD-FULFILL §5.1 第 16 段）。建立後不可修改；刪除為實刪。 */
@Entity
@Table(name = "service_report_media")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class ServiceReportMedia extends AuditedEntity {

    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "upload_id", nullable = false, updatable = false)
    private UUID uploadId;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "object_generation", nullable = false, updatable = false)
    private long objectGeneration;

    @Column(name = "media_type", nullable = false, updatable = false)
    private String mediaType; // IMAGE, VIDEO

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(name = "duration_seconds", updatable = false)
    private Short durationSeconds;

    @Column(length = 100, updatable = false)
    private String caption;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;

    @Column(name = "is_purged", nullable = false)
    private boolean purged;

    @Column(name = "purged_at")
    private OffsetDateTime purgedAt;
}
