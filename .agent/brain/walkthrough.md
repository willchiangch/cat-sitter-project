# 🐾 記事本與媒體庫 (SD-021) 開發總結報告

這份報告總結了基於 [SD-021-care-notes-and-media.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-021-care-notes-and-media.md) 設計文件的完整實作內容。專案已全部開發完成，以下為功能模組與 Audit 修復的盤點：

## 1. 資料庫變更 (Database Migration)
> [!NOTE]
> 採用 Flyway V20260522_01 進行 Schema 的版本控制，包含所有表的建置與約束。另外以 V20260523_01 建立了獨立的 `care_logs` 表以配合稽核日誌。

- 新增 6 張核心資料表：`care_notes`, `care_note_items`, `care_note_templates`, `care_note_template_items`, `care_media`, `idempotency_keys`。
- 新增 `care_logs` 用以記錄照護日誌變更稽核，解決了原本 `order_logs` 外鍵綁定 `order_id` 導致無法記錄無訂單上下文日誌的問題。
- 已加入 `CHECK (section_type IN (...))` 嚴格限制區塊類型防禦。

## 2. 領域模型 (Domain Layer)
- 實作 JPA Entities 並採用 UUID 作為主鍵，並支援 `@CreationTimestamp` 進行自動化稽核時間標記。
- 實作 4 組 Spring Data JPA Repositories，搭配 `@Modifying @Query` 實作高效的實體刪除（供 Recreate-on-Save 使用）。
- **時區統一 (P2)**：將 `CareNote`, `CareNoteItem`, `CareNoteTemplate`, `CareNoteTemplateItem`, `CareMedia`, `IdempotencyKey` 等 6 個 Entity 中使用的 `ZonedDateTime` 全面替換為 `OffsetDateTime`，遵守 `GLOBAL-SPEC` 規範。

## 3. 共用基礎建設 (Infrastructure)
- **定期排程**：實作 `CleanIdempotencyKeysJob`，透過 `@Scheduled(cron = "0 0 3 * * ?")` 每日凌晨自動掃除過期 24 小時的防重發 Key（已改用 `OffsetDateTime` UTC 時區）。
- **隔離儲存**：
  - 實作 `LocalMediaStorageServiceImpl` (Profile: local) 與 `LocalMediaWebConfig`，開發期將檔案轉存至 `/tmp/cat_sitter_media`。
  - 實作 `GcsMediaStorageServiceImpl` 介面，供後續整合真實 Google Cloud Storage。

## 4. 業務邏輯服務 (Service Layer)
> [!IMPORTANT]
> 所有的核心防禦機制與安全例外皆已在此層實作完畢！

- **`CareNoteService`**:
  - **Recreate-on-Save**：存檔時清空舊有 `care_note_items`，重新寫入保持乾淨的 `sort_order`。
  - **Append-Only 模板追加**：套用模板時使用 `COALESCE(MAX, -1) + 1` 演算法，接續既有記事本繼續排版，不會刪除飼主原有的紀錄。
  - **首次初始化 (UPSERT)**：無論是查詢還是存檔，當 `care_notes` Header 不存在時自動初始化。
  - **回傳 UUID**：`saveCareNote` 回傳值改為 `UUID`，便於 Controller 取得並包裝成 `careNoteId` 響應。
- **`CareMediaService`**:
  - **反向補償機制**：當資料庫 Transaction Rollback 時，會攔截 `Exception` 並透過 `try/catch` 清除已上傳至 GCS 的檔案實體。
- **例外處理加固**：將所有涉及「查無資源」或「非擁有人」的邏輯由原本拋出 `IllegalArgumentException` 修改為直接拋出 `org.springframework.security.access.AccessDeniedException`，以利全域安全攔截。

## 5. Web API 控制器 (Controller Layer)
- **`CareNoteController` & `CareMediaController`**：
  - **SaaS Gating (P1)**：在類別級別套用 `@RequirePlan(PlanTier.FREE)`；同時擴展 `PlanGatingAspect` 使其支援 `@within(requirePlan)` 以攔截 Class 級別的方案控制。
  - **防重複提交 (P0)**：引入並注入新封裝的 `IdempotencyService` 進行 `checkAndConsume(key, userId)`，不再直接碰觸 Repository。
  - **IDOR 安全防禦 (P0) [加固]**：
    - 寫入與套用模板端點：顯式比對 `TokenContext.getUserId()` 必須與 `sitterId` 相同。
    - GET 照護記事與媒體端點：使用 OR 邏輯比對，`TokenContext.getUserId()` 只要等於 `sitterId` 或 `ownerId` 其中之一即可通過，確保保母與飼主均能合法讀取照護資訊。
      ```java
      UUID userId = TokenContext.getUserId();
      if (!sitterId.equals(userId) && !ownerId.equals(userId)) {
          throw new AccessDeniedException("權限不足");
      }
      ```
  - [x] 正確的 Response 結構 (P1)：在 `saveCareNote` 端點中，正確串接 Service 的 `UUID` 並將其包裝在 `data.careNoteId` 中返回。
  - [x] **超限處理語意對齊 (P3)**：將模板與媒體超出上限之異常在 Service 改為拋出 `IllegalArgumentException`，Controller 捕獲 `IllegalArgumentException` 回傳 400 Bad Request，消除與全域 `IllegalStateException` -> 409 Conflict 的語意衝突。

