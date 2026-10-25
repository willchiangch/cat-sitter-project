# SD-FRONTEND-SPEC: 前端架構與實作規範 (WhiskerWatch PWA)

## 1. 架構定位與技術選型

本前端為雙角色 (Sitter/Client) PWA，對應後端 Spring Boot 4.0.6 API。
採取 Mobile-first 設計，並遵循 `SD-GLOBAL-SPEC` 容器化與資源控制標準。

### 1.1 核心技術棧
| 類別 | 技術 | 規範與限制 |
|:---|:---|:---|
| 框架 | **Vite + React 19 (SPA)** | Build 後透過 nginx:alpine 部署，不使用 SSR。 |
| 語言 | **TypeScript** | 嚴格模式開啟。 |
| 樣式 | **Vanilla CSS (CSS Variables)** | 禁止使用 Tailwind。採雙主題 (Amber/Blue) 動態切換。 |
| 伺服器狀態 | **React Query (TanStack)** | 唯一負責管理所有從 API 取得的非同步資料與 Cache。 |
| UI 狀態 | **Context API** | 僅管理純前端狀態（如：角色切換、Modal 開關），禁止將 API 回傳結果存入 Context。 |
| HTTP 客戶端 | **Axios** | 需封裝攔截器，處理 Auth 重試邏輯。 |
| PWA 支援 | **vite-plugin-pwa** | 需配置 Service Worker 處理離線快取（如：媒體庫暫存）。 |
| 測試 | **Playwright** (E2E) <br> **Vitest** (Unit) | E2E 測試放置於 `frontend/e2e/` 與原始碼同倉管理。 |
| 代碼風格 | **ESLint + Prettier** | 單引號、無尾隨逗號，遵循後端統一排版精神。 |

---

## 2. 實戰防雷機制 (關鍵基礎設施)

在開始任何業務畫面開發前，必須落實以下三大基礎設施配置，以避免後續嚴重的架構痛點：

### 2.1 Axios 攔截器的「併發刷新 (Concurrency) 風暴」防禦
- **場景**：SPA 載入時，多個 Component 同時發出 API 請求（如：撈使用者資訊、撈訂單、撈通知）。若 Token 過期，會同時收到多個 401 Unauthorized 回應。
- **風險**：若無併發控制，前端會同時發送多個 Refresh Token 請求，導致後端判定異常（Token 劫持或重複使用）而全面報錯，造成使用者被強制登出。
- **實作規範 (`src/api/axiosClient.ts`)**：
  - 必須實作 **Request Queue (請求佇列)** 與 **`isRefreshing` 鎖定狀態**。
  - 當第一個 401 發生時，將 `isRefreshing` 設為 true，暫停後續攔截。
  - 將後續遇到 401 的請求封裝成 Promise 存入 Queue 中。
  - 待 Refresh API 成功取得新 Token 後，統一更新 Header 並依次 Resolve 釋放 Queue 中的請求重試。
  - **Refresh 失敗時只清「還是自己那顆」的 token（2026-10 backlog G2）**：送出 refresh 前記下當下的 `refreshToken`；失敗時若 localStorage 的 `refreshToken` 已不是那顆（別的分頁在這期間重新登入、寫入新 token——SD-000 單一 session 下舊 refresh token 隨即失效，refresh 必然 401），**不清除**，改用 localStorage 現有的 access token 重試原請求並放行 Queue。只有仍是同一顆（或為空）時才清除 `accessToken`/`refreshToken` 並 reject。過去無條件清除，會把別分頁剛拿到的新 token 一起清掉，兩個分頁一起被登出。迴歸：`e2e/axios-refresh-cross-tab.spec.ts`。
  - **session 失效一律導去登入（2026-10-24，驗收 N16）**：遇 401 時 localStorage 沒有 `refreshToken` 就**不送** `/auth/refresh`；refresh 失敗或沒有 refresh token 時清除 token 並 `window.dispatchEvent(new Event('auth:session-expired'))`（`SESSION_EXPIRED_EVENT`），`RequireAuth` 監聽此事件重新判斷 token、導向 `/login?redirect=<目前路徑>`（與未登入直接開受保護頁同一條路；公開頁沒有掛 `RequireAuth`，不會被導走）。`main.tsx` 的 `QueryClient` 預設 `retry` 對 401 不重試，其餘錯誤維持最多 3 次。過去 token 清掉後停在原頁「$ 0 + 載入失敗」，且 React Query 重試再送 3 次 `{ refreshToken: null }`。

