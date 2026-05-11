package com.petsitter.domain.repository;

import com.petsitter.domain.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdAndSitterId(UUID id, UUID sitterId);

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key1), hashtext(:key2))", nativeQuery = true)
    void acquireAdvisoryLock(@Param("key1") String key1, @Param("key2") String key2);
}
