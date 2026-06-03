package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.OrderDetailResponseDto;
import com.petsitter.application.dto.UpdateSitterPaymentInfoRequest;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.BankAccountInfo;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.infrastructure.security.TokenContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
@DisplayName("TS-007: 線下付款憑證與收款帳戶測試")
class PaymentControllerTest {

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
    private OrderRepository orderRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @MockitoBean
    private MediaStorageService mediaStorageService;

    @MockitoSpyBean
    private com.petsitter.application.listener.NotificationListener notificationListener;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User owner;
    private User sitter;
    private User otherUser;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();

        // 建立測試使用者
        owner = User.builder()
                .email("owner@test.com")
                .passwordHash("hash")
                .role("OWNER")
                .build();
        sitter = User.builder()
                .email("sitter@test.com")
                .passwordHash("hash")
                .role("SITTER")
                .build();
        otherUser = User.builder()
                .email("other@test.com")
                .passwordHash("hash")
                .role("OWNER")
                .build();

        userRepository.save(owner);
        userRepository.save(sitter);
        userRepository.save(otherUser);

        // 建立測試訂單 (預設狀態 PENDING_PAYMENT)
        order = Order.builder()
                .owner(owner)
                .sitter(sitter)
                .status("PENDING_PAYMENT")
                .totalAmount(1500)
                .planId(UUID.randomUUID())
                .items(new ArrayList<>())
                .build();
        orderRepository.save(order);

