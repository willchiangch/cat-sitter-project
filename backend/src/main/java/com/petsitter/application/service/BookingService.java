package com.petsitter.application.service;

import com.petsitter.application.dto.BookingRequest;
import com.petsitter.application.exception.CapacityFullException;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderItem;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.ServicePlanRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final OrderRepository orderRepository;
    private final VisitRepository visitRepository;
    private final UserRepository userRepository;
    private final ServicePlanRepository servicePlanRepository;

    @Transactional
    public UUID createBooking(BookingRequest request) {
        log.info("[BookingService] Creating PENDING order for sitter: {}, plan: {}", request.getSitterId(), request.getPlanId());

        if (!servicePlanRepository.existsById(request.getPlanId())) {
            throw new IllegalArgumentException("找不到指定的服務方案: " + request.getPlanId());
        }

        List<String> dateStrings = request.getDates().stream()
                .map(LocalDate::toString)
                .collect(Collectors.toList());

        Order order = Order.builder()
                .owner(userRepository.findById(request.getOwnerId()).orElseThrow())
                .sitter(userRepository.findById(request.getSitterId()).orElseThrow())
                .planId(request.getPlanId())
                .status("PENDING")
                .items(List.of(new OrderItem("BOOKING", "Dates: " + String.join(", ", dateStrings), 0, dateStrings.size())))
                .idempotencyKey(request.getIdempotencyKey())
                .build();
        
        Order savedOrder = orderRepository.save(order);

        List<Visit> visits = request.getDates().stream()
                .map(date -> Visit.builder()
                        .order(savedOrder)
                        .status("PENDING")
                        .scheduledAt(date.atStartOfDay().atOffset(ZoneOffset.UTC))
                        .build())
                .collect(Collectors.toList());
        
        visitRepository.saveAll(visits);
        visitRepository.flush();

        log.info("[BookingService] Successfully created PENDING order ID: {}", savedOrder.getId());
        return savedOrder.getId();
    }

    @Transactional
    public void confirmBooking(UUID orderId) {
        log.info("[BookingService] Sitter confirming order: {}", orderId);
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到訂單: " + orderId));
        
        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("只有 PENDING 狀態的訂單可以確認接單");
        }

        UUID sitterId = order.getSitter().getId();
        ServicePlan plan = servicePlanRepository.findById(order.getPlanId())
                .orElseThrow(() -> new IllegalStateException("找不到對應的服務方案"));

        List<Visit> visits = visitRepository.findByOrderId(orderId);

        for (Visit visit : visits) {
            String dateStr = visit.getScheduledAt().toLocalDate().toString();
            orderRepository.acquireAdvisoryLock(sitterId.toString(), dateStr);

            long bookedCount = visitRepository.countBookedVisitsBySitterIdAndDate(sitterId, visit.getScheduledAt());
            
            if (bookedCount >= plan.getDailyCapacity()) {
                throw new CapacityFullException("日期 " + dateStr + " 的名額已滿，無法接單");
            }
        }

        order.setStatus("PENDING_PAYMENT");
        orderRepository.save(order);

        visits.forEach(v -> v.setStatus("CONFIRMED"));
        visitRepository.saveAll(visits);
    }
}
