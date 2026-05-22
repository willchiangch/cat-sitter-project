package com.petsitter.application.service;

import com.petsitter.domain.model.IdempotencyKey;
import com.petsitter.domain.model.IdempotencyKeyId;
import com.petsitter.domain.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

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
