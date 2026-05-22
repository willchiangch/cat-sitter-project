package com.petsitter.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class NotificationService {
    public void sendNotification(UUID userId, String message) {
        log.info("Sending notification to user {}: {}", userId, message);
        // TODO: Integrate with real notification provider
    }
}
