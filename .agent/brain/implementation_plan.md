# 支援複合方案與多趟次排程之實作計畫

目前的預約功能僅支援「全期間單一方案」，這是因為後端的 API (BookingRequest) 以及前端的 UI (BookingState) 皆採用簡化設計。為了支援「同一張訂單內，不同日期配置不同方案/次數」的進階需求，我們必須進行全端重構。

## User Review Required

> [!WARNING]
> **API 與資料庫結構變更**
> 此次更新將改動後端 `BookingRequest` 的結構，並修改 `VISIT` 資料表以新增 `plan_id` 欄位。這些改動屬於 Breaking Changes，若有其他正在開發的分支依賴目前的 `POST /api/orders/booking` 格式，將會受到影響。請確認是否可以直接在目前的開發環境進行此修改。

## Open Questions

> [!IMPORTANT]
> **關於「次數 (Frequency)」的定義**
> 假設飼主選擇「1/1 方案A 一天兩次」，在後端資料庫的 `visits` 表中，我們應該：
> 1.  產生 **2 筆獨立的 `Visit` 紀錄**（例如 1/1 上午、1/1 下午），需要飼主在 UI 選擇具體時段 (timeSlot)？
> 2.  還是產生 **1 筆 `Visit` 紀錄**，但附帶一個 `frequency=2` 的屬性，交由保母自行與飼主溝通時間？
> *建議採用第一種（產生多筆 Visit），對未來的行程表 (Calendar View) 與打卡功能擴充性較佳。本計畫預設採用此方案。*

## Proposed Changes

---

### 文件與設計 (Documentation)

#### [MODIFY] docs/sd/SD-ERD.md
- **VISIT Entity**: 補齊缺失的 `plan_id` (UUID) 與 `snapshot_plan_title` (VARCHAR) 欄位，確保每趟照顧都能對應方案。

#### [MODIFY] docs/sd/SD-005-public-booking.md
- **API Request Body**: 修正 Request 範例，從單一物件改為多項目 (Items) 的複合陣列，並明確展示如何傳遞多方案。

#### [MODIFY] docs/test-scenario/TS-005-public-booking.md
- 新增 **Scenario 3: 複合方案預約驗證**，補齊複雜訂單金額計算與資料庫落地的功能測試。

---

### 後端 (Backend API & Domain)

#### [MODIFY] backend/src/main/java/com/petsitter/application/dto/BookingRequest.java
- 移除扁平的 `planId` 與 `dates`，改為使用 `List<BookingItemRequest> items` 陣列。

#### [NEW] backend/src/main/java/com/petsitter/application/dto/BookingItemRequest.java
- 定義單一排程項目的 DTO：包含 `planId` (UUID)、`dates` (List<LocalDate>)、`timesPerDay` (Integer)。

#### [MODIFY] backend/src/main/java/com/petsitter/domain/model/OrderItem.java
- 與 `BookingItemRequest` 結構對齊，新增 `planId`、`dates` 等屬性，確保 JSONB 正確映射。

#### [MODIFY] backend/src/main/java/com/petsitter/domain/model/Visit.java
- 新增 `planId` 屬性。

#### [MODIFY] backend/src/main/java/com/petsitter/application/service/BookingService.java
- 重構 `createBooking` 邏輯：遍歷 `items`，根據 `timesPerDay` 展開並建立多筆對應的 `Visit` 實體。

---

### 前端 (Frontend UI & State)

#### [MODIFY] frontend/src/types/booking.ts
- 移除 `selectedPlanId`，改為 `schedules: DailySchedule[]`，支援趟次化狀態管理。

#### [MODIFY] frontend/src/pages/client/PublicBookingPage.tsx
- **Step 2**: 實作「趟次矩陣 (Visit Matrix)」編輯器，允許針對特定日期加入/移除特定方案。
- **Step 3**: 根據新的 `schedules` 狀態重新計算 `totalAmount` 總額。

#### [MODIFY] frontend/e2e/client-booking.spec.ts
- 修改測試腳本，模擬加入多趟次、多方案的複雜選取行為，並驗證最終金額。

## Verification Plan

### Automated Tests
- 執行後端單元測試：`mvn test -Dtest=BookingServiceTest` (包含新增的 Scenario 3)。
- 執行前端 E2E 測試：`npx playwright test e2e/client-booking.spec.ts`。

### Manual Verification
- 啟動全端服務，使用保母帳號進入。
- 透過前端介面操作「1/1-1/3 方案A兩次、方案B一次；1/4 方案A一次」的劇本。
- 確認訂單成功送出，且總結算金額符合預期。