- **登入後的角色（`localStorage.userRole`）以回應 `role` 為準（2026-10-25，fix-batch12 J5）**：`/auth/login`、`/auth/google`、`/auth/register/verify-otp`、WebAuthn 登入成功後一律呼叫 `src/contexts/loginRole.ts` 的 `applyLoginRole(data)`：先用回應的 `role`（`OWNER`/`CLIENT`→`client`、`SITTER`→`sitter`、`ADMIN`→`admin`），沒有可辨識的值才退回 JWT `role` claim（`decodeTokenRole`），**兩者都解不出就移除 `userRole`**，不得沿用。過去只看 JWT claim、解不出時不動 `userRole`，而 session 失效時攔截器只清 token、不清 `userRole`，下一位登入者就沿用上一位的角色。迴歸：`e2e/login-role-resolution.spec.ts`（保母登入 → session 失效 → 飼主以不可解碼 token 登入，舊版仍顯示「貓咪保母」）。

### 2.2 Vite PWA 快取策略限制 (Service Worker)
- **場景**：PWA Service Worker 預設行為可能過度快取網路請求。
- **風險**：如果把 `/api/*` 的回應也快取下來，會造成「資料不一致幽靈」。例如：使用者已點擊結案，但重整畫面後因讀取 SW Cache 卻依然顯示 `IN_PROGRESS`。
- **實作規範 (`vite.config.ts`)**：
  - 在配置 `vite-plugin-pwa` 時，必須在 `workbox` 選項中明確定義：**僅快取靜態資源 (HTML/CSS/JS/Fonts/Images)**。
  - **絕對不快取 `/api/` 結尾的網路請求**。API 的快取與失效管理 (Invalidation) 全權交由 React Query 負責。
  - **現行做法（2026-10-24 確認）**：`navigateFallbackDenylist: [/^\/api/]` 讓 `/api` 開頭的整頁導覽不回 precache 的 `index.html`；fetch/XHR 則**刻意不寫 `runtimeCaching`**——SW 沒有任何 route 命中 `/api` 時不會 `respondWith`，請求直接由瀏覽器走網路，不經 SW、不進任何 cache。過去的 `{ urlPattern: /^\/api\/.*$/, handler: 'NetworkOnly' }` 因 Workbox 的 RegExp route 是拿完整 href（`https://…`）比對而永遠不會命中，屬無效規則，已移除（實測新舊 build 的 `/api` 回應 `fromServiceWorker` 皆為 false，行為不變）。
  - **不要讓 SW 接手 `/api`**（例如改成 `({ url }) => url.pathname.startsWith('/api/')`）：除了沒有產品上的好處，SW 代發的請求 Playwright `page.route` 攔不到，E2E 的 API mock 會全部失效。E2E 已在 `playwright.config.ts` 全域 `serviceWorkers: 'block'`（E2E 不驗 SW/PWA 行為），正式 build 的 SW 導覽 precache 會讓攔 document 的 mock 失效（`.e2e-runs/20261024-1939` F1）。
  - **PWA smoke（2026-10-24，驗收弱化稽核建議）**：全域 `serviceWorkers: 'block'` 讓其他 spec 都不經過 Workbox，部署後 SW 回舊 `index.html`、引用的 hash chunk 已不在伺服器上（Firebase SPA rewrite 對缺檔回 200 + `index.html`，瀏覽器拿 HTML 當 JS → 白畫面）這類問題原本抓不到。`e2e/pwa-smoke.spec.ts` 以 `test.use({ serviceWorkers: 'allow' })` 開公開的 `/login`（不需要任何業務 API mock，也不用 `mockDemoSession`）：SW 註冊並啟用 → reload 後導覽 `fromServiceWorker()` 為 true、頁面被接管 → SW 回的 `index.html` 引用的每個 JS / CSS 以 `page.request`（不經 SW）取回須 200 且 Content-Type 為 JS / CSS、內容不是 HTML → 頁面發出的 `/api` 請求 `fromServiceWorker()` 為 false。SW 只在正式 build 註冊，`playwright.config.ts` 只在有 `BASE_URL`（CI 正式站、本機 `vite preview` 時 `BASE_URL=http://localhost:4173`）才納入。注意：目前 workbox 沒有 `clientsClaim`，首次載入的頁面不會被 SW 接管，下一次導覽才會。

