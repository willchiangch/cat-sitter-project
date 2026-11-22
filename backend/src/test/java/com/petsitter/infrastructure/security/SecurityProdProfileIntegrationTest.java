package com.petsitter.infrastructure.security;

import com.petsitter.application.service.MediaStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * SD-GLOBAL-SPEC §6.2 / §3.4.1（NFR-003 SEC-LOG-3.2、U-3、G-20）：prod profile 帶有效 token
 * {@code GET /v3/api-docs}、{@code GET /swagger-ui.html} → 404 {@code NOT_FOUND}；匿名 → 401；
 * local 專用的 {@code PUT /local-storage/**} 在 prod 不放行；prod 回應同樣帶 API CSP。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("prod")
@DisplayName("NFR-003 SEC-LOG-3 SecurityProdProfileIntegrationTest")
class SecurityProdProfileIntegrationTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void prodOverrides(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("app.security.cors.allowed-origins", () -> "https://prod-test.web.app");
        registry.add("app.security.jwt-secret", () -> "prod-profile-test-only-jwt-key-0123456789abcdef0123456789abcdef0123");
    }

    @MockitoBean
    private MediaStorageService mediaStorageService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    private String token() {
        UUID id = UUID.randomUUID();
        return "Bearer " + jwtUtils.generateToken(id + "@t.test", "OWNER", Map.<String, Object>of("userId", id.toString()), 60_000L);
    }

    @Test
    @DisplayName("§6.2 prod：帶有效 token GET /v3/api-docs、/swagger-ui.html → 404 NOT_FOUND；匿名 → 401")
    void swaggerDisabled() throws Exception {
        for (String url : new String[]{"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            MvcResult withToken = mockMvc.perform(get(url).header("Authorization", token())).andReturn();
            assertThat(withToken.getResponse().getStatus()).as(url).isEqualTo(404);
            assertThat(withToken.getResponse().getContentAsString()).contains("\"NOT_FOUND\"");
            assertThat(withToken.getResponse().getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
            assertThat(mockMvc.perform(get(url)).andReturn().getResponse().getStatus()).as(url).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("§3.4.1 prod：PUT /local-storage/** 不在白名單 → 匿名 401")
    void localStorageNotPermitted() throws Exception {
        assertThat(mockMvc.perform(put("/local-storage/x")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
