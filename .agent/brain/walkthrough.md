# SD-003: 保母服務方案設定 設計文件撰寫 Walkthrough (最新修正版)

我們已成功更新並建立了完整的 [SD-003-service-plans.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-003-service-plans.md) 設計文件，完全對齊 `PRD-003` 的功能要求與 `sd-skill` 的設計規範，並解決了所有審查回饋。

---

## 本次更新與修正要點

1. **技術憲法對齊 (價格精度)**：
   - 傳統 DB `price` 維持 `BIGINT` (Java `Long`，整數儲存)。API 接收 `BigDecimal` 並於存庫前執行 `.setScale(0, HALF_UP).longValue()`。
   - 修正 API Response 設計範例中的 `price` 值為整數 `500`。
2. **日期型別優化 (DATE)**：
   - 將 `start_date` 與 `end_date` 的型別設計為 **`DATE`** 型別 (Java 對應 `LocalDate`)，徹底排除時區位移換算所造成的邊界判定 Bug。
3. **時程防禦與動態 HttpStatus**：
   - 飼主預約時若方案不在生效區間，系統將會由 `BookingService` 拋出 `ServicePlanException`。
   - 在 `GlobalExceptionHandler.java` 中補上 `@ExceptionHandler(ServicePlanException.class)`，改用動態讀取 `ResponseEntity.status(ex.getStatus())` 以相容 `PLAN_NOT_FOUND` (404) 與 `PLAN_NOT_IN_RANGE` (422) 兩種狀態。
4. **SaaS Gating 門禁對齊 (AUTH_PLAN_LIMIT)**：
   - 新增「技術設計決策 (Design Decisions)」區塊，記錄 Service 層處理屬性級門禁檢查的 Conscious Exception。
   - 錯誤代碼對齊為 codebase 現存定義的 **`AUTH_PLAN_LIMIT`**。
5. **樂觀鎖衝突防護與 Handler 補齊 (409)**：
   - 保母呼叫 `PUT` 編輯方案時，必須傳入 `version`。若傳入版本與 DB 最新版本不一致，系統會拋出 `ObjectOptimisticLockingFailureException`。
   - 規劃在 `GlobalExceptionHandler.java` 中補上 `@ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)`，回傳 409 Conflict 且 error 欄位為 `VERSION_CONFLICT`，修補 codebase 歷史遺留漏洞，使 JPA 樂觀鎖衝突不致噴出 500。
   - **Version 初始值校正**：POST 成功後回傳 `"version": 0` (原本為 1)；第一次 PUT 成功後回傳 `"version": 1` (原本為 2)，完全符合 JPA `@Version` 規範行為。
6. **排序 API 規格定義**：
   - 定義 `POST /api/sitter/plans/sort` 接收 `planIds` 陣列，直接利用陣列 index 作為 `sort_order` 進行批次更新。
7. **稽核日誌 care_logs**：
   - 明確指出方案 CRUD 屬於保母的個人主檔設定，而非特定訂單的生命週期，故會將操作成功/失敗的稽核紀錄以 `REQUIRES_NEW` 獨立事務寫入 `care_logs` 表 (調用 `AuditLogService.writeLog`)，而非 `order_logs`。
8. **白名單門禁過濾 TODO (PRD-001/SD-001 整合點)**：
   - 當前前台方案查詢 API (`GET /api/sitters/{sitterId}/plans`) 將跳過白名單過濾直接回傳生效方案，避免 `SD-003` 設計範圍過度擴張。
