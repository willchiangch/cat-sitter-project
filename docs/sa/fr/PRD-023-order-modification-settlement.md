# PRD-023: 訂單變更：飼主回應、補款、退款與結清

| 項目 | 內容 |
|------|------|
| 狀態 | In Review |
| 建立日期 | 2026-11-21 |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0） |
| 測試情境 | TS-023 |
| 相依 PRD | PRD-016（變更的發起與報價，本卡從 `QUOTED` 接手）；PRD-007（補款 / 退款 payment 的憑證上傳、確認、駁回、憑證格式與檢視）；PRD-024（款項爭議 O14、DISPUTED 凍結、協商期間結案限制）；PRD-008（行程開始、系統關閉）；PRD-009（`IN_PROGRESS` 訂單結案、帳務總覽）；PRD-020（ADMIN 調解） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.2（資源歸屬、收款帳號快照）；§3.1（主狀態、有效行程）；§3.2 O9、O10（CQ-41、CQ-56）、期限以時間判斷、取消的共同附帶動作（CQ-65）、DISPUTED 凍結；§3.3（payments：`direction`、`purpose`、`amount`、`due_at`、收款帳號快照、付款方 / 收款方、`SUBMITTED` 不逾期作廢）；§3.4（減少的行程、新增與新總額生效、整筆取消、補款、補款逾時重算 CQ-53、退款、逾時）；§3.6（鎖、稽核、通知、冪等、排程）；§3.7（錯誤碼）；§4（已收淨額、變更差額、保母收入）；§5（`MODIFICATION_TIMEOUT_HOURS`、`VISIT_ZOMBIE_HOURS`、排程頻率） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念（角色、SaaS 額度、訂單狀態、時間起算點）只能**引用** DOMAIN-BASELINE，不得在這裡重新定義；要改就改 baseline。
> 本卡由原 PRD-016 拆出（2026-11-21）。payment 的上傳 / 確認 / 駁回流程與憑證規則一律見 PRD-007；本卡只定義補款與退款 payment **何時建立、金額怎麼算、結果如何影響變更與訂單**。

---

## 1. 背景與目的

保母報價後（變更 `QUOTED`），由飼主決定同意或拒絕。同意後依差額分三條路：無差額立即生效、需補款等飼主付差額、需退款等保母退回差額。補款與退款都是獨立的 payment（BASELINE §3.3），首次付款紀錄不被覆寫。協商期間訂單主狀態不變（CQ-1）；變更結束後，`CONFIRMED` 訂單若已無有效行程就轉 `CANCELLED`（O10），`IN_PROGRESS` 訂單照常走結案。

## 2. 使用者故事

- **US-1** 身為飼主，我希望看到保母的新報價與應補 / 應退差額後再決定同意或拒絕，以便金額變動在我同意前不生效。
- **US-2** 身為飼主，我希望補款頁只顯示「本次應補差額」與期限，以便不會照新總額整筆重匯。
- **US-3** 身為保母，我希望飼主同意後要減少的行程立刻從排程消失，以便不會誤去服務已取消的日期。
- **US-4** 身為保母，我希望退款需要我上傳退款憑證、飼主確認後才算結清，以便雙方都有退款證據。
- **US-5** 身為飼主，我希望補款逾期時已取消的行程不會被重新收費，系統自動算出應退金額，以便不會因沒補款反而多付。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 同意 / 拒絕 `QUOTED` 變更 | 本人訂單 | ✗（403 `ROLE_MISMATCH`） | ✗ | ✗ | 無 |
| 上傳補款憑證 | 本人訂單（見 PRD-007） | ✗ | ✗ | ✗ | 無 |
| 確認 / 駁回補款 | ✗ | 本人訂單（見 PRD-007） | ✗ | ✗ | 無；訂閱鎖定不擋（PRD-024） |
| 上傳退款憑證 | ✗ | 本人訂單（見 PRD-007） | ✗ | ✗ | 無；訂閱鎖定不擋 |
| 確認收到退款 | 本人訂單（只能確認，見 PRD-007） | ✗ | ✗ | ✗ | 無 |
| 讀取補款收款帳號快照 | 該補款 payment 的付款方 | 本人 | ✗ | ✗ | 無 |
| 檢視變更、補款、退款 | 本人訂單 | 本人訂單 | 唯讀，限 BASELINE §1.2 可讀訂單 | ✗ | 無 |

- 訂單存在但不屬於呼叫者 → 403 `FORBIDDEN`；不存在 → 404 `NOT_FOUND`；token 角色不符 → 403 `ROLE_MISMATCH`（BASELINE §1.1、§1.2）。

## 4. 主流程

### 4.1 飼主同意

