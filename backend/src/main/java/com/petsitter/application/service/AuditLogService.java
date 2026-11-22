package com.petsitter.application.service;

import com.petsitter.domain.model.CareLog;
import com.petsitter.domain.model.UserActionLog;
import com.petsitter.domain.repository.CareLogRepository;
import com.petsitter.domain.repository.UserActionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 非訂單稽核（SD-ORDER M4：writeOrderLog 已刪除，訂單稽核一律 OrderLogWriter，同交易）。
 * <p>
 * SD-GLOBAL-SPEC §2.9 / §4.3（NFR-003 SEC-AUDIT-1、SEC-LOG-1、G-13）：
 * <ul>
 *   <li>{@link #writeUserActionLog} 與主操作同交易（{@code MANDATORY}，無交易呼叫即拋例外）；寫入失敗讓主交易一起回滾。</li>
 *   <li>不以 {@code log.info} 印出稽核 details 全文。</li>
 *   <li>{@link #writeLog}（照護 / 方案的 {@code care_logs}）暫維持 {@code REQUIRES_NEW}：呼叫端在主操作失敗後仍寫 FAILED 紀錄，
 *       改同交易需重寫未重建模組（SD-CARE / SD-SUPPLY 重建時處理）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final CareLogRepository careLogRepository;
    private final UserActionLogRepository userActionLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeLog(UUID userId, String action, String status, String details) {
        log.debug("care audit log written: user={}, action={}, status={}", userId, action, status);
        try {
            CareLog careLog = CareLog.builder()
                    .userId(userId)
                    .action(action)
                    .status(status)
                    .details(details)
                    .build();
            careLogRepository.insert(careLog);
        } catch (Exception e) {
            log.error("Failed to write audit log to database: action={}", action, e);
        }
    }

    /** 帳號類稽核：與主操作同交易（§4.3 MANDATORY）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeUserActionLog(String funcCode, String actionType, UUID operatorId, UUID targetId, String targetTable) {
        UserActionLog auditLog = UserActionLog.builder()
                .funcCode(funcCode)
                .actionType(actionType)
                .operatorId(operatorId)
                .targetId(targetId)
                .targetTable(targetTable)
                .actionResult("SUCCESS")
                .build();
        userActionLogRepository.insert(auditLog);
    }

    /**
     * 與呼叫端共用同一交易（舊名，行為同 {@link #writeUserActionLog}）。
     * operator_id/target_id 指向本次交易內才建立的使用者（如註冊當下）時，同交易才不會違反 FK。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeUserActionLogInline(String funcCode, String actionType, UUID operatorId, UUID targetId, String targetTable) {
        writeUserActionLog(funcCode, actionType, operatorId, targetId, targetTable);
    }
}
