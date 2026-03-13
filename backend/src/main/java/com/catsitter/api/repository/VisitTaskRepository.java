package com.catsitter.api.repository;

import com.catsitter.api.entity.VisitTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitTaskRepository extends JpaRepository<VisitTask, UUID> {
  List<VisitTask> findByVisitIdOrderBySortOrderAsc(UUID visitId);
}