1. OWNER 在協商頁看到：新內容（減少 / 新增的行程）、新總額、已收淨額、差額（「應補款 NT$X」/「應退款 NT$X」/「無差額」）、`expires_at`（Asia/Taipei）。
2. 點「同意變更」，帶 `Idempotency-Key`。
3. 系統鎖訂單列並檢查：變更為 `QUOTED`、`now < expires_at`、訂單 `CONFIRMED` / `IN_PROGRESS`、所有減少目標仍為 `PENDING`。新增行程日期不再檢查（報價後跨日、日期已過也不擋，BR-12）。
4. 同一交易內：
   - 減少目標行程 `PENDING → CANCELLED`（CQ-32）；此取消不觸發 O9（CQ-56）。
   - 依 `差額 = 新總額 − 已收淨額`（已收淨額於此刻重算）分流：
     - `= 0` → 變更 `SETTLED`：建立新增行程（`PENDING`）、`orders.total_amount = 新總額`、記錄結束時間；接著做 4.5 結清後檢查。
     - `> 0` → 變更 `AWAITING_SUPPLEMENT`：建立 payment（`direction=CHARGE`、`purpose=SUPPLEMENT`、`method=OFFLINE_TRANSFER`、`amount=差額`、`due_at = now + MODIFICATION_TIMEOUT_HOURS`、快照保母收款帳號）`UNPAID`。新增行程與新總額尚未生效。同意當下的通知寫明補款金額與期限；`due_at` 前不另發補款提醒（BR-20）。
     - `< 0` → 變更 `AWAITING_REFUND`：建立 payment（`direction=REFUND`、`purpose=MODIFICATION_REFUND`、`method=OFFLINE_TRANSFER`、`amount=|差額|`、`due_at=null`）`UNPAID`。新增行程與新總額尚未生效。
   - 寫 `order_logs`；enqueue 通知（§9）。訂單主狀態不變。

### 4.2 飼主拒絕

1. OWNER 點「拒絕變更」→ 變更 `REJECTED`，通知 SITTER；行程、`total_amount`、訂單狀態不變。

### 4.3 補款

1. OWNER 在補款頁看到「本次應補差額 NT$X」、收款帳號快照、期限 `due_at`（Asia/Taipei），上傳憑證（見 PRD-007）→ payment `UNPAID → SUBMITTED`。
2. SITTER 核對（見 PRD-007）：
   - 確認 → payment `VERIFIED`，同一交易變更 `SETTLED`：建立新增行程（日期已過仍建立，交殭屍清理，CQ-49）、`orders.total_amount = 新總額`、記錄結束時間；通知雙方「補款已確認，變更已生效」；接著做 4.5。
   - 駁回 → payment `SUBMITTED → UNPAID`（見 PRD-007），`due_at` 不變。
3. 補款逾時（排程，`now ≥ payment.due_at` 且 payment `UNPAID`），同一交易：
   1. payment → `VOID`，變更 → `EXPIRED`，記錄結束時間；新增部分不生效；已取消的減少行程維持 `CANCELLED`。
   2. `orders.total_amount = max(0, 原 total_amount − Σ(已取消行程的快照單價))`，原報價的手動加減保留（CQ-53）。
   3. 若已收淨額 > 新 `total_amount`：在步驟 1 之後建立一筆變更（`initiatedBy=SYSTEM`、`type=CHANGE`、無減少 / 新增行程、新總額 = 重算後 `total_amount`、payload 帶來源變更 id、`status=AWAITING_REFUND`、不可撤回）與對應退款 payment（`purpose=MODIFICATION_REFUND`、`amount = 已收淨額 − 新 total_amount`、`UNPAID`）（BR-9）。
   4. 若不需退款：接著做 4.5。
   5. 寫 `order_logs`；通知雙方「補款逾期，新增行程未生效」；有系統退款時另通知 SITTER「請退還 NT$X」。
4. payment `SUBMITTED` 時不逾時，SITTER 仍可確認或駁回；自目前這筆 submission 上傳時間起超過 72h 未核對可申報款項爭議（BASELINE O14、CQ-64；PRD-024）。

### 4.4 退款

1. SITTER 退款後上傳退款憑證（見 PRD-007）→ payment `UNPAID → SUBMITTED`。退款要匯到哪由雙方自行溝通，系統不存飼主帳號（CQ-54）。
2. OWNER 點「確認收到退款」（見 PRD-007）→ payment `VERIFIED`，同一交易變更 `SETTLED`：
   - 一般變更：建立新增行程、`orders.total_amount = 新總額`；
   - `initiatedBy=SYSTEM` 的變更：`total_amount` 已於補款逾時時更新，不再變動，無新增行程；
   - 記錄結束時間；通知雙方「退款已確認，變更已完成」；接著做 4.5。
3. 退款無逾時；OWNER 不同意或任一方久候 → 申報款項爭議（PRD-024）。

### 4.5 變更結束後的訂單主狀態

1. 變更 `SETTLED`（或補款 `EXPIRED` 且不需系統退款）同一交易內檢查：
   - 訂單 `CONFIRMED` 且已無有效行程 → O10 `CONFIRMED → CANCELLED`，`cancelledBy=SYSTEM`、取消原因 `MODIFICATION`；`order_logs` payload 帶 `modificationId` 與該變更的 `initiatedBy`；同交易執行 BASELINE §3.2「取消的共同附帶動作」——剩餘 `PENDING` 行程轉 `CANCELLED`、未結束 payment 轉 `VOID`（CQ-65）；通知雙方「訂單已取消」。
   - 訂單 `CONFIRMED` 且仍有有效行程 → 維持 `CONFIRMED`。
   - 訂單 `IN_PROGRESS` → 維持 `IN_PROGRESS`，照常結案（見 PRD-009）；整筆取消後的保母收入 = 已收淨額（CQ-14）。
