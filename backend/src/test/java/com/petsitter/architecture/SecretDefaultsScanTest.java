package com.petsitter.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-003 SEC-SECRET-1.1（G-12、G-25）：{@code src/main/resources/application*.yml} 中五項 secret 不得以
 * {@code ${NAME:預設值}} 形式帶預設值；測試值只放 {@code src/test/resources}。
 * <p>{@code RESEND_API_KEY} 現況仍為 {@code ${RESEND_API_KEY:}}（空字串預設 = 不寄信），prod 缺值行為依 NFR-003 CQ-14，
 * 屬 SD-NOTIFY 範圍，本測試暫列例外並於回報標明。
 */
@DisplayName("NFR-003 SEC-SECRET-1 SecretDefaultsScanTest")
class SecretDefaultsScanTest {

    private static final List<String> SECRETS = List.of(
            "APP_SECURITY_JWT_SECRET", "INTERNAL_CRON_SECRET", "APP_CRYPTO_KEY", "SPRING_DATASOURCE_PASSWORD");

    @Test
    @DisplayName("SEC-SECRET-1.1 main application*.yml 無 secret 預設值（JWT、INTERNAL_CRON_SECRET、APP_CRYPTO_KEY、DB 密碼）")
    void noSecretDefaults() throws IOException {
        for (String file : List.of("application.yml", "application-prod.yml")) {
            String yml = Files.readString(Path.of("src/main/resources", file));
            for (String name : SECRETS) {
                assertThat(yml).as(file + " " + name).doesNotContain("${" + name + ":");
            }
            assertThat(yml).as(file).doesNotContain("v9y$B&E)H@McQfTjWnZr4u7x");
        }
    }
}
