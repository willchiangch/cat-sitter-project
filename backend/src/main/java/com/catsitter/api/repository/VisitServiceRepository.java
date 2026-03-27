package com.catsitter.api.repository;

import com.catsitter.api.entity.Visit;
import com.catsitter.api.entity.VisitService;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface VisitServiceRepository extends JpaRepository<VisitService, UUID> {
    List<VisitService> findByVisitIdOrderBySortOrderAsc(UUID visitId);
}
