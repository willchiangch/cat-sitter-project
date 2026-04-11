# WhiskerWatch 進度開發與優化導覽 (V25)

本文件摘要記錄了近期針對核心系統穩定性、數據完整性與開發者體驗 (DX) 的重大更新，供交接參考。

## 🌟 核心變更總結

### 1. 全系統軟刪除機制 (Soft Delete)
為了解決刪除資料（如寵物、帳號）導致歷史訂單斷鏈報錯的問題，我們導入了基於 Hibernate 註解的軟刪除方案。
- **影響範圍**：`Account`, `Profile`, `Pet`, `Service`, `Order`, `Visit`。
- **實作技術**：使用 `@SQLDelete` 攔截刪除行為，配合 `@SQLRestriction` 自動過濾已刪除紀錄。
- **資源釋放**：採用 Postgres 部分唯一索引 (`WHERE deleted_at IS NULL`)，讓已刪除的 Email 或 Slug 資源可被重新註冊。

### 2. 寵物護照與 UI 優化
- **精確年齡換算**：新增 `birthDate` 持久化，並在 Profile 頁面實作自動年齡計算（動態顯示 `x歲 y個月`）。
- **身分驗證勳章**：Email 旁加入綠色勾勾勳章，即時呈現電子郵件驗證狀態。
- **媒體服務修復**：修正 Vite Proxy 設置 (8081 -> 8080)，修復頭像與寵物照片顯示異常。

### 3. 開發者體驗 (DX) 升級
- **螢光 Console 日誌**：在 `smoke` 測試環境下，前端攔截器會自動將驗證碼以顯眼的螢光噴漆樣式輸出至瀏覽器 Console，大幅提升調試速度。
- **驗證流程自動化**：更新 Email 後，系統會透過自定義事件觸發 `CommunicationVerify` 組件直接進入驗證碼輸入狀態，無需手動重新整理頁面。

---

## 🔍 驗證與調試指引

### 圖片恢復顯示
> [!TIP]
> 確保後端環境已正確載入 `smoke` profile。修正 Proxy Port 後，原本破圖的照片應可正常由 `./storage/smoke-media` 讀取。

### 軟刪除驗證
1. 刪除一隻寵物。
2. 該寵物會從前端列表中消失。
3. 執行資料庫查詢：`SELECT name, deleted_at FROM pets WHERE name = '寵物名';` 可確認資料仍保留但已標記時間。

### 快速獲取驗證碼
1. 在前端進行信箱驗證操作。
2. 直接開啟瀏覽器 F12 -> Console。
3. 尋找 `🔑 [DEBUG] Verification Code: XXXXXX` 字樣。

---

## 📋 歷史里程碑回顧
- **Phase 1-7**：前端 UI/UX 重構（Tab 標籤統一、CSS Token 修復）。
- **Phase 8-10**：安全認證與測試架構建立（Playwright POM, JWT Fix）。
- **Phase 11-12**：數據完整性與 DX 深度優化（軟刪除, 螢光日誌）。
