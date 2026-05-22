# TS-021: 照護記事與媒體庫 (Care Notes & Media)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | SD-021 / PRD-021 |
| **測試類型** | ✅ 功能測試 / ✅ 安全測試 / ✅ 彈性與補償測試 / ✅ 並發測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | 🟢 已規劃 (12/12 Scenarios) |

---

## 0. 前置條件 (Prerequisites)
- **認證狀態**：所有參與測試之使用者（Sitter/Owner）皆須已登入，JWT 有效。
- **時區標準**：所有時間欄位皆符合 `OffsetDateTime` (UTC)。
- **測試隔離**：每個 Scenario 執行完畢後須清除測試資料，確保 Scenario 間互不影響。

---

## Scenario 1: getCareNote 首次初始化 (AC-1)
* **Given**: DB 中不存在 Sitter A 與 Owner A 之間的任何照護記事記錄。
* **When**: 查詢 `getCareNote(sitterId=A, ownerId=A)`。
* **Then**: 返回 200 OK，`careNoteId` 為 null，且固定 6 個大項（SERVICE/CONTACT/WARNING/PREFERENCE/HOSPITAL/OTHER）均為空列表。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 查詢不存在的記事本 | 返回 200，6 大分區皆為空陣列 | DB 無 `care_notes` header；Response `careNoteId == null` |

**Edge Cases**:
- 傳入不存在的 `sitterId`：仍應返回空結構（不得拋 404）。

**自動化對應**: `CareNoteServiceTest.should_InitializeEmptyCareNote_When_FirstAccess()`

---

## Scenario 2: saveCareNote 的 Recreate-on-Save 覆蓋重排 (AC-2, AC-4)
* **Given**: Sitter A 與 Owner A 的記事本已包含 2 筆舊項目。
* **When**: Sitter A 送出 3 筆新項目（跨兩個 section_type）進行保存。
* **Then**:
  - DB 中舊有 `care_note_items` 必須被完全刪除。
  - 新項目成功寫入，各區塊內 `sort_order` 從 0 開始遞增。
  - `care_logs` 寫入一筆 `SAVE_CARE_NOTE / SUCCESS` 日誌。
  - `NotificationService.sendNotification` 被呼叫，通知 Owner A 記事本已更新（AC-4）。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 建立初始記事本（2 筆舊項目） | 成功 | DB `care_note_items` count = 2 |
| 2 | saveCareNote（3 筆新項目） | 返回 200，data 含 `careNoteId` | 舊 items 不存在；新 items `sort_order` 依區塊從 0 遞增 |
| 3 | 驗證稽核日誌 | — | `care_logs` 有 1 筆 `SAVE_CARE_NOTE/SUCCESS` |
| 4 | 驗證通知 | — | `NotificationService.sendNotification(ownerId, ...)` 被呼叫 1 次 |

**Edge Cases**:
- 送出空陣列（清空記事本）：`care_note_items` 應清空，`care_notes` header 應保留。

**自動化對應**: `CareNoteServiceTest.should_PerformRecreateOnSave_When_SaveCareNote()`

---

## Scenario 3: createTemplate 數量限制拋出例外 (AC-3)
* **Given**: 保母已建立之範本數量已達系統上限（模擬上限為 2 筆）。
* **When**: 保母嘗試呼叫 `createTemplate` 建立第 3 個範本。
* **Then**: 系統應拋出 `IllegalArgumentException`，提示「模板數量已達上限」，且資料庫中範本數量不得增加。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 建立 2 個模板達到上限 | 成功 | DB `care_note_templates` count = 2 |
| 2 | 嘗試建立第 3 個模板 | 拋出例外 / API 返回 400 | DB count 維持 2；`IllegalArgumentException` 被拋出 |

**Edge Cases**:
- 超限時改呼叫 `updateTemplate`（覆蓋既有模板）：應正常成功，不受數量限制影響。

**自動化對應**: `CareNoteServiceTest.should_ThrowException_When_TemplateLimitExceeded()`

---

## Scenario 4: applyTemplate 的 Append-Only 追加模式 (AC-4)
* **Given**:
  - Sitter A 與 Owner A 的 `SERVICE` 區塊已有一個舊項目（sort_order = 0）。
  - Sitter A 擁有一個範本，其中 `SERVICE` 區塊有兩個項目。
* **When**: 套用該範本至 Sitter A / Owner A 的記事本。
* **Then**:
  - 舊有項目必須保留（sort_order = 0 不被刪除）。
  - 範本的兩個項目接續追加，sort_order = 1 和 2（COALESCE(MAX, -1) + 1 邏輯）。
  - `care_logs` 寫入 `APPLY_TEMPLATE / SUCCESS`。
  - `NotificationService.sendNotification` 被呼叫（AC-4）。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 建立含 1 筆舊項目的記事本 | 成功 | DB `care_note_items` count = 1，sort_order = 0 |
