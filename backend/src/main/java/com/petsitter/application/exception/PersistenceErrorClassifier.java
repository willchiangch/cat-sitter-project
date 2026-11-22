package com.petsitter.application.exception;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * 依 SD-GLOBAL-SPEC §2.6 / SD-ORDER §6.4 分類資料存取例外。
 * <ul>
 *   <li>SQLSTATE 55P03（lock_not_available）/ 40P01（deadlock_detected），或 Spring 轉換後的
 *       {@link PessimisticLockingFailureException}（含 {@code CannotAcquireLockException}、
 *       {@code DeadlockLoserDataAccessException}）→ {@link Kind#LOCK_UNAVAILABLE}</li>
 *   <li>{@code @Version} 衝突 → {@link Kind#VERSION_CONFLICT}</li>
 *   <li>SQLSTATE 23505（unique violation，含 commit 時的延遲唯一約束）→ {@link Kind#UNIQUE_VIOLATION}，帶約束名</li>
 *   <li>其餘（23514 / 23502 / 23503 等確定性錯誤與其他 DB 錯誤）→ {@link Kind#OTHER}（一律 500，不得對映可重試碼）</li>
 * </ul>
 * 不依賴 PostgreSQL driver 類別（runtime scope），以 {@link SQLException#getSQLState()} 與反射取約束名。
 */
public final class PersistenceErrorClassifier {

    public static final String SQLSTATE_LOCK_NOT_AVAILABLE = "55P03";
    public static final String SQLSTATE_DEADLOCK_DETECTED = "40P01";
    public static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    public enum Kind { LOCK_UNAVAILABLE, VERSION_CONFLICT, UNIQUE_VIOLATION, OTHER }

    public record Classification(Kind kind, String sqlState, String constraintName) {
    }

    private PersistenceErrorClassifier() {
    }

    public static Classification classify(Throwable error) {
        String sqlState = null;
        String constraint = null;
        boolean versionConflict = false;
        boolean pessimistic = false;

        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && seen.add(current)) {
            if (current instanceof OptimisticLockingFailureException
                    || current instanceof OptimisticLockException
                    || current.getClass().getName().startsWith("org.hibernate.Stale")) {
                versionConflict = true;
            }
            if (current instanceof PessimisticLockingFailureException) {
                pessimistic = true;
            }
            if (constraint == null) {
                constraint = hibernateConstraintName(current);
            }
            if (current instanceof SQLException sql && sqlState == null) {
                SQLException s = sql;
                while (s != null && sqlState == null) {
                    sqlState = s.getSQLState();
                    if (constraint == null) {
                        constraint = postgresConstraintName(s);
                    }
                    s = s.getNextException();
                }
            }
            current = current.getCause();
        }

        if (SQLSTATE_LOCK_NOT_AVAILABLE.equals(sqlState) || SQLSTATE_DEADLOCK_DETECTED.equals(sqlState)) {
            return new Classification(Kind.LOCK_UNAVAILABLE, sqlState, null);
        }
        if (SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
            return new Classification(Kind.UNIQUE_VIOLATION, sqlState, constraint);
        }
        if (versionConflict) {
            return new Classification(Kind.VERSION_CONFLICT, sqlState, null);
        }
        if (pessimistic && (sqlState == null || !sqlState.startsWith("23"))) {
            return new Classification(Kind.LOCK_UNAVAILABLE, sqlState, null);
        }
        return new Classification(Kind.OTHER, sqlState, constraint);
    }

    private static String hibernateConstraintName(Throwable t) {
        if (!"org.hibernate.exception.ConstraintViolationException".equals(t.getClass().getName())) {
            return null;
        }
        return invokeString(t, "getConstraintName");
    }

    private static String postgresConstraintName(SQLException e) {
        try {
            Method m = e.getClass().getMethod("getServerErrorMessage");
            Object serverMessage = m.invoke(e);
            return serverMessage == null ? null : invokeString(serverMessage, "getConstraint");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String invokeString(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof String s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 供 ERROR log 使用：保留例外類別與堆疊，但訊息只留每層第一行。PostgreSQL 的 {@code Detail:}
     * （例：{@code Failing row contains (...)}、{@code Key (x)=(...)}）會帶出整列資料值，不得寫入 log。
     */
    public static Throwable sanitized(Throwable error) {
        return sanitized(error, 0);
    }

    private static Throwable sanitized(Throwable error, int depth) {
        RuntimeException copy = new RuntimeException(error.getClass().getName() + ": " + firstLine(error.getMessage()));
        copy.setStackTrace(error.getStackTrace());
        Throwable cause = error.getCause();
        if (cause != null && cause != error && depth < 16) {
            copy.initCause(sanitized(cause, depth + 1));
        }
        return copy;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    /** 供 log 使用：不印參數值，只印分類、SQLSTATE、約束名。 */
    public static Map<String, Object> describe(Classification c) {
        return Map.of("kind", c.kind().name(),
                "sqlState", String.valueOf(c.sqlState()),
                "constraint", String.valueOf(c.constraintName()));
    }
}
