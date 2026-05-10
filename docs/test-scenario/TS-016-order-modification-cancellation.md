# TS-016: 訂單雙向變更與退款 (Modification & Cancellation)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-016 / SD-016 |
| **測試類型** | ✅ 複雜狀態機測試 / ✅ 財務試算測試 |
| **優先級** | P0 (Critical) |

---

## Scenario 1: 雙向變更與快照重算 (補款路徑)
* **Given**: 訂單已支付，狀態 `CONFIRMED`。
* **When**: 飼主申請「增加兩天」，保母報價後，飼主點擊 `confirm` 同意支付差額。
* **Then**: 系統重新獲取 **Sorted Advisory Locks**。訂單狀態轉回 `PENDING_PAYMENT`。

## Scenario 2: 線下退款憑證核銷 (退款路徑)
* **Given**: 狀態為 `MODIFYING`。保母報價退款 200 元，飼主點擊 `confirm`。
* **When**: 訂單進入 `REFUND_VERIFY`。保母上傳 `gs://` 憑證，飼主點擊【確認收到款項】。
* **Then**: 訂單狀態回歸 `CONFIRMED`。

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 飼主點擊 Confirm | 執行實質變更與鎖定 | **Snapshot Recalc**: 確認按原合約價計算，非現行價。 |
| 2 | 保母上傳憑證 | 二次驗證通過 | **GCP Storage**: 校驗 `evidence_url` 格式與權限。 |
| 3 | 飼主最終確認 | 解除退款凍結 | **State Cleanup**: `waiting_for_owner_action` 轉為 `false`。 |
