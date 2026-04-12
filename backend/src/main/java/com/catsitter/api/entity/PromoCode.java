package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "promo_codes")
public class PromoCode extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "max_uses", nullable = false)
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    @Column(name = "expiry_date")
    private Instant expiryDate;

    /**
     * FIXED = 固定金額折扣, PERCENT = 百分比折扣 (0-100)
     */
    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType = "FIXED";

    @Column(name = "discount_percent")
    private BigDecimal discountPercent;

    public UUID getId() { return this.id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return this.code; }
    public void setCode(String code) { this.code = code; }
    public BigDecimal getDiscountAmount() { return this.discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public Integer getMaxUses() { return this.maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }
    public Integer getUsedCount() { return this.usedCount; }
    public void setUsedCount(Integer usedCount) { this.usedCount = usedCount; }
    public Instant getExpiryDate() { return this.expiryDate; }
    public void setExpiryDate(Instant expiryDate) { this.expiryDate = expiryDate; }
    public String getDiscountType() { return this.discountType; }
    public void setDiscountType(String discountType) { this.discountType = discountType; }
    public BigDecimal getDiscountPercent() { return this.discountPercent; }
    public void setDiscountPercent(BigDecimal discountPercent) { this.discountPercent = discountPercent; }
}
