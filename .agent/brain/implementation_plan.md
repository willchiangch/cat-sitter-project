# 重構前端以對齊 Stitch 設計系統 (實作計畫)

當前前端使用的是通用樣式與顏色。我們將重構程式碼，使其嚴格遵循 Stitch 的 **"The Intuitive Concierge" (直覺管家)** 設計系統。該系統強調社論感美學、分層堆疊（無框線原則）以及高端字體。

## 擬議變更

### [修改] [global.css](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/styles/global.css)
- 導入 Google Fonts：`Plus Jakarta Sans` (標題) 與 `Manrope` (內文)。
- 定義所有 Stitch 設計標記的 CSS 變數：
  - 分層 (Surfaces)：`surface`, `surface-low`, `surface-lowest` 等。
  - 保母主題 (琥珀色)：`primary`, `primary-container`, `on-primary`。
  - 飼主主題 (藍色)：`primary`, `primary-container`, `on-primary`。
- 落實 **「無框線原則 (No-Line Rule)」**：
  - 移除通用邊框。
  - 使用背景色位移（例如在 `surface` 上疊加 `surface-low`）來呈現層次感。
- 落實 **環境光發光 (Ambient Glow)**：
  - 使用擴散陰影：`0 8px 24px rgba(45, 47, 46, 0.04)`。
- 落實 **玻璃擬態 (Glassmorphism)**：
  - 針對導航欄與 Bottom Sheets：`backdrop-blur: 12px` 與 `rgba(246, 247, 245, 0.8)`。

### [修改] [AppShell.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/layout/AppShell.tsx)
- 更新外殼組件以使用新的 Surface 標記。
- 確保最大寬度與置中對齊符合社論感外觀。

## 驗證計畫

### 自動化測試
- 執行現有的 E2E 測試，確保樣式變更未破壞功能。
- `cd frontend && npx playwright test`

### 手動驗證
- 驗證字體渲染效果。
*   切換角色時驗證顏色切換是否精準。
- 確認邊框是否已被色調過渡取代。
- 驗證底部導覽（若適用）的玻璃擬態效果。
