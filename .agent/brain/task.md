# 任務清單：多趟次排程實作 (Visit-based Scheduling)

## 1. 文件更新 (SD & ERD)
- [x] 修正 `docs/sd/SD-ERD.md`，於 VISIT 新增 `plan_id` 欄位。
- [x] 修正 `docs/sd/SD-005-public-booking.md`，更新 API 範例。
- [x] 修正 `docs/test-scenario/TS-005-public-booking.md`，新增 Scenario 3 測試情境。
- [x] TS-005-01 基礎預約流程驗證
- [x] TS-005-02 複合式預約案例驗證 (多日期、多方案、多趟次)
- [x] 優化 Playwright 報告 (截圖附件化)
- [x] 專案審計與修復 (Project Auditor)
    - [x] 修復 No-Line Rule 邊框違規
    - [x] 驗證時區、金額精度、PWA 配置
- [x] 更新 README.md
- [x] 執行 persist-progress 同步進度

## 2. 後端重構 (Backend)
- [x] 建立 `BookingItemRequest.java`。
- [x] 修正 `BookingRequest.java` 改用 `items`。
- [x] 修正 `OrderItem.java` 新增排程欄位。
- [x] 修正 `Visit.java` 新增 `planId` 欄位。
- [x] 修正 `BookingService.java` 的訂單與趟次建立邏輯。
- [x] 修正現有測試 (若編譯失敗) 並實作 `BookingServiceTest` 的 Scenario 3。

## 3. 前端重構 (Frontend)
- [x] 修正 `types/booking.ts` 狀態定義。
- [x] 重構 `PublicBookingPage.tsx` Step 2 趟次矩陣。
- [x] 重構 `PublicBookingPage.tsx` Step 3 金額計算。
- [x] 更新 `e2e/client-booking.spec.ts` 測試腳本以符合新 UI。

## 4. 驗證
- [x] 執行後端 Maven 測試。
- [x] 執行前端 E2E 測試。
