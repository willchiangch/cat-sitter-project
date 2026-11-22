package com.petsitter.application.service;

import com.petsitter.domain.model.IdempotencyKey;
import com.petsitter.domain.model.IdempotencyKeyId;
import com.petsitter.domain.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 舊冪等（`idempotency_keys`，重複回 409 DUPLICATE_REQUEST）。SD-GLOBAL-SPEC §2.4：只留給尚未重建的 KYC、
 * 照護記事 / 媒體與照護日誌（後者隨 SD-FULFILL 改用 {@link com.petsitter.application.common.idempotency.IdempotencyService}）。
 */
@Service
@RequiredArgsConstructor
public class LegacyIdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @Transactional
    public void checkAndConsume(String key, UUID userId) {
        if (key == null || key.isBlank()) {
            return;
        }

        IdempotencyKeyId id = new IdempotencyKeyId(key, userId);
        if (idempotencyKeyRepository.existsById(id)) {
            throw new DataIntegrityViolationException("Duplicate idempotency key detected");
        }

        IdempotencyKey idempotencyKey = IdempotencyKey.builder()
                .idempotencyKey(key)
                .userId(userId)
                .build();

        idempotencyKeyRepository.save(idempotencyKey);
    }
}
