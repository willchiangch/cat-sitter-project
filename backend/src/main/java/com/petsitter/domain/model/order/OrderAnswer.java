package com.petsitter.domain.model.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 問卷答案快照（O1 寫入後不變，SD-ORDER §5.1.1 第 5 段）。 */
@Entity
@Table(name = "order_answers")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private short sortOrder;

    @Column(name = "question_text", nullable = false, updatable = false, length = 200)
    private String questionText;

    @Column(name = "question_type", nullable = false, updatable = false, length = 16)
    private String questionType;

    /**
     * 答案的 JSON 原文：選擇題為選項文字陣列、簡答 / 詳答為字串、未答為 JSON {@code null}（非 SQL NULL）。
     * 由 Service 以專案的 Jackson 3 {@code JsonMapper} 序列化；Hibernate 7.2 內建 JSON mapper 為 Jackson 2，
     * 故不用 {@code @JdbcTypeCode(JSON)}，改以 SQL cast 寫入 / 讀出文字。
     */
    @Column(name = "answer", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(read = "answer::text", write = "?::jsonb")
    private String answerJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
