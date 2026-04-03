# 全面重構前端 UI/UX 以對齊業務規格與前端設計書

> 最後更新：2026-04-04（Claude 深度探索 codebase 後重寫，含 E2E 測試計劃）
> 規格依據：`doc/frontend-spec.md`、`doc/business-requirements.md`
> 執行追蹤：`task.md`

---

## 背景與問題根因

根據人工測試回饋與 `doc/frontend-spec.md` 比對，目前前端實作僅有外殼。元件放置位置、tab 標籤、核心功能嚴重偏離規格。本計劃已完成 codebase 深度探索，確認了以下關鍵問題：

| 問題 | 受影響範圍 |
|------|-----------|
| `/explore` 路由在 `App.jsx` 不存在 → Client「保母」tab 是 live 404 | BottomNavBar + App.jsx |
| `Profile.jsx` 使用未宣告的 `setIsLoading` → Sitter profile runtime crash | Auth/Profile.jsx |
| `--color-outline` 未定義 → inactive nav icon 不可見 | index.css + BottomNavBar |
| Sitter Dashboard 含工具 tab bar（不屬於此處） | Sitter/Dashboard.jsx |
| Sitter Orders 用 6 pill filter（應為 3-tab） | Sitter/Orders.jsx |
| Finance 無 PENDING/HISTORY tab 結構 | Sitter/Finance.jsx |
| Notifications 共用混合角色 mock data | notificationStore.js |
| Client Profile 無寵物列表與個人資料編輯 | Auth/Profile.jsx |

---

## 技術棧與設計系統

- **React 19**, React Router 7, Framer Motion 12, Tailwind CSS 4, Zustand 5, i18next
- **設計 token**：`frontend/src/index.css` 中的 CSS 變數（`.mode-sitter` → amber `#765600`，`.mode-client` → blue `#005e9f`）
- **容器**：`max-w-md mx-auto h-screen`（mobile-first）
- **圖示**：Material Symbols（非 Lucide）

---

## E2E 測試現狀

**現有測試（不破壞）：**
```
frontend/tests/e2e/
├── auth/onboarding.spec.js           # onboarding 流程（不受影響）
├── sitter-business.spec.js           # Sitter Profile → 工具導航（需確認 tab bar 移除後）
└── sitter/booking-lifecycle.spec.js  # 完整訂單生命週期（需確認 DashboardPage POM 路由）

frontend/tests/pages/
├── AuthPage.js                       # smoke auth injection
├── ProfilePage.js                    # navigateTo{Packages,Questionnaire,TrustCircle}
├── DashboardPage.js                  # navigateToInboxOrOrders / openFirstPendingOrder
├── BookingPage.js                    # booking 4-step flow
└── SitterToolsPage.js                # packages/questions/buddy 驗證
```

**⚠️ 潛在問題：**
- `DashboardPage.navigateToInboxOrOrders()` 目前疑似指向 `/orders`，但 Sitter 訂單路由是 `/sitter/orders` → **需在 Phase 1 確認**
- `DashboardPage.openFirstPendingOrder()` 在 Sitter Orders 改為 3-tab 後，需先切換至「評估中」tab → **在 Phase 4 修正**

---

## Phase 1 — 基礎修復

### 目標
修復全域 CSS token 缺失、統一 5 個 tab 標籤術語。這是所有後續工作的前提。

### 改動清單

**`frontend/src/index.css`**
```css
/* 在 :root block 加入 */
--outline: #8a8c8a;

/* 在 @theme block 加入 */
--color-outline: var(--outline);
```

**`frontend/src/components/shared/BottomNavBar.jsx`**

Sitter tabs（5 項）：
1. 行程 → `/sitter`（舊：我是保母）
2. 訂單 → `/sitter/orders`（舊：預約訂單紀錄）
3. 收款 → `/sitter/finance`（舊：財務收益對帳）
4. 通知 → `/notifications`（舊：多種鍵值）
5. 我的 → `/profile`（舊：多種鍵值）

Client tabs（5 項）：
1. 行程 → `/client`（舊：我是家長）
2. 訂單 → `/client/orders`（舊：預約訂單紀錄）
3. 保母 → `/client/sitters`（舊：探索專業保母 → `/explore`）⚠️ 路由也要改
4. 通知 → `/notifications`（舊：多種鍵值）
5. 我的 → `/profile`（舊：多種鍵值）

**`frontend/src/locales/zh-TW.json`**
- 新增 `common.tab_itinerary: "行程"`
- 更新 orders/finance/sitters/notifications/profile 等鍵值為短標籤

