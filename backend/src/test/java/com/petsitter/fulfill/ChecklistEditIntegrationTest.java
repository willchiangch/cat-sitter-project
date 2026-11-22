package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL ChecklistEditIntegrationTest（F9–F11）")
class ChecklistEditIntegrationTest extends FulfillIntegrationTestBase {

    @Test
    @DisplayName("PRD-028 AC-1.2 新增 strip 後加在最後、created_by / updated_by = token 保母、不寫 log / 不通知；同 key 回放仍 2 項；他行程與方案預設不變")
    void add() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        int logs = logActions(orderId).size();
        int notes = count("SELECT count(*) FROM notifications");
        String k = key();
        JsonNode first = body(addItem(orderId, vs.get(0), sitterToken(), k, "  餵乾糧 50g  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("餵乾糧 50g")).andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.isCompleted").value(false)));
        addItem(orderId, vs.get(0), sitterToken(), key(), "換貓砂").andExpect(status().isCreated()).andExpect(jsonPath("$.sortOrder").value(1));
        assertThat(body(addItem(orderId, vs.get(0), sitterToken(), k, "  餵乾糧 50g  ").andExpect(status().isCreated()))).isEqualTo(first);
        assertThat(itemTitles(vs.get(0))).containsExactly("餵乾糧 50g", "換貓砂");
        assertThat(itemTitles(vs.get(1))).isEmpty();
        assertThat(count("SELECT count(*) FROM visit_checklist_items WHERE created_source = 'SITTER' AND created_by = ? AND updated_by = ?", sitter, sitter)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT default_tasks::text FROM service_plans WHERE id = ?", String.class, planA)).isEqualTo("[]");
        assertThat(logActions(orderId)).hasSize(logs);
        assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(notes);
    }

    @Test
    @DisplayName("PRD-028 AC-1.3 刪除後重編、重送 404；排序 [I3, I2] 生效；集合不符 / 重複 / 他行程 id / 非 UUID → 400 field=itemIds；updated_by 寫入")
    void deleteAndReorder() throws Exception {
        defaultTasks(planA, "[\"餵乾糧\",\"換貓砂\",\"餵藥\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        UUID v = vs.get(0);
        List<UUID> items = itemIds(v);
        UUID i1 = items.get(0);
        UUID i2 = items.get(1);
        UUID i3 = items.get(2);
        deleteAs(VISIT + "/checklist/items/{i}", sitterToken(), orderId, v, i1).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].sortOrder").value(0));
        deleteAs(VISIT + "/checklist/items/{i}", sitterToken(), orderId, v, i1).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT updated_by FROM visit_checklist_items WHERE id = ?", UUID.class, i2)).isEqualTo(sitter);
        OffsetDateTime before = jdbc.queryForObject("SELECT updated_at FROM visit_checklist_items WHERE id = ?", OffsetDateTime.class, i3);
        putJson(VISIT + "/checklist/order", sitterToken(), Map.of("itemIds", List.of(i3.toString(), i2.toString())), orderId, v)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].itemId").value(i3.toString()));
        assertThat(itemTitles(v)).containsExactly("餵藥", "換貓砂");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM visit_checklist_items WHERE id = ?", OffsetDateTime.class, i3)).isAfter(before);
        UUID otherItem = itemIds(vs.get(1)).get(0);
        for (Object bad : List.of(List.of(i3.toString()), List.of(i3.toString(), i2.toString(), i2.toString()),
                List.of(i3.toString(), otherItem.toString()), List.of("not-a-uuid", i2.toString()), "x")) {
            putJson(VISIT + "/checklist/order", sitterToken(), Map.of("itemIds", bad), orderId, v).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.payload.field").value("itemIds"));
        }
        assertThat(itemTitles(v)).containsExactly("餵藥", "換貓砂");
    }

    @Test
    @DisplayName("PRD-028 AC-1.4 DISPUTED 可新增；DONE / CLOSED / CANCELLED → 409 VISIT_STATE_CONFLICT；PENDING_PAYMENT → ORDER_STATE_CONFLICT；訂閱鎖定 201")
    void editableWindow() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1), d.plusDays(2), d.plusDays(3));
        List<UUID> vs = visits(orderId);
        setOrderStatus(orderId, "DISPUTED");
        addItem(orderId, vs.get(0), sitterToken(), key(), "a").andExpect(status().isCreated());
        setOrderStatus(orderId, "IN_PROGRESS");
        OffsetDateTime t = taipei(d, 9, 0);
        forceVisit(vs.get(1), "DONE", t, t.plusHours(1), "SITTER");
        forceClosed(vs.get(2), t);
        forceCancelled(vs.get(3), t);
        UUID item = itemIds(vs.get(0)).get(0);
        for (int i = 1; i <= 3; i++) {
            String expected = List.of("DONE", "CLOSED_BY_SYSTEM", "CANCELLED").get(i - 1);
            addItem(orderId, vs.get(i), sitterToken(), key(), "x").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentVisitStatus").value(expected));
            putJson(VISIT + "/checklist/order", sitterToken(), Map.of("itemIds", List.of()), orderId, vs.get(i))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT"));
        }
        jdbc.update("UPDATE visit_checklist_items SET visit_id = ? WHERE id = ?", vs.get(1), item);
        deleteAs(VISIT + "/checklist/items/{i}", sitterToken(), orderId, vs.get(1), item).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT"));

        UUID pp = book(d);
        quote(pp);
        addItem(pp, visits(pp).get(0), sitterToken(), key(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentStatus").value("PENDING_PAYMENT"));

        tier(sitter, "FREE");
        jdbc.update("UPDATE subscriptions SET expired_at = now() - interval '30 days' WHERE sitter_id = ?", sitter);
        addItem(orderId, vs.get(0), sitterToken(), key(), "鎖定也可").andExpect(status().isCreated());

    }

    @Test
    @DisplayName("PRD-028 AC-1.5 第 21 項 400 field=items max=20；空白 / 51 字 400 field=title、50 字 201；缺 key 400；DONE 且 20 項 → 409（先於上限）")
    void limits() throws Exception {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 1; i <= 20; i++) {
            arr.append(i == 1 ? "" : ",").append("\"t").append(i).append("\"");
        }
        defaultTasks(planA, arr.append("]").toString());
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d, d.plusDays(1));
        List<UUID> vs = visits(orderId);
        addItem(orderId, vs.get(0), sitterToken(), key(), "第21項").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("items")).andExpect(jsonPath("$.payload.max").value(20));
        assertThat(itemIds(vs.get(0))).hasSize(20);
        jdbc.update("DELETE FROM visit_checklist_items WHERE visit_id = ?", vs.get(1));
        addItem(orderId, vs.get(1), sitterToken(), key(), " 　 ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("title"));
        addItem(orderId, vs.get(1), sitterToken(), key(), "字".repeat(51)).andExpect(status().isBadRequest());
        addItem(orderId, vs.get(1), sitterToken(), key(), Map.of("title", 5)).andExpect(status().isBadRequest());
        addItem(orderId, vs.get(1), sitterToken(), key(), "字".repeat(50)).andExpect(status().isCreated());
        addItem(orderId, vs.get(1), sitterToken(), null, "x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("Idempotency-Key"));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        OffsetDateTime t = taipei(d, 9, 0);
        forceVisit(vs.get(0), "DONE", t, t.plusHours(1), "SITTER");
        addItem(orderId, vs.get(0), sitterToken(), key(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("PRD-028 AC-1.2（EX-11）F9 同 key 處理中 → 409 IDEMPOTENCY_IN_PROGRESS")
    void addInProgress() throws Exception {
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        UUID v = visits(orderId).get(0);
        String k = key();
        var hold = holdOrderLock(orderId, java.time.Duration.ofSeconds(2));
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return addItem(orderId, v, sitterToken(), k, "a").andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(500);
        addItem(orderId, v, sitterToken(), k, "a").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        hold.join();
        assertThat(first.join()).isEqualTo(201);
    }

    @Test
    @DisplayName("PRD-028 AC-1.3（§6.1）commit 時延遲唯一約束 ux_checklist_sort 違反 → 409 ORDER_BUSY（23505）")
    void deferredUniqueViolation() throws Exception {
        defaultTasks(planA, "[\"a\",\"b\"]");
        LocalDate d = today().plusDays(1);
        UUID orderId = confirmedOrder(d);
        UUID v = visits(orderId).get(0);
        List<UUID> items = itemIds(v);
        // 造出不連續排序（a=0、b=5），之後新增會得 sort 6；以觸發器在 INSERT 時把另一列改成 6 使 commit 時撞約束
        jdbc.update("UPDATE visit_checklist_items SET sort_order = 5 WHERE id = ?", items.get(1));
        jdbc.execute("CREATE OR REPLACE FUNCTION fulfill_test_dup() RETURNS trigger AS $$ BEGIN "
                + "UPDATE visit_checklist_items SET sort_order = NEW.sort_order WHERE visit_id = NEW.visit_id AND id <> NEW.id AND sort_order = 0; "
                + "RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fulfill_test_dup_trg AFTER INSERT ON visit_checklist_items FOR EACH ROW EXECUTE FUNCTION fulfill_test_dup()");
        try {
            addItem(orderId, v, sitterToken(), key(), "c").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_BUSY"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "1"));
        } finally {
            jdbc.execute("DROP TRIGGER fulfill_test_dup_trg ON visit_checklist_items");
            jdbc.execute("DROP FUNCTION fulfill_test_dup()");
        }
        assertThat(itemIds(v)).hasSize(2);
    }
}
