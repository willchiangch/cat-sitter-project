package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * PRD-004: 保母自訂事前問卷題目
 */
@Entity
@Table(name = "sitter_questions")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SitterQuestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sitter_id", nullable = false)
    private User sitter;

    @Column(name = "question_text", nullable = false)
    private String questionText;

    @Column(name = "answer_type", nullable = false)
    private String answerType; // RADIO, CHECKBOX, INPUT, TEXTAREA

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> options = List.of();

    @Builder.Default
    @Column(nullable = false)
    private boolean required = false;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;
}
