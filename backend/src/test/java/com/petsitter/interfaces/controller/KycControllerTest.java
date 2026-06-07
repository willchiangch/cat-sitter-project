package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.exception.KycException;
import com.petsitter.application.service.BookingService;
import com.petsitter.application.service.KycService;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.KycRecord;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.KycRecordRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.TokenContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-017: 保母實名認證與資格審查測試")
class KycControllerTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private KycRecordRepository kycRecordRepository;

    @MockitoBean
    private MediaStorageService mediaStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User sitter;
    private User admin;
    private Profile sitterProfile;

    @BeforeEach
    void setUp() {
        kycRecordRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();

        // 建立測試保母
        sitter = User.builder()
                .email("sitter@test.com")
                .passwordHash("hash")
                .role("SITTER")
                .fullName("張保母")
                .build();
        userRepository.save(sitter);

        // 建立保母 Profile
        sitterProfile = Profile.builder()
                .userId(sitter.getId())
                .type("SITTER")
                .kycStatus("UNVERIFIED")
                .isOpen(false)
                .build();
        profileRepository.save(sitterProfile);

        // 建立測試管理員
        admin = User.builder()
                .email("admin@test.com")
                .passwordHash("hash")
                .role("ADMIN")
                .fullName("林管理")
                .build();
        userRepository.save(admin);

        TokenContext.clear();
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("保母初次提交實名認證成功 (200 OK)")
    void should_SubmitKyc_Successfully() throws Exception {
        TokenContext.setUserId(sitter.getId());

        MockMultipartFile frontFile = new MockMultipartFile(
                "idCardFront", "front.jpg", "image/jpeg", "front-content".getBytes()
        );
        MockMultipartFile selfieFile = new MockMultipartFile(
                "selfie", "selfie.jpg", "image/png", "selfie-content".getBytes()
        );

        when(mediaStorageService.uploadKycFile(eq(sitter.getId()), eq("id-front"), any())).thenReturn("kyc/sitter/front.jpg");
        when(mediaStorageService.uploadKycFile(eq(sitter.getId()), eq("selfie"), any())).thenReturn("kyc/sitter/selfie.jpg");

        mockMvc.perform(multipart("/api/sitter/kyc")
                .file(frontFile)
                .file(selfieFile)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("實名認證資料提交成功，已進入審核程序"))
                .andExpect(jsonPath("$.data.recordId").exists())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        // 驗證 DB 狀態變更
        Profile updatedProfile = profileRepository.findByUserIdAndType(sitter.getId(), "SITTER").orElseThrow();
        assertEquals("PENDING_REVIEW", updatedProfile.getKycStatus());
    }

    @Test
    @DisplayName("若認證狀態為 PENDING_REVIEW 或 VERIFIED 則阻擋重複提交 (422 UNPROCESSABLE_ENTITY)")
    void should_BlockDuplicateSubmit_WhenAlreadySubmittedOrVerified() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 設定狀態為 PENDING_REVIEW
        sitterProfile.setKycStatus("PENDING_REVIEW");
        profileRepository.save(sitterProfile);

        MockMultipartFile frontFile = new MockMultipartFile(
                "idCardFront", "front.jpg", "image/jpeg", "front-content".getBytes()
        );
        MockMultipartFile selfieFile = new MockMultipartFile(
                "selfie", "selfie.jpg", "image/png", "selfie-content".getBytes()
        );

        mockMvc.perform(multipart("/api/sitter/kyc")
                .file(frontFile)
                .file(selfieFile)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MSG_DATA_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("若認證狀態為 SUSPENDED 則阻擋重新提交 (422 UNPROCESSABLE_ENTITY)")
    void should_BlockSubmit_WhenSitterIsSuspended() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 設定狀態為 SUSPENDED
        sitterProfile.setKycStatus("SUSPENDED");
        profileRepository.save(sitterProfile);

        MockMultipartFile frontFile = new MockMultipartFile(
                "idCardFront", "front.jpg", "image/jpeg", "front-content".getBytes()
        );
        MockMultipartFile selfieFile = new MockMultipartFile(
                "selfie", "selfie.jpg", "image/png", "selfie-content".getBytes()
        );

        mockMvc.perform(multipart("/api/sitter/kyc")
                .file(frontFile)
                .file(selfieFile)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MSG_DATA_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("查詢保母自身的 KYC 審查狀態 (200 OK)")
    void should_GetKycStatus_Successfully() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 建立一筆已駁回的 KYC 紀錄
        KycRecord record = KycRecord.builder()
                .sitterId(sitter.getId())
                .idCardFrontKey("kyc/sitter/front.jpg")
                .selfieKey("kyc/sitter/selfie.jpg")
                .status("REJECTED")
                .rejectReason("自拍照模糊")
                .build();
        kycRecordRepository.save(record);

        sitterProfile.setKycStatus("REJECTED");
        profileRepository.save(sitterProfile);

        mockMvc.perform(get("/api/sitter/kyc/status")
                .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.kycStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("自拍照模糊"));
    }

    @Test
    @DisplayName("保母開啟接單卡控：未認證保母開啟接單應被阻擋 (403 Forbidden)")
    void should_BlockOpenBooking_WhenSitterNotVerified() throws Exception {
        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(put("/api/sitter/kyc/open")
                .with(user(sitter.getEmail()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("isOpen", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("MSG_DATA_INVALID_STATUS"));
    }

    @Test
    @DisplayName("管理員查詢待審核 KYC 紀錄列表 (200 OK)")
    void should_GetPendingKycRecords_ForAdmin() throws Exception {
        TokenContext.setUserId(admin.getId());

        // 建立一筆待審核 KYC 紀錄
        KycRecord record = KycRecord.builder()
                .sitterId(sitter.getId())
                .idCardFrontKey("kyc/sitter/front.jpg")
                .selfieKey("kyc/sitter/selfie.jpg")
                .status("PENDING")
                .build();
        kycRecordRepository.save(record);

        mockMvc.perform(get("/api/admin/kyc/pending")
                .with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content[0].fullName").value("張保母"))
                .andExpect(jsonPath("$.data.content[0].email").value("sitter@test.com"));
    }

    @Test
    @DisplayName("管理員執行審核核准，狀態更新為 VERIFIED (200 OK)")
    void should_ApproveKyc_Successfully() throws Exception {
        TokenContext.setUserId(admin.getId());

        KycRecord record = KycRecord.builder()
                .sitterId(sitter.getId())
                .idCardFrontKey("kyc/sitter/front.jpg")
                .selfieKey("kyc/sitter/selfie.jpg")
                .status("PENDING")
                .build();
        kycRecordRepository.save(record);

        sitterProfile.setKycStatus("PENDING_REVIEW");
        profileRepository.save(sitterProfile);

        mockMvc.perform(post("/api/admin/kyc/" + record.getId() + "/review")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(admin.getEmail()).roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("action", "APPROVE"))))
                .andExpect(status().isOk());

        // 驗證狀態
        KycRecord updatedRecord = kycRecordRepository.findById(record.getId()).orElseThrow();
        assertEquals("APPROVED", updatedRecord.getStatus());
        assertEquals(admin.getId(), updatedRecord.getReviewedBy());

        Profile updatedProfile = profileRepository.findByUserIdAndType(sitter.getId(), "SITTER").orElseThrow();
        assertEquals("VERIFIED", updatedProfile.getKycStatus());
    }

    @Test
    @DisplayName("管理員審核駁回，狀態更新為 REJECTED 且防呆強制將 isOpen 設為 false")
    void should_RejectKyc_AndForceCloseSitterBooking() throws Exception {
        TokenContext.setUserId(admin.getId());

        KycRecord record = KycRecord.builder()
                .sitterId(sitter.getId())
                .idCardFrontKey("kyc/sitter/front.jpg")
                .selfieKey("kyc/sitter/selfie.jpg")
                .status("PENDING")
                .build();
        kycRecordRepository.save(record);

        sitterProfile.setKycStatus("PENDING_REVIEW");
        sitterProfile.setOpen(true); // 預設模擬開啟接單
        profileRepository.save(sitterProfile);

        mockMvc.perform(post("/api/admin/kyc/" + record.getId() + "/review")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(admin.getEmail()).roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("action", "REJECT", "rejectReason", "身分證正反面顛倒"))))
                .andExpect(status().isOk());

        // 驗證狀態與 isOpen 自動關閉
        Profile updatedProfile = profileRepository.findByUserIdAndType(sitter.getId(), "SITTER").orElseThrow();
        assertEquals("REJECTED", updatedProfile.getKycStatus());
        assertFalse(updatedProfile.isOpen());
    }

    @Test
    @DisplayName("管理員將已認證保母停權，接單狀態強制變更為 false 且狀態為 SUSPENDED")
    void should_SuspendSitter_Successfully() throws Exception {
        TokenContext.setUserId(admin.getId());

        sitterProfile.setKycStatus("VERIFIED");
        sitterProfile.setOpen(true);
        profileRepository.save(sitterProfile);

        mockMvc.perform(post("/api/admin/sitters/" + sitter.getId() + "/suspend")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(admin.getEmail()).roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "違反服務條款"))))
                .andExpect(status().isOk());

        Profile updatedProfile = profileRepository.findByUserIdAndType(sitter.getId(), "SITTER").orElseThrow();
        assertEquals("SUSPENDED", updatedProfile.getKycStatus());
        assertFalse(updatedProfile.isOpen());
    }

    @Test
    @DisplayName("管理員解除停權，狀態回復為 VERIFIED 但不自動開啟 isOpen")
    void should_UnsuspendSitter_Successfully() throws Exception {
        TokenContext.setUserId(admin.getId());

        sitterProfile.setKycStatus("SUSPENDED");
        sitterProfile.setOpen(false);
        profileRepository.save(sitterProfile);

        mockMvc.perform(post("/api/admin/sitters/" + sitter.getId() + "/unsuspend")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk());

        Profile updatedProfile = profileRepository.findByUserIdAndType(sitter.getId(), "SITTER").orElseThrow();
        assertEquals("VERIFIED", updatedProfile.getKycStatus());
        assertFalse(updatedProfile.isOpen());
    }
}
