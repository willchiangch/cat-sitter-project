package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Entity
@Table(name = "client_favorite_sitters")
public class ClientFavoriteSitter extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "client_profile_id", nullable = false)
  private Profile clientProfile;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sitter_profile_id", nullable = false)
  private Profile sitterProfile;

  /** true = 顯示於最愛；false = 隱藏/不再合作 */
  @Column(name = "is_favorite", nullable = false)
  private Boolean isFavorite = true;

  public UUID getId() { return id; }
  public Profile getClientProfile() { return clientProfile; }
  public void setClientProfile(Profile clientProfile) { this.clientProfile = clientProfile; }
  public Profile getSitterProfile() { return sitterProfile; }
  public void setSitterProfile(Profile sitterProfile) { this.sitterProfile = sitterProfile; }
  public Boolean getIsFavorite() { return isFavorite; }
  public void setIsFavorite(Boolean isFavorite) { this.isFavorite = isFavorite; }
}
