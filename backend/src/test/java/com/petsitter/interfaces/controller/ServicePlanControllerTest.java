package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.BookingItemRequest;
import com.petsitter.application.dto.BookingRequest;
import com.petsitter.application.dto.ServicePlanDto;
import com.petsitter.application.dto.ServicePlanSortRequest;
import com.petsitter.domain.model.CareLog;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.CareLogRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.ServicePlanRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-003: 保母服務方案設定功能測試")
class ServicePlanControllerTest {

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
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ServicePlanRepository servicePlanRepository;

    @Autowired
    private CareLogRepository careLogRepository;

    @Autowired
    private ProfileRepository profileRepository;

    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private User sitterA;
    private User sitterB;
    private User sitterC;
    private User ownerA;

    @BeforeEach
    void setUp() {
        careLogRepository.deleteAll();
        servicePlanRepository.deleteAll();
        subscriptionRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();

        // 建立保母 A
        sitterA = userRepository.save(User.builder().email("sitterA@test.com").passwordHash("hash").role("SITTER").build());
        subscriptionRepository.save(Subscription.builder().sitter(sitterA).planTier("FREE").build());
        profileRepository.save(Profile.builder().userId(sitterA.getId()).type("SITTER").kycStatus("VERIFIED").isOpen(true).build());

        // 建立保母 B
        sitterB = userRepository.save(User.builder().email("sitterB@test.com").passwordHash("hash").role("SITTER").build());
        subscriptionRepository.save(Subscription.builder().sitter(sitterB).planTier("FREE").build());
        profileRepository.save(Profile.builder().userId(sitterB.getId()).type("SITTER").kycStatus("VERIFIED").isOpen(true).build());

        // 建立保母 C (專業版)
        sitterC = userRepository.save(User.builder().email("sitterC@test.com").passwordHash("hash").role("SITTER").build());
        subscriptionRepository.save(Subscription.builder().sitter(sitterC).planTier("PRO").build());
        profileRepository.save(Profile.builder().userId(sitterC.getId()).type("SITTER").kycStatus("VERIFIED").isOpen(true).build());

        // 建立飼主 A
        ownerA = userRepository.save(User.builder().email("ownerA@test.com").passwordHash("hash").role("OWNER").build());
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("Scenario 1: 保母成功建立常態服務方案 (Happy Path)")
    void ts003_01_should_CreateNormalServicePlanSuccessfully() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        ServicePlanDto dto = ServicePlanDto.builder()
                .name("常態到府照護")
                .price(BigDecimal.valueOf(500.50)) // 測試價格四捨五入轉換
                .dailyCapacity(3)
                .defaultTasks(List.of("基本餵食", "清理砂盆"))
                .applicablePetTypes(List.of("CAT"))
                .description("常態方案")
                .sortOrder(1)
                .build();

        mockMvc.perform(post("/api/sitter/plans")
                        .with(user(sitterA.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("新增成功"))
                .andExpect(jsonPath("$.data.name").value("常態到府照護"))
                .andExpect(jsonPath("$.data.price").value(501)) // 四捨五入後應為 501
                .andExpect(jsonPath("$.data.version").value(0));

        // 驗證資料庫
        List<ServicePlan> plans = servicePlanRepository.findAll();
        assertThat(plans).hasSize(1);
        ServicePlan plan = plans.get(0);
        assertThat(plan.getName()).isEqualTo("常態到府照護");
        assertThat(plan.getPrice()).isEqualTo(501L); // 驗證 DB 存成長整數

        // 驗證稽核日誌
        List<CareLog> logs = careLogRepository.findAll();
        assertThat(logs).hasSize(1);
        CareLog log = logs.get(0);
        assertThat(log.getAction()).isEqualTo("SERVICE_PLAN_CRUD");
        assertThat(log.getStatus()).isEqualTo("CREATE_SUCCESS");
        assertThat(log.getDetails()).contains("Created service plan with ID");
    }

    @Test
    @DisplayName("Scenario 2: 保母成功編輯服務方案 (Happy Path)")
    void ts003_02_should_UpdateServicePlanSuccessfully() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        ServicePlan plan = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterA)
                .name("常態到府照護")
                .price(500L)
                .dailyCapacity(3)
                .isDeleted(false)
                .build());

        ServicePlanDto updateDto = ServicePlanDto.builder()
                .name("常態到府照護 (更新版)")
                .price(BigDecimal.valueOf(600))
                .dailyCapacity(4)
                .version(plan.getVersion()) // 帶上目前版本
                .build();

