package com.petsitter.application.service.fulfill;

import com.petsitter.domain.model.fulfill.ServiceReportMedia;
import com.petsitter.domain.repository.fulfill.ServiceReportMediaRepository;
import com.petsitter.domain.repository.fulfill.ServiceReportUploadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@link CareLogMediaRetentionPort} 實作（SD-FULFILL §2.3.2）。 */
@Service
@RequiredArgsConstructor
public class CareLogMediaRetentionPortImpl implements CareLogMediaRetentionPort {

    private final ServiceReportMediaRepository mediaRepository;
    private final ServiceReportUploadRepository uploadRepository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<ExpiredMediaView> findExpiredMedia(OffsetDateTime now, int limit) {
        return mediaRepository.findExpired(now, limit).stream()
                .map(r -> new ExpiredMediaView(r.getMediaId(), r.getObjectKey(), r.getObjectGeneration(), r.getOrderId()))
                .toList();
    }

    @Override
    @Transactional
    public void markPurged(UUID mediaId, OffsetDateTime now) {
        UUID uploadId = mediaRepository.findById(mediaId).map(ServiceReportMedia::getUploadId).orElse(null);
        mediaRepository.markPurged(mediaId, now);
        if (uploadId != null) {
            uploadRepository.markObjectDeleted(uploadId, now);
        }
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Set<UUID> hasUnpurgedMedia(Set<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(mediaRepository.findOrderIdsWithUnpurgedMedia(orderIds));
    }
}
