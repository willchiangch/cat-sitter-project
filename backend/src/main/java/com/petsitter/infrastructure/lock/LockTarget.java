package com.petsitter.infrastructure.lock;

import java.util.regex.Pattern;

/**
 * 可被 {@link RowLockSupport} 鎖定的資料表（表名直接組進 SQL，因此只接受常數或經格式驗證的名稱）。
 * 依 SD-ORDER §3.6.1 鎖順序，業務上只鎖父訂單列；其他表只給測試或日後經 SD 定義的情境使用。
 */
public record LockTarget(String table) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /** 父訂單列（SD-ORDER §3.6.1 訂單鎖入口唯一的鎖定對象）。 */
    public static final LockTarget ORDERS = new LockTarget("orders");

    public LockTarget {
        if (table == null || !IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("invalid lock target table");
        }
    }

    public static LockTarget of(String table) {
        return new LockTarget(table);
    }
}
