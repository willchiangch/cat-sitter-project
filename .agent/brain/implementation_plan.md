# SD-021 照護記事與媒體庫測試實作計畫 (Task 6)

> [!NOTE]
> **計畫狀態：等待審查 (UNDER REVIEW)**
> 本計畫已根據最新版 `TS-021-care-notes-and-media.md` 進行對齊，全面規劃 Service 層與 Controller 層的單元/整合測試，涵蓋所有 12 個細分測試情境（Scenario 1 - 10）。

## User Review Required
請確認以下測試類別的劃分與驗證邏輯：
- **`CareNoteServiceTest`**: 涵蓋 S1 (getCareNote 首次初始化), S2 (saveCareNote 覆蓋重排與通知), S3 (createTemplate 數量超限例外), S4 (applyTemplate 的 Append-Only 追加模式與通知), S10 (Advisory Lock 並發防超限)。
- **`CareMediaServiceTest`**: 涵蓋 S5 (uploadMedia 數量超限), S6 (uploadMedia DB 失敗之 GCS 補償清除與 FAILED 日誌), S7 (deleteMedia 刪除與 GCS 同步刪除與通知)。
- **`CareNoteControllerTest` & `CareMediaControllerTest`**: 涵蓋 S8a (飼主唯讀寫入端點 403), S8b (無關保母越權 GET 403), S8c (保母與飼主合法 GET 200), S9 (Idempotency 重複請求回傳 409)。

## Proposed Changes

### 測試程式 (Tests)

#### [NEW] [CareNoteServiceTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/CareNoteServiceTest.java)
- **S1 (First Access)**: 驗證無 header 時返回空分區結構，`careNoteId` 為 null。
- **S2 (Recreate-on-Save)**: 驗證 `deleteByCareNoteId` 與新 item 依 order 重新寫入；驗證呼叫 `auditLogService` 及 `notificationService`。
- **S3 (Template Limit)**: 透過 `@MockitoBean` 讓 `SystemConfigService.getTemplateLimit()` 返回 2，驗證新增第 3 個拋出 `IllegalArgumentException`。
- **S4 (Apply Template)**: 驗證舊 item 存在、新 item sort_order 遞增；驗證呼叫 `notificationService`。
- **S10 (Concurrent Template)**: 透過多執行緒並發呼叫 `createTemplate`，驗證 Advisory Lock 生效，僅有一筆成功，另一筆拋出例外。

#### [NEW] [CareMediaServiceTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/CareMediaServiceTest.java)
- **S5 (Media Limit)**: Mock `SystemConfigService.getMediaLimit()` 返回 2，驗證新增第 3 個拋出 `IllegalArgumentException` 且不調用 `MediaStorageService.uploadMedia`。
- **S6 (Compensation Delete)**: Mock DB save 拋出異常，驗證 `mediaStorageService.deleteMedia(url)` 被呼叫 1 次，且 `care_logs` 紀錄 FAILED。
- **S7 (Delete Media)**: 正常刪除媒體時，驗證 DB 紀錄刪除、`mediaStorageService.deleteMedia` 呼叫、通知呼叫。

#### [NEW] [CareNoteControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/CareNoteControllerTest.java)
- **S8a (Owner Read-Only)**: 模擬 Owner 登入請求 `PUT /api/care-notes/{sitterId}/{ownerId}` 返回 403 Forbidden。
- **S8b (IDOR Read Block)**: 模擬無關 Sitter B 登入請求 `GET /api/care-notes/{sitterId}/{ownerId}` 返回 403 Forbidden。
- **S8c (OR Logic Read)**: 模擬 Owner A 或 Sitter A 登入請求 `GET /api/care-notes/{sitterId}/{ownerId}` 返回 200 OK。
- **S9 (Idempotency 409)**: 重複發送同個 `Idempotency-Key` 返回 409 Conflict。

#### [NEW] [CareMediaControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/CareMediaControllerTest.java)
- 模擬並驗證 CareMedia 控制器的 IDOR 與冪等控制邏輯（對齊 S8a、S8b、S8c、S9）。

---

## Verification Plan

### Automated Tests
- 本地執行 `mvn test -Dtest=CareNoteServiceTest,CareMediaServiceTest,CareNoteControllerTest,CareMediaControllerTest` 驗證所有測試案例皆能通過。
- 測試完成後，執行 `/project-auditor` 進行專案審計，確保系統狀態變更為 `COMPLIANT`。
