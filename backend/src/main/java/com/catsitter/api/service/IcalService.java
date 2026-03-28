package com.catsitter.api.service;

import com.catsitter.api.entity.Order;
import com.catsitter.api.entity.Visit;
import com.catsitter.api.entity.enums.OrderStatus;
import com.catsitter.api.repository.VisitRepository;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class IcalService {

    private final VisitRepository visitRepository;

    public IcalService(VisitRepository visitRepository) {
        this.visitRepository = visitRepository;
    }

    public String generateSitterIcal(UUID sitterProfileId) {
        List<Visit> visits = visitRepository.findByOrderCurrentSitterIdAndOrderOrderStatus(sitterProfileId, OrderStatus.CONFIRMED);

        Calendar calendar = new Calendar()
                .withProdId("-//Cat Sitter PWA//NONSGML v1.0//EN")
                .withDefaults()
                .getFluentTarget();

        for (Visit visit : visits) {
            Order order = visit.getOrder();
            String summaryText = "[貓咪保母] " + order.getClientProfile().getName() + " - " + order.getServiceName();
            String descText = "預約服務: " + order.getServiceName() + "\n飼主: " + order.getClientProfile().getName();

            VEvent event = new VEvent(
                    visit.getVisitStartTime().toInstant(),
                    visit.getVisitEndTime().toInstant(),
                    summaryText
            );
            event.add(new Description(descText));
            event.add(new Uid(visit.getId().toString()));
            
            calendar.add(event);
        }

        return calendar.toString();
    }
}
