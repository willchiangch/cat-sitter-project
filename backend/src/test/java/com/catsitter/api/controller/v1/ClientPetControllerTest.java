package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.dto.client.CreatePetRequest;
import com.catsitter.api.dto.client.UpdateClientProfileRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Pet;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.*;
import com.catsitter.api.repository.AccountRepository;
import com.catsitter.api.repository.PetRepository;
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

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ClientPetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PetRepository petRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String clientToken;
    private Profile clientProfile;

    @BeforeEach
    void setUp() throws Exception {
        petRepository.deleteAll();
        profileRepository.deleteAll();
        accountRepository.deleteAll();

        // Create a Client Account
        Account clientAccount = new Account();
        clientAccount.setEmail("client@example.com");
        clientAccount.setPasswordHash(passwordEncoder.encode("password123"));
        clientAccount.setOauthProvider(OAuthProvider.LOCAL);
        clientAccount.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(clientAccount);

        clientProfile = new Profile();
        clientProfile.setAccount(clientAccount);
        clientProfile.setRoleType(RoleType.CLIENT);
        clientProfile.setName("Test Client");
        profileRepository.save(clientProfile);

        // Login to get token
        LoginRequest loginRequest = new LoginRequest("client@example.com", "password123");
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        AuthTokenResponse tokenResponse = objectMapper.readValue(response, AuthTokenResponse.class);
        clientToken = "Bearer " + tokenResponse.accessToken();
    }

    @Test
    void shouldUpdateClientProfile() throws Exception {
        var request = new UpdateClientProfileRequest(
                "Updated Client",
                "http://avatar.com",
                "0987654321",
                "New Address"
        );

        mockMvc.perform(put("/api/v1/clients/me/profile")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Client"));
    }

    @Test
    void shouldCreatePet() throws Exception {
        var request = new CreatePetRequest(
                "Mimi",
                PetSpecies.CAT,
                PetGender.FEMALE,
                true,
                new BigDecimal("4.5"),
                null, null, null, null, null
        );

        mockMvc.perform(post("/api/v1/clients/me/pets")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Mimi"));
    }

    @Test
    void shouldListPets() throws Exception {
        Pet pet = new Pet();
        pet.setClientProfile(clientProfile);
        pet.setName("Lucky");
        pet.setSpecies(PetSpecies.DOG);
        petRepository.save(pet);

        mockMvc.perform(get("/api/v1/clients/me/pets")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Lucky"));
    }

    @Test
    void shouldGetIndividualPet() throws Exception {
        Pet pet = new Pet();
        pet.setClientProfile(clientProfile);
        pet.setName("Individual Pet");
        pet.setSpecies(PetSpecies.CAT);
        pet = petRepository.save(pet);

        mockMvc.perform(get("/api/v1/clients/me/pets/" + pet.getId())
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Individual Pet"));
    }

    @Test
    void shouldUpdatePet() throws Exception {
        Pet pet = new Pet();
        pet.setClientProfile(clientProfile);
        pet.setName("Old Name");
        pet.setSpecies(PetSpecies.CAT);
        pet = petRepository.save(pet);

        var request = new CreatePetRequest(
                "New Name",
                PetSpecies.CAT,
                PetGender.MALE,
                false,
                new BigDecimal("5.0"),
                null, null, null, null, null
        );

        mockMvc.perform(put("/api/v1/clients/me/pets/" + pet.getId())
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    void shouldDeletePet() throws Exception {
        Pet pet = new Pet();
        pet.setClientProfile(clientProfile);
        pet.setName("To Delete");
        pet.setSpecies(PetSpecies.CAT);
        pet = petRepository.save(pet);

        mockMvc.perform(delete("/api/v1/clients/me/pets/" + pet.getId())
                        .header("Authorization", clientToken))
                .andExpect(status().isNoContent());

        assert(!petRepository.existsById(pet.getId()));
    }
}