        mockMvc.perform(put("/api/sitter/plans/{planId}", plan.getId())
                        .with(user(sitterA.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("修改成功"))
                .andExpect(jsonPath("$.data.name").value("常態到府照護 (更新版)"))
                .andExpect(jsonPath("$.data.price").value(600))
                .andExpect(jsonPath("$.data.version").value(1));

        // 驗證 DB version 遞增
        ServicePlan updated = servicePlanRepository.findById(plan.getId()).orElseThrow();
        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(updated.getName()).isEqualTo("常態到府照護 (更新版)");

        // 驗證稽核日誌
        List<CareLog> logs = careLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo("UPDATE_SUCCESS");
    }

    @Test
    @DisplayName("Scenario 3: 編輯服務方案樂觀鎖衝突 (409 VERSION_CONFLICT)")
    void ts003_03_should_ThrowConflictException_When_OptimisticLockingFailure() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        ServicePlan plan = ServicePlan.builder()
                .sitter(sitterA)
                .name("常態到府照護")
                .price(500L)
                .dailyCapacity(3)
                .isDeleted(false)
                .build();
        plan = servicePlanRepository.save(plan);
        // 手動更新一次，使 DB 的 version 變為 1 (如果 JPA 有自動處理。在 @Version 下，預設存庫後 version = 0)
        plan.setName("更新過的名字");
        plan = servicePlanRepository.save(plan);
        assertThat(plan.getVersion()).isEqualTo(1);

        // 傳入舊的版本號 version = 0
        ServicePlanDto updateDto = ServicePlanDto.builder()
                .name("嘗試衝突更新")
                .price(BigDecimal.valueOf(600))
                .dailyCapacity(4)
                .version(0)
                .build();

        mockMvc.perform(put("/api/sitter/plans/{planId}", plan.getId())
                        .with(user(sitterA.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString("內容已被更新")));

        // 驗證 DB 中依然為更新過的名字且 version 為 1
        ServicePlan notUpdated = servicePlanRepository.findById(plan.getId()).orElseThrow();
        assertThat(notUpdated.getVersion()).isEqualTo(1);
        assertThat(notUpdated.getName()).isEqualTo("更新過的名字");
    }

    @Test
    @DisplayName("Scenario 4: 保母邏輯刪除服務方案 (Happy Path)")
    void ts003_04_should_DeleteServicePlanLogically() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        ServicePlan plan = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterA)
                .name("常態到府照護")
                .price(500L)
                .dailyCapacity(3)
                .isDeleted(false)
                .build());

        mockMvc.perform(delete("/api/sitter/plans/{planId}", plan.getId())
                        .with(user(sitterA.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("刪除成功"))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 驗證 DB isDeleted 為 true
        ServicePlan deleted = servicePlanRepository.findById(plan.getId()).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();

        // 驗證日誌
        List<CareLog> logs = careLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo("DELETE_SUCCESS");
    }

    @Test
    @DisplayName("Scenario 5: 保母方案排序調整")
    void ts003_05_should_SortServicePlansSuccessfully() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        ServicePlan p1 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterA).name("方案一").price(500L).dailyCapacity(3).sortOrder(0).isDeleted(false).build());
        ServicePlan p2 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterA).name("方案二").price(500L).dailyCapacity(3).sortOrder(1).isDeleted(false).build());

        ServicePlanSortRequest sortReq = ServicePlanSortRequest.builder()
                .planIds(List.of(p2.getId(), p1.getId()))
                .build();

        mockMvc.perform(post("/api/sitter/plans/sort")
                        .with(user(sitterA.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sortReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("修改成功"));

        ServicePlan updatedP1 = servicePlanRepository.findById(p1.getId()).orElseThrow();
        ServicePlan updatedP2 = servicePlanRepository.findById(p2.getId()).orElseThrow();

        assertThat(updatedP2.getSortOrder()).isEqualTo(0);
        assertThat(updatedP1.getSortOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario 6: 價格無效校驗拒絕 (400 INVALID_PARAMETER)")
    void ts003_06_should_RejectInvalidPrice() throws Exception {
        TokenContext.setUserId(sitterA.getId());

        // 1. price = -100
        ServicePlanDto dto1 = ServicePlanDto.builder()
                .name("負價格")
                .price(BigDecimal.valueOf(-100))
                .dailyCapacity(3)
                .build();

        mockMvc.perform(post("/api/sitter/plans")
                        .with(user(sitterA.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));

        // 2. price = 0
        ServicePlanDto dto2 = ServicePlanDto.builder()
                .name("零元價格")
                .price(BigDecimal.valueOf(0))
                .dailyCapacity(3)
                .build();

        mockMvc.perform(post("/api/sitter/plans")
                        .with(user(sitterA.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("Scenario 7: 非專業版保母設定日期區間遭拒 (403 AUTH_PLAN_LIMIT)")
    void ts003_07_should_RejectDateLimit_When_FreeSitter() throws Exception {
        TokenContext.setUserId(sitterB.getId());

        ServicePlanDto dto = ServicePlanDto.builder()
                .name("常態到府照護")
                .price(BigDecimal.valueOf(500))
                .dailyCapacity(3)
                .startDate(LocalDate.now().plusDays(1))
                .endDate(LocalDate.now().plusDays(10))
                .build();

        mockMvc.perform(post("/api/sitter/plans")
                        .with(user(sitterB.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("AUTH_PLAN_LIMIT"));

        // 驗證失敗日誌寫入 care_logs
        List<CareLog> logs = careLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo("CREATE_FAIL");
    }

    @Test
    @DisplayName("Scenario 8: 專業版以上保母成功設定生效日期區間 (Happy Path)")
    void ts003_08_should_AllowDateLimit_When_ProSitter() throws Exception {
        TokenContext.setUserId(sitterC.getId());

        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = LocalDate.now().plusDays(10);

        ServicePlanDto dto = ServicePlanDto.builder()
                .name("暑期限定照護")
                .price(BigDecimal.valueOf(800))
                .dailyCapacity(3)
                .startDate(start)
                .endDate(end)
                .build();

        mockMvc.perform(post("/api/sitter/plans")
                        .with(user(sitterC.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value(start.toString()))
                .andExpect(jsonPath("$.data.endDate").value(end.toString()));

        // 驗證 DB 確實寫入
        List<ServicePlan> plans = servicePlanRepository.findAll();
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getStartDate()).isEqualTo(start);
        assertThat(plans.get(0).getEndDate()).isEqualTo(end);

        // 驗證操作日誌
        List<CareLog> logs = careLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo("CREATE_SUCCESS");
    }

    @Test
    @DisplayName("Scenario 9a: 飼主端 Lazy Evaluation 生效日期區間過濾 — 未來方案隱藏")
    void ts003_09a_should_HideFuturePlanForOwner() throws Exception {
        // P1 常態無限制
        ServicePlan p1 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P1").price(500L).dailyCapacity(3).sortOrder(0).isDeleted(false).build());
        // P2 未來開放的方案 (現已改為相對日期：now + 10 ~ now + 20)
        ServicePlan p2 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P2").price(500L).dailyCapacity(3).sortOrder(1)
                .startDate(LocalDate.now().plusDays(10))
                .endDate(LocalDate.now().plusDays(20))
                .isDeleted(false).build());

        mockMvc.perform(get("/api/sitters/{sitterId}/plans", sitterC.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(p1.getId().toString()));
    }

    @Test
    @DisplayName("Scenario 9b: 飼主端 Lazy Evaluation 生效日期區間過濾 — 逾期方案隱藏")
    void ts003_09b_should_HideExpiredPlanForOwner() throws Exception {
        // P1 常態無限制
        ServicePlan p1 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P1").price(500L).dailyCapacity(3).sortOrder(0).isDeleted(false).build());
        // P3 逾期的方案 (相對日期：now - 20 ~ now - 10)
        ServicePlan p3 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P3").price(500L).dailyCapacity(3).sortOrder(1)
                .startDate(LocalDate.now().minusDays(20))
                .endDate(LocalDate.now().minusDays(10))
                .isDeleted(false).build());

        mockMvc.perform(get("/api/sitters/{sitterId}/plans", sitterC.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(p1.getId().toString()));
    }

    @Test
    @DisplayName("Scenario 9c: 飼主端 Lazy Evaluation 生效日期區間過濾 — 有效區間方案顯示")
    void ts003_09c_should_ShowActivePlanForOwner() throws Exception {
        // P1 常態無限制
        ServicePlan p1 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P1").price(500L).dailyCapacity(3).sortOrder(0).isDeleted(false).build());
        // P4 在有效區間內的方案 (相對日期：now - 5 ~ now + 5)
        ServicePlan p4 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P4").price(500L).dailyCapacity(3).sortOrder(1)
                .startDate(LocalDate.now().minusDays(5))
                .endDate(LocalDate.now().plusDays(5))
                .isDeleted(false).build());

        mockMvc.perform(get("/api/sitters/{sitterId}/plans", sitterC.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(p1.getId().toString()))
                .andExpect(jsonPath("$.data[1].id").value(p4.getId().toString()));
    }

    @Test
    @DisplayName("Scenario 10: 預約送單日期超出方案生效區間拒絕 (422 PLAN_NOT_IN_RANGE)")
    void ts003_10_should_RejectBooking_When_DateOutOfRange() throws Exception {
        // P4 在有效區間內的方案 (相對日期：now - 5 ~ now + 5)
        ServicePlan p4 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P4").price(500L).dailyCapacity(3)
                .startDate(LocalDate.now().minusDays(5))
                .endDate(LocalDate.now().plusDays(5))
                .isDeleted(false).build());

        // 1. 預約過早日期 (now - 10)
        BookingRequest earlyReq = BookingRequest.builder()
                .ownerId(ownerA.getId())
                .sitterId(sitterC.getId())
                .items(List.of(BookingItemRequest.builder()
                        .planId(p4.getId())
                        .dates(List.of(LocalDate.now().minusDays(10)))
                        .timesPerDay(1)
                        .build()))
                .build();

        mockMvc.perform(post("/api/orders/booking")
                        .with(user(ownerA.getId().toString()).roles("OWNER"))
                        .header("Idempotency-Key", "key-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(earlyReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_IN_RANGE"))
                .andExpect(jsonPath("$.message").value("方案目前不在生效區間"));

        // 2. 預約過晚日期 (now + 10)
        BookingRequest lateReq = BookingRequest.builder()
                .ownerId(ownerA.getId())
                .sitterId(sitterC.getId())
                .items(List.of(BookingItemRequest.builder()
                        .planId(p4.getId())
                        .dates(List.of(LocalDate.now().plusDays(10)))
                        .timesPerDay(1)
                        .build()))
                .build();

        mockMvc.perform(post("/api/orders/booking")
                        .with(user(ownerA.getId().toString()).roles("OWNER"))
                        .header("Idempotency-Key", "key-late")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lateReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_IN_RANGE"));
    }

    @Test
    @DisplayName("Scenario 11: 越權防禦 — 編輯/刪除非自己擁有的方案 (403)")
    void ts003_11_should_RejectEditAndDelete_When_NotOwner() throws Exception {
        // P1 屬於 Sitter A
        ServicePlan p1 = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterA).name("P1").price(500L).dailyCapacity(3).isDeleted(false).build());

        // 1. Sitter B 嘗試修改 P1
        ServicePlanDto updateDto = ServicePlanDto.builder()
                .name("越權修改")
                .price(BigDecimal.valueOf(600))
                .dailyCapacity(4)
                .version(p1.getVersion())
                .build();

        mockMvc.perform(put("/api/sitter/plans/{planId}", p1.getId())
                        .with(user(sitterB.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        // 2. Sitter B 嘗試刪除 P1
        mockMvc.perform(delete("/api/sitter/plans/{planId}", p1.getId())
                        .with(user(sitterB.getId().toString()).roles("SITTER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("Scenario 12: 編輯/刪除不存在的方案編號防禦 (404 PLAN_NOT_FOUND)")
    void ts003_12_should_Return404_When_PlanDoesNotExist() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        ServicePlanDto updateDto = ServicePlanDto.builder()
                .name("不存在的方案更新")
                .price(BigDecimal.valueOf(600))
                .dailyCapacity(4)
                .version(0)
                .build();

        // 1. PUT 404
        mockMvc.perform(put("/api/sitter/plans/{planId}", nonExistentId)
                        .with(user(sitterA.getId().toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_FOUND"));

        // 2. DELETE 404
        mockMvc.perform(delete("/api/sitter/plans/{planId}", nonExistentId)
                        .with(user(sitterA.getId().toString()).roles("SITTER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_FOUND"));
    }

    @Test
    @DisplayName("Scenario 13: 保母後台查詢方案列表 (不限期過濾)")
    void ts003_13_should_ReturnAllPlansForSitterBackend() throws Exception {
        TokenContext.setUserId(sitterC.getId());

        // P1 常態
        servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P1").price(500L).dailyCapacity(3).sortOrder(0).isDeleted(false).build());
        // P2 未來
        servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P2").price(500L).dailyCapacity(3).sortOrder(1)
                .startDate(LocalDate.now().plusDays(10))
                .isDeleted(false).build());
        // P3 已過期
        servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitterC).name("P3").price(500L).dailyCapacity(3).sortOrder(2)
                .endDate(LocalDate.now().minusDays(10))
                .isDeleted(false).build());

        mockMvc.perform(get("/api/sitter/plans")
                        .with(user(sitterC.getId().toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3))); // 應該包含全部 3 個，不被 Lazy Evaluation 過濾
    }

    @Test
    @DisplayName("Scenario 14: 角色與認證門禁防護 (401/403)")
    void ts003_14_should_EnforceRoleAndAuthenticationGating() throws Exception {
        ServicePlanDto dto = ServicePlanDto.builder()
                .name("常態到府照護")
                .price(BigDecimal.valueOf(500))
                .dailyCapacity(3)
                .build();

        // 1. OWNER 呼叫 POST /api/sitter/plans -> 403 Forbidden
        mockMvc.perform(post("/api/sitter/plans")
                        .with(user(ownerA.getId().toString()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());

        // 2. 訪客匿名呼叫 POST /api/sitter/plans -> 401 Unauthorized
        mockMvc.perform(post("/api/sitter/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());
    }
}
