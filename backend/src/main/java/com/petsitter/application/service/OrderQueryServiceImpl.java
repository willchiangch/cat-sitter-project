package com.petsitter.application.service;

import com.petsitter.application.dto.OrderDetailResponseDto;
import com.petsitter.application.dto.OrderLedgerEntryDto;
import com.petsitter.application.dto.OrderSummaryDto;
import com.petsitter.application.dto.SitterLedgerResponse;
import com.petsitter.domain.model.BankAccountInfo;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderItem;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryServiceImpl implements OrderQueryService {

    private final OrderRepository orderRepository;
    private final ProfileRepository profileRepository;

    @Override
    @Transactional(readOnly = true)
    public OrderDetailResponseDto getOrderDetail(UUID orderId, UUID requesterId) {
        log.info("Querying order details for order: {}, requester: {}", orderId, requesterId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到對應的訂單: " + orderId));

        // BOLA 安全性檢核：請求者必須是訂單的飼主或保母
        boolean isOwner = order.getOwner().getId().equals(requesterId);
        boolean isSitter = order.getSitter().getId().equals(requesterId);

        if (!isOwner && !isSitter) {
            log.error("BOLA Violation: User {} tried to query details for order {} owned by {} and assigned to {}", 
                    requesterId, orderId, order.getOwner().getId(), order.getSitter().getId());
            throw new AccessDeniedException("權限不足：您非此訂單的飼主或服務保母");
        }

        // 依訂單狀態過濾並注入保母匯款帳戶資訊 (僅 PENDING_PAYMENT 與 PAID 開放)
        BankAccountInfo bankAccountInfo = null;
        if ("PENDING_PAYMENT".equals(order.getStatus()) || "PAID".equals(order.getStatus())) {
            UUID sitterUserId = order.getSitter().getId();
            Profile sitterProfile = profileRepository.findByUserIdAndType(sitterUserId, "SITTER").orElse(null);
            if (sitterProfile != null) {
                // 由 AttributeConverter 自動於載入時解密
                bankAccountInfo = sitterProfile.getBankAccountInfo();
            }
        }

        return OrderDetailResponseDto.builder()
                .id(order.getId())
                .ownerId(order.getOwner().getId())
                .ownerName(order.getOwner().getFullName())
                .sitterId(order.getSitter().getId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .version(order.getVersion())
                .adjustmentAmount(order.getAdjustmentAmount())
                .adjustmentReason(order.getAdjustmentReason())
                .paymentProofUrl(order.getPaymentProofUrl())
                .paymentProofLastFive(order.getPaymentProofLastFive())
                .disclaimerAgreed(order.isDisclaimerAgreed())
                .disclaimerAgreedAt(order.getDisclaimerAgreedAt())
                .paidAt(order.getPaidAt())
                .completedAt(order.getCompletedAt())
                .payoutAt(order.getPayoutAt())
                .items(order.getItems())
                .sitterPaymentInfo(bankAccountInfo)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getMyOrdersAsOwner(UUID ownerId) {
        return orderRepository.findByOwnerIdWithParties(ownerId).stream()
                .map(this::toSummaryDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getMyOrdersAsSitter(UUID sitterId) {
        return orderRepository.findBySitterIdWithParties(sitterId).stream()
                .map(this::toSummaryDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SitterLedgerResponse getSitterLedger(UUID sitterId, YearMonth month) {
        OffsetDateTime from = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<OrderLedgerEntryDto> entries = orderRepository
                .findCompletedBySitterIdAndCompletedAtBetween(sitterId, from, to).stream()
                .map(order -> OrderLedgerEntryDto.builder()
                        .orderId(order.getId())
                        .ownerName(order.getOwner().getFullName())
                        .totalAmount(order.getTotalAmount())
                        .paidAt(order.getPaidAt())
                        .completedAt(order.getCompletedAt())
                        .payoutAt(order.getPayoutAt())
                        .build())
                .toList();

        int totalRevenue = entries.stream().mapToInt(OrderLedgerEntryDto::getTotalAmount).sum();

        return SitterLedgerResponse.builder()
                .yearMonth(month.toString())
                .totalRevenue(totalRevenue)
                .entries(entries)
                .build();
    }

    private OrderSummaryDto toSummaryDto(Order order) {
        return OrderSummaryDto.builder()
                .id(order.getId())
                .ownerId(order.getOwner().getId())
                .ownerName(order.getOwner().getFullName())
                .sitterId(order.getSitter().getId())
                .sitterName(order.getSitter().getFullName())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .paymentProofUrl(order.getPaymentProofUrl())
                .paymentProofLastFive(order.getPaymentProofLastFive())
                .scheduledDatesLabel(buildScheduledDatesLabel(order.getItems()))
                .build();
    }

    private String buildScheduledDatesLabel(List<OrderItem> items) {
        List<String> dates = items.stream()
                .flatMap(item -> item.getDates() == null ? java.util.stream.Stream.<String>empty() : item.getDates().stream())
                .sorted()
                .toList();

        if (dates.isEmpty()) {
            return "尚未排定日期";
        }

        String start = dates.get(0);
        String end = dates.get(dates.size() - 1);
        long dayCount = dates.stream().distinct().count();

        if (start.equals(end)) {
            return start + " (共 " + dayCount + " 天)";
        }
        return start + " ~ " + end + " (共 " + dayCount + " 天)";
    }
}
