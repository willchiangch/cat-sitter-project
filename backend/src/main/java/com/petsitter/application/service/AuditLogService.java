package com.petsitter.application.service;

import com.petsitter.domain.model.CareLog;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderLog;
import com.petsitter.domain.repository.CareLogRepository;
import com.petsitter.domain.repository.OrderLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final CareLogRepository careLogRepository;
    private final OrderLogRepository orderLogRepository;
    private final com.petsitter.domain.repository.UserActionLogRepository userActionLogRepository;


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeLog(UUID userId, String action, String status, String details) {
        log.info("AUDIT LOG [REQUIRES_NEW] - User: {}, Action: {}, Status: {}, Details: {}", 
                 userId, action, status, details);
        try {
            CareLog careLog = CareLog.builder()
                    .userId(userId)
                    .action(action)
                    .status(status)
                    .details(details)
                    .build();
            careLogRepository.save(careLog);
        } catch (Exception e) {
            log.error("Failed to write audit log to database", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeOrderLog(Order order, String operatorId, String actionType, Map<String, Object> payload) {
        log.info("ORDER AUDIT LOG [REQUIRES_NEW] - Order: {}, Operator: {}, Action: {}, Payload: {}", 
                 order.getId(), operatorId, actionType, payload);
        try {
            OrderLog orderLog = OrderLog.builder()
                    .order(order)
                    .operatorId(operatorId)
                    .actionType(actionType)
                    .payload(payload)
                    .build();
            orderLogRepository.save(orderLog);
        } catch (Exception e) {
            log.error("Failed to write order log to database", e);
        }
     }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeUserActionLog(String funcCode, String actionType, UUID operatorId, UUID targetId, String targetTable) {
        log.info("USER ACTION AUDIT LOG [REQUIRES_NEW] - FuncCode: {}, ActionType: {}, Operator: {}, TargetId: {}, TargetTable: {}", 
                 funcCode, actionType, operatorId, targetId, targetTable);
        try {
            com.petsitter.domain.model.UserActionLog auditLog = com.petsitter.domain.model.UserActionLog.builder()
                    .funcCode(funcCode)
                    .actionType(actionType)
                    .operatorId(operatorId)
                    .targetId(targetId)
                    .targetTable(targetTable)
                    .actionResult("SUCCESS")
                    .build();
            userActionLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to write user action log to database", e);
        }
    }
}

