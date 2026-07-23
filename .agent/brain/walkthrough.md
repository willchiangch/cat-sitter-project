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

---

## 9. Open Beta / 正式上線階段系統設計 (Phase 4)

### 9.1. SD-015: 線上支付與金流整合 (🟡 Designing)
- 完成線上代收代付流程與 Webhooks 非同步付款成功通知設計。
- 規劃 `sitter_payout_settings`、`payments`、`payout_records` 資料表。
- 實作 HMAC-SHA256 安全簽章防禦、TWD 高精度四捨五入計算與防重送冪等設計。

### 9.2. SD-013: 多媒體生命週期與保留策略 (✅ Implemented & COMPLIANT)
- **Cloud Run 排程設計**：使用 GCP Cloud Scheduler + Internal API (/api/internal/cron/...) 代替原生 @Scheduled，規避 min-instances:0 限制。
- **資料表對齊**：基於現有 `service_report_media` 與 `order_snapshots`（mediaRetentionDays / planTier）進行狀態欄位擴充與快照判定。
- **邊界防禦與強健性**：
  - 警告通知查詢加上 `EXISTS` 子查詢防禦，照片已物理清理的訂單不發送過期警告。
  - 使用 `MediaExpiryWarningBatchProcessor` 的 `REQUIRES_NEW` 獨立 Bean 處理單筆通知發送，避免單筆失敗阻塞整批排程。
  - 通知信件發送攜帶 `expiryTime` 精確日期（`yyyy-MM-dd`），優化 UX。
- **前端下沉**：`isPurged` 判定下沉至單一媒體級別 (Per-item DTO)，避免誤遮擋整份報告未過期媒體，report 級別 `isPurged` 僅用於頂端 Banner。

---

## 10. 前後端分離部署、正式路由/登入頁與訂單清單 API 化

本節紀錄將 Demo Hub 架構升級為正式產品架構的完整過程：前端獨立部署至 Firebase Hosting、`react-router` 正式路由取代 `ViewState` 手動切換、新增正式登入頁、`OwnerOrders`/`SitterOrders` 由假資料改接真實清單 API，以及打開 CI 後端測試閘門。

### 10.1 前後端分離部署 (Firebase Hosting + Cloud Run)
- 前端獨立建置為靜態資源部署至 Firebase Hosting（`wd-pet-sitter.web.app`），`firebase.json` 設定 `/api/**` → Cloud Run `run` rewrite，同源代理避免 CORS。
- 後端 `backend/Dockerfile` 恢復為純 API 服務，不再內嵌前端靜態資源。
- `.github/workflows/deploy.yml` 新增 `Deploy Frontend to Firebase Hosting` 步驟，於 Cloud Run 部署完成後執行。

### 10.2 正式路由與登入頁
- 新增 `frontend/src/routes.tsx`：`App.tsx` 原本 25 個 `ViewState` case 全數轉為 `react-router` 路由，需要資源 id 的頁面改用 `useParams()`。
- 新增 [`LoginPage.tsx`](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/auth/LoginPage.tsx) 與 [`RequireAuth.tsx`](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/auth/RequireAuth.tsx) route guard。
- `RoleContext.tsx` 的 role-toggle（供 E2E 使用）保留為 dev 快速切換工具，新增 `authMode`（`manual` / `seed`）判斷式，避免正式登入的 session 被自動種子登入覆蓋；新增 `isAuthLoading` 狀態，避免 `RequireAuth` 在自動登入完成前誤判為未登入而導致連環重導向。
- 新增共用 `useCurrentUser.ts` hook（decode JWT 取得 `userId`/`role`），取代各頁面各自硬寫的假 UUID。

### 10.3 訂單清單 API 化
- 後端新增 `GET /api/orders/owner`、`GET /api/orders/sitter`（`OrderController`/`OrderQueryService`），回傳 `OrderSummaryDto`（含後端組好的 `scheduledDatesLabel` 摘要字串）。
- `OwnerOrders.tsx`/`SitterOrders.tsx` 移除固定假資料陣列，改用 `useOwnerOrdersQuery`/`useSitterOrdersQuery` 串接真實清單。
- **E2E 測試資料清理**：因 Supabase 為持久化資料庫，清單改真實資料後種子帳號測試訂單會越積越多。新增 `TestDataCleanupService` + `POST /api/internal/cron/test-data/cleanup-seed-orders`（沿用 `InternalSecretFilter` 保護），CI 於 Playwright 跑完後 (`if: always()`) 呼叫此端點軟刪除種子帳號訂單。

