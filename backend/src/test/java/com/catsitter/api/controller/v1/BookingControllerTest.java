package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.dto.booking.BookingResponse;
import com.catsitter.api.dto.booking.CreateBookingRequest;
import com.catsitter.api.dto.booking.SubmitQuoteRequest;
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
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BookingControllerTest {

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
    private SitterQuestionRepository questionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String clientToken;
    private String sitterToken;
    private UUID sitterId;
    private UUID serviceId;
    private UUID questionId;

    @BeforeEach
    void setUp() throws Exception {
        accountRepository.deleteAll();
        profileRepository.deleteAll();

        // Sitter
        Account sitterAcc = new Account();
        sitterAcc.setEmail("sitter@example.com");
        sitterAcc.setOauthProvider(OAuthProvider.LOCAL);
        sitterAcc.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(sitterAcc);

        Profile sitterProf = new Profile();
        sitterProf.setAccount(sitterAcc);
        sitterProf.setRoleType(RoleType.SITTER);
        sitterProf.setName("Sitter");
        sitterId = profileRepository.save(sitterProf).getId();

        Service s = new Service();
        s.setSitterProfile(sitterProf);
        s.setName("Cat Caring");
        s.setBasePrice(new BigDecimal("500"));
        s.setDurationMinutes(60);
        s.setSupportedPetTypes(List.of(TargetPetType.CAT.name()));
        s.setIsActive(true);
        serviceId = serviceRepository.save(s).getId();

        SitterQuestion q = new SitterQuestion();
        q.setSitterProfile(sitterProf);
        q.setTargetPetType(TargetPetType.CAT);
        q.setQuestionText("How many cats?");
        q.setIsActive(true);
        questionId = questionRepository.save(q).getId();

        // Client
        Account clientAcc = new Account();
        clientAcc.setEmail("client@example.com");
        clientAcc.setPasswordHash(passwordEncoder.encode("password123"));
        clientAcc.setOauthProvider(OAuthProvider.LOCAL);
        clientAcc.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(clientAcc);

        Profile clientProf = new Profile();
        clientProf.setAccount(clientAcc);
        clientProf.setRoleType(RoleType.CLIENT);
        clientProf.setName("Client");
        profileRepository.save(clientProf);

        // Login
        LoginRequest loginRequest = new LoginRequest("client@example.com", "password123");
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        AuthTokenResponse tokenResponse = objectMapper.readValue(response, AuthTokenResponse.class);
        clientToken = "Bearer " + tokenResponse.accessToken();

        // Sitter Login
        LoginRequest sitterLogin = new LoginRequest("sitter@example.com", "password123");
        sitterAcc.setPasswordHash(passwordEncoder.encode("password123"));
        accountRepository.save(sitterAcc);
        
        String sitterResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sitterLogin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        AuthTokenResponse sitterTokenResponse = objectMapper.readValue(sitterResp, AuthTokenResponse.class);
        sitterToken = "Bearer " + sitterTokenResponse.accessToken();
    }

    @Test
    void shouldCreateBooking() throws Exception {
        var visit = new CreateBookingRequest.VisitRequest(
                OffsetDateTime.now().plusDays(1),
                OffsetDateTime.now().plusDays(1).plusHours(1)
        );
        var answer = new CreateBookingRequest.AnswerRequest(questionId, "2 cats");
        var request = new CreateBookingRequest(
                sitterId,
                serviceId,
                List.of(UUID.randomUUID()), // Dummy pet ID
                List.of(visit),
                List.of(answer)
        );

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(500.0));
    }

    @Test
    void shouldGetBookingDetail() throws Exception {
        // First create a booking
        var visit = new CreateBookingRequest.VisitRequest(
                OffsetDateTime.now().plusDays(1),
                OffsetDateTime.now().plusDays(1).plusHours(1)
        );
        var request = new CreateBookingRequest(
                sitterId,
                serviceId,
                List.of(UUID.randomUUID()),
                List.of(visit),
                List.of()
        );

        String createResp = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();
        
        BookingResponse booking = objectMapper.readValue(createResp, BookingResponse.class);

        mockMvc.perform(get("/api/v1/bookings/" + booking.id())
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(booking.id().toString()))
                .andExpect(jsonPath("$.visits.length()").value(1));
    }

    @Test
    void shouldSubmitQuote() throws Exception {
        // 1. Create booking
        var visit = new CreateBookingRequest.VisitRequest(
                OffsetDateTime.now().plusDays(1),
                OffsetDateTime.now().plusDays(1).plusHours(1)
        );
        var request = new CreateBookingRequest(
                sitterId,
                serviceId,
                List.of(UUID.randomUUID()),
                List.of(visit),
                List.of()
        );

        String createResp = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();

        BookingResponse booking = objectMapper.readValue(createResp, BookingResponse.class);

        // 2. Submit quote
        SubmitQuoteRequest quoteRequest = new SubmitQuoteRequest(
                new BigDecimal("500"),
                new BigDecimal("50"),
                new BigDecimal("0"),
                "Holiday surcharge"
        );

        mockMvc.perform(post("/api/v1/bookings/" + booking.id() + "/quote")
                        .header("Authorization", sitterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quoteRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("QUOTED"))
                .andExpect(jsonPath("$.totalAmount").value(550.0));
    }

    @Test
    void shouldUpdatePaymentStatus() throws Exception {
        // 1. Create a booking first
        CreateBookingRequest request = new CreateBookingRequest(
                sitterId, serviceId,
                List.of(), // petIds
                List.of(new CreateBookingRequest.VisitRequest(OffsetDateTime.now().plusHours(1), OffsetDateTime.now().plusHours(2))),
                List.of(new CreateBookingRequest.AnswerRequest(questionId, "Some answer"))
        );

        String createResp = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        BookingResponse booking = objectMapper.readValue(createResp, BookingResponse.class);
        UUID bId = booking.id();

        // 2. Client uploads payment proof
        Map<String, Object> proofData = Map.of("bank", "ABC Bank", "txnId", "TXN123");
        mockMvc.perform(post("/api/v1/bookings/" + bId + "/payment-proofs")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(proofData)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING_VERIFICATION"));

        // 3. Sitter confirms offline payment
        mockMvc.perform(post("/api/v1/bookings/" + bId + "/payments/confirm-offline")
                        .header("Authorization", sitterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.orderStatus").value("CONFIRMED"));
    }
}
