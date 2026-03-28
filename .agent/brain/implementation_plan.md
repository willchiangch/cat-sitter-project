# WhiskerWatch: 高階編輯感 PWA 前端轉型計畫 (V10)

根據提供的 Figma 設計組件與 `DESIGN.md` 規範，我們將把前端從基礎測試腳本升級為 **"The Intuitive Concierge" (直覺系管家)** 風格的高階 PWA。

## User Review Required

> [!IMPORTANT]
> **視覺設計核心**：採用「無框線 (No-Line)」哲學，透過背景色深淺 (Tonal Layering) 區分層次，而非傳統的 1px 邊框。
> **雙色系切換**：保母模式 (Amber 琥珀色) 與 飼主模式 (Blue 藍色) 的動態切換。
> **字體需求**：需引入 Google Fonts (`Plus Jakarta Sans` 與 `Manrope`)。

## Proposed Changes

### 1. 全域樣式與設計標籤 (Design Tokens) [NEW]
在 `index.css` 中透過 Tailwind 4 的 CSS 變數定義設計規範。
- **色彩組合**：導入 `surface-container-lowest` (#ffffff), `surface` (#f6f7f5) 等層次色。
- **字體體系**：標題使用 `font-headline`, 內文使用 `font-body`。
- **特效**：實作 `glass-effect` (backdrop-blur) 與 `ambient-shadow`。

### 2. 響應式佈局架構 [MODIFY]
修改 `App.jsx` 提供標準的移動優先容器。
- **MainLayout**：設定 `max-w-md mx-auto` 置中佈局。
- **Shared Components**：
    - `TopAppBar`: 包含角色切換 (Switch Role) 功能與 Glassmorphism 特效。
    - `BottomNavBar`: 提供導航功能，並具備圓角大半徑 (`rounded-t-[3rem]`)。

### 3. 主題切換狀態管理 [NEW]
使用 **Zustand** 管理目前的 UI Mode (`SITTER` | `CLIENT`)。
- 根據 Role 自動切換全域主題色（從 Amber 漸層切換至 Blue 漸層）。
- 此狀態將同步影響 API 請求的認證與權限。

### 4. 首頁與儀表板實作 (Mockup to React) [NEW]
將提供的 `code.html` 結構轉化為 React 組件。
- `SitterDashboard`: 包含今日服務卡片 (Today's Service)、財務簡報 (Dashboard Insights) 與時間軸。
- `ClientDashboard`: 包含貓咪護照 (Cat Passport) 與服務日誌導航。

### 5. 核心依賴安裝 [COMMAND]
安裝必要的開發套件：
- `react-router-dom` (導航)
- `zustand` (狀態管理)
- `framer-motion` (微交互動畫)
- `lucide-react` & `material-symbols` (圖示)

---

## Open Questions

1. **圖示方案**：設計稿使用了 `Material Symbols Outlined`，是否確認以此作為主要圖示庫？
2. **多語系支持**：目前設計稿為英文，是否需要在開發初期就導入 i18next 支持繁體中文切換？

## Verification Plan

### Automated Tests
- Playwright 執行響應式截圖比對，確保與設計稿一致。
- 驗證「角色切換」時，UI 色系與內容是否正確連動。

### Manual Verification
- 在測試手機上驗證「Bottom Navigation」的 Thumb-zone 人體工學。
- 確認「玻璃擬態」特效在低階設備上的效能表現。
