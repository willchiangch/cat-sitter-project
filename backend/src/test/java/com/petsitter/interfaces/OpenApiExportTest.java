package com.petsitter.interfaces;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重新產生 docs/api/openapi.json（springdoc /v3/api-docs 快照）。
 * 平常 mvn test 不會跑，需要更新 API 文件時手動執行：
 *   mvn -q test -Dtest=OpenApiExportTest -Dopenapi.export=true
 * 之後再用 Redocly 產生 docs/api/index.html（見 docs/api/README.md）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "openapi.export", matches = "true")
class OpenApiExportTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportOpenApiSpec() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"openapi\"");

        Path out = Path.of(System.getProperty("openapi.output", "../docs/api/openapi.json"));
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }
}
