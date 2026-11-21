# PRD-016: 訂單變更：發起、報價與協商期限

| 項目 | 內容 |
|------|------|
| 狀態 | In Review |
| 建立日期 | 2026-05-09（2026-11-21 依 DOMAIN-BASELINE 重寫） |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0） |
| 測試情境 | TS-016 |
| 相依 PRD | PRD-023（飼主同意 / 拒絕、補款、退款與結清，接續本卡的 `QUOTED`）；PRD-024（款項爭議、協商期間對結案的限制、DISPUTED 凍結、訂閱鎖定不擋變更）；PRD-006（報價時的單價快照與 surcharge / discount 欄位）；PRD-003（方案可預訂日期區間）；PRD-008（行程開始 / 系統關閉，影響「可減少的行程」）；PRD-014（通知管道） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1、§1.2（角色、資源歸屬）；§2.1、§2.2（CQ-22 變更自訂新總額、方案即時查詢、快照、訂閱鎖定）；§3.1（主狀態、有效行程）；§3.2（`CONFIRMED` 後只能透過變更改內容、期限以時間判斷、DISPUTED 凍結、鎖定期間的變更計價 CQ-66）；§3.4（變更類型、可發起時機、報價、計價範圍、新增行程日期、差額、撤回、逾時、訂閱鎖定）；§3.6（鎖、稽核、通知 outbox、冪等、排程）；§3.7（錯誤碼，含 `MODIFICATION_STATE_CONFLICT`）；§4（已收淨額、變更差額）；§5（`MODIFICATION_TIMEOUT_HOURS`、時區、排程頻率） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念（角色、SaaS 額度、訂單狀態、時間起算點）只能**引用** DOMAIN-BASELINE，不得在這裡重新定義；要改就改 baseline。

> 原 PRD-016 的範圍拆成三張（皆歸屬 SD-ORDER）：
> - **PRD-016（本卡）**：變更請求從建立到「等飼主決定」為止——發起、保母報價 / 拒絕、撤回、`REQUESTED` / `QUOTED` 逾時。
> - **PRD-023**：飼主同意 / 拒絕、減少行程立即取消、補款與補款逾時重算、退款、結清後的主狀態（O10）。
> - **PRD-024**：款項爭議（O14）、DISPUTED 凍結、協商期間的結案限制、訂閱鎖定不擋變更。

---

## 1. 背景與目的

訂單 `CONFIRMED` 之後，訂單內容與金額只能透過變更協商改變（BASELINE §3.2，CQ-7）。飼主可能提早回國或行程取消、保母可能臨時無法服務，雙方都需要一個留下紀錄的協商流程：誰提出、改了哪些行程、新總額怎麼算、對方有沒有回應。本卡定義變更請求的建立與報價階段；變更協商期間訂單主狀態**不變**（CQ-1），變更是獨立實體 `modification_requests`（BASELINE §3.4）。

## 2. 使用者故事

- **US-1** 身為飼主，我希望在付款成立後能提出減少 / 增加行程或整筆取消，以便計畫改變時不必私下與保母協調金額。
- **US-2** 身為保母，我希望在無法提供完整服務時能主動提出變更並同時報價，以便飼主直接決定是否接受。
- **US-3** 身為保母，我希望看到飼主的變更請求後，依訂單成交時的快照單價得到新總額（專業版以上可再加減），或直接拒絕，以便金額有依據、不被迫接受。
- **US-4** 身為發起者，我希望在對方回應前撤回請求；對方 72 小時不回應時請求自動失效，以便訂單不會卡在協商中。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 發起變更（`REQUESTED`） | 本人訂單 | ✗（403 `ROLE_MISMATCH`） | ✗ | ✗ | 無 |
| 發起變更並同時報價（`QUOTED`） | ✗ | 本人訂單 | ✗ | ✗ | 計價方式依方案（BR-9、BR-10）；訂閱鎖定不擋，但鎖定期間依 FREE 規則計價（BR-17、BR-18） |
| 對 `REQUESTED` 報價 | ✗ | 本人訂單 | ✗ | ✗ | 同上 |
| 拒絕 `REQUESTED` | ✗ | 本人訂單 | ✗ | ✗ | 無；訂閱鎖定不擋 |
| 拒絕或改價 OWNER 發起的 `QUOTED` | ✗ | ✗（409 `MODIFICATION_STATE_CONFLICT`，BR-8） | ✗ | ✗ | — |
| 撤回 | 本人發起的請求 | 本人發起的請求 | ✗ | ✗ | 無 |
| 檢視變更請求與歷史 | 本人訂單 | 本人訂單 | 唯讀，限 BASELINE §1.2 可讀訂單（`DISPUTED`、有逾期待辦） | ✗ | 無 |

- 「本人訂單」以 token 身分判斷：訂單存在但不屬於呼叫者 → 403 `FORBIDDEN`；不存在 → 404 `NOT_FOUND`（BASELINE §1.2）。
- token 的 `currentRole` 必須是端點要求的角色，否則 403 `ROLE_MISMATCH`，即使該帳號是此訂單另一方（BASELINE §1.1）。ADMIN 呼叫本卡寫入端點一律 403 `ROLE_MISMATCH`。
- 撤回限發起者：訂單當事人但非發起者 → 403 `FORBIDDEN`。`initiatedBy=SYSTEM` 的變更無人可撤回（見 PRD-023）。

## 4. 主流程

### 4.1 飼主發起

1. OWNER 在訂單詳情點「申請變更」，選擇類型：
   - `CHANGE`：勾選要減少的行程（只能選 `PENDING` 行程）及 / 或新增行程（日期 + 方案 + 數量）；
   - `CANCEL_ALL`：整筆取消，內容 = 取消所有 `PENDING` 行程（BASELINE §3.4）。
2. 填寫原因（必填）後送出，帶 `Idempotency-Key`。
3. 系統鎖訂單列（BASELINE §3.6）並檢查：訂單為 `CONFIRMED` / `IN_PROGRESS`；無未結束變更；要減少的行程皆為本訂單的 `PENDING` 行程；內容非空（BR-24）；新增行程的方案在訂單快照內（BR-25）、日期 ≥ 明天且在方案可預訂區間（BR-13、BR-14，發起時即檢查）。
4. 建立變更請求：`status=REQUESTED`、`initiatedBy=OWNER`、`expires_at = now + MODIFICATION_TIMEOUT_HOURS`；寫 `order_logs`；同交易 enqueue 通知 SITTER。訂單主狀態、行程、`total_amount` 都不變。

