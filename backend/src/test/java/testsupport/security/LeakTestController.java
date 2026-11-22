package testsupport.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SD-GLOBAL-SPEC §2.6 {@code ErrorResponseLeakIntegrationTest} 的觸發端點（只在測試 classpath；走全域 {@code GlobalExceptionHandler}）。
 */
@RestController
@RequestMapping("/api/test-leak")
@PreAuthorize("isAuthenticated()")
public class LeakTestController {

    public static final String SECRET_DETAIL = "SELECT password_hash FROM users WHERE secret_marker_9f3";
    public static final String CONSTRAINT = "ck_secret_marker_constraint";

    private final JdbcTemplate jdbcTemplate;

    public LeakTestController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/{scenario}")
    public Map<String, Object> trigger(@PathVariable String scenario) {
        switch (scenario) {
            case "runtime" -> throw new RuntimeException(SECRET_DETAIL);
            case "check" -> jdbcTemplate.execute("DO $$ BEGIN RAISE EXCEPTION 'new row violates check constraint \"" + CONSTRAINT
                    + "\" of relation \"users\"' USING ERRCODE = '23514', CONSTRAINT = '" + CONSTRAINT + "'; END $$");
            case "authentication" -> throw new InsufficientAuthenticationException(SECRET_DETAIL);
            case "illegal-argument" -> throw new IllegalArgumentException(SECRET_DETAIL);
            case "illegal-state" -> throw new IllegalStateException(SECRET_DETAIL);
            case "ok" -> {
                return Map.of("ok", true);
            }
            default -> throw new UnsupportedOperationException(SECRET_DETAIL);
        }
        return Map.of();
    }

    @PostMapping("/json")
    public Map<String, Object> json(@RequestBody Map<String, Object> body) {
        return Map.of("size", body.size());
    }
}
