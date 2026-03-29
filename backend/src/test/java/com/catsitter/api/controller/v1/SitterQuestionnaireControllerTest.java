package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.dto.sitter.CreateQuestionRequest;
import com.catsitter.api.dto.sitter.UpdateQuestionRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.SitterQuestion;
import com.catsitter.api.entity.enums.AccountStatus;
import com.catsitter.api.entity.enums.OAuthProvider;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.entity.enums.QuestionType;
import com.catsitter.api.entity.enums.TargetPetType;
import java.util.List;
import java.util.UUID;
import com.catsitter.api.repository.AccountRepository;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.SitterQuestionRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SitterQuestionnaireControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private SitterQuestionRepository questionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String sitterToken;
    private Profile sitterProfile;

    @BeforeEach
    void setUp() throws Exception {
        questionRepository.deleteAll();
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
    void shouldCreateQuestion() throws Exception {
        var request = new CreateQuestionRequest(
                TargetPetType.CAT,
                "How many cats do you have?",
                QuestionType.TEXT,
                true,
                List.of(),
                1
        );

        mockMvc.perform(post("/api/v1/sitters/me/questionnaires")
                        .header("Authorization", sitterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questionText").value("How many cats do you have?"))
                .andExpect(jsonPath("$.targetPetType").value("CAT"));
    }

    @Test
    void shouldListQuestions() throws Exception {
        SitterQuestion q = new SitterQuestion();
        q.setSitterProfile(sitterProfile);
        q.setTargetPetType(TargetPetType.ALL);
        q.setQuestionText("Test Question");
        q.setSortOrder(0);
        questionRepository.save(q);

        mockMvc.perform(get("/api/v1/sitters/me/questionnaires")
                        .header("Authorization", sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].questionText").value("Test Question"));
    }

    @Test
    void shouldUpdateQuestion() throws Exception {
        SitterQuestion q = new SitterQuestion();
        q.setSitterProfile(sitterProfile);
        q.setTargetPetType(TargetPetType.CAT);
        q.setQuestionText("Old Question");
        q.setSortOrder(1);
        q = questionRepository.save(q);

        var request = new UpdateQuestionRequest(
                TargetPetType.DOG,
                "New Question",
                QuestionType.TEXT,
                true,
                List.of(),
                2,
                false
        );

        mockMvc.perform(put("/api/v1/sitters/me/questionnaires/" + q.getId())
                        .header("Authorization", sitterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionText").value("New Question"))
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    void shouldReorderQuestions() throws Exception {
        SitterQuestion q1 = new SitterQuestion();
        q1.setSitterProfile(sitterProfile);
        q1.setTargetPetType(TargetPetType.CAT);
        q1.setQuestionText("Q1");
        q1.setSortOrder(1);
        q1 = questionRepository.save(q1);

        SitterQuestion q2 = new SitterQuestion();
        q2.setSitterProfile(sitterProfile);
        q2.setTargetPetType(TargetPetType.CAT);
        q2.setQuestionText("Q2");
        q2.setSortOrder(2);
        q2 = questionRepository.save(q2);

        var request = new com.catsitter.api.dto.sitter.ReorderQuestionRequest(
                java.util.List.of(q2.getId(), q1.getId())
        );

        mockMvc.perform(patch("/api/v1/sitters/me/questionnaires/reorder")
                        .header("Authorization", sitterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        SitterQuestion updatedQ1 = questionRepository.findById(q1.getId()).get();
        SitterQuestion updatedQ2 = questionRepository.findById(q2.getId()).get();

        assert(updatedQ1.getSortOrder() == 1);
        assert(updatedQ2.getSortOrder() == 0);
    }
}
