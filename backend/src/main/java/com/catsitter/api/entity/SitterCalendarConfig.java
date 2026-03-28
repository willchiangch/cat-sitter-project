package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sitter_calendar_configs")
public class SitterCalendarConfig extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sitter_profile_id", nullable = false)
    private Profile sitterProfile;

    @NotNull
    @Column(nullable = false, length = 50)
    private String provider; // e.g., 'GOOGLE'

    @NotNull
    @Column(name = "access_token", columnDefinition = "TEXT", nullable = false)
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "ical_token", unique = true, length = 64)
    private String icalToken;

    public UUID getId() { return id; }
    public Profile getSitterProfile() { return sitterProfile; }
    public void setSitterProfile(Profile sitterProfile) { this.sitterProfile = sitterProfile; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getIcalToken() { return icalToken; }
    public void setIcalToken(String icalToken) { this.icalToken = icalToken; }
}
