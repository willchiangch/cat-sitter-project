package com.petsitter.application.service;

import com.petsitter.application.dto.SitterQuestionDto;
import com.petsitter.application.dto.SitterQuestionSortRequest;
import com.petsitter.application.exception.SitterQuestionException;
import com.petsitter.domain.model.SitterQuestion;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.SitterQuestionRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * PRD-004: 保母自訂事前問卷題目管理
 */
@Service
@RequiredArgsConstructor
public class SitterQuestionService {

    private static final int MAX_QUESTIONS_PER_SITTER = 20;
    private static final Set<String> CHOICE_TYPES = Set.of("RADIO", "CHECKBOX");

    private final SitterQuestionRepository sitterQuestionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<SitterQuestionDto> getMyQuestions(UUID sitterId) {
        return sitterQuestionRepository.findBySitterIdAndIsDeletedFalseOrderBySortOrderAsc(sitterId).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * 供 PRD-005 預約流程動態渲染問卷使用：只回傳啟用中的題目
     */
    @Transactional(readOnly = true)
    public List<SitterQuestionDto> getActiveQuestionsForBooking(UUID sitterId) {
        return sitterQuestionRepository.findBySitterIdAndIsDeletedFalseAndIsActiveTrueOrderBySortOrderAsc(sitterId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public SitterQuestionDto createQuestion(UUID sitterId, SitterQuestionDto dto) {
        validateChoiceOptions(dto);

        long currentCount = sitterQuestionRepository.countBySitterIdAndIsDeletedFalse(sitterId);
        if (currentCount >= MAX_QUESTIONS_PER_SITTER) {
            throw new SitterQuestionException(HttpStatus.UNPROCESSABLE_ENTITY, "QUESTION_LIMIT_EXCEEDED",
                    "問卷題數已達上限 " + MAX_QUESTIONS_PER_SITTER + " 題");
        }

        User sitter = userRepository.findById(sitterId)
                .orElseThrow(() -> new SitterQuestionException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到保母資料"));

        int nextSortOrder = (int) currentCount;

        SitterQuestion question = SitterQuestion.builder()
                .sitter(sitter)
                .questionText(dto.getQuestionText())
                .answerType(dto.getAnswerType())
                .options(normalizeOptions(dto))
                .required(dto.isRequired())
                .sortOrder(nextSortOrder)
                .isActive(true)
                .build();

        return toDto(sitterQuestionRepository.save(question));
    }

    @Transactional
    public SitterQuestionDto updateQuestion(UUID sitterId, UUID questionId, SitterQuestionDto dto) {
        validateChoiceOptions(dto);

        SitterQuestion question = findOwnedQuestion(sitterId, questionId);

        if (dto.getVersion() != null && !dto.getVersion().equals(question.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(SitterQuestion.class, questionId);
        }

        question.setQuestionText(dto.getQuestionText());
        question.setAnswerType(dto.getAnswerType());
        question.setOptions(normalizeOptions(dto));
        question.setRequired(dto.isRequired());

        return toDto(sitterQuestionRepository.save(question));
    }

    @Transactional
    public void deleteQuestion(UUID sitterId, UUID questionId) {
        // 採邏輯刪除，確保歷史訂單中的問卷回覆快照不受影響 (PRD-004 例外處理表)
        SitterQuestion question = findOwnedQuestion(sitterId, questionId);
        question.setDeleted(true);
        sitterQuestionRepository.save(question);
    }

    @Transactional
    public SitterQuestionDto toggleActive(UUID sitterId, UUID questionId, boolean active) {
        SitterQuestion question = findOwnedQuestion(sitterId, questionId);
        question.setActive(active);
        return toDto(sitterQuestionRepository.save(question));
    }

    @Transactional
    public void reorderQuestions(UUID sitterId, SitterQuestionSortRequest request) {
        if (request.getQuestionIds() == null || request.getQuestionIds().isEmpty()) {
            return;
        }

        for (int i = 0; i < request.getQuestionIds().size(); i++) {
            SitterQuestion question = findOwnedQuestion(sitterId, request.getQuestionIds().get(i));
            question.setSortOrder(i);
            sitterQuestionRepository.saveAndFlush(question);
        }
    }

    private SitterQuestion findOwnedQuestion(UUID sitterId, UUID questionId) {
        SitterQuestion question = sitterQuestionRepository.findById(questionId)
                .orElseThrow(() -> new SitterQuestionException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "找不到該問題"));

        if (question.isDeleted()) {
            throw new SitterQuestionException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "找不到該問題");
        }
        // IDOR 越權防禦
        if (!question.getSitter().getId().equals(sitterId)) {
            throw new SitterQuestionException(HttpStatus.FORBIDDEN, "FORBIDDEN", "無權限操作此問題");
        }

        return question;
    }

    private void validateChoiceOptions(SitterQuestionDto dto) {
        if (CHOICE_TYPES.contains(dto.getAnswerType())
                && (dto.getOptions() == null || dto.getOptions().isEmpty())) {
            throw new SitterQuestionException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT",
                    "單選或多選題必須至少設定一個選項");
        }
    }

    private List<String> normalizeOptions(SitterQuestionDto dto) {
        if (!CHOICE_TYPES.contains(dto.getAnswerType())) {
            return List.of();
        }
        return dto.getOptions() == null ? List.of() : dto.getOptions();
    }

    private SitterQuestionDto toDto(SitterQuestion question) {
        return SitterQuestionDto.builder()
                .id(question.getId())
                .questionText(question.getQuestionText())
                .answerType(question.getAnswerType())
                .options(question.getOptions())
                .required(question.isRequired())
                .sortOrder(question.getSortOrder())
                .isActive(question.isActive())
                .version(question.getVersion())
                .build();
    }
}
