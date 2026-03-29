package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.AccountStatus;
import com.catsitter.api.entity.enums.OAuthProvider;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.AccountRepository;
import com.catsitter.api.repository.ProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SitterProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String sitterToken;
    private Account sitterAccount;

    @BeforeEach
    void setUp() throws Exception {
        profileRepository.deleteAll();
        accountRepository.deleteAll();

        // Create a Sitter Account
        sitterAccount = new Account();
        sitterAccount.setEmail("sitter@example.com");
        sitterAccount.setPasswordHash(passwordEncoder.encode("password123"));
        sitterAccount.setOauthProvider(OAuthProvider.LOCAL);
        sitterAccount.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(sitterAccount);

        Profile profile = new Profile();
        profile.setAccount(sitterAccount);
        profile.setRoleType(RoleType.SITTER);
        profile.setName("Test Sitter");
        profileRepository.save(profile);

        // Login to get token
        LoginRequest loginRequest = new LoginRequest("sitter@example.com", "password123");
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        AuthTokenResponse tokenResponse = objectMapper.readValue(response, AuthTokenResponse.class);
        sitterToken = "Bearer " + tokenResponse.accessToken();
    }
    
    // Helper to avoid ambiguous post import
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
    }

    @Test
    void shouldGetOwnSitterProfile() throws Exception {
        mockMvc.perform(get("/api/v1/sitters/me/profile")
                        .header("Authorization", sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Sitter"));
    }

    @Test
    void shouldUpdateSitterProfile() throws Exception {
        var updateRequest = new com.catsitter.api.dto.sitter.UpdateSitterProfileRequest(
                "Updated Sitter Name",
                "http://example.com/avatar.jpg",
                "0912345678",
                List.of("Area 1", "Area 2"),
                "New Bio Summary",
                List.of(), // professionalLabels
                null,      // bankCode
                null,      // bankAccount
                null       // bankAccountHolder
        );

        mockMvc.perform(put("/api/v1/sitters/me/profile")
                        .header("Authorization", sitterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Sitter Name"))
                .andExpect(jsonPath("$.bioSummary").value("New Bio Summary"));
    }
}
