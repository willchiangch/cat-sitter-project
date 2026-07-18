package com.petsitter.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * PRD-004/005：飼主於預約時對保母自訂問卷的回覆快照。
 * 儲存 questionText 而非只存 questionId，是為了在題目日後被修改/停用/刪除後，
 * 訂單內的歷史回覆快照依然完整可讀 (PRD-004 資料規則「歷史隔離」)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionnaireAnswer {
    private UUID questionId;
    private String questionText;
    private String answerType;
    private List<String> answerValues;
}
