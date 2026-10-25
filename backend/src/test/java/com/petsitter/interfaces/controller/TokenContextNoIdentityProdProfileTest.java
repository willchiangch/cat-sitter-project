package com.petsitter.interfaces.controller;

import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * fix-batch12 J2：TokenContext.getUserId() 沒有身分時過去回全 0 的 dummy UUID，
 * 讓 permitAll 路徑（/api/auth/**）上需要身分的端點、以及 JWT 缺 userId claim 的請求
 * 靜默以 0000 身分執行。現在一律 401。用真的 prod profile 起 context（正式站設定），請求走完整 filter chain。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("prod")
@DisplayName("J2: prod profile 下沒有身分的請求 → 401，不再以 dummy UUID 執行")
class TokenContextNoIdentityProdProfileTest {

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
        registry.add("app.security.jwt-secret",
                () -> "v9y$B&E)H@McQfTjWnZr4u7x!A%C*F-JaNdRgUkXp2s5v8y/B?E(G+KbPeShVmYq");
    }

    // prod profile 會註冊 GcsMediaStorageServiceImpl（建構時連 GCP），以 mock 取代
    @MockitoBean
    private MediaStorageService mediaStorageService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("匿名打 /api/auth/logout-all-devices（permitAll、無 @PreAuthorize）→ 401")
    void anonymous_logoutAllDevices_401() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all-devices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("匿名打 /api/auth/deactivate → 401")
    void anonymous_deactivate_401() throws Exception {
        mockMvc.perform(post("/api/auth/deactivate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("匿名打 /api/auth/switch-role → 401")
    void anonymous_switchRole_401() throws Exception {
        mockMvc.perform(post("/api/auth/switch-role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRole\":\"OWNER\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("簽章有效但缺 userId claim 的 JWT 打需要身分的端點 → 401，不是以 0000 查到空清單 200")
    void jwtWithoutUserId_401() throws Exception {
        User sitter = userRepository.save(User.builder()
                .email("j2-sitter-" + System.nanoTime() + "@test.com")
                .passwordHash("hash").fullName("J2 保母").role("SITTER").build());
        String token = jwtUtils.generateToken(sitter.getEmail(), "SITTER", Map.of(), 3_600_000L);

        mockMvc.perform(get("/api/care-notes/templates").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
