package com.catsitter.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MediaRetentionJob {

    private static final Logger logger = LoggerFactory.getLogger(MediaRetentionJob.class);

    private final MediaService mediaService;

    public MediaRetentionJob(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * Run every day at 3 AM to cleanup expired media files.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void runCleanup() {
        logger.info("[JOB START] Media retention cleanup");
        try {
            mediaService.cleanupExpiredMedia();
            logger.info("[JOB END] Media retention cleanup - Completed");
        } catch (Exception e) {
            logger.error("[JOB ERROR] Media retention cleanup - Reason: {}", e.getMessage(), e);
        }
    }
}
