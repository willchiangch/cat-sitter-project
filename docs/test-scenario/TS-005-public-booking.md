# TS-005: 飼主預約申請 (Public Booking)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-005 / SD-005 |
| **測試類型** | ✅ 功能測試 / ✅ 併發測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | 🟡 部分實作 (4/5 Scenarios，Scenario 4 待補) |

---

## 0. 前置條件 (Prerequisites)
- **認證狀態**：所有參與測試之使用者（Owner/Sitter）皆須已登入。
- **認證 Header**：API 請求須包含 `Authorization: Bearer <Token>`。
- **基礎資料**：保母已建立至少一個服務方案。

## Scenario 1: 多人同時送單 (媒合式模型)
* **Given**: 保母 A 的方案「專業餵食」在 2026-06-01 僅剩 1 個名額。
* **When**: 飼主甲與飼主乙同時送出該日期的預約申請（帶入不同 Idempotency-Key）。
* **Then**: 系統應允許兩張訂單皆成功建立，狀態皆為 `PENDING`。
* **自動化對應**: `BookingServiceTest.ts005_01_should_AllowMultiplePendingOrders_When_ConcurrentSubmission()`

## Scenario 2: 保母併發接單 (配額鎖定)
* **Given**: 存在兩筆對應同一日期、同一保母的 `PENDING` 訂單。
* **When**: 保母嘗試同時確認這兩筆訂單。
* **Then**: 系統應利用 Advisory Lock 確保僅有一筆能成功變更為 `PENDING_PAYMENT`，另一筆應拋出 `CapacityFullException`。
* **自動化對應**: `BookingServiceTest.ts005_02_should_PreventOverselling_When_SitterConcurrentConfirm()`

## Scenario 3: 複合方案預約驗證 (Multi-Plan / Multi-Visit)
* **Given**: 保母有方案 A ($500) 與方案 B ($800)。
* **When**: 飼主送出預約申請，包含 Item 1 (方案 A, 1/1-1/2, 每日 2 次) 與 Item 2 (方案 B, 1/3, 每日 1 次)。
* **Then**: 總額應正確計算為 `(500 * 2 * 2) + (800 * 1 * 1) = 2800`，且資料庫應產生 5 筆對應的 `Visit` 記錄（每筆皆帶有正確的 `plan_id` 與日期）。
* **自動化對應**: `BookingServiceTest.ts005_03_should_CalculateTotalCorrectly_When_MultiPlanSubmitted()`

## Scenario 4: Zero-Trust Pricing — 前端試算金額與資料庫現價不符應拒絕
* **Given**: 方案現價為 $500。
* **When**: 飼主送出的 `expectedTotalAmount` 與後端依資料庫現價重算的金額不一致（例如前端頁面快取了舊報價，保母已調整方案價格）。
* **Then**: 系統回傳 400 `PRICING_MISMATCH`，不建立訂單。
* **自動化狀態**: ⬜ 未自動化。目前 `BookingServiceTest` 僅涵蓋金額計算「正確」路徑（Scenario 3），未有測試驗證金額「不符時拒絕」的分支，為已知待補項目。

## Scenario 5: 事前問卷必填題未作答應擋下送單 (PRD-004 AC-4)
* **Given**: 保母設有 1 題必填問卷題目。
* **When**: 飼主送出預約申請，未包含該題的作答值。
* **Then**: 系統拒絕送單，錯誤訊息附題目名稱，不建立訂單。
* **自動化對應**: `BookingServiceTest.should_RejectBooking_When_RequiredQuestionUnanswered()`

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 併發送出預約 | 多筆 PENDING 成功 | 檢查 `orders` 記錄是否存在多筆且 ID 不同。 |
| 2 | 併發確認接單 | 僅一勝，其餘敗 | **Advisory Lock**: 檢查接單時是否成功攔截。 |
| 3 | 檢查狀態變更 | 成功者轉為 `PENDING_PAYMENT` | 狀態機轉換正確性驗證。 |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [BookingServiceTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/BookingServiceTest.java)
- **執行指令**: `mvn test -Dtest=BookingServiceTest`
- **最後驗證日期**: 2026-05-11