### 4.2 保母發起（同時報價）

1. SITTER 在訂單詳情點「提出變更」，選類型與內容（同 4.1-1），填原因，系統依方案算出新總額（4.3），PRO / ULTIMATE 且未鎖定時可另加減並附原因。
2. 系統檢查同 4.1-3，另檢查計價規則（BR-9~BR-11、BR-18）。
3. 建立變更請求：`status=QUOTED`、`initiatedBy=SITTER`、`expires_at = now + MODIFICATION_TIMEOUT_HOURS`；通知 OWNER 進行同意 / 拒絕（PRD-023）。

### 4.3 保母對飼主請求報價或拒絕

1. SITTER 開啟 `REQUESTED` 的變更，畫面顯示：飼主期望內容、原因、各行程的快照單價、已收淨額、系統計算的新總額與差額。
2. 新內容預填飼主期望內容，SITTER 可調整（BASELINE §3.4「一律由 SITTER 給新內容與新總額」）；調整後仍須非空、新增方案在訂單快照內、日期符合 BR-13 / BR-14；`CANCEL_ALL` 的內容固定為報價當下所有 `PENDING` 行程。
3. 新總額：
   - FREE / BASIC，或保母訂閱鎖定 / 停權（不論鎖定前等級）：`新總額 = Σ(新內容中每個有效行程的快照單價)`，不可輸入（CQ-22、BASELINE CQ-66）；
   - PRO / ULTIMATE 且未鎖定：`新總額 = 系統計算值 + 加價 − 折扣`，有加減時必填原因（CQ-22）；
   - 新總額 ≥ 0；畫面顯示 `差額 = 新總額 − 已收淨額`：> 0「應補款 NT$X」、< 0「應退款 NT$X」、= 0「無差額」。
4. 送出報價 → `REQUESTED → QUOTED`，`expires_at` 重設為 `now + MODIFICATION_TIMEOUT_HOURS`，通知 OWNER。
5. 或點「拒絕變更」並填原因（必填，BR-8）→ `REQUESTED → REJECTED`，通知 OWNER（含拒絕原因）；訂單、行程、金額不變。
6. 報價送出（`QUOTED`）後，SITTER 不能再拒絕或改價；要改內容或金額，須等 OWNER 拒絕（或該變更逾時）後由 SITTER 重新發起（4.2）（BR-8）。

### 4.4 撤回

1. 發起者在 `REQUESTED` / `QUOTED` 點「撤回請求」→ `WITHDRAWN`，通知對方；訂單、行程、金額不變。

### 4.5 逾時

1. 排程每 15 分鐘查出 `status ∈ {REQUESTED, QUOTED}` 且 `now ≥ expires_at` 的變更，逐筆獨立交易鎖訂單、重驗條件後改 `EXPIRED`，寫 `order_logs`，通知雙方（BASELINE §3.4、§3.6、§5）。
2. 排程尚未執行前，任何使用者對該變更的操作已依時間判斷為逾期（BR-15）。

### 4.6 協商頁可執行動作（沿用原 PRD-016「協商頁只呈現當下可執行的動作」）

| 變更狀態 | OWNER 看到的動作 | SITTER 看到的動作 |
|---------|----------------|------------------|
| `REQUESTED`（OWNER 發起） | 撤回請求 | 送出報價、拒絕變更 |
| `QUOTED`（OWNER 發起） | 同意變更、拒絕變更、撤回請求 | 無（唯讀報價內容與到期時間；不能拒絕或改價，BR-8） |
| `QUOTED`（SITTER 發起） | 同意變更、拒絕變更 | 撤回請求 |
| `AWAITING_SUPPLEMENT` / `AWAITING_REFUND` | 見 PRD-023 | 見 PRD-023 |
| `SETTLED` / `REJECTED` / `WITHDRAWN` / `EXPIRED` | 無（唯讀結果：狀態、時間、金額、原因） | 無（同左） |

- `now ≥ expires_at` 時，上表 `REQUESTED` / `QUOTED` 的所有動作按鈕隱藏，顯示「此變更請求已逾期」。
- 前端隱藏只是提示；後端每個端點以狀態與身分防護（§7）。

## 5. 狀態轉移

> 只列本卡造成的轉移；變更子流程完整狀態圖見 BASELINE §3.4。本卡不改訂單主狀態、行程狀態、payment。

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1 | `[*]` | `REQUESTED` | OWNER | 訂單 `CONFIRMED` / `IN_PROGRESS`；無未結束變更；減少目標皆為本訂單 `PENDING` 行程；內容非空（BR-24）；新增行程符合 BR-13、BR-14、BR-25 | 409 `ORDER_STATE_CONFLICT` / `MODIFICATION_IN_PROGRESS` / `VISIT_ALREADY_STARTED`；422 `INVALID_VISIT_DATE` / `MODIFICATION_EMPTY` / `PLAN_NOT_IN_ORDER` |
| T2 | `[*]` | `QUOTED` | SITTER | 同 T1；計價符合 BR-9~BR-11、BR-18 | 同 T1；403 `PLAN_REQUIRED`；422 `INVALID_MODIFICATION_AMOUNT` |
| T3 | `REQUESTED` | `QUOTED` | SITTER | `now < expires_at`；訂單 `CONFIRMED` / `IN_PROGRESS`；減少目標仍為 `PENDING`；內容非空；新增行程符合 BR-13、BR-14、BR-25；計價符合 BR-9~BR-11、BR-18 | 409 `DEADLINE_PASSED`（`deadline=MODIFICATION_EXPIRES_AT`）/ `MODIFICATION_STATE_CONFLICT` / `ORDER_STATE_CONFLICT` / `VISIT_ALREADY_STARTED`；403 `PLAN_REQUIRED`；422 `INVALID_VISIT_DATE` / `INVALID_MODIFICATION_AMOUNT` / `MODIFICATION_EMPTY` / `PLAN_NOT_IN_ORDER` |
| T4 | `REQUESTED` | `REJECTED` | SITTER | `now < expires_at`；訂單非 `DISPUTED`；拒絕原因非空 | 409 `DEADLINE_PASSED` / `MODIFICATION_STATE_CONFLICT` / `ORDER_STATE_CONFLICT`；400 `VALIDATION_FAILED` |
| T5 | `REQUESTED` / `QUOTED` | `WITHDRAWN` | 發起者（OWNER / SITTER） | `now < expires_at`；呼叫者 = 發起者；訂單非 `DISPUTED` | 403 `FORBIDDEN`；409 `DEADLINE_PASSED` / `MODIFICATION_STATE_CONFLICT` / `ORDER_STATE_CONFLICT` |
| T6 | `REQUESTED` / `QUOTED` | `EXPIRED` | SYSTEM | `now ≥ expires_at` | — |

