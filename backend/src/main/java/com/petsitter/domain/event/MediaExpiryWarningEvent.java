package com.petsitter.domain.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class MediaExpiryWarningEvent {
    private final UUID orderId;
    private final UUID ownerId;
    private final OffsetDateTime expiryTime;
}
