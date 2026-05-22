- [x] **1. CareNoteService 測試實作 (Service 層)**
  - [x] 實作 `CareNoteServiceTest.java` 涵蓋：
    - [x] Scenario 1: getCareNote 首次初始化 (AC-1)
    - [x] Scenario 2: saveCareNote Recreate-on-Save 覆蓋重排與通知 (AC-2, AC-4)
    - [x] Scenario 3: createTemplate 數量限制拋出例外 (AC-3)
    - [x] Scenario 4: applyTemplate 的 Append-Only 追加模式與通知 (AC-4)
    - [x] Scenario 10: Advisory Lock 並發超量防護 (SD-021 §1.5)

- [x] **2. CareMediaService 測試實作 (Service 層)**
  - [x] 實作 `CareMediaServiceTest.java` 涵蓋：
    - [x] Scenario 5: uploadMedia 數量限制拋出例外 (AC-6)
    - [x] Scenario 6: uploadMedia DB 儲存失敗時的 GCS 反向補償清除
    - [x] Scenario 7: deleteMedia 正常刪除與 GCS 同步刪除與通知 (AC-7)

- [x] **3. Controller 層整合測試實作 (API / Security / Idempotency)**
  - [x] 實作 `CareNoteControllerTest.java` 涵蓋：
    - [x] Scenario 8a: 飼主唯讀寫入端點拒絕 (403)
    - [x] Scenario 8b: IDOR 越權防護 — 無關保母讀取 (403)
    - [x] Scenario 8c: OR 邏輯驗證 — 飼主合法讀取 GET (200)
    - [x] Scenario 9: Idempotency 重複請求回傳 409
  - [x] 實作 `CareMediaControllerTest.java` 涵蓋：
    - [x] 媒體上傳/刪除/查詢的 IDOR 越權與唯讀驗證
    - [x] 媒體上傳/刪除的冪等性驗證

- [x] **4. 驗證與審計**
  - [x] 執行本地單元測試 `mvn test` 確保全部通過
  - [x] 執行 `/project-auditor` 確認稽核結果由 AT RISK 變更為 COMPLIANT
  - [x] 更新 `walkthrough.md` 總結測試結果與合規狀態
