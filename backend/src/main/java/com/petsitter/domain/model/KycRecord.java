package com.petsitter.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "kyc_records")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class KycRecord extends BaseEntity {

    @Column(name = "sitter_id", nullable = false)
    private UUID sitterId;

    @Column(name = "id_card_front_key", nullable = false, length = 512)
    private String idCardFrontKey;

    @Column(name = "selfie_key", nullable = false, length = 512)
    private String selfieKey;

    @Builder.Default
    @Column(nullable = false, length = 50)
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;
}