### 10.4 CI 後端測試閘門
- `.github/workflows/deploy.yml` 新增 `Run Backend Tests`（`mvn test`），擋在 Docker build 之前，取代先前 `Dockerfile` 用 `-DskipTests` 導致後端測試從未在 CI 執行過的缺口。
- 修復兩個既有失敗測試：`MediaRetentionServiceTest`（稽核日誌 FK 違反，改用真實 admin user id）、`ServicePlanControllerTest`（斷言值對齊 `GlobalExceptionHandler` 實際回傳的 `MSG_DATA_CONCURRENCY_CONFLICT`）。
- 修復 `application-local.yml` 被 `.gitignore` 排除、CI 從未取得 `jwt-secret`/`jwt-expiration` 導致 `@ActiveProfiles("local")` 測試全面 `ApplicationContext` 載入失敗的問題，於 `application.yml` 補上非機密的預設值。
- 建立正式 `INTERNAL_CRON_SECRET`（GCP Secret Manager + GitHub Actions secret），取代先前 `/api/internal/**` 端點一直用原始碼硬寫預設密碼防護的安全缺口。

### 10.5 意外發現並修復的正式環境 Bug
- **`forbidden_keywords` 查詢 500 錯誤**：`GET /api/admin/forbidden-keywords`（不帶 `q` 參數）在正式環境會回傳 500（`function lower(bytea) does not exist`）。根因是 Supabase Transaction Pooler（`prepareThreshold=0`，無 server-side prepared statement）下，JPQL `WHERE :q IS NULL OR LOWER(f.keyword) LIKE ...` 對 `null` 參數型別推斷失敗。修復：`SitterPublicProfileServiceImpl.getForbiddenKeywords` 在 `q` 為 null/blank 時改呼叫 `findAll()`，`ForbiddenKeywordRepository` 移除 `:q IS NULL OR` 分支，並補上無 `q` 參數的回歸測試。

---

## 11. 正式環境上線後人工巡檢修復

10 完成部署後，於正式環境 (`https://wd-pet-sitter.web.app`) 手動走查時發現並修復以下問題，皆已本地重現驗證後上線：

### 11.1 毛孩頭像須「先儲存才能上傳」的體驗問題
- **問題**：`PetManager.tsx` 新增毛孩流程中，點擊頭像會被 `isAdding` 擋下並跳錯誤提示，要求先送出基本資料表單才能上傳照片，原因是後端 `uploadPetAvatar(petId, file)` 需要已存在的 `petId`。
- **修復**：新增流程改為先用 `URL.createObjectURL` 在本地暫存檔案並即時預覽，待「儲存基本資料」拿到新建 `petId` 後自動補傳（`handleSavePet` 內串接 `uploadAvatarMutation`），使用者體感一次完成選圖＋填資料＋存檔。

### 11.2 窄螢幕（App 固定 390px 手機殼容器）表單文字逐字直排
- **問題**：`PetManager.tsx` 新增毛孩表單原本用 `大頭照(150px) + 表單(1fr 1fr)` 三欄並排，但整個 App 由 `AppShell` 固定卡在 `--max-width-mobile: 390px` 容器內，換算下每個表單欄位僅剩約 40px 寬，中文 label（如「毛孩名字」）被迫逐字換行直排。
- **修復**：改為大頭照獨立一列置中、表單欄位另起一列 2 欄，欄寬回到約 130px 正常顯示。

