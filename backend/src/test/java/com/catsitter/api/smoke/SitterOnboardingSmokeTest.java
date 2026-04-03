package com.catsitter.api.smoke;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.RegisterRequest;
import com.catsitter.api.entity.enums.RoleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class SitterOnboardingSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sitterOnboardingSmokeTest() throws Exception {
        // 1. Register Sitter
        var sitterReq = new RegisterRequest("onboard_sitter@example.com", "password123", RoleType.SITTER, "Onboard Sitter");
        String sitterResp = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sitterReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        AuthTokenResponse sitterToken = objectMapper.readValue(sitterResp, AuthTokenResponse.class);

        // 2. Update Profile (Onboarding)
        var profileReq = new com.catsitter.api.dto.sitter.UpdateSitterProfileRequest(
                "Onboard Sitter UPDATED", null, "0912345678", List.of("新莊區", "板橋區"), "A cat lover",
                List.of(), null, null, null, null, null
        );
        mockMvc.perform(put("/api/v1/sitters/me/profile")
                .header("Authorization", "Bearer " + sitterToken.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(profileReq)))
                .andExpect(status().isOk());
    }
}
