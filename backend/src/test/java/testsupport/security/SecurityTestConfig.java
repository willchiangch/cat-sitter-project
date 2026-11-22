package testsupport.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** NFR-003 S1 安全測試：掛上觸發各類例外的測試端點（{@link LeakTestController}）。 */
@TestConfiguration
@Import(LeakTestController.class)
public class SecurityTestConfig {
}
