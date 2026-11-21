# PRD-016: 訂單變更：發起、報價與協商期限

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 核准 | 使用者，2026-11-22 |
| 建立日期 | 2026-05-09（2026-11-21 依 DOMAIN-BASELINE 重寫） |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0） |
| 測試情境 | TS-016 |
| 相依 PRD | PRD-023（飼主同意 / 拒絕、補款、退款與結清，接續本卡的 `QUOTED`）；PRD-024（款項爭議、協商期間對結案的限制、DISPUTED 凍結、訂閱鎖定不擋變更）；PRD-005（送單的方案可預約區間與數量上限，本卡新增行程同規則）；PRD-006（報價時的單價快照與 surcharge / discount 欄位、金額上限）；PRD-003（方案可預訂日期區間的設定）；PRD-008（行程開始 / 系統關閉，影響「可減少的行程」）；PRD-014（通知管道） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1、§1.2（角色、資源歸屬、ADMIN 可讀「`DISPUTED` 或有 OPEN 待辦」）；§2.1（CQ-22 變更自訂新總額、CQ-68 方案可預約區間只在 PRO / ULTIMATE 且未鎖定時套用）；§2.2（方案即時查詢、快照、訂閱鎖定）；§3.1（主狀態、有效行程）；O1（每個日期群組至少綁 1 隻毛孩，CQ-60）；§3.2（`CONFIRMED` 後只能透過變更改內容、期限以時間判斷、DISPUTED 凍結、鎖定期間的變更計價 CQ-66）；§3.4（變更類型、可發起時機 CQ-69、報價、計價範圍、新增行程日期、差額、撤回、逾時、訂閱鎖定）；§3.6（鎖、稽核、通知 outbox、冪等、排程）；§3.7（錯誤碼、判斷順序 CQ-70、payload 共通欄位 CQ-71）；§4（已收淨額、變更差額、金額上限 CQ-74）；§5（`MODIFICATION_TIMEOUT_HOURS`、`completion_basis_at` 只看 SETTLED 變更 CQ-69、時區、排程頻率） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念（角色、SaaS 額度、訂單狀態、時間起算點）只能**引用** DOMAIN-BASELINE，不得在這裡重新定義；要改就改 baseline。

> 原 PRD-016 的範圍拆成三張（皆歸屬 SD-ORDER）：
> - **PRD-016（本卡）**：變更請求從建立到「等飼主決定」為止——發起、保母報價 / 拒絕、撤回、`REQUESTED` / `QUOTED` 逾時。變更的錯誤判斷順序（BR-28）與鎖定期間計價（BR-17、BR-18）在本卡定義，PRD-023、PRD-024 引用。
> - **PRD-023**：飼主同意 / 拒絕、減少行程立即取消、補款與補款逾時重算、退款、結清後的主狀態（O10）。
> - **PRD-024**：款項爭議（O14）、DISPUTED 凍結、協商期間的結案限制、訂閱鎖定不擋變更。

---

## 1. 背景與目的

訂單 `CONFIRMED` 之後，訂單內容與金額只能透過變更協商改變（BASELINE §3.2，CQ-7）。飼主可能提早回國或行程取消、保母可能臨時無法服務，雙方都需要一個留下紀錄的協商流程：誰提出、改了哪些行程、新總額怎麼算、對方有沒有回應。本卡定義變更請求的建立與報價階段；變更協商期間訂單主狀態**不變**（CQ-1），變更是獨立實體 `modification_requests`（BASELINE §3.4）。

## 2. 使用者故事

- **US-1** 身為飼主，我希望在付款成立後、仍有未開始的行程時，能提出減少 / 增加行程或整筆取消，以便計畫改變時不必私下與保母協調金額。
- **US-2** 身為保母，我希望在無法提供完整服務時能主動提出變更並同時報價，以便飼主直接決定是否接受。
- **US-3** 身為保母，我希望看到飼主的變更請求後，依訂單成交時的快照單價得到新總額（專業版以上可再加減），或直接拒絕，以便金額有依據、不被迫接受。
- **US-4** 身為發起者，我希望在對方回應前撤回請求；對方 72 小時不回應時請求自動失效，以便訂單不會卡在協商中。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 發起變更（`REQUESTED`） | 本人訂單 | ✗（403 `ROLE_MISMATCH`） | ✗ | ✗ | 無；方案可預約區間依 BR-14 |
| 發起變更並同時報價（`QUOTED`） | ✗ | 本人訂單 | ✗ | ✗ | 計價方式依方案（BR-9、BR-10）；訂閱鎖定不擋，但鎖定期間依 FREE 規則計價（BR-17、BR-18）；方案可預約區間依 BR-14 |
| 對 `REQUESTED` 報價 | ✗ | 本人訂單 | ✗ | ✗ | 同上 |
| 拒絕 `REQUESTED` | ✗ | 本人訂單 | ✗ | ✗ | 無；訂閱鎖定不擋 |
| 拒絕或改價 OWNER 發起的 `QUOTED` | ✗ | ✗（409 `MODIFICATION_STATE_CONFLICT`，BR-8） | ✗ | ✗ | — |
| 撤回 | 本人發起、仍為 `REQUESTED` / `QUOTED` 的請求 | 同左 | ✗ | ✗ | 無 |
| 檢視變更請求與歷史 | 本人訂單 | 本人訂單 | 唯讀，限 BASELINE §1.2 可讀訂單（`DISPUTED` 或有 OPEN 待辦的訂單） | ✗ | 無 |

- 「本人訂單」以 token 身分判斷：訂單存在但不屬於呼叫者 → 403 `FORBIDDEN`；不存在 → 404 `NOT_FOUND`（BASELINE §1.2）。
- token 的 `currentRole` 必須是端點要求的角色，否則 403 `ROLE_MISMATCH`，即使該帳號是此訂單另一方（BASELINE §1.1）。ADMIN 呼叫本卡寫入端點一律 403 `ROLE_MISMATCH`。
- 撤回限發起者且變更為 `REQUESTED` / `QUOTED`。依判斷順序（BR-28），發起者檢查先於變更狀態檢查：呼叫者不是發起者（含 `initiatedBy=SYSTEM` 的退款變更，任何人都不是發起者）→ 403 `FORBIDDEN`；發起者本人撤回已 `AWAITING_*` 或終態的變更 → 409 `MODIFICATION_STATE_CONFLICT`。

## 4. 主流程

### 4.1 飼主發起

1. OWNER 在訂單詳情點「申請變更」，選擇類型：
   - `CHANGE`：勾選要減少的行程（只能選 `PENDING` 行程）及 / 或新增行程（每筆：日期 + 方案 + 每日次數 + 綁定毛孩；毛孩預設全選本訂單已綁定的毛孩，BR-26）；
   - `CANCEL_ALL`：整筆取消，內容 = 取消所有 `PENDING` 行程（BASELINE §3.4）。
2. 填寫原因（必填，1–200 字，BR-5）後送出，帶 `Idempotency-Key`。
3. 系統鎖訂單列（BASELINE §3.6）並依 BR-28 的順序檢查：欄位格式與上限（BR-5、BR-26、BR-27；行程不屬本訂單 → 400）；訂單為 `CONFIRMED` / `IN_PROGRESS`；無未結束變更；訂單至少還有 1 個 `PENDING` 行程（CQ-69）；要減少的行程皆為 `PENDING`；內容非空（BR-24）；新增行程的方案在訂單快照內（BR-25）、日期 ≥ 明天（BR-13）且符合方案可預約區間（BR-14，以發起當下的保母等級判斷）。
4. 建立變更請求：`status=REQUESTED`、`initiatedBy=OWNER`、`expires_at = now + MODIFICATION_TIMEOUT_HOURS`；寫 `order_logs`；同交易 enqueue 通知 SITTER。訂單主狀態、行程、`total_amount` 都不變。

### 4.2 保母發起（同時報價）

1. SITTER 在訂單詳情點「提出變更」，選類型與內容（同 4.1-1），填原因，系統依方案算出新總額（4.3），PRO / ULTIMATE 且未鎖定時可另加減並附原因。
2. 系統檢查同 4.1-3，另檢查計價規則（BR-9~BR-11、BR-18）。
3. 建立變更請求：`status=QUOTED`、`initiatedBy=SITTER`、`expires_at = now + MODIFICATION_TIMEOUT_HOURS`；通知 OWNER 進行同意 / 拒絕（PRD-023）。

### 4.3 保母對飼主請求報價或拒絕

