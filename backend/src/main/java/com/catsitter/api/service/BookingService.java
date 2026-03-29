package com.catsitter.api.service;

import com.catsitter.api.dto.booking.BookingDetailResponse;
import com.catsitter.api.dto.booking.BookingResponse;
import com.catsitter.api.dto.booking.CreateBookingRequest;
import com.catsitter.api.dto.booking.SubmitQuoteRequest;
import com.catsitter.api.entity.*;
import com.catsitter.api.entity.enums.*;
import com.catsitter.api.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private final OrderRepository orderRepository;
    private final ProfileRepository profileRepository;
    private final ServiceRepository serviceRepository;
    private final VisitRepository visitRepository;
    private final OrderAnswerRepository orderAnswerRepository;
    private final SitterQuestionRepository questionRepository;
    private final OrderActionLogRepository actionLogRepository;
    private final SitterClientWhitelistRepository whitelistRepository;
    private final CalendarSyncService calendarSyncService;
    private final ObjectMapper objectMapper;

    public BookingService(OrderRepository orderRepository,
                          ProfileRepository profileRepository,
                          ServiceRepository serviceRepository,
                          VisitRepository visitRepository,
                          OrderAnswerRepository orderAnswerRepository,
                          SitterQuestionRepository questionRepository,
                          OrderActionLogRepository actionLogRepository,
                          SitterClientWhitelistRepository whitelistRepository,
                          CalendarSyncService calendarSyncService,
                          ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.profileRepository = profileRepository;
        this.serviceRepository = serviceRepository;
        this.visitRepository = visitRepository;
        this.orderAnswerRepository = orderAnswerRepository;
        this.questionRepository = questionRepository;
        this.actionLogRepository = actionLogRepository;
        this.whitelistRepository = whitelistRepository;
        this.calendarSyncService = calendarSyncService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BookingResponse createBooking(Account account, CreateBookingRequest request) {
        Profile clientProfile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.CLIENT)
                .orElseThrow(() -> new RuntimeException("Client profile not found"));

        Profile sitterProfile = profileRepository.findById(request.sitterProfileId())
                .orElseThrow(() -> new RuntimeException("Sitter not found"));

        com.catsitter.api.entity.Service service = serviceRepository.findById(request.serviceId())
                .orElseThrow(() -> new RuntimeException("Service not found"));

        // Calculate amount
        BigDecimal basePrice = service.getBasePrice();
        BigDecimal totalAmount = basePrice.multiply(BigDecimal.valueOf(request.visits().size()));

        // Create Order
        Order order = new Order();
        order.setClientProfile(clientProfile);
        order.setCurrentSitter(sitterProfile);
        order.setService(service);
        order.setServiceName(service.getName());
        order.setServiceUnitPrice(basePrice);
        order.setBaseAmount(totalAmount);
        order.setTotalAmount(totalAmount);
        order.setOrderStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.UNPAID);
        
        // V31: Check Regular Whitelist Logic
        boolean skipSurvey = whitelistRepository.findBySitterProfileIdAndClientProfileId(sitterProfile.getId(), clientProfile.getId())
                .map(SitterClientWhitelist::getSkipQuestionnaire)
                .orElse(false);

        QuestionnaireStatus qStatus;
        if (skipSurvey) {
            qStatus = QuestionnaireStatus.NOT_REQUIRED;
        } else {
            qStatus = (request.answers() == null || request.answers().isEmpty()) 
                ? QuestionnaireStatus.NOT_REQUIRED 
                : QuestionnaireStatus.COMPLETED;
        }
        order.setQuestionnaireStatus(qStatus);

        Order savedOrder = orderRepository.save(order);

        // Create Visits
        List<Visit> visits = request.visits().stream().map(v -> {
            Visit visit = new Visit();
            visit.setOrder(savedOrder);
            visit.setVisitStartTime(v.startTime());
            visit.setVisitEndTime(v.endTime());
            visit.setStatus(VisitStatus.SCHEDULED);
            return visit;
        }).collect(Collectors.toList());
        visitRepository.saveAll(visits);

        // Create Answers
        List<OrderAnswer> answers = request.answers().stream().map(a -> {
            SitterQuestion question = questionRepository.findById(a.questionId())
                    .orElseThrow(() -> new RuntimeException("Question not found: " + a.questionId()));
            OrderAnswer answer = new OrderAnswer();
            answer.setOrder(savedOrder);
            answer.setQuestion(question);
            answer.setAnswerText(a.answer());
            return answer;
        }).collect(Collectors.toList());
        orderAnswerRepository.saveAll(answers);

        // Log Action
        OrderActionLog log = new OrderActionLog();
        log.setOrder(savedOrder);
        log.setActorProfile(clientProfile);
        log.setActionType(ActionType.ORDER_CREATED);
        log.setNewStatus(OrderStatus.PENDING.name());
        actionLogRepository.save(log);

        return mapToResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public BookingDetailResponse getBookingDetail(Account account, UUID bookingId) {
        Order order = orderRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        // Ownership check: must be the client or the sitter
        UUID profileId = order.getClientProfile().getId();
        UUID sitterId = order.getCurrentSitter().getId();
        boolean isAuthorized = profileRepository.findByAccountId(account.getId()).stream()
                .anyMatch(p -> p.getId().equals(profileId) || p.getId().equals(sitterId));

        if (!isAuthorized) {
            throw new RuntimeException("Unauthorized to view this booking");
        }

        List<BookingDetailResponse.VisitDetail> visits = visitRepository.findByOrderId(bookingId)
                .stream().map(v -> new BookingDetailResponse.VisitDetail(
                        v.getId(), v.getVisitStartTime(), v.getVisitEndTime(), v.getStatus()
                )).collect(Collectors.toList());

        List<BookingDetailResponse.AnswerDetail> answers = orderAnswerRepository.findByOrderId(bookingId)
                .stream().map(a -> new BookingDetailResponse.AnswerDetail(
                        a.getQuestion().getId(), a.getQuestion().getQuestionText(), a.getAnswerText()
                )).collect(Collectors.toList());

        return new BookingDetailResponse(
                order.getId(),
                order.getClientProfile().getId(),
                order.getCurrentSitter().getId(),
                new BookingDetailResponse.ServiceDetail(order.getService().getId(), order.getServiceName(), order.getServiceUnitPrice()),
                order.getTotalAmount(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                order.getQuestionnaireStatus(),
                visits,
                answers
        );
    }

    private BookingResponse mapToResponse(Order order) {
        return new BookingResponse(
                order.getId(),
                order.getClientProfile().getId(),
                order.getCurrentSitter().getId(),
                order.getService().getId(),
                order.getServiceName(),
                order.getTotalAmount(),
                order.getOrderStatus(),
                order.getPaymentStatus()
        );
    }

    @Transactional
    public BookingDetailResponse submitQuote(Account sitterAccount, UUID bookingId, SubmitQuoteRequest request) {
        Order order = orderRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (!order.getCurrentSitter().getAccount().getId().equals(sitterAccount.getId())) {
            throw new RuntimeException("Unauthorized: You are not the sitter for this booking");
        }

        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new RuntimeException("Cannot submit quote: Order is not in PENDING status");
        }

        order.setBaseAmount(request.baseAmount());
        order.setSurchargeAmount(request.surchargeAmount());
        order.setDiscountAmount(request.discountAmount());
        order.setPricingNotes(request.pricingNotes());
        
        BigDecimal total = request.baseAmount()
                .add(request.surchargeAmount())
                .subtract(request.discountAmount());
        order.setTotalAmount(total);
        
        order.setOrderStatus(OrderStatus.QUOTED);
        orderRepository.save(order);

        // Log action
        OrderActionLog log = new OrderActionLog();
        log.setOrder(order);
        log.setActorProfile(order.getCurrentSitter());
        log.setActionType(ActionType.STATUS_CHANGED);
        log.setPreviousStatus(OrderStatus.PENDING.name());
        log.setNewStatus(OrderStatus.QUOTED.name());
        try {
            log.setMetadata(objectMapper.writeValueAsString(Map.of("action", "SUBMIT_QUOTE")));
        } catch (Exception e) {
            log.setMetadata("{}");
        }
        actionLogRepository.save(log);

        return getBookingDetail(sitterAccount, bookingId);
    }

    @Transactional
    public BookingDetailResponse uploadPaymentProof(Account clientAccount, UUID bookingId, Map<String, Object> proofData) {
        Order order = orderRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (!order.getClientProfile().getAccount().getId().equals(clientAccount.getId())) {
            throw new RuntimeException("Unauthorized: You are not the client for this booking");
        }

        order.setPaymentStatus(PaymentStatus.PENDING_VERIFICATION);
        orderRepository.save(order);

        // Log action
        OrderActionLog log = new OrderActionLog();
        log.setOrder(order);
        log.setActorProfile(order.getClientProfile());
        log.setActionType(ActionType.STATUS_CHANGED);
        log.setNewStatus(PaymentStatus.PENDING_VERIFICATION.name());
        try {
            log.setMetadata(objectMapper.writeValueAsString(proofData));
        } catch (Exception e) {
            log.setMetadata("{}");
        }
        actionLogRepository.save(log);

        return getBookingDetail(clientAccount, bookingId);
    }

    @Transactional
    public BookingDetailResponse confirmOfflinePayment(Account sitterAccount, UUID bookingId) {
        Order order = orderRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (!order.getCurrentSitter().getAccount().getId().equals(sitterAccount.getId())) {
            throw new RuntimeException("Unauthorized: You are not the sitter for this booking");
        }

        order.setPaymentStatus(PaymentStatus.PAID);
        order.setOrderStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        // Sync to Sitter's Calendar
        calendarSyncService.syncOrderEvents(order);

        // Log action
        OrderActionLog log = new OrderActionLog();
        log.setOrder(order);
        log.setActorProfile(order.getCurrentSitter());
        log.setActionType(ActionType.STATUS_CHANGED);
        log.setNewStatus(PaymentStatus.PAID.name());
        try {
            log.setMetadata(objectMapper.writeValueAsString(Map.of("action", "CONFIRM_OFFLINE_PAYMENT")));
        } catch (Exception e) {
            log.setMetadata("{}");
        }
        actionLogRepository.save(log);

        return getBookingDetail(sitterAccount, bookingId);
    }

    @Transactional
    public BookingDetailResponse completeOrder(Account clientAccount, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (!order.getClientProfile().getAccount().getId().equals(clientAccount.getId())) {
            throw new RuntimeException("Unauthorized: You are not the client for this booking");
        }

        List<Visit> visits = visitRepository.findByOrderId(orderId);
        boolean allDone = !visits.isEmpty() && visits.stream().allMatch(v -> v.getStatus() == VisitStatus.DONE);

        if (!allDone) {
            throw new RuntimeException("Cannot complete order: Some visits are not yet finished or no visits found");
        }

        order.setOrderStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);

        // Log action
        OrderActionLog log = new OrderActionLog();
        log.setOrder(order);
        log.setActorProfile(order.getClientProfile());
        log.setActionType(ActionType.STATUS_CHANGED);
        log.setPreviousStatus(OrderStatus.CONFIRMED.name());
        log.setNewStatus(OrderStatus.COMPLETED.name());
        try {
            log.setMetadata(objectMapper.writeValueAsString(Map.of("action", "COMPLETE_ORDER")));
        } catch (Exception e) {
            log.setMetadata("{}");
        }
        actionLogRepository.save(log);

        return getBookingDetail(clientAccount, orderId);
    }
}
