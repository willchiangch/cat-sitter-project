# 多趟次排程實作總結報告 (Visit-based Scheduling)

我們已經完成了飼主預約精靈從「單一方案」升級為「趟次矩陣 (Visit-based Scheduling)」的完整實作。以下是主要完成的改動：

## 1. 後端 (Backend) 重構
- **Flyway Migration**：新增 `V6__add_plan_id_to_visits.sql`，為 `visits` 表新增 `plan_id` 與 `snapshot_plan_title`，確保資料庫層級的支援。
- **領域模型 (Domain Models)**：
  - 更新 `OrderItem`，將 `dates` 型別調整為 `List<String>`（避免 Jackson 序列化 `LocalDate` 到 JSONB 失敗的問題），並加入 `planId`、`timesPerDay` 與 `petIds`。
  - 更新 `Visit` 實體加入快照資訊，完整反映 TS-005 的需求。
- **預約邏輯 (Service)**：重構 `BookingService.java`，確保在儲存 `Order` 之前建構好包含 `items` 的完整清單，以避免 Postgres `not-null` constraint violation。並支援將不同日期的趟次展開為多筆 `Visit` 記錄。
- **自動化測試 (Tests)**：後端所有測試皆修正並順利通過（包含 `BookingServiceTest`、`CompletionServiceTest`、`OrderEvaluationTest` 等），並新增了針對「複合方案（多種方案/次數）」預約情境的 Scenario 3 測試案例。

## 2. 前端 (Frontend) 重構
- **資料狀態模型**：於 `types/booking.ts` 引入 `DailySchedule` 介面，將 `BookingState` 的 `selectedDates` 與 `selectedPlanId` 升級為 `schedules` 陣列。
- **E2E 測試升級 (`e2e/client-booking.spec.ts`)**：
    *   **在地化與格式化**：將測試案例名稱改為中文編號對應 (TS-005-01, TS-005-02)。
    *   **報告優化**：導入 `test.step` 與 `testInfo.attach`，讓每個步驟的截圖直接顯示在 HTML 報告中。
- **專案審計與規範修復 (`project-auditor`)**：
    *   **無框線原則 (No-Line Rule)**：修復 `PublicBookingPage.tsx` 中誤用的 1px 實線邊框，改用背景色偏移 (Background Shifts) 與 `var(--shadow-ambient)`。
    *   **合規性驗證**：確認後端金額精度 (Integer)、時區 (UTC/OffsetDateTime) 與 PWA 避坑配置皆符合 `SD-GLOBAL-SPEC`。
- **進度同步 (`persist-progress`)**：
    *   更新 `README.md` 狀態表。
    *   將 `brain` 相關文件同步至專案根目錄 `.agent/brain/`。
- **預約精靈 UI (PublicBookingPage.tsx)**：
  - **Step 1**：選擇日期時會自動以預設方案初始化該日期的 `DailySchedule`。
  - **Step 2**：引入「每日排程」設定區塊，使用者可針對自己選擇的每一天，分別指定「方案（基礎/進階）」及「趟次頻率（一天 1~3 次）」。
  - **Step 3**：重構總額計算邏輯，遍歷所有日程的 `(方案單價 * 該日趟次)` 來計算正確總額，並於摘要區條列顯示各日選擇與數量。
- **Playwright E2E 測試**：更新 `client-booking.spec.ts` 以符合新的 UI 選擇流程。測試成功驗證「單日 2 趟次」的點擊選擇，並正確核對金額從 $500 升級計算為 $1000 的邏輯。

## 3. 測試驗證
- 後端：`mvn clean test` 測試全數綠燈 (100% Pass)。
- 前端：`npx playwright test` 測試全數綠燈 (100% Pass)。

目前開發進度已將功能完整打通，可隨時準備提交或推進到後續整合。
