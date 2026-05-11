package com.petsitter.application.service;

import com.petsitter.application.dto.BookingRequest;
import com.petsitter.domain.model.Order;
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
}
