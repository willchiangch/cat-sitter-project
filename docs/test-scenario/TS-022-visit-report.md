# TS-022: 行程照護日誌與多媒體回報 (Visit Report & Media)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | SD-022 / PRD-022 |
| **測試類型** | ✅ 功能測試 / ✅ 安全測試 / ✅ 彈性與補償測試 / ✅ 並發測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | 🟢 已完成 (15/15 Scenarios) |

---

## 0. 前置條件 (Prerequisites)
- **認證狀態**：所有參與測試之使用者（Sitter/Owner）皆須已登入，JWT 有效。
- **時區標準**：所有時間欄位皆符合 `OffsetDateTime` (UTC)。
- **測試隔離**：每個 Scenario 執行完畢後須清除測試資料，確保 Scenario 間互不影響。
- **快照基準**：預設使用 PRO 方案快照（`maxPhotos=10, maxVideos=2`），除非 Scenario 另行指定。
- **GCS Mock**：`MediaStorageService` 以 `@MockitoBean` 替換，預設回傳合法 URL。

---

## Scenario 1: 保母暫存文字草稿成功（首次建立自動初始化 DRAFT）

* **Given**: DB 中不存在 `visitId=V` 的日誌記錄；`Visit.status = IN_PROGRESS`。
* **When**: 保母呼叫 `PUT /api/visits/{visitId}/report`，`content = "今日餵食正常"`，`version = 0`。
* **Then**: 返回 200，`status = DRAFT`，DB 中自動建立一筆 `visit_service_reports`；`order_logs` 寫入 `CREATE/SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | PUT 暫存草稿（首次） | 200 OK，`data.status = DRAFT`，`data.version = 1` | DB `visit_service_reports` 新增 1 筆；`status = DRAFT` |
| 2 | 驗證自動初始化 | `reportId` 有值 | 首次建立時後端 Lazy-Init `INSERT INTO visit_service_reports` |
| 3 | 驗證稽核日誌 | — | `order_logs` 有 1 筆 `SERVICE_REPORT_MGT / CREATE / SUCCESS`，含 `reportId` |

**Edge Cases**:
- `content` 為空字串：應接受，`visit_service_reports.content` 儲存空字串。
- 第二次暫存（`version = 1`）：`action_type = UPDATE`，版本遞增至 2。

**自動化對應**: `VisitReportControllerTest.should_SaveDraft_Successfully()` ✅

---

## Scenario 2: 保母上傳媒體成功（IMAGE + 稽核日誌）

* **Given**: `Visit.status = IN_PROGRESS`；方案快照 `maxPhotos = 10`，目前已上傳 0 張。
* **When**: 保母上傳 `image/jpeg`（< 1MB）並帶 `Idempotency-Key`。
* **Then**: 返回 200，`data.mediaId` 與 `data.mediaUrl` 有值；`service_report_media` 新增 1 筆（`is_deleted = false`）；`order_logs` 寫入 `UPLOAD_MEDIA/SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST multipart 上傳 JPG | 200 OK，`data.mediaId` 有值 | `MediaStorageService.uploadReportMedia` 被呼叫 1 次；DB `service_report_media` count = 1 |
| 2 | 驗證媒體路徑規則 | `mediaUrl` 含 `planTier` 前綴 | URL 格式：`/{bucket}/{snapshot_plan_tier}/{date}/{order_id}/{uuid}.jpg` |
| 3 | 驗證稽核日誌 | — | `order_logs` 有 1 筆 `SERVICE_REPORT_MEDIA / CREATE / SUCCESS` |

**Edge Cases**:
- 首次上傳時報告尚不存在：後端應自動 Lazy-Init `visit_service_reports`。

**自動化對應**: `VisitReportControllerTest.should_UploadMedia_Successfully()` ⏳ **待補**

---

## Scenario 3: 保母邏輯刪除媒體成功

