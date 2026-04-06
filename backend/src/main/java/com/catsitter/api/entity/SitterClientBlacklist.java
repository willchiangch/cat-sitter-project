package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Entity
@Table(name = "sitter_client_blacklists", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"sitter_profile_id", "client_profile_id"})
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SitterClientBlacklist extends AuditableEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sitter_profile_id", nullable = false)
    private Profile sitterProfile;

    @NotNull @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_profile_id", nullable = false)
    private Profile clientProfile;

    @Column(columnDefinition = "TEXT")
    private String reason;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Profile getSitterProfile() { return sitterProfile; }
    public void setSitterProfile(Profile sitterProfile) { this.sitterProfile = sitterProfile; }
    public Profile getClientProfile() { return clientProfile; }
    public void setClientProfile(Profile clientProfile) { this.clientProfile = clientProfile; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