### 11.3 `/login` 頁面與 RoleContext 自動種子登入並發衝突
- **問題**：`RoleContext.tsx` 的自動種子登入 `useEffect` 未依路由排除，使用者在 `/login` 頁面手動登入時，會跟自動種子登入同時對同一組帳號打 `POST /api/auth/login`，後端對同帳號並發登入有樂觀鎖版本控制，輸的一方回 409（`MSG_DATA_CONCURRENCY_CONFLICT`），導致帳密正確卻顯示登入失敗——尤其容易在用種子帳號（`sitter/owner/admin@test.com`）手動驗證正式環境時踩到。
- **修復**：`RoleContext.tsx` 的自動登入 effect 於 `window.location.pathname === '/login'` 時直接跳過。

### 11.4 PWA Service Worker 未偵測更新，部署後開著的分頁變空白頁
- **問題**：`vite-plugin-pwa` 預設自動注入的 `registerSW.js` 只單純 `navigator.serviceWorker.register()`，沒有偵測到新版本後 reload 的邏輯。部署後，先前已開啟過網站的分頁重新導覽時，SW 攔截 navigation 並吐出舊快取的 `index.html`（引用已被新版覆蓋、Firebase Hosting 上已不存在的舊 JS bundle hash），造成白畫面。以 Playwright 全新無痕 context 測試正常，但重現使用者「點連結變空白頁」的情境需要瀏覽器已有舊 SW 快取。
- **修復**：`vite.config.ts` 設定 `injectRegister: false`，改於 `main.tsx` 用 `virtual:pwa-register` 手動註冊，`onNeedRefresh` 時呼叫 `window.location.reload()` 強制刷新，之後每次部署都會讓已開啟分頁自動跳到新版本。

### 11.5 `sitter-profile.spec.ts` E2E 測試逾時（CI 連續失敗排查）
- **問題**：本次部署後 CI 的 Playwright E2E 連續兩輪都在 `sitter-profile.spec.ts` 最後一步（還原公開檔案並儲存）逾時失敗。用 Playwright HTML report 與 trace 分析（`0-trace.network`）發現：此測試本身步驟極多（4 次保存／多次角色切換／KYC／關鍵字管理，每個都是跨區 Cloud Run↔Supabase 真實 API 呼叫），連成功的一次本地重跑都要 29–30 秒，卡在 Playwright 預設 30 秒測試逾時邊緣，並非功能性 regression。另有一次本地重現「開場即空白頁」，但後續 5 次冷啟動測試皆正常（無 reload loop），排除與 11.4 的 PWA 修復有關，判斷為單次網路抖動。
- **修復**：於該測試內加上 `test.setTimeout(60000)`，本地重跑 3 次穩定通過，推上 CI 一次全綠（31/31，7m17s）。

---

## 12. PRD-000 補齊：Email OTP 註冊驗證 + 帳號註銷（軟刪除）

依使用者指示，按序實作 PRD-000 尚未落地的三項功能中的前兩項（第三項 Google OAuth 需使用者先在 Google Cloud Console 申請 OAuth 憑證，待後續進行）。

### 12.1 Email OTP 註冊驗證 (PRD-000 AC-1)

- **流程設計**：`POST /api/auth/register` 不再直接建立帳號並核發 Token，改為寄送 6 碼 OTP；新增獨立暫存表 `registration_otps`（`V20260723_01__create_registration_otps.sql`）存放待建立的帳號資料（含已雜湊密碼）與雜湊後的 OTP，通過 `POST /api/auth/register/verify-otp` 驗證後才正式寫入 `users` 並自動登入（核發雙 Token）。另提供 `POST /api/auth/register/resend-otp`（60 秒冷卻）。
- **關鍵參數**：OTP 10 分鐘效期、60 秒重寄冷卻、5 次錯誤上限（`AuthService` 常數）。
- **Bug 修復**：OTP 錯誤次數 (`attempts`) 原本會隨 `RegistrationException` 一起被交易回滾，鎖定機制形同虛設——比照既有 `LoginAttemptService` 的解法，抽成獨立 Bean `RegistrationOtpAttemptService`（`@Transactional(REQUIRES_NEW)`）確保錯誤次數獨立提交。
- **前端**：`RegisterPage.tsx` 改為兩段式表單（填表單 → 輸入 OTP，含重寄倒數），驗證成功後比照 `LoginPage.tsx` 寫入 `localStorage` 並導向 `/demo`。
- **測試**：`AuthControllerTest` 新增 7 個 OTP 情境（OTP_SENT 未建帳號、驗證成功建帳號、錯誤累加、超過上限鎖定、過期、重寄冷卻、Email 重複註冊），E2E 更新 `auth-register-and-password-reset.spec.ts`。

