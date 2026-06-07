package com.petsitter.application.event;

import java.util.UUID;

public record VisitNotificationEvent(UUID userId, String message) {}
