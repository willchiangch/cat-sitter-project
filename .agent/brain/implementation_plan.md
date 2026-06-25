# SD-014: 訊息中心與推播通知實作計劃

本計劃旨在實作 PRD-014 訊息中心與推播通知，打通通知清單分頁、未讀數統計、已讀標示、通知偏好設定與 90 天物理清理排程。

## 1. 資料庫設計 (Schema Migrations)

### [V20260614_01__create_notifications_and_preferences.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V20260614_01__create_notifications_and_preferences.sql)
- `notifications` 表儲存發送給特定使用者的通知。
- `notification_preferences` 表儲存使用者的通知管道偏好開關。
- 設計 `chk_pref_account_auth_locked` CHECK 約束，確保 `ACCOUNT_AUTH` 偏好在資料庫層級永遠無法被設定為 false。
- 建立 `idx_notifications_user_role_read` 複合索引優化小鈴鐺未讀數查詢。

---

## 2. 後端業務與安全防禦 (Backend Engine)

### IDOR Ownership 模糊化防禦
* 於 `markAsRead` API 檢核擁有權：
  ```java
  Notification noti = notificationRepository.findById(notiId)
      .orElseThrow(() -> new NotificationException(HttpStatus.NOT_FOUND, "MSG_DATA_F11"));
  if (!noti.getUserId().equals(currentUserId)) {
      throw new NotificationException(HttpStatus.NOT_FOUND, "MSG_DATA_F11"); // 模糊化防範 ID 枚舉與時序攻擊
  }
  ```

### 偏好更新錯誤碼一致性
* 更新偏好設定時拋出的無效類別與 `ACCOUNT_AUTH` 鎖定錯誤，統一使用專案標準錯誤碼 `"MSG_DATA_INVALID_INPUT"`，對齊前端規格。

### 物理清理分批與 AOP Proxy 防護
* **問題**：若將大於 90 天通知的 DELETE LIMIT 迴圈寫在同一個 Service 方法中呼叫，會導致 Spring AOP Proxy 被跳過而使 `REQUIRES_NEW` 事務隔離失效，鎖定時間過長。
* **解法**：拆分為雙 Bean 呼叫：
  - `NotificationCleanupService`：無事務標記，執行 batch 迴圈。
  - `NotificationBatchDeleter`：標註 `@Transactional(propagation = Propagation.REQUIRES_NEW)`，以獨立 Bean 注入 cleanup 方法，確保每次批次 (LIMIT 1000) 物理刪除皆能單獨 Commit 並釋放 PostgreSQL 行級鎖。

---

## 3. 前端 UI 與 API 整合 (Frontend Integration)

### 3.1. API 與 Custom Hooks 封裝
* `notificationApi.ts`：串接後端 `/notifications` 的 CRUD 及 `/preferences` 端點。
* `useNotifications.ts`：採用 React Query 管理快取。
  * `useUnreadCountQuery` 設定 `staleTime: 30000`（30 秒快取）與 `refetchInterval: 60000`（60 秒定時 Polling）。
  * 配合 `refetchIntervalInBackground: false`，當 App 被切換至背景時自動暫停 Polling，防止 Cloud Run 實體無法縮容至 0 產生高額計費。

### 3.2. 視覺 UI 元件 (Stitch & Vanilla CSS)
* **`AppHeader.tsx`**：通用 sticky 頂部，含小鈴鐺、未讀計數紅點（大於 99 顯示 `'99+'`）與前 10 筆 Dropdown，以及保母端專屬 KYC 置頂警告 Banner（當 `kycStatus !== 'VERIFIED'` 時優先呈現）。
* **`NotificationsPage.tsx`**：通知中心頁面，支援分頁、一鍵已讀，並依據 `linkUrl` 解析進行單頁應用的視圖跳轉。
* **`PreferencesPage.tsx`**：通知偏好設定頁面，iOS 風格 Toggle 切換，`ACCOUNT_AUTH` 類別強制 Disabled 並顯示核心安全防護警語。

---

## 4. 測試驗證與相容性修復

### E2E 導覽攔截修復
* **問題**：由於 `AppHeader` (64px) 與 `KYC Banner` (~40px) 覆蓋在最頂部，導致既有 E2E 測試腳本在點擊 `返回 Demo 首頁` 按鈕（原本定位於 `top: 16px`）時會被橫幅攔截，產生超時失敗。
* **修復**：將 `返回 Demo 首頁` 按鈕位置調降至 `top: 120px; right: 16px`，避開所有頂端 banner 攔截。

### 測試執行
* 後端測試：`mvn test -Dtest=NotificationServiceTest,NotificationControllerTest,InternalCronControllerTest` (100% 綠燈)
* 前端 E2E 測試：`npx playwright test e2e/dispute-and-completion.spec.ts e2e/offline-payment.spec.ts` (100% 綠燈)
* 前端打包：`npm run build` (編譯成功，無 TypeScript 型別錯誤)