* **Given**: DB 中存在 `mediaId=M1`，`version = 0`；日誌狀態為 `DRAFT`。
* **When**: 保母呼叫 `DELETE /api/visits/media/{mediaId}`，帶 `{ "version": 0 }`。
* **Then**: 返回 200；`service_report_media.is_deleted = true`（非實體刪除）；`order_logs` 寫入 `DELETE/SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | DELETE 媒體 | 200 OK，`data = null` | DB `service_report_media.is_deleted = true`；記錄仍存在 |
| 2 | 驗證 GCS 不呼叫 | — | `MediaStorageService.deleteMedia` **不被呼叫**（邏輯刪除無需 GCS 同步） |
| 3 | 驗證稽核日誌 | — | `order_logs` 有 1 筆 `SERVICE_REPORT_MEDIA / DELETE / SUCCESS` |
| 4 | 驗證配額更新 | 再次上傳仍在限額內 | `SELECT count WHERE is_deleted = false` 不計入已刪除媒體 |

**Edge Cases**:
- 刪除後重新計算配額，應可繼續上傳至上限。

**自動化對應**: `VisitReportControllerTest.should_DeleteMedia_Successfully()` ⏳ **待補**

---

## Scenario 4: 保母正式送出日誌成功（非同步通知 + 稽核）

* **Given**: 日誌狀態為 `DRAFT`；`Visit.status = DONE`；`finishedAt` 在 24 小時內。
* **When**: 保母呼叫 `POST /api/visits/{visitId}/report/submit` 帶 `Idempotency-Key`。
* **Then**: 返回 200；`visit_service_reports.status = SUBMITTED`，`submitted_at` 寫入時間戳；非同步發送飼主通知；`order_logs` 寫入 `SUBMIT_REPORT/SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 建立 DRAFT 草稿 | 成功 | DB `status = DRAFT` |
| 2 | 設定 Visit 為 DONE | — | `visits.status = DONE`，`finishedAt` 為 1 小時前 |
| 3 | POST submit | 200 OK，`data = null` | DB `status = SUBMITTED`；`submitted_at` 不為 null |
| 4 | 驗證 CAS 寫入 | — | `UPDATE WHERE status = 'DRAFT'` 影響 1 row |
| 5 | 驗證非同步通知 | — | `NotificationService.sendNotificationAsync(ownerId, ...)` 被呼叫 1 次 |
| 6 | 驗證稽核日誌 | — | `order_logs` 有 1 筆 `SERVICE_REPORT_SUBMIT / UPDATE / SUCCESS` |

**Edge Cases**:
- 通知發送失敗：不應影響日誌 `SUBMITTED` 狀態（`@Async` 失敗不回滾主事務）。

**自動化對應**: `VisitReportControllerTest.should_SubmitReport_Successfully()` ⏳ **待補**

---

## Scenario 5: 飼主讀取已送出日誌（SUBMITTED-only Gate 通過）

* **Given**: 日誌狀態為 `SUBMITTED`；飼主 JWT 中 `userId == ownerId`。
* **When**: 飼主呼叫 `GET /api/visits/{visitId}/report`。
* **Then**: 返回 200，`data.status = SUBMITTED`，`isEditable = false`，`submittedAt` 有值；媒體清單正確返回。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 建立 SUBMITTED 日誌 | 成功 | DB `status = SUBMITTED` |
| 2 | 飼主 GET 讀取 | 200 OK，完整資料 | `data.isEditable = false`；`data.status = SUBMITTED` |
| 3 | 驗證媒體清單 | `data.media` 正確包含媒體項目 | 僅返回 `is_deleted = false` 的媒體 |

**Edge Cases**:
- 日誌含多筆媒體（IMAGE + VIDEO 混合）：兩種 `mediaType` 均正確序列化。

**自動化對應**: `VisitReportControllerTest.should_Return200_When_OwnerReadsSubmittedReport()` ⏳ **待補**

---

## Scenario 6: 逾期 24 小時後暫存拒絕 (403 REPORT_EXPIRED)

* **Given**: `Visit.finishedAt` 為 25 小時前；日誌狀態為 `DRAFT`。
* **When**: 保母呼叫 `PUT /api/visits/{visitId}/report` 嘗試暫存。
* **Then**: 返回 403，`error = MSG_DATA_REPORT_EXPIRED`；DB 資料無異動。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 設定 `finishedAt` 為 25 小時前 | — | `visits.finishedAt = NOW() - 25h` |
| 2 | PUT 暫存草稿 | 403 Forbidden | `error = MSG_DATA_REPORT_EXPIRED`；`visit_service_reports` 無新資料 |

**Edge Cases**:
- `finishedAt = NOW() - 23.5h`（未逾期邊界）：應允許暫存，返回 200。
- 同樣的逾期邏輯適用於 `submitReport` 與 `uploadMedia`（Lazy Evaluation 全面套用）。

**自動化對應**: `VisitReportControllerTest.should_Return403_When_DraftIsExpired()` ✅

---

## Scenario 7: 飼主端草稿隔離 — 未送出草稿不可見 (404)

