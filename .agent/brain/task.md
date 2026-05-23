# 🐾 實作任務清單 (SD-021 & SD-022) - 完工版

## 1. 照護記事與媒體庫 (SD-021) - 已完成
- [x] **1. API 串接與 React Query Hooks (Infrastructure & Data Layer)**
- [x] **2. 共用 UI 元件開發 (Stitch UI Layer)**
- [x] **3. 保母端照護管理頁面實作 (Sitter CareNoteManager)**
- [x] **4. 飼主端照護檢視頁面實作 (Client CareNoteView)**
- [x] **5. 整合與路由配置 (App Integration)**
- [x] **6. 驗證與微調 (Verification & Tuning)**

## 2. 行程照護日誌與多媒體回報 (SD-022) - 已完成
- [x] **1. 資料庫變遷與快照補足 (Flyway & DDL)**
  - [x] 新增 `V20260523_03__create_visit_service_reports.sql` 遷移檔案。
  - [x] 在 `order_snapshots` 補齊 `max_videos` (INT) 與 `plan_tier` (VARCHAR) 快照欄位。
  - [x] 建立服務日誌主表 `visit_service_reports` 及多媒體表 `service_report_media` 與對應 composite indexes。
- [x] **2. Domain Layer & Entity Models**
  - [x] 建立 `VisitServiceReport.java` 及 `ServiceReportMedia.java` 繼承 `BaseEntity`。
  - [x] 建立 `VisitServiceReportRepository` 及 `ServiceReportMediaRepository` 介面。
- [x] **3. Application Services & Business Logic**
  - [x] 實作 `VisitReportService.java` 中暫存草稿、媒體上傳、邏輯刪除、送出日誌及安全分流角色讀取。
  - [x] 實作 `AuditLogService` 支援 `REQUIRES_NEW` 的 `writeOrderLog` 方法。
  - [x] 實作 `VisitReportException` 及在 `GlobalExceptionHandler` 中統一處理，實現 400, 403, 409, 422, 503 的精確轉換。
- [x] **4. Controllers & API Specifications**
  - [x] 建立 `VisitReportController.java` 暴露對應 PUT, POST, DELETE, GET 接口，並以 `@RequirePlan(PlanTier.FREE)` 控制准入。
- [x] **5. Integration Tests (TS-022)**
  - [x] 建立 `VisitReportControllerTest.java`。
  - [x] 測試覆蓋 7 個核心商業與越權防禦情境。
  - [x] 測試全數通過，無 errors / failures。
- [x] **6. Frontend API & React Hooks**
  - [x] 建立 `visitReportApi.ts` 與 React Query hooks `useVisitReport.ts`（支援 Catch 404 回傳 empty draft 避免崩潰）。
- [x] **7. Frontend Pages (VisitReportManager & VisitReportView)**
  - [x] 建立保母端 `VisitReportManager.tsx`（文字字數 1000 限制、多媒體格式大小時長前端防禦、上傳按鈕 loading 與 409 連點 idempotency-key 攔截、草稿逾期過期唯讀）。
  - [x] 建立飼主端 `VisitReportView.tsx`（唯讀日誌與相簿、點擊放大 LightBox、安全隔離未送出草稿顯示 concierge placeholder）。
  - [x] 於 `App.tsx` 整合新增 Demo 入口按鈕。
  - [x] 前端通過 `npm run build` TypeScript 嚴格編譯！

## 3. Review 修正與安全防禦 - 已完成
- [x] **1. QueryClient 全域安全配置**
  - [x] 於 `main.tsx` 注入 `defaultOptions` 以設定全域 `staleTime: 5 * 60 * 1000` (5 分鐘) 與防範背景覆蓋。
- [x] **2. 補足其餘 query 的 staleTime 屬性**
  - [x] 於 `useCareMedia.ts` 的 `useCareMediaQuery` 加上 `staleTime`。
- [x] **3. 回歸測試驗證**
  - [x] 確保後端測試執行成功且無報錯。
  - [x] 執行前端 `npm run build` 編譯通過。
