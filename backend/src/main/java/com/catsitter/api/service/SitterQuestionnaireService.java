package com.catsitter.api.service;

import com.catsitter.api.dto.sitter.CreateQuestionRequest;
import com.catsitter.api.dto.sitter.QuestionItemResponse;
import com.catsitter.api.dto.sitter.UpdateQuestionRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.SitterQuestion;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.SitterQuestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SitterQuestionnaireService {

    private final SitterQuestionRepository questionRepository;
    private final ProfileRepository profileRepository;

    public SitterQuestionnaireService(SitterQuestionRepository questionRepository, ProfileRepository profileRepository) {
        this.questionRepository = questionRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public List<QuestionItemResponse> getSitterQuestions(Account account) {
        Profile profile = getSitterProfile(account);
        return questionRepository.findBySitterProfileIdOrderBySortOrderAsc(profile.getId())
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public QuestionItemResponse createQuestion(Account account, CreateQuestionRequest request) {
        Profile profile = getSitterProfile(account);
        
        SitterQuestion question = new SitterQuestion();
        question.setSitterProfile(profile);
        question.setTargetPetType(request.targetPetType());
        question.setQuestionText(request.questionText());
        question.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        question.setIsActive(true);

        return mapToResponse(questionRepository.save(question));
    }

    @Transactional
    public QuestionItemResponse updateQuestion(Account account, UUID questionId, UpdateQuestionRequest request) {
        Profile profile = getSitterProfile(account);
        SitterQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new RuntimeException("Question not found"));

        if (!question.getSitterProfile().getId().equals(profile.getId())) {
            throw new RuntimeException("Unauthorized to update this question");
        }

        question.setTargetPetType(request.targetPetType());
        question.setQuestionText(request.questionText());
        question.setSortOrder(request.sortOrder());
        question.setIsActive(request.isActive());

        return mapToResponse(questionRepository.save(question));
    }

    @Transactional
    public void reorderQuestions(Account account, List<UUID> questionIds) {
        Profile profile = getSitterProfile(account);
        List<SitterQuestion> questions = questionRepository.findAllById(questionIds);
        
        for (int i = 0; i < questionIds.size(); i++) {
            UUID id = questionIds.get(i);
            int finalI = i;
            questions.stream()
                    .filter(q -> q.getId().equals(id))
                    .findFirst()
                    .ifPresent(q -> {
                        if (q.getSitterProfile().getId().equals(profile.getId())) {
                            q.setSortOrder(finalI);
                        }
                    });
        }
        questionRepository.saveAll(questions);
    }

    private Profile getSitterProfile(Account account) {
        return profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));
    }

    private QuestionItemResponse mapToResponse(SitterQuestion question) {
        return new QuestionItemResponse(
                question.getId(),
                question.getTargetPetType(),
                question.getQuestionText(),
                question.getSortOrder(),
                question.getIsActive()
        );
    }
}