**`frontend/tests/pages/DashboardPage.js`（調查任務）**
- 確認 `navigateToInboxOrOrders()` 路由，若有 bug 同步修正

### 驗證
切換角色 → nav 顯示 行程/訂單/收款(保母)/通知/我的；inactive icon 可見。

---

## Phase 2 — 建立缺失頁面（唯一的新頁面）

### 目標
修復 Client 「保母」tab 的 live 404。這是唯一需要完全新建的頁面。

### 改動清單

**新建 `frontend/src/pages/Client/Sitters.jsx`**

仿照 `Pets.jsx` 的模式（header + list + action）：
```
- sticky header（「我的保母」標題）
- 搜尋列（input + 搜尋按鈕，輸入保母代碼）
- 保母卡片列表（頭像 + 名字 + 移除按鈕）
- 空白狀態（還沒加入任何保母時的提示）
```

先確認 `frontend/src/services/api.ts` 是否有 `clientFavoriteSitters` 相關 API（若無，以 mock 先行）。

**`frontend/src/App.jsx`**
```jsx
import ClientSitters from './pages/Client/Sitters'
// 在 Client Routes group 內加入：
<Route path="client/sitters" element={<ClientSitters />} />
```

### E2E（新建）
- `frontend/tests/e2e/client/sitters.spec.js`
- `frontend/tests/pages/ClientSittersPage.js`（POM：navigate / searchBySitterCode / getSitterCards）

### 驗證
Client 模式點擊「保母」→ `/client/sitters` 正常渲染，不 404。

---

## Phase 3 — Dashboard 重構

### 目標
移除錯置的元件，讓 Dashboard 單純顯示「今日行程」。

### 保母端 — `frontend/src/pages/Sitter/Dashboard.jsx`

**移除：**
- 整個 nav tab bar（Services/Questionnaire/TrustCircle 入口不屬於 Dashboard）
- StatCard 收益統計區塊（屬於 Finance 頁面）

**修復：**
- mock `nextVisit` 的時間欄位：從 `time: '14:00 PM'` 改為日期型顯示（如 `date: '週三 · 今日'`）
- 「進入服務面板」按鈕加入 `onClick={() => navigate('/sitter/service/' + visit.id)}`

**`frontend/src/components/sitter/UpcomingVisitCard.jsx`**
- 加入 `onSopClick` prop（查看 SOP 備註）
- 加入 `onPanelClick` prop（進入服務面板）

### 飼主端 — `frontend/src/pages/Client/Dashboard.jsx`

**移除：**
- "Recent Activity Teaser" 區塊（含英文硬編碼 "Miso enjoying treats" 等佔位文字）
- 底部 FAB 按鈕（`bg-gradient-to-r from-blue-500 to-blue-600` 硬編碼漸層，繞過 design token）

**加入：**
- 空白狀態：今日無預約時顯示中文提示

### E2E
更新 `sitter-business.spec.js`：
- 確認工具導航是否原本走 Dashboard tab bar（若是，改為走 ProfilePage POM）
- 新增 Scenario：Sitter Dashboard 只看到今日行程，不含工具 tabs

### 驗證
- Sitter Dashboard：無工具 tab bar、無 "14:00 PM"、「進入服務面板」可 navigate
- Client Dashboard：無英文佔位符、無硬編碼 FAB 按鈕

---

## Phase 4 — 訂單 Tab 對齊

### 目標
Sitter Orders 改為與 Client Orders 一致的 3-tab 結構（Client Orders 已正確實作，直接參考）。

### `frontend/src/pages/Sitter/Orders.jsx`

**替換** 6 個 flat pill filter（ALL/PENDING/QUOTED/CONFIRMED/COMPLETED/CANCELLED）→ 3-tab segmented control：

| Tab 標籤 | 對應 status |
|---------|-------------|
| 評估中 | `['PENDING', 'QUOTED']` |
| 進行中 | `['CONFIRMED', 'IN_PROGRESS']` |
| 歷史訂單 | `['COMPLETED', 'CANCELLED']` |

**參考** `frontend/src/pages/Client/Orders.jsx` 的 segmented control 實作（已有正確 pattern）。

**其他改動：**
- 移除頁面頂部的返回箭頭 header（Orders 是 primary nav tab，不是 drill-down）
- 各 tab 加入中文空白狀態文字

### E2E
更新 `frontend/tests/pages/DashboardPage.js`：
- `openFirstPendingOrder()` 現在需先點「評估中」tab 才能看到 PENDING 訂單

### 驗證
Sitter 訂單頁顯示 3-tab segmented control（非 6 pill）；booking-lifecycle test 仍通過。

