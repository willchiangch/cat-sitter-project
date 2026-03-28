# Walkthrough - Calendar Sync & Media Retention

這份文件總結了近期完成的兩大核心功能：**行事曆自動同步 (Google & Apple)** 以及 **多媒體儲存與 60 天保留政策 (Media Retention)**。

## 1. 行事曆自動同步 (Calendar Sync)

為了滿足不同保母的排程需求，我們實作了「雙軌制」同步機制。

### Google 原生同步 (Native API)
- **OAuth2 整合**：保母可連結 Google 帳號。同步時由後端伺服器直接對接 Google Calendar API。
- **自動化實作**：
  - **狀態：CONFIRMED**：自動於日曆建立事件。
  - **狀態：CANCELLED**：自動於日曆刪除事件。
- **異步執行**：使用 `@Async` 確保 API 反應速度不受同步過程影響。

### 通用型 iCal (ICS) 訂閱 — 支援 Apple / iOS
- **訂閱網址**：為每位保母產生唯一加密 Token 的 `webcal://` 連結。
- **安全性**：支援重置 Token 功能，防止連結外流。
- **標準協定**：採用 `ical4j` 生成符合 RFC 5545 標準的內容，支援 iPhone、Mac 內建日曆。

## 2. 多媒體儲存與保留政策 (Media Retention)

考量到 GCP 尚未開通與開發便利性，目前採用 **「儲存抽象化 + 本地模擬」** 方案。

### 儲存架構
- **StorageService 介面**：解耦業務邏輯與儲存底層。
- **LocalStorageService**：將檔案存放在本地路徑（預設：`./storage/media`），支援開發階段的檔案預覽。
- **未來擴充性**：只需切換配置即可無痛對接 GCS (Google Cloud Storage)。

### 媒體管理與清理
- **上傳限制**：每筆行程限 20 張照片/影片，單檔上限 10MB。
- **自動化清理 (Job)**：每日凌晨 3:00 執行 `MediaRetentionJob`，自動刪除 **60 天前** 的媒體檔案。
- **資料庫設計**：新增 `visit_media` 表，記錄檔案 metadata 並在刪除後保留 `is_deleted` 紀錄以供備查。

## 驗證與測試 (Verification)

### API 測試路徑
- **行事曆**：
  - `GET /api/v1/sitters/me/calendar/auth-url`：取得 Google 授權網址。
  - `GET /api/v1/sitters/me/calendar/status`：檢視同步狀態與 iCal 網址。
- **媒體**：
  - `POST /api/v1/visits/{visitId}/media`：上傳媒體檔案。
  - `GET /api/v1/media/{path}`：預覽已上傳之本地檔案。

### 自動化檢查
- 檢查 `OrderLifecycleService` 與 `BookingService` 是否正確呼叫同步邏輯。
- 確認 `CatSitterApiApplication` 已啟用 `@EnableScheduling` 與 `@EnableAsync`。

## 3. 測試體系與文件同步 (Testing & Documentation)

為了確保業務邏輯的穩定性，我們建立了多層層次的測試管理與規格文件同步機制。

### 資料庫規格書 (Schema V8)
- 已經將 `doc/schema.md` 從 V6 直接更新至 **V8** 版本。
- 補齊了：財務模組 (Finance)、訂閱系統 (Subscriptions)、促銷碼 (PromoCodes)、行事曆同步配置 (CalendarSync) 以及多媒體附件 (Media) 等新表格。
- 更新了 `visits` 與 `profiles` 等核心表格的最新欄位規格。

### 業務情境冒煙測試 (Smoke Tests)
- **前端 (Playwright)**：實作了 `tests/smoke/booking-smoke.spec.ts`，模擬透過 API 觸發的預訂流程健康檢查。
- **後端 (JUnit 5)**：
  - `BookingFlowSmokeTest`：模擬「訂單建立 -> 模擬支付 Webhook -> 自動確認 -> 日曆同步」的完整業務閉環，確保各組件整合正常。
  - `SitterOnboardingSmokeTest`：模擬「保母註冊 -> 選擇方案 -> 支付成功 -> 接單權限啟用」的流程。

### 壓力測試預備 (Performance)
- 提供 `backend/src/test/resources/performance/webhook-smoke.js` (k6 腳本)。
- 專門用於測試當大量支付回傳同時發生時，系統對併發交易的承載力。

## 4. 進度同步規範 (Workflow Sync)
- 已依照 `.agents/workflows/persist-progress.md` 規範，將「大腦」資料夾同步至專案根目錄 `.agent/brain/`。
- 包含最新版的 `task.md` 與 `walkthrough.md`，方便團隊跨環境追蹤開發狀態。

## 後續建議
- **GCP 環境建立**：當 GCP 專案就緒時，只需更新 `application.yml` 的 `storage.type` 為 `GCS` 並填寫相關憑證即可對接。
- **前端串接**：保母 App 的行程紀錄頁面現在可以開始串接 `POST /media` 接口來上載服務日誌圖檔。