- `QUOTED → REJECTED`（OWNER 拒絕）與 `QUOTED → AWAITING_* / SETTLED`（OWNER 同意）見 PRD-023。
- 每次轉移寫 `order_logs` 並 enqueue 通知，與狀態變更同一交易（BASELINE §3.6）。

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 變更類型只有 `CHANGE`（增減行程；改期 = 減少舊日期 + 新增新日期）與 `CANCEL_ALL`（整筆取消，新內容 = 取消所有 `PENDING` 行程） | BASELINE §3.4；沿用原 PRD-016（雙向變更、日期平移） |
| BR-2 | 可發起時機：訂單 `CONFIRMED` / `IN_PROGRESS`，且無未結束（非 `SETTLED` / `REJECTED` / `WITHDRAWN` / `EXPIRED`）變更；`PENDING` / `PENDING_PAYMENT` / `DISPUTED` / `COMPLETED` / `CANCELLED` → 409 `ORDER_STATE_CONFLICT`；已有未結束變更 → 409 `MODIFICATION_IN_PROGRESS` | BASELINE §3.4、§3.2（DISPUTED 凍結）、CQ-17 |
| BR-3 | 付款前要改內容 → 撤回重下，不走本流程 | BASELINE §3.2、CQ-3 |
| BR-4 | 雙方皆可發起；OWNER 發起建立為 `REQUESTED`，SITTER 發起同時報價、建立為 `QUOTED` | BASELINE §3.4 狀態圖；沿用原 PRD-016 |
| BR-5 | 發起時原因必填（OWNER 與 SITTER） | 沿用原 PRD-016 A.3、C.2 |
| BR-6 | 只能減少 `PENDING` 行程；減少目標為 `IN_PROGRESS` / `DONE` / `CLOSED_BY_SYSTEM` / `CANCELLED` → 409 `VISIT_ALREADY_STARTED`；行程不屬於本訂單 → 400 `VALIDATION_FAILED` | BASELINE §3.4、§3.7；沿用原 PRD-016（已完成行程不可刪改） |
| BR-7 | 新內容與新總額一律由 SITTER 給定；OWNER 發起時填的內容是「期望內容」，報價時預填、SITTER 可調整；`CANCEL_ALL` 的減少目標固定為報價當下的所有 `PENDING` 行程 | BASELINE §3.4 |
| BR-8 | SITTER 可拒絕任何變更（含整筆取消）；拒絕只能在 `REQUESTED`，且拒絕原因必填（空白 → 400 `VALIDATION_FAILED`），原因隨通知帶給 OWNER。OWNER 發起的變更進入 `QUOTED` 後，SITTER 不能拒絕、不能再報價或改價（→ 409 `MODIFICATION_STATE_CONFLICT`）；SITTER 要改，須等 OWNER 拒絕（或變更逾時）後由 SITTER 重新發起 | BASELINE §3.4、CQ-41、§3.4 狀態圖；使用者決定 2026-11-21（本卡 CQ-5、CQ-6） |
| BR-9 | FREE / BASIC：`新總額 = Σ(新內容每個有效行程所屬方案的快照單價)`，由系統計算，不可輸入；請求帶加價或折扣 → 403 `PLAN_REQUIRED` | BASELINE §2.1、§3.4、CQ-22 |
| BR-10 | PRO / ULTIMATE（未鎖定，見 BR-18）：`新總額 = 系統計算值 + 加價 − 折扣`；有加價或折扣時原因必填（缺 → 400 `VALIDATION_FAILED`）。不內建強制退款比例（例如「前 N 天取消退 X%」），由保母報價決定 | BASELINE §2.1、§3.4、CQ-22；沿用原 PRD-016「退款政策」 |
| BR-11 | 新總額為整數 TWD 且 ≥ 0，< 0 → 422 `INVALID_MODIFICATION_AMOUNT`（模組碼）；`差額 = 新總額 − 已收淨額`，因新總額 ≥ 0，應退金額必 ≤ 已收淨額 | BASELINE §3.4、§4 |
| BR-12 | 計價範圍：新內容的有效行程 = 本訂單非 `CANCELLED` 且不在減少目標內的行程（含 `DONE`、`CLOSED_BY_SYSTEM`、`IN_PROGRESS`、保留的 `PENDING`）+ 新增行程；`DONE` 與 `CLOSED_BY_SYSTEM` 都計價（有異議走爭議） | BASELINE §3.4 計價範圍、§3.1 有效行程 |
| BR-13 | 新增行程日期：OWNER 發起（T1）、SITTER 發起（T2）與報價（T3）時都須 ≥ 今天 + 1 天（Asia/Taipei），否則 422 `INVALID_VISIT_DATE`；發起時檢查過、報價時仍再檢查一次。例：2026-11-21 23:30（Asia/Taipei）發起或報價，新增 2026-11-21 → 422；新增 2026-11-22 → 通過 | BASELINE §3.4、CQ-49（報價時）；使用者決定 2026-11-21（本卡 CQ-1，擴及發起時） |
| BR-14 | 新增行程日期須在該方案的可預訂日期區間內（方案未設區間 = 常態開放；只設起日或迄日者另一端不限）；區間依方案**目前**設定，保母方案等級（含降到 FREE / BASIC）不影響此檢查，區間設定本身的生效規則依 PRD-003；原訂單已有的日期不重新檢查；違反 → 422 `INVALID_VISIT_DATE`，payload `reason=PLAN_NOT_IN_RANGE`（與 PRD-005 送單同碼） | 沿用原 PRD-016（PRD-003 B，2026-10-26）；BASELINE O1「方案在可預約區間」；使用者決定 2026-11-21（本卡 CQ-4） |
| BR-15 | 期限以時間判斷：`now ≥ expires_at` 時報價、拒絕、撤回（及 PRD-023 的同意 / 拒絕）→ 409 `DEADLINE_PASSED`（payload `deadline=MODIFICATION_EXPIRES_AT`），即使排程尚未寫 `EXPIRED`；排程只補寫終態，以 `now ≥ expires_at` 判斷、每 15 分鐘一次 | BASELINE §3.2、§3.4 逾時、§5、CQ-46 |
| BR-16 | `expires_at = 進入 REQUESTED / QUOTED 的時間 + MODIFICATION_TIMEOUT_HOURS（72h）`；`REQUESTED → QUOTED` 時重設。例：2026-11-21 10:00（Asia/Taipei）發起 → `expires_at` = 2026-11-24 10:00（Asia/Taipei，存 `2026-11-24T02:00:00Z`）| BASELINE §5、CQ-15 |
| BR-17 | 訂閱鎖定 / 停權不擋本卡任何 SITTER 動作（屬既有訂單），不回 403 `SUBSCRIPTION_LOCKED`；驗收見 PRD-024 AC-2.8 | BASELINE §2.2、§3.4、CQ-19 |
| BR-18 | 方案等級以報價當下的訂閱狀態即時判斷（不看發起時、不放 JWT）。報價當下保母訂閱鎖定 / 停權時，不論鎖定前等級，一律依 FREE 規則：新總額 = 系統計算值，帶加價或折扣 → 403 `PLAN_REQUIRED`；報價本身不擋 | BASELINE §2.2、§3.2「鎖定期間的變更計價」（CQ-66）；使用者決定 2026-11-21（本卡 CQ-7） |
| BR-19 | 系統不做容量 / 檔期控管：新增行程不檢查保母當日其他訂單，排不出來由保母拒絕 | doc-conventions 已定案決策；沿用原 PRD-016 |
| BR-20 | 本卡不改 `orders.status`、行程、`total_amount`、payment；減少的行程於 OWNER 同意時才取消（PRD-023） | BASELINE §3.1、§3.4、CQ-1、CQ-32 |
| BR-21 | 變更不影響保母月接單額度（額度只在 O5 計數） | BASELINE §2.2、CQ-5 |
| BR-22 | 所有寫入端點帶 `Idempotency-Key`；同 key 同內容重送 → 回放第一次的狀態碼與 body；同 key 不同內容 → 422 `IDEMPOTENCY_KEY_REUSED`；處理中 → 409 `IDEMPOTENCY_IN_PROGRESS`；不同 key 重做已完成的動作 → 依當下狀態回 409（例：已 `QUOTED` 再報價 → `MODIFICATION_STATE_CONFLICT`），不改寫資料 | BASELINE §3.6；沿用原 PRD-016「重送已完成的動作回 409」 |
| BR-23 | 金額為整數 TWD；時間 UTC 儲存、ISO-8601 傳輸、畫面以 Asia/Taipei `YYYY-MM-DD HH:mm` 顯示 | BASELINE §4、§5 |
| BR-24 | 變更內容不得為空：`CHANGE` 沒有任何減少也沒有任何新增，或 `CANCEL_ALL` 但訂單已無 `PENDING` 行程 → 422 `MODIFICATION_EMPTY`（模組碼），不建立 / 不改變更。發起（T1、T2）與報價（T3，SITTER 調整後或 `CANCEL_ALL` 報價當下已無 `PENDING`）皆檢查 | 使用者決定 2026-11-21（本卡 CQ-2） |
| BR-25 | 新增行程只能選訂單快照內的方案（才有快照單價可計價）；選其他方案 → 422 `PLAN_NOT_IN_ORDER`（模組碼） | BASELINE §2.2 快照；使用者決定 2026-11-21（本卡 CQ-3） |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 非該訂單當事人操作任何本卡端點 | 拒絕，不改資料 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | 訂單 / 變更不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此訂單」 |
| EX-3 | token 角色不符（OWNER 角色呼叫報價、SITTER 角色呼叫飼主發起、ADMIN 呼叫任何寫入端點） | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換為保母 / 飼主身分後再操作」 |
| EX-4 | 非發起者撤回 | 拒絕 | 403 `FORBIDDEN` | 「只有提出者可以撤回」 |
| EX-5 | FREE / BASIC，或訂閱鎖定 / 停權中的保母（不論鎖定前等級），報價帶加價或折扣 | 拒絕，變更維持原狀態 | 403 `PLAN_REQUIRED` | 「自訂變更金額需專業版以上」；鎖定中另顯示「訂閱已逾期，變更金額依系統計算」 |
| EX-6 | 訂單不在 `CONFIRMED` / `IN_PROGRESS`（含 `DISPUTED` 凍結） | 拒絕，不建立 / 不改變更 | 409 `ORDER_STATE_CONFLICT` | 「此訂單目前無法變更」 |
| EX-7 | 已有未結束變更 | 拒絕 | 409 `MODIFICATION_IN_PROGRESS` | 「此訂單已有進行中的變更請求」 |
| EX-8 | 變更狀態不允許（已報價再報價、SITTER 拒絕或改價 `QUOTED`、撤回 `AWAITING_*` 或終態） | 拒絕，不改資料 | 409 `MODIFICATION_STATE_CONFLICT`（BASELINE §3.7） | 「此變更請求狀態已更新，請重新整理」 |
| EX-9 | 已過 `expires_at`、排程尚未寫 `EXPIRED` | 拒絕 | 409 `DEADLINE_PASSED`（`deadline=MODIFICATION_EXPIRES_AT`） | 「此變更請求已逾期」 |
| EX-10 | 減少目標已非 `PENDING`（保母已開始、系統已關閉） | 拒絕 | 409 `VISIT_ALREADY_STARTED`（payload 帶行程 id） | 「部分行程已開始，無法取消」 |
| EX-11 | 新增行程日期 < 明天（發起或報價時），或不在方案可預訂區間 | 拒絕，不建立 / 不改變更 | 422 `INVALID_VISIT_DATE`（不在區間時 payload `reason=PLAN_NOT_IN_RANGE`） | 「新增日期需為明天以後，且在方案開放日期內」 |
| EX-12 | 新總額 < 0 | 拒絕 | 422 `INVALID_MODIFICATION_AMOUNT`（模組碼） | 「新總額不可小於 0」 |
| EX-13 | 跨角色併發（OWNER 撤回與 SITTER 報價同時；SITTER 開始行程與 SITTER / OWNER 送出含該行程的變更同時） | 鎖訂單列序列化；後到者依新狀態重驗 | 後到者 409 `MODIFICATION_STATE_CONFLICT` / `VISIT_ALREADY_STARTED` | 同 EX-8 / EX-10 |
| EX-14 | 鎖等待逾時 | 不改資料 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「訂單處理中，請稍後再試」（前端自動重試一次） |
| EX-15 | 重複送出（同 `Idempotency-Key`） | 回放第一次結果，不重複建立 | 同第一次（例 201） | 同第一次 |
| EX-16 | 同 key 不同內容 / 同 key 處理中 | 拒絕 | 422 `IDEMPOTENCY_KEY_REUSED` / 409 `IDEMPOTENCY_IN_PROGRESS` | 「請重新送出」 |
| EX-17 | Resend 寄信失敗 | 轉移照常提交；站內通知已同交易寫入；Email 由排程重試最多 5 次，4xx 直接判失敗 | 原請求 2xx | 站內通知正常出現 |
| EX-18 | GCS | 不適用：本卡不上傳檔案（憑證見 PRD-007） | — | — |
| EX-19 | 欄位格式錯誤（發起原因或拒絕原因空白、日期格式錯、數量 ≤ 0、行程不屬本訂單） | 拒絕 | 400 `VALIDATION_FAILED` | 欄位下方錯誤提示 |
| EX-20 | 排程處理單筆失敗 | 該筆回滾，其他筆照常；下輪重跑 | — | — |
| EX-21 | 變更內容為空（`CHANGE` 無增減；`CANCEL_ALL` 但已無 `PENDING` 行程） | 拒絕，不建立 / 不改變更 | 422 `MODIFICATION_EMPTY`（模組碼） | 「請至少選擇一個要減少或新增的行程」/「此訂單已無可取消的行程」 |
| EX-22 | 新增行程選了訂單快照以外的方案 | 拒絕 | 422 `PLAN_NOT_IN_ORDER`（模組碼） | 「新增行程只能選擇本訂單已有的方案」 |

