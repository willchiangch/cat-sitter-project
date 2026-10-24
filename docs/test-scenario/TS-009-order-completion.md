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
| TS-009-09 | 手動結案前置條件（行程未完成 / 零行程） | 1. 訂單 `CONFIRMED` 且行程 `PENDING`，或訂單沒有任何行程<br>2. 飼主呼叫 `/complete`；飼主開訂單詳情 | 後端拋 `IllegalStateException`，訂單維持原狀態、`completed_at`/`payout_at` 為空；前端不顯示 `complete-order-btn`，顯示 `complete-order-hint`。行程全部 `DONE`/`CLOSED_BY_SYSTEM` 才顯示按鈕 |
| TS-009-10 | 手動結案狀態白名單 | 1. 訂單行程全部 `DONE`，訂單狀態為 `PENDING` / `PENDING_PAYMENT` / `PAID` / `MODIFYING` / `REFUND_VERIFY` / `DISPUTED` / `CANCELLED` / `REJECTED` / `COMPLETED`<br>2. 飼主呼叫 `/complete` | 一律 409 `STATE_CONFLICT`，訂單狀態不變；非 COMPLETED 者 `completed_at`/`payout_at` 仍為空；COMPLETED 重複呼叫 `completed_at`/`payout_at` 與呼叫前相同。只有 `CONFIRMED` / `IN_PROGRESS` 可結案 |
| TS-009-08 | 保母帳務總覽依月份查詢 | 1. 保母名下有跨越 2 個月份的已結案訂單<br>2. 呼叫 `GET /api/orders/sitter/ledger?month=2026-07` | 僅回傳 `completed_at` 落在 2026-07 的訂單，`totalRevenue` 為該月加總 |

## 4. 自動化追溯 (Automation Trace)
- **單元測試**: `CompletionService.java`
- **整合測試**: `CompletionServiceTest.java`（涵蓋 TS-009-01~06、TS-009-09 後端：`should_RejectManualComplete_When_OrderHasNoVisits` / `_When_VisitStillPending`、`should_ManualComplete_When_AllVisitsDone`；TS-009-10：`should_RejectManualComplete_When_StatusNotCompletable`（8 個狀態參數化）、`_When_Negotiating`、`should_RejectManualComplete_And_KeepPayoutDate_When_AlreadyCompleted`、`should_ManualComplete_When_ConfirmedAndAllVisitsFinished`，HTTP 層 `OrderControllerTest.should_Return409_And_KeepPayoutAt_When_CompletingCompletedOrder`；**TS-009-07/08 目前僅有 E2E 覆蓋，無對應後端單元測試，為已知待補項目**）
- **E2E 對應**: `dispute-and-completion.spec.ts`（TS-009-03 前端：行程全部 DONE 才結案、TS-009-04/06/07）、`order-visit-gating.spec.ts` N10（TS-009-09 前端：待執行 / 部分執行中 / 零行程不顯示按鈕，全部 DONE+CLOSED_BY_SYSTEM 顯示）、`sitter-ledger.spec.ts`（TS-009-08）
- **測試指令**: `mvn test -Dtest=CompletionServiceTest`

## 5. 技術校驗 (DB/NFR)
- **Cron Transaction**: 確保每筆訂單在結案時獨立 Commit，避免批次失敗。
- **Payout Punctuality**: 驗證 `payout_at` 必須嚴格等於 `completed_at + 3 days`。
- **Audit Compliance**: `order_logs` 必須完整記錄操作者、變動內容與時間。

---

## 本測試發現並修復的真實 bug

**飼主訂單詳情「申請訂單變更」「申報爭議」低對比（2026-10-24，驗收 N6 / backlog 第 6 批 G5）**：兩顆次要按鈕是 `.btn-primary` 只用 `backgroundColor` 改底色，蓋不掉 class 的漸層 `background-image`，結果是灰字 `#5a5c5b` 疊在藍色漸層上。改用 `background:` 簡寫（實色 `#e1e3e1`，對比度約 5.2:1）。同一寫法另有 `OrderDisputeModal` 取消鈕、`SitterOrders` 駁回表單取消鈕，一併修正。迴歸：`frontend/e2e/ui-layout-contrast.spec.ts` N6（`background-image: none`、對比度 ≥ 4.5:1）。

**服務還沒開始就顯示「確認結案」（2026-10-24，驗收 N10）**：`OwnerOrderDetail.tsx` 在 `CONFIRMED || IN_PROGRESS` 就顯示「確認結案 (完成預約)」，CONFIRMED 訂單的行程還是「待執行」也看得到；後端 `manualComplete` 會以「尚有未完成的行程」擋下，所以沒有放款風險，但按了只會跳「結案失敗」。改成另查行程清單，非空且全部 `DONE`/`CLOSED_BY_SYSTEM` 才顯示（PRD-009 A.1）。查證後端時另發現零行程邊界：`allMatch` 對空集合回 true，而預約 `dates: []`（總額 0 也過 Zero-Trust 校驗）或訂單變更把未執行行程全刪掉都會產生零行程訂單，可直接結案。補 `visits.isEmpty()` 拒絕。迴歸：`CompletionServiceTest.should_RejectManualComplete_When_OrderHasNoVisits`（拿掉檢查時紅）、`order-visit-gating.spec.ts` N10（舊前端 3 支紅）。

**手動結案沒有訂單狀態白名單（2026-10-25，fix-batch10 H2 / 第 9 批另外發現 #2）**：`manualComplete` 只擋 `DISPUTED`，只要行程全部 `DONE` 就能結案。已 `COMPLETED` 的訂單重複呼叫會把 `completed_at` / `payout_at` 重設為「現在」與「現在 + 3 天」，撥款日期被往後推；`MODIFYING` / `REFUND_VERIFY`（PRD-016 協商中）結案會繞過協商直接放款。改為只允許 `CONFIRMED` / `IN_PROGRESS`（與 `disputeOrder` 一致），其餘 409 並依狀態給訊息（SD-009 §1.1）。迴歸：TS-009-10 的 JUnit（舊版在 MODIFYING / REFUND_VERIFY / PENDING_PAYMENT / PAID / CANCELLED / REJECTED / PENDING 會被結案、COMPLETED 的 payoutAt 會變）。
