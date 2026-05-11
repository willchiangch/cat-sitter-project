# SD-009 訂單結案與排程觸發實作計畫

## 1. 目標
實作訂單結案流程（手動/自動），並因應 Cloud Run `min-instances: 0` 的成本限制，將排程邏輯改為 API 驅動架構。

## 2. 核心變動

### 2.1 安全加固 (Security)
#### [MODIFY] [SecurityConfig.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/infrastructure/security/SecurityConfig.java)
- 新增 `/api/internal/**` 路由保護。
- 實作內部校驗：檢查 `X-Internal-Secret` 標頭。

### 2.2 排程觸發 (Infrastructure)
#### [NEW] `InternalCronController.java`
- 提供 `POST /api/internal/cron/orders/auto-complete`。
- 呼叫 `CompletionService.triggerAutoCompletion()`。

#### [NEW] `LocalCronSimulator.java`
- 使用 `@Scheduled` 定時觸發。
- 僅在 `@Profile("local")` 啟用。

### 2.3 業務邏輯 (Application)
#### [NEW] `CompletionService.java`
- **`triggerAutoCompletion()`**:
    - Step 1: `UPDATE visits SET status='CLOSED_BY_SYSTEM' WHERE ...` (72hr zombie cleanup)。
    - Step 2: 查詢符合結案條件的訂單 (48hr buffer)。
    - Step 3: 迭代處理訂單，標註 `@Transactional(propagation = Propagation.REQUIRES_NEW)`。
- **`manualComplete(UUID orderId, UUID ownerId)`**:
    - 驗證訂單權限與狀態。
    - 執行結案。

### 2.4 資料持久化 (Domain/Persistence)
#### [MODIFY] `VisitRepository.java` / `OrderRepository.java`
- 新增批次更新與複合條件查詢方法。

---

## 3. 驗證計畫

### 自動化測試
- **`InternalCronControllerTest`**: 驗證 Secret Header 攔截邏輯（401 vs 200）。
- **`CompletionServiceTest`**: 
    - 測試殭屍行程清理是否正確。
    - 測試自動結案的時間演算法（48hr）。

### 手動驗證
- 在本地啟動 `local` profile，觀察 Console Log 是否每小時（測試時可設為每分鐘）觸發排程。