## 8. 驗收標準（AC）

> 共用前提（除非另寫）：保母方案單價快照 500；訂單 3 個行程 V1、V2、V3 各 500，`total_amount` = 1500，首次收款 VERIFIED，已收淨額 = 1500。時間皆為 Asia/Taipei。

### 子模組 1：發起變更

- [ ] **AC-1.1 (飼主發起 CHANGE)**：Given `IN_PROGRESS` 訂單（V1 `DONE`，V2、V3 `PENDING`），When OWNER 於 2026-11-21 10:00 送出 `CHANGE`（減少 V3、新增 2026-11-28 一次、原因「提早回國」），Then 201，變更 `status=REQUESTED`、`initiatedBy=OWNER`、`expires_at=2026-11-24T02:00:00Z`；訂單仍為 `IN_PROGRESS`、V3 仍 `PENDING`、`total_amount` 仍 1500；`order_logs` 新增一筆；SITTER 收到站內通知「飼主提出訂單變更」。對應：T1、BR-1、BR-4、BR-16、BR-20
- [ ] **AC-1.2 (飼主發起 CANCEL_ALL)**：Given `CONFIRMED` 訂單，When OWNER 送出 `CANCEL_ALL`（原因必填），Then 201，變更 `type=CANCEL_ALL`、`status=REQUESTED`；三個行程仍 `PENDING`；原因空白 → 400 `VALIDATION_FAILED`、不建立變更。Given `IN_PROGRESS` 訂單已無 `PENDING` 行程（V1、V3 `DONE`，V2 `CLOSED_BY_SYSTEM`），When OWNER 送出 `CANCEL_ALL`，Then 422 `MODIFICATION_EMPTY`、不建立變更。對應：T1、BR-1、BR-5、BR-24、EX-21
- [ ] **AC-1.3 (保母發起即報價)**：Given `CONFIRMED` 訂單、BASIC 保母，When SITTER 送出 `CHANGE`（減少 V3、原因「臨時住院」），Then 201，變更 `status=QUOTED`、`initiatedBy=SITTER`、新總額 = 1000、差額 = −500，`expires_at` = 送出時間 + 72h；OWNER 收到通知且協商頁出現「同意變更」「拒絕變更」。對應：T2、BR-4、BR-9、BR-11
- [ ] **AC-1.4 (不可發起的主狀態)**：Given 訂單分別為 `PENDING`、`PENDING_PAYMENT`、`DISPUTED`、`COMPLETED`、`CANCELLED`，When OWNER 或 SITTER 發起變更，Then 皆 409 `ORDER_STATE_CONFLICT`，`modification_requests` 無新增列。對應：BR-2、BR-3、EX-6
- [ ] **AC-1.5 (同時只能一筆未結束變更)**：Given 訂單已有一筆 `REQUESTED`（或 `QUOTED` / `AWAITING_SUPPLEMENT` / `AWAITING_REFUND`）變更，When 任一方再發起，Then 409 `MODIFICATION_IN_PROGRESS`；Given 前一筆為 `REJECTED` / `WITHDRAWN` / `EXPIRED` / `SETTLED`，When 再發起，Then 201。對應：BR-2、EX-7
- [ ] **AC-1.6 (發起內容驗證)**：Given V1 為 `DONE`、V2 為 `IN_PROGRESS`，When OWNER 送出減少 V1 或 V2 的 `CHANGE`，Then 409 `VISIT_ALREADY_STARTED`（payload 含該行程 id）；減少其他訂單的行程 → 400 `VALIDATION_FAILED`；`CHANGE` 無任何減少與新增 → 422 `MODIFICATION_EMPTY`；新增行程選訂單快照以外的方案 → 422 `PLAN_NOT_IN_ORDER`；於 2026-11-21 23:30 發起、新增 2026-11-21 → 422 `INVALID_VISIT_DATE`（新增 2026-11-22 → 201）；以上失敗皆不建立變更。對應：T1、BR-6、BR-13、BR-24、BR-25、EX-10、EX-11、EX-19、EX-21、EX-22
- [ ] **AC-1.7 (越權與角色)**：Given 訂單屬於 OWNER-A / SITTER-S，When OWNER-B 發起 → 403 `FORBIDDEN`；When 不存在的訂單 id → 404 `NOT_FOUND`；When 帳號 S 以 `currentRole=OWNER` 呼叫保母發起端點、或 ADMIN 呼叫 → 403 `ROLE_MISMATCH`；以上皆無新增變更。對應：§3、EX-1~EX-3
- [ ] **AC-1.8 (發起冪等)**：Given OWNER 以 `Idempotency-Key=K1` 發起成功（201），When 同 key 同內容重送，Then 回 201 與相同 `modificationId`，只有一筆變更；When 同 key 不同內容 → 422 `IDEMPOTENCY_KEY_REUSED`；When 換 key 重送 → 409 `MODIFICATION_IN_PROGRESS`。對應：BR-22、EX-15、EX-16

