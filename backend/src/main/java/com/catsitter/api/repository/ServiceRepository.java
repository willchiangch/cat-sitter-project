package com.catsitter.api.repository;

import com.catsitter.api.entity.Service;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<Service, UUID> {
  List<Service> findBySitterProfileIdAndIsActiveTrueOrderBySortOrderAsc(UUID sitterProfileId);
  List<Service> findBySitterProfileIdOrderBySortOrderAsc(UUID sitterProfileId);
}
