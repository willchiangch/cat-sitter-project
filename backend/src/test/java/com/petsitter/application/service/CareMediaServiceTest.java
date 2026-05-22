package com.petsitter.application.service;

import com.petsitter.application.dto.CareMediaDto;
import com.petsitter.domain.model.CareLog;
import com.petsitter.domain.model.CareMedia;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.CareLogRepository;
import com.petsitter.domain.repository.CareMediaRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-021: 照護媒體服務層測試")
class CareMediaServiceTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CareMediaService careMediaService;

    @MockitoSpyBean
    private CareMediaRepository careMediaRepository;

    @MockitoBean
    private MediaStorageService mediaStorageService;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private SystemConfigService systemConfigService;

    @Autowired
    private CareLogRepository careLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    private UUID sitterId;
    private UUID ownerId;
    private MultipartFile dummyFile;

    @BeforeEach
    void setUp() {
        Mockito.reset(careMediaRepository);
        careMediaRepository.deleteAll();
        careLogRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        // 建立真實的使用者與訂閱以滿足外鍵約束與 gating 檢查
        User sitter = userRepository.save(User.builder()
                .email("sitter-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .role("SITTER")
                .build());

        subscriptionRepository.save(Subscription.builder()
                .sitter(sitter)
                .planTier("FREE")
                .build());

        User owner = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .role("OWNER")
                .build());

        sitterId = sitter.getId();
        ownerId = owner.getId();
        dummyFile = new MockMultipartFile("file", "test.jpg", "image/jpeg", "test image content".getBytes());

        when(systemConfigService.getMediaLimit()).thenReturn(20);
    }

    @Test
    @DisplayName("Scenario 5: uploadMedia 數量限制拋出例外 (AC-6)")
    void should_ThrowException_When_MediaLimitExceeded() {
        // Given: 模擬上限為 2 筆，且目前已上傳 2 筆
        when(systemConfigService.getMediaLimit()).thenReturn(2);

        CareMedia media1 = CareMedia.builder().id(UUID.randomUUID()).sitterId(sitterId).ownerId(ownerId).caption("1").mediaUrl("url1").mediaType("image").build();
        CareMedia media2 = CareMedia.builder().id(UUID.randomUUID()).sitterId(sitterId).ownerId(ownerId).caption("2").mediaUrl("url2").mediaType("image").build();
        careMediaRepository.saveAll(List.of(media1, media2));

        assertThat(careMediaRepository.countBySitterIdAndOwnerId(sitterId, ownerId)).isEqualTo(2);

        // When & Then: 嘗試上傳第 3 筆應拋出例外
        assertThatThrownBy(() -> careMediaService.uploadMedia(sitterId, ownerId, "3", "image", dummyFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("媒體數量已達上限");

        verify(mediaStorageService, never()).uploadMedia(any(), any(), any(), any());
        assertThat(careMediaRepository.countBySitterIdAndOwnerId(sitterId, ownerId)).isEqualTo(2);
    }

    @Test
    @DisplayName("Scenario 6: uploadMedia DB 儲存失敗時的 GCS 反向補償清除")
    void should_TriggerCompensationDelete_When_DbSaveFails() {
        // Given
        String expectedUrl = "https://gcs.com/test.jpg";
        when(mediaStorageService.uploadMedia(eq(sitterId), eq(ownerId), any(UUID.class), eq(dummyFile)))
                .thenReturn(expectedUrl);

        doThrow(new RuntimeException("Simulated DB error")).when(careMediaRepository).save(any(CareMedia.class));

        // When & Then: 嘗試上傳應拋出例外
        assertThatThrownBy(() -> careMediaService.uploadMedia(sitterId, ownerId, "caption", "image", dummyFile))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB save failed");

        verify(mediaStorageService, times(1)).deleteMedia(expectedUrl);

        List<CareLog> logs = careLogRepository.findAll();
        assertThat(logs).isNotEmpty();
        boolean hasFailedLog = logs.stream()
                .anyMatch(l -> "UPLOAD_MEDIA".equals(l.getAction()) && "FAILED".equals(l.getStatus()));
        assertThat(hasFailedLog).isTrue();
    }

    @Test
    @DisplayName("Scenario 7: deleteMedia 正常刪除與 GCS 同步刪除 (AC-7)")
    void should_DeleteFromDbAndStorage_When_DeleteMedia() {
        // Given: 存在一筆媒體
        CareMedia media = CareMedia.builder()
                .id(UUID.randomUUID())
                .sitterId(sitterId)
                .ownerId(ownerId)
                .caption("caption")
                .mediaUrl("https://gcs.com/delete-me.jpg")
                .mediaType("image")
                .build();
        media = careMediaRepository.save(media);

        // When
        careMediaService.deleteMedia(sitterId, media.getId());

        // Then: DB 中不應存在該媒體
        assertThat(careMediaRepository.findById(media.getId())).isEmpty();

        verify(mediaStorageService, times(1)).deleteMedia("https://gcs.com/delete-me.jpg");
        verify(notificationService, times(1)).sendNotification(eq(ownerId), contains("保母已移除部分媒體"));

        List<CareLog> logs = careLogRepository.findAll();
        boolean hasDeleteLog = logs.stream()
                .anyMatch(l -> "DELETE_MEDIA".equals(l.getAction()) && "SUCCESS".equals(l.getStatus()));
        assertThat(hasDeleteLog).isTrue();
    }
}