| 2 | 套用含 2 個 SERVICE 項目的範本 | 返回 200 | DB count = 3；新項目 sort_order = 1, 2；舊項目保留 |
| 3 | 驗證稽核日誌 | — | `care_logs` 有 1 筆 `APPLY_TEMPLATE/SUCCESS` |
| 4 | 驗證通知 | — | `NotificationService.sendNotification(ownerId, ...)` 被呼叫 1 次 |

**Edge Cases**:
- 首次套用（care_notes Header 尚不存在）：應自動 UPSERT 建立主記錄，不拋錯。

**自動化對應**: `CareNoteServiceTest.should_AppendItemsInOrder_When_ApplyTemplate()`

---

## Scenario 5: uploadMedia 數量限制拋出例外 (AC-6)
* **Given**: Sitter A 與 Owner A 的媒體庫數量已達系統上限（模擬上限為 2 筆）。
* **When**: 嘗試呼叫 `uploadMedia` 上傳新檔案。
* **Then**: 系統應拋出 `IllegalArgumentException`，提示「媒體數量已達上限」，且 `MediaStorageService.uploadMedia` 不被呼叫。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 建立 2 筆媒體達到上限 | 成功 | DB `care_media` count = 2 |
| 2 | 嘗試上傳第 3 筆媒體 | 拋出例外 / API 返回 400 | DB count 維持 2；`MediaStorageService.uploadMedia` **不被呼叫** |

**Edge Cases**:
- 刪除 1 筆後再上傳：應允許（count = 2 < 上限），`uploadMedia` 被正常呼叫。

**自動化對應**: `CareMediaServiceTest.should_ThrowException_When_MediaLimitExceeded()`

---

## Scenario 6: uploadMedia DB 儲存失敗時的 GCS 反向補償清除
* **Given**: `MediaStorageService.uploadMedia` 已成功執行並返回 URL。
* **When**: `careMediaRepository.save(...)` 拋出例外（模擬 DB 故障）。
* **Then**:
  - 系統呼叫 `mediaStorageService.deleteMedia(url)` 補償清除 GCS 檔案。
  - 例外原樣拋出，DB Transaction Rollback。
  - `care_logs` 紀錄 `UPLOAD_MEDIA / FAILED`（`REQUIRES_NEW` 確保寫入不受主事務影響）。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 模擬 GCS 上傳成功 | URL 返回 | `MediaStorageService.uploadMedia` 被呼叫 1 次 |
| 2 | 模擬 DB save 拋出例外 | 拋出 RuntimeException | `MediaStorageService.deleteMedia(url)` 被呼叫 1 次（補償） |
| 3 | 驗證 DB 狀態 | — | `care_media` count 不變；Transaction 已 Rollback |
| 4 | 驗證稽核日誌 | — | `care_logs` 有 1 筆 `UPLOAD_MEDIA/FAILED` |

**Edge Cases**:
- GCS 補償刪除本身也失敗：`log.error` 被呼叫，但不再向上拋（避免遮蓋原始錯誤）。

**自動化對應**: `CareMediaServiceTest.should_TriggerCompensationDelete_When_DbSaveFails()`

---

## Scenario 7: deleteMedia 正常刪除與 GCS 同步刪除 (AC-7)
* **Given**: 存在一筆由 Sitter A 上傳的媒體檔案。
* **When**: Sitter A 呼叫 `deleteMedia`。
* **Then**:
  - DB `care_media` 記錄被刪除。
  - `mediaStorageService.deleteMedia(url)` 被呼叫（GCS 同步刪除）。
  - `care_logs` 紀錄 `DELETE_MEDIA / SUCCESS`。
  - `NotificationService.sendNotification` 被呼叫（AC-7）。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 刪除媒體 | 返回 200 | DB `care_media` 該筆記錄不存在 |
| 2 | 驗證 GCS 同步 | — | `MediaStorageService.deleteMedia` 被呼叫 1 次 |
| 3 | 驗證稽核日誌 | — | `care_logs` 有 1 筆 `DELETE_MEDIA/SUCCESS` |
| 4 | 驗證通知 | — | `NotificationService.sendNotification(ownerId, ...)` 被呼叫 1 次 |

**Edge Cases**:
- GCS 刪除失敗：`log.error` 被呼叫，但 DB 刪除不回滾（反向補償設計）。

**自動化對應**: `CareMediaServiceTest.should_DeleteFromDbAndStorage_When_DeleteMedia()`

---

## Scenario 8a: 飼主唯讀強制 (AC-5) — 寫入端點拒絕
* **Given**: Owner A 已登入，JWT 中 `userId == ownerId`（非 sitterId）。
* **When**: Owner A 嘗試呼叫 `PUT /api/care-notes/{sitterId=A}/{ownerId=A}`（寫入端點）。
* **Then**: 返回 403 Forbidden（IDOR 驗證：sitterId != Token.userId）。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | Owner A 呼叫寫入端點 | 返回 403 | Controller IDOR check 拋 `AccessDeniedException`；DB 無資料異動 |

**Edge Cases**:
- Owner A 呼叫 GET 查詢端點（唯讀）：應返回 200（見 S8c）。

