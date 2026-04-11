package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.AccountStatus;
import com.catsitter.api.entity.enums.OAuthProvider;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@org.hibernate.annotations.SQLDelete(sql = "UPDATE accounts SET deleted_at = NOW() WHERE id = ?")
@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")
public class Account extends AuditableEntity implements UserDetails {

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

  @Enumerated(EnumType.STRING)
  @Column(name = "last_active_role", length = 50)
  private com.catsitter.api.entity.enums.RoleType lastActiveRole;

  @Column(name = "is_email_verified", nullable = false)
  private boolean emailVerified = false;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
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
  public com.catsitter.api.entity.enums.RoleType getLastActiveRole() { return lastActiveRole; }
  public void setLastActiveRole(com.catsitter.api.entity.enums.RoleType lastActiveRole) { this.lastActiveRole = lastActiveRole; }
  public boolean isEmailVerified() { return emailVerified; }
  public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public boolean isAccountNonExpired() {
    return status != AccountStatus.SUSPENDED;
  }

  @Override
  public boolean isAccountNonLocked() {
    return status != AccountStatus.SUSPENDED;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return status == AccountStatus.ACTIVE;
  }
}
