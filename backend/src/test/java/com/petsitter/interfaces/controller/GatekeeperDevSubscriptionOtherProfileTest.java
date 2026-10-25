package com.petsitter.interfaces.controller;

import com.petsitter.application.service.MediaStorageService;
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
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * fix-batch12 J4：開發用自助升級端點改白名單 @Profile("local")。
 * 過去 @Profile("!prod")：部署時 profile 設成 prod 以外的任何值（staging / uat / 拼錯）就會註冊，
 * 任何保母都能把自己升到 ULTIMATE。這裡以一個非 local、非 prod 的 profile 起 context。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("staging")
@DisplayName("J4: 非 local profile（staging）不註冊開發用自助升級端點")
class GatekeeperDevSubscriptionOtherProfileTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // 非 local profile 會註冊 GcsMediaStorageServiceImpl（建構時連 GCP），以 mock 取代
    @MockitoBean
    private MediaStorageService mediaStorageService;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Test
    @DisplayName("staging profile：bean 不存在，保母打 /subscription/mock → 404，訂閱不變")
    void should_Return404_When_NonLocalProfile() throws Exception {
        assertThat(context.getBeanNamesForType(GatekeeperDevSubscriptionController.class)).isEmpty();

        User sitter = userRepository.save(User.builder()
                .email("j4-sitter-" + System.nanoTime() + "@test.com")
                .passwordHash("hash").fullName("J4 保母").role("SITTER").build());
        String token = jwtUtils.generateToken(sitter.getEmail(), "SITTER",
                Map.<String, Object>of("userId", sitter.getId().toString()), 3_600_000L);

        mockMvc.perform(post("/api/sitter/gatekeeper/subscription/mock")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"ULTIMATE\"}"))
                .andExpect(status().isNotFound());

        assertThat(subscriptionRepository.findBySitterId(sitter.getId())).isEmpty();
    }
}
