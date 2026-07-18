package com.petsitter.domain.repository;

import com.petsitter.domain.model.SitterQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SitterQuestionRepository extends JpaRepository<SitterQuestion, UUID> {
    List<SitterQuestion> findBySitterIdAndIsDeletedFalseOrderBySortOrderAsc(UUID sitterId);

    List<SitterQuestion> findBySitterIdAndIsDeletedFalseAndIsActiveTrueOrderBySortOrderAsc(UUID sitterId);

    long countBySitterIdAndIsDeletedFalse(UUID sitterId);
}
