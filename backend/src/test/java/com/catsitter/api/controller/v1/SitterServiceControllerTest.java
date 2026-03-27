package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.dto.sitter.CreateServiceRequest;
import com.catsitter.api.dto.sitter.UpdateServiceRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.AccountStatus;
import com.catsitter.api.entity.enums.OAuthProvider;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.AccountRepository;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.ServiceRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SitterServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String sitterToken;
    private Profile sitterProfile;

    @BeforeEach
    void setUp() throws Exception {
        serviceRepository.deleteAll();
        profileRepository.deleteAll();
        accountRepository.deleteAll();

        // Create a Sitter Account
        Account sitterAccount = new Account();
        sitterAccount.setEmail("sitter@example.com");
        sitterAccount.setPasswordHash(passwordEncoder.encode("password123"));
        sitterAccount.setOauthProvider(OAuthProvider.LOCAL);
        sitterAccount.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(sitterAccount);

        sitterProfile = new Profile();
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
    void shouldCreateService() throws Exception {
        var request = new CreateServiceRequest(
                "Cat Sitting 30m",
                new BigDecimal("500.00"),
                30,
                List.of("CAT")
        );

        mockMvc.perform(post("/api/v1/sitters/me/services")
                        .header("Authorization", sitterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Cat Sitting 30m"))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void shouldListSitterServices() throws Exception {
        com.catsitter.api.entity.Service service = new com.catsitter.api.entity.Service();
        service.setSitterProfile(sitterProfile);
        service.setName("Existing Service");
        service.setBasePrice(new BigDecimal("600.00"));
        service.setDurationMinutes(60);
        service.setSupportedPetTypes(List.of("DOG"));
        serviceRepository.save(service);

        mockMvc.perform(get("/api/v1/sitters/me/services")
                        .header("Authorization", sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Existing Service"));
    }

    @Test
    void shouldUpdateService() throws Exception {
        com.catsitter.api.entity.Service service = new com.catsitter.api.entity.Service();
        service.setSitterProfile(sitterProfile);
        service.setName("Old Name");
        service.setBasePrice(new BigDecimal("100.00"));
        service.setDurationMinutes(15);
        service.setSupportedPetTypes(List.of("CAT"));
        service = serviceRepository.save(service);

        var updateRequest = new UpdateServiceRequest(
                "New Name",
                new BigDecimal("200.00"),
                30,
                List.of("CAT", "DOG"),
                false
        );

        mockMvc.perform(put("/api/v1/sitters/me/services/" + service.getId())
                        .header("Authorization", sitterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    void shouldDeleteService() throws Exception {
        com.catsitter.api.entity.Service service = new com.catsitter.api.entity.Service();
        service.setSitterProfile(sitterProfile);
        service.setName("To Delete");
        service.setBasePrice(new BigDecimal("100.00"));
        service.setDurationMinutes(15);
        service.setSupportedPetTypes(List.of("CAT"));
        service = serviceRepository.save(service);

        mockMvc.perform(delete("/api/v1/sitters/me/services/" + service.getId())
                        .header("Authorization", sitterToken))
                .andExpect(status().isNoContent());

        assert(!serviceRepository.existsById(service.getId()));
    }
}