### 子模組 2：保母報價與拒絕

- [ ] **AC-2.1 (FREE / BASIC 系統計價)**：Given AC-1.1 的 `REQUESTED` 變更（減少 V3、新增 1 次）、BASIC 保母，When SITTER 送出報價（不帶加減），Then 200，`status=QUOTED`，新總額 = 1500（V1 + V2 + 新增 = 3 × 500），差額 = 0，畫面顯示「無差額」；`expires_at` 重設為報價時間 + 72h；OWNER 收到通知「保母已回覆變更報價」。對應：T3、BR-9、BR-12、BR-16
- [ ] **AC-2.2 (FREE / BASIC 不可自訂)**：Given FREE 或 BASIC 保母，When 報價帶加價 200 或折扣 100，Then 403 `PLAN_REQUIRED`，變更仍為 `REQUESTED`、`expires_at` 不變。對應：BR-9、EX-5
- [ ] **AC-2.3 (PRO 自訂並附原因)**：Given PRO 保母、`CONFIRMED` 訂單的 `CHANGE`（減少 V3、新增 2 次），When 報價加價 300、原因「節日加成」，Then 新總額 = 2000 + 300 = 2300、差額 = +800，畫面顯示「應補款 NT$800」；有加減但原因空白 → 400 `VALIDATION_FAILED`；折扣使新總額 < 0 → 422 `INVALID_MODIFICATION_AMOUNT`；新總額 = 0 → 200、顯示「應退款 NT$1500」。對應：BR-10、BR-11、EX-12
- [ ] **AC-2.4 (計價範圍含 DONE 與 CLOSED_BY_SYSTEM)**：Given `IN_PROGRESS` 訂單 V1 `DONE`、V2 `CLOSED_BY_SYSTEM`、V3 `PENDING` 的 `CANCEL_ALL`、BASIC 保母，When 報價，Then 減少目標 = {V3}、新總額 = 1000、差額 = −500（「應退款 NT$500」）；Given `CONFIRMED` 訂單（三個皆 `PENDING`）的 `CANCEL_ALL`，Then 新總額 = 0、差額 = −1500。Given `CANCEL_ALL` 於 `REQUESTED` 期間最後一個 `PENDING` 行程已被開始，When 報價，Then 422 `MODIFICATION_EMPTY`、變更仍 `REQUESTED`。對應：BR-7、BR-12、BR-24
- [ ] **AC-2.5 (新增日期下限與方案區間)**：Given 2026-11-21 23:30 報價，When 新增 2026-11-21，Then 422 `INVALID_VISIT_DATE`；新增 2026-11-22 → 200；Given 方案可預訂區間 2026-12-01 ~ 2026-12-31，When 新增 2026-11-30 → 422 `INVALID_VISIT_DATE`（payload `reason=PLAN_NOT_IN_RANGE`），原訂單已有的 11 月日期不被檢查；保母已降為 BASIC 但方案仍設此區間 → 同樣 422；SITTER 把新增行程改為訂單快照以外的方案 → 422 `PLAN_NOT_IN_ORDER`；以上失敗皆不改變更狀態。對應：T3、BR-13、BR-14、BR-25、EX-11、EX-22
- [ ] **AC-2.6 (保母拒絕)**：Given `REQUESTED` 變更，When SITTER 點「拒絕變更」並填原因，Then 200，`status=REJECTED`；行程、`total_amount`、訂單狀態不變；OWNER 收到通知含拒絕原因；之後雙方可再發起新變更；拒絕原因空白 → 400 `VALIDATION_FAILED`、仍 `REQUESTED`。Given OWNER 發起且已 `QUOTED`，When SITTER 呼叫拒絕，Then 409 `MODIFICATION_STATE_CONFLICT`、仍 `QUOTED`；When OWNER 拒絕該報價（PRD-023）後 SITTER 以新內容與金額發起變更，Then 201、新變更 `QUOTED`、`initiatedBy=SITTER`。對應：T2、T4、BR-8、EX-8、EX-19
- [ ] **AC-2.7 (報價狀態防護與冪等)**：Given 變更已 `QUOTED`，When SITTER 以新 key 再報價，Then 409 `MODIFICATION_STATE_CONFLICT`、新總額不變；When 以第一次報價的同一 key 同內容重送，Then 回放 200 與相同 body；When OWNER 角色呼叫報價端點 → 403 `ROLE_MISMATCH`。對應：BR-22、EX-3、EX-8、EX-15
- [ ] **AC-2.8 (方案以報價當下判斷；鎖定依 FREE 計價)**：Given 保母於發起時為 PRO、報價前已降為 BASIC，When 報價帶加價，Then 403 `PLAN_REQUIRED`；不帶加減 → 200，新總額為系統計算值。Given 發起時 BASIC、報價前升為 PRO，When 帶折扣並附原因，Then 200。Given PRO 保母訂閱已鎖定（或被停權），When 對 `REQUESTED` 報價帶加價 → 403 `PLAN_REQUIRED`、仍 `REQUESTED`；When 不帶加減 → 200、`QUOTED`、新總額 = 系統計算值（不回 403 `SUBSCRIPTION_LOCKED`）。對應：BR-9、BR-10、BR-17、BR-18、EX-5

