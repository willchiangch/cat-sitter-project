package com.petsitter.application.port;

import java.util.List;
import java.util.UUID;

/** 問卷題目查詢（SD-ORDER §2.4.2；SD-SUPPLY 實作，過渡）。 */
public interface SitterQuestionQuery {

    /** 啟用中題目，依 sortOrder。 */
    List<QuestionView> listActive(UUID sitterId);

    /** {@code type}：沿用 sitter_questions 題型（RADIO / CHECKBOX / INPUT / TEXTAREA）。 */
    record QuestionView(UUID questionId, String text, String type, List<String> options, boolean required, int sortOrder) {
    }
}
