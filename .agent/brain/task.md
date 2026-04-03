# WhiskerWatch 前端重構任務清單

> 最後更新：2026-04-04（Claude 完成 codebase 深度探索後更新）
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

---

## 🔴 Critical Bugs（最優先，需在 Phase 1/2 解決）

- [ ] **[Phase 7A]** `frontend/src/pages/Auth/Profile.jsx`：使用 `setIsLoading` 但未宣告 `const [isLoading, setIsLoading] = useState(true)` → Sitter profile 掛載即 crash
- [ ] **[Phase 2B]** `frontend/src/App.jsx`：Client 「保母」tab 路由指向 `/explore`，但此路由完全不存在 → live 404，需新建 `Client/Sitters.jsx` 並補路由
- [ ] **[Phase 1A]** `frontend/src/index.css`：`--color-outline` 未定義於 `@theme` block → BottomNavBar inactive icon 不可見（`text-outline` class 無效）

---

## 📋 待執行

### Phase 1 — 基礎修復（無 API、純 UI，最安全）

- [ ] **[1A]** `frontend/src/index.css`
  - `:root` 加入：`--outline: #8a8c8a;`
  - `@theme` block 加入：`--color-outline: var(--outline);`

- [ ] **[1B]** `frontend/src/components/shared/BottomNavBar.jsx`
  - Sitter tab 1：標籤改為「行程」（舊：`t('auth.role_sitter')` = 我是保母）
  - Client tab 1：標籤改為「行程」（舊：`t('auth.role_parent')` = 我是家長）
  - 兩者 tab 2：標籤改為「訂單」（舊：`t('common.orders')` = 預約訂單紀錄）
  - Sitter tab 3：標籤改為「收款」（舊：`t('common.finance')` = 財務收益對帳）
  - Client tab 3：標籤改為「保母」（舊：`t('common.sitters')` = 探索專業保母）；**路由從 `/explore` 改為 `/client/sitters`**
  - 兩者 tab 4：標籤改為「通知」
  - 兩者 tab 5：標籤改為「我的」

- [ ] **[1C]** `frontend/src/locales/zh-TW.json`
  - 新增 `common.tab_itinerary: "行程"`
  - 更新各鍵值為短標籤（orders/finance/sitters/notifications/profile）

- [ ] **[1D-調查]** `frontend/tests/pages/DashboardPage.js`
  - 確認 `navigateToInboxOrOrders()` 的實際路由（目前疑似指向 `/orders`，但 Sitter 訂單路由是 `/sitter/orders`）
  - **若有 bug 需修正此 POM，確保 `booking-lifecycle.spec.js` 仍可正確導航**

---

### Phase 2 — 建立缺失頁面（修復 live 404）

- [ ] **[2A]** 新建 `frontend/src/pages/Client/Sitters.jsx`
  - 仿照 `frontend/src/pages/Client/Pets.jsx` 結構（header + list + modal）
  - 頂部搜尋列（輸入保母代碼）
  - 我的保母卡片列表（頭像、名字、移除按鈕）
  - 空白狀態 UI（無保母時顯示）
  - 先確認 `frontend/src/services/api.ts` 是否有 `clientSitterService` 相關 API

- [ ] **[2B]** `frontend/src/App.jsx`
  - 新增 `import ClientSitters from './pages/Client/Sitters'`
  - 新增路由：`<Route path="client/sitters" element={<ClientSitters />} />`

- [ ] **[2C] E2E** 新建 `frontend/tests/e2e/client/sitters.spec.js`
  - Scenario 1：Client 點擊「保母」tab → 路由 `/client/sitters` 正常渲染（不 404）
  - Scenario 2：搜尋保母代碼 → 卡片出現（需 mock API）

- [ ] **[2D] E2E POM** 新建 `frontend/tests/pages/ClientSittersPage.js`
  - `navigate()` — 導航至 `/client/sitters`
  - `searchBySitterCode(code)` — 輸入代碼並送出
  - `getSitterCards()` — 取得保母卡片列表

---

### Phase 3 — Dashboard 重構（中風險）

- [ ] **[3A]** `frontend/src/pages/Sitter/Dashboard.jsx`
  - **移除**整個 nav tab bar（Services / Questionnaire / TrustCircle tabs 不屬於此處）
  - **移除** StatCard 收益統計區塊（移至 Finance 頁）
  - **保留** `UpcomingVisitCard`（今日行程核心）
  - **修復** 時間顯示：mock data 中移除 `time: '14:00 PM'`，改為日期型顯示（如 "週三 · 今日"）
  - **修復** 按鈕 onClick：「進入服務面板」→ `navigate('/sitter/service/' + visit.id)`

