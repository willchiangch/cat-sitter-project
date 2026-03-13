package com.catsitter.api.repository;

import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {
  List<Profile> findByAccountId(UUID accountId);
  Optional<Profile> findByAccountIdAndRoleType(UUID accountId, RoleType roleType);
}
