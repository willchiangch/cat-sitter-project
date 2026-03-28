package com.catsitter.api.service;

import com.catsitter.api.client.GoogleCalendarClient;
import com.catsitter.api.entity.Order;
import com.catsitter.api.entity.SitterCalendarConfig;
import com.catsitter.api.entity.Visit;
import com.catsitter.api.repository.SitterCalendarConfigRepository;
import com.catsitter.api.repository.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CalendarSyncService {

    private static final Logger logger = LoggerFactory.getLogger(CalendarSyncService.class);

    private final SitterCalendarConfigRepository calendarConfigRepository;
    private final VisitRepository visitRepository;
    private final GoogleCalendarClient googleCalendarClient;

    public CalendarSyncService(SitterCalendarConfigRepository calendarConfigRepository,
                               VisitRepository visitRepository,
                               GoogleCalendarClient googleCalendarClient) {
        this.calendarConfigRepository = calendarConfigRepository;
        this.visitRepository = visitRepository;
        this.googleCalendarClient = googleCalendarClient;
    }

    /**
     * Synchronize events for a given order asynchronously.
     * Supports both creating/updating (CONFIRMED) and deleting (CANCELLED).
     */
    @Async
    @Transactional
    public void syncOrderEvents(Order order) {
        Optional<SitterCalendarConfig> configOpt = calendarConfigRepository.findBySitterProfileId(order.getCurrentSitter().getId());
        if (configOpt.isEmpty()) {
            logger.debug("No calendar sync configured for sitter Profile ID: {}", order.getCurrentSitter().getId());
            return;
        }

        SitterCalendarConfig config = configOpt.get();
        List<Visit> visits = visitRepository.findByOrderId(order.getId());

        for (Visit visit : visits) {
            try {
                processVisitSync(visit, config, order);
            } catch (Exception e) {
                logger.error("Failed to sync visit {} to calendar: {}", visit.getId(), e.getMessage());
            }
        }
    }

    private void processVisitSync(Visit visit, SitterCalendarConfig config, Order order) throws Exception {
        String status = order.getOrderStatus().name();
        
        if ("CONFIRMED".equals(status)) {
            // Create event if it doesn't exist
            if (visit.getCalendarEventId() == null) {
                String summary = "[貓咪保母] " + order.getClientProfile().getName() + " - " + order.getServiceName();
                String description = String.format("預約服務: %s\n飼主: %s\n訂單 ID: %s", 
                        order.getServiceName(), order.getClientProfile().getName(), order.getId());
                
                String eventId = googleCalendarClient.createEvent(
                        config.getAccessToken(), config.getRefreshToken(),
                        summary, description, visit.getVisitStartTime(), visit.getVisitEndTime());
                
                visit.setCalendarEventId(eventId);
                visitRepository.save(visit);
                logger.info("Successfully created calendar event for visit ID: {}", visit.getId());
            }
        } else if ("CANCELLED".equals(status)) {
            // Delete event if it exists
            if (visit.getCalendarEventId() != null) {
                googleCalendarClient.deleteEvent(config.getAccessToken(), config.getRefreshToken(), visit.getCalendarEventId());
                visit.setCalendarEventId(null);
                visitRepository.save(visit);
                logger.info("Successfully deleted calendar event for visit ID: {}", visit.getId());
            }
        }
    }
}
