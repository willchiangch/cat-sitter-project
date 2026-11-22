package com.petsitter.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * SD-ORDER §3.6.1「強制」（OrderAggregateArchTest）：
 * <ul>
 *   <li>訂單聚合 Repository（{@code domain.repository.order}）只准 {@code application.service.order} 依賴</li>
 *   <li>{@code IdempotencyRecordRepository} 只准共用冪等套件依賴（見 {@link SharedMechanismArchTest}）</li>
 *   <li>{@code VisitCommandService} 寫入方法只能由 order 套件與 SD-FULFILL 自身呼叫</li>
 *   <li>行程實體不得依賴訂單實體（SD-FULFILL §2.5 第 2 點）</li>
 * </ul>
 */
@DisplayName("SD-ORDER §3.6.1 OrderAggregateArchTest（ArchUnit）")
class OrderAggregateArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.petsitter");

    @Test
    @DisplayName("訂單聚合 Repository 只被 application.service.order 依賴")
    void orderRepositoriesArePrivateToOrderService() {
        noClasses().that().resideOutsideOfPackages("com.petsitter.application.service.order..", "com.petsitter.domain.repository.order..")
                .should().dependOnClassesThat().resideInAPackage("com.petsitter.domain.repository.order..")
                .check(MAIN);
    }

    @Test
    @DisplayName("IdempotencyRecordRepository 只被 application.common.idempotency 依賴")
    void idempotencyRecordRepository() {
        noClasses().that().resideOutsideOfPackage("com.petsitter.application.common.idempotency..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.petsitter.application.common.idempotency.IdempotencyRecordRepository")
                .check(MAIN);
    }

    @Test
    @DisplayName("VisitCommandService 只被 application.service.order 與 application.service.fulfill 呼叫")
    void visitCommandServiceCallers() {
        noClasses().that().resideOutsideOfPackages("com.petsitter.application.service.order..", "com.petsitter.application.service.fulfill..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.petsitter.application.service.fulfill.VisitCommandService")
                .check(MAIN);
    }

    @Test
    @DisplayName("行程實體（domain.model.fulfill）不依賴訂單實體 Order")
    void visitDoesNotDependOnOrder() {
        noClasses().that().resideInAPackage("com.petsitter.domain.model.fulfill..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.petsitter.domain.model.order.Order")
                .check(MAIN);
    }
}
