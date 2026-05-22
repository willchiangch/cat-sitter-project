package com.petsitter.application.service;

import com.petsitter.application.dto.CareNoteDto;
import com.petsitter.application.dto.CareNoteItemDto;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-021: 照護記事服務層測試")
class CareNoteServiceTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CareNoteService careNoteService;

    @Autowired
    private CareNoteRepository careNoteRepository;

    @Autowired
    private CareNoteItemRepository careNoteItemRepository;

    @Autowired
    private CareNoteTemplateRepository templateRepository;

    @Autowired
    private CareNoteTemplateItemRepository templateItemRepository;

    @Autowired
    private CareLogRepository careLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private SystemConfigService systemConfigService;

    private UUID sitterId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        careNoteItemRepository.deleteAll();
        templateItemRepository.deleteAll();
        templateRepository.deleteAll();
        careNoteRepository.deleteAll();
        careLogRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        // 建立真實的使用者以滿足外鍵約束
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

        // 預設 config limit
        when(systemConfigService.getTemplateLimit()).thenReturn(10);
        when(systemConfigService.getMediaLimit()).thenReturn(20);
    }

    @Test
    @DisplayName("Scenario 1: getCareNote 首次初始化 (AC-1)")
    void should_InitializeEmptyCareNote_When_FirstAccess() {
        // When
        CareNoteDto result = careNoteService.getCareNote(sitterId, ownerId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getCareNoteId()).isNull();
        assertThat(result.getSitterId()).isEqualTo(sitterId);
        assertThat(result.getOwnerId()).isEqualTo(ownerId);
        assertThat(result.getSections()).containsOnlyKeys(
                "SERVICE", "CONTACT", "WARNING", "PREFERENCE", "HOSPITAL", "OTHER"
        );
        for (String section : result.getSections().keySet()) {
            assertThat(result.getSections().get(section)).isEmpty();
        }
    }

    @Test
    @DisplayName("Scenario 2: saveCareNote 的 Recreate-on-Save 覆蓋重排 (AC-2, AC-4)")
    void should_PerformRecreateOnSave_When_SaveCareNote() {
        // Given: 建立初始記事本（2 筆舊項目）
        List<CareNoteItemDto> oldItems = List.of(
                CareNoteItemDto.builder().sectionType("SERVICE").title("舊標題1").content("舊內容1").build(),
                CareNoteItemDto.builder().sectionType("CONTACT").title("舊標題2").content("舊內容2").build()
        );
        UUID firstNoteId = careNoteService.saveCareNote(sitterId, ownerId, oldItems);

        List<CareNoteItem> beforeItems = careNoteItemRepository.findByCareNoteIdOrderBySortOrderAsc(firstNoteId);
        assertThat(beforeItems).hasSize(2);

        // When: saveCareNote（3 筆新項目，跨 SERVICE 與 CONTACT）
        List<CareNoteItemDto> newItems = List.of(
                CareNoteItemDto.builder().sectionType("SERVICE").title("新標題1").content("新內容1").build(),
                CareNoteItemDto.builder().sectionType("SERVICE").title("新標題2").content("新內容2").build(),
                CareNoteItemDto.builder().sectionType("CONTACT").title("新標題3").content("新內容3").build()
        );
        UUID secondNoteId = careNoteService.saveCareNote(sitterId, ownerId, newItems);

        // Then
        assertThat(secondNoteId).isEqualTo(firstNoteId);

        // 舊 items 不存在
        List<CareNoteItem> afterItems = careNoteItemRepository.findByCareNoteIdOrderBySortOrderAsc(secondNoteId);
        assertThat(afterItems).hasSize(3);

        // 新 items sort_order 依區塊從 0 遞增
        List<CareNoteItem> serviceItems = afterItems.stream()
                .filter(i -> "SERVICE".equals(i.getSectionType()))
                .toList();
        assertThat(serviceItems).hasSize(2);
        assertThat(serviceItems.get(0).getSortOrder()).isEqualTo(0);
        assertThat(serviceItems.get(0).getTitle()).isEqualTo("新標題1");
        assertThat(serviceItems.get(1).getSortOrder()).isEqualTo(1);
        assertThat(serviceItems.get(1).getTitle()).isEqualTo("新標題2");

        List<CareNoteItem> contactItems = afterItems.stream()
                .filter(i -> "CONTACT".equals(i.getSectionType()))
                .toList();
        assertThat(contactItems).hasSize(1);
        assertThat(contactItems.get(0).getSortOrder()).isEqualTo(0);
        assertThat(contactItems.get(0).getTitle()).isEqualTo("新標題3");

        // 驗證稽核日誌
        List<CareLog> logs = careLogRepository.findAll();
        assertThat(logs).isNotEmpty();
        boolean hasSaveLog = logs.stream()
                .anyMatch(l -> "SAVE_CARE_NOTE".equals(l.getAction()) && "SUCCESS".equals(l.getStatus()));
        assertThat(hasSaveLog).isTrue();

        // 驗證通知
        verify(notificationService, atLeastOnce()).sendNotification(eq(ownerId), contains("記事本已更新"));
    }

    @Test
    @DisplayName("Scenario 3: createTemplate 數量限制拋出例外 (AC-3)")
    void should_ThrowException_When_TemplateLimitExceeded() {
        // Given: 模擬上限為 2 筆
        when(systemConfigService.getTemplateLimit()).thenReturn(2);

        List<CareNoteItemDto> items = List.of(
                CareNoteItemDto.builder().sectionType("SERVICE").title("項目").content("內容").build()
        );

        careNoteService.createTemplate(sitterId, "模板1", items);
        careNoteService.createTemplate(sitterId, "模板2", items);

        assertThat(templateRepository.countBySitterId(sitterId)).isEqualTo(2);

        // When & Then: 嘗試建立第 3 個模板應拋出例外
        assertThatThrownBy(() -> careNoteService.createTemplate(sitterId, "模板3", items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模板數量已達上限");

        // DB count 維持 2
        assertThat(templateRepository.countBySitterId(sitterId)).isEqualTo(2);
    }

    @Test
    @DisplayName("Scenario 4: applyTemplate 的 Append-Only 追加模式 (AC-4)")
    void should_AppendItemsInOrder_When_ApplyTemplate() {
        // Given: 建立含 1 筆舊項目的記事本
        List<CareNoteItemDto> noteItems = List.of(
                CareNoteItemDto.builder().sectionType("SERVICE").title("舊項目").content("舊內容").build()
        );
        UUID noteId = careNoteService.saveCareNote(sitterId, ownerId, noteItems);

        // 建立含 2 個 SERVICE 項目的範本
        List<CareNoteItemDto> templateItems = List.of(
                CareNoteItemDto.builder().sectionType("SERVICE").title("範本項目1").content("內容1").build(),
                CareNoteItemDto.builder().sectionType("SERVICE").title("範本項目2").content("內容2").build()
        );
        UUID templateId = careNoteService.createTemplate(sitterId, "標準模板", templateItems);

        // When: 套用該範本
        careNoteService.applyTemplate(sitterId, ownerId, templateId);

        // Then
        List<CareNoteItem> finalItems = careNoteItemRepository.findByCareNoteIdOrderBySortOrderAsc(noteId);
        assertThat(finalItems).hasSize(3);

        // 舊項目保留，且 sort_order = 0
        CareNoteItem oldItem = finalItems.get(0);
        assertThat(oldItem.getTitle()).isEqualTo("舊項目");
        assertThat(oldItem.getSortOrder()).isEqualTo(0);

        // 新項目追加且 sort_order 接續為 1 與 2
        CareNoteItem newItem1 = finalItems.get(1);
        assertThat(newItem1.getTitle()).isEqualTo("範本項目1");
        assertThat(newItem1.getSortOrder()).isEqualTo(1);

        CareNoteItem newItem2 = finalItems.get(2);
        assertThat(newItem2.getTitle()).isEqualTo("範本項目2");
        assertThat(newItem2.getSortOrder()).isEqualTo(2);

        // 驗證日誌與通知
        List<CareLog> logs = careLogRepository.findAll();
        boolean hasApplyLog = logs.stream()
                .anyMatch(l -> "APPLY_TEMPLATE".equals(l.getAction()) && "SUCCESS".equals(l.getStatus()));
        assertThat(hasApplyLog).isTrue();

        verify(notificationService, atLeastOnce()).sendNotification(eq(ownerId), contains("記事本已更新"));
    }

    @Test
    @DisplayName("Scenario 10: Advisory Lock 並發超量防護 (SD-021 §1.5)")
    void should_PreventOverLimit_When_ConcurrentCreate() throws InterruptedException, ExecutionException {
        // Given: 模擬上限為 2 筆，目前已建立 1 筆
        when(systemConfigService.getTemplateLimit()).thenReturn(2);
        List<CareNoteItemDto> items = List.of(
                CareNoteItemDto.builder().sectionType("SERVICE").title("項目").content("內容").build()
        );
        careNoteService.createTemplate(sitterId, "初始模板", items);
        assertThat(templateRepository.countBySitterId(sitterId)).isEqualTo(1);

        // When: 兩個執行緒同時呼叫 createTemplate 嘗試新增第 2 與第 3 個模板
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Callable<UUID>> tasks = List.of(
                () -> careNoteService.createTemplate(sitterId, "並發模板A", items),
                () -> careNoteService.createTemplate(sitterId, "並發模板B", items)
        );

        List<Future<UUID>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        int successCount = 0;
        int failureCount = 0;
        for (Future<UUID> future : futures) {
            try {
                future.get();
                successCount++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IllegalArgumentException) {
                    failureCount++;
                } else {
                    throw e;
                }
            }
        }

        // Then: 只有一筆會成功，另一筆應超限失敗
        assertThat(successCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(1);

        // 總數不得超過上限 (2 筆)
        assertThat(templateRepository.countBySitterId(sitterId)).isEqualTo(2);
    }
}