### 12.2 帳號註銷（軟刪除）(PRD-000 AC-8)

- **關鍵發現**：`User`（`BaseEntity.is_deleted`）已有軟刪除欄位，且 `SitterPublicProfileServiceImpl.getPublicProfile()` 早已檢查 `user.isDeleted()` 並回 404（`MSG_DATA_F11`）——公開頁 404 這項幾乎是現成的，沒有新增資料表也沒有改動該檔案。
- **前置檢查**（使用者指示的嚴格版）：`OrderRepository.existsActiveOrderForParty` 檢查名下（`owner_id` 或 `sitter_id`）是否有任何非終態訂單（除 `COMPLETED`/`CANCELLED` 外皆算未結案），有則 409 `ACCOUNT_DEACTIVATION_BLOCKED`。
- **不阻擋、改自動清除**：依使用者指示，「存在於別人的信任圈」與「被收藏為我的最愛」不擋註銷，改為 `TrustRelationshipRepository`/`FavoriteSitterRepository` 各自新增 `softDeleteByPartyId` 批次軟刪除。
- **最終確認方式**：重新輸入密碼（沿用 SD-009 admin 二次驗證的 403 慣例——`AccessDeniedException`，避免撞上前端 axios 全域 401 refresh-token 重試機制）。
- **`AuthService.login()`**：新增 `user.isDeleted()` 檢查，一律回傳既有「帳號或密碼錯誤」401，不透露帳號已註銷。
- **前端**：新增 `frontend/src/pages/shared/AccountSettings.tsx`（`/account-settings` 路由），密碼確認註銷後清空本機 Token 並導回 `/login`。
- **測試**：`AuthControllerTest` 新增 7 個情境、`SitterPublicProfileControllerTest` 新增 1 個 404 情境（驗證既有邏輯）、新增 E2E `account-deactivation.spec.ts`（2 情境）。
- **已知限制（明確不做）**：PRD-000 提及「已註銷帳號 Email 30 天冷卻後可重新註冊」；`users.email` 為 DB 硬 UNIQUE 約束，要支援冷卻後複用需額外機制（背景 Job 改名/清空已註銷帳號 email）。現況為**已註銷帳號 Email 永久佔用、不可重新註冊**（比規格更嚴格，不影響資料安全），已記錄於 SD-000，待後續有需要再實作自動化解鎖。

### 12.3 測試結果

- 後端整合測試（`AuthControllerTest` 12+7、`SitterPublicProfileControllerTest` +1）與全量 `mvn test` 皆綠燈（過程中兩次遇到本地 Docker daemon 中途掉線的環境性 flaky，非程式碼問題，重跑後穩定通過）。
- 前端 `npm run build`（TS 嚴格模式）通過；E2E `auth-register-and-password-reset.spec.ts`（5 情境）與新增 `account-deactivation.spec.ts`（2 情境）全數通過。
- 文件回補：`SD-000-authentication-authorization.md`（新增 2 個序列圖、`registration_otps` 資料模型、3+1 個新 API 列入表格、備註區參數與已知限制）、`TS-000-authentication.md`（新增 TS-000-11~24 共 14 個測試情境）、README 模組狀態表 SD-000 說明列。

---

## 13. PRD-000 補齊完結：Google 第三方登入

延續第 12 節，完成 PRD-000 三項待補功能中的最後一項。使用者已在 GCP 專案 `wd-pet-sitter` 建立 OAuth 2.0 用戶端（Web application 類型），提供 Client ID `1020176535925-78mcs53q25ku3s4ob6j0kpvp6tpu32cu.apps.googleusercontent.com`，Client Secret 檔案未放入 repo（本方案也用不到）。

### 13.1 技術選型