1. SITTER 開啟 `REQUESTED` 的變更，畫面顯示：飼主期望內容、原因、各行程的快照單價、已收淨額、系統計算的新總額與差額。
2. 新內容預填飼主期望內容，SITTER 可調整（BASELINE §3.4「一律由 SITTER 給新內容與新總額」）；調整後仍須符合 BR-24~BR-27（非空、方案在快照內、毛孩在本訂單範圍內、數量上限），日期符合 BR-13 / BR-14（BR-14 以報價當下的保母等級判斷）；`CANCEL_ALL` 的內容固定為報價當下所有 `PENDING` 行程。
3. 新總額：
   - FREE / BASIC，或保母訂閱鎖定 / 停權（不論鎖定前等級）：`新總額 = Σ(新內容中每個有效行程的快照單價)`，不可輸入（CQ-22、BASELINE CQ-66）；
   - PRO / ULTIMATE 且未鎖定：`新總額 = 系統計算值 + 加價 − 折扣`，加價、折扣各 0–999,999，任一 > 0 時必填調整原因 1–200 字（CQ-22、BR-10）；
   - 0 ≤ 新總額 ≤ 999,999；畫面顯示 `差額 = 新總額 − 已收淨額`：> 0「應補款 NT$X」、< 0「應退款 NT$X」、= 0「無差額」。
4. 送出報價 → `REQUESTED → QUOTED`，`expires_at` 重設為 `now + MODIFICATION_TIMEOUT_HOURS`，通知 OWNER。
5. 或點「拒絕變更」並填原因（必填，1–200 字，BR-8）→ `REQUESTED → REJECTED`，同交易記錄結束時間 = 轉移時間，通知 OWNER（含拒絕原因）；訂單、行程、金額不變。
6. 報價送出（`QUOTED`）後，SITTER 不能再拒絕或改價；要改內容或金額，須等該變更結束（OWNER 拒絕、OWNER 撤回或逾時）後由 SITTER 重新發起（4.2）（BR-8）。

### 4.4 撤回

1. 發起者在 `REQUESTED` / `QUOTED` 點「撤回請求」→ `WITHDRAWN`，同交易記錄結束時間 = 轉移時間，通知對方；訂單、行程、金額不變。

### 4.5 逾時

1. 排程每 15 分鐘唯讀查出 `status ∈ {REQUESTED, QUOTED}` 且 `now ≥ expires_at` 的變更，逐筆獨立交易鎖訂單後重驗：鎖住後變更仍為 `REQUESTED` / `QUOTED` 且 `now ≥ expires_at` 才改 `EXPIRED`，否則略過（例：同輪重跑時已是 `EXPIRED`，不重複寫 `order_logs` 與通知）。改 `EXPIRED` 時同交易記錄結束時間 = 轉移時間、寫 `order_logs`（操作者 SYSTEM）、通知雙方（BASELINE §3.4、§3.6、§5）。
2. 排程尚未執行前，任何使用者對該變更的操作已依時間判斷為逾期（BR-15）。

### 4.6 協商頁可執行動作與歷史（沿用原 PRD-016「協商頁只呈現當下可執行的動作」）

| 變更狀態 | OWNER 看到的動作 | SITTER 看到的動作 |
|---------|----------------|------------------|
| `REQUESTED`（OWNER 發起） | 撤回請求 | 送出報價、拒絕變更 |
| `QUOTED`（OWNER 發起） | 同意變更、拒絕變更、撤回請求 | 無（唯讀報價內容與到期時間；不能拒絕或改價，BR-8） |
| `QUOTED`（SITTER 發起） | 同意變更、拒絕變更 | 撤回請求 |
| `AWAITING_SUPPLEMENT` / `AWAITING_REFUND` | 見 PRD-023 | 見 PRD-023 |
| `SETTLED` / `REJECTED` / `WITHDRAWN` / `EXPIRED` | 無（唯讀結果：狀態、結束時間、金額、原因） | 無（同左） |

- `now ≥ expires_at` 時，上表 `REQUESTED` / `QUOTED` 的所有動作按鈕隱藏，顯示「此變更請求已逾期」。
- 前端隱藏只是提示；後端每個端點以狀態與身分防護（§7）。
- 變更歷史列表依 `created_at` 倒序；訂單沒有任何變更時顯示「尚無變更紀錄」。

## 5. 狀態轉移

> 只列本卡造成的轉移；變更子流程完整狀態圖見 BASELINE §3.4。本卡不改訂單主狀態、行程狀態、payment。錯誤碼欄依 BR-28 判斷順序排列。

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1 | `[*]` | `REQUESTED` | OWNER | 欄位符合 BR-5、BR-26、BR-27，減少目標屬本訂單；訂單 `CONFIRMED` / `IN_PROGRESS`；無未結束變更；至少 1 個 `PENDING` 行程（CQ-69）；減少目標皆 `PENDING`；內容非空（BR-24）；新增行程符合 BR-13、BR-14、BR-25 | 400 `VALIDATION_FAILED`；409 `ORDER_STATE_CONFLICT` / `MODIFICATION_IN_PROGRESS` / `MODIFICATION_NOT_ALLOWED` / `VISIT_ALREADY_STARTED`；422 `INVALID_VISIT_DATE` / `MODIFICATION_EMPTY` / `PLAN_NOT_IN_ORDER` |
| T2 | `[*]` | `QUOTED` | SITTER | 同 T1；計價符合 BR-9~BR-11、BR-18 | 同 T1；422 `INVALID_MODIFICATION_AMOUNT`；403 `PLAN_REQUIRED` |
| T3 | `REQUESTED` | `QUOTED` | SITTER | 欄位符合 BR-10、BR-11、BR-26、BR-27；訂單 `CONFIRMED` / `IN_PROGRESS`；變更為 `REQUESTED`；`now < expires_at`；減少目標仍為 `PENDING`；內容非空；新增行程符合 BR-13、BR-14、BR-25；計價符合 BR-9~BR-11、BR-18 | 400 `VALIDATION_FAILED`；409 `ORDER_STATE_CONFLICT` / `MODIFICATION_STATE_CONFLICT` / `DEADLINE_PASSED`（`deadline=MODIFICATION_EXPIRES_AT`、`deadlineAt`）/ `VISIT_ALREADY_STARTED`；422 `INVALID_VISIT_DATE` / `MODIFICATION_EMPTY` / `PLAN_NOT_IN_ORDER` / `INVALID_MODIFICATION_AMOUNT`；403 `PLAN_REQUIRED` |
| T4 | `REQUESTED` | `REJECTED` | SITTER | 拒絕原因 1–200 字；訂單非 `DISPUTED`；變更為 `REQUESTED`；`now < expires_at`。附帶：記錄結束時間 = 轉移時間 | 400 `VALIDATION_FAILED`；409 `ORDER_STATE_CONFLICT` / `MODIFICATION_STATE_CONFLICT` / `DEADLINE_PASSED` |
| T5 | `REQUESTED` / `QUOTED` | `WITHDRAWN` | 發起者（OWNER / SITTER） | 訂單非 `DISPUTED`；變更為 `REQUESTED` / `QUOTED`；呼叫者 = 發起者；`now < expires_at`。附帶：記錄結束時間 = 轉移時間 | 409 `ORDER_STATE_CONFLICT` / `MODIFICATION_STATE_CONFLICT`（含 `AWAITING_*`、終態、`initiatedBy=SYSTEM`）；403 `FORBIDDEN`（非發起者）；409 `DEADLINE_PASSED` |
| T6 | `REQUESTED` / `QUOTED` | `EXPIRED` | SYSTEM | 鎖住後仍為 `REQUESTED` / `QUOTED` 且 `now ≥ expires_at`。附帶：記錄結束時間 = 轉移時間 | —（不符則略過） |

