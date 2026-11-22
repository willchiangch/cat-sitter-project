package testsupport.sharedmech;

import com.petsitter.infrastructure.security.JwtUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

/** 共用機制整合測試的資料表與真 JWT。 */
public final class SharedMechSupport {

    private SharedMechSupport() {
    }

    /** DatabaseCleanupListener 每個測試前 TRUNCATE 所有表，這裡只負責第一次建立。 */
    public static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS shared_mech_widgets (
                    id UUID PRIMARY KEY,
                    order_id UUID NOT NULL,
                    name VARCHAR(200)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS shared_mech_lock_rows (
                    id UUID PRIMARY KEY,
                    counter INT NOT NULL DEFAULT 0
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS shared_mech_children (
                    id UUID PRIMARY KEY,
                    parent_id UUID NOT NULL REFERENCES shared_mech_lock_rows(id)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS shared_mech_constraints (
                    id UUID PRIMARY KEY,
                    named_code VARCHAR(10) NOT NULL,
                    other_code VARCHAR(10) NOT NULL,
                    positive INT NOT NULL,
                    CONSTRAINT ux_shared_mech_named UNIQUE (named_code),
                    CONSTRAINT ux_shared_mech_other UNIQUE (other_code),
                    CONSTRAINT ck_shared_mech_positive CHECK (positive > 0)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS shared_mech_deferred (
                    id UUID PRIMARY KEY,
                    sort_order INT NOT NULL,
                    CONSTRAINT ux_shared_mech_deferred UNIQUE (sort_order) DEFERRABLE INITIALLY DEFERRED
                )""");
    }

    public static String bearer(JwtUtils jwtUtils, UUID userId, String role) {
        return "Bearer " + jwtUtils.generateToken("shared-mech-" + userId + "@test.com", role,
                Map.<String, Object>of("userId", userId.toString()), 3_600_000L);
    }
}
