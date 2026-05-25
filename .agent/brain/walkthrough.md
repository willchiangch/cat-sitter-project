# SD-003: 保母服務方案設定 前端實作成果與驗證

我們已成功完成 SD-003 前端自訂服務方案功能的所有實作，並通過 Playwright 自動化 E2E 測試與 TypeScript 嚴格模式編譯。

## 變更項目

### 1. 資料結構與 API 層
- **[NEW] [servicePlan.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/types/servicePlan.ts)**：定義 `ServicePlan` 介面，完全對齊 Spring Boot 後端傳回的欄位格式（包含 `version`、`applicablePetTypes`、`defaultTasks` 等）。
- **[NEW] [servicePlanApi.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/api/servicePlanApi.ts)**：封裝與後端互動的 CRUD、排序、及前台查詢 API，統一回傳 `response.data.data` 以對齊專案 API 慣例。
- **[NEW] [useServicePlans.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/hooks/useServicePlans.ts)**：整合 React Query 封裝的方案查詢 Query 與變更 Mutation（包含 `invalidateQueries` 機制）。

### 2. 元件與路由整合
- **[NEW] [SitterPlans.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/SitterPlans.tsx)**：
  - 開發保母後台設定頁面，完美契合 Stitch "The Intuitive Concierge" Amber 主題與無框線分割設計。
  - **功能**：新增與編輯抽屜 Modal、適用寵物與 SOP 照護項目的動態刪減與增加、SaaS 方案限制警示、樂觀鎖衝突 (409) 提示、上移/下移排序功能、及下架邏輯刪除 Dialog 攔截。
- **[MODIFY] [App.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/App.tsx)**：註冊 `sitter-plans` 路由，並在 Demo 首頁新增入口按鈕；同步修正 `booking` 路由將 `sitterId` 作為 prop 傳入 `PublicBookingPage`。
- **[MODIFY] [PublicBookingPage.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/client/PublicBookingPage.tsx)**：接收 `sitterId` prop，移除寫死的 `MOCK_PLANS`，改用 `useSitterActivePlansQuery` 載入真實的保母開放方案，並在載入中或無方案時優雅提示。

### 3. 測試覆蓋
- **[NEW] [service-plans.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/service-plans.spec.ts)**：撰寫 5 個 Playwright E2E 自動化測試，分別針對：
  1. 成功查詢與新增常態方案 (Happy Path)
  2. 編輯既有方案與預設任務 SOP 動態增減
  3. 樂觀鎖衝突 (409) 錯誤攔截紅字提示
  4. SaaS 日期區間卡控 (403) 錯誤攔截紅字提示
  5. 方案上移排序與下架邏輯刪除功能

---

## 驗證結果

### 1. TypeScript 嚴格編譯檢查
執行 `npm run build` 通過，無任何型別錯誤。
```bash
vite v8.0.12 building client environment for production...
transforming...✓ 1863 modules transformed.
rendering chunks...
computing gzip size...
dist/registerSW.js                0.13 kB
dist/manifest.webmanifest         0.33 kB
dist/index.html                   0.57 kB │ gzip:   0.34 kB
dist/assets/index-C-oHgKLQ.css    2.29 kB │ gzip:   0.98 kB
dist/assets/index-D4_bsWfN.js   434.44 kB │ gzip: 123.97 kB
✓ built in 208ms
```

### 2. Playwright E2E 測試驗證
執行 `npx playwright test e2e/service-plans.spec.ts` 所有測試順利通過。
```bash
Running 5 tests using 4 workers

  5 passed (3.6s)
```
E2E 測試通過情境：
- **保母成功查詢方案列表與新增常態方案**：驗證新增、適用寵物、預設工作及列表渲染。
- **保母編輯方案與預設任務動態刪減**：驗證 PUT API、任務 SOP 的增刪更新。
- **樂觀鎖衝突 409 錯誤處理提示**：模擬衝突 version，確認錯誤提示紅字正確渲染在 Modal 表單內。
- **SaaS 日期區間 403 錯誤處理提示**：模擬 FREE 保母嘗試設定日期區間，驗證 SaaS Gating 阻守與紅字提示。
- **方案排序調整與下架功能**：驗證批次排序 API、上移功能順序更新，以及邏輯刪除後的 UI 移除。
