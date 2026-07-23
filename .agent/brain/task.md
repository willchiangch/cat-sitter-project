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
- [x] **PRD-000 補齊：Email OTP 註冊驗證 + 帳號註銷（軟刪除）**（2026-07-23）：新增 `registration_otps` 表與 OTP 寄送/驗證/重寄三支 API（10 分鐘效期/60 秒冷卻/5 次錯誤鎖定），修正錯誤次數被交易回滾的 bug（`RegistrationOtpAttemptService` REQUIRES_NEW）；帳號註銷沿用既有 `User.is_deleted` 與 `SitterPublicProfileServiceImpl` 既有 404 邏輯，新增未結案訂單前置卡控（409）、密碼二次驗證（403）、信任圈/我的最愛自動軟刪除、登入卡控；前端新增兩段式註冊表單與 `/account-settings` 頁面；後端測試 27 個新情境 + 全量 `mvn test` 綠燈、前端 E2E 7 個情境綠燈 (✅ **Implemented**)。**PRD-011 行事曆同步依使用者指示整個跳過**，尚未開始
- [x] **PRD-000 補齊：Google 第三方登入**（2026-07-23）：採 Google Identity Services (GIS) 前端按鈕 + 後端驗證 ID Token 方案（非傳統 Authorization Code 導向流程，不需 redirect URI、不需 Client Secret）；新增 `google-api-client` 依賴、`GoogleTokenVerifierService`（官方 `GoogleIdTokenVerifier` 處理 JWKS 輪替）、`POST /api/auth/google`（既有 Email 自動綁定登入；新 Email 需帶 `role` 才建立帳號，未帶則回 `NEEDS_ROLE_SELECTION`）；`LoginPage.tsx` 新增 GIS 按鈕與角色選擇步驟，並暴露 `window.__handleGoogleCredential` 供 E2E 測試模擬 credential 事件（不依賴真實 Google 彈窗）；後端測試 6 個新情境（含 Token 驗證失敗/Email 未驗證/已註銷帳號阻擋）+ 全量 `mvn test` 綠燈、前端 E2E 2 個情境綠燈 (✅ **Implemented**)。**PRD-000 三項待補功能至此全數完成**。Client ID 由使用者提供並直接寫入版控預設值（非機密，單一 Client 通吃本地/正式環境）
- [x] **PRD-000 收尾稽核修復 + 生物辨識登入（AC-6）+ 登出所有裝置**（2026-07-23）：
  - 稽核修復：補齊 OTP 註冊/Google 建帳號/帳號註銷三處缺漏的稽核日誌（過程中抓到並修正一個真實 bug——`log_user_action.operator_id` 對 `users` 有 FK 約束，建新帳號當下用既有 `REQUIRES_NEW` 版本寫稽核日誌會因為另一個交易看不到還沒 commit 的新使用者而 FK 失敗，改用新增的 `writeUserActionLogInline` 同交易寫入解決）；補上 PRD-000 AC-2 服務條款勾選 UI 卡控；修正 SD-000 文件內不存在的 `DataMessageEnum` 引用；OTP 驗證/Google 建帳號的併發雙擊送出加防禦（DB UNIQUE 撞號回友善 400）；`/account-settings` 補上導覽入口
  - 登出所有裝置：新增 `POST /api/auth/logout-all-devices`。過程中發現既有 `AuthService.createRefreshToken()`/`switchRole()` 皆為「同一帳號同時只保留一組 refresh token」設計，代表系統現況並非真正支援多裝置同時在線；已與使用者確認維持現況，本功能實質等同撤銷目前唯一一組 session，此為記錄在 SD-000 的已知限制
  - 生物辨識登入 (WebAuthn)：新增 `com.yubico:webauthn-server-core` 依賴、`webauthn_credentials`/`webauthn_challenges` 兩表、`WebAuthnService`/`WebAuthnController`（5 支 API，掛在 `/api/auth/webauthn/**`）；一人可註冊多組裝置憑證；`login/options` 對不存在或未開通生物辨識的 Email 一律回傳空 `allowCredentials` 而非 404，避免帳號枚舉；後端 15 個新測試情境 + 全量 `mvn test`（190+ 情境）綠燈；`webauthn-login.spec.ts` 用 Playwright CDP virtual authenticator 跑真實簽章迴圈（刻意不 mock，需真後端+真 DB），過程中修了兩個真實 bug：(1) Yubico 函式庫回傳的 JSON 是 `{publicKey: {...}}` 包一層，不是我原本以為的攤平格式；(2) 自訂的 `CredentialRepository` 實作類別命名為 `*RepositoryImpl` 撞上 Spring Data JPA 自動偵測自訂實作片段的命名慣例，造成 Bean 循環參照，改名 `WebAuthnRpCredentialLookup` 解決 (✅ **Implemented**)。**PRD-000 全部驗收標準與業務規則至此全數收斂**（僅「多裝置同時在線」維持前述已知限制）