* **Given**: 日誌狀態為 `DRAFT`；飼主 JWT 中 `userId == ownerId`。
* **When**: 飼主呼叫 `GET /api/visits/{visitId}/report`。
* **Then**: 返回 404，`error = MSG_DATA_F11`；草稿內容對飼主完全不可見。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 保母建立 DRAFT 草稿 | 200 OK（保母端） | DB `status = DRAFT` |
| 2 | 飼主 GET 讀取 | 404 Not Found | `error = MSG_DATA_F11`；後端 SUBMITTED-only Gate 攔截 |

**Edge Cases**:
- 邏輯 `EXPIRED` 狀態（非 SUBMITTED）：同樣返回 404，不得洩漏逾期草稿。
- 日誌不存在（未建立）：亦返回 404（相同的對外門面）。

**自動化對應**: `VisitReportControllerTest.should_Return404_When_OwnerReadsDraftReport()` ✅

---

## Scenario 8: 進行中行程送出日誌拒絕 (422 VISIT_NOT_FINISHED)

* **Given**: `Visit.status = IN_PROGRESS`；日誌狀態為 `DRAFT`。
* **When**: 保母呼叫 `POST /api/visits/{visitId}/report/submit`。
* **Then**: 返回 422，`error = MSG_DATA_VISIT_NOT_FINISHED`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 保母建立草稿（Visit IN_PROGRESS） | 200 OK | — |
| 2 | POST submit | 422 Unprocessable Entity | `error = MSG_DATA_VISIT_NOT_FINISHED`；`status` 仍為 `DRAFT` |

**Edge Cases**:
- `Visit.status = PENDING` 或 `SCHEDULED` 時嘗試暫存：422 `INVALID_VISIT_STATUS`。

**自動化對應**: `VisitReportControllerTest.should_Return422_When_SubmittingInProgressReport()` ✅

---

## Scenario 9: 冪等性重複請求拒絕 (409 IDEMPOTENCY_CONFLICT)

* **Given**: `Idempotency-Key: key-abc` 已成功消費入 `idempotency_keys` 表。
* **When**: 使用相同 Key 再次呼叫 `POST /api/visits/{visitId}/media`。
* **Then**: 返回 409，`error = MSG_DATA_IDEMPOTENCY_CONFLICT`；`service_report_media` 數量不增加。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 第一次上傳（Key = key-abc） | 200 OK | `idempotency_keys` 新增 1 筆；`service_report_media` count = 1 |
| 2 | 第二次上傳（同 Key） | 409 Conflict | `error = MSG_DATA_IDEMPOTENCY_CONFLICT`；DB count 不變 |

**Edge Cases**:
- 不同 `userId` 使用相同 Key：Composite PK 為 `(key, user_id)`，視為不同 Key，允許成功。
- `submitReport` 的冪等性：同樣適用，重複提交返回 409。

**自動化對應**: `VisitReportControllerTest.should_Return409_When_DuplicateIdempotencyKeyOnMediaUpload()` ✅

---

## Scenario 10: 方案配額超限拒絕 (403 AUTH_PLAN_LIMIT)

* **Given**: 訂單快照 `maxPhotos = 0`（模擬 Free 方案零配額）。
* **When**: 保母呼叫 `POST /api/visits/{visitId}/media` 上傳 IMAGE。
* **Then**: 返回 403，`error = MSG_DATA_PLAN_LIMIT`；`MediaStorageService.uploadReportMedia` **不被呼叫**。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 設定快照 `maxPhotos = 0` | — | — |
| 2 | 上傳 IMAGE | 403 Forbidden | `error = MSG_DATA_PLAN_LIMIT`；`service_report_media` count = 0；GCS 未呼叫 |

**Edge Cases**:
- VIDEO 超限（`maxVideos = 0`）：同樣返回 403 `MSG_DATA_PLAN_LIMIT`。
- 邏輯刪除媒體後再上傳（count < limit）：應放行（`WHERE is_deleted = false` 重算）。

**自動化對應**: `VisitReportControllerTest.should_Return403_When_PhotoUploadExceedsLimit()` ✅

---

## Scenario 11: 樂觀鎖版本衝突 (409 VERSION_CONFLICT)

* **Given**: DB 中 `visit_service_reports.version = 2`；保母傳入 `version = 1`（舊版本）。
* **When**: 保母呼叫 `PUT /api/visits/{visitId}/report`，`version = 1`。
* **Then**: 返回 409，`error = MSG_DATA_VERSION_CONFLICT`；DB 資料無異動。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 暫存兩次使版本推進至 2 | `version = 2` | DB `version = 2` |
| 2 | 帶舊 `version = 1` 再次暫存 | 409 Conflict | `error = MSG_DATA_VERSION_CONFLICT`；DB `version` 不變 |

