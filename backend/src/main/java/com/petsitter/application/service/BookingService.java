package com.petsitter.application.service;

import com.petsitter.application.dto.BookingRequest;
import com.petsitter.application.exception.CapacityFullException;
import com.petsitter.domain.model.Order;
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
import java.util.Collections;
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

        // 1. 驗證方案是否存在 (基本的資料完整性檢查)
        if (!servicePlanRepository.existsById(request.getPlanId())) {
            throw new IllegalArgumentException("找不到指定的服務方案: " + request.getPlanId());
        }

        // 2. 建立訂單 (初始狀態為 PENDING)
        // 根據 PRD-005: 檔期不在送單時鎖定，多人可同時送單。
        List<String> dateStrings = request.getDates().stream()
                .map(LocalDate::toString)
                .collect(Collectors.toList());

        Order order = Order.builder()
                .owner(userRepository.findById(request.getOwnerId()).orElseThrow())
                .sitter(userRepository.findById(request.getSitterId()).orElseThrow())
                .planId(request.getPlanId())
                .status("PENDING")
                .items(Collections.singletonMap("dates", dateStrings))
                .idempotencyKey(request.getIdempotencyKey())
                .build();
        
        Order savedOrder = orderRepository.save(order);

        // 3. 建立關聯行程
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

        // 1. 取得關聯行程的所有日期
        List<Visit> visits = visitRepository.findByOrderId(orderId);

        // 2. 針對每個預約日期加鎖並檢查容量
        for (Visit visit : visits) {
            String dateStr = visit.getScheduledAt().toLocalDate().toString();
            
            // PostgreSQL Advisory Lock (Transaction-bound)
            log.debug("[AdvisoryLock] Acquiring lock for sitter {} on date {}", sitterId, dateStr);
            orderRepository.acquireAdvisoryLock(sitterId.toString(), dateStr);

            // 檢查該日期已佔用的名額
            long bookedCount = visitRepository.countBookedVisitsBySitterIdAndDate(sitterId, visit.getScheduledAt());
            
            if (bookedCount >= plan.getDailyCapacity()) {
                log.warn("[CapacityFull] Sitter {} is full on {}. Capacity: {}, Booked: {}", 
                        sitterId, dateStr, plan.getDailyCapacity(), bookedCount);
                throw new CapacityFullException("日期 " + dateStr + " 的名額已滿，無法接單");
            }
        }

        // 3. 更新訂單狀態為 PENDING_PAYMENT (等待保母後續報價，或直接進入支付流程)
        // 根據 SD，確認接單後會進入報價流程，所以狀態先轉為 PENDING_PAYMENT
        order.setStatus("PENDING_PAYMENT");
        orderRepository.save(order);

        // 4. 更新行程狀態
        visits.forEach(v -> v.setStatus("CONFIRMED"));
        visitRepository.saveAll(visits);

        log.info("[BookingService] Order {} confirmed successfully", orderId);
    }
}