- `QUOTED → REJECTED`（OWNER 拒絕）與 `QUOTED → AWAITING_* / SETTLED`（OWNER 同意）見 PRD-023。
- 每次轉移寫 `order_logs` 並 enqueue 通知，與狀態變更同一交易（BASELINE §3.6）。
- 本卡造成的終態（T4、T5、T6）都記錄結束時間，但不推遲 `completion_basis_at`；只有 `SETTLED` 變更的結束時間計入（BASELINE §5、CQ-69；見 PRD-024 BR-8）。

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 變更類型只有 `CHANGE`（增減行程；改期 = 減少舊日期 + 新增新日期）與 `CANCEL_ALL`（整筆取消，新內容 = 取消所有 `PENDING` 行程） | BASELINE §3.4；沿用原 PRD-016（雙向變更、日期平移） |
| BR-2 | 可發起時機：訂單 `CONFIRMED` / `IN_PROGRESS`、至少還有 1 個 `PENDING` 行程，且無未結束（非 `SETTLED` / `REJECTED` / `WITHDRAWN` / `EXPIRED`）變更。`PENDING` / `PENDING_PAYMENT` / `DISPUTED` / `COMPLETED` / `CANCELLED` → 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`）；已有未結束變更 → 409 `MODIFICATION_IN_PROGRESS`；已無 `PENDING` 行程（含只想新增行程的 `CHANGE`）→ 409 `MODIFICATION_NOT_ALLOWED`。此條只在發起（T1、T2）檢查；報價（T3）時 `PENDING` 已全部開始，依 BR-24 判斷 | BASELINE §3.4 可發起時機（CQ-17、CQ-69）、§3.2（DISPUTED 凍結）、§3.7 |
| BR-3 | 付款前要改內容 → 撤回重下，不走本流程 | BASELINE §3.2、CQ-3 |
| BR-4 | 雙方皆可發起；OWNER 發起建立為 `REQUESTED`，SITTER 發起同時報價、建立為 `QUOTED` | BASELINE §3.4 狀態圖；沿用原 PRD-016 |
| BR-5 | 發起時原因必填（OWNER 與 SITTER），去前後空白後 1–200 字，否則 400 `VALIDATION_FAILED` | 沿用原 PRD-016 A.3、C.2；使用者決定 2026-11-21（本卡 CQ-10） |
| BR-6 | 只能減少 `PENDING` 行程；減少目標為 `IN_PROGRESS` / `DONE` / `CLOSED_BY_SYSTEM` / `CANCELLED` → 409 `VISIT_ALREADY_STARTED`（payload 帶行程 id）；行程不屬於本訂單 → 400 `VALIDATION_FAILED` | BASELINE §3.4、§3.7；沿用原 PRD-016（已完成行程不可刪改） |
| BR-7 | 新內容與新總額一律由 SITTER 給定；OWNER 發起時填的內容是「期望內容」，報價時預填、SITTER 可調整；`CANCEL_ALL` 的減少目標固定為報價當下的所有 `PENDING` 行程 | BASELINE §3.4 |
| BR-8 | SITTER 可拒絕任何變更（含整筆取消）；拒絕只能在 `REQUESTED`，拒絕原因去前後空白後 1–200 字（不符 → 400 `VALIDATION_FAILED`），原因隨通知帶給 OWNER。OWNER 發起的變更進入 `QUOTED` 後，SITTER 不能拒絕、不能再報價或改價（→ 409 `MODIFICATION_STATE_CONFLICT`）；SITTER 要改，須等該變更結束（OWNER 拒絕、OWNER 撤回或逾時）後由 SITTER 重新發起 | BASELINE §3.4、CQ-41、§3.4 狀態圖；使用者決定 2026-11-21（本卡 CQ-5、CQ-6、CQ-10） |
| BR-9 | FREE / BASIC：`新總額 = Σ(新內容每個有效行程所屬方案的快照單價)`，由系統計算，不可輸入；請求的加價或折扣任一 > 0 → 403 `PLAN_REQUIRED`（加價 = 0 且折扣 = 0 或未帶，視為未自訂） | BASELINE §2.1、§3.4、CQ-22 |
| BR-10 | PRO / ULTIMATE（未鎖定，見 BR-18）：`新總額 = 系統計算值 + 加價 − 折扣`；加價、折扣各為 0–999,999 的整數，超出 → 400 `VALIDATION_FAILED`；任一 > 0 時調整原因必填，去前後空白後 1–200 字（不符 → 400 `VALIDATION_FAILED`）。不內建強制退款比例（例如「前 N 天取消退 X%」），由保母報價決定 | BASELINE §2.1、§3.4、CQ-22；沿用原 PRD-016「退款政策」；使用者決定 2026-11-21（本卡 CQ-10，比照 PRD-006 BR-5） |
| BR-11 | 新總額為整數 TWD：> 999,999 → 400 `VALIDATION_FAILED`；< 0 → 422 `INVALID_MODIFICATION_AMOUNT`（模組碼）。`差額 = 新總額 − 已收淨額`，因新總額 ≥ 0，應退金額必 ≤ 已收淨額 | BASELINE §3.4、§4（CQ-74 單筆上限）；使用者決定 2026-11-21（本卡 CQ-10） |
| BR-12 | 計價範圍：新內容的有效行程 = 本訂單非 `CANCELLED` 且不在減少目標內的行程（含 `DONE`、`CLOSED_BY_SYSTEM`、`IN_PROGRESS`、保留的 `PENDING`）+ 新增行程；`DONE` 與 `CLOSED_BY_SYSTEM` 都計價（有異議走爭議） | BASELINE §3.4 計價範圍、§3.1 有效行程 |
| BR-13 | 新增行程日期：OWNER 發起（T1）、SITTER 發起（T2）與報價（T3）時都須 ≥ 今天 + 1 天（Asia/Taipei），否則 422 `INVALID_VISIT_DATE`；發起時檢查過、報價時仍再檢查一次。例：2026-11-21 23:30（Asia/Taipei）發起或報價，新增 2026-11-21 → 422；新增 2026-11-22 → 通過 | BASELINE §3.4、CQ-49（報價時）；使用者決定 2026-11-21（本卡 CQ-1，擴及發起時） |
| BR-14 | 方案可預約日期區間：只在檢查當下（T1、T2、T3 各自的當下）保母訂閱為 PRO / ULTIMATE 且未鎖定 / 停權時套用；FREE / BASIC / 鎖定 / 停權一律常態開放（與 PRD-005 BR-8 送單一致）。套用時：新增行程日期須在該方案目前設定的區間內（方案未設區間 = 常態開放；只設起日或迄日者另一端不限），原訂單已有的日期不重新檢查；違反 → 422 `INVALID_VISIT_DATE`，payload `reason=PLAN_NOT_IN_RANGE`。例：OWNER 發起時保母為 BASIC、新增日期在區間外 → 201；報價前保母升為 PRO → 報價 422，SITTER 須調整日期或拒絕 | BASELINE §2.1（CQ-68）；PRD-005 BR-8；使用者決定 2026-11-21（本卡 CQ-8 取代 CQ-4 的「不看等級」；錯誤碼沿用 CQ-4） |
| BR-15 | 期限以時間判斷：`now ≥ expires_at` 時報價、拒絕、撤回（及 PRD-023 的同意 / 拒絕）→ 409 `DEADLINE_PASSED`（payload `deadline=MODIFICATION_EXPIRES_AT`、`deadlineAt` = 該變更的 `expires_at`），即使排程尚未寫 `EXPIRED`；排程只補寫終態，以 `now ≥ expires_at` 判斷、每 15 分鐘一次 | BASELINE §3.2、§3.4 逾時、§3.7 payload（CQ-71）、§5、CQ-46 |
| BR-16 | `expires_at = 進入 REQUESTED / QUOTED 的時間 + MODIFICATION_TIMEOUT_HOURS（72h）`；`REQUESTED → QUOTED` 時重設。例：2026-11-21 10:00（Asia/Taipei）發起 → `expires_at` = 2026-11-24 10:00（Asia/Taipei，存 `2026-11-24T02:00:00Z`）| BASELINE §5、CQ-15 |
| BR-17 | 訂閱鎖定 / 停權不擋本卡任何 SITTER 動作（屬既有訂單），不回 403 `SUBSCRIPTION_LOCKED`；鎖定期間的計價以本卡 BR-18、AC-2.8 為準，PRD-024 只引用 | BASELINE §2.2、§3.4、CQ-19 |
| BR-18 | 方案等級以報價當下（SITTER 發起 T2 或報價 T3 的當下）的訂閱狀態即時判斷（不看 OWNER 發起時、不放 JWT）。報價當下保母訂閱鎖定 / 停權時，不論鎖定前等級，一律依 FREE 規則：新總額 = 系統計算值，加價或折扣任一 > 0 → 403 `PLAN_REQUIRED`；報價本身不擋。方案只在報價時判斷：已 `QUOTED` 的變更（例：PRO 時報了加價）在保母之後降級或鎖定時，OWNER 同意不重驗方案，依報價內容生效（PRD-023 BR-1） | BASELINE §2.2、§3.2「鎖定期間的變更計價」（CQ-66）；使用者決定 2026-11-21（本卡 CQ-7） |
| BR-19 | 系統不做容量 / 檔期控管：新增行程不檢查保母當日其他訂單，排不出來由保母拒絕 | doc-conventions 已定案決策；沿用原 PRD-016 |
| BR-20 | 本卡不改 `orders.status`、行程、`total_amount`、payment；減少的行程於 OWNER 同意時才取消（PRD-023） | BASELINE §3.1、§3.4、CQ-1、CQ-32 |
| BR-21 | 變更不影響保母月接單額度（額度只在 O5 計數） | BASELINE §2.2、CQ-5 |
| BR-22 | 所有寫入端點帶 `Idempotency-Key`（缺 → 400 `VALIDATION_FAILED`，`field=Idempotency-Key`）；同 key 同內容重送 → 回放第一次的狀態碼與 body；同 key 不同內容 → 422 `IDEMPOTENCY_KEY_REUSED`；處理中 → 409 `IDEMPOTENCY_IN_PROGRESS`；不同 key 重做已完成的動作 → 依當下狀態回 409（例：已 `QUOTED` 再報價 → `MODIFICATION_STATE_CONFLICT`），不改寫資料 | BASELINE §3.6、§3.7；沿用原 PRD-016「重送已完成的動作回 409」 |
| BR-23 | 金額為整數 TWD；時間 UTC 儲存、ISO-8601 傳輸、畫面以 Asia/Taipei `YYYY-MM-DD HH:mm` 顯示 | BASELINE §4、§5 |
| BR-24 | 變更內容不得為空：`CHANGE` 沒有任何減少也沒有任何新增 → 422 `MODIFICATION_EMPTY`（模組碼），不建立 / 不改變更（T1、T2、T3 皆檢查）；報價（T3）時 `CANCEL_ALL` 但已無 `PENDING` 行程（`REQUESTED` 期間全部被開始）→ 同碼，變更維持 `REQUESTED`，SITTER 只能拒絕或等逾時。發起時已無 `PENDING` 行程的情況先由 BR-2 回 409 `MODIFICATION_NOT_ALLOWED` | 使用者決定 2026-11-21（本卡 CQ-2）；BASELINE CQ-69 |
| BR-25 | 新增行程只能選訂單快照內的方案（才有快照單價可計價）；選其他方案 → 422 `PLAN_NOT_IN_ORDER`（模組碼） | BASELINE §2.2 快照；使用者決定 2026-11-21（本卡 CQ-3） |
| BR-26 | 每筆新增行程至少綁定 1 隻毛孩，且只能從本訂單已綁定的毛孩中選；畫面預設全選。OWNER 發起時選；SITTER 報價時預填 OWNER 的選擇，只能在本訂單毛孩範圍內調整（SITTER 發起時同此範圍）；未綁或綁了訂單外的毛孩 → 400 `VALIDATION_FAILED`（payload 指出欄位） | BASELINE O1（CQ-60）；使用者決定 2026-11-21（本卡 CQ-9） |
| BR-27 | 數量上限：新增行程每日次數 1–3；同一方案同一日期，新增次數與保留的有效行程合計 ≤ 3；變更後訂單有效行程的日期總數 ≤ 60；違反 → 400 `VALIDATION_FAILED`（payload 指出欄位） | PRD-005 BR-18；使用者決定 2026-11-21（本卡 CQ-10） |
| BR-28 | 多個錯誤同時成立時依 BASELINE §3.7 判斷順序（CQ-70、CQ-76）回第一個。本卡對應：第 5 步 403 `FORBIDDEN` 含「當事人但非該變更的發起者」（撤回）；第 6 步 400 含缺 key、BR-5、BR-6 行程不屬本訂單、BR-8、BR-10、BR-11、BR-26、BR-27；第 9 步 `MODIFICATION_IN_PROGRESS` → `MODIFICATION_NOT_ALLOWED` → `MODIFICATION_STATE_CONFLICT`；第 11 步 `VISIT_ALREADY_STARTED`；第 12 步 422 `INVALID_VISIT_DATE`、`MODIFICATION_EMPTY`、`PLAN_NOT_IN_ORDER`、`INVALID_MODIFICATION_AMOUNT`；第 13 步 `PLAN_REQUIRED`。例：`DISPUTED` 且有 `AWAITING_REFUND` 的訂單發起 → `ORDER_STATE_CONFLICT`；任一方撤回 `initiatedBy=SYSTEM` 的變更 → 403 `FORBIDDEN`（非發起者）；發起者撤回自己已 `AWAITING_*` 的變更 → `MODIFICATION_STATE_CONFLICT`。PRD-023、PRD-024 的變更相關端點同此順序 | BASELINE §3.7（CQ-70、CQ-76） |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 非該訂單當事人操作任何本卡端點 | 拒絕，不改資料 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | 訂單 / 變更不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此訂單」 |
| EX-3 | token 角色不符（OWNER 角色呼叫報價、SITTER 角色呼叫飼主發起、ADMIN 呼叫任何寫入端點） | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換為保母 / 飼主身分後再操作」 |
| EX-4 | 非發起者撤回變更（任何狀態；`initiatedBy=SYSTEM` 的變更任何人都不是發起者） | 拒絕 | 403 `FORBIDDEN` | 「只有提出者可以撤回」 |
| EX-5 | FREE / BASIC，或訂閱鎖定 / 停權中的保母（不論鎖定前等級），報價的加價或折扣任一 > 0 | 拒絕，變更維持原狀態 | 403 `PLAN_REQUIRED` | 「自訂變更金額需專業版以上」；鎖定中另顯示「訂閱已逾期，變更金額依系統計算」 |
| EX-6 | 訂單不在 `CONFIRMED` / `IN_PROGRESS`（含 `DISPUTED` 凍結） | 拒絕，不建立 / 不改變更 | 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`） | 「此訂單目前無法變更」 |
| EX-7 | 已有未結束變更 | 拒絕 | 409 `MODIFICATION_IN_PROGRESS` | 「此訂單已有進行中的變更請求」 |
| EX-8 | 變更狀態不允許（已報價再報價、SITTER 拒絕或改價 `QUOTED`、發起者撤回 `AWAITING_*` / 終態的變更；`initiatedBy=SYSTEM` 的變更見 EX-4） | 拒絕，不改資料 | 409 `MODIFICATION_STATE_CONFLICT`（BASELINE §3.7） | 「此變更請求狀態已更新，請重新整理」；SYSTEM 退款變更顯示「此退款由系統建立，無法撤回」 |
| EX-9 | 已過 `expires_at`、排程尚未寫 `EXPIRED` | 拒絕 | 409 `DEADLINE_PASSED`（payload `deadline=MODIFICATION_EXPIRES_AT`、`deadlineAt`） | 「此變更請求已逾期」 |
| EX-10 | 減少目標已非 `PENDING`（保母已開始、系統已關閉） | 拒絕 | 409 `VISIT_ALREADY_STARTED`（payload 帶行程 id） | 「部分行程已開始，無法取消」 |
| EX-11 | 新增行程日期 < 明天（發起或報價時），或保母當下為 PRO / ULTIMATE 未鎖定且日期不在方案可預約區間 | 拒絕，不建立 / 不改變更 | 422 `INVALID_VISIT_DATE`（不在區間時 payload `reason=PLAN_NOT_IN_RANGE`） | 「新增日期需為明天以後，且在方案開放日期內」 |
| EX-12 | 新總額 < 0 | 拒絕 | 422 `INVALID_MODIFICATION_AMOUNT`（模組碼） | 「新總額不可小於 0」 |
| EX-13 | 跨角色併發（OWNER 撤回與 SITTER 報價同時；SITTER 開始行程與 SITTER / OWNER 送出含該行程的變更同時） | 鎖訂單列序列化；後到者依新狀態重驗 | 後到者 409 `MODIFICATION_STATE_CONFLICT` / `VISIT_ALREADY_STARTED` | 同 EX-8 / EX-10 |
| EX-14 | 鎖等待逾時 | 不改資料 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「訂單處理中，請稍後再試」（前端自動重試一次） |
| EX-15 | 重複送出（同 `Idempotency-Key`） | 回放第一次結果，不重複建立 | 同第一次（例 201） | 同第一次 |
| EX-16 | 同 key 不同內容 / 同 key 處理中 | 拒絕 | 422 `IDEMPOTENCY_KEY_REUSED` / 409 `IDEMPOTENCY_IN_PROGRESS` | 「請重新送出」 |
| EX-17 | Resend 寄信失敗 | 轉移照常提交；站內通知已同交易寫入；Email 由排程重試最多 5 次，4xx 直接判失敗 | 原請求 2xx | 站內通知正常出現 |
| EX-18 | GCS | 不適用：本卡不上傳檔案（憑證見 PRD-007） | — | — |
| EX-19 | 欄位格式或上限錯誤（發起 / 拒絕 / 調整原因空白或 > 200 字；日期格式錯；每日次數不在 1–3、同方案同日合計 > 3、日期總數 > 60；加價 / 折扣不在 0–999,999、新總額 > 999,999；新增行程未綁毛孩或綁訂單外毛孩；行程不屬本訂單；缺 `Idempotency-Key`） | 拒絕，不建立 / 不改變更 | 400 `VALIDATION_FAILED`（payload 指出欄位） | 欄位下方錯誤提示 |
| EX-20 | 排程處理單筆失敗 | 該筆回滾，其他筆照常；下輪重跑 | — | — |
| EX-21 | 變更內容為空（`CHANGE` 無增減；報價時 `CANCEL_ALL` 但已無 `PENDING` 行程） | 拒絕，不建立 / 不改變更 | 422 `MODIFICATION_EMPTY`（模組碼） | 發起時「請至少選擇一個要減少或新增的行程」；報價時「此訂單已無可取消的行程，請拒絕此變更」 |
| EX-22 | 新增行程選了訂單快照以外的方案 | 拒絕 | 422 `PLAN_NOT_IN_ORDER`（模組碼） | 「新增行程只能選擇本訂單已有的方案」 |
| EX-23 | 發起時訂單已無 `PENDING` 行程 | 拒絕，不建立變更 | 409 `MODIFICATION_NOT_ALLOWED` | 「所有行程皆已開始，無法再申請變更」 |
| EX-24 | 同一請求同時違反多條規則 | 依 BR-28 順序回第一個，不改資料 | 依 BR-28 | 依該錯誤 |

