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
