package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.ServiceAreaDto;
import com.petsitter.application.dto.UpdatePublicProfileRequest;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import com.petsitter.infrastructure.security.TokenContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

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
@DisplayName("TS-018: 保母公開檔案與標籤管理測試")
class SitterPublicProfileControllerTest {

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
    private SitterTagRepository sitterTagRepository;

    @Autowired
    private SitterServiceAreaRepository sitterServiceAreaRepository;

    @Autowired
    private ForbiddenKeywordRepository forbiddenKeywordRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private GatekeeperRuleRepository gatekeeperRuleRepository;

    @Autowired
    private UserActionLogRepository userActionLogRepository;

    @MockitoBean
    private MediaStorageService mediaStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User sitterUser;
    private User clientUser;
    private User adminUser;
    private Profile sitterProfile;

    @BeforeEach
    void setUp() {
        gatekeeperRuleRepository.deleteAll();
        subscriptionRepository.deleteAll();
        sitterTagRepository.deleteAll();
        sitterServiceAreaRepository.deleteAll();
        forbiddenKeywordRepository.deleteAll();
        userActionLogRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();

        // 建立測試帳號
        sitterUser = User.builder()
                .email("sitter@test.com")
                .passwordHash("hash")
                .role("SITTER")
                .fullName("張保母")
                .build();
        userRepository.save(sitterUser);

        clientUser = User.builder()
                .email("client@test.com")
                .passwordHash("hash")
                .role("OWNER")
                .fullName("王客戶")
                .build();
        userRepository.save(clientUser);

        adminUser = User.builder()
                .email("admin@test.com")
                .passwordHash("hash")
                .role("ADMIN")
                .fullName("林管理")
                .build();
        userRepository.save(adminUser);

        // 建立保母 Profile
        sitterProfile = Profile.builder()
                .userId(sitterUser.getId())
                .type("SITTER")
                .kycStatus("VERIFIED")
                .isOpen(true)
                .isVisible(true)
                .trustScore(100)
                .displayName("張保母")
                .bio("我是個好保母")
                .build();
        profileRepository.save(sitterProfile);
    }


    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("Scenario 1: 編輯公開檔案 - 成功")
    void testUpdateProfileSuccess() throws Exception {
        TokenContext.setUserId(sitterUser.getId());

        UpdatePublicProfileRequest request = UpdatePublicProfileRequest.builder()
                .displayName("愛貓保母阿香")
                .bio("提供優質貓咪寄養服務，十年經驗。")
                .isVisible(true)
                .tags(Arrays.asList("溫柔", "有耐性", "愛乾淨"))
                .serviceAreas(Arrays.asList(
                        new ServiceAreaDto("台北市", "大安區"),
                        new ServiceAreaDto("新北市", "板橋區")
                ))
                .version(sitterProfile.getVersion())
                .build();

        mockMvc.perform(put("/api/sitter/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(user(sitterUser.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("公開檔案更新成功"));

        // 驗證 DB 狀態
        Profile updated = profileRepository.findById(sitterProfile.getId()).orElseThrow();
        assertEquals("愛貓保母阿香", updated.getDisplayName());
        assertEquals("提供優質貓咪寄養服務，十年經驗。", updated.getBio());

        List<SitterTag> tags = sitterTagRepository.findByProfileId(updated.getId());
        assertEquals(3, tags.size());
        assertTrue(tags.stream().anyMatch(t -> "溫柔".equals(t.getTag())));

        List<SitterServiceArea> areas = sitterServiceAreaRepository.findByProfileId(updated.getId());
        assertEquals(2, areas.size());
        assertTrue(areas.stream().anyMatch(a -> "台北市".equals(a.getCity()) && "大安區".equals(a.getDistrict())));

        // 驗證審計日誌
        List<UserActionLog> logs = userActionLogRepository.findAll();
        assertFalse(logs.isEmpty());
        assertTrue(logs.stream().anyMatch(l -> "SITTER_PROFILE_UPDATE".equals(l.getFuncCode())));
    }

    @Test
    @DisplayName("Scenario 2: 編輯公開檔案 - 敏感詞過濾失敗")
    void testUpdateProfileForbiddenKeyword() throws Exception {
        TokenContext.setUserId(sitterUser.getId());

        // 新增敏感詞
        ForbiddenKeyword fk = ForbiddenKeyword.builder()
                .keyword("買賣")
                .createdBy(adminUser.getId())
                .build();
        forbiddenKeywordRepository.save(fk);

        UpdatePublicProfileRequest request = UpdatePublicProfileRequest.builder()
                .displayName("貓咪買賣阿香") // 包含敏感詞
                .bio("提供優質服務")
                .isVisible(true)
                .tags(Collections.singletonList("溫柔"))
                .serviceAreas(Collections.emptyList())
                .version(sitterProfile.getVersion())
                .build();

        mockMvc.perform(put("/api/sitter/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(user(sitterUser.getEmail()).roles("SITTER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MSG_DATA_INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("內容包含敏感詞彙"));
    }

    @Test
    @DisplayName("Scenario 3: 編輯公開檔案 - 樂觀鎖衝突")
    void testUpdateProfileOptimisticLockConflict() throws Exception {
        TokenContext.setUserId(sitterUser.getId());

        UpdatePublicProfileRequest request = UpdatePublicProfileRequest.builder()
                .displayName("阿香")
                .bio("提供優質服務")
                .isVisible(true)
                .tags(Collections.singletonList("溫柔"))
                .serviceAreas(Collections.emptyList())
                .version(sitterProfile.getVersion() + 999) // 錯誤的 version
                .build();

        mockMvc.perform(put("/api/sitter/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(user(sitterUser.getEmail()).roles("SITTER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MSG_DATA_CONCURRENCY_CONFLICT"));
    }

    @Test
    @DisplayName("Scenario 4: 編輯公開檔案 - 標籤個數超限")
    void testUpdateProfileTooManyTags() throws Exception {
        TokenContext.setUserId(sitterUser.getId());

        List<String> tooManyTags = Arrays.asList(
                "T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9", "T10", "T11"
        );

        UpdatePublicProfileRequest request = UpdatePublicProfileRequest.builder()
                .displayName("阿香")
                .bio("提供優質服務")
                .isVisible(true)
                .tags(tooManyTags)
                .serviceAreas(Collections.emptyList())
                .version(sitterProfile.getVersion())
                .build();

        mockMvc.perform(put("/api/sitter/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(user(sitterUser.getEmail()).roles("SITTER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER")); // 經由 Validation 校驗攔截
    }

    @Test
    @DisplayName("Scenario 5: 大頭貼上傳 - 成功")
    void testUploadAvatarSuccess() throws Exception {
        TokenContext.setUserId(sitterUser.getId());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.png",
                "image/png",
                new byte[100]
        );

        when(mediaStorageService.uploadAvatar(eq(sitterUser.getId()), any()))
                .thenReturn("http://localhost:8080/local-media/avatars/" + sitterUser.getId() + ".png");

        mockMvc.perform(multipart("/api/sitter/profile/avatar")
                        .file(file)
                        .with(user(sitterUser.getEmail()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.avatarUrl").value(org.hamcrest.Matchers.containsString("?v=")));

        Profile updated = profileRepository.findById(sitterProfile.getId()).orElseThrow();
        assertNotNull(updated.getAvatarUrl());
        assertTrue(updated.getAvatarUrl().contains("?v="));
    }

    @Test
    @DisplayName("Scenario 6: 取得公開檔案 - Gating 隱私模糊化")
    void testGetProfileGating() throws Exception {
        // --- A. 停權保母 ---
        User suspendedSitter = User.builder()
                .email("suspended@test.com")
                .passwordHash("hash")
                .role("SITTER")
                .build();
        userRepository.save(suspendedSitter);

        Profile suspendedProfile = Profile.builder()
                .userId(suspendedSitter.getId())
                .type("SITTER")
                .kycStatus("SUSPENDED") // 停權
                .isOpen(true)
                .isVisible(true)
                .displayName("壞保母")
                .bio("我很壞")
                .build();
        profileRepository.save(suspendedProfile);

        // 匿名查詢停權保母，應該回傳 gated = true
        mockMvc.perform(get("/api/sitter/profile/" + suspendedSitter.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gated").value(true))
                .andExpect(jsonPath("$.displayName").value("保母休息中"))
                .andExpect(jsonPath("$.bio").value(""));

        // --- B. 設定不可見保母 ---
        User invisibleSitter = User.builder()
                .email("invisible@test.com")
                .passwordHash("hash")
                .role("SITTER")
                .build();
        userRepository.save(invisibleSitter);

        Profile invisibleProfile = Profile.builder()
                .userId(invisibleSitter.getId())
                .type("SITTER")
                .kycStatus("VERIFIED")
                .isOpen(true)
                .isVisible(false) // 隱藏
                .displayName("隱密保母")
                .bio("看不見我")
                .build();
        profileRepository.save(invisibleProfile);

        // 匿名查詢不可見保母，應該回傳 gated = true
        mockMvc.perform(get("/api/sitter/profile/" + invisibleSitter.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gated").value(true))
                .andExpect(jsonPath("$.displayName").value("保母休息中"));

        // --- C. 客戶被保母加入黑名單 ---
        // 建立訂閱方案，保母必須是 PRO/ULTIMATE 黑名單才生效
        Subscription sub = Subscription.builder()
                .sitter(sitterUser)
                .planTier("PRO")
                .build();
        subscriptionRepository.save(sub);

        // 建立黑名單規則
        GatekeeperRule rule = GatekeeperRule.builder()
                .sitterId(sitterUser.getId())
                .targetUserId(clientUser.getId())
                .ruleType("BLACK")
                .scopeType("GLOBAL")
                .build();
        gatekeeperRuleRepository.save(rule);

        // 客戶 D 登入查詢保母 A，應被 gated = true
        TokenContext.setUserId(clientUser.getId());
        mockMvc.perform(get("/api/sitter/profile/" + sitterUser.getId())
                        .with(user(clientUser.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gated").value(true))
                .andExpect(jsonPath("$.displayName").value("保母休息中"));

        // 匿名查詢保母 A (未傳入 token/TokenContext 清理後)，應不受 gated 卡控
        TokenContext.clear();
        mockMvc.perform(get("/api/sitter/profile/" + sitterUser.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gated").value(false))
                .andExpect(jsonPath("$.displayName").value(sitterProfile.getDisplayName()));
    }

    @Test
    @DisplayName("Scenario 7: Admin 敏感詞管理")
    void testAdminForbiddenKeywords() throws Exception {
        TokenContext.setUserId(adminUser.getId());

        // 1. 新增
        String response = mockMvc.perform(post("/api/admin/forbidden-keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"買賣\"}")
                        .with(user(adminUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyword").value("買賣"))
                .andReturn().getResponse().getContentAsString();

        UUID keywordId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        // 2. 重複新增應報錯 409
        mockMvc.perform(post("/api/admin/forbidden-keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"買賣\"}")
                        .with(user(adminUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MSG_DATA_CONCURRENCY_CONFLICT"));

        // 3. 查詢
        mockMvc.perform(get("/api/admin/forbidden-keywords?q=買")
                        .with(user(adminUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].keyword").value("買賣"));

        // 4. 刪除
        mockMvc.perform(delete("/api/admin/forbidden-keywords/" + keywordId)
                        .with(user(adminUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 驗證已刪除
        assertFalse(forbiddenKeywordRepository.existsById(keywordId));
    }
}