---

## Phase 5 — Finance Tab 重構

### 目標
Finance 加入 PENDING/HISTORY 分頁結構，同時保留現有 payout modal 邏輯不變。

### `frontend/src/pages/Sitter/Finance.jsx`

```jsx
const [activeTab, setActiveTab] = useState('PENDING')
```

**待付款 tab（PENDING）：**
- 可提領餘額大字顯示（原有的 hero card）
- Request Payout 按鈕（原有的 payout modal trigger）
- 未完成交易列表

**收款紀錄 tab（HISTORY）：**
- 已完成（`COMPLETED` status）交易列表

**⚠️ 重要：** payout modal 的 state（`showModal`）和 handler 必須保持在 Finance 頁面層級，不要搬進 tab 子元件內。

其他改動：移除返回箭頭 header。

### E2E（新建）
- `frontend/tests/e2e/sitter/finance.spec.js`
- `frontend/tests/pages/FinancePage.js`（POM：navigate / switchToTab / getWithdrawableBalance）

### 驗證
Finance 顯示 2 個 tab；payout modal 仍正常運作。

---

## Phase 6 — Notifications 角色分流

### 目標
通知頁依角色過濾並分組顯示，移除混合角色的 mock data。

### `frontend/src/store/notificationStore.js`

每筆通知物件加入：
```js
role: 'SITTER' | 'CLIENT' | 'ALL'
```

更新 mock data：
- 保母收到的通知（新訂單等）→ `role: 'SITTER'`
- 飼主收到的通知（服務狀態等）→ `role: 'CLIENT'`
- 系統通知 → `role: 'ALL'`

新增 selector：`getNotificationsForRole(role)`

### `frontend/src/pages/Shared/Notifications.jsx`

```jsx
import { useThemeStore } from '../store/themeStore'
const { mode } = useThemeStore()
const filtered = notifications.filter(n =>
  n.role === 'ALL' || n.role === mode  // mode 為 'SITTER' 或 'CLIENT'
)
```

依 `contactName` 分組：Sitter 看飼主清單，Client 看保母清單。

### `frontend/src/components/shared/BottomNavBar.jsx`（badge）
- badge count 改用 `getNotificationsForRole` selector，只計算對應角色未讀數

### E2E（新建）
`frontend/tests/e2e/shared/notifications.spec.js`：
- Sitter 模式 → 通知頁只顯示 SITTER 角色通知
- Client 模式 → 通知頁只顯示 CLIENT 角色通知

### 驗證
切換角色後通知內容正確切換；badge count 正確。

---

## Phase 7 — Profile 頁面補全

### 目標
修復 Sitter profile crash，補齊雙端 Profile 的缺失功能。

### 7A — Critical Bug 修復（最優先執行）

**`frontend/src/pages/Auth/Profile.jsx`**

在既有 `useState` 宣告附近加入：
```jsx
const [isLoading, setIsLoading] = useState(true)
```

這一行缺失會導致 Sitter profile 掛載即 crash，**必須在所有其他 Profile 工作之前完成**。

### 7B — Sitter Profile 補充項目

**`frontend/src/pages/Auth/Profile.jsx`（Sitter 條件分支）**

**接單專屬網址 section（新增在頭像後、SaaS 方案卡前）：**
```jsx
// 顯示 booking URL（用 sitterData.slug 或 user.id 構成）
const bookingUrl = `https://whiskerwatch.com/book/${sitterData?.slug || user?.id}`
// 複製按鈕：參考同頁面 iCal URL 複製的模式（已有 copy pattern）
// 「預覽對外網頁」按鈕：window.open(bookingUrl)
```

**客群門禁管理 entry（加入「專業經營工具」section）：**
```jsx
<SettingsItem
  icon="manage_accounts"
  label="客群門禁管理"
  value="白名單 / 黑名單設定"
  onClick={() => navigate('/sitter/trust-circle')}
/>
```
（`/sitter/trust-circle` 已存在且有 whitelist 功能，可先用此入口）

**管理訂閱按鈕：**
- 目前為 dead button，至少綁定一個動作（開啟確認 modal 或外部連結）

### 7C — Client Profile 補充項目

**`frontend/src/pages/Auth/Profile.jsx`（Client 條件分支）**

**我的毛孩 section（新增）：**
```jsx
const [pets, setPets] = useState([])
const [showPetModal, setShowPetModal] = useState(false)

useEffect(() => {
  if (role === 'CLIENT') {
    petService.list().then(data => setPets(data))
  }
}, [role])

