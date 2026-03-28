package com.catsitter.api.smoke;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.RegisterRequest;
import com.catsitter.api.dto.booking.BookingResponse;
import com.catsitter.api.dto.booking.CreateBookingRequest;
import com.catsitter.api.entity.enums.RoleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class BookingFlowSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullBookingWorkflowSmokeTest() throws Exception {
        // 1. Register Sitter
        var sitterReq = new RegisterRequest("smoke_sitter@example.com", "password123", RoleType.SITTER, "Smoke Sitter");
        String sitterResp = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sitterReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        AuthTokenResponse sitterToken = objectMapper.readValue(sitterResp, AuthTokenResponse.class);

        // Get Sitter Profile
        String meResp = mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + sitterToken.accessToken()))
                .andReturn().getResponse().getContentAsString();
        var meDto = objectMapper.readTree(meResp);
        UUID sitterProfileId = UUID.fromString(meDto.get("profiles").get(0).get("profileId").asText());

        // 2. Add Service
        var serviceReq = new com.catsitter.api.dto.sitter.CreateServiceRequest(
                "Standard Caring", new BigDecimal("500"), 60, List.of("CAT")
        );
        String svcResp = mockMvc.perform(post("/api/v1/sitters/me/services")
                .header("Authorization", "Bearer " + sitterToken.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(serviceReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID serviceId = UUID.fromString(objectMapper.readTree(svcResp).get("serviceId").asText());

        // 3. Register Client
        var clientReq = new RegisterRequest("smoke_client@example.com", "password123", RoleType.CLIENT, "Smoke Client");
        String clientResp = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(clientReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        AuthTokenResponse clientToken = objectMapper.readValue(clientResp, AuthTokenResponse.class);

        // 4. Create Booking
        var bookingReq = new CreateBookingRequest(
                sitterProfileId, serviceId, List.of(),
                List.of(new CreateBookingRequest.VisitRequest(OffsetDateTime.now().plusHours(24), OffsetDateTime.now().plusHours(25))),
                List.of()
        );
        mockMvc.perform(post("/api/v1/bookings")
                .header("Authorization", "Bearer " + clientToken.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bookingReq)))
                .andExpect(status().isOk());
    }
}
