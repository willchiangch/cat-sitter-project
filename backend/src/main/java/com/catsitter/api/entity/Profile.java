package com.catsitter.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.RoleType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "profiles")
@org.hibernate.annotations.SQLDelete(sql = "UPDATE profiles SET deleted_at = NOW() WHERE id = ?")
@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Profile extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;
  
  @Column(unique = true)
  private String slug;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "role_type", nullable = false, length = 50)
  private RoleType roleType;

  @NotBlank
  @Column(nullable = false, unique = true, length = 255)
  private String name;

  @Column(name = "avatar_url", length = 1024)
  private String avatarUrl;

  @Column(length = 50)
  private String phone;

  @Column(length = 255)
  private String address;

  /** SITTER 專用：服務區域，如 ["新莊區", "板橋區"] */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "service_areas")
  private List<String> serviceAreas;

  @Column(name = "bio_summary", columnDefinition = "TEXT")
  private String bioSummary;

  @Column(name = "refusal_criteria", columnDefinition = "TEXT")
  private String refusalCriteria;

  @Column(name = "booking_open_start")
  private LocalDate bookingOpenStart;

  @Column(name = "booking_open_end")
  private LocalDate bookingOpenEnd;

  /** SITTER 專用：每週固定可用時段 {"MONDAY": ["09:00-12:00"], ...} */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "weekly_availability")
  private java.util.Map<java.time.DayOfWeek, List<String>> weeklyAvailability;

  /** SITTER 專用：特定排除日期 ["2024-04-04", ...] */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "specific_exclusions")
  private List<LocalDate> specificExclusions;

  /** V30 新增：身分驗證相關 */
  @Column(name = "is_verified", nullable = false)
  private Boolean isVerified = false;

  @Column(name = "verified_at")
  private java.time.OffsetDateTime verifiedAt;

  @Column(name = "id_card_front_url", length = 1024)
  private String idCardFrontUrl;

  @Column(name = "face_photo_url", length = 1024)
  private String facePhotoUrl;

  /** V30 新增：專業標籤 (純文字列表) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "professional_labels")
  private List<String> professionalLabels;

  /** V30 新增：撥款資訊 (選填) */
  @Column(name = "bank_code", length = 10)
  private String bankCode;

  @Column(name = "bank_account", length = 50)
  private String bankAccount;

  @Column(name = "bank_account_holder", length = 100)
  private String bankAccountHolder;

  @PrePersist
  public void generateSlug() {
    if (this.slug == null && this.roleType == RoleType.SITTER) {
      this.slug = UUID.randomUUID().toString().substring(0, 8);
    }
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public String getSlug() { return slug; }
  public void setSlug(String slug) { this.slug = slug; }
  public Account getAccount() { return account; }
  public void setAccount(Account account) { this.account = account; }
  public RoleType getRoleType() { return roleType; }
  public void setRoleType(RoleType roleType) { this.roleType = roleType; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getAvatarUrl() { return avatarUrl; }
  public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
  public String getPhone() { return phone; }
  public void setPhone(String phone) { this.phone = phone; }
  public String getAddress() { return address; }
  public void setAddress(String address) { this.address = address; }
  public List<String> getServiceAreas() { return serviceAreas; }
  public void setServiceAreas(List<String> serviceAreas) { this.serviceAreas = serviceAreas; }
  public String getBioSummary() { return bioSummary; }
  public void setBioSummary(String bioSummary) { this.bioSummary = bioSummary; }
  public String getRefusalCriteria() { return refusalCriteria; }
  public void setRefusalCriteria(String refusalCriteria) { this.refusalCriteria = refusalCriteria; }
  public LocalDate getBookingOpenStart() { return bookingOpenStart; }
  public void setBookingOpenStart(LocalDate bookingOpenStart) { this.bookingOpenStart = bookingOpenStart; }
  public LocalDate getBookingOpenEnd() { return bookingOpenEnd; }
  public void setBookingOpenEnd(LocalDate bookingOpenEnd) { this.bookingOpenEnd = bookingOpenEnd; }
  public java.util.Map<java.time.DayOfWeek, List<String>> getWeeklyAvailability() { return weeklyAvailability; }
  public void setWeeklyAvailability(java.util.Map<java.time.DayOfWeek, List<String>> weeklyAvailability) { this.weeklyAvailability = weeklyAvailability; }
  public List<LocalDate> getSpecificExclusions() { return specificExclusions; }
  public void setSpecificExclusions(List<LocalDate> specificExclusions) { this.specificExclusions = specificExclusions; }

  public Boolean getIsVerified() { return isVerified; }
  public void setIsVerified(Boolean verified) { isVerified = verified; }
  public java.time.OffsetDateTime getVerifiedAt() { return verifiedAt; }
  public void setVerifiedAt(java.time.OffsetDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
  public String getIdCardFrontUrl() { return idCardFrontUrl; }
  public void setIdCardFrontUrl(String idCardFrontUrl) { this.idCardFrontUrl = idCardFrontUrl; }
  public String getFacePhotoUrl() { return facePhotoUrl; }
  public void setFacePhotoUrl(String facePhotoUrl) { this.facePhotoUrl = facePhotoUrl; }
  public List<String> getProfessionalLabels() { return professionalLabels; }
  public void setProfessionalLabels(List<String> professionalLabels) { this.professionalLabels = professionalLabels; }
  public String getBankCode() { return bankCode; }
  public void setBankCode(String bankCode) { this.bankCode = bankCode; }
  public String getBankAccount() { return bankAccount; }
  public void setBankAccount(String bankAccount) { this.bankAccount = bankAccount; }
  public String getBankAccountHolder() { return bankAccountHolder; }
  public void setBankAccountHolder(String bankAccountHolder) { this.bankAccountHolder = bankAccountHolder; }
}
