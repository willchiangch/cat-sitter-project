# 保母專業經營工具整合與穩定化計畫

本計畫旨在將 Sophia (Sitter Smoke) 的專業管理工具完整整合至 WhiskerWatch Profile 頁面，並確保在 E2E 測試環境下的高度穩定性。

## 使用者評論與決策
*   **證件加密**：僅對證件照片進行加密處理。
*   **測試優先順序**：優先確保本地自動化測試通過，再考慮 CI/CD 整合。

## 擬議變更

### 1. 前端組件 (Auth/Profile)
#### [修改] [Profile.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Profile.jsx)
- **API 解耦**：將個人資料獲取與日曆狀態獲取拆分，避免 `Promise.all` 導致的連鎖失敗。
- **UI 增強**：新增「專業經營工具」區塊，為保母提供服務方案、問卷與信任圈的快捷入口。
- **加載狀態優化**：增加 `finally` 區塊，確保無論 API 成功或失敗，加載動畫都會消失。

### 2. 後端控制器 (Calendar)
#### [修改] [CalendarSyncController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/controller/v1/CalendarSyncController.java)
- **防禦性編程**：在 `/status` 端點加入 Profile 存在性檢查，避免出現 `RuntimeException` (500 錯誤)。

### 3. 自動化測試 (E2E)
#### [修改] [AuthPage.js](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/tests/pages/AuthPage.js) / [ProfilePage.js](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/tests/pages/ProfilePage.js)
- **認證注入**：改用 Playwright `context().addInitScript` 進行全域 `localStorage` 注入，確保認證狀態在路由守衛 (Route Guard) 執行前生效。
- **SPA 導航優化**：針對 React Router 的客戶端導航，使用 `waitForFunction` 監控 `pathname` 變化。

## 驗證計畫

### 自動化測試
- 執行 `npx playwright test tests/e2e/sitter-business.spec.js`。
- 確認認證查核與工具面板導覽正常。

### 手動驗證
- 使用 Browser Subagent 檢查 `localhost:5173/profile`。
- 確認「專業經營工具」區塊顯示正確的連結。
