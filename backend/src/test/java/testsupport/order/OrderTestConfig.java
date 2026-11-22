package testsupport.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import testsupport.sharedmech.MutableClock;

/** 訂單整合測試：可設定時間的 Clock（SD-ORDER §10.1「時間」）。 */
@TestConfiguration
public class OrderTestConfig {

    @Bean
    @Primary
    public MutableClock orderTestClock() {
        return new MutableClock();
    }
}
