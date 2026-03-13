package com.catsitter.api.repository;

import com.catsitter.api.entity.OrderTransfer;
import com.catsitter.api.entity.enums.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderTransferRepository extends JpaRepository<OrderTransfer, UUID> {
  List<OrderTransfer> findByToSitterIdAndTransferStatus(UUID toSitterId, TransferStatus status);
}
