package com.catsitter.api.controller.v1;

import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.Service;
import com.catsitter.api.entity.SitterQuestion;
import com.catsitter.api.entity.enums.AccountStatus;
import com.catsitter.api.entity.enums.OAuthProvider;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.entity.enums.TargetPetType;
import com.catsitter.api.repository.AccountRepository;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.ServiceRepository;
import com.catsitter.api.repository.SitterQuestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BookingPreviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private SitterQuestionRepository questionRepository;

    @BeforeEach
    void setUp() {
        questionRepository.deleteAll();
        serviceRepository.deleteAll();
        profileRepository.deleteAll();
        accountRepository.deleteAll();

        Account account = new Account();
        account.setEmail("sitter@example.com");
        account.setOauthProvider(OAuthProvider.LOCAL);
        account.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);

        Profile profile = new Profile();
        profile.setAccount(account);
        profile.setRoleType(RoleType.SITTER);
        profile.setName("Sitter One");
        profile.setSlug("sitter-one");
        profileRepository.saveAndFlush(profile);

        Service s = new Service();
        s.setSitterProfile(profile);
        s.setName("Cat Caring");
        s.setBasePrice(new BigDecimal("500"));
        s.setDurationMinutes(60);
        s.setSupportedPetTypes(List.of(TargetPetType.CAT.name()));
        s.setIsActive(true);
        serviceRepository.save(s);

        SitterQuestion q = new SitterQuestion();
        q.setSitterProfile(profile);
        q.setTargetPetType(TargetPetType.CAT);
        q.setQuestionText("How many cats?");
        q.setIsActive(true);
        questionRepository.save(q);
    }

    @Test
    void shouldGetBookingPreview() throws Exception {
        mockMvc.perform(get("/api/v1/sitters/sitter-one/booking-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sitterProfile.name").value("Sitter One"))
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.questionnaire.length()").value(1));
    }
}
