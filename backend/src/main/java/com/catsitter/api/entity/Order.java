package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.OrderStatus;
import com.catsitter.api.entity.enums.PaymentStatus;
import com.catsitter.api.entity.enums.QuestionnaireStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "orders")
@org.hibernate.annotations.SQLDelete(sql = "UPDATE orders SET deleted_at = NOW() WHERE id = ?")
@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")
public class Order extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "client_profile_id", nullable = false)
  private Profile clientProfile;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "current_sitter_id", nullable = false)
  private Profile currentSitter;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "service_id", nullable = false)
  private Service service;

  /** 快照：方案名稱（與 services.name 解耦） */
  @Column(name = "service_name", length = 255)
  private String serviceName;

  /** 快照：方案單價（與 services.base_price 解耦） */
  @Column(name = "service_unit_price", precision = 10, scale = 2)
  private BigDecimal serviceUnitPrice;

  @NotNull
  @Column(name = "base_amount", nullable = false, precision = 10, scale = 2)
  private BigDecimal baseAmount;

  @NotNull
  @PositiveOrZero
  @Column(name = "surcharge_amount", nullable = false, precision = 10, scale = 2)
  private BigDecimal surchargeAmount = BigDecimal.ZERO;

  @NotNull
  @PositiveOrZero
  @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
  private BigDecimal discountAmount = BigDecimal.ZERO;

  @NotNull
  @PositiveOrZero
  @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
  private BigDecimal totalAmount;

  @Column(name = "pricing_notes", columnDefinition = "TEXT")
  private String pricingNotes;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "order_status", nullable = false, length = 50)
  private OrderStatus orderStatus;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "payment_status", nullable = false, length = 50)
  private PaymentStatus paymentStatus;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "questionnaire_status", nullable = false, length = 50)
  private QuestionnaireStatus questionnaireStatus;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public Profile getClientProfile() { return clientProfile; }
  public void setClientProfile(Profile clientProfile) { this.clientProfile = clientProfile; }
  public Profile getCurrentSitter() { return currentSitter; }
  public void setCurrentSitter(Profile currentSitter) { this.currentSitter = currentSitter; }
  public Service getService() { return service; }
  public void setService(Service service) { this.service = service; }
  public String getServiceName() { return serviceName; }
  public void setServiceName(String serviceName) { this.serviceName = serviceName; }
  public BigDecimal getServiceUnitPrice() { return serviceUnitPrice; }
  public void setServiceUnitPrice(BigDecimal serviceUnitPrice) { this.serviceUnitPrice = serviceUnitPrice; }
  public BigDecimal getBaseAmount() { return baseAmount; }
  public void setBaseAmount(BigDecimal baseAmount) { this.baseAmount = baseAmount; }
  public BigDecimal getSurchargeAmount() { return surchargeAmount; }
  public void setSurchargeAmount(BigDecimal surchargeAmount) { this.surchargeAmount = surchargeAmount; }
  public BigDecimal getDiscountAmount() { return discountAmount; }
  public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
  public BigDecimal getTotalAmount() { return totalAmount; }
  public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
  public String getPricingNotes() { return pricingNotes; }
  public void setPricingNotes(String pricingNotes) { this.pricingNotes = pricingNotes; }
  public OrderStatus getOrderStatus() { return orderStatus; }
  public void setOrderStatus(OrderStatus orderStatus) { this.orderStatus = orderStatus; }
  public PaymentStatus getPaymentStatus() { return paymentStatus; }
  public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }
  public QuestionnaireStatus getQuestionnaireStatus() { return questionnaireStatus; }
  public void setQuestionnaireStatus(QuestionnaireStatus questionnaireStatus) { this.questionnaireStatus = questionnaireStatus; }
}
