package com.petsitter.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * SD-GLOBAL-SPEC §2.4 / SD-ORDER §3.6.1「強制」：{@code IdempotencyRecordRepository} 只准共用冪等套件依賴。
 * （B 階段在此擴充 {@code OrderAggregateArchTest} 的訂單聚合 Repository 規則。）
 */
@DisplayName("SD-GLOBAL §2.4 共用冪等套件依賴規則（ArchUnit）")
class SharedMechanismArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.petsitter");

    @Test
    @DisplayName("IdempotencyRecordRepository 只能被 application.common.idempotency 依賴")
    void idempotencyRecordRepositoryIsPrivateToPackage() {
        noClasses().that().resideOutsideOfPackage("com.petsitter.application.common.idempotency..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.petsitter.application.common.idempotency.IdempotencyRecordRepository")
                .check(MAIN);
    }

    @Test
    @DisplayName("新程式不得使用舊 IdempotencyService 名稱（已改名 LegacyIdempotencyService）")
    void legacyServiceRenamed() {
        noClasses().should().dependOnClassesThat()
                .haveFullyQualifiedName("com.petsitter.application.service.IdempotencyService")
                .allowEmptyShould(true)
                .check(MAIN);
    }
}
