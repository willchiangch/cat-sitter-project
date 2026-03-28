package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
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
}
