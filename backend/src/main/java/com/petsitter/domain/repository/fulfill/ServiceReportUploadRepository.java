package com.petsitter.domain.repository.fulfill;

import com.petsitter.domain.model.fulfill.ServiceReportUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ServiceReportUploadRepository extends JpaRepository<ServiceReportUpload, UUID> {
}