2. 保母收入一律 = `COMPLETED` 與 `CANCELLED` 訂單的已收淨額（含取消費）（BASELINE §4）。

## 5. 狀態轉移

> 只列本卡造成的轉移；完整狀態機見 BASELINE §3.2–§3.4。

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1 | 變更 `QUOTED` | `SETTLED` | OWNER（同意） | `now < expires_at`；訂單 `CONFIRMED` / `IN_PROGRESS`；減少目標皆 `PENDING`；差額 = 0 | 409 `DEADLINE_PASSED`（`deadline=MODIFICATION_EXPIRES_AT`）/ `MODIFICATION_STATE_CONFLICT` / `ORDER_STATE_CONFLICT` / `VISIT_ALREADY_STARTED` |
| T2 | 變更 `QUOTED` | `AWAITING_SUPPLEMENT` | OWNER（同意） | 同 T1；差額 > 0 | 同 T1 |
| T3 | 變更 `QUOTED` | `AWAITING_REFUND` | OWNER（同意） | 同 T1；差額 < 0 | 同 T1 |
| T4 | 變更 `QUOTED` | `REJECTED` | OWNER（拒絕） | `now < expires_at`；訂單非 `DISPUTED` | 409 `DEADLINE_PASSED` / `MODIFICATION_STATE_CONFLICT` / `ORDER_STATE_CONFLICT` |
| T5 | 行程 `PENDING` | `CANCELLED` | OWNER（隨 T1–T3） | 為減少目標 | 整筆拒絕（見 T1） |
| T6 | — | payment `SUPPLEMENT` `UNPAID` / `MODIFICATION_REFUND` `UNPAID` | SYSTEM（隨 T2 / T3 / T9） | — | — |
| T7 | 變更 `AWAITING_SUPPLEMENT` | `SETTLED` | SITTER（確認補款，PRD-007） | 補款 payment `SUBMITTED → VERIFIED` | 409 `PAYMENT_STATE_CONFLICT`（PRD-007） |
| T8 | 變更 `AWAITING_SUPPLEMENT` | `EXPIRED` | SYSTEM | 補款 payment `UNPAID` 且 `now ≥ due_at`；同交易 payment → `VOID`、`total_amount` 重算 | — |
| T9 | `[*]` | 變更 `AWAITING_REFUND`（`initiatedBy=SYSTEM`） | SYSTEM（隨 T8） | 已收淨額 > 重算後 `total_amount` | — |
| T10 | 變更 `AWAITING_REFUND` | `SETTLED` | OWNER（確認收到退款，PRD-007） | 退款 payment `SUBMITTED → VERIFIED` | 409 `PAYMENT_STATE_CONFLICT`（PRD-007） |
| T11 | 訂單 `CONFIRMED` | `CANCELLED`（O10，`cancelledBy=SYSTEM`、原因 `MODIFICATION`） | SYSTEM | 隨 T1 / T7 / T8（無系統退款）/ T10，結束後無有效行程；同交易剩餘 `PENDING` 行程 → `CANCELLED`、未結束 payment → `VOID`（BASELINE CQ-65） | — |

