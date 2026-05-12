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

### 2.2 Vite PWA 快取策略限制 (Service Worker)
- **場景**：PWA Service Worker 預設行為可能過度快取網路請求。
- **風險**：如果把 `/api/*` 的回應也快取下來，會造成「資料不一致幽靈」。例如：使用者已點擊結案，但重整畫面後因讀取 SW Cache 卻依然顯示 `IN_PROGRESS`。
- **實作規範 (`vite.config.ts`)**：
  - 在配置 `vite-plugin-pwa` 時，必須在 `workbox` 選項中明確定義：**僅快取靜態資源 (HTML/CSS/JS/Fonts/Images)**。
  - **絕對不快取 `/api/` 結尾的網路請求**。API 的快取與失效管理 (Invalidation) 全權交由 React Query 負責。

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

---

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
