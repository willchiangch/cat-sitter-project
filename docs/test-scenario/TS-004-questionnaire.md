# TS-004: 事前問卷設定 (Pre-Booking Questionnaire)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-004 / SD-004 |
| **測試類型** | ✅ 功能測試 / ✅ 權限測試 |
| **優先級** | P1 (High) |
| **自動化狀態** | 🟢 已實作 (7/7 Scenarios) |

---

## 0. 前置條件 (Prerequisites)
- 保母已登入且已建立公開檔案。
- 測試涉及跨帳號操作時，另備一名「非本人保母」帳號。

## Scenario 1: 新增單選題成功
* **Given**: 保母尚無任何問卷題目。
* **When**: 保母新增一題 `answerType=RADIO`，`options=["會","不會"]`。
* **Then**: 題目建立成功，`sortOrder` 為 0，`isActive` 預設為 `true`。
* **自動化對應**: `SitterQuestionServiceTest.should_CreateRadioQuestion_Successfully()`

## Scenario 2: 選擇題缺少選項應被拒絕
* **Given**: 保母欲新增 `answerType=CHECKBOX` 的題目。
* **When**: 送出時 `options` 為空陣列。
* **Then**: 系統回傳 400，不建立該題目。
* **自動化對應**: `SitterQuestionServiceTest.should_RejectCreate_When_ChoiceTypeHasNoOptions()`

## Scenario 3: 題數達上限應被拒絕
* **Given**: 保母已建立 20 題（`MAX_QUESTIONS_PER_SITTER`）。
* **When**: 嘗試新增第 21 題。
* **Then**: 系統回傳 422 `QUESTION_LIMIT_EXCEEDED`。
* **自動化對應**: `SitterQuestionServiceTest.should_RejectCreate_When_ExceedsMaxQuestions()`

## Scenario 4: 拖拽排序後順序持久化
* **Given**: 保母已有 3 題，預設順序為 A、B、C。
* **When**: 呼叫排序 API，帶入新順序 `[C, A, B]`。
* **Then**: 重新查詢時，`sortOrder` 依序反映為 C=0, A=1, B=2。
* **自動化對應**: `SitterQuestionServiceTest.should_PersistSortOrder_AfterReorder()`

## Scenario 5: 停用題目不出現在預約流程清單
* **Given**: 保母有 2 題，其中 1 題被停用 (`isActive=false`)。
* **When**: 飼主端呼叫 `GET /api/sitters/{sitterId}/questions`（公開端點）。
* **Then**: 回傳清單僅包含啟用中的題目。
* **自動化對應**: `SitterQuestionServiceTest.should_ExcludeInactiveQuestions_FromBookingList()`

## Scenario 6: 刪除題目為邏輯刪除
* **Given**: 保母有 1 題。
* **When**: 呼叫刪除 API。
* **Then**: 該題 `is_deleted=true`，不再出現於任何查詢清單，但資料庫記錄仍保留。
* **自動化對應**: `SitterQuestionServiceTest.should_SoftDelete_Question()`

## Scenario 7: 非本人操作應被拒絕 (IDOR)
* **Given**: 保母 A 建立了一題問卷。
* **When**: 保母 B 嘗試編輯/刪除/停用該題目（直接帶入題目 ID）。
* **Then**: 系統回傳 403 `FORBIDDEN`。
* **自動化對應**: `SitterQuestionServiceTest.should_RejectOperation_When_NotOwner()`

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 新增/編輯選擇題 | 缺選項時拒絕 | Service 層驗證，不落地資料庫 |
| 2 | 新增第 21 題 | 拒絕，維持 20 題 | `COUNT(*)` 查詢卡控 |
| 3 | 刪除題目 | 邏輯刪除 | `is_deleted=true`，記錄不消失 |
| 4 | 跨帳號操作他人題目 | 403 | `sitter_id` 比對 |
| 5 | 飼主預約送出必填題未答 | 400，附題目名稱 | `BookingService.buildQuestionnaireAnswers()` |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [SitterQuestionServiceTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/SitterQuestionServiceTest.java)
- **E2E 對應**: [sitter-question-manager.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/sitter-question-manager.spec.ts), [public-booking-questionnaire.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/public-booking-questionnaire.spec.ts)
- **執行指令**: `mvn test -Dtest=SitterQuestionServiceTest`
- **最後驗證日期**: 2026-07-18