## 8. 驗收標準（AC）

> 共用前提（除非另寫）：保母方案 P 單價快照 500；訂單 3 個行程 V1、V2、V3（方案 P、各 1 次、各 500，日期互不相同），綁定毛孩 M1、M2，`total_amount` = 1500，首次收款 VERIFIED，已收淨額 = 1500；保母 BASIC 未鎖定。時間皆為 Asia/Taipei。

### 子模組 1：發起變更

- [ ] **AC-1.1 (飼主發起 CHANGE)**：Given `IN_PROGRESS` 訂單（V1 `DONE`，V2、V3 `PENDING`），When OWNER 於 2026-11-21 10:00 送出 `CHANGE`（減少 V3、新增 2026-11-28 方案 P 一次、毛孩維持預設、原因「提早回國」），Then 201，變更 `status=REQUESTED`、`initiatedBy=OWNER`、`expires_at=2026-11-24T02:00:00Z`、期望內容的新增行程綁定 M1、M2；訂單仍為 `IN_PROGRESS`、V3 仍 `PENDING`、`total_amount` 仍 1500；`order_logs` 新增一筆；SITTER 收到站內通知「飼主提出訂單變更」。對應：T1、BR-1、BR-4、BR-16、BR-20、BR-26
- [ ] **AC-1.2 (飼主發起 CANCEL_ALL 與原因)**：Given `CONFIRMED` 訂單，When OWNER 送出 `CANCEL_ALL`、原因「行程取消」，Then 201，變更 `type=CANCEL_ALL`、`status=REQUESTED`，三個行程仍 `PENDING`。Given 同一訂單，When 原因空白或 201 字，Then 400 `VALIDATION_FAILED`、不建立變更；When 原因 200 字，Then 201。對應：T1、BR-1、BR-5、EX-19
- [ ] **AC-1.3 (保母發起即報價)**：Given `CONFIRMED` 訂單、BASIC 保母，When SITTER 送出 `CHANGE`（減少 V3、原因「臨時住院」），Then 201，變更 `status=QUOTED`、`initiatedBy=SITTER`、新總額 = 1000、差額 = −500，`expires_at` = 送出時間 + 72h；OWNER 收到通知且協商頁出現「同意變更」「拒絕變更」。對應：T2、BR-4、BR-9、BR-11
- [ ] **AC-1.4 (不可發起)**：Given 訂單分別為 `PENDING`、`PENDING_PAYMENT`、`COMPLETED`、`CANCELLED`，When OWNER 或 SITTER 發起變更，Then 409 `ORDER_STATE_CONFLICT`（payload `currentStatus` = 該狀態）。Given `DISPUTED` 訂單且有一筆 `AWAITING_REFUND` 變更，When 任一方發起，Then 409 `ORDER_STATE_CONFLICT`（不回 `MODIFICATION_IN_PROGRESS`）。Given `IN_PROGRESS` 訂單已無 `PENDING` 行程（V1、V3 `DONE`，V2 `CLOSED_BY_SYSTEM`）且無未結束變更，When OWNER 發起 `CANCEL_ALL` 或只含新增 2026-11-28 的 `CHANGE`，Then 409 `MODIFICATION_NOT_ALLOWED`。以上 `modification_requests` 皆無新增列。對應：BR-2、BR-3、BR-28、EX-6、EX-23
- [ ] **AC-1.5 (同時只能一筆未結束變更)**：Given 訂單已有一筆 `REQUESTED`、`QUOTED`、`AWAITING_SUPPLEMENT` 或 `AWAITING_REFUND` 變更，When 任一方再發起，Then 409 `MODIFICATION_IN_PROGRESS`、無新增列。Given 前一筆為 `REJECTED`、`WITHDRAWN`、`EXPIRED` 或 `SETTLED` 且仍有 `PENDING` 行程，When 再發起，Then 201。對應：BR-2、EX-7
- [ ] **AC-1.6 (減少目標與空內容)**：Given V1 `DONE`、V2 `IN_PROGRESS`、V3 `PENDING`，When OWNER 送出減少 V1 或 V2 的 `CHANGE`，Then 409 `VISIT_ALREADY_STARTED`（payload 含該行程 id）。Given 同一訂單，When 減少其他訂單的行程，Then 400 `VALIDATION_FAILED`。Given 同一訂單，When 送出無任何減少與新增的 `CHANGE`，Then 422 `MODIFICATION_EMPTY`。以上皆不建立變更。對應：T1、BR-6、BR-24、EX-10、EX-19、EX-21
- [ ] **AC-1.7 (越權與角色)**：Given 訂單屬於 OWNER-A / SITTER-S，When OWNER-B 發起，Then 403 `FORBIDDEN`；When 以不存在的訂單 id 發起，Then 404 `NOT_FOUND`；When 帳號 S 以 `currentRole=OWNER` 呼叫保母發起或報價端點、帳號 A 以 `currentRole=OWNER` 呼叫報價端點、或 ADMIN 呼叫任一寫入端點，Then 403 `ROLE_MISMATCH`；以上皆無新增變更、既有變更狀態不變。對應：§3、EX-1~EX-3
- [ ] **AC-1.8 (新增行程的毛孩、數量與方案)**：Given `CONFIRMED` 訂單，When OWNER 新增一筆行程但未綁毛孩、或綁定訂單外的毛孩 M3，Then 400 `VALIDATION_FAILED`（payload 指出毛孩欄位）。When 新增每日次數 4，或於 V2 同日同方案新增 3 次（與 V2 合計 4），或新增後有效行程日期總數 61，Then 400 `VALIDATION_FAILED`。When 新增行程選訂單快照以外的方案 Q，Then 422 `PLAN_NOT_IN_ORDER`。When 新增 V2 同日同方案 2 次（合計 3）、毛孩只選 M1，Then 201、該新增行程綁定 M1。以上失敗皆不建立變更。對應：T1、BR-25、BR-26、BR-27、EX-19、EX-22

