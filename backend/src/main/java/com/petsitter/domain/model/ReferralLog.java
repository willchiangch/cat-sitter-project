package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * PRD-010: 保母轉介紀錄。orderId 可為 null (代表 D 流程：非訂單關聯的主動分享)
 */
@Entity
@Table(name = "referral_logs")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralLog extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referring_sitter_id", nullable = false)
    private User referringSitter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommended_sitter_id", nullable = false)
    private User recommendedSitter;

    @Column(columnDefinition = "TEXT")
    private String message;
}