## 6. 安全加固與修正細節 (Security & Refactoring Details)
這部分針對 `project-auditor` 的回饋進行了深度的安全性與語意加固：
- **IDOR 修正（OR 邏輯）**：所有 GET 照護端點（CareNote/CareMedia）校驗邏輯全面修改為 Sitter 或 Owner 本人皆可放行（`sitterId.equals(userId) || ownerId.equals(userId)`），杜絕外部越權存取 (IDOR)。
- **Idempotency 真正落地**：抽離 `IdempotencyService` 封裝防重送邏輯；`GlobalExceptionHandler` 對 `DataIntegrityViolationException` 針對 idempotency 進行檢測，在 UNIQUE 鍵衝突時正確回傳 `409 Conflict`。
- **稽核日誌 DB 寫入**：將原本不實作的 `AuditLogService` 改為使用 `CareLogRepository` 真正將軌跡寫入 `care_logs` 表，保障稽核合規。
- **例外語意清理**：將容量/數量限制超限的例外由原先與全域 409 衝突的 `IllegalStateException` 替換為 `IllegalArgumentException`，回傳穩定的 `400 Bad Request`。

## 7. 全域異常攔截 (Global Exception Handler)
- **`GlobalExceptionHandler`**:
  - 增加 `@ExceptionHandler(AccessDeniedException.class)` 捕獲安全拒絕例外，並回傳 `403 Forbidden`。
  - 調整 `@ExceptionHandler(DataIntegrityViolationException.class)`，當判定 message 含有 `idempotency`（不分大小寫）時，精準回傳 `409 Conflict`，完成冪等鍵衝突的對應。移除先前殘留的舊 Constraint 名稱 `orders_idempotency_key_key` 死碼。

## 8. 測試驗證 (TS-021) 與 JPA 疑難排解 (Troubleshooting)
本階段完成了 18 個測試場景的實作與驗證，所有單元與整合測試均順利通過（`BUILD SUCCESS`）。以下為實作中的關鍵挑戰與解決方案：

### A. 冪等性重複 Key 被 JPA Merge 繞過問題
- **問題**：在 `IdempotencyKey` 中，因為使用了自訂複合主鍵 `(idempotencyKey, userId)`。呼叫 `idempotencyKeyRepository.save(key)` 時，JPA 偵測到實體的 ID 欄位不為空，會預設執行 `merge` (即 UPDATE) 而非 `persist` (INSERT)。這導致當重複的 Key 被送入時，JPA 會直接執行 SQL UPDATE，而不會觸發資料庫的 `Unique` 約束，從而繞過了防重發機制的異常觸發。
- **解法**：在 `IdempotencyService` 進行 `save` 之前，先呼叫 `existsById()` 顯式檢查。若已存在，手動拋出 `DataIntegrityViolationException("Duplicate idempotency key")`，使 `GlobalExceptionHandler` 捕獲並正確回傳 `409 Conflict`。

### B. 手動指定 UUID 主鍵導致 StaleObjectStateException
- **問題**：`CareMedia` 的 `id` 原先被標記為 `@GeneratedValue`，但在 `CareMediaService.uploadMedia` 中卻是手動透過 `UUID.randomUUID()` 賦值。這使得 JPA 在 `save(media)` 時會因為偵測到 `id != null` 而誤判其為已存在的 detached 實體，並在 `flush` 時發送 SQL UPDATE。因資料庫中此時根本沒有該 UUID 的資料列，進而拋出 `StaleObjectStateException`。
- **解法**：
  1. 移除了 `CareMedia.id` 欄位上的 `@GeneratedValue` 註解。
  2. 讓 `CareMedia` 類別實作 `org.springframework.data.domain.Persistable<UUID>` 介面。
  3. 配合 `@PostPersist` 與 `@PostLoad` 註解來維護一個 `transient boolean isNew` 狀態變數。在 `isNew()` 方法中回傳該變數，確保 JPA 在保存時能精準識別其為新實體，強制發送 SQL INSERT（走 `persist` 流程）。

### C. 測試覆蓋率
- 實作的 `CareNoteServiceTest`、`CareMediaServiceTest`、`CareNoteControllerTest` 與 `CareMediaControllerTest` 共 18 個核心測項均成功通過，覆蓋了從 IDOR 安全越權（Sitter/Owner 雙重放行）、冪等性衝突（409）、SaaS Gating 攔截、Advisory Lock 到 GCS 刪除補償機制等全部核心業務場景。

> [!TIP]
> 本次修改已在本地使用 `mvn test -Dmaven.compiler.release=17 -Dtest=CareNoteServiceTest,CareMediaServiceTest,CareNoteControllerTest,CareMediaControllerTest` 進行完整聯測，18 個測試場景全數通過，無任何 Failures 與 Errors，建置狀態為 **BUILD SUCCESS**。

## 9. UAT 部署評估決議
- **評估日期**：2026-05-23
- **決議**：**暫不部署至 UAT 環境，繼續進行開發。**
- **考量點**：
  1. **前後端聯調優先**：後端 API 與安全防護已全數通過 18 個單元與整合測試，但前端對接（特別是 IDOR 雙重放行邏輯與 `idempotency-key` 處理）尚未聯調，此時部署後端無法實現完整業務流程的 UAT 驗收。
  2. **基礎設施依賴**：媒體上傳功能有 GCS 依賴，需在與前端聯調確認無誤後，與 UAT GCP 權限一同配置上線，以減少不必要的環境除錯成本。
  3. **下一步行動**：優先對接前端 UI，或繼續開發下一個業務模組，於階段性 Milestone 完成時再統一發布 UAT。
