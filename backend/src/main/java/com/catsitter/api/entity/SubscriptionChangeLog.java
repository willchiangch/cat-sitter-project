package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "subscription_change_logs")
public class SubscriptionChangeLog extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sitter_profile_id", nullable = false)
    private Profile sitterProfile;

    @Column(name = "from_plan_code", length = 20)
    private String fromPlanCode;

    @Column(name = "to_plan_code", nullable = false, length = 20)
    private String toPlanCode;

    /**
     * SUBSCRIBE / UPGRADE / DOWNGRADE / CANCEL / FREE_REDEMPTION
     */
    @Column(name = "change_type", nullable = false, length = 30)
    private String changeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promo_code_id")
    private PromoCode promoCode;

    @Column(name = "promo_code_used", length = 50)
    private String promoCodeUsed;

    @Column(name = "original_amount", nullable = false)
    private BigDecimal originalAmount;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "final_amount", nullable = false)
    private BigDecimal finalAmount;

    @Column(name = "note")
    private String note;

    public UUID getId() { return id; }
    public Profile getSitterProfile() { return sitterProfile; }
    public void setSitterProfile(Profile sitterProfile) { this.sitterProfile = sitterProfile; }
    public String getFromPlanCode() { return fromPlanCode; }
    public void setFromPlanCode(String fromPlanCode) { this.fromPlanCode = fromPlanCode; }
    public String getToPlanCode() { return toPlanCode; }
    public void setToPlanCode(String toPlanCode) { this.toPlanCode = toPlanCode; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public PromoCode getPromoCode() { return promoCode; }
    public void setPromoCode(PromoCode promoCode) { this.promoCode = promoCode; }
    public String getPromoCodeUsed() { return promoCodeUsed; }
    public void setPromoCodeUsed(String promoCodeUsed) { this.promoCodeUsed = promoCodeUsed; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public void setFinalAmount(BigDecimal finalAmount) { this.finalAmount = finalAmount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
