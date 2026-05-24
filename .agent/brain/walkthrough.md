# SD-009 & SD-016: 前端實作與前後端聯調驗證 Walkthrough

我們已成功實作並驗證 `SD-009` (訂單結案與爭議) 與 `SD-016` (訂單雙向變更與退款) 的前端介面、多角色自定義路由以及相關安全防衛機制，所有 6 個 Playwright E2E 測試情境皆已 100% 綠燈通過。

---

## 變更項目說明

### 1. 後端種子資料補齊 (Database Migration)
- 新增遷移檔 [V20260524_02__add_admin_seed_user.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V20260524_02__add_admin_seed_user.sql)，於資料庫中新增管理員測試帳號 `admin@test.com` (ROLE: ADMIN)，確保後台調解 API 能通過 `ROLE_ADMIN` 的 JWT 校驗。

### 2. API Client 封裝
- 新增 [orderApi.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/api/orderApi.ts)，將前端所有訂單操作（預約、變更、確認、報價、結案、爭議與調解、憑證上傳與退款確認）與後端 `OrderController` 的 10 個端點進行了完整類型封裝。

### 3. 多角色路由與動態 JWT 同步機制
- 修改 [RoleContext.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/contexts/RoleContext.tsx)：
  - `Role` type 擴充支援 `'admin'`。
  - 實作 **動態 JWT 同步機制**：在初始化或切換角色時（`setRole`），自動呼叫後端 `/api/auth/login` (搭配本地聯調帳號 sitter/owner/admin) 重新獲取對應的 JWT token 並寫入 localStorage，徹底解決越權操作或 403 Forbidden 錯誤。
- 修改 [App.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/App.tsx)：
  - 擴充 `ViewState` 自定義路由，納入 `owner-orders`、`owner-order-detail`、`admin-resolve` 等新頁面狀態。
  - Demo 首頁整合了「角色即時切換」按鈕（會觸發 token 重取）以及各功能直接 Demo 的快速跳轉入口，極大地方便了手動測試與自動化測試。

### 4. 訂單結案、爭議與雙向變更前端元件
- 新增 `OwnerOrders.tsx`：提供飼主端專屬的「進行中」與「歷史與爭議」Tab 訂單列表。
- 新增 `OwnerOrderDetail.tsx`：飼主訂單詳情頁，整合了【確認結案】與【回報爭議】實體按鈕。
- 新增 `OrderDisputeModal.tsx`：飼主回報爭議彈窗，支援爭議類別與詳情說明提交。
- 新增 `AdminResolvePanel.tsx`：管理員調解與強制結案面板，落實了二次密碼驗證的安全防線。
- 新增 `OrderModificationWizard.tsx`：日期與趟數調整精靈，支援一鍵【取消預約 (Cancellation)】；前端加載方案範圍實施日期越界防呆（`PLAN_NOT_IN_RANGE`）。
- 新增 `SitterModificationQuote.tsx`：保母微調報價與轉帳憑證上傳（對應 GCP Storage 模擬）。
- 新增 `OwnerModificationConfirm.tsx`：飼主確認變更對帳防線（金額不符阻擋之零信任防禦）與線下退款確認。

---

## 🧪 E2E 驗證結果

我們針對結案、爭議、管理端調解、變更卡控、取消預約以及零信任對帳撰寫了完整的 Playwright E2E 整合測試：
- [dispute-and-completion.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/dispute-and-completion.spec.ts)
- [order-modification.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/order-modification.spec.ts)

**測試執行指令**：
```bash
npx playwright test dispute-and-completion.spec.ts order-modification.spec.ts
```

**測試結果**：
```text
Running 6 tests using 4 workers

  6 passed (2.1s)
```

6 個測試情境全數 100% 綠燈通過！安全防衛與對帳邏輯驗證無誤。
