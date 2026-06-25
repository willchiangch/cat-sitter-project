# SD-008: 服務執行與 Check-in 實作報告

本文件紀錄了 **SD-008: 服務執行與 Check-in** 的前後端實作完成狀況，以及修復並通過 E2E 測試的詳細過程。

---

## 1. 核心實作目標

- **行程狀態機流轉**：保母點擊 **Check-in (開始服務)**，行程（Visit）狀態從 `PENDING` 轉為 `IN_PROGRESS`，且若是行程首日會自動將訂單（Order）狀態從 `CONFIRMED` 轉為 `IN_PROGRESS`。
- **日誌填寫與結束**：保母在 `IN_PROGRESS` 期間可撰寫文字日誌與上傳多媒體（受方案額度限制）。服務結束後點擊 **Check-out (完成服務)**，狀態轉為 `DONE`，文字日誌編輯與媒體管理變為唯讀。
- **正式送出**：行程 `DONE` 後，保母可正式提交日誌，狀態轉為 `SUBMITTED`（已送出且完全唯讀），並非同步發送通知給飼主。

---

## 2. Bug 修復紀錄與 E2E 測試綠燈

在此次任務中，我們主要定位並修復了兩個阻塞 E2E 測試運行的關鍵問題，並順利讓測試完全通過。

### 2.1. 修正 E2E Mock 路由掛起問題
- **問題**：原先的 `frontend/e2e/service-execution.spec.ts` 中分別對同一 URL `**/api/visits/.../report` 註冊了兩次 Mock（一次 GET，一次 PUT）。在 Playwright 中，後面註冊的 PUT 路由攔截器覆蓋了前者，且當接收到 GET 請求時，因條件不符合且未調用 `route.continue()`，導致 GET 請求被掛起（React Query 一直處於 loading 狀態，畫面卡死在「正在載入照護日誌...」）。
- **修復**：將兩者合併為單一路由攔截器，根據 HTTP Method 進行分流處理，其他不符條件的請求均安全回退至 `route.continue()`。
  
  ```typescript
  // frontend/e2e/service-execution.spec.ts
  await page.route('**/api/visits/2624511e-3f10-4376-b81e-7fb02e615dda/report', async (route) => {
    const method = route.request().method();
    if (method === 'GET') {
      await route.fulfill({ ... });
    } else if (method === 'PUT') {
      await route.fulfill({ ... });
    } else {
      await route.continue();
    }
  });
  ```

