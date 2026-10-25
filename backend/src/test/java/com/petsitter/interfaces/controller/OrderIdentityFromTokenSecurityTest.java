package com.petsitter.interfaces.controller;

import com.petsitter.domain.model.GatekeeperRule;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderItem;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.GatekeeperRuleRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.ServicePlanRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.VisitRepository;
import com.petsitter.infrastructure.security.JwtUtils;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * fix-batch11 I1：訂單狀態轉移端點身分一律取自 token（BOLA 迴歸）。
 * <p>
 * 過去 POST /api/orders/{id}/confirm|quote|reject 吃 @RequestParam sitterId、/complete|/dispute 吃
 * @RequestParam ownerId，且沒有 @PreAuthorize，任何已登入者帶上對方 UUID 就能代替保母接單 / 報價 / 婉拒、
 * 代替飼主結案（觸發撥款）/ 申報爭議。
 * <p>
 * 這裡刻意不用 @WithMockUser / TokenContext.setUserId：請求帶真的 JWT（JwtUtils 簽發），走完整
 * SecurityConfig filter chain → JwtAuthenticationFilter 寫 TokenContext → @PreAuthorize → 真的 service + DB。
 * I2：POST /api/orders/booking 過去讀 body 的 ownerId 且沒有角色限制，任何已登入者都能以別的飼主名義下單。
 * <p>
 * 每支端點三種情境：別人的 token 操作 A 的訂單 → 403（含舊攻擊手法：帶受害者 UUID 的 query param）、
 * 錯角色 → 403、本人 → 200（並帶一個亂填的舊身分 param，證明後端忽略它，舊前端在部署空窗期不壞）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@DisplayName("I1: 訂單狀態轉移端點身分取自 token（真 JWT + 真 SecurityConfig）")
class OrderIdentityFromTokenSecurityTest {

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
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private ServicePlanRepository servicePlanRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private GatekeeperRuleRepository gatekeeperRuleRepository;

    private User ownerA;
    private User ownerB;
    private User sitterA;
    private User sitterB;
    private ServicePlan planA;

    @BeforeEach
    void setUp() {
        ownerA = saveUser("owner-a", "OWNER");
        ownerB = saveUser("owner-b", "OWNER");
        sitterA = saveUser("sitter-a", "SITTER");
        sitterB = saveUser("sitter-b", "SITTER");
        planA = servicePlanRepository.saveAndFlush(ServicePlan.builder()
                .sitter(sitterA).name("I1 測試方案").price(600L).build());
    }

    private User saveUser(String prefix, String role) {
        return userRepository.saveAndFlush(User.builder()
                .email("i1-" + prefix + "-" + System.nanoTime() + "@test.com")
                .passwordHash("hash").fullName("I1 " + prefix).role(role).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtils.generateToken(user.getEmail(), user.getRole(),
                Map.<String, Object>of("userId", user.getId().toString()), 3_600_000L);
    }

    /** 飼主 A 向保母 A 下的單 */
    private Order orderOfA(String status) {
        OrderItem item = new OrderItem();
        item.setCategory("BOOKING");
        item.setServiceName(planA.getName());
        item.setUnitPrice(600);
        item.setQuantity(1);
        item.setPlanId(planA.getId());
        return orderRepository.saveAndFlush(Order.builder()
                .owner(ownerA).sitter(sitterA)
                .items(List.of(item))
                .status(status)
                .totalAmount(600)
                .planId(planA.getId())
                .build());
    }

    private void addVisit(Order order, String visitStatus) {
        visitRepository.saveAndFlush(Visit.builder()
                .order(order).planId(order.getPlanId()).snapshotPlanTitle("I1")
                .status(visitStatus).scheduledAt(OffsetDateTime.now().minusDays(1)).build());
    }

    private String statusOf(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }

    private int versionOf(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getVersion();
    }

    private MockHttpServletRequestBuilder quoteReq(Order order) {
        return post("/api/orders/{id}/quote", order.getId())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adjustmentAmount\":0,\"expectedTotalAmount\":600,\"version\":" + versionOf(order) + "}");
    }