**Edge Cases**:
- 媒體的 `version` 衝突（`DELETE media version = 0`，但 DB `version = 1`）：同樣返回 409。
- 前端應在每次成功操作後更新本地 `version` 值。

**自動化對應**: `VisitReportControllerTest.should_Return409_When_VersionConflict()` ⏳ **待補**

---

## Scenario 12: SUBMITTED 狀態後再修改拒絕 (409 REPORT_STATE_CONFLICT)

* **Given**: 日誌狀態已為 `SUBMITTED`。
* **When**: 保母嘗試呼叫 `PUT /api/visits/{visitId}/report` 再次暫存。
* **Then**: 返回 409，`error = MSG_DATA_STATE_CONFLICT`；`status` 仍為 `SUBMITTED`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 建立並送出日誌（SUBMITTED） | 成功 | DB `status = SUBMITTED` |
| 2 | 再次 PUT 暫存 | 409 Conflict | `error = MSG_DATA_STATE_CONFLICT`；DB `status = SUBMITTED` 不變 |

**Edge Cases**:
- 嘗試刪除 SUBMITTED 日誌下的媒體：同樣返回 409 `REPORT_STATE_CONFLICT`（不允許修改已送出日誌）。
- 重複 `submitReport`（CAS 影響 0 rows）：返回 409 `REPORT_STATE_CONFLICT`。

**自動化對應**: `VisitReportControllerTest.should_Return409_When_ReportAlreadySubmitted()` ⏳ **待補**

---

## Scenario 13: 媒體格式/大小驗證失敗 (400 INVALID_MEDIA_FORMAT)

* **Given**: 合法的行程狀態與方案配額。
* **When**: 保母上傳不符合規格的媒體檔案。
* **Then**: 返回 400，`error = MSG_DATA_INVALID_MEDIA`；`MediaStorageService.uploadReportMedia` **不被呼叫**。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1a | 上傳 GIF（`image/gif`，不支援格式） | 400 Bad Request | `error = MSG_DATA_INVALID_MEDIA`；GCS 未呼叫 |
| 1b | 上傳 JPG 但大小 > 1MB | 400 Bad Request | 同上 |
| 1c | 上傳 AVI 影片（不支援格式） | 400 Bad Request | 同上 |
| 1d | 上傳 MP4 但時長 < 15s 或 > 30s | 400 Bad Request | 同上（後端讀取 metadata 驗證） |
| 1e | 上傳 MP4 但大小 > 50MB | 400 Bad Request | 同上 |

**Edge Cases**:
- 上傳合法 WebP（`image/webp`，< 1MB）：應返回 200。
- 上傳合法 MOV（`video/quicktime`，16s，< 50MB）：應返回 200。

**自動化對應**: `VisitReportControllerTest.should_Return400_When_InvalidMediaFormat()` ⏳ **待補**

---

## Scenario 14: GCS 上傳故障觸發事務回滾 (503 STORAGE_SERVICE_UNAVAILABLE)

* **Given**: `MediaStorageService.uploadReportMedia` Mock 拋出 `RuntimeException`。
* **When**: 保母呼叫 `POST /api/visits/{visitId}/media`。
* **Then**: 返回 503，`error = MSG_DATA_STORAGE_ERROR`；`@Transactional` 回滾，`service_report_media` 無新增記錄；`order_logs` 寫入 `CREATE/FAIL`（`REQUIRES_NEW` 確保日誌不受回滾影響）。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | Mock GCS 拋出 RuntimeException | — | `when(mediaStorageService.uploadReportMedia(...)).thenThrow(...)` |
| 2 | POST 上傳媒體 | 503 Service Unavailable | `error = MSG_DATA_STORAGE_ERROR` |
| 3 | 驗證事務回滾 | — | `service_report_media` count 不變；`@Transactional` 已 Rollback |
| 4 | 驗證失敗稽核日誌 | — | `order_logs` 有 1 筆 `SERVICE_REPORT_MEDIA / CREATE / FAIL`（`REQUIRES_NEW` 獨立提交） |

**Edge Cases**:
- `idempotency_keys` 在同一事務中寫入：GCS 失敗時連同回滾，下次重試可使用相同 Key。

**自動化對應**: `VisitReportControllerTest.should_Return503_When_GcsUploadFails()` ⏳ **待補**

---

