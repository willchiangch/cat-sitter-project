package com.petsitter.infrastructure.security;

import com.petsitter.application.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SD-GLOBAL-SPEC §4.3 同交易（NFR-003 SEC-AUDIT-1、G-13）：{@code AuditLogService.writeUserActionLog} 傳播 {@code MANDATORY}；
 * 主交易回滾 → 稽核 0 列，成功 → 恰 1 列；log 不印稽核 details。
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("NFR-003 SEC-AUDIT-1 AuditTransactionIntegrationTest")
class AuditTransactionIntegrationTest extends SecurityIntegrationTestBase {

    @Autowired
    private AuditLogService auditLogService;
    @Autowired
    private PlatformTransactionManager txManager;

    private int rows(UUID target) {
        return count("SELECT COUNT(*) FROM log_user_action WHERE target_id = ?", target);
    }

    @Test
    @DisplayName("SEC-AUDIT-1.2 無交易呼叫 writeUserActionLog → 拋例外、不寫入")
    void mandatory() {
        UUID target = UUID.randomUUID();
        assertThatThrownBy(() -> auditLogService.writeUserActionLog("T", "UPDATE", owner, target, "users"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rows(target)).isZero();
    }

    @Test
    @DisplayName("SEC-AUDIT-1.5 注入失敗使主交易回滾 → 稽核 0 列；成功 → 恰 1 列；log 不含 details")
    void rollbackAndCommit(CapturedOutput output) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        UUID rolledBack = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            auditLogService.writeUserActionLog("T_ROLLBACK", "UPDATE", owner, rolledBack, "users");
            throw new RuntimeException("inject failure");
        })).isInstanceOf(RuntimeException.class);
        assertThat(rows(rolledBack)).isZero();
        UUID committed = UUID.randomUUID();
        tx.executeWithoutResult(s -> auditLogService.writeUserActionLog("T_COMMIT_MARKER", "UPDATE", owner, committed, "users"));
        assertThat(rows(committed)).isEqualTo(1);
        assertThat(output.getOut()).doesNotContain("T_COMMIT_MARKER").doesNotContain("USER ACTION AUDIT LOG");
    }
}