### 子模組 2：保母報價與拒絕

- [ ] **AC-2.1 (FREE / BASIC 系統計價)**：Given AC-1.1 的 `REQUESTED` 變更（減少 V3、新增 1 次）、BASIC 保母，When SITTER 送出報價（不帶加減、毛孩維持預填），Then 200，`status=QUOTED`，新總額 = 1500（V1 + V2 + 新增 = 3 × 500），差額 = 0，畫面顯示「無差額」；`expires_at` 重設為報價時間 + 72h；OWNER 收到通知「保母已回覆變更報價」。對應：T3、BR-9、BR-12、BR-16
- [ ] **AC-2.2 (FREE / BASIC 不可自訂)**：Given FREE 或 BASIC 保母、`REQUESTED` 變更，When 報價帶加價 200 或折扣 100，Then 403 `PLAN_REQUIRED`，變更仍為 `REQUESTED`、`expires_at` 不變。When 報價帶加價 0 與折扣 0，Then 200、`QUOTED`、新總額 = 系統計算值。When 報價帶加價 200 且新增日期為今天，Then 422 `INVALID_VISIT_DATE`（先於 `PLAN_REQUIRED`）。對應：BR-9、BR-28、EX-5
- [ ] **AC-2.3 (PRO 自訂並附原因)**：Given PRO 保母、`CONFIRMED` 訂單的 `REQUESTED` 變更（減少 V3、新增 2 次，系統計算值 2000）。When 報價加價 300、原因「節日加成」，Then 200，新總額 = 2300、差額 = +800，畫面顯示「應補款 NT$800」。When 加價 300、原因空白，Then 400 `VALIDATION_FAILED`、仍 `REQUESTED`。When 折扣 2000、附原因，Then 200，新總額 = 0、畫面顯示「應退款 NT$1500」。When 折扣 2001、附原因，Then 422 `INVALID_MODIFICATION_AMOUNT`、仍 `REQUESTED`。對應：BR-10、BR-11、EX-12、EX-19
- [ ] **AC-2.4 (計價範圍含 DONE 與 CLOSED_BY_SYSTEM)**：Given `IN_PROGRESS` 訂單 V1 `DONE`、V2 `CLOSED_BY_SYSTEM`、V3 `PENDING` 的 `CANCEL_ALL`、BASIC 保母，When 報價，Then 200，減少目標 = {V3}、新總額 = 1000、差額 = −500（「應退款 NT$500」）。Given `CONFIRMED` 訂單（三個皆 `PENDING`）的 `CANCEL_ALL`，When 報價，Then 200，新總額 = 0、差額 = −1500。Given `CANCEL_ALL` 於 `REQUESTED` 期間最後一個 `PENDING` 行程已被開始，When 報價，Then 422 `MODIFICATION_EMPTY`、變更仍 `REQUESTED`、畫面顯示「此訂單已無可取消的行程，請拒絕此變更」。對應：BR-7、BR-12、BR-24、EX-21
- [ ] **AC-2.5 (新增日期下限與方案區間)**：Given 2026-11-21 23:30，When OWNER 發起或 SITTER 報價新增 2026-11-21，Then 422 `INVALID_VISIT_DATE`、不建立 / 不改變更；When 新增 2026-11-22，Then 201 / 200。Given PRO 未鎖定保母，方案 P 可預約區間 2026-12-01 ~ 2026-12-31、原訂單行程皆在 11 月，When 報價新增 2026-11-30，Then 422 `INVALID_VISIT_DATE`（payload `reason=PLAN_NOT_IN_RANGE`）、仍 `REQUESTED`；When 新增 2026-12-05，Then 200（原 11 月日期不被檢查）。Given 同一區間但報價當下保母為 BASIC，或為訂閱鎖定 / 停權中的 PRO，When 報價新增 2026-11-30，Then 200、`QUOTED`。對應：T1、T3、BR-13、BR-14、EX-11
- [ ] **AC-2.6 (保母拒絕)**：Given `REQUESTED` 變更，When SITTER 點「拒絕變更」並填原因「當週無法服務」，Then 200，`status=REJECTED`、結束時間 = 拒絕時間；行程、`total_amount`、訂單狀態不變；OWNER 收到通知含拒絕原因；之後雙方可再發起新變更（201）。When 拒絕原因空白，Then 400 `VALIDATION_FAILED`、仍 `REQUESTED`。Given OWNER 發起且已 `QUOTED`，When SITTER 填原因呼叫拒絕，Then 409 `MODIFICATION_STATE_CONFLICT`、仍 `QUOTED`。Given OWNER 已拒絕該報價（PRD-023），When SITTER 以新內容與金額發起變更，Then 201、新變更 `QUOTED`、`initiatedBy=SITTER`。對應：T2、T4、BR-8、EX-8、EX-19
- [ ] **AC-2.7 (金額與原因上限)**：Given PRO 保母、`REQUESTED` 變更（減少 V3，系統計算值 1000）。When 加價 1,000,000，Then 400 `VALIDATION_FAILED`。When 加價 999,000（新總額 1,000,000）附原因，Then 400 `VALIDATION_FAILED`。When 加價 998,999（新總額 999,999）附原因，Then 200、新總額 = 999,999。When 加價 100、原因 201 字，Then 400 `VALIDATION_FAILED`；原因 200 字，Then 200。以上失敗變更皆仍 `REQUESTED`。對應：BR-10、BR-11、EX-19
- [ ] **AC-2.8 (方案以報價當下判斷；鎖定依 FREE 計價)**：Given 保母於 OWNER 發起時為 PRO、報價前已降為 BASIC，When 報價帶加價 200，Then 403 `PLAN_REQUIRED`；When 不帶加減，Then 200、新總額 = 系統計算值。Given 發起時 BASIC、報價前升為 PRO，When 報價折扣 100 並附原因，Then 200、新總額 = 系統計算值 − 100。Given PRO 保母訂閱已鎖定（或被停權），When 對 `REQUESTED` 報價帶加價 200，Then 403 `PLAN_REQUIRED`、仍 `REQUESTED`；When 不帶加減，Then 200、`QUOTED`、新總額 = 系統計算值（不回 403 `SUBSCRIPTION_LOCKED`）。對應：BR-9、BR-10、BR-17、BR-18、EX-5

