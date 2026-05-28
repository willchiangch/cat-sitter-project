package com.petsitter.test;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

public class DatabaseCleanupListener extends AbstractTestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) throws Exception {
        try {
            JdbcTemplate jdbcTemplate = testContext.getApplicationContext().getBean(JdbcTemplate.class);
            jdbcTemplate.execute(
                "DO $$\n" +
                "DECLARE\n" +
                "    r RECORD;\n" +
                "BEGIN\n" +
                "    FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename != 'flyway_schema_history') LOOP\n" +
                "        EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' RESTART IDENTITY CASCADE';\n" +
                "    END LOOP;\n" +
                "END $$;"
            );
        } catch (Exception e) {
            // Context 未完全啟動或找不到 JdbcTemplate 時靜默忽略
        }
    }
}
