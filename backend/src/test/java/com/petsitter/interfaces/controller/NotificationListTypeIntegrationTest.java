package com.petsitter.interfaces.controller;

import com.petsitter.application.service.NotificationService;
import com.petsitter.application.service.notify.NotificationCommand;
import com.petsitter.order.OrderIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SD-GLOBAL-SPEC §4.4 NotificationListTypeIntegrationTest：{@code GET /api/notifications} 的 {@code content[]} 帶 {@code type}
 * （{@code notifications.type}），null 時不輸出欄位且不因 null 拋 NPE。真 JWT + 真 filter chain。
 */
@DisplayName("SD-GLOBAL-SPEC §4.4 NotificationListTypeIntegrationTest")
class NotificationListTypeIntegrationTest extends OrderIntegrationTestBase {

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("SD-GLOBAL-SPEC §4.4 enqueue 寫入的 CARE_LOG_SUBMITTED 帶 type；舊 createNotification（type null）無 type 欄位、linkUrl 空字串，回應 200")
    void typeIncludedWhenPresent() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> notificationService.enqueue(new NotificationCommand(
                owner, "OWNER", "CARE_LOG_SUBMITTED", false, null, "/owner/orders/x",
                Map.of("serviceDate", "2026-11-30", "name", "咪咪"))));
        notificationService.createNotification(owner, "系統通知", "舊通知", "ACCOUNT_AUTH", null, "ALL");

        JsonNode res = body(mvc.perform(getAs("/api/notifications", ownerToken())).andExpect(status().isOk()));
        JsonNode content = res.get("data").get("content");
        assertThat(content.size()).isEqualTo(2);
        JsonNode typed = null;
        JsonNode legacy = null;
        for (JsonNode n : content) {
            if ("ORDER_AFFAIR".equals(n.get("category").asString())) {
                typed = n;
            } else {
                legacy = n;
            }
        }
        assertThat(typed).isNotNull();
        assertThat(legacy).isNotNull();
        assertThat(typed.get("type").asString()).isEqualTo("CARE_LOG_SUBMITTED");
        assertThat(typed.get("linkUrl").asString()).isEqualTo("/owner/orders/x");
        assertThat(typed.get("roleTarget").asString()).isEqualTo("OWNER");
        assertThat(legacy.has("type")).isFalse();
        assertThat(legacy.get("linkUrl").asString()).isEmpty();
        assertThat(legacy.get("title").asString()).isEqualTo("系統通知");
        for (JsonNode n : content) {
            assertThat(n.has("emailStatus")).isFalse();
            assertThat(n.has("relatedOrderId")).isFalse();
            assertThat(n.has("mandatory")).isFalse();
        }
        assertThat(res.get("data").get("totalElements").asInt()).isEqualTo(2);
    }
}
