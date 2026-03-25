package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.RoleType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "profiles")
public class Profile extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "role_type", nullable = false, length = 50)
  private RoleType roleType;

  @NotBlank
  @Column(nullable = false, length = 255)
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

  public UUID getId() { return id; }
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
}