- [ ] **[3B]** `frontend/src/components/sitter/UpcomingVisitCard.jsx`
  - 加入 `onSopClick` prop（查看 SOP 備註）
  - 加入 `onPanelClick` prop（進入服務面板）

- [ ] **[3C]** `frontend/src/pages/Client/Dashboard.jsx`
  - **移除** "Recent Activity Teaser" 區塊（含 "Miso enjoying treats" 等英文佔位符）
  - **移除** 底部 FAB 按鈕（硬編碼藍色漸層，繞過 design token）
  - **保留** `ClientVisitCard`
  - 加入今日無預約空白狀態

- [ ] **[3D] E2E** 更新 `frontend/tests/e2e/sitter-business.spec.js`
  - 確認是否有測試透過 Dashboard tab bar 導航（若有，改為透過 ProfilePage POM 導航）
  - 新增 Scenario：Sitter Dashboard 只顯示今日行程，不含服務方案/問卷/信任圈 tabs

---

### Phase 4 — 訂單 Tab 對齊（低-中風險）

- [ ] **[4A]** `frontend/src/pages/Sitter/Orders.jsx`
  - 將 6 個 flat pill filter（ALL/PENDING/QUOTED/CONFIRMED/COMPLETED/CANCELLED）替換為 3-tab segmented control
  - Tab 對應：評估中 = `['PENDING', 'QUOTED']`；進行中 = `['CONFIRMED', 'IN_PROGRESS']`；歷史訂單 = `['COMPLETED', 'CANCELLED']`
  - **參考** `frontend/src/pages/Client/Orders.jsx` 已有正確的 3-tab 實作
  - 移除返回箭頭 header（Orders 是 primary tab，不是 drill-down 頁面）
  - 加入各 tab 中文空白狀態文字

- [ ] **[4B] E2E** 更新 `frontend/tests/pages/DashboardPage.js`
  - `openFirstPendingOrder()` 方法：現在需先點擊「評估中」tab，才能看到 PENDING 訂單
  - 確保 `booking-lifecycle.spec.js` 中的訂單流程測試仍能通過

---

### Phase 5 — Finance Tab 重構（中風險）

- [ ] **[5A]** `frontend/src/pages/Sitter/Finance.jsx`
  - 加入 `const [activeTab, setActiveTab] = useState('PENDING')` state
  - 加入 segmented control（同 Orders 頁面的 tab 樣式）
  - **待付款** tab：顯示可提領餘額大字、Request Payout 按鈕、未完成交易列表
  - **收款紀錄** tab：顯示已完成（COMPLETED）交易
  - **⚠️ 保留 payout modal 邏輯不動**（state 和 handler 保持在 Finance 頁面層級，不要放進 tab 子元件）
  - 移除返回箭頭 header

- [ ] **[5B] E2E** 新建 `frontend/tests/e2e/sitter/finance.spec.js`
  - Scenario：進入「收款」tab → 看到「待付款」「收款紀錄」兩個 tabs
  - Scenario：「待付款」tab 顯示可提領餘額與 Request Payout 按鈕

- [ ] **[5C] E2E POM** 新建 `frontend/tests/pages/FinancePage.js`
  - `navigate()` — 導航至 `/sitter/finance`
  - `switchToTab(tabName)` — 切換「待付款」/ 「收款紀錄」
  - `getWithdrawableBalance()` — 讀取餘額數值

---

### Phase 6 — Notifications 角色分流（中風險）

- [ ] **[6A]** `frontend/src/store/notificationStore.js`
  - 為每筆通知物件加入 `role: 'SITTER' | 'CLIENT' | 'ALL'` 欄位
  - mock 資料更新：sitter 收到的通知（新訂單）→ `role: 'SITTER'`；client 收到的通知（服務更新）→ `role: 'CLIENT'`；系統通知 → `role: 'ALL'`
  - 加入 `getNotificationsForRole(role)` selector

- [ ] **[6B]** `frontend/src/pages/Shared/Notifications.jsx`
  - `import { useThemeStore }` 取得當前 mode（SITTER / CLIENT）
  - 依角色過濾通知：`notifications.filter(n => n.role === 'ALL' || n.role === mode)`
  - 依 `contactName` 分組顯示（Sitter 看飼主清單，Client 看保母清單）
  - 空白狀態：當前角色無通知時顯示提示

- [ ] **[6C]** `frontend/src/components/shared/BottomNavBar.jsx`（通知 badge）
  - Badge count 改用 `getNotificationsForRole` selector，只計算對應角色未讀數

- [ ] **[6D] E2E** 新建 `frontend/tests/e2e/shared/notifications.spec.js`
  - Scenario 1：Sitter 模式 → 通知頁只顯示 SITTER 角色通知
  - Scenario 2：Client 模式 → 通知頁只顯示 CLIENT 角色通知（不見 SITTER 通知）

