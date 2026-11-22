package testsupport.fulfill;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import testsupport.order.OrderTestConfig;

/** SD-FULFILL 整合測試：沿用訂單測試的 Clock，另以可程式化 fake 取代 MediaStorageService（§10.1）。 */
@TestConfiguration
@Import(OrderTestConfig.class)
public class FulfillTestConfig {

    @Bean
    @Primary
    public FakeMediaStorageService fakeMediaStorageService() {
        return new FakeMediaStorageService();
    }
}