### 子模組 3：撤回、逾時、協商頁與冪等

- [ ] **AC-3.1 (飼主撤回)**：Given OWNER 發起的變更為 `REQUESTED` 或 `QUOTED`，When OWNER 於 2026-11-22 09:00 點「撤回請求」，Then 200，`status=WITHDRAWN`、結束時間 = `2026-11-22T01:00:00Z`；行程、`total_amount`、訂單狀態不變；SITTER 收到通知「飼主已撤回變更請求」。對應：T5
- [ ] **AC-3.2 (撤回權限與不可撤回狀態)**：Given SITTER 發起的 `QUOTED` 變更，When SITTER 撤回，Then 200、`WITHDRAWN`、記錄結束時間、OWNER 收到通知；When OWNER 呼叫撤回，Then 403 `FORBIDDEN`、仍 `QUOTED`。Given 變更為 `AWAITING_SUPPLEMENT` 或 `AWAITING_REFUND`，When 發起者撤回，Then 409 `MODIFICATION_STATE_CONFLICT`；When 另一方撤回，Then 403 `FORBIDDEN`。Given `initiatedBy=SYSTEM` 的退款變更，When OWNER 或 SITTER 撤回，Then 403 `FORBIDDEN`。Given 變更為 `SETTLED`、`REJECTED`、`WITHDRAWN` 或 `EXPIRED`，When 發起者撤回，Then 409 `MODIFICATION_STATE_CONFLICT`。以上失敗資料皆不變。對應：T5、BR-28、EX-4、EX-8
- [ ] **AC-3.3 (逾時排程)**：Given `REQUESTED` 變更 `expires_at=2026-11-24T02:00:00Z`，When 排程於 2026-11-24 10:00 之後第一次執行（≤ 15 分鐘內），Then `status=EXPIRED`、結束時間 = 排程寫入時間、寫 `order_logs`（操作者 SYSTEM）、雙方收到通知「變更請求已逾期」；訂單狀態與行程不變。Given `QUOTED` 變更已過 `expires_at`，When 排程執行，Then 同上。Given 排程候選列在鎖住時已是 `EXPIRED`（重跑），When 處理該筆，Then 略過，`order_logs` 與通知不重複。對應：T6、BR-15、BR-16、EX-20
- [ ] **AC-3.4 (期限以時間判斷)**：Given `REQUESTED` 變更已過 `expires_at=2026-11-24T02:00:00Z` 但排程尚未執行（狀態仍 `REQUESTED`），When SITTER 報價、SITTER 拒絕或 OWNER 撤回，Then 409 `DEADLINE_PASSED`（payload `deadline=MODIFICATION_EXPIRES_AT`、`deadlineAt=2026-11-24T02:00:00Z`），狀態不變。Given `QUOTED` 變更已過 `expires_at` 且排程未執行，When 發起者撤回，Then 同一 409 與 payload。兩種情況協商頁皆隱藏動作按鈕、顯示「此變更請求已逾期」。對應：BR-15、EX-9
- [ ] **AC-3.5 (協商頁可執行動作與歷史)**：Given §4.6 每一列的狀態與發起者，When OWNER 與 SITTER 分別開啟協商頁，Then 只出現該列列出的按鈕（例：OWNER 發起的 `QUOTED`，SITTER 頁面沒有「送出報價」「拒絕變更」，只顯示報價金額與到期時間）；終態只顯示唯讀結果。Given 訂單有 3 筆已結束變更，When 開啟變更歷史，Then 依 `created_at` 倒序列出 3 筆；Given 訂單沒有任何變更，Then 顯示「尚無變更紀錄」。對應：§4.6
- [ ] **AC-3.6 (跨角色併發)**：Given OWNER 發起的 `REQUESTED` 變更，When OWNER 撤回與 SITTER 報價同時送出，Then 恰有一個成功；另一個 409 `MODIFICATION_STATE_CONFLICT`，最終狀態為 `WITHDRAWN` 或 `QUOTED` 其一，`order_logs` 只有成功那一筆；鎖等待超過 3 秒的請求 → 409 `ORDER_BUSY` + `Retry-After: 1`。對應：EX-13、EX-14
- [ ] **AC-3.7 (發起與報價冪等)**：Given OWNER 以 `Idempotency-Key=K1` 發起成功（201），When 同 key 同內容重送，Then 201 與相同 `modificationId`、只有一筆變更；When 同 key 不同內容，Then 422 `IDEMPOTENCY_KEY_REUSED`；When 換 key 重送，Then 409 `MODIFICATION_IN_PROGRESS`。Given SITTER 以 K2 報價成功、變更 `QUOTED`，When 同 key 同內容重送，Then 回放 200 與相同 body；When 換 key 再報價，Then 409 `MODIFICATION_STATE_CONFLICT`、新總額不變。Given 呼叫發起端點未帶 key，Then 400 `VALIDATION_FAILED`（`field=Idempotency-Key`）。對應：BR-22、EX-15、EX-16
- [ ] **AC-3.8 (寄信失敗不影響轉移)**：Given Resend 回 5xx，When OWNER 發起變更，Then 201、變更 `REQUESTED` 已提交、SITTER 站內通知已存在；Email 由排程重試，最多 5 次。對應：EX-17

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | `modification_requests`（SD-ORDER）：類型、發起者（OWNER / SITTER / SYSTEM）、狀態、原因、期望內容（OWNER）、報價內容（減少的行程 id、新增行程的日期 / 方案 / 每日次數 / 綁定毛孩）、系統計算值、加價 / 折扣與原因、新總額、報價當下的已收淨額與差額（同意時以 PRD-023 重算值回寫）、`expires_at`、結束時間（每個終態都記；只有 `SETTLED` 的結束時間供 `completion_basis_at`，CQ-69）、拒絕原因；`order_logs` 每次轉移一筆。本卡不改 `orders`、`visits`、`payments` |
| 畫面 | 訂單詳情「申請變更 / 提出變更」入口（OWNER / SITTER；無 `PENDING` 行程時隱藏入口）；變更精靈（選類型、選行程、新增日期 / 次數 / 毛孩（預設全選），保母當下為 PRO / ULTIMATE 未鎖定時顯示方案可預約區間）；協商頁（雙方，§4.6）；變更歷史列表（`created_at` 倒序、空狀態）；ADMIN 訂單唯讀頁顯示變更歷史。路徑由 SD-FRONTEND-SPEC 定 |
| 通知 | 發起 → 對方；報價 → OWNER；拒絕 → OWNER；撤回 → 對方；逾時 → 雙方。管道：站內 + Email（outbox，BASELINE §3.6；沿用原 PRD-016「請求送出、保母回覆、最終確認皆需通知」；範本見 PRD-014） |
| 排程 | 有：變更逾時（每 15 分鐘，`now ≥ expires_at`，鎖住後重驗，BASELINE §3.6、§5） |
| API | 飼主發起、保母發起、保母報價、保母拒絕、撤回、查詢變更（含歷史，`created_at` 倒序）；契約在 SD-ORDER |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | BASELINE CQ-49 只規定「報價時」新增日期 ≥ 明天；OWNER 發起（`REQUESTED`）時要不要也擋 | 發起時同樣檢查（422 `INVALID_VISIT_DATE`），報價時再檢查一次；避免飼主送出必被退回的請求。已寫入 BR-13、T1、EX-11、AC-2.5 | 已決（使用者 2026-11-21） |
| CQ-2 | 變更內容為空要不要擋：`CHANGE` 沒有任何減少或新增；`CANCEL_ALL` 但訂單已無 `PENDING` 行程 | 擋，422 `MODIFICATION_EMPTY`（模組碼），不建立變更。發起時無 `PENDING` 的情況改由 BASELINE CQ-69 的 409 `MODIFICATION_NOT_ALLOWED` 先擋（BR-2），`MODIFICATION_EMPTY` 保留給空 `CHANGE` 與報價時的 `CANCEL_ALL`。已寫入 BR-24、T1~T3、EX-21、AC-1.6、AC-2.4 | 已決（使用者 2026-11-21） |
| CQ-3 | 新增行程能否使用訂單快照以外的方案（沒有快照單價可算） | 不可，只能選訂單快照內的方案；違反 → 422 `PLAN_NOT_IN_ORDER`（模組碼）。已寫入 BR-25、T1~T3、EX-22、AC-1.8 | 已決（使用者 2026-11-21） |
| CQ-4 | 方案可預訂區間的錯誤碼：原 PRD-016 用 `PLAN_NOT_IN_RANGE`，BASELINE §3.7 沒有此碼；另外保母降到 FREE / BASIC 後，舊方案區間是否仍生效 | 錯誤碼：422 `INVALID_VISIT_DATE`，payload `reason=PLAN_NOT_IN_RANGE`（維持）。「方案等級不影響」部分已由 CQ-8 取代。已寫入 BR-14、EX-11、AC-2.5 | 已決（使用者 2026-11-21；等級部分由 CQ-8 取代） |
| CQ-5 | 保母拒絕變更是否必填原因 | 必填（與 O3 婉拒一致），通知帶給飼主。已寫入 BR-8、T4、EX-19、AC-2.6 | 已決（使用者 2026-11-21） |
| CQ-6 | 原 PRD-016 寫「保母報價後不可再報價（仍可拒絕）」，BASELINE §3.4 狀態圖沒有 SITTER 在 `QUOTED` 的拒絕 / 撤回（OWNER 發起時） | 依 BASELINE：OWNER 發起的 `QUOTED`，SITTER 不能拒絕或改價；SITTER 要改，等該變更結束（OWNER 拒絕、撤回或逾時）後重新發起。已寫入 §3、§4.3-6、§4.6、BR-8、EX-8、AC-2.6 | 已決（使用者 2026-11-21） |
| CQ-7 | 保母訂閱鎖定（逾期）時的計價方式：鎖定前是 PRO，是否還能自訂新總額 | 鎖定 / 停權期間依 FREE 規則：只能用系統計算值，帶加減 → 403 `PLAN_REQUIRED`；報價本身不擋（BASELINE §3.2、CQ-66）。已寫入 §3、§4.3-3、BR-18、EX-5、AC-2.8 | 已決（使用者 2026-11-21） |
| CQ-8 | 方案可預訂區間在保母為 FREE / BASIC 或訂閱鎖定時，是否仍限制變更的新增行程（CQ-4 原決定「不看等級」，與 PRD-005 BR-8、BASELINE §2.1 相反） | 對齊 BASELINE §2.1（CQ-68）/ PRD-005：發起與報價當下保母為 PRO / ULTIMATE 且未鎖定才套用區間；FREE / BASIC / 鎖定 / 停權一律常態開放；AC-2.5 降級案例改為 200。已寫入 §3、§4.1-3、§4.3-2、BR-14、EX-11、AC-2.5 | 已決（使用者 2026-11-21） |
| CQ-9 | 新增行程綁定哪些毛孩（BASELINE O1 / CQ-60 要求每個日期群組至少 1 隻） | 必須綁定 ≥ 1 隻、只能從本訂單已綁定的毛孩中選，畫面預設全選；OWNER 發起時選，SITTER 報價時預填且只能在訂單毛孩範圍內調整；違反 → 400 `VALIDATION_FAILED`。已寫入 §4.1-1、§4.3-2、BR-26、EX-19、AC-1.1、AC-1.8 | 已決（使用者 2026-11-21） |
| CQ-10 | 變更的數值與數量上限（加價 / 折扣 / 新總額、原因長度、新增行程數量） | 加價、折扣各 0–999,999 整數，新總額 ≤ 999,999；發起 / 拒絕 / 調整原因去前後空白後 1–200 字；新增行程每日次數 1–3，同方案同日與保留的有效行程合計 ≤ 3；變更後日期總數 ≤ 60；違反 → 400 `VALIDATION_FAILED`。已寫入 §4.1-2、§4.3-3、§4.3-5、BR-5、BR-8、BR-10、BR-11、BR-27、EX-19、AC-1.2、AC-1.8、AC-2.7 | 已決（使用者 2026-11-21） |
| CQ-11 | BASELINE §3.7 判斷順序把 `MODIFICATION_*` 列為同一層，且沒有列 `VISIT_ALREADY_STARTED`：(a) 有未結束變更且已無 `PENDING` 行程時（例：`IN_PROGRESS` 整筆取消等退款中），回 `MODIFICATION_IN_PROGRESS` 還是 `MODIFICATION_NOT_ALLOWED`；(b) `VISIT_ALREADY_STARTED` 與 `DEADLINE_PASSED`、422 同時成立時誰先 | (a) `MODIFICATION_IN_PROGRESS` 先（使用者等該筆結束即可知道下一步）→ `MODIFICATION_NOT_ALLOWED` → `MODIFICATION_STATE_CONFLICT`；(b) `VISIT_ALREADY_STARTED` 放在 `DEADLINE_PASSED` 之後、422 之前（期限過了不必再看內容）。暫依此寫入 BR-28；確認後建議回寫 BASELINE §3.7 | 已決（主流程 2026-11-22，baseline CQ-76）：採建議順序，已寫入 baseline §3.7 |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-22 | 狀態改 Approved | 使用者核准 |
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
| 2026-11-21 | 第 1 輪 #55：重送分兩種——同 `Idempotency-Key` 回放第一次結果；不同 key 重做已完成動作依狀態回 409（BR-22、AC-3.7） | 第 1 輪 #55、BASELINE §3.6 |
| 2026-11-21 | 第 2 輪 PRD 衝突 #6：變更終態補 `WITHDRAWN`、`EXPIRED`（T5、T6、AC-3.1~3.4）；新增撤回、72h 逾時、期限以時間判斷 | 第 2 輪 #6、CQ-15、CQ-46 |
| 2026-11-21 | 其他：新增行程日期 ≥ 明天（BR-13，CQ-49）；方案可預訂區間錯誤碼改用 baseline 碼並列 CQ-4；原「線上支付自動退刷」移出（線上退款未在 BASELINE 定義，見 PRD-015）；原「帳務總覽以變更後金額為準」改依 BASELINE §4（收入 = 已收淨額，見 PRD-009） | BASELINE §3.4、§4 |
| 2026-11-21 | 待釐清 CQ-1~7 全部依建議預設值定案並寫入正文：發起時也檢查新增日期（BR-13）；新增 BR-24 `MODIFICATION_EMPTY`、BR-25 `PLAN_NOT_IN_ORDER`（模組碼）與 EX-21、EX-22；區間錯誤碼 payload `reason=PLAN_NOT_IN_RANGE`、方案等級不影響（BR-14）；拒絕原因必填、`QUOTED` 後 SITTER 不能拒絕或改價（BR-8、§4.3-6）；鎖定 / 停權依 FREE 規則計價（BR-18，BASELINE CQ-66）；`MODIFICATION_STATE_CONFLICT` 改標 BASELINE §3.7 錯誤碼（EX-8）；AC-1.2、AC-1.6、AC-2.4~2.6、AC-2.8 擴充，AC 總數不變 | 使用者決定 2026-11-21；DOMAIN-BASELINE 第 2 版修訂（CQ-66、§3.7） |
| 2026-11-21 | 審查第 1 輪 #1（阻擋）：BR-14 改為只在發起 / 報價當下保母為 PRO / ULTIMATE 且未鎖定時套用方案區間，FREE / BASIC / 鎖定一律常態開放；AC-2.5 降級案例改為 200；§3、§4.1-3、§4.3-2、EX-11 同步；CQ-8 已決，CQ-4 標註等級部分被取代 | 審查第 1 輪 #1；使用者決定 2026-11-21（CQ-8）；BASELINE CQ-68 |
| 2026-11-21 | 審查第 1 輪 #2：新增 BR-28 判斷順序，照 BASELINE §3.7（CQ-70），注意 400 `VALIDATION_FAILED` 依 baseline 排在 `ORDER_STATE_CONFLICT` 之前（審查建議的順序與 baseline 不同，以 baseline 為準）；(a) `DISPUTED` + `AWAITING_REFUND` 發起 → `ORDER_STATE_CONFLICT`（AC-1.4）；(b)(c) 撤回 `AWAITING_*` / SYSTEM 變更不論呼叫者 → 409 `MODIFICATION_STATE_CONFLICT`（§3、T5、EX-4、EX-8、AC-3.2）；T1~T4 錯誤碼欄補 400 並依判斷順序排列；同層先後與 `VISIT_ALREADY_STARTED` 位置列 CQ-11 | 審查第 1 輪 #2；BASELINE §3.7 |
| 2026-11-21 | 審查第 1 輪 #3：新增 BR-26 新增行程綁定毛孩（≥ 1 隻、限本訂單毛孩、預設全選）；§4.1-1、§4.3-2、§9、EX-19、AC-1.1、AC-1.8 | 審查第 1 輪 #3；使用者決定 2026-11-21（CQ-9） |
| 2026-11-21 | 審查第 1 輪 #4：加價 / 折扣 0–999,999、新總額 ≤ 999,999、原因 1–200 字（BR-5、BR-8、BR-10、BR-11）；新增 BR-27 數量上限；EX-19 擴充；新增 AC-2.7（金額與原因上限），AC-1.2、AC-1.8 補邊界 | 審查第 1 輪 #4；使用者決定 2026-11-21（CQ-10） |
| 2026-11-21 | 審查第 1 輪 #5：T4、T5、T6 附帶「記錄結束時間 = 轉移時間」，§4.3-5、§4.4、§4.5 同步；AC-2.6、AC-3.1、AC-3.2、AC-3.3 斷言結束時間。依 BASELINE 第 2 版 CQ-69，只有 `SETTLED` 的結束時間推遲 `completion_basis_at`（§5 註、§9）；審查建議「PRD-024 AC-2.7 加 `WITHDRAWN` 範例」改為斷言撤回不推遲（見 PRD-024） | 審查第 1 輪 #5；BASELINE CQ-69 |
| 2026-11-21 | 審查第 1 輪 #6：§3 ADMIN 可讀範圍改「`DISPUTED` 或有 OPEN 待辦的訂單」 | 審查第 1 輪 #6；BASELINE §1.2 |
| 2026-11-21 | 審查第 1 輪 #7：AC 重排（總數仍 24，子模組 8 / 8 / 8）——多情境 AC 改為每個 Given 各帶明確 Then（AC-1.4、1.6、2.3~2.6、2.8）；原 AC-1.6 的方案與日期情境移至 AC-1.8 / AC-2.5；原 AC-1.8、AC-2.7 冪等合併為 AC-3.7；原 AC-2.7 的角色檢查併入 AC-1.7；原 AC-3.3 併入 AC-3.2；騰出名額給 AC-1.8（毛孩與數量）、AC-2.7（金額上限）；子模組 3 改名「撤回、逾時、協商頁與冪等」 | 審查第 1 輪 #7 |
| 2026-11-21 | 審查第 1 輪 #8：BR-8、§4.3-6 改「須等該變更結束（OWNER 拒絕、OWNER 撤回或逾時）後」重新發起；延伸解讀 (a)：EX-21 報價時文案補「請拒絕此變更」（AC-2.4） | 審查第 1 輪 #8、延伸解讀 (a) |
| 2026-11-21 | 審查第 1 輪 #9：BR-9、BR-18、EX-5 寫明加價或折扣「任一 > 0」才算自訂，0 視為未帶（AC-2.2） | 審查第 1 輪 #9 |
| 2026-11-21 | 審查第 1 輪 #10：期限以時間判斷的 AC（新 AC-3.4）拆成 `REQUESTED` 逾期（報價、拒絕、撤回）與 `QUOTED` 逾期（撤回）兩個 Given；`DEADLINE_PASSED` payload 依 BASELINE CQ-71 帶 `deadline` 與 `deadlineAt`（BR-15、EX-9、T3） | 審查第 1 輪 #10；BASELINE CQ-71 |
| 2026-11-21 | 審查第 1 輪 #11：§4.5-1、T6 寫明重驗條件「鎖住後仍為 `REQUESTED` / `QUOTED` 且 `now ≥ expires_at` 才改 `EXPIRED`，否則略過」；AC-3.3 補重跑略過情境 | 審查第 1 輪 #11 |
| 2026-11-21 | 審查第 1 輪 #12：鎖定期間計價只在本卡定義與驗收（BR-17 改為「以本卡 BR-18、AC-2.8 為準，PRD-024 只引用」） | 審查第 1 輪 #12 |
| 2026-11-21 | 審查第 1 輪 #13：BR-18 補「方案只在報價時判斷；OWNER 同意時不重驗」（PRD-023 BR-1 同步） | 審查第 1 輪 #13 |
| 2026-11-21 | 審查第 1 輪 #14：變更歷史依 `created_at` 倒序、無變更時顯示「尚無變更紀錄」（§4.6、§9、AC-3.5） | 審查第 1 輪 #14 |
| 2026-11-21 | BASELINE CQ-69：BR-2 補「至少 1 個 `PENDING` 行程」，否則 409 `MODIFICATION_NOT_ALLOWED`（T1、EX-23、AC-1.4）；原 AC-1.2「`CANCEL_ALL` 已無 `PENDING` → 422」改為 409 `MODIFICATION_NOT_ALLOWED`；BR-24 `MODIFICATION_EMPTY` 限空 `CHANGE` 與報價時的 `CANCEL_ALL`；CQ-2 說明同步 | BASELINE 第 2 版 §3.4、CQ-69 |
| 2026-11-21 | 待釐清 CQ-8~10 已決並寫入正文；新增 CQ-11（判斷順序同層先後）Open；狀態 Needs Clarification → In Review | 使用者決定 2026-11-21；審查第 1 輪 |
