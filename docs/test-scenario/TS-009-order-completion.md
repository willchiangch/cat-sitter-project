# TS-009: 訂單結案與爭議 (Order Completion)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-009 / PRD-020 / SD-009 |
| **測試類型** | ✅ 自動化測試 / ✅ 審計測試 |
| **優先級** | P0 (Critical) |

---

## 3. 測試場景與驗證

| 編號 | 標題 | 測試步驟 | 預期結果 |
| :--- | :--- | :--- | :--- |
| TS-009-01 | 殭屍行程自動清理 | 1. 建立一個 72 小時前 PENDING 的行程<br>2. 執行自動結案排程 | 該行程狀態轉為 `CLOSED_BY_SYSTEM` |
| TS-009-02 | 無異議自動結案 | 1. 訂單所有行程已結束且最後行程過 48 小時<br>2. 執行自動結案排程 | 訂單轉為 `COMPLETED`，計算 `payout_at` |
| TS-009-03 | 飼主手動結案 | 1. 飼主對已結束行程的訂單呼叫 `/complete` | 訂單轉為 `COMPLETED`，進入財務撥款隊列 |
| TS-009-04 | 管理員爭議裁決 | 1. 訂單狀態為 `DISPUTED`<br>2. 管理員呼叫 `/resolve` 並調整金額 | 狀態轉為 `COMPLETED`，金額變更，寫入審計日誌 |
| TS-009-05 | 結案權限檢查 | 1. 使用 A 飼主帳號嘗試結案 B 飼主的訂單 | 回傳 403 Forbidden 或 400 Bad Request |
| TS-009-06 | 管理員二次驗證密碼錯誤 | 1. 訂單狀態為 `DISPUTED`<br>2. 管理員呼叫 `/admin-resolve` 但 `adminPassword` 錯誤 | 回傳 **403**（非 401，避免前端 axios 攔截器誤判為 session 過期並觸發靜默重試），訂單狀態不變 |
| TS-009-07 | 爭議申報狀態機防呆 | 1. 訂單狀態為 `PENDING`（尚未進行中）或已是 `DISPUTED`<br>2. 飼主呼叫 `/dispute` | 回傳錯誤，不允許重複或非法狀態下申報爭議；`OwnerOrderDetail.tsx` 的「申報爭議」按鈕在此狀態下亦不應顯示 |
| TS-009-08 | 保母帳務總覽依月份查詢 | 1. 保母名下有跨越 2 個月份的已結案訂單<br>2. 呼叫 `GET /api/orders/sitter/ledger?month=2026-07` | 僅回傳 `completed_at` 落在 2026-07 的訂單，`totalRevenue` 為該月加總 |

## 4. 自動化追溯 (Automation Trace)
- **單元測試**: `CompletionService.java`
- **整合測試**: `CompletionServiceTest.java`（涵蓋 TS-009-01~06；**TS-009-07/08 目前僅有 E2E 覆蓋，無對應後端單元測試，為已知待補項目**）
- **E2E 對應**: `dispute-and-completion.spec.ts`（TS-009-04/06/07）、`sitter-ledger.spec.ts`（TS-009-08）
- **測試指令**: `mvn test -Dtest=CompletionServiceTest`

## 5. 技術校驗 (DB/NFR)
- **Cron Transaction**: 確保每筆訂單在結案時獨立 Commit，避免批次失敗。
- **Payout Punctuality**: 驗證 `payout_at` 必須嚴格等於 `completed_at + 3 days`。
- **Audit Compliance**: `order_logs` 必須完整記錄操作者、變動內容與時間。
