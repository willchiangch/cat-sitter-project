# WhiskerWatch: 專案現況評估與健康度報告 (V10 / 2026-03-30)

本報告總結了完成 **混合測試架構 (Vitest + Playwright POM)** 與 **PWA 全面升級** 後的專案現況，並點出邁向 Production-Ready（正式上線準備）前亟需調整的重點項目。

---

## 🟢 系統亮點與已防禦範圍 (Current Strengths)

### 1. 穩定且極速的開發體驗
- **Hybrid Testing 防護網**：成功分離了高成本的跨角色 E2E (Playwright) 與低成本的元件邏輯測試 (Vitest)，CI/CD 執行時間大幅縮短，Flaky Tests 降至最低。
- **PWA 桌面級體驗**：iOS/Android 已全部支援沉浸式安裝，並整合了 Workbox 網路降級快取策略 (Network-First & Cache-First)。

### 2. 核心業務邏輯的成熟度
- 雙軌行事曆同步、多檔案媒體持久化、VIP 熟客免問卷白名單、雙重角色帳號體系等都已經具備後端實體化 (Realization) 支援。

---

## 🏗️ 亟需調整的工程架構 (Architectural Adjustments)

作為一個接案與媒合性質的系統，目前在架構上還有幾個容易產生技術債或體驗扣分的「斷層」：

### 1. API 契約管理缺失 (缺少 OpenAPI / Swagger 自動生成)
- **現狀**：前端的 `services/api.js` 是純手寫的。當後端 Spring Boot 更改 DTO 屬性名稱時，前端在執行時才會報錯（幸好我們剛剛掛了單元測試）。
- **調整建議**：導入 `openapi-spec-generation` 技能，讓 Spring Boot 啟動時自動產生 YAML，直接編譯出 Frontend 定義檔，達到前後端 API 型別 100% 同步。

### 2. 靜態資源的隱私與安全漏洞 (GCS Presigned URLs)
- **現狀**：目前保母上傳的「身分證正反面 (`identity/`)」與「飼主家長照片」也是採用公開讀取的 GCS URL 模式。
- **調整建議**：高度隱私資料必須採用 **Pre-Signed URL (預先簽章網址)**，並限制 5~15 分鐘的時效。需要後端修改 Upload Service 的產出邏輯，前端則配合時效刷新顯示。

### 3. CI/CD 管線未掛載最新防線 (Pipeline Gaps)
- **現狀**：我們剛剛辛苦建置了 Vitest 跟 Playwright POM，但 `.github/workflows` 若沒有更新，PR 就不會被攔截。
- **調整建議**：更新 GitHub Actions，強制要求合併 `main` 前必須 `npm run test` 並由 Playwright 進行檢查。

---

## 🚀 亟需補齊的核心業務功能 (Feature Adjustments)

除了工程架構，在產品體驗上，PWA 需要這幾個區塊打通才能顯現威力：

### 1. 實時通知系統 (Real-time Notifications / WebSockets)
- **痛點**：保母在後台點選「加成報價 200 元」後，飼主目前必須手動 F5 重新整理頁面才能看到訂單變化，這在 PWA 體驗上非常扣分。
- **解法**：導入 SSE (Server-Sent Events) 或 Spring WebSockets + 前端 Zustand Listener，實現「報價更改 -> 飼主手機立即彈出 Toast」。

### 2. 動態問卷編輯器 (Sitter Questionnaire Editor)
- **痛點**：後端 Schema 已經準備好讓保母自定義入站問卷。但前端欠缺一個「題目拖拉排序與選項編輯」的後台 UI。

### 3. 提現與金流閉環 (Financial Payouts)
- **痛點**：訂單走完 PAYUNi 刷卡後，目前只做到了記帳。保母目前還缺少「申請撥款 (Withdrawal Request)」的流水帳介面來正式把錢領出來。