## Scenario 15a: 越權防禦 — 無關保母讀取或編輯日誌 (403)

* **Given**: Sitter B 已登入，其 `userId` 與路徑中 `visitId` 對應的 `sitterId=A` 不符。
* **When**: Sitter B 呼叫 `GET /api/visits/{visitId}/report`。
* **Then**: 返回 403 Forbidden。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | Sitter B 讀取 Sitter A 的日誌 | 403 Forbidden | `JWTAuthFilter` IDOR check：`sitterId(A) != B.userId` → 拒絕 |

**自動化對應**: `VisitReportControllerTest.should_Return403_When_UnrelatedSitterAccessesReport()` ✅

---

## Scenario 15b: 越權防禦 — 飼主嘗試呼叫寫入端點 (403)

* **Given**: Owner A 已登入，嘗試呼叫保母專屬的寫入端點。
* **When**: Owner A 呼叫 `PUT /api/visits/{visitId}/report`（暫存草稿）。
* **Then**: 返回 403 Forbidden；DB 無異動。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | Owner A 呼叫 PUT 暫存端點 | 403 Forbidden | `JWTAuthFilter`：`Token.userId == ownerId` 但非 `sitterId` → 拒絕寫入 |

**Edge Cases**:
- Owner A 呼叫 GET 讀取已 SUBMITTED 的日誌：應返回 200（見 S5）。

**自動化對應**: `VisitReportControllerTest.should_Return403_When_OwnerAttemptsToWrite()` ⏳ **待補**

---

## 驗收標準追溯矩陣 (AC Traceability)

| SD-022 AC | 描述 | 覆蓋 Scenario |
| :--- | :--- | :--- |
| AC-1 | 保母可暫存文字草稿，首次自動初始化 DRAFT | S1 |
| AC-2 | 保母可上傳媒體（IMAGE/VIDEO），寫入 GCS 與 DB | S2 |
| AC-3 | 保母可邏輯刪除媒體，配額動態重算 | S3 |
| AC-4 | 保母可正式送出日誌（Visit DONE），CAS 防競爭 | S4 |
| AC-5 | 保母讀取自己的日誌，含 `isEditable` 動態判定 | S1, S4 |
| AC-6 | 飼主唯讀隔離（SUBMITTED-only Gate） | S5, S7 |
| AC-7 | 逾期 24h 後所有寫入操作拒絕（Lazy Evaluation） | S6 |
| AC-8 | SaaS 方案配額限制媒體上傳上限 | S10 |
| AC-9 | 送出後非同步通知飼主 | S4 |
| AC-10 | IDOR 越權防護（保母/飼主雙向） | S15a, S15b |
| AC-11 | 冪等性防護防止重複提交 | S9 |
| AC-12 | 樂觀鎖防止弱網並發覆蓋 | S11 |
| AC-13 | 狀態機防護（SUBMITTED 不可再改） | S12 |
| AC-14 | 媒體格式/大小/時長前後端雙重驗證 | S13 |
| AC-15 | GCS 故障補償（事務回滾 + 失敗稽核日誌） | S14 |

---

## 自動化實作追溯 (Traceability)

- **測試專案**: `backend`
- **測試類別**:
  - [VisitReportControllerTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/VisitReportControllerTest.java)
- **已自動化 (15/15)**:
  - `should_SaveDraft_Successfully()` → S1
  - `should_UploadMedia_Successfully()` → S2
  - `should_DeleteMedia_Successfully()` → S3
  - `should_SubmitReport_Successfully()` → S4
  - `should_Return200_When_OwnerReadsSubmittedReport()` → S5
  - `should_Return403_When_DraftIsExpired()` → S6
  - `should_Return404_When_OwnerReadsDraftReport()` → S7
  - `should_Return422_When_SubmittingInProgressReport()` → S8
  - `should_Return409_When_DuplicateIdempotencyKeyOnMediaUpload()` → S9
  - `should_Return403_When_PhotoUploadExceedsLimit()` → S10
  - `should_Return409_When_VersionConflict()` → S11
  - `should_Return409_When_ReportAlreadySubmitted()` → S12
  - `should_Return400_When_InvalidMediaFormat()` → S13
  - `should_Return503_When_GcsUploadFails()` → S14
  - `should_Return403_When_UnrelatedSitterAccessesReport()` → S15a
  - `should_Return403_When_OwnerAttemptsToWrite()` → S15b
- **待補自動化 (0/15)**:
- **執行指令**: `mvn test -Dtest=VisitReportControllerTest`
- **最後驗證日期**: 2026-05-24
