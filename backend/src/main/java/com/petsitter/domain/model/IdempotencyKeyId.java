package com.petsitter.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyKeyId implements Serializable {
    private String idempotencyKey;
    private UUID userId;
}