---

### Phase 7 — Profile 頁面補全（最高複雜度）

- [ ] **[7A] Critical Bug** `frontend/src/pages/Auth/Profile.jsx`
  - 在其他 `useState` 宣告旁加入：`const [isLoading, setIsLoading] = useState(true)`
  - 這是 runtime crash，**必須在任何 Profile 相關工作之前修復**

- [ ] **[7B]** 同上 — Sitter Profile 補充
  - 新增「接單專屬網址」section：
    - 顯示保母的 booking URL：`https://whiskerwatch.com/book/{sitterData.slug}`（或用 user.id 構成）
    - 複製按鈕（參考同頁面 iCal URL 複製的模式）
    - 「預覽對外網頁」按鈕（`window.open` 或 `navigate` 至 `/booking/sitter/...`）
  - 新增「客群門禁管理」`SettingsItem`（icon: `manage_accounts`）→ navigate 至 `/sitter/trust-circle`
  - 綁定「管理訂閱」按鈕的 `onClick`（目前為 dead button，至少開啟確認 modal 或外部連結）

- [ ] **[7C]** 同上 — Client Profile 補充
  - 新增「我的毛孩」section：
    - `useEffect` fetch `petService.list()` 取得寵物資料
    - 顯示精簡寵物卡片（頭像 + 名字）
    - 「新增寵物」按鈕 → 開啟 `PetFormModal`（已存在：`frontend/src/components/client/PetFormModal.jsx`）
    - 「管理全部」連結 → navigate 至 `/client/pets`（完整 CRUD 頁面已存在，不重複實作）
  - 「個人資料」section：將顯示名稱等欄位改為可編輯

- [ ] **[7D] E2E** 更新 `frontend/tests/e2e/sitter-business.spec.js`
  - 更新現有 Scenario：確認 Sitter profile 成功渲染（無 crash）
  - 新增 Scenario：看到「接單專屬網址」section，複製按鈕可互動

- [ ] **[7E] E2E POM** 更新 `frontend/tests/pages/ProfilePage.js`
  - 新增 `getBookingUrlSection()` — locator 找到「接單專屬網址」section
  - 新增 `copyBookingUrl()` — 點擊複製按鈕

- [ ] **[7F] E2E** 新建 Client Profile 寵物管理 scenario（可加入 `frontend/tests/e2e/client/` 目錄）
  - Client 模式進入「我的」tab → 看到「我的毛孩」section
  - 點擊「新增寵物」→ `PetFormModal` 開啟

---

## 最終驗證

- [ ] `npm run test:e2e` — 所有 E2E tests pass（含新增的 sitters / finance / notifications spec）
- [ ] 手動切換 Sitter/Client 角色，驗證 5 個 tab 標籤為：行程 / 訂單 / 收款(保母) / 通知 / 我的
- [ ] 手動逐 tab 確認功能符合 `doc/frontend-spec.md` 所列規格

---

## 新增/修改檔案速查表

| 操作 | 檔案 |
|------|------|
| 修改 | `frontend/src/index.css` |
| 修改 | `frontend/src/components/shared/BottomNavBar.jsx` |
| 修改 | `frontend/src/locales/zh-TW.json` |
| 新建 | `frontend/src/pages/Client/Sitters.jsx` |
| 修改 | `frontend/src/App.jsx` |
| 修改 | `frontend/src/pages/Sitter/Dashboard.jsx` |
| 修改 | `frontend/src/components/sitter/UpcomingVisitCard.jsx` |
| 修改 | `frontend/src/pages/Client/Dashboard.jsx` |
| 修改 | `frontend/src/pages/Sitter/Orders.jsx` |
| 修改 | `frontend/src/pages/Sitter/Finance.jsx` |
| 修改 | `frontend/src/store/notificationStore.js` |
| 修改 | `frontend/src/pages/Shared/Notifications.jsx` |
| 修改 | `frontend/src/pages/Auth/Profile.jsx` |
| 修改 | `frontend/tests/pages/DashboardPage.js` |
| 修改 | `frontend/tests/pages/ProfilePage.js` |
| 修改 | `frontend/tests/e2e/sitter-business.spec.js` |
| 新建 | `frontend/tests/e2e/client/sitters.spec.js` |
| 新建 | `frontend/tests/pages/ClientSittersPage.js` |
| 新建 | `frontend/tests/e2e/sitter/finance.spec.js` |
| 新建 | `frontend/tests/pages/FinancePage.js` |
| 新建 | `frontend/tests/e2e/shared/notifications.spec.js` |
