package com.petsitter.application.service;

import com.petsitter.domain.model.ServiceReportMedia;
import com.petsitter.domain.repository.ServiceReportMediaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MediaPurgeBatchDeleter {
    private final ServiceReportMediaRepository mediaRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsPurged(UUID mediaId) {
        ServiceReportMedia media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media not found: " + mediaId));
        media.setPurged(true);
        media.setPurgedAt(OffsetDateTime.now(ZoneOffset.UTC));
        mediaRepository.saveAndFlush(media);
    }
}
