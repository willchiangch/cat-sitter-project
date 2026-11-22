package com.petsitter.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SD-FULFILL §2.3.1 / §2.3.3（FulfillAggregateArchTest，與 SD-ORDER {@code OrderAggregateArchTest} 對稱）：
 * <ul>
 *   <li>本模組 Repository（{@code domain.repository.fulfill}）只准 {@code application.service.fulfill} 依賴</li>
 *   <li>{@code VisitCommandService} 寫入方法的呼叫者限 {@code application.service.order} 與本套件</li>
 *   <li>{@code Visit} 等實體不得依賴 {@code Order} entity</li>
 *   <li>除 {@code CareLogMediaRetentionPort}（與其實作）外不新增 {@code CareLog*} 類別（SD-CARE 既有 {@code CareLog} 除外）</li>
 *   <li>{@code FulfillTestInspectionQuery} 只由 test-data 使用</li>
 * </ul>
 */
@DisplayName("SD-FULFILL §2.3.1 FulfillAggregateArchTest（ArchUnit）")
class FulfillAggregateArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.petsitter");

    @Test
    @DisplayName("共用（§2.3.1）Visit / 檢核項 / 日誌 / 媒體 / 上傳槽位 Repository 只被 application.service.fulfill 依賴")
    void fulfillRepositoriesArePrivate() {
        noClasses().that().resideOutsideOfPackages("com.petsitter.application.service.fulfill..", "com.petsitter.domain.repository.fulfill..")
                .should().dependOnClassesThat().resideInAPackage("com.petsitter.domain.repository.fulfill..")
                .check(MAIN);
    }

    @Test
    @DisplayName("共用（§2.3.1）VisitCommandService 只被 application.service.order 與 application.service.fulfill 呼叫")
    void visitCommandCallers() {
        noClasses().that().resideOutsideOfPackages("com.petsitter.application.service.order..", "com.petsitter.application.service.fulfill..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.petsitter.application.service.fulfill.VisitCommandService")
                .check(MAIN);
    }

    @Test
    @DisplayName("共用（§2.5 第 2 點）domain.model.fulfill 不依賴訂單實體 Order")
    void visitDoesNotDependOnOrder() {
        noClasses().that().resideInAPackage("com.petsitter.domain.model.fulfill..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.petsitter.domain.model.order.Order")
                .check(MAIN);
    }

    @Test
    @DisplayName("共用（§2.3.1 命名）除 CareLogMediaRetentionPort 外不新增 CareLog* 類別")
    void noNewCareLogClasses() {
        Set<String> allowed = Set.of(
                "com.petsitter.domain.model.CareLog",
                "com.petsitter.domain.repository.CareLogRepository",
                "com.petsitter.application.service.fulfill.CareLogMediaRetentionPort",
                "com.petsitter.application.service.fulfill.CareLogMediaRetentionPortImpl");
        Set<String> found = MAIN.stream()
                .filter(c -> c.getSimpleName().startsWith("CareLog"))
                .map(JavaClass::getName)
                .map(n -> n.split("\\$")[0])  // Lombok 產生的巢狀 builder 歸屬其外層類別
                .collect(Collectors.toSet());
        assertThat(found).isSubsetOf(allowed);
    }

    @Test
    @DisplayName("共用（§2.3.3）FulfillTestInspectionQuery 只被 test-data 使用")
    void inspectionOnlyForTestData() {
        noClasses().that().haveSimpleNameNotContaining("TestData")
                .and().doNotHaveFullyQualifiedName("com.petsitter.application.service.fulfill.FulfillTestInspectionQuery")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.petsitter.application.service.fulfill.FulfillTestInspectionQuery")
                .check(MAIN);
    }
}