        TokenContext.clear();
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("保母更新收款帳戶成功 (200 OK)")
    void should_UpdateSitterPaymentInfo_Successfully() throws Exception {
        TokenContext.setUserId(sitter.getId());

        UpdateSitterPaymentInfoRequest request = new UpdateSitterPaymentInfoRequest(
                "822", "忠孝分行", "123456789012", "王小明"
        );

        mockMvc.perform(put("/api/sitter/payment-info")
                .with(user(sitter.getEmail()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 驗證 DB 內是否已成功加密寫入
        Profile profile = profileRepository.findByUserIdAndType(sitter.getId(), "SITTER").orElse(null);
        assertNotNull(profile);
        assertNotNull(profile.getBankAccountInfo());
        assertEquals("822", profile.getBankAccountInfo().getBankCode());
        assertEquals("忠孝分行", profile.getBankAccountInfo().getBankBranch());
    }

    @Test
    @DisplayName("保母更新收款帳戶欄位校驗失敗 (400 Bad Request)")
    void should_FailUpdateSitterPaymentInfo_WhenFieldsInvalid() throws Exception {
        TokenContext.setUserId(sitter.getId());

        // 錯誤的 bankCode (長度不為 3 且有英文字母)
        UpdateSitterPaymentInfoRequest request = new UpdateSitterPaymentInfoRequest(
                "82A", "忠孝分行", "123456789012", "王小明"
        );

        mockMvc.perform(put("/api/sitter/payment-info")
                .with(user(sitter.getEmail()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("飼主正常上傳付款憑證成功 (200 OK) 且狀態變更為 PAID")
    void should_UploadPaymentProof_Successfully() throws Exception {
        TokenContext.setUserId(owner.getId());

        String mockImageUrl = "https://storage.googleapis.com/bucket/proofs/123.jpg";
        when(mediaStorageService.uploadPaymentProof(eq(owner.getId()), eq(order.getId()), any()))
                .thenReturn(mockImageUrl);

        MockMultipartFile file = new MockMultipartFile(
                "file", "proof.jpg", MediaType.IMAGE_JPEG_VALUE, "fake image content".getBytes()
        );

        mockMvc.perform(multipart("/api/orders/{orderId}/payment-proof", order.getId())
                .file(file)
                .param("lastFive", "12345")
                .param("disclaimerAgreed", "true")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 驗證 DB 狀態變更
        Order updatedOrder = orderRepository.findById(order.getId()).orElse(null);
        assertNotNull(updatedOrder);
        assertEquals("PAID", updatedOrder.getStatus());
        assertEquals("12345", updatedOrder.getPaymentProofLastFive());
        assertEquals(mockImageUrl, updatedOrder.getPaymentProofUrl());
        assertTrue(updatedOrder.isDisclaimerAgreed());
        assertNotNull(updatedOrder.getDisclaimerAgreedAt());

        // 驗證非同步通知有觸發
        org.mockito.Mockito.verify(notificationListener, org.mockito.Mockito.timeout(5000).times(1))
                .onPaymentProofSubmitted(any());
    }

    @Test
    @DisplayName("飼主上傳付款憑證若未勾選免責聲明應失敗 (400)")
    void should_FailUploadPaymentProof_WhenDisclaimerNotAgreed() throws Exception {
        TokenContext.setUserId(owner.getId());

        MockMultipartFile file = new MockMultipartFile(
                "file", "proof.jpg", MediaType.IMAGE_JPEG_VALUE, "fake image content".getBytes()
        );

        mockMvc.perform(multipart("/api/orders/{orderId}/payment-proof", order.getId())
                .file(file)
                .param("lastFive", "12345")
                .param("disclaimerAgreed", "false") // 未同意
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("必須勾選並同意線下交易免責聲明"));
    }

    @Test
    @DisplayName("飼主上傳付款憑證若後五碼格式錯誤應失敗 (400)")
    void should_FailUploadPaymentProof_WhenLastFiveInvalid() throws Exception {
        TokenContext.setUserId(owner.getId());

        MockMultipartFile file = new MockMultipartFile(
                "file", "proof.jpg", MediaType.IMAGE_JPEG_VALUE, "fake image content".getBytes()
        );

        mockMvc.perform(multipart("/api/orders/{orderId}/payment-proof", order.getId())
                .file(file)
                .param("lastFive", "123a5") // 格式錯誤
                .param("disclaimerAgreed", "true")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("轉帳帳號後五碼必須為 5 位數字"));
    }

    @Test
    @DisplayName("非訂單飼主嘗試上傳憑證應觸發 BOLA 防禦並退回 (403)")
    void should_BlockUploadPaymentProof_For_NonOwner() throws Exception {
        // 設定 Token 使用者為 otherUser
        TokenContext.setUserId(otherUser.getId());

        MockMultipartFile file = new MockMultipartFile(
                "file", "proof.jpg", MediaType.IMAGE_JPEG_VALUE, "fake image content".getBytes()
        );

        mockMvc.perform(multipart("/api/orders/{orderId}/payment-proof", order.getId())
                .file(file)
                .param("lastFive", "12345")
                .param("disclaimerAgreed", "true")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(otherUser.getEmail()).roles("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("保母確認入帳成功 (200 OK) 且狀態變更為 CONFIRMED")
    void should_VerifyPayment_Successfully() throws Exception {
        // 先將訂單狀態修改為 PAID (核對付款的前提狀態)
        order.setStatus("PAID");
        orderRepository.save(order);

        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(post("/api/orders/{orderId}/verify-payment", order.getId())
                .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        Order updatedOrder = orderRepository.findById(order.getId()).orElse(null);
        assertNotNull(updatedOrder);
        assertEquals("CONFIRMED", updatedOrder.getStatus());
        assertNotNull(updatedOrder.getPaidAt());

        // 驗證非同步通知有觸發
        org.mockito.Mockito.verify(notificationListener, org.mockito.Mockito.timeout(5000).times(1))
                .onPaymentVerified(any());
    }

    @Test
    @DisplayName("保母駁回憑證成功 (200 OK) 且清空相關憑證資訊、免責重置為 false")
    void should_RejectPayment_Successfully() throws Exception {
        order.setStatus("PAID");
        order.setPaymentProofUrl("https://storage.googleapis.com/proof.jpg");
        order.setPaymentProofLastFive("12345");
        order.setDisclaimerAgreed(true);
        order.setDisclaimerAgreedAt(OffsetDateTime.now());
        order.setPaymentIdempotencyKey("idemp-123");
        orderRepository.save(order);

        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(post("/api/orders/{orderId}/reject-payment", order.getId())
                .with(user(sitter.getEmail()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rejectReason", "金額不足"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        Order updatedOrder = orderRepository.findById(order.getId()).orElse(null);
        assertNotNull(updatedOrder);
        assertEquals("PENDING_PAYMENT", updatedOrder.getStatus());
        assertNull(updatedOrder.getPaymentProofUrl());
        assertNull(updatedOrder.getPaymentProofLastFive());
        assertNull(updatedOrder.getPaymentIdempotencyKey());
        assertFalse(updatedOrder.isDisclaimerAgreed());
        assertNull(updatedOrder.getDisclaimerAgreedAt());

        // 驗證非同步通知有觸發
        org.mockito.Mockito.verify(notificationListener, org.mockito.Mockito.timeout(5000).times(1))
                .onPaymentRejected(any());
    }

    @Test
    @DisplayName("查詢訂單詳情 BOLA 檢核且根據狀態正確過濾保母收款資訊 (PENDING_PAYMENT)")
    void should_GetOrderDetail_And_IncludeSitterBankInfo_WhenStatusIsPending() throws Exception {
        // 設定保母收款帳戶
        Profile profile = Profile.builder()
                .userId(sitter.getId())
                .type("SITTER")
                .kycStatus("VERIFIED")
                .bankAccountInfo(new BankAccountInfo("822", "分行", "12345", "保母"))
                .build();
        profileRepository.save(profile);

        TokenContext.setUserId(owner.getId());

        mockMvc.perform(get("/api/orders/{orderId}", order.getId())
                .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.sitterPaymentInfo.bankCode").value("822"))
                .andExpect(jsonPath("$.sitterPaymentInfo.bankAccount").value("12345"));
    }

    @Test
    @DisplayName("查詢訂單詳情當訂單非待付款或待核對狀態，保母收款帳戶一律強制過濾為 null")
    void should_GetOrderDetail_And_FilterBankInfoToNull_WhenStatusIsNotPendingOrPaid() throws Exception {
        // 設定保母收款帳戶
        Profile profile = Profile.builder()
                .userId(sitter.getId())
                .type("SITTER")
                .kycStatus("VERIFIED")
                .bankAccountInfo(new BankAccountInfo("822", "分行", "12345", "保母"))
                .build();
        profileRepository.save(profile);

        // 修改訂單狀態為 CONFIRMED
        order.setStatus("CONFIRMED");
        orderRepository.save(order);

        TokenContext.setUserId(owner.getId());

        mockMvc.perform(get("/api/orders/{orderId}", order.getId())
                .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.sitterPaymentInfo").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("保母查詢收款帳戶成功 (200 OK)")
    void should_GetSitterPaymentInfo_Successfully() throws Exception {
        TokenContext.setUserId(sitter.getId());

        Profile profile = Profile.builder()
                .userId(sitter.getId())
                .type("SITTER")
                .kycStatus("VERIFIED")
                .bankAccountInfo(new BankAccountInfo("822", "分行", "12345", "保母"))
                .build();
        profileRepository.save(profile);

        mockMvc.perform(get("/api/sitter/payment-info")
                .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankCode").value("822"))
                .andExpect(jsonPath("$.bankAccount").value("12345"));
    }

    @Test
    @DisplayName("飼主嘗試查詢保母收款帳戶應拒絕 (403 Forbidden)")
    void should_BlockGetSitterPaymentInfo_For_Owner() throws Exception {
        TokenContext.setUserId(owner.getId());

        mockMvc.perform(get("/api/sitter/payment-info")
                .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("飼主嘗試更新保母收款帳戶應拒絕 (403 Forbidden)")
    void should_BlockUpdateSitterPaymentInfo_For_Owner() throws Exception {
        TokenContext.setUserId(owner.getId());

        UpdateSitterPaymentInfoRequest request = new UpdateSitterPaymentInfoRequest(
                "822", "分行", "123456789012", "保母"
        );

        mockMvc.perform(put("/api/sitter/payment-info")
                .with(user(owner.getEmail()).roles("OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("飼主嘗試駁回憑證應拒絕 (403 Forbidden)")
    void should_BlockRejectPayment_For_Owner() throws Exception {
        TokenContext.setUserId(owner.getId());

        mockMvc.perform(post("/api/orders/{orderId}/reject-payment", order.getId())
                .with(user(owner.getEmail()).roles("OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rejectReason", "理由"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("保母駁回憑證若 rejectReason 格式錯誤應失敗 (400)")
    void should_FailRejectPayment_WhenReasonInvalid() throws Exception {
        order.setStatus("PAID");
        orderRepository.save(order);

        TokenContext.setUserId(sitter.getId());

        // 1. 空字串
        mockMvc.perform(post("/api/orders/{orderId}/reject-payment", order.getId())
                .with(user(sitter.getEmail()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rejectReason", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));

        // 2. 超過 500 字元
        String longReason = "a".repeat(501);
        mockMvc.perform(post("/api/orders/{orderId}/reject-payment", order.getId())
                .with(user(sitter.getEmail()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rejectReason", longReason))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("保母查詢訂單詳情亦可看到自己收款資訊 (PENDING_PAYMENT)")
    void should_GetOrderDetail_And_IncludeSitterBankInfo_WhenRequesterIsSitter() throws Exception {
        Profile profile = Profile.builder()
                .userId(sitter.getId())
                .type("SITTER")
                .kycStatus("VERIFIED")
                .bankAccountInfo(new BankAccountInfo("822", "分行", "12345", "保母"))
                .build();
        profileRepository.save(profile);

        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(get("/api/orders/{orderId}", order.getId())
                .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.sitterPaymentInfo.bankCode").value("822"));
    }

    @Test
    @DisplayName("飼主上傳付款憑證若 Idempotency-Key 超過 100 字元應失敗 (400)")
    void should_FailUploadPaymentProof_WhenIdempotencyKeyTooLong() throws Exception {
        TokenContext.setUserId(owner.getId());

        MockMultipartFile file = new MockMultipartFile(
                "file", "proof.jpg", MediaType.IMAGE_JPEG_VALUE, "fake image content".getBytes()
        );

        String longKey = "a".repeat(101);

        mockMvc.perform(multipart("/api/orders/{orderId}/payment-proof", order.getId())
                .file(file)
                .param("lastFive", "12345")
                .param("disclaimerAgreed", "true")
                .header("Idempotency-Key", longKey)
                .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key 長度不得超過 100 字元"));
    }
}
