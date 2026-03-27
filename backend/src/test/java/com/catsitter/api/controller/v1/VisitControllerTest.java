package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.dto.visit.UpdateChecklistRequest;
import com.catsitter.api.entity.*;
import com.catsitter.api.entity.enums.*;
import com.catsitter.api.repository.*;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VisitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private VisitServiceRepository visitServiceRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String sitterToken;
    private UUID visitId;
    private UUID checklistItemId;

    @BeforeEach
    void setUp() throws Exception {
        visitServiceRepository.deleteAll();
        visitRepository.deleteAll();
        orderRepository.deleteAll();
        serviceRepository.deleteAll();
        profileRepository.deleteAll();
        accountRepository.deleteAll();

        String sitterEmail = "sitter_" + UUID.randomUUID() + "@example.com";
        String clientEmail = "client_" + UUID.randomUUID() + "@example.com";

        // 1. Create Sitter
        Account sitterAcc = new Account();
        sitterAcc.setEmail(sitterEmail);
        sitterAcc.setPasswordHash(passwordEncoder.encode("password123"));
        sitterAcc.setOauthProvider(OAuthProvider.LOCAL);
        sitterAcc.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(sitterAcc);

        Profile sitterProf = new Profile();
        sitterProf.setAccount(sitterAcc);
        sitterProf.setRoleType(RoleType.SITTER);
        sitterProf.setName("Professional Sitter");
        profileRepository.save(sitterProf);

        // 2. Create Client
        Account clientAcc = new Account();
        clientAcc.setEmail(clientEmail);
        clientAcc.setOauthProvider(OAuthProvider.LOCAL);
        clientAcc.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(clientAcc);

        Profile clientProf = new Profile();
        clientProf.setAccount(clientAcc);
        clientProf.setRoleType(RoleType.CLIENT);
        clientProf.setName("Cat Owner");
        profileRepository.save(clientProf);

        // 3. Create Service
        Service s = new Service();
        s.setSitterProfile(sitterProf);
        s.setName("Premium Cat Caring");
        s.setBasePrice(new BigDecimal("600"));
        s.setDurationMinutes(60);
        s.setSupportedPetTypes(List.of("CAT"));
        s.setIsActive(true);
        serviceRepository.save(s);

        // 4. Create Order & Visit
        Order order = new Order();
        order.setClientProfile(clientProf);
        order.setCurrentSitter(sitterProf);
        order.setService(s);
        order.setServiceName(s.getName());
        order.setServiceUnitPrice(s.getBasePrice());
        order.setBaseAmount(s.getBasePrice());
        order.setSurchargeAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalAmount(s.getBasePrice());
        order.setOrderStatus(OrderStatus.CONFIRMED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setQuestionnaireStatus(QuestionnaireStatus.COMPLETED);
        orderRepository.save(order);

        Visit v = new Visit();
        v.setOrder(order);
        v.setVisitStartTime(OffsetDateTime.now().plusHours(1));
        v.setVisitEndTime(OffsetDateTime.now().plusHours(2));
        v.setStatus(VisitStatus.SCHEDULED);
        visitRepository.save(v);
        visitId = v.getId();

        VisitService vs = new VisitService();
        vs.setVisit(v);
        vs.setServiceType(ServiceType.FEEDING);
        vs.setDescription("Feeding the cats");
        vs.setIsCompleted(false);
        vs.setSortOrder(1);
        visitServiceRepository.save(vs);
        checklistItemId = vs.getId();

        // 5. Login Sitter
        LoginRequest loginRequest = new LoginRequest(sitterEmail, "password123");
        String loginJson = objectMapper.writeValueAsString(loginRequest);
        
        var mvcResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andReturn();
        
        if (mvcResult.getResponse().getStatus() != 200) {
            System.out.println("Login Failed: " + mvcResult.getResponse().getContentAsString());
            throw new RuntimeException("Login failed during setup");
        }

        AuthTokenResponse tokenResponse = objectMapper.readValue(mvcResult.getResponse().getContentAsString(), AuthTokenResponse.class);
        sitterToken = "Bearer " + tokenResponse.accessToken();
    }

    @Test
    void shouldListSitterVisits() throws Exception {
        String today = OffsetDateTime.now().toLocalDate().toString();
        mockMvc.perform(get("/api/v1/sitters/me/visits")
                        .header("Authorization", sitterToken)
                        .param("date", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(visitId.toString()));
    }

    @Test
    void shouldGetVisitDetail() throws Exception {
        mockMvc.perform(get("/api/v1/visits/" + visitId)
                        .header("Authorization", sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(visitId.toString()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Feeding the cats"));
    }

    @Test
    void shouldUpdateChecklistAndCompleteVisit() throws Exception {
        // Update checklist item
        UpdateChecklistRequest updateReq = new UpdateChecklistRequest(checklistItemId, true, "http://photo.url");
        mockMvc.perform(patch("/api/v1/visits/" + visitId + "/checklist")
                        .header("Authorization", sitterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].isCompleted").value(true));

        // Complete visit
        mockMvc.perform(post("/api/v1/visits/" + visitId + "/complete")
                        .header("Authorization", sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }
}
