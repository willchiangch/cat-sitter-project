package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.dto.auth.RegisterRequest;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private ProfileRepository profileRepository;

  @Autowired
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    profileRepository.deleteAll();
    accountRepository.deleteAll();
  }

  @Test
  void shouldRegisterNewUser() throws Exception {
    RegisterRequest request = new RegisterRequest(
            "test@example.com",
            "password123",
            RoleType.SITTER,
            "Test Sitter"
    );

    mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists());
  }

  @Test
  void shouldLoginExistingUser() throws Exception {
    // Register first
    RegisterRequest regRequest = new RegisterRequest(
            "login@example.com",
            "password123",
            RoleType.CLIENT,
            "Test Client"
    );
    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(regRequest)));

    LoginRequest loginRequest = new LoginRequest("login@example.com", "password123");

    mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(loginRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists());
  }

  @Test
  void shouldReturnMeWithProfiles() throws Exception {
    // 1. Register
    RegisterRequest regRequest = new RegisterRequest(
            "me@example.com",
            "password123",
            RoleType.SITTER,
            "Me User"
    );
    String regResp = mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(regRequest)))
            .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

    com.catsitter.api.dto.auth.AuthTokenResponse token = objectMapper.readValue(regResp, com.catsitter.api.dto.auth.AuthTokenResponse.class);

    // 2. Call /me
    mockMvc.perform(get("/api/v1/auth/me")
                    .header("Authorization", "Bearer " + token.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("me@example.com"))
            .andExpect(jsonPath("$.profiles").isArray())
            .andExpect(jsonPath("$.profiles[0].role").value("SITTER"))
            .andExpect(jsonPath("$.profiles[0].name").value("Me User"));
  }

  @Test
  void shouldSwitchRole() throws Exception {
    // 1. Register
    RegisterRequest regRequest = new RegisterRequest(
            "switch@example.com",
            "password123",
            RoleType.SITTER,
            "Switch User"
    );
    String regResp = mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(regRequest)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

    com.catsitter.api.dto.auth.AuthTokenResponse token = objectMapper.readValue(regResp, com.catsitter.api.dto.auth.AuthTokenResponse.class);

    // 2. Switch role to CLIENT
    com.catsitter.api.dto.auth.SwitchRoleRequest switchReq = new com.catsitter.api.dto.auth.SwitchRoleRequest(RoleType.CLIENT);
    mockMvc.perform(post("/api/v1/auth/switch-role")
                    .header("Authorization", "Bearer " + token.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(switchReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentRole").value("CLIENT"));

    // 3. Verify in /me
    mockMvc.perform(get("/api/v1/auth/me")
                    .header("Authorization", "Bearer " + token.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentRole").value("CLIENT"));
  }

  @Test
  void shouldReturn401WhenAccessingMeWithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized());
  }
}
