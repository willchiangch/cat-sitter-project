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

import com.petsitter.application.exception.ServicePlanException;
import com.petsitter.application.exception.KycException;
import com.petsitter.domain.repository.ProfileRepository;
import org.springframework.http.HttpStatus;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final OrderRepository orderRepository;
    private final VisitRepository visitRepository;
    private final UserRepository userRepository;
    private final ServicePlanRepository servicePlanRepository;
    private final GatekeeperService gatekeeperService;
    private final ProfileRepository profileRepository;

    @Transactional
    public UUID createBooking(BookingRequest request) {
        log.info("[BookingService] Creating PENDING order for sitter: {}", request.getSitterId());

        // 保母實名認證與接單狀態雙重校驗 (SD-017 / SD-005 聯動)
        com.petsitter.domain.model.Profile sitterProfile = profileRepository.findByUserIdAndType(request.getSitterId(), "SITTER")
                .orElseThrow(() -> new KycException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "保母未開放預約或未完成實名認證"));
        
        if (!sitterProfile.isOpen() || !"VERIFIED".equals(sitterProfile.getKycStatus())) {
            throw new KycException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_DATA_INVALID_STATUS", "保母未開放預約或未完成實名認證");
        }

        // 全域門禁卡控防禦
        if (gatekeeperService.isBlocked(request.getSitterId(), request.getOwnerId(), null)) {
            throw new org.springframework.security.access.AccessDeniedException("保母目前不開放預約");
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("預約項目不得為空");
        }

        java.util.List<OrderItem> orderItems = new java.util.ArrayList<>();
        
        for (com.petsitter.application.dto.BookingItemRequest itemReq : request.getItems()) {
            ServicePlan plan = servicePlanRepository.findById(itemReq.getPlanId())
                    .orElseThrow(() -> new IllegalArgumentException("找不到指定的服務方案: " + itemReq.getPlanId()));

            // 方案級門禁卡控防禦
            if (gatekeeperService.isBlocked(request.getSitterId(), request.getOwnerId(), plan.getId())) {
                throw new org.springframework.security.access.AccessDeniedException("您無權預約此方案");
            }

            // 雙向日期區間防禦 (SD-003)
            for (LocalDate date : itemReq.getDates()) {
                if (plan.getStartDate() != null && date.isBefore(plan.getStartDate())) {
                    throw new ServicePlanException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_NOT_IN_RANGE", "方案目前不在生效區間");
                }
                if (plan.getEndDate() != null && date.isAfter(plan.getEndDate())) {
                    throw new ServicePlanException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_NOT_IN_RANGE", "方案目前不在生效區間");
                }
            }

            OrderItem orderItem = new OrderItem();
            orderItem.setCategory("BOOKING");
            orderItem.setServiceName(plan.getName());
            orderItem.setUnitPrice(plan.getPrice().intValue());
            orderItem.setQuantity(itemReq.getDates().size() * itemReq.getTimesPerDay());
            orderItem.setPlanId(plan.getId());
            orderItem.setDates(itemReq.getDates().stream().map(java.time.LocalDate::toString).collect(java.util.stream.Collectors.toList()));
            orderItem.setTimesPerDay(itemReq.getTimesPerDay());
            if (itemReq.getPetIds() != null) {
                orderItem.setPetIds(itemReq.getPetIds());
            }
            orderItems.add(orderItem);
        }

        int totalAmount = orderItems.stream()
                .mapToInt(item -> item.getUnitPrice() * item.getQuantity())
                .sum();

        Order order = Order.builder()
                .owner(userRepository.findById(request.getOwnerId()).orElseThrow())
                .sitter(userRepository.findById(request.getSitterId()).orElseThrow())
                .planId(request.getItems().get(0).getPlanId()) // 保留首個方案作為主要方案
                .status("PENDING")
                .items(orderItems)
                .totalAmount(totalAmount)
                .idempotencyKey(request.getIdempotencyKey())
                .build();
        
        Order savedOrder = orderRepository.save(order);

        java.util.List<Visit> visitsToSave = new java.util.ArrayList<>();

        for (com.petsitter.application.dto.BookingItemRequest itemReq : request.getItems()) {
            ServicePlan plan = servicePlanRepository.findById(itemReq.getPlanId()).orElseThrow();
            for (LocalDate date : itemReq.getDates()) {
                for (int i = 0; i < itemReq.getTimesPerDay(); i++) {
                    visitsToSave.add(Visit.builder()
                            .order(savedOrder)
                            .planId(plan.getId())
                            .snapshotPlanTitle(plan.getName())
                            .status("PENDING")
                            .scheduledAt(date.atStartOfDay().atOffset(ZoneOffset.UTC))
                            .build());
                }
            }
        }
        
        visitRepository.saveAll(visitsToSave);
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
        List<Visit> visits = visitRepository.findByOrderId(orderId);

        for (Visit visit : visits) {
            String dateStr = visit.getScheduledAt().toLocalDate().toString();
            orderRepository.acquireAdvisoryLock(sitterId.toString(), dateStr);

            ServicePlan plan = servicePlanRepository.findById(visit.getPlanId())
                    .orElseThrow(() -> new IllegalStateException("找不到對應的服務方案: " + visit.getPlanId()));

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
