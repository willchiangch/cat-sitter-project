package com.petsitter.application.port.transitional;

import com.petsitter.application.port.SitterQuestionQuery;
import com.petsitter.domain.repository.SitterQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** SD-SUPPLY 未重建前的過渡實作。 */
@Component
@RequiredArgsConstructor
public class TransitionalSitterQuestionQuery implements SitterQuestionQuery {

    private final SitterQuestionRepository repository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<QuestionView> listActive(UUID sitterId) {
        return repository.findBySitterIdAndIsDeletedFalseAndIsActiveTrueOrderBySortOrderAsc(sitterId).stream()
                .map(q -> new QuestionView(q.getId(), q.getQuestionText(), q.getAnswerType(),
                        q.getOptions() == null ? List.of() : List.copyOf(q.getOptions()), q.isRequired(), q.getSortOrder()))
                .toList();
    }
}