    private MockHttpServletRequestBuilder rejectReq(Order order) {
        return post("/api/orders/{id}/reject", order.getId())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rejectReason\":\"I1\",\"version\":" + versionOf(order) + "}");
    }

    private MockHttpServletRequestBuilder disputeReq(Order order) {
        return post("/api/orders/{id}/dispute", order.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"QUALITY\",\"description\":\"I1\"}");
    }

    // ---- POST /{id}/confirm（保母原價接單） ----

    @Test
    @DisplayName("POST /{id}/confirm（保母原價接單）：別的保母帶受害保母的 ?sitterId= → 403，訂單仍 PENDING")
    void confirm_otherSitter_403() throws Exception {
        Order order = orderOfA("PENDING");
        mockMvc.perform(post("/api/orders/{id}/confirm", order.getId())
                        .header("Authorization", bearer(sitterB))
                        .param("sitterId", sitterA.getId().toString()))
                .andExpect(status().isForbidden());
        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("POST /{id}/confirm（保母原價接單）：錯角色（訂單的飼主本人帶 ?sitterId=）→ 403；未登入 → 401")
    void confirm_wrongRole_403() throws Exception {
        Order order = orderOfA("PENDING");
        mockMvc.perform(post("/api/orders/{id}/confirm", order.getId())
                        .header("Authorization", bearer(ownerA))
                        .param("sitterId", sitterA.getId().toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orders/{id}/confirm", order.getId())
                        .param("sitterId", sitterA.getId().toString()))
                .andExpect(status().isUnauthorized());
        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("POST /{id}/confirm（保母原價接單）：本人 → 200 PENDING_PAYMENT；舊前端多帶的 ?sitterId=（亂填）被忽略")
    void confirm_self_200_ignoresLegacyParam() throws Exception {
        Order order = orderOfA("PENDING");
        mockMvc.perform(post("/api/orders/{id}/confirm", order.getId())
                        .header("Authorization", bearer(sitterA))
                        .param("sitterId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("訂單已確認"));
        assertThat(statusOf(order)).isEqualTo("PENDING_PAYMENT");
    }

    // ---- POST /{id}/quote（保母報價） ----

    @Test
    @DisplayName("POST /{id}/quote（保母報價）：別的保母帶受害保母的 ?sitterId= → 403，訂單仍 PENDING")
    void quote_otherSitter_403() throws Exception {
        Order order = orderOfA("PENDING");
        mockMvc.perform(quoteReq(order)
                        .header("Authorization", bearer(sitterB))
                        .param("sitterId", sitterA.getId().toString()))
                .andExpect(status().isForbidden());
        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("POST /{id}/quote（保母報價）：錯角色（飼主）→ 403")
    void quote_wrongRole_403() throws Exception {
        Order order = orderOfA("PENDING");
        mockMvc.perform(quoteReq(order)
                        .header("Authorization", bearer(ownerA))
                        .param("sitterId", sitterA.getId().toString()))
                .andExpect(status().isForbidden());
        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("POST /{id}/quote（保母報價）：本人 → 200 PENDING_PAYMENT；舊 ?sitterId= 被忽略")
    void quote_self_200_ignoresLegacyParam() throws Exception {
        Order order = orderOfA("PENDING");
        mockMvc.perform(quoteReq(order)
                        .header("Authorization", bearer(sitterA))
                        .param("sitterId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("報價已送出"));
        assertThat(statusOf(order)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("POST /{id}/quote（保母報價）：方案門檻不因移除 sitterId 參數而失效：FREE 保母調價 → 403 AUTH_PLAN_LIMIT（以 token 身分判斷方案）")
    void quote_freePlanAdjustment_stillBlocked() throws Exception {
        subscriptionRepository.saveAndFlush(Subscription.builder().sitter(sitterA).planTier("FREE").build());
        Order order = orderOfA("PENDING");
        mockMvc.perform(post("/api/orders/{id}/quote", order.getId())
                        .header("Authorization", bearer(sitterA))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adjustmentAmount\":100,\"expectedTotalAmount\":700,\"adjustmentReason\":\"I1\",\"version\":"
                                + versionOf(order) + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("AUTH_PLAN_LIMIT"));
        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    // ---- POST /{id}/reject（保母婉拒） ----

    @Test
    @DisplayName("POST /{id}/reject（保母婉拒）：別的保母帶受害保母的 ?sitterId= → 403，訂單仍 PENDING")
    void reject_otherSitter_403() throws Exception {
        Order order = orderOfA("PENDING");
        mockMvc.perform(rejectReq(order)
                        .header("Authorization", bearer(sitterB))
                        .param("sitterId", sitterA.getId().toString()))
                .andExpect(status().isForbidden());
        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("POST /{id}/reject（保母婉拒）：錯角色（飼主）→ 403")
    void reject_wrongRole_403() throws Exception {
        Order order = orderOfA("PENDING");
        mockMvc.perform(rejectReq(order)
                        .header("Authorization", bearer(ownerA))
                        .param("sitterId", sitterA.getId().toString()))
                .andExpect(status().isForbidden());
        assertThat(statusOf(order)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("POST /{id}/reject（保母婉拒）：本人 → 200 CANCELLED；舊 ?sitterId= 被忽略")
    void reject_self_200_ignoresLegacyParam() throws Exception {
        Order order = orderOfA("PENDING");
        mockMvc.perform(rejectReq(order)
                        .header("Authorization", bearer(sitterA))
                        .param("sitterId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已拒絕此訂單"));
        assertThat(statusOf(order)).isEqualTo("CANCELLED");
    }

    // ---- POST /{id}/complete（飼主結案，會排撥款） ----

    @Test
    @DisplayName("POST /{id}/complete（飼主結案，會排撥款）：別的飼主帶受害飼主的 ?ownerId= → 403，訂單仍 IN_PROGRESS、沒有撥款日")
    void complete_otherOwner_403() throws Exception {
        Order order = orderOfA("IN_PROGRESS");
        addVisit(order, "DONE");
        mockMvc.perform(post("/api/orders/{id}/complete", order.getId())
                        .header("Authorization", bearer(ownerB))
                        .param("ownerId", ownerA.getId().toString()))
                .andExpect(status().isForbidden());
        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(after.getPayoutAt()).isNull();
    }

    @Test
    @DisplayName("POST /{id}/complete（飼主結案，會排撥款）：錯角色（訂單的保母帶 ?ownerId=飼主）→ 403；未登入 → 401")
    void complete_wrongRole_403() throws Exception {
        Order order = orderOfA("IN_PROGRESS");
        addVisit(order, "DONE");
        mockMvc.perform(post("/api/orders/{id}/complete", order.getId())
                        .header("Authorization", bearer(sitterA))
                        .param("ownerId", ownerA.getId().toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orders/{id}/complete", order.getId())
                        .param("ownerId", ownerA.getId().toString()))
                .andExpect(status().isUnauthorized());
        assertThat(statusOf(order)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("POST /{id}/complete（飼主結案，會排撥款）：本人 → 200 COMPLETED 並排撥款；舊 ?ownerId= 被忽略")
    void complete_self_200_ignoresLegacyParam() throws Exception {
        Order order = orderOfA("IN_PROGRESS");
        addVisit(order, "DONE");
        mockMvc.perform(post("/api/orders/{id}/complete", order.getId())
                        .header("Authorization", bearer(ownerA))
                        .param("ownerId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("訂單已成功結案"));
        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("COMPLETED");
        assertThat(after.getPayoutAt()).isNotNull();
    }

    // ---- POST /{id}/dispute（飼主申報爭議） ----

    @Test
    @DisplayName("POST /{id}/dispute（飼主申報爭議）：別的飼主帶受害飼主的 ?ownerId= → 403，訂單仍 IN_PROGRESS")
    void dispute_otherOwner_403() throws Exception {
        Order order = orderOfA("IN_PROGRESS");
        mockMvc.perform(disputeReq(order)
                        .header("Authorization", bearer(ownerB))
                        .param("ownerId", ownerA.getId().toString()))
                .andExpect(status().isForbidden());
        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(after.isDisputed()).isFalse();
    }

    @Test
    @DisplayName("POST /{id}/dispute（飼主申報爭議）：錯角色（訂單的保母）→ 403")
    void dispute_wrongRole_403() throws Exception {
        Order order = orderOfA("IN_PROGRESS");
        mockMvc.perform(disputeReq(order)
                        .header("Authorization", bearer(sitterA))
                        .param("ownerId", ownerA.getId().toString()))
                .andExpect(status().isForbidden());
        assertThat(statusOf(order)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("POST /{id}/dispute（飼主申報爭議）：本人 → 200 DISPUTED；舊 ?ownerId= 被忽略")
    void dispute_self_200_ignoresLegacyParam() throws Exception {
        Order order = orderOfA("IN_PROGRESS");
        mockMvc.perform(disputeReq(order)
                        .header("Authorization", bearer(ownerA))
                        .param("ownerId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已成功提交爭議申請"));
        assertThat(statusOf(order)).isEqualTo("DISPUTED");
    }

    // ---- POST /booking（飼主送出預約，fix-batch11 I2） ----

    private void openSitterA() {
        profileRepository.saveAndFlush(com.petsitter.domain.model.Profile.builder()
                .userId(sitterA.getId()).type("SITTER").kycStatus("VERIFIED").isOpen(true).build());
    }

    /** 舊前端的 body：仍帶 ownerId（這裡填受害飼主 A） */
    private MockHttpServletRequestBuilder bookingReq() {
        return post("/api/orders/booking")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerId\":\"" + ownerA.getId() + "\",\"sitterId\":\"" + sitterA.getId()
                        + "\",\"items\":[{\"planId\":\"" + planA.getId() + "\",\"dates\":[\""
                        + LocalDate.now().plusDays(3) + "\"],\"timesPerDay\":1,\"petIds\":[]}],"
                        + "\"answers\":[],\"expectedTotalAmount\":600}");
    }

    @Test
    @DisplayName("POST /booking：飼主 B 在 body 填飼主 A 的 ownerId → 201 但訂單掛在 B 名下（body ownerId 被忽略）")
    void booking_bodyOwnerIdIgnored_orderBelongsToCaller() throws Exception {
        openSitterA();
        String body = mockMvc.perform(bookingReq().header("Authorization", bearer(ownerB)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(body.replaceAll(".*\"orderId\":\"([0-9a-f-]{36})\".*", "$1"));
        Order created = orderRepository.findById(orderId).orElseThrow();
        assertThat(created.getOwner().getId()).isEqualTo(ownerB.getId());
        assertThat(orderRepository.findAll()).noneMatch(o -> o.getOwner().getId().equals(ownerA.getId()));
    }

    @Test
    @DisplayName("POST /booking：被保母全域黑名單的飼主 B 冒用飼主 A 的 ownerId → 仍以 B 判斷黑名單 403，沒有建單")
    void booking_blacklistUsesTokenIdentity() throws Exception {
        openSitterA();
        subscriptionRepository.saveAndFlush(Subscription.builder().sitter(sitterA).planTier("PRO").build());
        gatekeeperRuleRepository.saveAndFlush(GatekeeperRule.builder()
                .sitterId(sitterA.getId()).ruleType("BLACK").scopeType("GLOBAL").targetUserId(ownerB.getId()).build());
        long before = orderRepository.count();
        mockMvc.perform(bookingReq().header("Authorization", bearer(ownerB)))
                .andExpect(status().isForbidden());
        assertThat(orderRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("POST /booking：錯角色（保母）→ 403、未登入 → 401，都沒有建單")
    void booking_wrongRole_403() throws Exception {
        openSitterA();
        long before = orderRepository.count();
        mockMvc.perform(bookingReq().header("Authorization", bearer(sitterB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(bookingReq())
                .andExpect(status().isUnauthorized());
        assertThat(orderRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("POST /booking：飼主本人 → 201，訂單飼主為 token 身分")
    void booking_self_201() throws Exception {
        openSitterA();
        String body = mockMvc.perform(bookingReq().header("Authorization", bearer(ownerA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isString())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(body.replaceAll(".*\"orderId\":\"([0-9a-f-]{36})\".*", "$1"));
        assertThat(orderRepository.findById(orderId).orElseThrow().getOwner().getId()).isEqualTo(ownerA.getId());
    }
}
