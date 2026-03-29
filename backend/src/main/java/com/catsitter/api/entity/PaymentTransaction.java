package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne
    @JoinColumn(name = "subscription_id")
    private SitterSubscription subscription;

    @ManyToOne
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "trade_no", unique = true, length = 100)
    private String tradeNo;

    @Column(name = "mer_trade_no", unique = true, length = 100)
    private String merTradeNo;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 50)
    private String status; // PENDING, SUCCESS, FAILED

    @Column(name = "payment_type", length = 50)
    private String paymentType;

    @Column(name = "pay_time")
    private Instant payTime;

    public UUID getId() { return this.id; }
    public void setId(UUID id) { this.id = id; }
    public Account getAccount() { return this.account; }
    public void setAccount(Account account) { this.account = account; }
    public SitterSubscription getSubscription() { return this.subscription; }
    public void setSubscription(SitterSubscription subscription) { this.subscription = subscription; }
    public Order getOrder() { return this.order; }
    public void setOrder(Order order) { this.order = order; }
    public String getTradeNo() { return this.tradeNo; }
    public void setTradeNo(String tradeNo) { this.tradeNo = tradeNo; }
    public String getMerTradeNo() { return this.merTradeNo; }
    public void setMerTradeNo(String merTradeNo) { this.merTradeNo = merTradeNo; }
    public BigDecimal getAmount() { return this.amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return this.status; }
    public void setStatus(String status) { this.status = status; }
    public String getPaymentType() { return this.paymentType; }
    public void setPaymentType(String paymentType) { this.paymentType = paymentType; }
    public Instant getPayTime() { return this.payTime; }
    public void setPayTime(Instant payTime) { this.payTime = payTime; }
}
