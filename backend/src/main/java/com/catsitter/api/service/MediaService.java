package com.catsitter.api.service;

import com.catsitter.api.entity.Visit;
import com.catsitter.api.entity.VisitMedia;
import com.catsitter.api.repository.VisitMediaRepository;
import com.catsitter.api.repository.VisitRepository;
import com.catsitter.api.service.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class MediaService {

    private static final Logger logger = LoggerFactory.getLogger(MediaService.class);

    private final StorageService storageService;
    private final VisitMediaRepository mediaRepository;
    private final VisitRepository visitRepository;

    @Value("${application.storage.retention-days:60}")
    private int retentionDays;

    private static final int MAX_MEDIA_PER_VISIT = 20;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    public MediaService(StorageService storageService,
                        VisitMediaRepository mediaRepository,
                        VisitRepository visitRepository) {
        this.storageService = storageService;
        this.mediaRepository = mediaRepository;
        this.visitRepository = visitRepository;
    }

    /**
     * Upload a media file for a visit. 
     * Includes validations for file size and maximum media count per visit.
     */
    @Transactional
    public VisitMedia uploadVisitMedia(UUID visitId, MultipartFile file) throws IOException {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new RuntimeException("Visit not found"));

        // 1. Validation
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new RuntimeException("File size exceeds limit (10MB)");
        }

        List<VisitMedia> existingMedia = mediaRepository.findByVisitId(visitId);
        if (existingMedia.size() >= MAX_MEDIA_PER_VISIT) {
            throw new RuntimeException("Maximum of " + MAX_MEDIA_PER_VISIT + " media files allowed per visit.");
        }

        // 2. Determine type and path
        String contentType = file.getContentType();
        String type = (contentType != null && contentType.startsWith("video")) ? "VIDEO" : "PHOTO";
        String folder = "visits/" + visitId;
        String filePath = storageService.store(file, folder);

        // 3. Save Record
        VisitMedia media = new VisitMedia();
        media.setVisit(visit);
        media.setMediaUrl(filePath);
        media.setMediaType(type);
        media.setFileSize(file.getSize());
        
        logger.info("Uploaded {} media for Visit ID: {}. Path: {}", type, visitId, filePath);
        
        return mediaRepository.save(media);
    }

    /**
     * Cleanup media files that are older than the retention limit.
     */
    @Transactional
    public void cleanupExpiredMedia() {
        Instant expiryDate = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<VisitMedia> expiredMedia = mediaRepository.findByCreatedAtBeforeAndIsDeletedFalse(expiryDate);

        if (expiredMedia.isEmpty()) {
            logger.debug("No expired media found for cleanup.");
            return;
        }

        logger.info("Found {} expired media files to clean up (retention: {} days)", expiredMedia.size(), retentionDays);

        for (VisitMedia media : expiredMedia) {
            try {
                storageService.delete(media.getMediaUrl());
                media.setDeleted(true);
                media.setDeletedAt(Instant.now());
                mediaRepository.save(media);
                logger.info("Successfully cleaned up expired media: {}", media.getMediaUrl());
            } catch (IOException e) {
                logger.error("Failed to delete physical file for expired media {}: {}", media.getMediaUrl(), e.getMessage());
            }
        }
    }
}