**自動化對應**: `CareNoteControllerTest.should_Return403_When_OwnerAttemptsToWrite()`

---

## Scenario 8b: IDOR 越權防護 — 無關保母讀取
* **Given**: Sitter B 已登入，其 `userId` 與路徑中的 `sitterId=A` 及 `ownerId=A` 均不符。
* **When**: Sitter B 嘗試呼叫 `GET /api/care-notes/{sitterId=A}/{ownerId=A}`。
* **Then**: 返回 403 Forbidden。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | Sitter B 讀取 Sitter A 的記事本 | 返回 403 | Controller OR 邏輯：sitterId(A) != B.userId AND ownerId(A) != B.userId → `AccessDeniedException` |

**自動化對應**: `CareNoteControllerTest.should_Return403_When_UnrelatedSitterAccesses()`

---

## Scenario 8c: OR 邏輯驗證 — 飼主合法讀取 GET
* **Given**: Owner A 已登入，JWT 中 `userId == ownerId(A)`。
* **When**: Owner A 呼叫 `GET /api/care-notes/{sitterId=A}/{ownerId=A}`。
* **Then**: 返回 200 OK，記事本資料正常呈現。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | Owner A 讀取自己的記事本 | 返回 200，資料完整 | Controller OR 邏輯：ownerId(A) == A.userId → 驗證通過 |

**Edge Cases**:
- Owner A 以 `ownerId=A` 讀取另一組配對（其中 `sitterId=B, ownerId=C`）：應返回 403。

**自動化對應**: `CareNoteControllerTest.should_Return200_When_OwnerReadsOwnCareNote()`

---

## Scenario 9: Idempotency 重複請求 → 409 (SD-021 §1.4)
* **Given**: 已成功呼叫 `PUT /api/care-notes/{sitterId}/{ownerId}`，`Idempotency-Key: key-abc` 已消費入庫。
* **When**: 相同 `Idempotency-Key: key-abc` 再送一次相同請求。
* **Then**: 返回 409 Conflict（`DUPLICATE_REQUEST`），`care_note_items` 資料與第一次相同，無重複寫入。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 第一次送出請求 | 返回 200，careNoteId 返回 | `idempotency_keys` 新增 1 筆；`care_note_items` 寫入 |
| 2 | 相同 Key 再送一次 | 返回 409 DUPLICATE_REQUEST | `idempotency_keys` count 不變；`care_note_items` count 不變 |

**Edge Cases**:
- 不同 userId 使用相同 Key：Composite PK 為 `(key, user_id)`，視為不同 Key，允許第二個請求成功。

**自動化對應**: `CareNoteControllerTest.should_Return409_When_DuplicateIdempotencyKey()`

---

## Scenario 10: Advisory Lock 並發超量防護 (SD-021 §1.5)
* **Given**: 模板數量為上限 - 1 筆（如目前 1 筆，上限 2 筆），兩個請求同時送出 `POST /api/care-notes/templates`。
* **When**: 兩個請求在同一個 Transaction 週期內並發觸發。
* **Then**: 只有一筆成功（201 Created），另一筆返回 400（IllegalArgumentException 超限）。DB 中模板數量 == 上限，無超量記錄。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 建立 1 筆模板（上限 - 1） | 成功 | DB `care_note_templates` count = 1 |
| 2 | 同時送出 2 個 createTemplate 請求 | 1 個 201、1 個 400 | DB count == 上限；`pg_advisory_xact_lock` 確保序列化，無超量 |

**Edge Cases**:
- 媒體庫上傳並發（相同邏輯）：同時兩個 `POST /api/care-media` 在邊界時，結果相同。

**自動化對應**: `CareNoteServiceTest.should_PreventOverLimit_When_ConcurrentCreate()`

---

## 驗收標準追溯矩陣 (AC Traceability)

| PRD AC | 描述 | 覆蓋 Scenario |
| :--- | :--- | :--- |
| AC-1 | 6 大項分類固定，首次存取返回空結構 | S1 |
| AC-2 | 條目序號連續性（Recreate-on-Save 重算） | S2 |
| AC-3 | 模板超限例外，需覆蓋既有才可新增 | S3 |
| AC-4 | 記事本更新（儲存 / 套用模板）後通知飼主 | S2, S4 |
| AC-5 | 飼主唯讀，寫入端點返回 403 | S8a |
| AC-6 | 媒體 20 筆上限阻擋 | S5 |
| AC-7 | 媒體庫更新（上傳 / 刪除）後通知飼主 | S7 |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**:
  - [CareNoteServiceTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/CareNoteServiceTest.java)
  - [CareMediaServiceTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/CareMediaServiceTest.java)
  - [CareNoteControllerTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/CareNoteControllerTest.java)
  - [CareMediaControllerTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/CareMediaControllerTest.java)
- **執行指令**: `mvn test -Dtest=CareNoteServiceTest,CareMediaServiceTest,CareNoteControllerTest,CareMediaControllerTest`
- **最後驗證日期**: 2026-05-23