### 2.2. 修正 `VisitReportManager.tsx` 中的日誌提交阻擋
- **問題**：在 [VisitReportManager.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/VisitReportManager.tsx#L264-L281) 中，點擊「正式送出日誌」按鈕會觸發 `handleSubmitReport`。然而其第一行寫了 `if (!isEditable) return;`。
  當保母執行 Check-out 後，行程狀態會流轉為 `DONE`，此時 `isEditable`（定義為在 `IN_PROGRESS` 且未送出）會變為 `false`，這導致雖然畫面上渲染了「正式送出」按鈕，但點擊該按鈕會被直接 return 阻擋，無法發送 API 與顯示成功提示。
- **修復**：將條件從 `!isEditable` 修改為基於提交狀態與行程狀態的防呆判定：
  
  ```typescript
  // frontend/src/pages/sitter/VisitReportManager.tsx
  const handleSubmitReport = async () => {
    if (isSubmitted || report?.visitStatus !== 'DONE') return;
    // ...
  };
  ```

---

## 3. 新增優化與 Action Items 實作

根據專案精益求精的要求，本階段已完成以下優化與追加項目：

### 3.1. 補齊 /start 與 /end 冪等性（Idempotency-Key）支援
- **後端 Controller/Service**：在 `startVisit` 與 `endVisit` API 新增必填的 `Idempotency-Key` 請求標頭校驗，並於 Service 呼叫 `idempotencyService.checkAndConsume`。若重複提交，將回傳 `409 CONFLICT` 衝突（`MSG_DATA_IDEMPOTENCY_CONFLICT`）。
- **前端支援**：在前端 React mutations 及 UI 元件中使用 `useRef` 保存並隨機生成 `startKeyRef` 和 `endKeyRef`。成功發送 API 後重置，失敗時則保留重試。
- **後端測試**：在 `VisitReportControllerTest.java` 補齊對應的重複請求冪等性驗證。

### 3.2. 事件驅動通知（消弭幽靈通知風險）
- **機制**：為避免在 `@Transactional` 中發送通知後發生事務回滾，導致飼主收到「幽靈通知」的問題，我們將 `startVisit/endVisit` 的通知重構為事件驅動。
- **實作**：
  1. 新增 `VisitNotificationEvent` 事件。
  2. 於 `NotificationService` 中改用 `@Async` 與 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` 監聽此事件。
  3. `VisitReportService` 改為發布該事件，以確保只在事務安全提交後才非同步發送通知。

### 3.3. 明確持久化意圖與後續日 Check-in 測試
- **明確持久化**：在 `VisitReportService.startVisit` 中追加呼叫 `orderRepository.save(order)`。
- **後續日測試**：於 `VisitReportControllerTest` 中新增 `should_StartVisit_OnSubsequentDay_Successfully` 測試情境，模擬訂單已經在 `IN_PROGRESS` 狀態下的第二日（或後續日）Check-in 行為，確認行程與訂單狀態運作無誤。
- **文檔備註**：
  * 更新 [Visit.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/Visit.java#L22) 的 status 註解，補充 `IN_PROGRESS`。
  * 更新 `SD-008-service-execution.md` 文檔，備註離線補送機制延後至 Open Beta 實作。

### 3.4. 追加修正與重構（Bug & Clean Code Fixes）
- **UTC 時區修正**：將 `VisitReportService.java` 中原先缺漏 `ZoneOffset.UTC` 的 `OffsetDateTime.now()` 統一替換成 `OffsetDateTime.now(ZoneOffset.UTC)`（包含日誌送出時間及完工結束時間）。
- **Idempotency-Key 改非必填**：修正 `VisitReportController.java`，將 `/start` 與 `/end` 的 `@RequestHeader` 改為 `@RequestHeader(value = "Idempotency-Key", required = false)`，以徹底對齊 SD 規格定義之「非必填」，避免前端在沒有帶 Header 的情況下被攔截拋出 400 錯誤。
- **事件監聽器重構**：將 `handleVisitNotification` 事件監聽器從 `NotificationService.java` 移入 `NotificationListener.java`，讓 `NotificationService` 專心負責通知推送的實體邏輯，所有非同步事務事件聆聽機制均統一由 `NotificationListener` 接管，保持架構一致性。

---

## 4. 測試驗證結果

### 4.1. 前端 E2E 測試
在 `frontend` 目錄下執行 Playwright E2E 測試，結果為 100% 綠燈通過：

```bash
npx playwright test e2e/service-execution.spec.ts
```

**輸出結果：**
```text
Running 1 test using 1 worker

[1/1] [chromium] › e2e/service-execution.spec.ts:135:3 › Service Execution & Check-in Flow E2E › should complete the entire check-in, execution, check-out and submission flow successfully
  1 passed (1.9s)
```

### 4.2. E2E 相容性修復與驗證 (SD-014)
- **遮擋攔截問題**：引入全新的通用 `AppHeader` (高度 64px) 與保母端置頂 `KYC Banner` (~40px) 後，既有 E2E 測試腳本在點擊 `返回 Demo 首頁` 按鈕（原本定位於 `top: 16px; right: 16px`）時，指針會被 `AppHeader` 的 glass-panel 或是 `sitter-kyc-banner-action` 攔截，導致測試超時失敗。
- **排版定位修復**：將 `App.tsx` 中的 `返回 Demo 首頁` 飄浮按鈕之絕對定位修改為 `top: 120px; right: 16px`，完美避開了所有 sticky 頂部橫幅的干涉。
- **相容性測試驗證**：執行與首頁跳轉高度關聯的既有 E2E 測試，結果順利重回 100% 綠燈：
  
  ```bash
  npx playwright test e2e/dispute-and-completion.spec.ts e2e/offline-payment.spec.ts
  ```
  
  **輸出結果：**
  ```text
  Running 6 tests using 4 workers
  6 passed (3.7s)
  ```

---

## 5. 進度更新

- [x] **SD-008: 服務執行與 Check-in** (✅ **Implemented**)
  - 打通 `CONFIRMED → IN_PROGRESS → DONE` 狀態流轉，支援選填 `Idempotency-Key`。
  - 完成事件監聽器重構與異步防幽靈通知機制。
  - 完成前端日誌回報介面的 SOP 控制與 E2E 綠燈驗證。

- [x] **SD-017: 保母實名認證與資格審查 (KYC)** (✅ **前後端全端 Implemented & COMPLIANT**)
  - 完成系統設計文件（9 輪 Review → COMPLIANT）。
  - 完整實作後端所有 KYC 路徑，通過 Project Auditor 2 輪 Review（FAIL-1 SUSPENDED 阻擋、N+1、@Version、SUSPENDED 測試補齊）。
  - 關鍵架構決策：Rate Limiting 置於 `@Transactional` 外的 Controller 層、Partial Unique Index DB 層防並發重送、`@Version` 補至 `profiles` 表、JOIN 查詢取代 N+1。
  - 前端實作：`kycApi.ts` 全端點串接、`SitterKycSubmit`（5 狀態分支 + 5MB 驗證）、`AdminKycList`（分頁待審 + 停權工具）、`AdminKycDetail`（Promise.all 並行 Signed URL + Approve/Reject）。

- [x] **SD-014: 訊息中心與推播通知** (✅ **前後端核心與測試實作完成，測試 100% 綠燈**)
  - **資料模型與防禦**：
    - Flyway `V20260614_01` 建立通知與偏好設定表，添加 `created_at` 複合索引優化小鈴鐺查詢效能。
    - 資料庫層級建立 CHECK 條件防呆，強制保護 `ACCOUNT_AUTH` 類別在 DB 層也無法被關閉。
  - **後端安全與業務**：
    - 實作 IDOR Ownership 驗證防禦，未授權或查無紀錄一律回傳 `404 (MSG_DATA_F11)`，隱藏資料存在性以防 ID 枚舉時序攻擊。
    - 修正通知偏好更新錯誤碼，將 "INVALID_PARAMETER" 統一修改為 "MSG_DATA_INVALID_INPUT"，確保與專案統一的錯誤回應規格對齊。
    - 解決 Spring AOP 自我呼叫失效問題，將物理清理拆分為 `NotificationCleanupService`（loop 方法）與 `NotificationBatchDeleter`（REQUIRES_NEW），以獨立 Bean 進行跨 Bean 呼叫，確保每個 LIMIT 1000 批次皆能獨立 Commit 釋放行級鎖。
    - 建立全域預設偏好配置 `NotificationPreferenceDefaults` 常數 Map，由 API 與 Listener 共享，解決預設值不一致之痛點。
    - 重構 `NotificationListener`，以 `OrderRepository` 動態查詢 `ownerId` / `sitterId` 關聯者，全面串接 `createNotification` 實現角色隔離通知。
    - 於 `InternalCronController` 暴露清理 API，並於 `LocalCronSimulator` 中模擬每日凌晨清理。
  - **前端實作**：
    - 實作 `notificationApi.ts` 串接後端 API。
    - 實作 `useNotifications.ts` React Query custom hooks，對小鈴鐺未讀數套用 30 秒快取與 60 秒定時 Polling（於背景自動暫停，兼顧即時性並防止 Cloud Run min-instances 0 無法縮容之計費陷阱）。
    - 實作 `AppHeader.tsx` 整合小鈴鐺 icon、未讀計數 Badge、前 5 筆通知之下拉選單（Dropdown），並針對保母端加入置頂 KYC Banner 警示。
    - 實作 `NotificationsPage.tsx` 與 `PreferencesPage.tsx` 頁面，支援分頁載入、一鍵已讀、單頁應用路由跳轉，以及對 `ACCOUNT_AUTH` 偏好設定之雙端防呆卡控。
  - **測試覆蓋**：
    - 建立 `NotificationServiceTest` 與 `NotificationControllerTest` 整合測試，全面覆蓋 IDOR 防禦、偏好鎖定、動態補齊、物理分批清理與角色隔離等核心場景，並使用 `JdbcTemplate` 物理修正建立時間以繞過 JPA updatable 限制，測試 100% 綠燈通過。

---

## 6. SD-018: 保母公開檔案與標籤管理實作報告

本文件紀錄了 **SD-018: 保母公開檔案與標籤管理** 的前後端實作完成狀況，以及修復並通過 E2E 測試與後端整合測試的詳細過程。

### 6.1. 核心修復與合規性重構

1. **優化事務邊界 (避免 DB 連接長鎖)**
   - 移除了 `SitterPublicProfileServiceImpl.java` 中整個 `uploadAvatar` 方法的 `@Transactional` 標記。
   - 將 `mediaStorageService.uploadAvatar(sitterId, file)` 移至寫入事務之外執行，僅在 GCS 網路 I/O 上傳成功後，再使用新抽離出的 `@Transactional` 方法 `self.txSaveAvatar(sitterId, avatarUrlWithVersion)` 寫入資料庫。
2. **修正標籤上限 (VARCHAR(20) Buffer 隔離)**
   - 將 DTO 欄位 `@Size(max = 20)` 修改為 `@Size(max = 10)`、後端 Service 的防截斷 check 由 `> 20` 改為 `> 10`。
   - 前端 [SitterProfileSettings.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/SitterProfileSettings.tsx) 最大長度 `maxLength` 與錯誤提示均改為 10 字。
3. **解決 TOCTOU 競爭條件**
   - 移除 `addForbiddenKeyword()` 中的 `existsByKeyword()` 檢查，改為直接執行 `save()` 並 `flush()`，若拋出 `DataIntegrityViolationException` 則使用 try-catch 將其轉譯並重新拋出為 409 `PublicProfileException` 搭配錯誤碼 `MSG_DATA_CONCURRENCY_CONFLICT`。
4. **樂觀鎖錯誤碼一致性**
   - 修正 [GlobalExceptionHandler.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/exception/GlobalExceptionHandler.java)，將樂觀鎖 fallback 錯誤碼由 `VERSION_CONFLICT` 修正為對齊設計規範的 `MSG_DATA_CONCURRENCY_CONFLICT`。
5. **補齊 DTO 欄位與安全 Toggle 初始化**
   - 在 `PublicProfileResponse` 實體中補齊 `isVisible` 屬性，且 Service 端非 gated 分支調用 `.isVisible(profile.isVisible())`。
   - 前端 DTO 介面補齊 `isVisible?: boolean;`，且 React 元件中改用 `profile.isVisible ?? true` 安全初始化，防止 default 值被覆蓋為 false。

### 6.2. E2E 測試狀態污染問題修復

* **問題**：由於本地運行的是持久化 PostgreSQL 資料庫，前一次 E2E 測試執行了步驟 5 關閉公開檔案，將 `isVisible` 寫為 `false`。本次測試再次執行時，第一步與第三步的 `SitterProfileSettings` 元件加載時會取得為 `false` 的 `isVisible`，並在點擊儲存時再次把 `false` 寫回，導致步驟 4 的飼主端預約精靈得到的 profile 永遠處於 `gated = true` (顯示為 "保母休息中")。
* **修復**：在 `sitter-profile.spec.ts` 中改為在點擊儲存前，利用隱藏 checkbox 的 `isChecked()` 狀態進行防禦判定。如果當前狀態不符合期望，才去點擊外層 span 開關，成功消除了測試狀態污染：
  ```typescript
  // 確保公開檔案是開啟狀態 (避免被上次測試結果污染)
  const toggleCheckbox = page.getByTestId('sitter-profile-toggle-visible');
  if (!(await toggleCheckbox.isChecked())) {
    await page.locator('label:has([data-testid="sitter-profile-toggle-visible"]) span').first().click();
  }
  ```

---

## 7. 測試驗證結果

* **後端控制器整合測試**：`SitterPublicProfileControllerTest` 通過 (7 Tests Run, 0 Failures)。
* **前端 E2E 整合測試**：`sitter-profile.spec.ts` 通過 (1 Passed, 2.3s)。

---

## 8. Admin Subscription API — 訂閱方案人工覆寫

本節紀錄 **Close Beta 最後一哩路** Admin Subscription API 的實作完成狀況。

### 8.1 需求背景

Close Beta 不接線上支付 (SD-015)，早鳥優惠與補償方案由管理員透過後台手動覆寫保母的 SaaS 訂閱等級，對應 PRD-012 備注與 PRD-020 AC-2。無需獨立 SD 文件。

### 8.2 後端實作

新增 [`AdminSubscriptionController.java`](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/AdminSubscriptionController.java)：

* `GET /api/admin/subscriptions/{sitterId}` — 查詢保母當前訂閱，若無記錄回傳 `planTier: FREE`。
* `POST /api/admin/subscriptions/{sitterId}` — 覆寫訂閱，planTier 必填（FREE/BASIC/PRO/ULTIMATE）、expiredAt 選填（留空表示永不到期）、monthlyOrderCount 維持原值不動。
* 受 `@PreAuthorize("hasRole('ADMIN')")` class-level 保護。
* 每次覆寫後呼叫 `auditLogService.writeUserActionLog("ADMIN_SUBSCRIPTION_SET", "UPDATE", ...)` 寫入獨立 REQUIRES_NEW 審計日誌。

### 8.3 前端實作

新增 [`AdminSubscriptionPage.tsx`](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/admin/AdminSubscriptionPage.tsx)：

* **兩段式 UI**：Step 1 輸入保母 UUID → 查詢顯示當前方案；Step 2 選擇新 planTier / 到期日 / 異動原因 → 確認覆寫。
* `App.tsx` 新增 `'admin-subscription'` ViewState 與 Demo 首頁快速跳轉按鈕。

### 8.4 Close Beta 完成里程碑

至此，`00-SD-PLAN.md` 第三階段 Close Beta 必要模組全部完成：

| 模組 | 狀態 |
|---|---|
| SD-007 線下付款 | ✅ Implemented |
| SD-008 服務執行 | ✅ Implemented |
| SD-017 保母 KYC | ✅ Implemented |
| SD-014 通知中心 | ✅ Implemented |
| SD-018 保母公開檔案 | ✅ Implemented & COMPLIANT |
| Admin Subscription API | ✅ Implemented |

