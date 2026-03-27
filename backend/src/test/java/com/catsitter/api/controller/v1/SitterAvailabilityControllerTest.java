package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.dto.sitter.UpdateAvailabilityRequest;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SitterAvailabilityControllerTest {

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

    @BeforeEach
    void setUp() throws Exception {
        profileRepository.deleteAll();
        accountRepository.deleteAll();

        // Create a Sitter Account
        Account sitterAccount = new Account();
        sitterAccount.setEmail("sitter@example.com");
        sitterAccount.setPasswordHash(passwordEncoder.encode("password123"));
        sitterAccount.setOauthProvider(OAuthProvider.LOCAL);
        sitterAccount.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(sitterAccount);

        Profile sitterProfile = new Profile();
        sitterProfile.setAccount(sitterAccount);
        sitterProfile.setRoleType(RoleType.SITTER);
        sitterProfile.setName("Test Sitter");
        profileRepository.save(sitterProfile);

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

    @Test
    void shouldUpdateAndGetAvailability() throws Exception {
        var request = new UpdateAvailabilityRequest(
                LocalDate.now(),
                LocalDate.now().plusMonths(3),
                Map.of(DayOfWeek.MONDAY, List.of("09:00-12:00")),
                List.of(LocalDate.now().plusDays(10))
        );

        mockMvc.perform(put("/api/v1/sitters/me/availability")
                        .header("Authorization", sitterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyAvailability.MONDAY[0]").value("09:00-12:00"));

        mockMvc.perform(get("/api/v1/sitters/me/availability")
                        .header("Authorization", sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyAvailability.MONDAY[0]").value("09:00-12:00"));
    }

    @Test
    void shouldGetPublicAvailability() throws Exception {
        Profile sitterProfile = profileRepository.findByAccountIdAndRoleType(
                accountRepository.findByEmail("sitter@example.com").get().getId(),
                RoleType.SITTER
        ).get();
        sitterProfile.setSlug("test-sitter");
        sitterProfile.setWeeklyAvailability(Map.of(DayOfWeek.WEDNESDAY, List.of("10:00-14:00")));
        profileRepository.save(sitterProfile);

        mockMvc.perform(get("/api/v1/sitters/test-sitter/availability/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyAvailability.WEDNESDAY[0]").value("10:00-14:00"));
    }
}
