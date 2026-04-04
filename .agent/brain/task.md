# WhiskerWatch 前端重構任務清單

> 最後更新：2026-04-04（Phase 1–7 + E2E 批次 + 後端修復全部完成；**16/16 tests pass**）
> 規格依據：`doc/frontend-spec.md`、`doc/business-requirements.md`
> 測試架構：Playwright E2E + POM（`frontend/tests/`）

---

## ✅ 已完成

- [x] 審查商業需求與 SASS 程式文件，比對現有 UI 實作落差
- [x] 產出前端 UI/UX 重構草稿計劃（implementation_plan.md）
- [x] 確認獨立「客群名單門禁管理」與修正 Top/Bottom Bar 對比問題方向
- [x] 實作 UAT 登入控制（VITE_ENABLE_PASSWORD_LOGIN toggle）
- [x] 實作人臉辨識自拍（face_photo_url）取代身分證背面上傳
- [x] 更新 Profile.java、DTO、V15 migration 與 OpenAPI spec 同步

### Phase 1 — 基礎修復 ✅

- [x] **[1A]** `frontend/src/index.css`：`:root` 加入 `--outline: #8a8c8a;`，`@theme` 加入 `--color-outline: var(--outline);`
- [x] **[1B]** `frontend/src/components/shared/BottomNavBar.jsx`：統一 5 tab 標籤（行程/訂單/收款·保母/通知/我的）；Client tab 3 路由從 `/explore` 改為 `/client/sitters`；badge 支援 Sitter/Client 分離計數
- [x] **[1C]** `frontend/src/locales/zh-TW.json`：新增 `common.tab_itinerary/tab_orders/tab_finance/tab_sitters/tab_notifications/tab_profile` 6 個短標籤鍵值
- [x] **[1D]** `frontend/tests/pages/DashboardPage.js`：確認並修正 `navigateToInboxOrOrders()` 路由為 `/sitter/orders`

### Phase 2 — 建立缺失頁面 ✅

- [x] **[2A]** 新建 `frontend/src/pages/Client/Sitters.jsx`（header + 搜尋列 + 保母卡片列表 + 空白狀態；backend API 未備妥，以 mock 先行）
- [x] **[2B]** `frontend/src/App.jsx`：新增 `import ClientSitters` + 路由 `<Route path="client/sitters" />`

### Phase 3 — Dashboard 重構 ✅

- [x] **[3A]** `frontend/src/pages/Sitter/Dashboard.jsx`：移除工具 tab bar + StatCard；mock data `time` 改為 `date`；加入 `onPanelClick` navigate
- [x] **[3B]** `frontend/src/components/sitter/UpcomingVisitCard.jsx`：加入 `onSopClick`、`onPanelClick` props；header 顯示 `date`
- [x] **[3C]** `frontend/src/pages/Client/Dashboard.jsx`：移除英文 "Recent Activity Teaser"；移除硬編碼 FAB；加入今日無預約中文空白狀態

### Phase 4 — 訂單 Tab 對齊 ✅

- [x] **[4A]** `frontend/src/pages/Sitter/Orders.jsx`：6 pill filter → 3-tab segmented control（評估中/進行中/歷史訂單）；移除返回箭頭 header；各 tab 中文空白狀態
- [x] **[4B]** `frontend/tests/pages/DashboardPage.js`：`openFirstPendingOrder()` 先點「評估中」tab 再點訂單

### Phase 5 — Finance Tab 重構 ✅

- [x] **[5A]** `frontend/src/pages/Sitter/Finance.jsx`：修復 `AnimatePresence` 未 import；加入 `activeTab` state；2-tab segmented control（待付款/收款紀錄）；移除返回箭頭 header；payout modal 保持頁面層級；英文文字全面中文化

### Phase 6 — Notifications 角色分流 ✅

- [x] **[6A]** `frontend/src/store/notificationStore.js`：mock data 加入 `role` 欄位（SITTER/CLIENT/ALL）；新增 `getNotificationsForRole(role)` 和 `getUnreadCountForRole(role)` selectors
- [x] **[6B]** `frontend/src/pages/Shared/Notifications.jsx`：import `useThemeStore`；依角色過濾通知；移除返回箭頭；英文 "Recent Updates" → 「最新通知」
- [x] **[6C]** `frontend/src/components/shared/BottomNavBar.jsx`：badge count 改用 `getUnreadCountForRole(mode)`

### Phase 7 — Profile 補全 ✅

- [x] **[7A] Critical Bug** `frontend/src/pages/Auth/Profile.jsx`：加入 `const [isLoading, setIsLoading] = useState(true)`
- [x] **[7B]** Sitter Profile：新增「接單專屬網址」section（複製 + 預覽按鈕）；新增「客群門禁管理」SettingsItem；綁定「管理訂閱」button
- [x] **[7C]** Client Profile：新增 `pets`/`showPetModal` state；pets useEffect；「我的毛孩」section（卡片 + 新增 + 管理全部）；PetFormModal 掛載

---

## ✅ 已完成 — E2E 批次（2026-04-04）

### 現有測試更新