### 子模組 3：撤回、逾時與協商頁

- [ ] **AC-3.1 (飼主撤回)**：Given OWNER 發起的變更為 `REQUESTED` 或 `QUOTED`，When OWNER 點「撤回請求」，Then 200，`status=WITHDRAWN`；行程、`total_amount`、訂單狀態不變；SITTER 收到通知「飼主已撤回變更請求」。對應：T5
- [ ] **AC-3.2 (保母撤回與非發起者)**：Given SITTER 發起的 `QUOTED` 變更，When SITTER 撤回，Then `status=WITHDRAWN`、OWNER 收到通知；When OWNER 呼叫撤回（非發起者），Then 403 `FORBIDDEN`、狀態不變。對應：T5、EX-4
- [ ] **AC-3.3 (不可撤回的狀態)**：Given 變更為 `AWAITING_SUPPLEMENT`、`AWAITING_REFUND`、`SETTLED`、`REJECTED`、`WITHDRAWN` 或 `EXPIRED`，When 發起者撤回，Then 409 `MODIFICATION_STATE_CONFLICT`，資料不變。對應：EX-8
- [ ] **AC-3.4 (逾時排程)**：Given `REQUESTED` 變更 `expires_at=2026-11-24T02:00:00Z`，When 排程於 2026-11-24 10:00（Asia/Taipei）之後第一次執行（≤ 15 分鐘內），Then `status=EXPIRED`、寫 `order_logs`（操作者 SYSTEM）、雙方收到通知「變更請求已逾期」；訂單狀態與行程不變。`QUOTED` 同。對應：T6、BR-15、BR-16
- [ ] **AC-3.5 (期限以時間判斷)**：Given `QUOTED` 變更已過 `expires_at` 但排程尚未執行（狀態仍 `QUOTED`），When 發起者撤回、或 SITTER 對 `REQUESTED` 報價 / 拒絕，Then 409 `DEADLINE_PASSED`（payload `deadline=MODIFICATION_EXPIRES_AT`），狀態不變；協商頁隱藏動作按鈕、顯示「此變更請求已逾期」。對應：BR-15、EX-9
- [ ] **AC-3.6 (協商頁只呈現可執行動作)**：Given §4.6 每一列的狀態與發起者，When OWNER 與 SITTER 分別開啟協商頁，Then 只出現該列列出的按鈕（例：OWNER 發起的 `QUOTED`，SITTER 頁面沒有「送出報價」「拒絕變更」，只顯示報價金額與到期時間）；終態只顯示唯讀結果。對應：§4.6
- [ ] **AC-3.7 (跨角色併發)**：Given OWNER 發起的 `REQUESTED` 變更，When OWNER 撤回與 SITTER 報價同時送出，Then 恰有一個成功；另一個 409 `MODIFICATION_STATE_CONFLICT`，最終狀態為 `WITHDRAWN` 或 `QUOTED` 其一，`order_logs` 只有成功那一筆；鎖等待超過 3 秒的請求 → 409 `ORDER_BUSY` + `Retry-After: 1`。對應：EX-13、EX-14
- [ ] **AC-3.8 (寄信失敗不影響轉移)**：Given Resend 回 5xx，When OWNER 發起變更，Then 201、變更 `REQUESTED` 已提交、SITTER 站內通知已存在；Email 由排程重試，最多 5 次。對應：EX-17

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | `modification_requests`（SD-ORDER）：類型、發起者（OWNER / SITTER / SYSTEM）、狀態、原因、期望內容（OWNER）、報價內容（減少的行程 id、新增行程的日期 / 方案 / 數量）、系統計算值、加價 / 折扣與原因、新總額、報價當下的已收淨額與差額、`expires_at`、結束時間（供 `completion_basis_at`）、拒絕原因；`order_logs` 每次轉移一筆。本卡不改 `orders`、`visits`、`payments` |
| 畫面 | 訂單詳情「申請變更 / 提出變更」入口（OWNER / SITTER）；變更精靈（選類型、選行程、新增日期，顯示方案可預訂區間）；協商頁（雙方，§4.6）；ADMIN 訂單唯讀頁顯示變更歷史。路徑由 SD-FRONTEND-SPEC 定 |
| 通知 | 發起 → 對方；報價 → OWNER；拒絕 → OWNER；撤回 → 對方；逾時 → 雙方。管道：站內 + Email（outbox，BASELINE §3.6；沿用原 PRD-016「請求送出、保母回覆、最終確認皆需通知」；範本見 PRD-014） |
| 排程 | 有：變更逾時（每 15 分鐘，`now ≥ expires_at`，BASELINE §3.6、§5） |
| API | 飼主發起、保母發起、保母報價、保母拒絕、撤回、查詢變更（含歷史）；契約在 SD-ORDER |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | BASELINE CQ-49 只規定「報價時」新增日期 ≥ 明天；OWNER 發起（`REQUESTED`）時要不要也擋 | 發起時同樣檢查（422 `INVALID_VISIT_DATE`），報價時再檢查一次；避免飼主送出必被退回的請求。已寫入 BR-13、T1、EX-11、AC-1.6 | 已決（使用者 2026-11-21） |
| CQ-2 | 變更內容為空要不要擋：`CHANGE` 沒有任何減少或新增；`CANCEL_ALL` 但訂單已無 `PENDING` 行程 | 擋，422 `MODIFICATION_EMPTY`（模組碼），不建立變更。已寫入 BR-24、T1~T3、EX-21、AC-1.2、AC-1.6、AC-2.4 | 已決（使用者 2026-11-21） |
| CQ-3 | 新增行程能否使用訂單快照以外的方案（沒有快照單價可算） | 不可，只能選訂單快照內的方案；違反 → 422 `PLAN_NOT_IN_ORDER`（模組碼）。已寫入 BR-25、T1~T3、EX-22、AC-1.6、AC-2.5 | 已決（使用者 2026-11-21） |
| CQ-4 | 方案可預訂區間的錯誤碼：原 PRD-016 用 `PLAN_NOT_IN_RANGE`，BASELINE §3.7 沒有此碼；另外保母降到 FREE / BASIC（無「特定日期預約」能力）後，舊方案區間是否仍生效 | 用 422 `INVALID_VISIT_DATE`，payload `reason=PLAN_NOT_IN_RANGE`（與 PRD-005 送單同碼）；區間依方案目前設定，方案等級不影響（區間設定本身的生效規則依 PRD-003）。已寫入 BR-14、EX-11、AC-2.5 | 已決（使用者 2026-11-21） |
| CQ-5 | 保母拒絕變更是否必填原因 | 必填（與 O3 婉拒一致），通知帶給飼主。已寫入 BR-8、T4、EX-19、AC-2.6 | 已決（使用者 2026-11-21） |
| CQ-6 | 原 PRD-016 寫「保母報價後不可再報價（仍可拒絕）」，BASELINE §3.4 狀態圖沒有 SITTER 在 `QUOTED` 的拒絕 / 撤回（OWNER 發起時） | 依 BASELINE：OWNER 發起的 `QUOTED`，SITTER 不能拒絕或改價；SITTER 要改，等飼主拒絕（或逾時）後重新發起。已寫入 §3、§4.3-6、§4.6、BR-8、EX-8、AC-2.6 | 已決（使用者 2026-11-21） |
| CQ-7 | 保母訂閱鎖定（逾期）時的計價方式：鎖定前是 PRO，是否還能自訂新總額 | 鎖定 / 停權期間依 FREE 規則：只能用系統計算值，帶加減 → 403 `PLAN_REQUIRED`；報價本身不擋（BASELINE §3.2、CQ-66）。已寫入 §3、§4.3-3、BR-18、EX-5、AC-2.8 | 已決（使用者 2026-11-21） |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-21 | 依新範本與 DOMAIN-BASELINE 全文重寫；拆出 PRD-023（同意與結清）、PRD-024（款項爭議與協商限制），本卡保留發起、報價、拒絕、撤回、逾時 | 對齊 BASELINE（Approved 2026-11-21）；處理 `docs/reviews/00-DOMAIN-BASELINE.prd.review.md` 第 1 輪 #43–#55、第 2 輪 PRD 衝突 #5、#6，逐項見下列 |
| 2026-11-21 | 第 1 輪 #43：發起條件改為訂單 `CONFIRMED` / `IN_PROGRESS`，其他狀態 409 `ORDER_STATE_CONFLICT`（BR-2、AC-1.4）；刪除舊的「已付款」主狀態 | 第 1 輪 #43 |
| 2026-11-21 | 第 1 輪 #44：請求初始狀態改為 `REQUESTED`；刪除舊的「變更協商中」訂單主狀態，訂單主狀態全程不變（AC-1.1） | 第 1 輪 #44、CQ-1 |
| 2026-11-21 | 第 1 輪 #45：刪除所有「恢復 / 回到發起前狀態」字眼；AC 改斷言訂單狀態維持原值（本卡 AC-1.1、AC-2.6、AC-3.1；結清後見 PRD-023） | 第 1 輪 #45 |
| 2026-11-21 | 第 1 輪 #46：補款改為變更 `AWAITING_SUPPLEMENT` + `SUPPLEMENT` payment，憑證存於 payment；移至 PRD-023 子模組 2，上傳 / 核對細節見 PRD-007 | 第 1 輪 #46 |
| 2026-11-21 | 第 1 輪 #47：舊的「退款待確認」訂單主狀態改為變更 `AWAITING_REFUND` + `MODIFICATION_REFUND` payment，保母上傳退款憑證、飼主確認；移至 PRD-023 子模組 3 | 第 1 輪 #47、CQ-34、CQ-54 |
| 2026-11-21 | 第 1 輪 #48：整筆取消分 `CONFIRMED`（結束後無有效行程 → O10 `CANCELLED`）與 `IN_PROGRESS`（照常結案，收入 = 已收淨額）；移至 PRD-023 AC-3.3~3.5 | 第 1 輪 #48、CQ-14、CQ-41 |
| 2026-11-21 | 第 1 輪 #49：套用時點依 CQ-32——減少的行程於飼主同意時取消，新增行程與新總額於 `SETTLED` 生效（本卡 BR-20；PRD-023） | 第 1 輪 #49、CQ-32 |
| 2026-11-21 | 第 1 輪 #50：保母發起直接建立為 `QUOTED`（T2、AC-1.3） | 第 1 輪 #50、BASELINE §3.4 |
| 2026-11-21 | 第 1 輪 #51：差額改為「新總額 − 已收淨額」，應退 ≤ 已收淨額（BR-11） | 第 1 輪 #51、BASELINE §4 |
| 2026-11-21 | 第 1 輪 #52：FREE / BASIC 新總額由系統依快照單價計算，PRO+ 可加減附原因（BR-9、BR-10、AC-2.1~2.3）；計價範圍含 `DONE` 與 `CLOSED_BY_SYSTEM`（BR-12、AC-2.4） | 第 1 輪 #52、CQ-22、第 2 輪 #14 |
| 2026-11-21 | 第 1 輪 #53 與第 2 輪 PRD 衝突 #5：原備註「飼主長時間未確認可發起爭議」改為 O14——OWNER / SITTER 任一方於 `AWAITING_REFUND`，或補款 `SUBMITTED` 超過 72h 未核對時申報，`CONFIRMED` / `IN_PROGRESS` 皆可；移至 PRD-024 子模組 1 | 第 1 輪 #53、第 2 輪 #5、CQ-30、CQ-41、CQ-42 |
| 2026-11-21 | 第 1 輪 #54：原 9 條 AC（編號錯亂）改為三張卡共 8 個子模組，每子模組 ≤ 8 條 AC | 第 1 輪 #54 |
| 2026-11-21 | 第 1 輪 #55：重送分兩種——同 `Idempotency-Key` 回放第一次結果；不同 key 重做已完成動作依狀態回 409（BR-22、AC-1.8、AC-2.7） | 第 1 輪 #55、BASELINE §3.6 |
| 2026-11-21 | 第 2 輪 PRD 衝突 #6：變更終態補 `WITHDRAWN`、`EXPIRED`（T5、T6、AC-3.1~3.5）；新增撤回、72h 逾時、期限以時間判斷 | 第 2 輪 #6、CQ-15、CQ-46 |
| 2026-11-21 | 其他：新增行程日期 ≥ 明天（BR-13，CQ-49）；方案可預訂區間錯誤碼改用 baseline 碼並列 CQ-4；原「線上支付自動退刷」移出（線上退款未在 BASELINE 定義，見 PRD-015）；原「帳務總覽以變更後金額為準」改依 BASELINE §4（收入 = 已收淨額，見 PRD-009） | BASELINE §3.4、§4 |
| 2026-11-21 | 待釐清 CQ-1~7 全部依建議預設值定案並寫入正文：發起時也檢查新增日期（BR-13）；新增 BR-24 `MODIFICATION_EMPTY`、BR-25 `PLAN_NOT_IN_ORDER`（模組碼）與 EX-21、EX-22；區間錯誤碼 payload `reason=PLAN_NOT_IN_RANGE`、方案等級不影響（BR-14）；拒絕原因必填、`QUOTED` 後 SITTER 不能拒絕或改價（BR-8、§4.3-6）；鎖定 / 停權依 FREE 規則計價（BR-18，BASELINE CQ-66）；`MODIFICATION_STATE_CONFLICT` 改標 BASELINE §3.7 錯誤碼（EX-8）；AC-1.2、AC-1.6、AC-2.4~2.6、AC-2.8 擴充，AC 總數不變 | 使用者決定 2026-11-21；DOMAIN-BASELINE 第 2 版修訂（CQ-66、§3.7） |
