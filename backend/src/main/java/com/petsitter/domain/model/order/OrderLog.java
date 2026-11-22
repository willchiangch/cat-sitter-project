package com.petsitter.domain.model.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.Convert;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 同交易稽核（SD-ORDER §3.6.3、§5.1.1 第 10 段）。不可變列，不繼承基底。 */
@Entity
@Table(name = "order_logs")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "action_type", nullable = false, updatable = false)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator_role", nullable = false, updatable = false)
    private ActorRole operatorRole;

    @Column(name = "operator_user_id", updatable = false)
    private UUID operatorUserId;

    @Convert(converter = JsonbConverters.PayloadConverter.class)
    @ColumnTransformer(read = "payload::text", write = "?::jsonb")
    @Column(name = "payload", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
