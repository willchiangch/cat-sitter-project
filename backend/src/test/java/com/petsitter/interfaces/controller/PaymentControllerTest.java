package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.OrderDetailResponseDto;
import com.petsitter.application.dto.UpdateSitterPaymentInfoRequest;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.BankAccountInfo;
import com.petsitter.domain.repository.OrderLogRepository;
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

    @Autowired
    private OrderLogRepository orderLogRepository;

    @Autowired
    private com.petsitter.domain.repository.NotificationRepository notificationRepository;

    @Autowired
    private com.petsitter.domain.repository.ModificationRequestRepository modificationRequestRepository;

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
        orderLogRepository.deleteAll();
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

    /**
     * 監聽器是 @Async，verify(timeout) 只保證方法被呼叫，不保證通知已寫入。不等它寫完，
     * 下一支測試 setUp 的 userRepository.deleteAll() 可能撞上剛插入的 notifications 外鍵而失敗
     * （新增測試後執行順序改變才暴露）。
     */
    private void awaitAsyncNotificationPersisted() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (notificationRepository.count() == 0) {
            if (System.currentTimeMillis() > deadline) {
                fail("非同步通知 5 秒內未寫入");
            }
            Thread.sleep(50);
        }
    }

    /** 等非同步監聽器寫完飼主的通知後回傳（只會有一筆） */
    private com.petsitter.domain.model.Notification awaitOwnerNotification() throws InterruptedException {
        awaitAsyncNotificationPersisted();
        var list = notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(owner.getId()))
                .toList();
        assertEquals(1, list.size());
        return list.get(0);
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

        // 2026-10 起 uploadPaymentProof 回傳私有物件的 Object Key，而非公開網址 (SD-007 §5.1)
        String mockObjectKey = "payment-proofs/2026-10-24/" + order.getId() + "_proof.jpg";
        when(mediaStorageService.uploadPaymentProof(eq(owner.getId()), eq(order.getId()), any()))
                .thenReturn(mockObjectKey);

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
        assertEquals(mockObjectKey, updatedOrder.getPaymentProofUrl());
        assertTrue(updatedOrder.isDisclaimerAgreed());
        assertNotNull(updatedOrder.getDisclaimerAgreedAt());

        // 驗證非同步通知有觸發
        org.mockito.Mockito.verify(notificationListener, org.mockito.Mockito.timeout(5000).times(1))
                .onPaymentProofSubmitted(any());
        awaitAsyncNotificationPersisted();
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
        var notification = awaitOwnerNotification();
        assertEquals("付款已確認", notification.getTitle());
        assertTrue(notification.getContent().contains("交易正式成立"));
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
        awaitAsyncNotificationPersisted();
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

    // ===== 付款憑證私有化 + 簽名網址 (SD-007 §4.7, TS-007 B1 迴歸) =====

    private static final String LEGACY_PUBLIC_URL_PREFIX = "https://storage.googleapis.com/cat-sitter-media-bucket/";

    private void givenPaidOrderWithProof(String storedRef) {
        order.setStatus("PAID");
        order.setPaymentProofUrl(storedRef);
        order.setPaymentProofLastFive("12345");
        orderRepository.save(order);
        // 模擬 GCS 實作的 resolveObjectKey：剝掉舊資料的公開網址前綴
        when(mediaStorageService.resolveObjectKey(any())).thenAnswer(inv -> {
            String ref = inv.getArgument(0);
            return ref.startsWith(LEGACY_PUBLIC_URL_PREFIX) ? ref.substring(LEGACY_PUBLIC_URL_PREFIX.length()) : ref;
        });
        when(mediaStorageService.generateSignedUrl(any(), any())).thenAnswer(inv ->
                "https://storage.googleapis.com/cat-sitter-media-bucket/" + inv.getArgument(0)
                        + "?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Signature=abc");
    }

    @Test
    @DisplayName("B1 迴歸：非訂單當事人取付款憑證簽名網址應 403，且不得觸發簽名")
    void should_Return403_When_NonPartyRequestsPaymentProofSignedUrl() throws Exception {
        givenPaidOrderWithProof("payment-proofs/2026-10-24/" + order.getId() + "_a.jpg");
        TokenContext.setUserId(otherUser.getId());

        mockMvc.perform(get("/api/orders/{orderId}/payment-proof/signed-url", order.getId())
                        .with(user(otherUser.getEmail()).roles("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        org.mockito.Mockito.verify(mediaStorageService, org.mockito.Mockito.never()).generateSignedUrl(any(), any());
    }

    @Test
    @DisplayName("B1 迴歸：訂單飼主與保母都能取得付款憑證的 10 分鐘簽名網址")
    void should_ReturnSignedUrl_When_OrderPartyRequestsPaymentProof() throws Exception {
        String objectKey = "payment-proofs/2026-10-24/" + order.getId() + "_a.jpg";
        givenPaidOrderWithProof(objectKey);

        TokenContext.setUserId(owner.getId());
        mockMvc.perform(get("/api/orders/{orderId}/payment-proof/signed-url", order.getId())
                        .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedUrl").value(org.hamcrest.Matchers.containsString("X-Goog-Signature=")))
                .andExpect(jsonPath("$.expiresInSeconds").value(600));

        TokenContext.setUserId(sitter.getId());
        mockMvc.perform(get("/api/orders/{orderId}/payment-proof/signed-url", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedUrl").value(org.hamcrest.Matchers.containsString(objectKey)));

        org.mockito.Mockito.verify(mediaStorageService, org.mockito.Mockito.times(2))
                .generateSignedUrl(eq(objectKey), eq(java.time.Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("B1 迴歸：舊資料存的是公開網址，仍以解析出的 Object Key 簽名")
    void should_SignResolvedObjectKey_When_LegacyPublicUrlStored() throws Exception {
        String objectKey = "payment-proofs/2026-09-01/" + order.getId() + "_legacy.jpg";
        givenPaidOrderWithProof(LEGACY_PUBLIC_URL_PREFIX + objectKey);
        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(get("/api/orders/{orderId}/payment-proof/signed-url", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(mediaStorageService).generateSignedUrl(eq(objectKey), any());
    }

    @Test
    @DisplayName("B1 迴歸：憑證參照不在 payment-proofs/ 底下時拒絕簽名 (409)，避免變成任意物件簽名器")
    void should_RefuseToSign_When_ProofRefOutsidePaymentProofPrefix() throws Exception {
        givenPaidOrderWithProof("kyc/" + sitter.getId() + "/id-front.jpg");
        TokenContext.setUserId(owner.getId());

        mockMvc.perform(get("/api/orders/{orderId}/payment-proof/signed-url", order.getId())
                        .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isConflict());

        org.mockito.Mockito.verify(mediaStorageService, org.mockito.Mockito.never()).generateSignedUrl(any(), any());
    }

    @Test
    @DisplayName("B1 迴歸：訂單詳情與保母清單 DTO 不再回傳憑證網址，只回 hasPaymentProof")
    void should_NotLeakProofUrl_In_OrderDtos() throws Exception {
        givenPaidOrderWithProof(LEGACY_PUBLIC_URL_PREFIX + "payment-proofs/2026-09-01/" + order.getId() + "_legacy.jpg");

        TokenContext.setUserId(owner.getId());
        String detail = mockMvc.perform(get("/api/orders/{orderId}", order.getId())
                        .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPaymentProof").value(true))
                .andExpect(jsonPath("$.paymentProofLastFive").value("12345"))
                .andExpect(jsonPath("$.paymentProofUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(detail.contains("payment-proofs/"), "訂單詳情不得含憑證物件路徑: " + detail);

        TokenContext.setUserId(sitter.getId());
        String list = mockMvc.perform(get("/api/orders/sitter")
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasPaymentProof").value(true))
                .andExpect(jsonPath("$[0].paymentProofUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("payment-proofs/"), "保母訂單清單不得含憑證物件路徑: " + list);
    }

    @Test
    @DisplayName("B1 迴歸：沒有憑證的訂單 hasPaymentProof=false，取簽名網址回 409")
    void should_Return409_When_OrderHasNoPaymentProof() throws Exception {
        TokenContext.setUserId(owner.getId());

        mockMvc.perform(get("/api/orders/{orderId}", order.getId())
                        .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPaymentProof").value(false));

        mockMvc.perform(get("/api/orders/{orderId}/payment-proof/signed-url", order.getId())
                        .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("B1 遷移端點：預設 dry-run 只回報公開物件不修改；dryRun=false 只對公開物件移除 ACL")
    void should_RevokeOnlyPublicProofs_And_DefaultToDryRun() throws Exception {
        String publicKey = "payment-proofs/2026-09-01/a.jpg";
        String privateKey = "payment-proofs/2026-10-24/b.jpg";
        when(mediaStorageService.listObjectKeys("payment-proofs/")).thenReturn(java.util.List.of(publicKey, privateKey));
        when(mediaStorageService.isPubliclyReadable(publicKey)).thenReturn(true);
        when(mediaStorageService.isPubliclyReadable(privateKey)).thenReturn(false);
        when(mediaStorageService.revokePublicRead(publicKey)).thenReturn(true);

        mockMvc.perform(post("/api/internal/maintenance/payment-proofs/revoke-public-acl")
                        .header("X-Internal-Secret", "local-secret-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.scanned").value(2))
                .andExpect(jsonPath("$.publicFound").value(1))
                .andExpect(jsonPath("$.revoked").value(0))
                .andExpect(jsonPath("$.publicKeys[0]").value(publicKey));
        org.mockito.Mockito.verify(mediaStorageService, org.mockito.Mockito.never()).revokePublicRead(any());

        mockMvc.perform(post("/api/internal/maintenance/payment-proofs/revoke-public-acl")
                        .param("dryRun", "false")
                        .header("X-Internal-Secret", "local-secret-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.revoked").value(1))
                .andExpect(jsonPath("$.failed").value(0));
        org.mockito.Mockito.verify(mediaStorageService, org.mockito.Mockito.times(1)).revokePublicRead(publicKey);
        org.mockito.Mockito.verify(mediaStorageService, org.mockito.Mockito.never()).revokePublicRead(privateKey);
    }

    @Test
    @DisplayName("B1 遷移端點：不帶 X-Internal-Secret 應 401")
    void should_Return401_When_RevokeAclWithoutSecret() throws Exception {
        mockMvc.perform(post("/api/internal/maintenance/payment-proofs/revoke-public-acl").param("dryRun", "false"))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(mediaStorageService, org.mockito.Mockito.never()).listObjectKeys(any());
    }

    // ===== 訂單變更補款 (PRD-016 C.3 / SD-016 §4.2, TS-016 B2 迴歸) =====

    private static final String INITIAL_PROOF_KEY = "payment-proofs/2026-10-01/initial.jpg";
    private static final String SUPPLEMENT_PROOF_KEY = "payment-proofs/2026-10-24/supplement.jpg";
    private static final OffsetDateTime INITIAL_PAID_AT = OffsetDateTime.parse("2026-10-01T02:00:00Z");
    private static final OffsetDateTime INITIAL_DISCLAIMER_AT = OffsetDateTime.parse("2026-10-01T01:00:00Z");

    /**
     * 首次付款已核帳（憑證 11111、paidAt 固定），之後從 previousStatus 發起變更、飼主確認差額 +600，
     * 訂單停在 PENDING_PAYMENT 等補款
     */
    private com.petsitter.domain.model.ModificationRequest givenSupplementPending(String previousStatus) {
        order.setStatus("PENDING_PAYMENT");
        order.setTotalAmount(1200);
        order.setPaymentProofUrl(INITIAL_PROOF_KEY);
        order.setPaymentProofLastFive("11111");
        order.setPaymentIdempotencyKey("initial-key");
        order.setDisclaimerAgreed(true);
        order.setDisclaimerAgreedAt(INITIAL_DISCLAIMER_AT);
        order.setPaidAt(INITIAL_PAID_AT);
        order = orderRepository.save(order); // merge 後回傳新版本，後續再存才不會樂觀鎖衝突
        when(mediaStorageService.resolveObjectKey(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaStorageService.generateSignedUrl(any(), any())).thenAnswer(inv ->
                "https://storage.googleapis.com/b/" + inv.getArgument(0) + "?X-Goog-Signature=abc");
        return modificationRequestRepository.save(com.petsitter.domain.model.ModificationRequest.builder()
                .order(order)
                .requestedBy("OWNER")
                .status("M_DONE")
                .diffAmount(600)
                .newTotalAmount(1200)
                .previousStatus(previousStatus)
                .payload(new ArrayList<>())
                .dates(java.util.List.of())
                .build());
    }

    private void markSupplementSubmitted(com.petsitter.domain.model.ModificationRequest mod) {
        mod.setSupplementProofKey(SUPPLEMENT_PROOF_KEY);
        mod.setSupplementProofLastFive("54321");
        mod.setSupplementIdempotencyKey("supplement-key");
        mod.setSupplementSubmittedAt(OffsetDateTime.now());
        modificationRequestRepository.save(mod);
        order.setStatus("PAID");
        order = orderRepository.save(order);
    }

    private void assertInitialPaymentUntouched(Order o) {
        assertEquals(INITIAL_PROOF_KEY, o.getPaymentProofUrl());
        assertEquals("11111", o.getPaymentProofLastFive());
        assertEquals("initial-key", o.getPaymentIdempotencyKey());
        assertTrue(o.isDisclaimerAgreed());
        assertEquals(INITIAL_DISCLAIMER_AT.toInstant(), o.getDisclaimerAgreedAt().toInstant());
        assertEquals(INITIAL_PAID_AT.toInstant(), o.getPaidAt().toInstant());
    }

    @Test
    @DisplayName("B2(a) 迴歸：IN_PROGRESS 中發起的變更，補款核帳後回到 IN_PROGRESS（過去一律 CONFIRMED），paidAt 不覆寫")
    void should_RestoreInProgress_When_SupplementVerified() throws Exception {
        var mod = givenSupplementPending("IN_PROGRESS");
        markSupplementSubmitted(mod);
        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(post("/api/orders/{orderId}/verify-payment", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk());

        Order updated = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals("IN_PROGRESS", updated.getStatus());
        assertInitialPaymentUntouched(updated);
        assertNotNull(modificationRequestRepository.findById(mod.getId()).orElseThrow().getSupplementVerifiedAt());
        awaitAsyncNotificationPersisted();
    }

    @Test
    @DisplayName("C5 迴歸：補款核帳的通知不可再說「交易正式成立」，應為補款確認文案（category 仍為 ORDER_AFFAIR）")
    void should_SendSupplementWording_When_SupplementVerified() throws Exception {
        var mod = givenSupplementPending("CONFIRMED");
        markSupplementSubmitted(mod);
        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(post("/api/orders/{orderId}/verify-payment", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk());

        var notification = awaitOwnerNotification();
        assertEquals("補款已確認", notification.getTitle());
        assertTrue(notification.getContent().contains("補款"));
        assertFalse(notification.getContent().contains("交易正式成立"));
        assertEquals("ORDER_AFFAIR", notification.getCategory());
        assertEquals("OWNER", notification.getRoleTarget());
    }

    @Test
    @DisplayName("B2(a)：CONFIRMED 中發起的變更，補款核帳後回到 CONFIRMED，且之後 amountDue 回到總額")
    void should_RestoreConfirmed_When_SupplementVerified() throws Exception {
        var mod = givenSupplementPending("CONFIRMED");
        markSupplementSubmitted(mod);
        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(post("/api/orders/{orderId}/verify-payment", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk());

        Order updated = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals("CONFIRMED", updated.getStatus());
        assertInitialPaymentUntouched(updated);

        mockMvc.perform(get("/api/orders/{orderId}", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountDue").value(1200))
                .andExpect(jsonPath("$.paymentProofLastFive").value("11111"));
        awaitAsyncNotificationPersisted();
    }

    @Test
    @DisplayName("B2(b) 迴歸：補款上傳憑證存在變更請求上，不覆寫首次付款的憑證/後五碼/冪等鍵/免責時間/paidAt；保母核對看到補款憑證與差額")
    void should_KeepInitialPayment_And_ExposeSupplementProof_When_SupplementUploaded() throws Exception {
        var mod = givenSupplementPending("IN_PROGRESS");
        when(mediaStorageService.uploadPaymentProof(eq(owner.getId()), eq(order.getId()), any()))
                .thenReturn(SUPPLEMENT_PROOF_KEY);
        TokenContext.setUserId(owner.getId());

        mockMvc.perform(multipart("/api/orders/{orderId}/payment-proof", order.getId())
                        .file(new MockMultipartFile("file", "s.jpg", MediaType.IMAGE_JPEG_VALUE, "img".getBytes()))
                        .param("lastFive", "54321")
                        .param("disclaimerAgreed", "true")
                        .header("Idempotency-Key", "supplement-key-1")
                        .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isOk());
        awaitAsyncNotificationPersisted();

        Order updated = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals("PAID", updated.getStatus());
        assertInitialPaymentUntouched(updated);
        var updatedMod = modificationRequestRepository.findById(mod.getId()).orElseThrow();
        assertEquals(SUPPLEMENT_PROOF_KEY, updatedMod.getSupplementProofKey());
        assertEquals("54321", updatedMod.getSupplementProofLastFive());
        assertEquals("supplement-key-1", updatedMod.getSupplementIdempotencyKey());
        assertNotNull(updatedMod.getSupplementSubmittedAt());
        assertNull(updatedMod.getSupplementVerifiedAt());

        // 保母核對：看到的是補款憑證、應收金額是差額
        TokenContext.setUserId(sitter.getId());
        mockMvc.perform(get("/api/orders/sitter").with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].amountDue").value(600))
                .andExpect(jsonPath("$[0].totalAmount").value(1200))
                .andExpect(jsonPath("$[0].paymentProofLastFive").value("54321"))
                .andExpect(jsonPath("$[0].hasPaymentProof").value(true));
        mockMvc.perform(get("/api/orders/{orderId}/payment-proof/signed-url", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedUrl").value(org.hamcrest.Matchers.containsString(SUPPLEMENT_PROOF_KEY)));

        // 核帳後回到首次付款的憑證資訊
        mockMvc.perform(post("/api/orders/{orderId}/verify-payment", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk());
        TokenContext.setUserId(owner.getId());
        mockMvc.perform(get("/api/orders/{orderId}", order.getId())
                        .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.paymentProofLastFive").value("11111"))
                .andExpect(jsonPath("$.hasPaymentProof").value(true));
        mockMvc.perform(get("/api/orders/{orderId}/payment-proof/signed-url", order.getId())
                        .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedUrl").value(org.hamcrest.Matchers.containsString(INITIAL_PROOF_KEY)));
        assertInitialPaymentUntouched(orderRepository.findById(order.getId()).orElseThrow());
    }

    @Test
    @DisplayName("B2(b) 迴歸：駁回補款憑證只清補款欄位，首次付款資料保留；待重傳期間不把首次憑證當成本次憑證")
    void should_ClearOnlySupplementFields_When_SupplementRejected() throws Exception {
        var mod = givenSupplementPending("CONFIRMED");
        markSupplementSubmitted(mod);
        TokenContext.setUserId(sitter.getId());

        mockMvc.perform(post("/api/orders/{orderId}/reject-payment", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rejectReason", "金額不符"))))
                .andExpect(status().isOk());
        awaitAsyncNotificationPersisted();

        Order updated = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals("PENDING_PAYMENT", updated.getStatus());
        assertInitialPaymentUntouched(updated);
        var updatedMod = modificationRequestRepository.findById(mod.getId()).orElseThrow();
        assertNull(updatedMod.getSupplementProofKey());
        assertNull(updatedMod.getSupplementProofLastFive());
        assertNull(updatedMod.getSupplementIdempotencyKey());
        assertNull(updatedMod.getSupplementSubmittedAt());

        mockMvc.perform(get("/api/orders/{orderId}", order.getId())
                        .with(user(sitter.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountDue").value(600))
                .andExpect(jsonPath("$.hasPaymentProof").value(false))
                .andExpect(jsonPath("$.paymentProofLastFive").doesNotExist());
    }

    @Test
    @DisplayName("B2(b)：補款沿用首次付款的 Idempotency-Key 應 409，且不上傳檔案")
    void should_Return409_When_SupplementReusesInitialIdempotencyKey() throws Exception {
        givenSupplementPending("CONFIRMED");
        TokenContext.setUserId(owner.getId());

        mockMvc.perform(multipart("/api/orders/{orderId}/payment-proof", order.getId())
                        .file(new MockMultipartFile("file", "s.jpg", MediaType.IMAGE_JPEG_VALUE, "img".getBytes()))
                        .param("lastFive", "54321")
                        .param("disclaimerAgreed", "true")
                        .header("Idempotency-Key", "initial-key")
                        .with(user(owner.getEmail()).roles("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_REQUEST"));

        org.mockito.Mockito.verify(mediaStorageService, org.mockito.Mockito.never()).uploadPaymentProof(any(), any(), any());
    }
}
