package com.petsitter.domain.event;

import java.util.UUID;

public record SitterSuspendedEvent(UUID sitterId, String reason) {
}
