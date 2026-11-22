package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.petsitter.application.port.ServicePlanQuery;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL ChecklistSeedIntegrationTest（createVisits §4.8）")
class ChecklistSeedIntegrationTest extends FulfillIntegrationTestBase {

    @Autowired
    private EntityManagerFactory emf;

    @Test
    @DisplayName("PRD-028 AC-1.1 O1 依方案預設任務帶入（順序、未打勾、created_source=SYSTEM、OWNER 觸發仍 created_by NULL）；無預設任務 → 空；不寫 log / 不通知；方案改動不影響既有、變更新增取當下預設")
    void seedOnBooking() throws Exception {
        defaultTasks(planA, "[\"餵乾糧\",\"換貓砂\",\"清水碗\"]");
        LocalDate d = today().plusDays(1);
        Map<String, Object> body = Map.of("sitterId", sitter.toString(), "expectedAmount", 500 * 2 + 800,
                "groups", List.of(
                        Map.of("planId", planA.toString(), "dates", List.of(d.toString(), d.plusDays(1).toString()), "dailyCount", 1, "petIds", List.of(petA.toString())),
                        Map.of("planId", planB.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of(petA.toString()))));
        int notes = count("SELECT count(*) FROM notifications");
        UUID orderId = UUID.fromString(body(mvc.perform(postJson("/api/orders", ownerToken(), key(), body))).get("orderId").asString());
        List<UUID> aVisits = jdbc.queryForList("SELECT id FROM visits WHERE order_id = ? AND plan_id = ? ORDER BY service_date", UUID.class, orderId, planA);
        UUID bVisit = jdbc.queryForObject("SELECT id FROM visits WHERE order_id = ? AND plan_id = ?", UUID.class, orderId, planB);
        for (UUID v : aVisits) {
            assertThat(itemTitles(v)).containsExactly("餵乾糧", "換貓砂", "清水碗");
        }
        assertThat(itemTitles(bVisit)).isEmpty();
        assertThat(count("SELECT count(*) FROM visit_checklist_items WHERE visit_id IN (SELECT id FROM visits WHERE order_id = ?) "
                + "AND (is_completed OR created_source <> 'SYSTEM' OR created_by IS NOT NULL OR checked_at IS NOT NULL)", orderId)).isZero();
        assertThat(logActions(orderId)).doesNotContain("CHECKLIST_SEEDED");
        // E1 本身的通知（新預約）之外不因帶入多出通知
        assertThat(count("SELECT count(*) FROM notifications WHERE type LIKE '%CHECKLIST%'")).isZero();
        assertThat(count("SELECT count(*) FROM notifications")).isGreaterThanOrEqualTo(notes);

        // 情境 2 / 3：方案改動不影響既有行程；變更新增的行程取當下預設
        UUID pay = quote(orderId);
        verify(orderId, pay, submitProof(orderId, pay, ownerToken()), sitterToken());
        defaultTasks(planA, "[\"餵乾糧\",\"換貓砂\",\"梳毛\"]");
        assertThat(itemTitles(aVisits.get(0))).containsExactly("餵乾糧", "換貓砂", "清水碗");
        LocalDate added = d.plusDays(3);
        UUID mod = modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "加一天",
                "removeVisitIds", List.of(), "addItems", List.of(addItem(added, planA, 1, petA)))).andExpect(status().isCreated()));
        tools.jackson.databind.JsonNode acc = body(mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(),
                Map.of(), orderId, mod)).andExpect(status().isOk()));
        UUID p2 = UUID.fromString(acc.get("payment").get("paymentId").asString());
        verify(orderId, p2, submitProof(orderId, p2, ownerToken()), sitterToken()).andExpect(status().isOk());
        UUID addedVisit = jdbc.queryForObject("SELECT id FROM visits WHERE order_id = ? AND service_date = ?", UUID.class, orderId,
                java.sql.Date.valueOf(added));
        assertThat(itemTitles(addedVisit)).containsExactly("餵乾糧", "換貓砂", "梳毛");
    }

    @Test
    @DisplayName("PRD-028 AC-1.1（CQ-7 / EX-14）空白略過、strip 後截至 50 字、只取前 20 項、非字串元素略過；方案下架 → 空清單")
    void normalization() throws Exception {
        StringBuilder arr = new StringBuilder("[\"  \", 123, null, \"  " + "長".repeat(55) + "  \"");
        for (int i = 1; i <= 25; i++) {
            arr.append(",\"任務").append(i).append("\"");
        }
        arr.append("]");
        defaultTasks(planA, arr.toString());
        UUID orderId = book(today().plusDays(1));
        List<String> titles = itemTitles(visits(orderId).get(0));
        assertThat(titles).hasSize(20);
        assertThat(titles.get(0)).isEqualTo("長".repeat(50));
        assertThat(titles.get(1)).isEqualTo("任務1");
        assertThat(titles.get(19)).isEqualTo("任務19");

        jdbc.update("UPDATE service_plans SET is_active = false WHERE id = ?", planB);
        defaultTasks(planB, "[\"a\"]");
        // 下架方案無法預約，直接驗證查詢介面
        assertThat(servicePlanQuery.findDefaultTasks(java.util.Set.of(planB)).get(planB)).isEmpty();
    }

    @Autowired
    private ServicePlanQuery servicePlanQuery;

    @Test
    @DisplayName("PRD-028 AC-1.1（§4.8 第 4 點）60 日 × 3 次 × 20 項以 JDBC batch 寫入：E1 全部 JDBC 語句 ≤ 50")
    void batchInsert() throws Exception {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 1; i <= 20; i++) {
            arr.append(i == 1 ? "" : ",").append("\"項目").append(i).append("\"");
        }
        defaultTasks(planA, arr.append("]").toString());
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            dates.add(today().plusDays(i));
        }
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, dates, 3, 500 * 180, petA))).andExpect(status().isCreated());
        long statements = stats.getPrepareStatementCount();
        stats.setStatisticsEnabled(false);
        assertThat(count("SELECT count(*) FROM visit_checklist_items")).isEqualTo(3600);
        assertThat(statements).as("E1 全部 JDBC 語句（含 3,600 列檢核項批次）").isLessThanOrEqualTo(50);
        assertThat(stats.getEntityInsertCount()).isGreaterThanOrEqualTo(3600 + 180);
    }

    @Test
    @DisplayName("PRD-028 AC-1.1（EX-15）帶入寫入失敗 → 行程建立整筆回滾，E1 回 500 INTERNAL_ERROR，無訂單 / 行程 / 檢核項")
    void rollback() throws Exception {
        defaultTasks(planA, "[\"a\"]");
        jdbc.execute("CREATE OR REPLACE FUNCTION fulfill_test_fail() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'seed failure' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fulfill_test_fail_trg BEFORE INSERT ON visit_checklist_items FOR EACH ROW EXECUTE FUNCTION fulfill_test_fail()");
        try {
            mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(today().plusDays(1)), 1, 500, petA)))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        } finally {
            jdbc.execute("DROP TRIGGER fulfill_test_fail_trg ON visit_checklist_items");
            jdbc.execute("DROP FUNCTION fulfill_test_fail()");
        }
        assertThat(count("SELECT count(*) FROM orders")).isZero();
        assertThat(count("SELECT count(*) FROM visits")).isZero();
        assertThat(count("SELECT count(*) FROM visit_checklist_items")).isZero();
    }
}
