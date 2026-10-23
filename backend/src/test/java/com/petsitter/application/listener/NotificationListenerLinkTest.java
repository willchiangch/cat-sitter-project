package com.petsitter.application.listener;

import com.petsitter.application.event.VisitNotificationEvent;
import com.petsitter.application.service.NotificationService;
import com.petsitter.domain.event.KycReviewedEvent;
import com.petsitter.domain.event.MediaExpiryWarningEvent;
import com.petsitter.domain.event.PaymentProofSubmittedEvent;
import com.petsitter.domain.event.PaymentRejectedEvent;
import com.petsitter.domain.event.PaymentVerifiedEvent;
import com.petsitter.domain.event.SitterSuspendedEvent;
import com.petsitter.domain.event.SitterUnsuspendedEvent;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迴歸 (Backlog 第 3 批 D1)：通知的 linkUrl 必須是前端真的存在的路由。
 * 過去飼主端付款確認/退回、照護日誌通知寫 /client/orders、影像到期提醒寫 /visits/report?orderId=，
 * 前端 routes.tsx 根本沒有這兩條路徑。這裡直接解析 frontend/src/routes.tsx 的 path="..."，
 * 逐一比對每個監聽器產生的連結，之後前端改路由或後端寫錯連結都會在這裡紅。
 */
@ExtendWith(MockitoExtension.class)
class NotificationListenerLinkTest {

    private static final Path ROUTES_TSX = Path.of("..", "frontend", "src", "routes.tsx");
    private static List<Pattern> frontendRoutes;

    @Mock
    private NotificationService notificationService;
    @Mock
    private OrderRepository orderRepository;
    @InjectMocks
    private NotificationListener listener;

    private UUID orderId;

    @BeforeAll
    static void loadFrontendRoutes() throws IOException {
        assertThat(ROUTES_TSX).as("需要 monorepo 同層的 frontend/src/routes.tsx").exists();
        String source = Files.readString(ROUTES_TSX);
        Matcher m = Pattern.compile("path=\"([^\"]+)\"").matcher(source);
        frontendRoutes = new ArrayList<>();
        while (m.find()) {
            String regex = m.group(1).replaceAll(":[A-Za-z]+", "[^/]+");
            frontendRoutes.add(Pattern.compile("^" + regex + "$"));
        }
        assertThat(frontendRoutes).as("routes.tsx 應解析出路由").hasSizeGreaterThan(10);
    }

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
    }

    private void stubOrder() {
        Order order = Order.builder()
                .id(orderId)
                .owner(User.builder().id(UUID.randomUUID()).build())
                .sitter(User.builder().id(UUID.randomUUID()).build())
                .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
    }

    private String capturedLink() {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(any(), anyString(), anyString(), anyString(),
                link.capture(), anyString());
        return link.getValue();
    }

    private static void assertIsFrontendRoute(String link) {
        assertThat(link).doesNotStartWith("/client/");
        assertThat(frontendRoutes)
                .as("linkUrl %s 必須對應 frontend/src/routes.tsx 的某條路由", link)
                .anyMatch(p -> p.matcher(link).matches());
    }

    @Test
    @DisplayName("付款確認（首次/補款）→ 飼主訂單詳情 /owner/orders/{orderId}")
    void paymentVerifiedLinksToOwnerOrderDetail() {
        stubOrder();
        listener.onPaymentVerified(new PaymentVerifiedEvent(orderId, UUID.randomUUID(), false));
        String link = capturedLink();
        assertThat(link).isEqualTo("/owner/orders/" + orderId);
        assertIsFrontendRoute(link);
    }

    @Test
    @DisplayName("付款憑證被退回 → 飼主訂單詳情（在詳情頁重傳憑證）")
    void paymentRejectedLinksToOwnerOrderDetail() {
        stubOrder();
        listener.onPaymentRejected(new PaymentRejectedEvent(orderId, UUID.randomUUID(), "金額不符"));
        String link = capturedLink();
        assertThat(link).isEqualTo("/owner/orders/" + orderId);
        assertIsFrontendRoute(link);
    }

    @Test
    @DisplayName("照護日誌回報（Check-in/Check-out）→ 飼主訂單詳情")
    void visitNotificationLinksToOwnerOrderDetail() {
        listener.handleVisitNotification(new VisitNotificationEvent(UUID.randomUUID(), orderId, "msg"));
        String link = capturedLink();
        assertThat(link).isEqualTo("/owner/orders/" + orderId);
        assertIsFrontendRoute(link);
    }

    @Test
    @DisplayName("照護影像即將過期 → 飼主訂單詳情（過去是不存在的 /visits/report?orderId=）")
    void mediaExpiryLinksToOwnerOrderDetail() {
        listener.onMediaExpiryWarning(new MediaExpiryWarningEvent(orderId, UUID.randomUUID(), OffsetDateTime.now()));
        String link = capturedLink();
        assertThat(link).isEqualTo("/owner/orders/" + orderId);
        assertIsFrontendRoute(link);
    }

    @Test
    @DisplayName("保母端：收到付款憑證 → /sitter/orders；KYC 結果/停權/復權 → /sitter/kyc，皆為真實路由")
    void sitterLinksAreFrontendRoutes() {
        stubOrder();
        listener.onPaymentProofSubmitted(new PaymentProofSubmittedEvent(orderId, UUID.randomUUID(), "k"));
        assertThat(capturedLink()).isEqualTo("/sitter/orders");

        for (Runnable r : List.<Runnable>of(
                () -> listener.onKycReviewed(new KycReviewedEvent(UUID.randomUUID(), UUID.randomUUID(), "APPROVED", null)),
                () -> listener.onSitterSuspended(new SitterSuspendedEvent(UUID.randomUUID(), "r")),
                () -> listener.onSitterUnsuspended(new SitterUnsuspendedEvent(UUID.randomUUID())))) {
            org.mockito.Mockito.clearInvocations(notificationService);
            r.run();
            assertThat(capturedLink()).isEqualTo("/sitter/kyc");
        }
        assertIsFrontendRoute("/sitter/orders");
        assertIsFrontendRoute("/sitter/kyc");
    }
}
