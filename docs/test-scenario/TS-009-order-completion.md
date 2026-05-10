# TS-009: 訂單結案與爭議 (Order Completion)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-009 / PRD-020 / SD-009 |
| **測試類型** | ✅ 自動化測試 / ✅ 審計測試 |
| **優先級** | P0 (Critical) |

---

## Scenario 1: 自動結案與殭屍行程清理
* **Given**: 訂單中最後一筆正常完成的行程已過 48 小時，且其中一筆未打卡的 PENDING 行程，其 scheduled_at 已超過 72 小時。
* **When**: CronJob 執行。
* **Then**: 系統自動將該行程標記為 `CLOSED_BY_SYSTEM`，並將訂單轉為 `COMPLETED`。

## Scenario 2: 管理員爭議裁決
* **Given**: 訂單狀態為 `DISPUTED`。
* **When**: 管理員輸入二次驗證密碼，調整最終總額並上傳 `gs://` 憑證結案。
* **Then**: 系統產生 `LEDGER_ENTRY` 紀錄差額，訂單轉為 `COMPLETED`，寫入 `payout_at`。

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 排程執行 | 狀態自動跳轉 | **Cron Transaction**: 確保每筆訂單獨立 Commit。 |
| 2 | 管理員結案 | 財務紀錄產生 | **Payout_at**: 線上支付需為 `completed_at + 3 days`。 |
| 3 | 檢查審計日誌 | 紀錄二次驗證與差額 | `order_logs` 紀錄狀態變遷 (`DISPUTED` -> `COMPLETED`)。 |
