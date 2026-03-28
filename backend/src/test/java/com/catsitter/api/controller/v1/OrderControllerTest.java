package com.catsitter.api.controller.v1;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.dto.booking.BookingResponse;
import com.catsitter.api.dto.booking.CreateBookingRequest;
import com.catsitter.api.entity.*;
import com.catsitter.api.entity.enums.*;
import com.catsitter.api.repository.*;
import com.catsitter.api.service.AuthService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderControllerTest {

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
    private VisitRepository visitRepository;

    @Autowired
    private AuthService authService;

    private String clientToken;
    private UUID sitterId;
    private UUID serviceId;

    @BeforeEach
    void setUp() throws Exception {
        visitRepository.deleteAll();
        serviceRepository.deleteAll();
        profileRepository.deleteAll();
        accountRepository.deleteAll();

        // 1. Register Sitter (to get an account and profile)
        var sitterReq = new com.catsitter.api.dto.auth.RegisterRequest(
                "sitter3@example.com",
                "password123456",
                RoleType.SITTER,
                "Sitter 3"
        );
        authService.register(sitterReq);
        Account sitterAcc = accountRepository.findByEmail("sitter3@example.com").get();
        sitterId = profileRepository.findByAccount(sitterAcc).get(0).getId();

        // Add a service
        Profile sitterProf = profileRepository.findById(sitterId).get();
        Service s = new Service();
        s.setSitterProfile(sitterProf);
        s.setName("Cat Caring");
        s.setBasePrice(new BigDecimal("500"));
        s.setDurationMinutes(60);
        s.setSupportedPetTypes(List.of("CAT"));
        s.setIsActive(true);
        serviceId = serviceRepository.save(s).getId();
        serviceRepository.flush();

        // 2. Register Client
        var clientReq = new com.catsitter.api.dto.auth.RegisterRequest(
                "client3@example.com",
                "password123456",
                RoleType.CLIENT,
                "Client 3"
        );
        authService.register(clientReq);

        // 3. Login as Client
        LoginRequest loginRequest = new LoginRequest("client3@example.com", "password123456");
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        AuthTokenResponse tokenResponse = objectMapper.readValue(response, AuthTokenResponse.class);
        clientToken = "Bearer " + tokenResponse.accessToken();
    }

    @Test
    void shouldCompleteOrder() throws Exception {
        // 1. Create a booking
        CreateBookingRequest request = new CreateBookingRequest(
                sitterId, serviceId,
                List.of(),
                List.of(new CreateBookingRequest.VisitRequest(OffsetDateTime.now().plusHours(1), OffsetDateTime.now().plusHours(2))),
                List.of()
        );

        String createResp = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();

        BookingResponse booking = objectMapper.readValue(createResp, BookingResponse.class);
        UUID bId = booking.id();

        // 2. Set visit to DONE
        List<Visit> visits = visitRepository.findByOrderId(bId);
        visits.forEach(v -> v.setStatus(VisitStatus.DONE));
        visitRepository.saveAll(visits);

        // 3. Client completes order
        mockMvc.perform(post("/api/v1/orders/" + bId + "/complete")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));
    }

    @Test
    void shouldFailToCompleteOrderWhenVisitsNotDone() throws Exception {
        // 1. Create a booking
        CreateBookingRequest request = new CreateBookingRequest(
                sitterId, serviceId,
                List.of(),
                List.of(new CreateBookingRequest.VisitRequest(OffsetDateTime.now().plusHours(1), OffsetDateTime.now().plusHours(2))),
                List.of()
        );

        String createResp = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();

        BookingResponse booking = objectMapper.readValue(createResp, BookingResponse.class);

        // 2. Client completes order (should fail because visit is SCHEDULED)
        mockMvc.perform(post("/api/v1/orders/" + booking.id() + "/complete")
                        .header("Authorization", clientToken))
                .andExpect(status().isBadRequest());
    }
}
