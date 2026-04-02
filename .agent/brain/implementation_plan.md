# WhiskerWatch: 戰備狀態回顧與今日行動計畫

## 當前專案現況評估 (Review)

### 🟢 已完成事項 (DONE)
- **Profile 頁面升級**：成功加入「專業經營工具」區塊，整合服務方案、問卷、信任圈入口。
- **後端防禦性修復**：`CalendarSyncController` 已具備 Profile 缺失檢查，消滅了 500 錯誤。
- **混合測試框架**：建立 `SitterBusinessFlow` E2E 腳本與對應的 POM (`ProfilePage`, `SitterToolsPage`)。
- **資料預埋**：`SmokeDataSeeder` 已包含 Sophia 帳號、服務方案與問卷基礎資料。

### 🔴 待修復與缺口 (TODO / Gaps)
- **前端 Bug**：`Profile.jsx` 使用 `navigate` 但未導入 `useNavigate`，點擊工具連結會崩潰。
- **測試不穩定性**：E2E 測試在目前環境下偶爾發生「No tests found」或導航超時。
- **業務缺口**：`project_evaluation.md` 提到目前 API 契約手寫容易出錯，且保母缺乏「提現/財務流水」UI。

---

## 今日優先行動 (Proposed for Today)

### 1. 穩定性鞏固 (Priority: High)
#### [修改] [Profile.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Profile.jsx)
- **修復導航語法**：導入 `useNavigate` 並正確初始化。
- **優化加載邏輯**：確保 `calendarStatus` 在 Profile 載入後能非同步更新而不影響主 UI。

#### [修復] E2E 測試路徑
- 調整 `playwright.config.js` 或執行參數，確保 `sitter-business.spec.js` 能穩定執行。

### 2. 業務功能延伸 (Priority: Medium)
#### [NEW] 財務介面原型 (Financial Payouts)
- 在 `Finance.jsx` 基礎上，增加「申請撥款」彈窗與「歷史撥款紀錄」表格。
- 符合 `project_evaluation.md` 中對「提現與金流閉環」的建議。

#### [修改] [SmokeDataSeeder.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/service/SmokeDataSeeder.java)
- 更新問卷資料，增加選項型題目，以支援開發中的問卷編輯器。

---

## 使用者確認事項 (User Review Required)

> [!IMPORTANT]
> 是否優先完成 **API 契約同步 (OpenAPI)** 的建置？
> `project_evaluation.md` 提到這能從根本解決前後端屬性名稱不一致導致的 Bug。如果同意，我將優先使用 `openapi-spec-generation` 技能。

## 驗證計畫

### 自動化驗證
- `npx playwright test sitter-business` 必須 100% 通過。

### 手動驗證
- 使用 Browser Subagent 點擊 Profile 中的「管理服務方案」，確認能正確跳轉至 `/sitter/service-packages`。
