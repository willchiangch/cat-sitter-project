package com.petsitter.domain.event;

import java.util.UUID;

public record SitterUnsuspendedEvent(UUID sitterId) {
}
