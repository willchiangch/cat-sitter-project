package com.petsitter.domain.repository;

import com.petsitter.domain.model.ServiceReportMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceReportMediaRepository extends JpaRepository<ServiceReportMedia, UUID> {
    List<ServiceReportMedia> findByReportIdAndIsDeletedFalse(UUID reportId);
    int countByReportIdAndMediaTypeAndIsDeletedFalse(UUID reportId, String mediaType);
    Optional<ServiceReportMedia> findByIdAndIsDeletedFalse(UUID id);
}
