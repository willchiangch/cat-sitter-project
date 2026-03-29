package com.catsitter.api.service;

import com.catsitter.api.dto.payment.PayUniWebhookRequest;
import com.catsitter.api.dto.payment.SitterFinanceResponse;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Order;
import com.catsitter.api.entity.PaymentTransaction;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.SitterSubscription;
import com.catsitter.api.entity.enums.OrderStatus;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.OrderRepository;
import com.catsitter.api.repository.PaymentTransactionRepository;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.SitterSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FinanceService {

    private static final Logger logger = LoggerFactory.getLogger(FinanceService.class);

    private final PaymentTransactionRepository transactionRepository;
    private final SitterSubscriptionRepository subscriptionRepository;
    private final OrderRepository orderRepository;
    private final ProfileRepository profileRepository;
    private final PayUniService payUniService;

    public FinanceService(PaymentTransactionRepository transactionRepository,
                          SitterSubscriptionRepository subscriptionRepository,
                          OrderRepository orderRepository,
                          ProfileRepository profileRepository,
                          PayUniService payUniService) {
        this.transactionRepository = transactionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.orderRepository = orderRepository;
        this.profileRepository = profileRepository;
        this.payUniService = payUniService;
    }

    @Transactional(readOnly = true)
    public SitterFinanceResponse getSitterFinanceSummary(Account account) {
        Profile sitterProfile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found"));

        UUID sitterId = sitterProfile.getId();

        // 1. Aggregations
        BigDecimal totalRevenue = orderRepository.sumTotalAmountBySitterAndStatus(sitterId, OrderStatus.COMPLETED);
        if (totalRevenue == null) totalRevenue = BigDecimal.ZERO;

        BigDecimal pendingBalance = orderRepository.sumTotalAmountBySitterAndStatus(sitterId, OrderStatus.CONFIRMED);
        if (pendingBalance == null) pendingBalance = BigDecimal.ZERO;

        long totalCompleted = orderRepository.countBySitterAndStatus(sitterId, OrderStatus.COMPLETED);
        BigDecimal avgOrderValue = totalCompleted > 0 
                ? totalRevenue.divide(BigDecimal.valueOf(totalCompleted), 2, RoundingMode.HALF_UP) 
                : BigDecimal.ZERO;

        // 2. Recent Transactions (Top 10)
        List<Order> recentOrders = orderRepository.findRecentBySitter(sitterId, PageRequest.of(0, 10));
        List<SitterFinanceResponse.TransactionItem> ledger = recentOrders.stream()
                .map(o -> new SitterFinanceResponse.TransactionItem(
                        o.getId(),
                        LocalDate.ofInstant(o.getCreatedAt(), ZoneId.systemDefault()),
                        o.getClientProfile().getName(),
                        o.getServiceName(), // Assuming service name represents cat names in localized DTO context
                        o.getTotalAmount(),
                        o.getOrderStatus(),
                        o.getPricingNotes()
                )).collect(Collectors.toList());

        return new SitterFinanceResponse(
                totalRevenue,
                pendingBalance,
                totalRevenue, // Monthly logic could be added with additional date-bounded queries
                (int)orderRepository.countBySitterAndStatus(sitterId, OrderStatus.CONFIRMED),
                avgOrderValue,
                ledger
        );
    }

    @Transactional
    public String processPayUniWebhook(PayUniWebhookRequest request) {
        // 1. Verify Token
        if (!payUniService.verifyWebhookToken(request)) {
            logger.warn("Invalid PayUni token for MerTradeNo: {}", request.MerTradeNo());
            return "0|InvalidToken";
        }

        // 2. Find Transaction
        Optional<PaymentTransaction> txOpt = transactionRepository.findByMerTradeNo(request.MerTradeNo());
        if (txOpt.isEmpty()) {
            logger.warn("Transaction not found for MerTradeNo: {}", request.MerTradeNo());
            return "0|TransactionNotFound";
        }

        PaymentTransaction tx = txOpt.get();
        if ("SUCCESS".equals(tx.getStatus())) {
            return "1|OK"; // Idempotency
        }

        // 3. Update Status
        tx.setTradeNo(request.TradeNo());
        tx.setPaymentType(request.PaymentType());
        tx.setPayTime(request.Timestamp() != null ? Instant.ofEpochSecond(Long.parseLong(request.Timestamp())) : Instant.now());

        if ("1".equals(request.Status())) {
            tx.setStatus("SUCCESS");
            
            // 4. Handle Subscription (if applicable)
            if (tx.getSubscription() != null) {
                activateSubscription(tx.getSubscription());
            }
            
            logger.info("Payment successful for MerTradeNo: {}", request.MerTradeNo());
        } else {
            tx.setStatus("FAILED");
            logger.info("Payment failed for MerTradeNo: {}", request.MerTradeNo());
        }

        transactionRepository.save(tx);
        return "1|OK";
    }

    private void activateSubscription(SitterSubscription sub) {
        LocalDate now = LocalDate.now();
        
        // If current sub is still active, extend it. Otherwise, start from now.
        if (sub.getEndDate() != null && sub.getEndDate().isAfter(now)) {
            sub.setEndDate(sub.getEndDate().plusMonths(1)); // Default to monthly extension for MVP
        } else {
            sub.setStartDate(now);
            sub.setEndDate(now.plusMonths(1));
        }
        
        sub.setStatus("ACTIVE");
        subscriptionRepository.save(sub);
        logger.info("Subscription activated for Sitter: {} until {}", sub.getSitterProfile().getId(), sub.getEndDate());
    }
}
