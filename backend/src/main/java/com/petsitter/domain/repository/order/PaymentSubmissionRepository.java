package com.petsitter.domain.repository.order;

import com.petsitter.domain.model.order.PaymentSubmission;
import com.petsitter.domain.model.order.SubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentSubmissionRepository extends JpaRepository<PaymentSubmission, UUID> {

    List<PaymentSubmission> findByPaymentIdOrderBySubmittedAtDescIdDesc(UUID paymentId);

    Optional<PaymentSubmission> findFirstByPaymentIdAndStatus(UUID paymentId, SubmissionStatus status);

    List<PaymentSubmission> findByPaymentIdInAndStatus(Collection<UUID> paymentIds, SubmissionStatus status);

    boolean existsByProofObjectKey(String proofObjectKey);

    List<PaymentSubmission> findByProofObjectKeyIn(Collection<String> keys);
}
