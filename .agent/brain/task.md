# SD-008: 服務執行與 Check-in 實作任務清單

- [x] **1. 領域模型與資料庫設計 (Domain & DB)**
  - [x] 更新 `Visit.java` 的 `status` 狀態註解，補上 `IN_PROGRESS`
- [x] **2. 業務服務層與控制器實作 (Services & Controllers)**
  - [x] 擴充 `VisitReportService.java` 中 `startVisit` 與 `endVisit` 的實作
  - [x] 注入 `OrderRepository` 與 `ApplicationEventPublisher` 以供狀態流轉與非同步事件通知
  - [x] 新增可選（`required = false`）的 `Idempotency-Key` 校驗邏輯，對齊設計規格
  - [x] 於 `startVisit` 狀態流轉後，呼叫 `orderRepository.save(order)` 明確化持久化意圖
  - [x] 實作 `VisitReportController.java` 中的 `/start` 與 `/end` 端點，對接 Service
- [x] **3. 異步事件驅動通知重構 (Events & Listeners)**
  - [x] 新增 `VisitNotificationEvent` 事件
  - [x] 於 `NotificationListener.java` 中加上對應的事件監聽器，使用 `@Async` 與 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` 進行解耦，防止幽靈通知
  - [x] 移除 `NotificationService.java` 原有的監聽邏輯，使其專注於通知推送實體
- [x] **4. 前端 API 與 React Hooks 串接 (Frontend Integration)**
  - [x] 更新 `visitReportApi.ts` 中 `startVisit` 與 `endVisit` 方法，加入 `Idempotency-Key` 標頭傳遞
  - [x] 更新 `useVisitReport.ts` mutations 支援傳參
  - [x] 修改 `VisitReportManager.tsx`：
    - [x] 使用 `useRef` 生成並保存 `startKeyRef` 和 `endKeyRef` 冪等金鑰
    - [x] 修正日誌正式送出 `handleSubmitReport` 的狀態防呆攔截，將 `!isEditable` 限制改為基於 `DONE` 行程狀態的判斷
- [x] **5. 設計規格文件與測試驗證 (Verification)**
  - [x] 撰寫並擴充 `VisitReportControllerTest.java` 整合測試：
    - [x] 補齊對應 /start 與 /end 的 `Idempotency-Key` 測試
    - [x] 新增「後續日 Check-in」（訂單已為 `IN_PROGRESS`）測試情境
    - [x] 新增 `/start` 與 `/end` 重複請求冪等 409 衝突測試
  - [x] 修正 `service-execution.spec.ts` 中 Mock 路由重複攔截掛起的 bug，將 GET 與 PUT 合併
  - [x] 執行本地前端 Playwright E2E 測試，100% 綠燈通過
  - [x] 更新 [SD-008-service-execution.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-008-service-execution.md)，將 `Idempotency-Key` 改為必填並加註「離線補送機制延後至 Open Beta 實作」之備註

- [x] **SD-017: 保母實名認證與資格審查 (KYC) 實作** (✅ **COMPLIANT — Approved**)
  - [x] 完成 [SD-017-sitter-kyc.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-017-sitter-kyc.md) 規劃（9 輪 Review 後 COMPLIANT）
  - [x] 建立 Flyway 遷移 `V20260606_01__add_sitter_kyc_and_is_open.sql`（`is_open`、`version` 欄位、`kyc_records` 表、局部唯一索引防重送）
  - [x] `Profile.java` 加 `@Version` 樂觀鎖，預設 `kycStatus = UNVERIFIED`，同步修正 `AuthService`/`PaymentService` 的硬碼 `"PENDING"`
  - [x] 擴充 `MediaStorageService` 介面：`uploadKycFile` + `generateSignedUrl`，兩個 Profile 均已實作
  - [x] `KycRecord.java`、`KycRecordRepository.java`（含 JOIN 查詢防 N+1）、`KycServiceImpl.java`
  - [x] `SitterKycController.java`（Rate Limiting 在 `@Transactional` 外）+ `AdminKycController.java`
  - [x] `BookingService.java` 新增雙重卡控：`isOpen == true && kycStatus == VERIFIED`（SD-005 聯動）
  - [x] 非同步 AFTER_COMMIT 事件通知：`KycReviewedEvent`、`SitterSuspendedEvent`、`SitterUnsuspendedEvent`
  - [x] `GlobalExceptionHandler` 修正冪等性衝突統一回 409（`pk_idempotency` + `Duplicate idempotency key`）
  - [x] `ServicePlanControllerTest` 補齊保母 VERIFIED Profile 種子資料
  - [x] `KycControllerTest.java` 整合測試全覆蓋（含 SUSPENDED 重提 422 場景），15 筆全綠
  - [x] `AdminKycController.getPendingKycRecords` 冗餘 null-check 清理（INNER JOIN 保證非 null）

- [x] **SD-017: 保母實名認證 (KYC) 前端實作** (✅ **COMPLIANT**)
  - [x] 新增 `frontend/src/api/kycApi.ts`：對接全部 KYC 端點（getSitterKycStatus、submitKyc multipart、getPendingKycList、getKycDetail、getAdminMediaUrl、getSitterMediaUrl、reviewKyc、suspendSitter、unsuspendSitter、updateSitterOpenStatus、getSitterOpenStatus）
  - [x] 新增 `frontend/src/pages/sitter/SitterKycSubmit.tsx`：5 狀態分支 UI（UNVERIFIED/PENDING_REVIEW/VERIFIED/REJECTED/SUSPENDED）、檔案上傳表單含 5MB 前端驗證、Idempotency-Key
  - [x] 新增 `frontend/src/pages/admin/AdminKycList.tsx`：待審清單（分頁）+ 停權/解停工具欄
  - [x] 新增 `frontend/src/pages/admin/AdminKycDetail.tsx`：申請者資料 + Promise.all 並行取 Signed URL 媒體預覽 + Approve/Reject 決策（含駁回原因輸入）
  - [x] 更新 `App.tsx`：新增 `sitter-kyc`、`admin-kyc-list`、`admin-kyc-detail` ViewState，補 `kycRecordId` params，新增 Demo 首頁快速跳轉按鈕