### 2.3 雙主題與設計標記 (Design Tokens)
- **場景**：系統具備保母 (Amber 琥珀色) 與飼主 (Blue 藍色) 雙主題。
- **風險**：若在 Component 內寫死顏色或使用 ad-hoc 的邊框樣式，會破壞整體設計的一致性。
- **實作規範 (`src/styles/global.css`)**：
  - 利用 CSS Variables 綁定 `data-theme` 屬性。
  - 核心顏色映射：
    - **Sitter Theme**: `--color-primary` 映射至 Amber (#765600)。
    - **Client Theme**: `--color-primary` 映射至 Blue (#005e9f)。
  - 介面分層則共用語義化變數：
    - `var(--color-surface)`: 基座底層 (#f6f7f5)。
    - `var(--color-surface-low)`: 次要區塊或分段。
    - `var(--color-surface-lowest)`: 互動卡片或容器 (純白)。
  - **`--color-primary-container` 底上的文字一律用 `--color-on-primary-container`（2026-10-24，驗收 N18）**：sitter `#5c4300`（對 `#ffbf00` 5.6:1）、client `#00528c`（對 `#b3d4ff` 5.3:1）、admin `#334155`。過去直接用 `--color-primary`：sitter `#765600` 對 `#ffbf00` 只有 4.1:1、client 4.44:1，都未達 AA。適用：日誌頁「暫存草稿」、「已送出 / 已完工 (已送出)」徽章、訂單列表選中分頁、爭議類別選中鈕、門禁設定範圍鈕與鎖頭圖示、預約頁保母頭像佔位。迴歸：`e2e/ui-layout-contrast.spec.ts` N18（保母「暫存草稿」、飼主「進行中」分頁，舊版兩支紅）。

---
  - **Admin Theme**（2026-10-24，驗收 N7）：`--color-primary` #334155、漸層 `#1e293b → #475569`、`--color-on-primary` #f8fafc（對漸層兩端皆 ≥ 4.5:1）。`RoleContext` 一直會設 `data-theme="admin"`，過去 CSS 沒有這組變數，管理員畫面所有 `.btn-primary` 都是無底色的純文字。

### 2.4 390px 殼內的版面與按鈕規則（2026-10-24，驗收 N1~N6 / backlog 第 6 批 G5）
AppShell 內容區只有 390 − 48 = 342px，`<main>` 自己 `overflow:auto`。驗收截圖的溢出全部同源：**flex / grid 子項預設 `min-width:auto`（grid `1fr` 的下限是 min-content）**，遇到沒有斷點的長字串（Journey 時間戳名稱、Email）、不換行的按鈕列、`<input>` 預設寬度或寫死的多欄格線時，子項拒絕縮小、把整欄撐寬，真機上整頁可以左右滑，截圖則畫到殼外。規則：
  - 會放動態長字串的 flex 子項加 `minWidth: 0`（文字本身 `overflowWrap: 'anywhere'`），旁邊的圖示 / 按鈕 / 徽章 `flexShrink: 0`（徽章另加 `whiteSpace: 'nowrap'`，否則會被擠成一字一行）。
  - grid 欄寬用 `minmax(0, 1fr)`，不要用 `1fr`；行動版不要寫死多欄（`/admin/kyc` 的 `'1fr 320px'`）。多欄表格改堆疊清單。
  - 標題 + 按鈕列一律 `flexWrap: 'wrap'`。
  - 不在全域對 `<main>` 下 `overflow-wrap: anywhere`：會降低所有 flex 子項的 min-content，實測頁首 Logo「WhiskerWatch」被從字中間斷成「WhiskerWa / tch」，副作用比收益大，改在需要的元素上個別設定。
  - `.btn-primary` 的底色是 `background` 簡寫的漸層；要做次要按鈕時必須用 `background:` 覆蓋，只寫 `backgroundColor` 蓋不掉 `background-image`（驗收 N6：灰字疊藍漸層，對比度不到 4.5:1）。
  - `.btn-primary:disabled` 為 `opacity .45 + grayscale + not-allowed`（驗收 N2）；非 `.btn-primary` 的按鈕停用時自行給相同視覺。
  - 卡片角落用 `position:absolute` 疊放的按鈕（例如照護記事本條目的「移除」），旁邊欄位靠 `paddingRight` 讓位時，欄位所在的 flex 子項要 `minWidth: 0`、`<input>` 要 `width: '100%'`，否則 input 預設固有寬度撐破欄位、壓進讓出的空間（2026-10-24 驗收附帶觀察：「移除」疊在標題輸入框右上角 1.5px）。
  - 切換鈕的「未選中」狀態不要用 `opacity` 表示：半透明會讓文字與底色一起淡化，`/demo` 管理員主題未選中的切換鈕實際對比度只有 2.84:1。改用白底（`--color-surface-lowest`）+ `--color-primary` 字 + 主色細框，選中的維持 `.btn-primary` 實色漸層，並以 `aria-pressed` 標示目前角色。
  - **頁面容器左右內距統一（2026-10-24，驗收 N17）**：頁面最外層容器一律 `padding: PAGE_PADDING`（`src/components/layout/pageLayout.ts`，`'2rem 1.5rem'`），加上 `<main>` 的 1.5rem，內容左右各內縮 48px（約 294px 寬）。不要為了讓內容變寬而拿掉頁面容器的左右內距：KYC 審核詳情（`1.5rem 0`）與照護記事本管理頁（`1rem 0`）過去這樣做，換頁時左右邊界跳動 24px。需要更寬的區塊（例如 KYC 證件照）改縮小該卡片自身內距。迴歸：`layout.ts` 的 `expectStandardPageInset`（N17 方案設定頁對照、N4 記事本管理頁、`admin-kyc-detail.spec.ts` U1）。
  - 迴歸：`e2e/ui-layout-contrast.spec.ts`（`e2e/support/layout.ts` 的 `expectNoHorizontalOverflow` / `expectInside` / `expectNoOverlap` / `contrastOf` / `gradientContrastOf` / `effectiveGradientContrastOf`（把元素與祖先的 opacity 混色算進對比度））。
  - 已知未修（設計系統層，待設計決策）：sitter / client 主題的 `.btn-primary` 漸層亮端與 `--color-on-primary` 對比度不足（sitter `#fff1db` 對 `#ffbf00` 約 1.45:1、client `#edf3ff` 對 `#b3d4ff` 約 1.4:1），影響全站主要按鈕，不在個別頁面修補。


## 3. 目錄與元件架構

```text
frontend/
├── e2e/                         # Playwright 測試腳本 (與 UI 綁定)
├── src/
│   ├── api/
│   │   └── axiosClient.ts       # 實作 401 Queue 與 Refresh 邏輯
│   ├── components/
│   │   ├── layout/              # AppShell, AppHeader, BottomNav
│   │   ├── modals/              # BottomSheet (共用骨架), PetFormModal 等
│   │   └── ui/                  # Button, Card, StatusBadge (Design System 元件)
│   ├── contexts/
│   │   ├── RoleContext.tsx      # 管理 currentRole UI 狀態與 data-theme
│   │   └── AuthContext.tsx      # 管理登入狀態
│   ├── pages/
│   │   ├── sitter/              # 保母專屬畫面
│   │   ├── client/              # 飼主專屬畫面
│   │   └── shared/              # 共用畫面 (PetProfile, Booking Wizard)
│   └── hooks/                   # React Query custom hooks (如 useOrders, usePets)
```

---

## 4. E2E 測試與 data-testid 命名規範

為了確保 Playwright 測試腳本的強健性與可讀性，所有具備互動邏輯的 UI 元素，皆須掛載 `data-testid`。

**命名格式：`[role]-[screen]-[element/action]`**

| 類別 | 範例 | 說明 |
|:---|:---|:---|
| Tab 切換 | `data-testid="sitter-orders-tab-evaluating"` | 保母端訂單管理，點擊「評估中」分頁 |
| 互動按鈕 | `data-testid="sitter-order-eval-btn-confirm"` | 保母端報價頁面，點擊「確認發送報價」|
| 表單輸入 | `data-testid="sitter-order-eval-input-add-fee"` | 保母端報價頁面，輸入加價金額 |
| 列表卡片 | `data-testid="sitter-order-card-{orderId}"` | 列表中的個別訂單卡片 (動態 ID) |
| 共用模態框 | `data-testid="modal-plan-selection-overlay"` | 點擊外部關閉方案選擇 Modal |
| 全域元件 | `data-testid="btn-role-toggle"` | 右上角角色切換按鈕 |

**開發紀律**：在開發或修改 Component UI 的同時，必須同步檢視並維護對應的 `e2e/` 腳本，避免因 UI 重構導致測試大面積損壞。

### 4.1 mock E2E 的隔離規範（2026-10，驗收 N9 / backlog 第 6 批）

CI 的 mock spec 是對**正式站**跑的（`BASE_URL` = Firebase Hosting，`/api/**` 轉 Cloud Run）。漏 mock 的 API 過去會帶假 token 打到正式後端：401 → `/api/auth/refresh` 401/400 → token 被清 → 截圖停在錯誤畫面，斷言卻只看 URL 所以照樣綠（驗收 R1/R3），每次 CI 還在正式站製造假 401/400 噪音。規範：

- 以假 token 進站的 mock spec 一律用 `e2e/support/mockSession.ts` 的 `mockDemoSession(page, role)`；要自己控制登入流程（例如從匿名開始數 login 次數）的用 `mockSessionApis(page)`。
- 兩者都會在 **context 層**註冊 `/api/` catch-all：沒被任何 `page.route` 接手的請求一律回 **501 `E2E_UNMOCKED_API`** 並記錄，不會送到任何後端（`page.route` 永遠優先於 `context.route`，與註冊順序無關）。`/api/auth/refresh` 也攔下回 401 `E2E_MOCK_REFRESH` 並記錄；要測 refresh 行為的 spec 自行 `page.route` 覆蓋。
- spec 必須 `test.afterEach(({ page }) => assertNoUnmockedApi(page))`，有任何漏 mock 或走到 refresh 就紅。`npm run e2e:lint` 會擋「用了 mockDemoSession / mockSessionApis 卻沒呼叫 assertNoUnmockedApi」的 spec。
- 導頁後的落地頁也要 mock（例如 `mockOwnerOrderDetail()`、`mockOwnerOrderList()`），並在截圖前斷言業務內容（如 `order-status-badge`）已渲染，不能只斷言 URL。
- 尚未改用 mockSession 的舊 spec（不在上述機制內）見 `.e2e-runs/20261024-backlog/fix-batch6.md`「另外發現」。

---

## 5. 開發分階路線 (Implementation Roadmap)

本專案採分階段漸進交付，各階段均須通過對應的 E2E 測試驗證：

- **Phase 0: 基礎建設**：完成 Vite 專案初始化、CSS Theme 配置、Axios 併發鎖、PWA Service Worker 避坑設定。
- **Phase 1: 保母核心 P0**：完成訂單管理 (SitterOrders) 與評估報價 (OrderEvalView)。
- **Phase 2: 飼主核心 P0**：完成訂單追蹤 (ClientOrders) 與預約精靈 (PublicBookingPage 3-Step Wizard)。
- **Phase 3: 保母功能 P1**：完成首頁 (Dashboard)、任務面板 (ActiveService) 與設定頁。
- **Phase 4: 飼主功能 P1**：完成首頁、名單管理與共用毛孩護照。
- **Phase 5: 邊緣功能 P2**：完成通知系統與收款明細。
- *(註：Admin Dashboard (PRD-020) 為 Desktop-first 設計，不包含於本 PWA 第一階段交付範疇，延後至 Phase 6 獨立實作。)*

---

## 6. UI/UX 設計系統與核心原則 (Design System)

本專案遵循 Stitch 所定義之 **"The Intuitive Concierge" (直覺管家)** 設計風格，強調高品質、社論感 (Editorial) 的 PWA 體驗。

### 6.1 創意指針 (Creative North Star)
介面不應像一個冰冷的工具庫，而應像一份精緻的個性化服務誌。透過**大面積留白**、**非對稱佈局**與**層次化深度模型**，營造呼吸感與信任感。

### 6.2 核心設計規範 (The Golden Rules)
1.  **無框線原則 (The No-Line Rule)**：
    *   嚴禁使用 1px 實線邊框進行區塊分割。
    *   改用背景色偏移 (Background Shifts) 來界定範圍，例如：在 `surface` 背景上疊加 `surface-container-low` 區塊。
2.  **層次堆疊模型 (Layered Depth)**：
    *   將 UI 視為一系列實體分層。底層為 `#f6f7f5`，互動卡片為純白。
    *   **環境光發光 (Ambient Glow)**：不使用重陰影，改用極淡的擴散發光 (`0 8px 24px rgba(45, 47, 46, 0.04)`)。
3.  **玻璃擬態與漸層 (Glass & Gradient)**：
    *   **Signature CTA**: 必須使用 45 度線性漸層，從 `primary` 渡至 `primary-container`。
    *   **浮動面板**: 導航欄與 Bottom Sheets 應具備 `backdrop-blur: 12px` 玻璃效果。
4.  **非對稱美學 (Asymmetry)**：
    *   標題與功能按鈕應保持顯著的水平 gap，避免對稱排版帶來的廉價感。

### 6.3 字體規範 (Typography)
*   **Display & Headlines**: `Plus Jakarta Sans` - 用於品牌時刻與大標題，展現權威與親和力。
*   **Body & Labels**: `Manrope` - 高功能性無襯線字體，優化長時間閱讀保母日誌的清晰度。

### 6.4 狀態顯示文字（中文對照表，2026-10）
畫面上任何狀態碼都不得直接顯示英文，一律經由 `src/components/ui/statusLabels.ts` 轉換；新增狀態碼時只改這個檔案。查無對照時顯示「未知狀態」。訂單徽章元件 `StatusBadge`（`data-testid="order-status-badge"`、`data-status=<狀態碼>`）只負責顏色。

| 類別 | 狀態碼 → 顯示文字 |
| :--- | :--- |
| 訂單 `orders.status` | `PENDING` 待評估、`PENDING_PAYMENT` 待付款、`PAID` 已付款、`CONFIRMED` 已確認、`IN_PROGRESS` 服務進行中、`MODIFYING` 變更協商中、`REFUND_VERIFY` 待確認退款、`COMPLETED` 已結案、`CANCELLED` 已取消、`DISPUTED` 爭議處理中 |
| 行程 `visits.status` | `PENDING`/`CONFIRMED` 待執行、`IN_PROGRESS` 執行中、`DONE` 已完工、`CLOSED_BY_SYSTEM` 系統結案、`CANCELLED` 已取消 |
| 保母認證 `profiles.kyc_status` | `UNVERIFIED` 未認證、`PENDING_REVIEW` 審核中、`VERIFIED` 已認證、`REJECTED` 被駁回、`SUSPENDED` 已停權 |
| KYC 申請 `kyc_records.status`（管理端） | `PENDING` 待審核、`APPROVED` 已通過、`REJECTED` 被駁回 |

2026-10 前 `StatusBadge` 只認 6 種訂單狀態，其餘（`IN_PROGRESS` 等）直接顯示英文碼；行程清單、評估頁「非待評估」提示直接印 `visit.status`/`order.status`；KYC 徽章是「待審核 (PENDING)」中英混雜（驗收 P6 / Backlog 第 3 批 U5）。迴歸：`e2e/order-status-labels.spec.ts`（10 種訂單狀態逐一比對、行程清單不得出現 `（[A-Z_]+）`）、Journey C 步驟 8。

### 6.4.1 金額顯示（2026-10-24，驗收 N15）
畫面上所有金額一律經由 `src/utils/money.ts` 的 `formatMoney(amount)` 輸出 `$ 1,200`（負數 `$ -600`），不得在頁面自己寫 `$ {x.toLocaleString()}` 或直接印 `$ {plan.price}`。千分位固定 `en-US`，不跟瀏覽器語系（無參數 `toLocaleString()` 在 de-DE 會是 `1.200`）。過去方案卡（`SitterPlans`）、預約頁方案價格是沒有千分位的 `$ 1200`，公開頁是 `NT$ 600`，其餘頁面 `$ 1,200`。迴歸：`e2e/service-plans.spec.ts` 新增方案卡斷言 `$ 1,200`；Journey A 步驟 8 公開頁斷言 `$ 600` / `$ 900`。

### 6.5 按鈕對比度（WCAG AA，2026-10）
- 文字對背景對比度至少 4.5:1（一般文字）。
- `.btn-primary` 的底色是 `background: var(--color-primary-gradient)`（`background-image`）。要做次要/破壞性按鈕時，**必須用 `background` 簡寫覆蓋**，只寫 `backgroundColor` 蓋不掉漸層。
- 破壞性次要按鈕（例如保母核對面板「駁回憑證」）：白底 `--color-surface-lowest`、文字與框線 `--color-error`（#b02500 對 #ffffff 約 6.8:1）。
- 2026-10 前「駁回憑證」只設 `backgroundColor`，實際呈現金色漸層底配灰棕字（驗收 N3 / Backlog 第 3 批 U8）。迴歸：`e2e/payment-reject-button-contrast.spec.ts`（以 computed style 計算對比度）。

