package com.petsitter.interfaces.controller;

import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迴歸（fix-batch10 H1）：正式站（SPRING_PROFILES_ACTIVE=prod，見 deploy.yml）不得有保母自助升級端點，
 * 開通只能走管理員端點。用真的 prod profile 起 context（application-prod.yml 的連線參數以容器覆蓋），
 * 請求帶真 JWT 走完整 filter chain。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("prod")
@DisplayName("H1: prod profile 下訂閱開通只剩管理員端點")
class GatekeeperSubscriptionProdProfileTest {

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
    private ApplicationContext context;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    private User sitter;
    private User admin;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        sitter = userRepository.save(User.builder()
                .email("h1-sitter-" + System.nanoTime() + "@test.com")
                .passwordHash("hash").fullName("H1 保母").role("SITTER").build());
        admin = userRepository.save(User.builder()
                .email("h1-admin-" + System.nanoTime() + "@test.com")
                .passwordHash("hash").fullName("H1 管理員").role("ADMIN").build());
    }

    private String bearer(User u) {
        return "Bearer " + jwtUtils.generateToken(u.getEmail(), u.getRole(),
                Map.<String, Object>of("userId", u.getId().toString()), 3_600_000L);
    }

    @Test
    @DisplayName("prod profile 不註冊 GatekeeperDevSubscriptionController")
    void should_NotRegisterDevSubscriptionController_When_ProdProfile() {
        assertThat(context.getBeanNamesForType(GatekeeperDevSubscriptionController.class)).isEmpty();
    }

    @Test
    @DisplayName("保母打 /subscription/mock → 404，訂閱不變")
    void should_Return404_And_NotCreateSubscription_When_SitterCallsMockEndpointInProd() throws Exception {
        mockMvc.perform(post("/api/sitter/gatekeeper/subscription/mock")
                        .header("Authorization", bearer(sitter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"ULTIMATE\"}"))
                .andExpect(status().isNotFound());

        assertThat(subscriptionRepository.findBySitterId(sitter.getId())).isEmpty();
    }

    @Test
    @DisplayName("保母打管理員開通端點 → 403，訂閱不變")
    void should_Return403_When_SitterCallsAdminSubscriptionEndpoint() throws Exception {
        mockMvc.perform(post("/api/admin/subscriptions/{id}", sitter.getId())
                        .header("Authorization", bearer(sitter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"PRO\"}"))
                .andExpect(status().isForbidden());

        assertThat(subscriptionRepository.findBySitterId(sitter.getId())).isEmpty();
    }

    @Test
    @DisplayName("飼主打管理員開通端點 → 403")
    void should_Return403_When_OwnerCallsAdminSubscriptionEndpoint() throws Exception {
        User owner = userRepository.save(User.builder()
                .email("h1-owner-" + System.nanoTime() + "@test.com")
                .passwordHash("hash").fullName("H1 飼主").role("OWNER").build());

        mockMvc.perform(post("/api/admin/subscriptions/{id}", sitter.getId())
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"PRO\"}"))
                .andExpect(status().isForbidden());

        assertThat(subscriptionRepository.findBySitterId(sitter.getId())).isEmpty();
    }

    @Test
    @DisplayName("未登入打管理員開通端點 → 401")
    void should_Return401_When_AnonymousCallsAdminSubscriptionEndpoint() throws Exception {
        mockMvc.perform(post("/api/admin/subscriptions/{id}", sitter.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"PRO\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("管理員開通 PRO → 200，訂閱寫入")
    void should_SetPlanTier_When_AdminCallsAdminSubscriptionEndpoint() throws Exception {
        mockMvc.perform(post("/api/admin/subscriptions/{id}", sitter.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"PRO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planTier").value("PRO"));

        assertThat(subscriptionRepository.findBySitterId(sitter.getId()))
                .get().extracting("planTier").isEqualTo("PRO");
    }
}