// render:
// - 精簡寵物卡片（頭像 + 名字）
// - 「新增寵物」→ setShowPetModal(true)（開啟 PetFormModal）
// - 「管理全部」→ navigate('/client/pets')（不重複實作完整 CRUD）

<PetFormModal
  open={showPetModal}
  onClose={() => setShowPetModal(false)}
  onSaved={() => { setShowPetModal(false); petService.list().then(setPets) }}
/>
```

PetFormModal 已存在於 `frontend/src/components/client/PetFormModal.jsx`，直接 import 使用。

**個人資料 section：**
- 顯示名稱等欄位改為可編輯（加入 edit mode toggle）

### E2E 更新

**更新 `frontend/tests/pages/ProfilePage.js`：**
```js
getBookingUrlSection() {
  return this.page.getByText(/接單專屬網址/i).locator('..')
}
copyBookingUrl() {
  return this.page.getByRole('button', { name: /複製/i }).click()
}
```

**更新 `frontend/tests/e2e/sitter-business.spec.js`：**
- 確認 Sitter profile 無 crash，看到「接單專屬網址」section

**新增 Client Profile scenario：**
- Client 進入「我的」→ 看到「我的毛孩」section
- 點擊「新增寵物」→ PetFormModal 開啟

### 驗證
- Sitter profile 無 crash
- 接單專屬網址 section 可見，複製按鈕可互動
- Client profile 顯示寵物卡片，可開啟 PetFormModal

---

## 全域驗證清單

| 驗證項目 | 指令/方式 |
|---------|----------|
| CSS token 修復 | 開啟 DevTools，確認 inactive nav icon 可見 |
| Tab 術語統一 | 切換 Sitter/Client 角色，底部 nav 顯示規格標籤 |
| Client 保母 tab 不 404 | Client 模式點擊「保母」→ 頁面渲染 |
| Sitter Dashboard 清潔 | 無工具 tabs，無 "14:00 PM"，按鈕可用 |
| Client Dashboard 清潔 | 無英文佔位符，無硬編碼 FAB |
| Sitter Orders 3-tab | 顯示評估中/進行中/歷史訂單 segmented control |
| Finance tabs | 待付款/收款紀錄 tabs；payout modal 正常 |
| Notifications 角色分流 | Sitter/Client 各自只看到對應通知 |
| Sitter Profile 無 crash | 點擊「我的」→ 成功渲染 |
| Client Profile 寵物管理 | 「我的毛孩」section 可見，PetFormModal 可開啟 |
| **E2E 全跑** | `npm run test:e2e` → 全部通過 |

---

## 新增/修改檔案速查

### src 變動
| 操作 | 檔案 | Phase |
|------|------|-------|
| 修改 | `frontend/src/index.css` | 1 |
| 修改 | `frontend/src/components/shared/BottomNavBar.jsx` | 1 |
| 修改 | `frontend/src/locales/zh-TW.json` | 1 |
| 新建 | `frontend/src/pages/Client/Sitters.jsx` | 2 |
| 修改 | `frontend/src/App.jsx` | 2 |
| 修改 | `frontend/src/pages/Sitter/Dashboard.jsx` | 3 |
| 修改 | `frontend/src/components/sitter/UpcomingVisitCard.jsx` | 3 |
| 修改 | `frontend/src/pages/Client/Dashboard.jsx` | 3 |
| 修改 | `frontend/src/pages/Sitter/Orders.jsx` | 4 |
| 修改 | `frontend/src/pages/Sitter/Finance.jsx` | 5 |
| 修改 | `frontend/src/store/notificationStore.js` | 6 |
| 修改 | `frontend/src/pages/Shared/Notifications.jsx` | 6 |
| 修改 | `frontend/src/pages/Auth/Profile.jsx` | 7 |

### tests 變動
| 操作 | 檔案 | Phase |
|------|------|-------|
| 調查/修改 | `frontend/tests/pages/DashboardPage.js` | 1, 4 |
| 修改 | `frontend/tests/e2e/sitter-business.spec.js` | 3, 7 |
| 修改 | `frontend/tests/pages/ProfilePage.js` | 7 |
| 新建 | `frontend/tests/e2e/client/sitters.spec.js` | 2 |
| 新建 | `frontend/tests/pages/ClientSittersPage.js` | 2 |
| 新建 | `frontend/tests/e2e/sitter/finance.spec.js` | 5 |
| 新建 | `frontend/tests/pages/FinancePage.js` | 5 |
| 新建 | `frontend/tests/e2e/shared/notifications.spec.js` | 6 |
