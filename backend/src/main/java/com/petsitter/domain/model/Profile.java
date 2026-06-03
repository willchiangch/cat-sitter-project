package com.petsitter.domain.model;

import com.petsitter.infrastructure.security.BankAccountInfoCryptoConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String type; // SITTER, CLIENT

    @Builder.Default
    @Column(name = "trust_score", nullable = false)
    private int trustScore = 100;

    @Builder.Default
    @Column(name = "kyc_status", nullable = false, length = 50)
    private String kycStatus = "PENDING";

    @Convert(converter = BankAccountInfoCryptoConverter.class)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "bank_account_info", columnDefinition = "jsonb")
    private BankAccountInfo bankAccountInfo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
