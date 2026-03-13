package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.AccountStatus;
import com.catsitter.api.entity.enums.OAuthProvider;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Email
  @NotBlank
  @Column(nullable = false, unique = true, length = 255)
  private String email;

  @Column(name = "password_hash", length = 255)
  private String passwordHash;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "oauth_provider", nullable = false, length = 50)
  private OAuthProvider oauthProvider;

  @Column(name = "oauth_id", length = 255)
  private String oauthId;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private AccountStatus status;

  public UUID getId() { return id; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public OAuthProvider getOauthProvider() { return oauthProvider; }
  public void setOauthProvider(OAuthProvider oauthProvider) { this.oauthProvider = oauthProvider; }
  public String getOauthId() { return oauthId; }
  public void setOauthId(String oauthId) { this.oauthId = oauthId; }
  public AccountStatus getStatus() { return status; }
  public void setStatus(AccountStatus status) { this.status = status; }
}
