# SD-003: 保母服務方案設定 前端實作計畫

本計畫旨在實作保母後台的服務方案管理功能，包含方案列表展示、新增、編輯、下架（邏輯刪除）與拖曳排序，並整合飼主端預約精靈（PublicBookingPage）讀取真實方案，最後透過 Playwright E2E 進行功能驗證。

## User Review Required

> [!IMPORTANT]
> **1. 方案限制級別 (SaaS Gating) 提示**
> 後端會在保母嘗試填寫「開放預約日期區間」且訂閱方案為 `FREE` 時拋出 403 `AUTH_PLAN_LIMIT` 錯誤。前端會在表單的日期設定區塊加上提示：「此欄位為專業版/旗艦版保母專屬功能」，並在 API 拋出錯誤時顯示錯誤訊息。
>
> **2. 樂觀鎖衝突 (Optimistic Locking) 處理**
> 為防範保母在多個瀏覽器分頁或設備同時編輯同一方案，更新 (PUT) 時需傳入版本號 (`version`)。若遭遇 409 `VERSION_CONFLICT`，前端會彈出 Alert 提示「此方案已被其他設備更新，請重新整理後再試」，且不關閉編輯表單以防保母輸入的內容丟失。
>
> **3. 排序操作機制**
> 基於 Vanilla CSS 無依賴原則，方案排序調整在 UI 上採用「上移 / 下移」按鈕的形式。當點擊按鈕時，會立即重排本地 state 並觸發 mutation 批次更新後端排序（`POST /api/sitter/plans/sort`），以提供最流暢且防呆的體驗。

## Open Questions

目前沒有重大的未決技術問題。後端 API 規格已與 `SD-003` 設計文件完全對齊。

---

## Proposed Changes

### 1. 基礎類型與 API 客戶端

#### [NEW] [servicePlan.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/types/servicePlan.ts)
定義服務方案相關的 TypeScript 介面。
- `ServicePlan`：對應後端 `ServicePlanDto` 欄位（包含 `version`、`startDate`、`endDate` 等）。
- `ServicePlanSortRequest`：排序請求。

#### [NEW] [servicePlanApi.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/api/servicePlanApi.ts)
封裝對應 `ServicePlanController` 的六個 Axios API 請求。
> [!IMPORTANT]
> **API 回傳層次規範**：為了避免型別不一致，所有方法皆必須回傳 `response.data.data`，對齊 `careApi.ts` 的 pattern，而非直接回傳 wrapper。
- `getSitterPlans` (GET `/api/sitter/plans`)
- `createSitterPlan` (POST `/api/sitter/plans`)
- `updateSitterPlan` (PUT `/api/sitter/plans/{planId}`)
- `deleteSitterPlan` (DELETE `/api/sitter/plans/{planId}`)
- `sortSitterPlans` (POST `/api/sitter/plans/sort`)
- `getActivePlansForOwner` (GET `/api/sitters/{sitterId}/plans`)

---

### 2. React Query 狀態管理

#### [NEW] [useServicePlans.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/hooks/useServicePlans.ts)
建立服務方案的 React Query Hooks，統一管理快取與同步。
- `useSitterPlansQuery`：取得保母所有方案。
- `useSitterActivePlansQuery(sitterId)`：飼主端查詢保母生效方案。
- `useCreatePlanMutation`：新增方案後 invalidate 相關 query。
- `useUpdatePlanMutation`：更新方案。
- `useDeletePlanMutation`：刪除方案。
- `useSortPlansMutation`：更新排序。

---

### 3. 使用者介面元件 (UI)

#### [NEW] [SitterPlans.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/SitterPlans.tsx)
保母服務方案設定頁面。
- **美學風格**：採用 Stitch "The Intuitive Concierge" Amber 主題。大面積留白、無框線分割（使用背景色差 `var(--color-surface-low)` 分類區塊）、精緻的擴散陰影與 CSS 微動畫。
- **方案列表**：
  - 顯示所有未刪除方案。
  - 卡片呈現名稱、適用寵物、預設照護工作（SOP）、價格及生效日期。
  - 每張卡片提供「編輯」按鈕，以及「上移/下移」排序按鈕（調整排序時，將批次呼叫 Sort API）。
  - **下架按鈕**：每張方案卡片新增「下架」按鈕。點擊時彈出確認 Dialog（防誤按），確認後觸發 `useDeletePlanMutation` 進行邏輯刪除。
- **新增/編輯方案抽屜 (BottomSheet) / Modal**：
  - 提供表單以設定 `name`、`price`、`dailyCapacity`、`applicablePetTypes`、`description`、`startDate`/`endDate` 及 `isRestricted`。
  - **適用寵物**：採 CAT/DOG 視覺化 Toggle 選擇器（多選）。
  - **SOP 照護工作**：提供動態列表，可點擊「新增任務」並進行單項刪除。
  - **日期限制**：提供開關 (Toggle)，啟用後展開日期選擇器。並於下方渲染 SaaS Gating 提示。
  - **白名單限制**：白名單預約限制 `isRestricted` 開關。
  - **錯誤防禦**：當呼叫 API 發生 `VERSION_CONFLICT` 或是 `AUTH_PLAN_LIMIT` 時，攔截錯誤並呈現對應警示訊息。

#### [MODIFY] [App.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/App.tsx)
- 註冊 `sitter-plans` View 狀態。
- 於 Demo 首頁清單中新增「進入服務方案設定 (保母端)」按鈕。
- 在 `renderView` 中將 `booking` 的渲染方式改為傳入 `sitterId` prop：
  ```tsx
  case 'booking':
    return <PublicBookingPage sitterId={view.params?.sitterId || mockParams.sitterId} />;
  ```

#### [MODIFY] [PublicBookingPage.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/client/PublicBookingPage.tsx)
- 元件定義調整為接收 `sitterId: string` prop。
- 將 `MOCK_PLANS` 替換為使用 `useSitterActivePlansQuery(sitterId)` 載入真實的保母開放服務方案，並於介面上正常處理讀取中/無方案等空狀態。


---

## Verification Plan

### Automated Tests
我們將在 `frontend/e2e/` 底下新增 Playwright E2E 測試，驗證前後端方案管理的完整邏輯。

#### [NEW] [service-plans.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/service-plans.spec.ts)
- **測試案例 1**：保母能正常進入方案設定頁，並建立常態方案。
- **測試案例 2**：保母編輯既有方案，並能動態增刪預設任務。
- **測試案例 3**：測試排序功能，點擊「下移」後，確認 API 送出正確的順序。
- **測試案例 4**：測試樂觀鎖防護，模擬送出衝突版本時，畫面顯示「內容已被更新，請重新整理後再試」。
- **測試案例 5**：測試 SaaS 門禁卡控，非專業方案的保母嘗試設定日期區間時，正確攔截並提示「僅限專業版以上方案可設定開放預約區間」。

### Manual Verification
- 啟動 Docker PostgreSQL 16。
- 啟動後端 Spring Boot 伺服器 (`mvn spring-boot:run`)。
- 啟動前端 Vite 開發伺服器 (`npm run dev`)。
- 在瀏覽器中操作 Demo 頁面，手動建立、修改、排序保母方案。
- 切換至飼主端，進入預約精靈，檢視剛剛設定的保母服務方案是否正確渲染。