- `AWAITING_* → REJECTED`（ADMIN 調解）見 PRD-024 / PRD-020。
- 每次轉移寫 `order_logs` 並 enqueue 通知，與狀態變更同一交易（BASELINE §3.6）。

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 只有 OWNER 能對 `QUOTED` 同意或拒絕（不論誰發起） | BASELINE §3.4 狀態圖 |
| BR-2 | 同意時重驗：任一減少目標已非 `PENDING`（保母已開始、系統已關閉）→ 409 `VISIT_ALREADY_STARTED`，整筆不生效，變更維持 `QUOTED`；需拒絕 / 撤回後重新發起報價 | BASELINE §3.4、第 2 輪 #11 |
| BR-3 | 減少的行程於同意時立即 `CANCELLED`；新增行程與新總額於 `SETTLED` 生效 | BASELINE §3.4、CQ-32 |
| BR-4 | 因變更取消的行程不觸發 O9（`CONFIRMED` 不因此轉 `IN_PROGRESS`） | BASELINE O9、CQ-56 |
| BR-5 | `差額 = 新總額 − 已收淨額`；`已收淨額 = Σ CHARGE VERIFIED − Σ REFUND VERIFIED`；> 0 補款、< 0 退款、= 0 立即 `SETTLED` | BASELINE §3.4、§4 |
| BR-6 | 補款 payment：`CHARGE` / `SUPPLEMENT`，`amount` = 差額，`due_at = 同意時間 + MODIFICATION_TIMEOUT_HOURS`（例：2026-11-21 10:00 同意 → 2026-11-24 10:00 Asia/Taipei），建立時快照保母收款帳號 | BASELINE §3.3、§3.4、§5、CQ-23 |
| BR-7 | 補款頁與保母核對畫面只顯示本筆 `amount`（本次應補差額），不顯示新總額為應付金額 | BASELINE §3.3 `amount`；沿用原 PRD-016 C.3 |
| BR-8 | 首次付款與補款是不同 payment；補款不覆寫首次付款的憑證、submission 與確認時間 | BASELINE §3.3；沿用原 PRD-016 C.3 |
| BR-9 | 補款 `UNPAID` 逾時 → payment `VOID`、變更 `EXPIRED`；新增不生效；已取消行程維持取消；同交易 `total_amount = max(0, 原 total_amount − Σ 已取消行程快照單價)`，保留原報價手動加減；已收淨額 > 新 `total_amount` 時另建 `initiatedBy=SYSTEM`、`AWAITING_REFUND`、不可撤回的變更與退款 payment（`amount = 已收淨額 − 新 total_amount`），先結束舊變更再建新列。系統退款變更記為 `type=CHANGE`、無減少 / 新增行程、新總額 = 重算後 `total_amount`，payload 帶來源變更 id | BASELINE §3.4、CQ-53；使用者決定 2026-11-21（本卡 CQ-3） |
| BR-10 | 補款 `SUBMITTED` 不逾時；逾時判斷只看補款 payment `due_at` 且 `UNPAID`。`due_at` 後上傳 → 409 `DEADLINE_PASSED`（見 PRD-007）；`due_at` 後被駁回回到 `UNPAID` → 下一輪排程依 BR-9 逾時 | BASELINE §3.3、§3.4、CQ-42、CQ-46 |
| BR-11 | 退款 payment：`REFUND` / `MODIFICATION_REFUND`，`amount` = \|差額\|（≤ 已收淨額），`due_at=null`；SITTER 上傳憑證、OWNER 只能確認；無逾時；不存飼主帳號 | BASELINE §3.3、§3.4、CQ-30、CQ-54 |
| BR-12 | 新增行程日期只在報價時檢查（PRD-016 BR-13）；OWNER 同意時不再檢查，報價後跨日致新增日期已過也不擋。`SETTLED` 時新增行程以 `PENDING` 建立，日期已過仍建立，交殭屍清理（`VISIT_ZOMBIE_HOURS`，見 PRD-008） | BASELINE §3.4、CQ-49；使用者決定 2026-11-21（本卡 CQ-4） |
| BR-13 | 變更結束（`SETTLED`，或 `EXPIRED` 且未建系統退款）同交易：`CONFIRMED` 且無有效行程 → O10 `CANCELLED`（`cancelledBy=SYSTEM`、取消原因 `MODIFICATION`，`order_logs` payload 帶 `modificationId` 與該變更的 `initiatedBy`），並執行取消的共同附帶動作（剩餘 `PENDING` 行程 → `CANCELLED`、未結束 payment → `VOID`）；`IN_PROGRESS` 照常結案 | BASELINE O10、§3.2 取消的共同附帶動作（CQ-65）、§3.4 整筆取消、CQ-14、CQ-41；使用者決定 2026-11-21（本卡 CQ-1） |
| BR-14 | 保母收入 = `COMPLETED` 與 `CANCELLED` 訂單的已收淨額（含取消費），不讀 `total_amount` | BASELINE §4、CQ-14、CQ-41 |
| BR-15 | 變更結束時記錄結束時間（`SETTLED` / `EXPIRED` / `REJECTED`），供 `completion_basis_at` 使用（見 PRD-024） | BASELINE §5 `completion_basis_at` |
| BR-16 | 變更不影響月接單額度 | BASELINE §2.2、CQ-5 |
| BR-17 | 協商頁只呈現當下可執行動作：補款分支不出現退款區塊；退款分支在保母上傳退款憑證前，飼主看不到「確認收到退款」；結束後只顯示唯讀結果 | 沿用原 PRD-016 |
| BR-18 | 寫入端點帶 `Idempotency-Key`，同 key 回放；不同 key 重做已完成動作 → 409，不重複建立 payment | BASELINE §3.6；沿用原 PRD-016 |
| BR-19 | 金額整數 TWD；時間 UTC 儲存、Asia/Taipei 顯示 | BASELINE §4、§5 |
| BR-20 | 補款不另發到期前提醒：期限只在同意當下的通知與補款頁寫明（「請於 YYYY-MM-DD HH:mm 前完成」）；`due_at` 前不產生補款提醒通知 | 使用者決定 2026-11-21（本卡 CQ-2） |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 非該訂單當事人同意 / 拒絕 | 拒絕 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | SITTER 角色或 ADMIN 呼叫同意 / 拒絕 | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換為飼主身分後再操作」 |
| EX-3 | 方案不足 | 不適用：同意、拒絕、補款、退款不分方案 | — | — |
| EX-4 | 變更非 `QUOTED`（`REQUESTED`、`AWAITING_*`、終態） | 拒絕 | 409 `MODIFICATION_STATE_CONFLICT`（BASELINE §3.7） | 「此變更請求狀態已更新，請重新整理」 |
| EX-5 | 已過 `expires_at`、排程未跑 | 拒絕 | 409 `DEADLINE_PASSED`（`deadline=MODIFICATION_EXPIRES_AT`） | 「此變更請求已逾期」 |
| EX-6 | 減少目標已非 `PENDING` | 整筆拒絕，不取消任何行程、不建 payment | 409 `VISIT_ALREADY_STARTED`（payload 帶行程 id） | 「部分行程已開始，請重新協商」 |
| EX-7 | 訂單 `DISPUTED` | 拒絕 | 409 `ORDER_STATE_CONFLICT` | 「訂單爭議處理中」 |
| EX-8 | 跨角色併發：OWNER 同意 vs SITTER 開始同一減少目標行程；OWNER 同意 vs 發起者撤回 | 鎖訂單列序列化；後到者依新狀態重驗 | 後到者 409 `VISIT_ALREADY_STARTED` / `MODIFICATION_STATE_CONFLICT`，或 PRD-008 的行程狀態錯誤 | 同 EX-4 / EX-6 |
| EX-9 | 排程補款逾時 vs SITTER 確認補款同時 | 鎖序列化；排程鎖住後重驗 payment 仍 `UNPAID` 才逾時；payment 已 `SUBMITTED` / `VERIFIED` 則略過 | — | — |
| EX-10 | 鎖等待逾時 | 不改資料 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「訂單處理中，請稍後再試」 |
| EX-11 | 重複送出（同 key） | 回放，不重複取消行程、不重複建 payment | 同第一次 | 同第一次 |
| EX-12 | 換 key 再同意 | 拒絕 | 409 `MODIFICATION_STATE_CONFLICT` | 同 EX-4 |
| EX-13 | 補款 `due_at` 後上傳 | 拒絕（見 PRD-007） | 409 `DEADLINE_PASSED`（`deadline=PAYMENT_DUE_AT`） | 「補款期限已過」 |
| EX-14 | 退款 `UNPAID` 時飼主按確認 | 拒絕（見 PRD-007） | 409 `PAYMENT_STATE_CONFLICT` | 「保母尚未上傳退款憑證」 |
| EX-15 | 撤回系統建立的退款變更 | 拒絕 | 409 `MODIFICATION_STATE_CONFLICT` | 「此退款由系統建立，無法撤回」 |
| EX-16 | GCS 上傳憑證失敗 | 見 PRD-007（上傳先於交易，交易失敗刪檔）；變更狀態不變 | 見 PRD-007 | 見 PRD-007 |
| EX-17 | Resend 寄信失敗 | 轉移照常提交；站內通知已寫入；Email 排程重試最多 5 次 | 原請求 2xx | 站內通知正常出現 |
| EX-18 | 排程處理單筆失敗（補款逾時） | 該筆整筆回滾（payment、變更、`total_amount`、系統退款一起），下輪重跑 | — | — |

