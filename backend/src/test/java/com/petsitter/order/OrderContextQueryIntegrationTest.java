package com.petsitter.order;

import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.order.OrderItemSource;
import com.petsitter.domain.model.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SD-ORDER {@code OrderQueryService.getOrderContexts}（SD-FULFILL 讀取訂單脈絡的唯一入口）直接測試：
 * 欄位內容、群組排序與毛孩綁定、方案標題快照、adminReadable、未知 id 省略、空集合、批次上限 200。
 */
@DisplayName("SD-ORDER getOrderContexts OrderContextQueryIntegrationTest")
class OrderContextQueryIntegrationTest extends OrderIntegrationTestBase {

    @Autowired
    private OrderQueryService orderQueryService;

    @Test
    @DisplayName("getOrderContexts：狀態、雙方 id 與顯示名、群組（BOOKING、sortIndex）與毛孩綁定、報價後方案標題快照；未知 id 省略")
    void contents() throws Exception {
        UUID orderId = book(today().plusDays(5));
        quote(orderId);
        UUID unknown = UUID.randomUUID();
        Map<UUID, OrderQueryService.OrderContextView> m = orderQueryService.getOrderContexts(Set.of(orderId, unknown));
        assertThat(m).containsOnlyKeys(orderId);
        OrderQueryService.OrderContextView v = m.get(orderId);
        assertThat(v.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(v.ownerId()).isEqualTo(owner);
        assertThat(v.sitterId()).isEqualTo(sitter);
        assertThat(v.ownerDisplayName()).isNotBlank();
        assertThat(v.sitterDisplayName()).isNotBlank();
        assertThat(v.createdAt()).isNotNull();
        assertThat(v.adminReadable()).isFalse();
        assertThat(v.items()).hasSize(1);
        OrderQueryService.ItemRef item = v.items().get(0);
        assertThat(item.planId()).isEqualTo(planA);
        assertThat(item.source()).isEqualTo(OrderItemSource.BOOKING);
        assertThat(item.sortIndex()).isZero();
        assertThat(v.petsByItem().get(item.orderItemId())).extracting(OrderQueryService.PetRef::petId).containsExactly(petA);
        assertThat(v.petsByItem().get(item.orderItemId()).get(0).name()).isEqualTo("咪咪");
        assertThat(v.planTitles()).containsEntry(planA, "到府餵食");
    }

    @Test
    @DisplayName("getOrderContexts：毛孩刪除後 PetRef.deleted = true；DISPUTED → adminReadable = true；空集合 → 空 Map")
    void deletedPetAndAdminReadable() throws Exception {
        UUID orderId = confirmedOrder(today().plusDays(5));
        jdbc.update("UPDATE pets SET is_deleted = true WHERE id = ?", petA);
        jdbc.update("UPDATE orders SET status = 'DISPUTED', disputed_at = now() WHERE id = ?", orderId);
        OrderQueryService.OrderContextView v = orderQueryService.getOrderContexts(Set.of(orderId)).get(orderId);
        assertThat(v.adminReadable()).isTrue();
        List<OrderQueryService.PetRef> pets = v.petsByItem().get(v.items().get(0).orderItemId());
        assertThat(pets).hasSize(1);
        assertThat(pets.get(0).deleted()).isTrue();
        assertThat(orderQueryService.getOrderContexts(Set.of())).isEmpty();
        assertThat(orderQueryService.getOrderContexts(Set.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("getOrderContexts：單次最多 200 筆，201 筆 → IllegalArgumentException；200 筆可")
    void batchLimit() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < OrderQueryService.MAX_CONTEXT_BATCH; i++) {
            ids.add(UUID.randomUUID());
        }
        assertThat(orderQueryService.getOrderContexts(ids)).isEmpty();
        ids.add(UUID.randomUUID());
        assertThatThrownBy(() -> orderQueryService.getOrderContexts(ids)).isInstanceOf(IllegalArgumentException.class);
    }
}
