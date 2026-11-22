package testsupport.sharedmech;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import testsupport.sharedmech.module.SharedMechModuleAdvice;
import testsupport.sharedmech.module.SharedMechModuleController;
import testsupport.sharedmech.other.UnmappedModuleController;


/**
 * 共用機制整合測試用的 controller / advice / Clock。放在 {@code com.petsitter} 之外，
 * 不會被主程式 component scan 掃到（不進 OpenAPI、不影響其他測試的 context）。
 */
@TestConfiguration
@Import({SharedMechModuleController.class, SharedMechModuleAdvice.class, UnmappedModuleController.class})
public class SharedMechTestConfig {

    /** 取代 TimeConfig 的 Clock（注入 Clock 型別時以 @Primary 勝出）。 */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
