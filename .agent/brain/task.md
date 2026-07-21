# SD-015 & SD-013: 線上支付與多媒體生命週期實作任務清單

## 📋 當前任務 (Open Beta - Phase 4)

### 💳 SD-015: 線上支付與金流整合
- [ ] **1. 資料庫變更與實體建立 (Schema & Entities)**
  - [ ] 撰寫 Flyway SQL 遷移，建立 `sitter_payout_settings`、`payments` 與 `payout_records` 資料表
  - [ ] 建立對應的 JPA 實體（`Payment`, `PayoutRecord`, `SitterPayoutSettings`）
  - [ ] 在 `Order` 與 `Sitter`（或 `Profile`）中配置對應的關聯欄位與 `@Version` 樂觀鎖
- [ ] **2. 核心支付與金流 Webhook (Backend Logic)**
  - [ ] 實作第三方金流商模擬器/SDK 串接元件（代收代付建立交易）
  - [ ] 實作 `PaymentService.java` 以處理支付請求與狀態流轉
  - [ ] 實作 `PaymentController.java` 暴露支付與 Webhook 端點
  - [ ] 於 Webhook 端點實作 HMAC-SHA256 簽章防禦，確保 Webhook 安全性
- [ ] **3. 結案撥款與對帳機制 (Settlement & Cron)**
  - [ ] 實作 Order 完成（`COMPLETED`）的事件監聽器，自動生成 `PayoutRecord`，計算預計撥款日（T+3 工作日）與淨額（高精度 `BigDecimal` 運算）
  - [ ] 實作撥款排程（模擬每天跑批執行 PENDING -> PROCESSING -> SUCCEEDED）
  - [ ] 實作 `SitterPayoutSettingsController.java` 以供保母設定收款帳號
- [ ] **4. 前端金流整合與對接 (Frontend)**
  - [ ] 新增 `paymentApi.ts` 與對應的 React Query Hooks
  - [ ] 實作飼主端預約支付跳轉與 Success/Failed 導向頁面
  - [ ] 實作保母端「收款設定」管理介面與「帳務撥款明細」面板
- [ ] **5. 整合測試與 E2E 驗證 (Verification)**
  - [ ] 撰寫 `PaymentControllerTest.java` 驗證 Webhook 簽章防禦與重複 Webhook 的 409 衝突
  - [ ] 撰寫 `PayoutSettlementTest.java` 驗證 T+3 撥款生成與手續費四捨五入計算
  - [ ] 撰寫 Playwright E2E 測試，打通「支付 -> 服務 -> 結案 -> 撥款產生」全流程

### 📅 SD-013: 多媒體生命週期與保留策略
- [x] **1. 欄位變更與快照更新 (Schema & DB)**
  - [x] 撰寫 Flyway SQL 遷移，於 `service_report_media` 新增 `is_purged` 與 `purged_at` 欄位，在 `orders` 新增 `media_expiry_warned`
- [x] **2. 批次清理與通知排程 (Backend Scheduling)**
  - [x] 於 `InternalCronController.java` 新增 `/api/internal/cron/media/cleanup` 與 `/api/internal/cron/media/expiry-warning` 兩個 API 端點
  - [x] 實作 `MediaRetentionServiceImpl.java` 中的 `cleanupExpiredMedia()` 方法，呼叫 `mediaStorageService.deleteMedia(url)` 執行實體刪除，並由 `MediaPurgeBatchDeleter` 以分批 (LIMIT 500) `REQUIRES_NEW` 獨立 Commit 標記 DB 刪除
  - [x] 實作保母升級方案時的 `upgradeSitterMediaRetention` 追溯展延邏輯，覆寫快照保留天數，並調用 `auditLogService.writeUserActionLog` (5 參數) 記錄審計日誌
  - [x] 實作過期前 3 天的自動通知排程任務，引入 `MediaExpiryWarningBatchProcessor` 獨立 Bean 處理單筆 `REQUIRES_NEW` 事務（避免單筆失敗阻塞），利用 `ApplicationEventPublisher` 發布事件，在 `AFTER_COMMIT` 由 `NotificationListener` 寫入通知（分類為 `SERVICE_RECORD`）

- [x] **3. 逾期 UI 與警告倒數 (Frontend UX)**
  - [x] 在 `ReportMediaDto` 新增 `isPurged` 欄位，並在 `VisitServiceReportDto` 擴充 `mediaRetentionDays`、`completedAt`、`expiryTime`、`isPurged`
  - [x] 前端實作當 `media.isPurged == true` 時顯示單個媒體灰色佔位盒（「照片已逾期移除 🐾」）
  - [x] 實作結案訂單媒體剩餘天數倒數 UI（剩餘 <= 3 天時顯示黃色警告橫幅），且當 `report.isPurged` 為 true 時頂端顯示部分照片已逾期移除橫幅

- [x] **4. 邏輯覆蓋測試 (Verification)**
  - [x] 撰寫 `MediaCleanupTest.java` (已在 `MediaRetentionServiceTest.java` 實作) 整合測試，驗證清理排程、升級展延追溯與降級合約保護隔離

---

## 🏆 已完成里程碑 (Close Beta - Phase 1~3)
- [x] **SD-008: 服務執行與 Check-in** (✅ **Implemented**)
- [x] **SD-017: 保母實名認證 (KYC)** (✅ **Implemented**)
- [x] **SD-014: 訊息中心與推播通知** (✅ **Implemented**)
- [x] **SD-018: 保母公開檔案與標籤管理** (✅ **Implemented**)
- [x] **Admin Subscription API 手動開通** (✅ **Implemented**)
- [x] **前後端分離部署 (Firebase Hosting + Cloud Run) + 正式路由/登入頁 + 訂單清單 API 化 + CI 後端測試閘門** (✅ **Implemented**，詳見 walkthrough.md 第 10 節)
- [x] **正式環境上線後人工巡檢修復**（毛孩頭像上傳體驗、窄螢幕表單直排、/login 登入並發 409、PWA 更新後空白頁、E2E 逾時）(✅ **Implemented**，詳見 walkthrough.md 第 11 節)
- [x] **PRD/SA/SD 稽核落差修復批次**（2026-07-19，對照 `project_prd_audit_2026_07` 稽核結果逐項修復）：PRD-021 模板上限 10→3、PRD-003 適用寵物類型擴至 8 種、PRD-009 結案按鈕狀態判斷+disputeOrder 狀態機防呆+admin 二次驗證(403)+保母帳務總覽頁、PRD-016 變更欄位對齊+報價/拒絕機制+confirmModification 權限與 Zero-Trust 校驗、PRD-017 未實名保母公開頁/預約攔截、PRD-020 內部信用指標管理、PRD-000 登入失敗鎖定+註冊頁+忘記密碼(Resend)、PRD-004 事前問卷設定整模組、PRD-005 動態問卷渲染+Zero-Trust 價格核對、PRD-019 我的最愛保母整模組、PRD-010 信任圈與轉介機制整模組（✅ **Implemented**；**PRD-011 行事曆同步依使用者指示整個跳過**，尚未開始）
- [x] **上述批次之 SD/TS 文件回補**（2026-07-21~22）：新建 SD-004/010/019/020 與對應 TS、補 TS-017，並更新 SD-000/003/005/009/016/021 與對應 TS 內容以對齊實作；複查時另修正 TS-003 自動化狀態長期未同步（16 個既有情境實為已自動化卻誤標 0/16）、SD-016 已規劃但未落地的密碼二次驗證描述、Idempotency-Key 未串接去重邏輯等既有落差 (✅ **Implemented**)