## 8. 驗收標準（AC）

> 共用前提（除非另寫）：快照單價 500；訂單 V1、V2、V3 各 500；時間皆 Asia/Taipei。

### 子模組 1：飼主同意與拒絕

- [ ] **AC-1.1 (無差額立即生效)**：Given `IN_PROGRESS` 訂單（V1 `DONE`、V2 / V3 `PENDING`），`total_amount` = 已收淨額 = 1500，`QUOTED` 變更減少 V3、新增 2026-11-28 一次、新總額 1500，When OWNER 同意，Then 200，變更 `SETTLED`；V3 `CANCELLED`；新增一個 2026-11-28 `PENDING` 行程；`total_amount` = 1500；無新 payment；訂單仍為 `IN_PROGRESS`；雙方收到「變更已生效」。對應：T1、T5、BR-3、BR-5
- [ ] **AC-1.2 (差額 > 0 建立補款)**：Given `CONFIRMED` 訂單已收淨額 1500，`QUOTED` 變更減少 V3、新增 2 次、新總額 2000，When OWNER 於 2026-11-21 10:00 同意，Then 變更 `AWAITING_SUPPLEMENT`；V3 立即 `CANCELLED`；新增行程尚未建立；`total_amount` 仍 1500；新 payment `direction=CHARGE`、`purpose=SUPPLEMENT`、`amount=500`、`due_at=2026-11-24T02:00:00Z`、`UNPAID`、含收款帳號快照；補款頁顯示「本次應補差額 NT$500」與「請於 2026-11-24 10:00 前完成」，不顯示 NT$2000 為應付金額；OWNER 於同意當下收到含 NT$500 與期限的通知，此後至 `due_at` 前不再產生補款提醒通知；訂單仍 `CONFIRMED`。對應：T2、T5、T6、BR-6、BR-7、BR-20
- [ ] **AC-1.3 (差額 < 0 建立退款)**：Given `CONFIRMED` 訂單已收淨額 1500，`QUOTED` 變更減少 V3、新總額 1000，When OWNER 同意，Then 變更 `AWAITING_REFUND`；V3 `CANCELLED`；新 payment `direction=REFUND`、`purpose=MODIFICATION_REFUND`、`amount=500`、`due_at=null`、`UNPAID`；SITTER 收到「請退還 NT$500 並上傳退款憑證」；飼主協商頁不出現「確認收到退款」。對應：T3、T6、BR-11、BR-17
- [ ] **AC-1.4 (減少目標已開始)**：Given `QUOTED` 變更要減少 V2、V3，V2 已由保母開始（`IN_PROGRESS`）或已被系統關閉（`CLOSED_BY_SYSTEM`），When OWNER 同意，Then 409 `VISIT_ALREADY_STARTED`（payload 含 V2）；V2、V3 狀態不變、無新 payment、變更仍 `QUOTED`。對應：BR-2、EX-6
- [ ] **AC-1.5 (同意與開始行程併發、O9 不觸發)**：Given `CONFIRMED` 訂單、`QUOTED` 變更減少 V1，When SITTER 開始 V1（PRD-008）與 OWNER 同意同時送出，Then 只有一個成功：先同意 → V1 `CANCELLED`、開始行程收到 PRD-008 的行程狀態錯誤、訂單仍 `CONFIRMED`（因變更取消不觸發 O9）；先開始 → V1 `IN_PROGRESS`、訂單 `IN_PROGRESS`、同意收到 409 `VISIT_ALREADY_STARTED`。對應：BR-4、EX-8
- [ ] **AC-1.6 (飼主拒絕)**：Given `QUOTED` 變更（任一方發起），When OWNER 點「拒絕變更」，Then 200，變更 `REJECTED`；行程、`total_amount`、payment、訂單狀態皆不變；SITTER 收到「飼主已拒絕變更」。對應：T4
- [ ] **AC-1.7 (越權、狀態與期限)**：Given `QUOTED` 變更，When 其他 OWNER 同意 → 403 `FORBIDDEN`；帳號以 SITTER 角色呼叫 → 403 `ROLE_MISMATCH`；Given 變更 `REQUESTED` → 409 `MODIFICATION_STATE_CONFLICT`；Given 已過 `expires_at` 仍 `QUOTED` → 409 `DEADLINE_PASSED`；以上資料皆不變。對應：EX-1、EX-2、EX-4、EX-5
- [ ] **AC-1.8 (同意冪等)**：Given OWNER 以 key K2 同意成功（建立補款 payment P1），When 同 key 重送，Then 回放相同狀態碼與 body，payment 仍只有 P1；When 換 key 再同意，Then 409 `MODIFICATION_STATE_CONFLICT`、無新 payment。對應：BR-18、EX-11、EX-12