採 **Google Identity Services (GIS)** 前端官方按鈕 + 後端驗證 ID Token，而非傳統整頁跳轉的 Authorization Code 流程：
- 不需要設定 Authorized redirect URI（只需 Authorized JavaScript origins，使用者已設定 `https://wd-pet-sitter.web.app` + `http://localhost:5173`）
- 後端完全不使用 Client Secret，只需 Client ID 驗證 `aud`
- Client ID 本身是設計上公開的值，且本地/正式共用同一組 Client，因此直接寫入 `application.yml` 版控預設值，`deploy.yml` 不需額外注入

### 13.2 後端實作

- 新增依賴 `com.google.api-client:google-api-client`（提供 `GoogleIdTokenVerifier`，官方處理 Google 公鑰 JWKS 抓取/快取/輪替，避免手刻 JWT 驗證的安全風險——這是本專案首次為了「驗證第三方簽發的身分權杖」而加函式庫，與 `EmailService` 手刻 HTTP 呼叫 Resend 的風險層級不同）。
- 新增 `GoogleTokenVerifierService`：包裝 Google SDK 細節，回傳與 SDK 型別解耦的 `GoogleUserInfo` record，方便測試 mock（不需真的組出真實簽章 JWT）。
- `AuthService.loginWithGoogle()`：
  - 既有 Email → 直接自動綁定登入（Google 已驗證 Email 擁有權，視為可信；若該帳號已註銷則回 401，訊息與一般登入一致不透露註銷狀態）
  - 新 Email 未帶 `role` → 回 `NEEDS_ROLE_SELECTION`（不建帳號）
  - 新 Email 帶 `role`（僅接受 OWNER/SITTER）→ 建立帳號（密碼為隨機亂數雜湊，日後可用忘記密碼流程設定）並自動登入
  - **未新增 `google_id` 欄位**：綁定純粹以 Email 比對，與密碼註冊流程對 Email 的信任程度一致。
- `POST /api/auth/google`（`/api/auth/**` 已 permitAll，無需額外設定）。

### 13.3 前端實作

- `LoginPage.tsx` 用 `useEffect` 動態注入 GIS 腳本並在 `onload` 才初始化按鈕，腳本載入失敗時只是按鈕不出現（比照 PRD-000 對生物辨識「裝置不支援則隱藏」的既有降級精神），不影響頁面其他功能。
- 新增角色選擇步驟（比照 `RegisterPage.tsx` 的兩段式 pattern），沿用同一個 ID Token 帶入選定角色再次呼叫後端。
- 無條件掛上 `window.__handleGoogleCredential` 全域函式供 E2E 測試用 `page.evaluate()` 直接模擬「收到 Google credential」事件，完全不需要驅動真實 Google 彈窗（也不受 CI/沙盒環境能否連上 `accounts.google.com` 影響），對正式環境無副作用。

### 13.4 測試結果

- 後端整合測試新增 6 個 Google 登入情境（`GoogleTokenVerifierService` 用 `@MockitoBean` 取代），與全量 `mvn test` 皆綠燈。
- 前端 `npm run build` 通過；新增 E2E `google-login.spec.ts`（2 情境）通過。
- 額外執行過一次前端**全量** E2E（`npx playwright test` 不加篩選）作交叉驗證，發現 28 個既有情境失敗；追查後確認根因是本次工作階段**未啟動本機後端伺服器**（僅跑過 `mvn test` 的短命 Testcontainers，未 `mvn spring-boot:run` 提供持久化 `localhost:8080`），這些情境依賴 `/demo` 種子帳號自動登入打真實 `/api/auth/login`，與本次改動（僅動到 `LoginPage.tsx`）無關——本次新增/修改的 3 支 E2E 檔案（`auth-register-and-password-reset.spec.ts`、`account-deactivation.spec.ts`、`google-login.spec.ts`）皆已透過 `page.route()` 完整 mock，不受影響、全數通過。
- 文件回補：`SD-000-authentication-authorization.md`（新增序列圖、`POST /api/auth/google` API 列、技術選型與 Client ID 設計理由備註）、`TS-000-authentication.md`（新增 TS-000-25~30 共 6 個測試情境）、README 模組狀態表 SD-000 說明列。**PRD-000 三項待補功能（Email OTP 註冊、帳號註銷、Google 登入）至此全數完成**。

---


