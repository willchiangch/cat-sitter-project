# TS-005: 飼主預約申請 (Public Booking)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-005 / SD-005 |
| **測試類型** | ✅ 功能測試 / ✅ 併發測試 |
| **優先級** | P0 (Critical) |

---

## Scenario 1: 高併發搶訂與配額鎖定
* **Given**: 保母 A 的方案「專業餵食」在 2026-06-01 僅剩 1 個名額。
* **When**: 飼主甲與飼主乙同時在 100ms 內送出該日期的預約申請（帶入不同 Idempotency-Key）。
* **Then**: 系統僅允許一筆訂單建立成功，另一筆回傳 `ORDER_SITTER_CAPACITY_FULL`。

## Scenario 2: 零信任金額校驗
* **Given**: 系統內方案單價已由 500 元調整為 600 元。
* **When**: 飼主的前端頁面尚未重整，帶入舊的試算總額 (`clientCalculatedTotal`=500) 提交預約。
* **Then**: 後端偵測到金額不符，回傳 400 `ORDER_PRICING_OUTDATED` 並拒絕建單。

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 併發送出預約 | 僅一勝，其餘敗 | **Advisory Lock**: 檢查 `pg_advisory_xact_lock` 成功攔截。 |
| 2 | 檢查成功訂單 | 狀態為 `PENDING` | DB `orders.idempotency_key` 唯一性校驗。 |
| 3 | 檢查審計日誌 | 紀錄建單詳細參數 | `order_logs` 紀錄 IP, UA 與初始 Items。 |