### 子模組 2：補款

- [ ] **AC-2.1 (補款確認即生效)**：Given AC-1.2 的 `AWAITING_SUPPLEMENT`，補款 payment `SUBMITTED`，When SITTER 確認收款（PRD-007），Then 同一交易：payment `VERIFIED`、變更 `SETTLED`、兩個新增行程以 `PENDING` 建立、`total_amount` = 2000、已收淨額 = 2000；訂單仍 `CONFIRMED`；雙方收到「補款已確認，變更已生效」（不使用「訂單啟動成功」文案）；保母當月接單計數不變。對應：T7、BR-3、BR-16
- [ ] **AC-2.2 (首次付款不被覆寫)**：Given AC-2.1 完成，When 查詢訂單 payments，Then 首次收款 payment（`purpose=INITIAL`）的 submission、確認時間與 AC-1.2 前完全相同；補款 payment 有自己的 submission 與確認時間。對應：BR-8
- [ ] **AC-2.3 (新增日期已過不擋同意、仍建立)**：Given 2026-11-30 報價新增 2026-12-01 一次（差額 > 0），When OWNER 於 2026-12-02 09:00 同意（報價後跨日、新增日期已過、仍在 `expires_at` 前），Then 200、變更 `AWAITING_SUPPLEMENT`，不回 422 `INVALID_VISIT_DATE`；When SITTER 於 2026-12-02 15:00 確認補款，Then 2026-12-01 行程以 `PENDING` 建立、不拒絕確認；之後由殭屍清理依 `VISIT_ZOMBIE_HOURS` 處理（見 PRD-008）。對應：T2、T7、BR-12
- [ ] **AC-2.4 (補款逾時重算並建系統退款)**：Given PRO 訂單 base 1500 + 加價 200，`total_amount` = 已收淨額 = 1700；變更減少 V3、新增 2 次、新總額 2200，2026-11-21 10:00 同意（V3 已取消，補款 500 `due_at` = 2026-11-24 10:00），When 補款仍 `UNPAID` 且排程於 `due_at` 後執行（≤ 15 分鐘），Then 同一交易：補款 payment `VOID`、變更 `EXPIRED`、新增行程未建立、V3 仍 `CANCELLED`、`total_amount` = 1700 − 500 = 1200；另建變更 `initiatedBy=SYSTEM`、`type=CHANGE`、無減少 / 新增、新總額 = 1200、payload 含原變更 id、`AWAITING_REFUND`，與退款 payment `amount=500`；雙方收到「補款逾期，新增行程未生效」，SITTER 另收到「請退還 NT$500」。對應：T8、T9、BR-9
- [ ] **AC-2.5 (純新增逾時不退款)**：Given 變更只新增 1 次（無減少）、新總額 2000、補款 500 `UNPAID`，When 逾時，Then 補款 `VOID`、變更 `EXPIRED`、`total_amount` 仍 1500、不建立系統退款變更；之後任一方可發起新變更（201）。對應：T8、BR-9
- [ ] **AC-2.6 (重算下限 0)**：Given PRO 訂單 base 1500 − 折扣 1000，`total_amount` = 已收淨額 = 500；變更減少 V1~V3、新增 2 次、新總額 1000，同意後 V1~V3 `CANCELLED`、補款 500，When 補款逾時，Then `total_amount` = max(0, 500 − 1500) = 0，系統退款 payment `amount=500`。對應：BR-9
- [ ] **AC-2.7 (SUBMITTED 不逾時；駁回與逾期上傳)**：Given 補款 2026-11-24 09:00 上傳（`SUBMITTED`），`due_at` = 2026-11-24 10:00，When 排程於 10:00 後執行，Then payment 仍 `SUBMITTED`、變更仍 `AWAITING_SUPPLEMENT`；When SITTER 於 11-25 確認 → 依 AC-2.1 `SETTLED`。When SITTER 改為於 11-25 駁回 → payment `UNPAID`，下一輪排程依 AC-2.4 逾時；駁回後 OWNER 重新上傳 → 409 `DEADLINE_PASSED`（`deadline=PAYMENT_DUE_AT`）。對應：BR-10、EX-9、EX-13

