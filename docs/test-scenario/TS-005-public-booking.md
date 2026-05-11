# TS-005: 飼主預約申請 (Public Booking)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-005 / SD-005 |
| **測試類型** | ✅ 功能測試 / ✅ 併發測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | 🟢 已實作 (2/2 Scenarios) |

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
