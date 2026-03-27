package com.catsitter.api.repository;

import com.catsitter.api.entity.SitterQuestion;
import com.catsitter.api.entity.enums.TargetPetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SitterQuestionRepository extends JpaRepository<SitterQuestion, UUID> {
  List<SitterQuestion> findBySitterProfileIdAndIsActiveTrueAndTargetPetTypeInOrderBySortOrderAsc(
      UUID sitterProfileId, List<TargetPetType> targetPetTypes);
  List<SitterQuestion> findBySitterProfileIdOrderBySortOrderAsc(UUID sitterProfileId);
}
