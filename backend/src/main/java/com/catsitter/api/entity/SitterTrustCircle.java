package com.catsitter.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.TrustCircleStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Entity
@Table(name = "sitter_trust_circles")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SitterTrustCircle extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @com.fasterxml.jackson.annotation.JsonIgnore
  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_sitter_id", nullable = false)
  private Profile ownerSitter;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "trusted_sitter_id", nullable = false)
  private Profile trustedSitter;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private TrustCircleStatus status;

  public UUID getId() { return id; }
  public Profile getOwnerSitter() { return ownerSitter; }
  public void setOwnerSitter(Profile ownerSitter) { this.ownerSitter = ownerSitter; }
  public Profile getTrustedSitter() { return trustedSitter; }
  public void setTrustedSitter(Profile trustedSitter) { this.trustedSitter = trustedSitter; }
  public TrustCircleStatus getStatus() { return status; }
  public void setStatus(TrustCircleStatus status) { this.status = status; }
}
