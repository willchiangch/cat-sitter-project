# WhiskerWatch: 專案現況評估與健康度報告 (V31)

本報告總結了截至 **V31 (2026-03-29)** 的專案現況。目前專案已從「高保真 UI」全面轉向「高擬真業務邏輯實體化 (Realization)」，核心數據閉環已初步成型。

---

## 🟢 目前已完成進度 (Current Status)

### 1. 核心實體化模組 [DONE]
- **V29：服務日誌 (Service Log Details)**：
    - 實作了服務期間的媒體持久化 (Moments)，對接 GCS 上傳。
    - 照護報告實體化，不再使用 Mock 數據。
- **V30：身份認證與專業標籤 (Identity & Labels)**：
    - 完成保母 KYC 實體對接，支援證件上傳、身份驗證標章顯示、專業標籤編輯。
    - 財務層面預留了選填的撥款銀行欄位。
- **V31：服務、信任與流程優化 (Services & Whitelist)**：
    - **服務方案 CRUD**：保母可管理定價、時長與方案狀態。
    - **常客精準經營**：建立「白名單」機制。標記常客為 `skipQuestionnaire` 時，預約流程會自動優化。
    - **夥伴轉介系統**：信任圈對接 `slug` 轉介連結。

### 2. 基礎設施健康度 [HEALTHY]
- **Smoke Testing 環境穩定化 [NEW]**：
    - 實作了 `smoke` profile 與具備自我清理功能的 `JdbcTemplate` Seeder。
    - 解決了 CORS 與多身份 Mock Auth (Sophia vs James) 驗證問題。
    - 整合 Playwright 驗證了兩大 Golden Paths (預約與報價)。
- **後端架構**：Spring Boot 穩定支援多租戶隔離與業務擴充。
- **前端架構**：Zustand 狀態機已與 `api.js` (Axios) 深度整合，所有核心頁面已實體化。
- **儲存方案**：GCS 目錄結構已規模化（`visit_media`, `profiles`, `identity`）。

---

## 🚀 剩餘開發優先級 (Prioritized Roadmap)

### 優先級一：問卷編輯器實體化 (Sitter Questionnaire Editor)
目前後端已支援題目管理，但前端尚未有 UI 讓保母自定義「入站問卷」題目。

### 優先級二：通知中心與推送邏輯 (Push & Global Notifications)
實作 `Notification` 實體。當服務日誌更新、訂單狀態變更或夥伴轉介時，觸發系統級提醒。

### 優先級三：金流與撥款閉環 (Financial Payouts)
目前已完成「統計」與「撥款資訊錄入」，下一步是實體化「申請撥款」與「提現狀態流水」。

---

## ⚠️ 風險評估 (Risk Assessment)
- **i18n 覆蓋率**：隨著 V31 業務邏輯增加，需確保所有實體化後的動態文字（如錯誤訊息、狀態標籤）都有正確的語系對應。
- **認證文件隱私**：身分證件存放在 GCS 的 `identity/` 目錄，需確保後續有權限檢核機制。
