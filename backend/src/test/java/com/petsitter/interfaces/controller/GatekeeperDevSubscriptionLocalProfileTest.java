package com.petsitter.interfaces.controller;

import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 對照組（fix-batch10 H1）：非正式環境（local）仍保留自助切換端點，供本機開發 / demo 使用 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("H1: local profile 保留開發用自助切換端點")
class GatekeeperDevSubscriptionLocalProfileTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Test
    @DisplayName("local profile：保母打 /subscription/mock → 200 並寫入訂閱")
    void should_UpdateSubscription_When_LocalProfile() throws Exception {
        User sitter = userRepository.save(User.builder()
                .email("h1-local-sitter-" + System.nanoTime() + "@test.com")
                .passwordHash("hash").fullName("H1 本機保母").role("SITTER").build());
        String token = jwtUtils.generateToken(sitter.getEmail(), "SITTER",
                Map.<String, Object>of("userId", sitter.getId().toString()), 3_600_000L);

        mockMvc.perform(post("/api/sitter/gatekeeper/subscription/mock")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"PRO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planTier").value("PRO"));

        assertThat(subscriptionRepository.findBySitterId(sitter.getId()))
                .get().extracting("planTier").isEqualTo("PRO");
    }
}
