package com.petsitter.application.service;

import com.petsitter.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class NotificationBatchDeleter {

    private final NotificationRepository notificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBatch(OffsetDateTime cutoffTime) {
        return notificationRepository.deleteOldNotificationsLimit(cutoffTime, 1000);
    }
}
