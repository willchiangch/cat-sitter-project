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

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class BlacklistSmokeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void blacklistCrudSmokeTest() throws Exception {
        // 1. Register sitter
        String sitterEmail = "bl_sitter_" + UUID.randomUUID() + "@test.com";
        RegisterRequest sitterReg = new RegisterRequest(sitterEmail, "password123", RoleType.SITTER, "Bl Sitter");
        String sitterBody = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sitterReg)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sitterToken = objectMapper.readValue(sitterBody, AuthTokenResponse.class).accessToken();

        // 2. Register client
        String clientEmail = "bl_client_" + UUID.randomUUID() + "@test.com";
        RegisterRequest clientReg = new RegisterRequest(clientEmail, "password123", RoleType.CLIENT, "Bl Client");
        String clientBody = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(clientReg)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 3. Get client's profile ID from /auth/me
        String clientToken = objectMapper.readValue(clientBody, AuthTokenResponse.class).accessToken();
        String clientMeBody = mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + clientToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Extract CLIENT profileId from the profiles array
        com.fasterxml.jackson.databind.JsonNode clientMeNode = objectMapper.readTree(clientMeBody);
        String clientProfileId = clientMeNode.get("profiles").get(0).get("profileId").asText();

        // 4. GET blacklist → empty
        mockMvc.perform(get("/api/v1/sitters/me/blacklist")
                .header("Authorization", "Bearer " + sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        // 5. POST to add client to blacklist
        mockMvc.perform(post("/api/v1/sitters/me/blacklist/clients")
                .header("Authorization", "Bearer " + sitterToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("clientId", clientProfileId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientProfile.id").value(clientProfileId));

        // 6. GET blacklist → 1 entry
        mockMvc.perform(get("/api/v1/sitters/me/blacklist")
                .header("Authorization", "Bearer " + sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 7. DELETE from blacklist
        mockMvc.perform(delete("/api/v1/sitters/me/blacklist/clients/" + clientProfileId)
                .header("Authorization", "Bearer " + sitterToken))
                .andExpect(status().isNoContent());

        // 8. GET blacklist → empty again
        mockMvc.perform(get("/api/v1/sitters/me/blacklist")
                .header("Authorization", "Bearer " + sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
