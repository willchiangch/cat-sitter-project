package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "care_media")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareMedia implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(name = "sitter_id", nullable = false)
    private UUID sitterId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String caption;

    @Column(name = "media_url", nullable = false, length = 1024)
    private String mediaUrl;

    @Column(name = "media_type", nullable = false, length = 50)
    private String mediaType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
