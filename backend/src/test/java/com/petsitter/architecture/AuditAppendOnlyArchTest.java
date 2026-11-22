package com.petsitter.architecture;

import com.petsitter.domain.repository.AppendOnlyRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SD-GLOBAL-SPEC §4.3 {@code AuditAppendOnlyArchTest}（NFR-003 SEC-AUDIT-3）：稽核實體的 Repository 都不是
 * {@code CrudRepository} 子型別、沒有 {@code delete*} / {@code save*} 方法，且繼承 {@link AppendOnlyRepository}。
 * 稽核實體 = {@code domain.model} 下名稱以 {@code Log} 結尾的實體（{@code order_logs}、{@code log_user_action}、
 * {@code care_logs}、{@code pet_edit_logs}、{@code referral_logs}）。
 */
@DisplayName("NFR-003 SEC-AUDIT-3 AuditAppendOnlyArchTest")
class AuditAppendOnlyArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.petsitter");

    private static final List<String> AUDIT_REPOSITORIES = List.of(
            "com.petsitter.domain.repository.order.OrderLogRepository",
            "com.petsitter.domain.repository.UserActionLogRepository",
            "com.petsitter.domain.repository.CareLogRepository",
            "com.petsitter.domain.repository.PetEditLogRepository",
            "com.petsitter.domain.repository.ReferralLogRepository");

    @Test
    @DisplayName("§4.3 稽核 Repository：非 CrudRepository、繼承 AppendOnlyRepository、無 save* / delete*")
    void auditRepositoriesAreAppendOnly() throws Exception {
        for (String name : AUDIT_REPOSITORIES) {
            Class<?> repo = Class.forName(name);
            assertThat(CrudRepository.class.isAssignableFrom(repo)).as(name).isFalse();
            assertThat(AppendOnlyRepository.class.isAssignableFrom(repo)).as(name).isTrue();
            for (Method m : repo.getMethods()) {
                assertThat(m.getName()).as(name).doesNotStartWith("save").doesNotStartWith("delete");
            }
        }
    }

    @Test
    @DisplayName("§4.3 domain.model 內所有 *Log 實體都有對應的 append-only Repository（新增稽核實體時須列入）")
    void everyLogEntityCovered() {
        List<String> logEntities = MAIN.stream()
                .filter(c -> c.getPackageName().startsWith("com.petsitter.domain.model"))
                .filter(c -> c.getSimpleName().endsWith("Log"))
                .filter(c -> c.isAnnotatedWith(jakarta.persistence.Entity.class))
                .map(JavaClass::getSimpleName).toList();
        assertThat(logEntities).isNotEmpty();
        for (String entity : logEntities) {
            assertThat(AUDIT_REPOSITORIES).anyMatch(r -> r.endsWith("." + entity + "Repository"));
        }
    }
}
