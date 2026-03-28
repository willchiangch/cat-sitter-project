# WhiskerWatch 前端架構轉型驗證 (V10 Walkthrough)

本階段已成功將前端從基礎測試腳本轉型為 **"The Intuitive Concierge" (直覺系管家)** 風格的高階 PWA 架構。我們建立了穩定的基礎，並根據 Figma 設計資產實作了核心 UI、多語系支持與認證流程。

## 已完成的架構升級

### 1. 設計系統與全域樣式 (Design Tokens)
- **字體匯入**：整合 Google Fonts `Plus Jakarta Sans` (標題) 與 `Manrope` (內文)。
- **Tailwind 4 配置**：在 `index.css` 中實作了完整的色階體系，包含：
    - `Sitter Mode`: 琥珀橘梯度 (Amber)。
    - `Client Mode`: 清爽藍梯度 (Blue)。
    - **No-Line 哲學**：全面使用背景層次 (Tonal Layering) 取代 1px 硬邊框。
    - **視覺特效**：實作了 `glass-effect` (毛玻璃) 與 `ambient-shadow` (環境陰影)。
- **Material Symbols 精細化**：將所有圖示的 `wght` (粗細) 設定為 **300**，營造出優雅、輕盈的編輯感 (Editorial) 質感。

### 2. 多語系架構 (i18next Integration)
- **i18n 框架**：導入 `i18next` 與 `react-i18next`。
- **本地語言支援**：提供完整的 **繁體中文 (`zh-TW`)** 與 **英文 (`en`)** 對照：
    - 使用了更具溫度的專業詞彙，例如將 Login 譯為「進入管家服務」。
    - 支援自動檢索瀏覽器語言偏好。
- **全站覆蓋**：從登入頁面、註冊頁面到 `TopAppBar` 與 `BottomNavBar` 的所有標籤，皆已改用翻譯鍵值 (`t()`)。

### 3. 狀態管理與佈局
- **Zustand Store**：
    - `themeStore`: 管理 `SITTER` / `CLIENT` 模式切換。
    - `authStore`: 管理 JWT Token 與使用者狀態，支援持久化存儲。
- **Layout Engine**：
    - `MainLayout`: 置中的 `max-w-md` 佈局容器。
    - `Shared Components**: `TopAppBar` (動態角色切換) 與 `BottomNavBar` (對應多語系標籤)。

### 4. 高保真認證頁面 (Auth Flow)
- **Login/Register**：
    - 串接後端 Auth API，支援角色選擇 (`SITTER` / `CLIENT`)。
    - 加入了流暢的過場動畫與高級質感按鈕。

## 驗證結果

- **佈局驗證**：在各語系下，佈局皆能在大留白與高品質字體下維持平衡。
- **多語系切換**：透過 `i18n.changeLanguage()` 測試，UI 文字可即時無感切換。
- **圖示質感**：300 粗細的圖示在視覺上與 `Plus Jakarta Sans` 的現代感完美契合。

## 下一步開發
- **Dashboard 實作**：開始將 `sitter_dashboard` 與 `client_dashboard` 從 HTML 轉換為功能性的 React 組件。
- **業務資料對接**：串接後端各項服務 (Appointments, Earnings, Profile Settings)。