### 子模組 3：退款與變更結束後的訂單

- [ ] **AC-3.1 (退款確認即結清)**：Given AC-1.3 的 `AWAITING_REFUND`，SITTER 已上傳退款憑證（payment `SUBMITTED`，見 PRD-007），When OWNER 點「確認收到退款」，Then 同一交易：payment `VERIFIED`、變更 `SETTLED`、`total_amount` = 1000、已收淨額 = 1000；訂單仍 `CONFIRMED`（V1、V2 仍為有效行程）；雙方收到「退款已確認，變更已完成」。對應：T10、BR-11、BR-13
- [ ] **AC-3.2 (退款前不可確認、退款無逾時)**：Given 退款 payment `UNPAID`，When OWNER 呼叫確認，Then 409 `PAYMENT_STATE_CONFLICT`、協商頁沒有「確認收到退款」按鈕；Given 退款 `UNPAID` 或 `SUBMITTED` 已超過 30 天，When 排程執行，Then 變更仍 `AWAITING_REFUND`、payment 狀態不變。對應：BR-11、BR-17、EX-14
- [ ] **AC-3.3 (CONFIRMED 整筆取消結清 → O10)**：Given `CONFIRMED` 訂單已收淨額 1500，PRO 保母對 `CANCEL_ALL` 報新總額 300（取消費，原因必填），OWNER 同意 → V1~V3 `CANCELLED`、退款 1200，When OWNER 確認收到退款，Then 同一交易：變更 `SETTLED`、訂單 `CONFIRMED → CANCELLED`、`cancelledBy=SYSTEM`、取消原因 `MODIFICATION`；該轉移的 `order_logs` payload 含 `modificationId` 與 `initiatedBy`；訂單無 `UNPAID` / `SUBMITTED` payment 殘留；雙方收到「訂單已取消」；保母帳務總覽此訂單收入 = 300。對應：T10、T11、BR-13、BR-14
- [ ] **AC-3.4 (無差額的整筆取消立即 O10)**：Given `CONFIRMED` 訂單已收淨額 1500，PRO 保母對 `CANCEL_ALL` 報新總額 1500（系統計算值 0 + 加價 1500，原因「不退款」），When OWNER 同意，Then 同一交易：V1~V3 `CANCELLED`、變更 `SETTLED`、訂單 `CANCELLED`（`cancelledBy=SYSTEM`）、無 payment 建立；保母收入 = 1500。對應：T1、T11、BR-13
- [ ] **AC-3.5 (IN_PROGRESS 整筆取消照常結案)**：Given `IN_PROGRESS` 訂單（V1 `DONE`、V2 `CLOSED_BY_SYSTEM`、V3 `PENDING`），`CANCEL_ALL` 新總額 1000、退款 500，When 退款確認，Then 變更 `SETTLED`、V3 `CANCELLED`、訂單仍 `IN_PROGRESS`（不轉 `CANCELLED`），之後依 PRD-009 結案；結案後保母收入 = 已收淨額 1000。對應：BR-13、BR-14
- [ ] **AC-3.6 (系統退款變更)**：Given AC-2.6 產生的 `initiatedBy=SYSTEM` 退款變更（`CONFIRMED` 訂單，已無有效行程），When OWNER 或 SITTER 呼叫撤回，Then 409 `MODIFICATION_STATE_CONFLICT`；When SITTER 上傳退款、OWNER 確認，Then 變更 `SETTLED`、`total_amount` 維持 0（不再變動）、訂單 `CONFIRMED → CANCELLED`（O10）、保母收入 = 0。對應：T9、T10、T11、BR-9、EX-15

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | `modification_requests`：狀態、結束時間、`initiatedBy=SYSTEM` 列、關聯的 payment；`payments`：`SUPPLEMENT` / `MODIFICATION_REFUND` 列（欄位定義見 BASELINE §3.3、PRD-007）；`visits`：減少目標 `CANCELLED`、新增行程 `PENDING`（SD-FULFILL 擁有，經 SD-ORDER 鎖入口，BASELINE §3.6）；`orders.total_amount`（`SETTLED` 或補款逾時重算時更新）、`orders.status`（O10）；`order_logs` |
| 畫面 | 協商頁（同意 / 拒絕、差額說明）；補款頁（本次應補差額、收款帳號快照、期限）；退款區塊（保母上傳、飼主確認）；保母帳務總覽（收入 = 已收淨額，見 PRD-009） |
| 通知 | 同意（三分支）→ SITTER，補款分支另通知 OWNER 金額與期限（`due_at` 前不另發提醒，BR-20）；拒絕 → SITTER；補款確認 → 雙方「補款已確認，變更已生效」；補款逾時 → 雙方，系統退款 → SITTER；退款確認 → 雙方；O10 → 雙方。管道：站內 + Email（outbox，BASELINE §3.6；範本見 PRD-014） |
| 排程 | 有：補款逾時（每 15 分鐘，`payments.due_at`、`purpose=SUPPLEMENT`、`UNPAID`，`now ≥ due_at`） |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | O10 只寫 `cancelledBy=SYSTEM`，沒定義取消原因；歷史頁要怎麼說明「因變更取消」 | `cancelledBy=SYSTEM`、取消原因 `MODIFICATION`，`order_logs` payload 帶 `modificationId` 與該變更的 `initiatedBy`。已寫入 §4.5、T11、BR-13、AC-3.3 | 已決（使用者 2026-11-21） |
| CQ-2 | 補款是否在 `due_at` 前提醒（首次付款有提醒，BASELINE 沒寫補款） | 不另發提醒；同意當下的通知與補款頁寫明期限。已寫入 §4.1、BR-20、§9、AC-1.2 | 已決（使用者 2026-11-21） |
| CQ-3 | `initiatedBy=SYSTEM` 的退款變更 `type` 與內容怎麼記（BASELINE 類型只有 `CHANGE` / `CANCEL_ALL`） | `type=CHANGE`、無減少 / 新增、新總額 = 重算後 `total_amount`，payload 帶來源變更 id。已寫入 §4.3-3、BR-9、AC-2.4 | 已決（使用者 2026-11-21） |
| CQ-4 | 同意時發現 `QUOTED` 的新增行程日期已過（報價後跨日），要不要擋 | 不擋（CQ-49 只在報價時檢查），`SETTLED` 時照建、交殭屍清理。已寫入 §4.1-3、BR-12、AC-2.3 | 已決（使用者 2026-11-21） |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-21 | 建立：由原 PRD-016 拆出飼主同意 / 拒絕、補款、退款、結清後主狀態 | 原 PRD-016 依 DOMAIN-BASELINE 重寫並拆卡（子模組 > 3）；處理 `docs/reviews/00-DOMAIN-BASELINE.prd.review.md` 第 1 輪 #45–#49、#51 中屬於結清的部分 |
| 2026-11-21 | 第 1 輪 #45：結清後一律斷言訂單狀態「仍為」原值（AC-1.1、AC-2.1、AC-3.1、AC-3.5），無「恢復」 | 第 1 輪 #45、CQ-1 |
| 2026-11-21 | 第 1 輪 #46：補款 = 變更 `AWAITING_SUPPLEMENT` + `SUPPLEMENT` payment，憑證在 payment（子模組 2）；補款逾時依 CQ-53 重算 | 第 1 輪 #46、CQ-42、CQ-53 |
| 2026-11-21 | 第 1 輪 #47：退款 = 變更 `AWAITING_REFUND` + `MODIFICATION_REFUND` payment，保母上傳、飼主確認（子模組 3） | 第 1 輪 #47、CQ-30、CQ-54 |
| 2026-11-21 | 第 1 輪 #48：整筆取消分 `CONFIRMED`（O10，AC-3.3、AC-3.4、AC-3.6）與 `IN_PROGRESS`（照常結案，AC-3.5） | 第 1 輪 #48、CQ-14、CQ-41 |
| 2026-11-21 | 第 1 輪 #49：減少於同意時取消、新增與新總額於 `SETTLED` 生效（BR-3） | 第 1 輪 #49、CQ-32 |
| 2026-11-21 | 第 1 輪 #51：差額以已收淨額計算（BR-5） | 第 1 輪 #51、BASELINE §4 |
| 2026-11-21 | 待釐清 CQ-1~4 全部依建議預設值定案並寫入正文：O10 取消原因 `MODIFICATION` 與 `order_logs` payload（§4.5、T11、BR-13、AC-3.3）；補款不另發提醒（新增 BR-20、AC-1.2）；系統退款變更 `type=CHANGE` 與內容（§4.3、BR-9、AC-2.4）；同意時不檢查新增日期（§4.1、BR-12、AC-2.3）。另引用 BASELINE CQ-65 取消的共同附帶動作（§4.5、T11、BR-13）；`MODIFICATION_STATE_CONFLICT` 改標 BASELINE §3.7 錯誤碼（EX-4）；AC 總數不變 | 使用者決定 2026-11-21；DOMAIN-BASELINE 第 2 版修訂（CQ-65、§3.7） |
