# Implementation Plan - Testing, Documentation & Workflow Review

此計畫旨在將現有的技術實作與業務情境對齊，建立可靠的測試體系，並同步核心設計文件。

## User Review Required

> [!IMPORTANT]
> **資料庫規格書更新**：我將把 `doc/schema.md` 從 V6 直接升級至 V8，補齊訂閱、支付、行事曆與媒體儲存等新表格。
> **壓力測試工具**：建議使用 **k6 (JavaScript)**，因為它與現有前端技術棧相容性高，且效能優異。需確保本機已安裝 Docker。
> **工作流同步**：我將執行 `.agents/workflows/persist-progress.md` 的要求，將「大腦」資料夾同步至專案根目錄。

## Proposed Changes

### [Component] Documentation (Schema V8)

#### [MODIFY] [schema.md](file:///doc/schema.md)
- 更新版本號至 **V8**。
- **新增領域：財務與訂閱 (Finance & Subscriptions)**
  - `subscription_plans`, `sitter_subscriptions`, `promo_codes`, `payment_transactions`。
- **更新領域：行事曆與多媒體 (Calendar & Media)**
  - `sitter_calendar_configs`, `visit_media`。
  - `visits` 表新增 `calendar_event_id`, `ical_token` 等欄位。

### [Component] Backend Testing (Smoke & Performance)

#### [NEW] [BookingFlowSmokeTest.java](file:///backend/src/test/java/com/catsitter/api/smoke/BookingFlowSmokeTest.java)
- 模擬 `booking-lifecycle.md` 情境。
- 測試路徑：下單 -> 報價 -> 模擬支付 Webhook -> 確認訂單 -> 自動產生行事曆事件。

#### [NEW] [SitterOnboardingSmokeTest.java](file:///backend/src/test/java/com/catsitter/api/smoke/SitterOnboardingSmokeTest.java)
- 模擬 `onboarding.md` 情境。
- 測試路徑：註冊 -> 填寫資料 -> 訂閱方案 -> 解鎖接單狀態。

#### [NEW] [Performance Tests (k6)](file:///backend/src/test/resources/performance/webhook_smoke.js)
- 撰寫 k6 腳本測試 `PayUni Webhook` 的並行處理能力。
- 撰寫 k6 腳本測試 `iCal Feed` 的讀取效能。

### [Component] Workflow & Dev-Ops

#### [SYNC] [Brain Data Sync](file:///.agent/brain/)
- 依照 `persist-progress.md` 規範，將 `.agent/brain/` 下的文件（`task.md`, `implementation_plan.md` 等）同步至專案庫，確保開發連續性。

## Open Questions
- [x] **壓力測試目標**：針對 MVP 階段，預計模擬的最高並發數 (VUs) 為多少？ -> **不超過 50 VUs**。

## Verification Plan

### Automated Tests
- 執行 `mvn test -Dtest=BusinessScenarioSmokeTest`。
- 執行 `k6 run backend/src/test/resources/performance/webhook_smoke.js`。

### Manual Verification
- 檢查 `doc/schema.md` 是否與資料庫實際表格一致。
- 檢查 `.agent/brain/` 是否包含最新進度文件。
