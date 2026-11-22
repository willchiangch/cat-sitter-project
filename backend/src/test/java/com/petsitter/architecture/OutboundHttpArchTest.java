package com.petsitter.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * SD-GLOBAL-SPEC §3.6 {@code OutboundHttpArchTest}（NFR-003 SEC-INPUT-8、SSRF）：{@code RestClient}、{@code WebClient}、
 * {@code RestTemplate}、{@code java.net.http.HttpClient}、{@code URL.openConnection} 只允許列冊類別使用。
 * 現有列冊：{@code EmailService}（Resend）。GCS SDK、Google ID token verifier 為程式庫內部連線，不在掃描範圍。
 */
@DisplayName("NFR-003 SEC-INPUT-8 OutboundHttpArchTest")
class OutboundHttpArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.petsitter");

    private static final String[] ALLOWED = {"com.petsitter.application.service.EmailService"};

    @Test
    @DisplayName("§3.6 HTTP client 類別只允許列冊類別依賴")
    void httpClientsOnlyInAllowList() {
        noClasses().that().doNotHaveFullyQualifiedName(ALLOWED[0])
                .should().dependOnClassesThat().haveFullyQualifiedName("java.net.http.HttpClient")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.client.RestClient")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.reactive.function.client.WebClient")
                .check(MAIN);
    }

    @Test
    @DisplayName("§3.6 不得呼叫 URL.openConnection / URL.openStream")
    void noUrlOpenConnection() {
        noClasses().that().doNotHaveFullyQualifiedName(ALLOWED[0])
                .should().callMethod(java.net.URL.class, "openConnection")
                .orShould().callMethod(java.net.URL.class, "openStream")
                .check(MAIN);
    }
}
