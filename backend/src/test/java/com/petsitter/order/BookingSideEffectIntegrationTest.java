package com.petsitter.order;

import com.petsitter.application.service.FavoriteSitterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** E1 commit 後副作用（PRD-005 AC-3.8、SD-ORDER §3.6.4）。 */
@DisplayName("E1 BookingSideEffectIntegrationTest")
class BookingSideEffectIntegrationTest extends OrderIntegrationTestBase {

    @MockitoSpyBean
    private FavoriteSitterService favoriteSitterService;

    private int favorites() {
        return count("SELECT count(*) FROM favorite_sitters WHERE owner_id = ? AND sitter_id = ? AND is_deleted = false", owner, sitter);
    }

    @Test
    @DisplayName("PRD-005 AC-3.8 送單後自動加入我的最愛（已存在不重複）；保母站內通知寫入，Email 依偏好")
    void favoriteAndNotification() throws Exception {
        book(today().plusDays(2));
        book(today().plusDays(3));
        assertThat(favorites()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'NEW_BOOKING_REQUEST' AND email_status = 'PENDING'", sitter))
                .isEqualTo(2);
        jdbc.update("INSERT INTO notification_preferences (id, user_id, category, enable_in_app, enable_email) VALUES (?, ?, 'ORDER_AFFAIR', true, false)",
                UUID.randomUUID(), sitter);
        book(today().plusDays(4));
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND email_status = 'SKIPPED_PREFERENCE'", sitter)).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-005 AC-3.8 已達 50 位上限略過；加入失敗不回滾訂單")
    void limitAndFailureDoNotRollback() throws Exception {
        for (int i = 0; i < 50; i++) {
            UUID s = fx.user("fav-" + i + "-" + UUID.randomUUID() + "@test.com", "SITTER");
            jdbc.update("INSERT INTO favorite_sitters (id, owner_id, sitter_id) VALUES (?, ?, ?)", UUID.randomUUID(), owner, s);
        }
        book(today().plusDays(2));
        assertThat(favorites()).isZero();
        jdbc.update("DELETE FROM favorite_sitters");
        doThrow(new RuntimeException("boom")).when(favoriteSitterService).addIfAbsent(any(), any());
        book(today().plusDays(2));
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(2);
    }
}
