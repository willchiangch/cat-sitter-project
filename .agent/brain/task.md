# WhiskerWatch 開發進度與明日計畫 (V10)

## ✅ 已完成里程碑 (Completed)

### 1. 混合測試架構 (Hybrid Testing)
- [x] **Scenario 標註**：完成 5 份核心業務情境的測試層級定義。
- [x] **Vitest 元件測試**：`QuoteModal`, `CalendarSettings` 邏輯驗證。
- [x] **Playwright POM 重構**：建立 `AuthPage`, `BookingPage`, `DashboardPage` 頁面物件並移除舊腳本。

### 2. PWA 全面升級 (PWA Experience)
- [x] **核心配置**：安裝並設定 `vite-plugin-pwa` 與 Workbox 快取。
- [x] **資產產生**：產生 iOS/Android 多尺寸與自適應圖標。
- [x] **沉浸式設計**：更新 `index.html` Meta Tags，支援全螢幕與主題色。

---

## 📅 明日繼續：Phase 4 - Production-Ready 強化

- [ ] **4.1 架構與安全強化 (Infrastructure)**
  - [ ] **OpenAPI 前端自動產生**：同步前後端型別，解決 API 欄位命名斷層。
  - [ ] **GCS 隱私保護**：針對證件照實作 Pre-Signed URL (時效 15 分鐘)。
  - [ ] **CI/CD 自動化卡控**：將 Vitest 與 Playwright 排入 GitHub Actions 檢查。

- [ ] **4.2 核心業務補齊 (Feature Gaps)**
  - [ ] **實時報價通知 (SSE/WebSockets)**：保母改價時，飼主手機 PWA 立即彈窗。
  - [ ] **保母問卷編輯器 UI**：提供保母自定義入站問題的排序與編輯介面。
  - [ ] **撥款流水帳介面**：實體化保母端的提現申請與狀態管理。

---

## 💡 備忘錄 (Notes)
- 執行 E2E 測試前，請確保後端已開啟 `smoke` profile (Port 8081)。
- PWA 預快取已生效，部署後需注意 Service Worker 的版本更新提示。
