package com.petsitter.domain.repository;

import com.petsitter.domain.model.UserActionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserActionLogRepository extends JpaRepository<UserActionLog, UUID> {
}
