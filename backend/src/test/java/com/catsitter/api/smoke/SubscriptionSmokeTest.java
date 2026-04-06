package com.catsitter.api.smoke;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.RegisterRequest;
import com.catsitter.api.entity.SubscriptionPlan;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.SubscriptionPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class SubscriptionSmokeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SubscriptionPlanRepository planRepository;

    @BeforeEach
    void seedPlans() {
        if (planRepository.findByPlanCode("PRO").isEmpty()) {
            for (String[] p : new String[][]{
                {"FREE", "免費版", "0", "0", "3"},
                {"STANDARD", "基礎版", "499", "4990", "20"},
                {"PRO", "專業版", "899", "8990", "999"},
                {"PREMIUM", "頂級版", "1299", "12990", "999"}
            }) {
                SubscriptionPlan plan = new SubscriptionPlan();
                plan.setPlanCode(p[0]);
                plan.setName(p[1]);
                plan.setMonthlyPrice(new BigDecimal(p[2]));
                plan.setYearlyPrice(new BigDecimal(p[3]));
                plan.setOrderLimit(Integer.parseInt(p[4]));
                plan.setIsActive(true);
                planRepository.save(plan);
            }
        }
    }

    @Test
    void subscriptionManagementSmokeTest() throws Exception {
        // 1. Register a sitter
        String email = "sub_smoke_" + UUID.randomUUID() + "@test.com";
        RegisterRequest reg = new RegisterRequest(email, "password123", RoleType.SITTER, "Sub Sitter");
        String regBody = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        AuthTokenResponse auth = objectMapper.readValue(regBody, AuthTokenResponse.class);
        String token = auth.accessToken();

        // 2. GET current subscription → should return FREE plan (no sub seeded)
        mockMvc.perform(get("/api/v1/sitters/me/subscription")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value("FREE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 3. PUT to change plan to PRO
        mockMvc.perform(put("/api/v1/sitters/me/subscription")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("planId", "PRO"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value("PRO"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 4. DELETE (cancel) subscription
        mockMvc.perform(delete("/api/v1/sitters/me/subscription")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 5. GET again → CANCELLED
        mockMvc.perform(get("/api/v1/sitters/me/subscription")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