- [x] **[E2E-1]** `frontend/tests/e2e/sitter-business.spec.js`
  - 新增 Scenario：Sitter Profile 成功渲染，可見「接單專屬網址」section（含複製/預覽按鈕）
  - 新增 Scenario：Sitter Dashboard 只顯示今日行程，不含工具 tabs

- [x] **[E2E-2]** `frontend/tests/pages/ProfilePage.js`
  - 新增 `getBookingUrlSection()` — locator 找到「接單專屬網址」section
  - 新增 `copyBookingUrl()` — 點擊複製按鈕

- [x] `frontend/tests/pages/AuthPage.js`
  - 新增 `injectClientSmokeAuth(targetUrl)` — 注入 CLIENT 角色 auth + theme（mode: CLIENT）

### 新建 POM

- [x] **[E2E-3]** `frontend/tests/pages/ClientSittersPage.js`
  - `navigate()`、`searchBySitterCode(code)`、`getSitterCards()`

- [x] **[E2E-4]** `frontend/tests/pages/FinancePage.js`
  - `navigate()`、`switchToTab(tabName)`、`getWithdrawableBalance()`
  - Finance API mock: `**/payments/payuni/sitter-summary`

### 新建 E2E Spec

- [x] **[E2E-5]** `frontend/tests/e2e/client/sitters.spec.js`
  - Scenario：Client 點擊「保母」tab → `/client/sitters` 正常渲染（不 404）
  - Scenario：搜尋保母代碼 → UI 響應（spinner 或 empty state）

- [x] **[E2E-6]** `frontend/tests/e2e/sitter/finance.spec.js`
  - Scenario：Finance 頁顯示「待付款」「收款紀錄」兩個 tabs
  - Scenario：「待付款」tab 顯示可提領餘額（$1,500）與申請提款按鈕
  - Scenario：切換到「收款紀錄」tab → 不顯示 hero balance card

- [x] **[E2E-7]** `frontend/tests/e2e/shared/notifications.spec.js`
  - Scenario：Sitter 模式 → 顯示 n2(SITTER)+n3(ALL)，不顯示 n1(CLIENT)
  - Scenario：Client 模式 → 顯示 n1(CLIENT)+n3(ALL)，不顯示 n2(SITTER)

- [x] **[E2E-8]** `frontend/tests/e2e/client/client-profile.spec.js`
  - Scenario：Client Profile 顯示「我的毛孩」section + 新增寵物/管理全部按鈕
  - Scenario：點擊「新增寵物」→ PetFormModal 開啟（顯示「新增貓咪至保險箱」標題）
  - Pets API mock: `**/clients/me/pets` → []

---

## 最終驗證

- [x] `npm run test:e2e` — **16/16 pass**（全部通過，含後端 + POM 修復）
- [ ] 手動切換 Sitter/Client 角色，確認 5 tab 標籤：行程 / 訂單 / 收款(保母) / 通知 / 我的
- [ ] 手動逐 tab 確認功能符合 `doc/frontend-spec.md` 所列規格

---

## 新增/修改檔案速查表

| 操作 | 檔案 | 狀態 |
|------|------|:----:|
| 修改 | `frontend/src/index.css` | ✅ |
| 修改 | `frontend/src/components/shared/BottomNavBar.jsx` | ✅ |
| 修改 | `frontend/src/locales/zh-TW.json` | ✅ |
| 新建 | `frontend/src/pages/Client/Sitters.jsx` | ✅ |
| 修改 | `frontend/src/App.jsx` | ✅ |
| 修改 | `frontend/src/pages/Sitter/Dashboard.jsx` | ✅ |
| 修改 | `frontend/src/components/sitter/UpcomingVisitCard.jsx` | ✅ |
| 修改 | `frontend/src/pages/Client/Dashboard.jsx` | ✅ |
| 修改 | `frontend/src/pages/Sitter/Orders.jsx` | ✅ |
| 修改 | `frontend/src/pages/Sitter/Finance.jsx` | ✅ |
| 修改 | `frontend/src/store/notificationStore.js` | ✅ |
| 修改 | `frontend/src/pages/Shared/Notifications.jsx` | ✅ |
| 修改 | `frontend/src/pages/Auth/Profile.jsx` | ✅ |
| 修改 | `frontend/tests/pages/DashboardPage.js` | ✅ |
| 修改 | `frontend/tests/pages/AuthPage.js` | ✅ |
| 修改 | `frontend/tests/pages/ProfilePage.js` | ✅ |
| 修改 | `frontend/tests/e2e/sitter-business.spec.js` | ✅ |
| 新建 | `frontend/tests/e2e/client/sitters.spec.js` | ✅ |
| 新建 | `frontend/tests/pages/ClientSittersPage.js` | ✅ |
| 新建 | `frontend/tests/e2e/sitter/finance.spec.js` | ✅ |
| 新建 | `frontend/tests/pages/FinancePage.js` | ✅ |
| 新建 | `frontend/tests/e2e/shared/notifications.spec.js` | ✅ |
| 新建 | `frontend/tests/e2e/client/client-profile.spec.js` | ✅ |
