# PRD-009: 訂單結案、服務爭議回報與帳務總覽

| 項目 | 內容 |
|------|------|
| 狀態 | In Review |
| 建立日期 | 2026-05-09（2026-11-22 依 DOMAIN-BASELINE 第 2 版全面改寫） |
| 歸屬 SD | SD-FULFILL（畫面與流程，見 `docs/sd/00-SD-PLAN.md` §0）；**狀態轉移 API 見 SD-ORDER**：O11 = E25 `POST /api/orders/{orderId}/complete`、O13 = E26 `POST /api/orders/{orderId}/service-disputes`、O12 = 排程 C8、帳務總覽 = E30 `GET /api/orders/sitter/ledger`（使用者決定 2026-11-22） |
| 測試情境 | TS-009 |
| 相依 PRD | PRD-008（行程開始 / 結束、`CLOSED_BY_SYSTEM` 殭屍清理、`IN_PROGRESS` 行程逾時出口；O9）；PRD-022（照護日誌送出、`CARE_LOG_EDIT_HOURS`，決定「`DONE`（日誌已送出）」是否成立）；PRD-024（協商期間不可結案 / 不可回報、`completion_basis_at` 只看 `SETTLED` 變更，本卡實作其 §4.5 對 PRD-009 的要求）；PRD-016 / PRD-023（變更請求與結清）；PRD-020（ADMIN 待辦清單、O15 / O17 調解結案）；PRD-013（媒體保留自結案日起算）；PRD-014（通知偏好）；PRD-015（線上付款、撥款，第二階段） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.2（訂單可讀範圍、ADMIN 唯讀）；§2.1 / §2.2（方案矩陣、訂閱鎖定只擋新生意 CQ-19）；§3.1（主狀態、前端分頁、有效行程）；§3.2 O11、O12、O13（附帶動作與錯誤碼含通知，CQ-82）、`DISPUTED` 凍結（CQ-48、CQ-57）、期限以時間判斷（CQ-46）；§3.4 可發起時機、協商期間；§3.6（鎖、稽核、通知與不可關閉類別、冪等、排程、`admin_tasks`）；§3.7（錯誤碼、判斷順序第 9 步含 `VISITS_NOT_FINISHED`（CQ-82）、payload 共通欄位、`DISPUTE_WINDOW_ENDS_AT`）；§4（已收淨額、保母收入）；§5（`completion_basis_at`、`AUTO_COMPLETE_HOURS`、`DISPUTE_WINDOW_HOURS`、`CARE_LOG_EDIT_HOURS`、排程頻率）；§6（行程、照護日誌） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念（角色、SaaS 額度、訂單狀態、時間起算點）只能**引用** DOMAIN-BASELINE，不得在這裡重新定義；要改就改 baseline。
> 本卡定義使用者看到的行為與驗收；O11 / O12 / O13 的狀態轉移、排程與帳務查詢端點由 SD-ORDER 實作。行程生命週期屬 PRD-008，照護日誌屬 PRD-022，ADMIN 調解（O15 / O17）屬 PRD-020，本卡只寫「見 PRD-020」。

---

## 1. 背景與目的

訂單的行程全部結束後，需要一個明確的終點：飼主確認結案，或在爭議視窗過後由系統自動結案，訂單才會列入保母收入、成為「熟客」判斷依據、開始媒體保留倒數（BR §3.6 第 6 步）。飼主對服務有異議時，要能在視窗內回報問題，讓訂單停在 `DISPUTED` 交給 ADMIN，而不是被自動結案。保母需要一個以「已收淨額」計算的月帳務總覽，與實際收到的錢一致（BR §5.6.2、§6；BASELINE §4）。飼主需要在歷史分頁回看已結束的訂單（BR §2.2、§3.1）。

## 2. 使用者故事

- **US-1** 身為飼主，當保母送出所有行程的照護日誌後，我希望能立即確認結案，以便不用等待系統倒數。
- **US-2** 身為飼主，我希望在服務結束後的一段時間內能回報服務問題，以便平台介入、訂單不被自動結案。
- **US-3** 身為保母，我希望飼主沒有動作時訂單會在固定時間後自動結案，以便訂單不會永遠停在進行中。
- **US-4** 身為保母，我希望依月份查看已結束訂單實際收到的淨額與總收入，以便對帳。
- **US-5** 身為飼主，我希望在歷史分頁查看已結案與已取消的訂單，以便回看日誌與付款紀錄。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 確認結案（O11） | 本人訂單 | ✗（403 `ROLE_MISMATCH`） | ✗（403 `ROLE_MISMATCH`） | ✗（401） | 無；FREE / BASIC / PRO / ULTIMATE 相同；保母訂閱鎖定不擋 |
| 回報服務問題（O13） | 本人訂單 | ✗（403 `ROLE_MISMATCH`） | ✗（403 `ROLE_MISMATCH`） | ✗（401） | 同上 |
| 自動結案（O12） | — | — | — | — | SYSTEM 排程；不分方案 |
| 檢視結案 / 爭議中訂單詳情、行程與已送出日誌 | 本人訂單 | 本人訂單 | 唯讀，限 `DISPUTED` 或有 OPEN 待辦的訂單（畫面見 PRD-020） | ✗ | 無 |
| 帳務總覽 | ✗（403 `ROLE_MISMATCH`） | 本人收入 | ✗（403 `ROLE_MISMATCH`） | ✗（401） | 無；四級相同；訂閱鎖定不擋（BR-14） |
| 飼主歷史訂單 | 本人訂單 | ✗（屬保母訂單列表，不在本卡） | ✗ | ✗ | 無 |

- 「本人訂單」以 token 身分判斷；訂單存在但不屬於呼叫者 → 403 `FORBIDDEN`；不存在 → 404 `NOT_FOUND`；token `currentRole` 與端點要求不符 → 403 `ROLE_MISMATCH`（BASELINE §1.1、§1.2）。
- 多個錯誤同時成立時依 BR-2（BASELINE §3.7 判斷順序）回第一個。

## 4. 主流程

### 4.1 飼主確認結案（O11）

1. 飼主在訂單詳情頁（`IN_PROGRESS`）看到結案區塊。顯示規則：
   - 所有有效行程皆為 `DONE` 且照護日誌已送出、或 `CLOSED_BY_SYSTEM`，且無未結束變更 → 顯示「確認結案」按鈕（`owner-order-detail-btn-complete`）。不需等 `CARE_LOG_EDIT_HOURS`（BASELINE CQ-27）。
   - 有未結束變更 → 不顯示按鈕，提示「訂單變更處理中，完成後才能確認結案」（`owner-order-detail-complete-hint`）。
   - 有行程未結束（`PENDING` / `IN_PROGRESS`），或有 `DONE` 行程的日誌尚未送出 → 不顯示按鈕，提示「所有行程完成且保母送出照護日誌後，即可確認結案」（同 testid）。
2. 點擊後出現確認框「確認結案後將無法再回報服務問題，確定要結案嗎？」，按「確認」（`owner-order-complete-btn-confirm`）帶 `Idempotency-Key` 呼叫 E25。
3. 系統鎖訂單列、重驗 BR-1，同一交易：訂單 `IN_PROGRESS → COMPLETED`、寫入 `completed_at`、寫 `order_logs`（操作者 OWNER）、排入給 SITTER 的通知（BR-20）。
4. 回應 200 後畫面依 4.4 呈現；訂單移到飼主與保母的「歷史」分頁（BR-9）。

> 零行程訂單不會出現在本流程：`IN_PROGRESS` 必有至少一個行程已開始或被系統關閉（O9），而變更只能取消 `PENDING` 行程（BASELINE §3.4），所以 `IN_PROGRESS` 訂單永遠至少有一個有效行程；`CONFIRMED` 訂單行程全被取消時走 O10 → `CANCELLED`（PRD-023）。原卡「零行程不可結案、記錄告警」的規則因此刪除。

### 4.2 系統自動結案（O12）

1. 排程 C8 每 15 分鐘執行（BASELINE §5 排程頻率），先唯讀查出候選：訂單 `IN_PROGRESS`、無未結束變更、`now ≥ completion_basis_at + AUTO_COMPLETE_HOURS`（48h）。
2. 逐筆以獨立交易處理：鎖訂單列（`SKIP LOCKED`）後重驗——所有有效行程為 `DONE` 或 `CLOSED_BY_SYSTEM`（**不看日誌是否送出**，BASELINE CQ-40）、無未結束變更、時間條件仍成立；成立 → `IN_PROGRESS → COMPLETED`、寫入 `completed_at` = 處理時間、寫 `order_logs`（操作者 SYSTEM）、排入給 OWNER 與 SITTER 的通知（BR-20）。不成立 → 略過，不寫任何資料。
3. 一筆失敗不影響其他筆；排程可重跑，已 `COMPLETED` 的訂單不再被選為候選（BASELINE §3.6 排程）。
4. `DISPUTED` 訂單不是候選（只選 `IN_PROGRESS`），自動結案因此暫停（BASELINE O13 附帶動作）。
5. `completion_basis_at` 的定義與維護見 BASELINE §5 與 PRD-024 BR-8，本卡不重定義；只有 `SETTLED` 變更會推遲（CQ-69）。

### 4.3 飼主回報服務問題（O13）

1. 飼主在 `IN_PROGRESS` 訂單詳情頁看到「回報問題」按鈕（`owner-order-detail-btn-report-issue`）的條件：所有有效行程為 `DONE` 或 `CLOSED_BY_SYSTEM`（日誌是否送出不影響）、無未結束變更、`now < completion_basis_at + DISPUTE_WINDOW_HOURS`。同時顯示「可回報問題至 YYYY-MM-DD HH:mm，屆時未結案將自動結案」（`owner-order-detail-dispute-window-ends-at`，Asia/Taipei；時間值 = `completion_basis_at + 48h`，BR-21）。
2. 點擊後填寫問題說明（必填 1–500 字，`owner-order-dispute-input-description`；不選問題類別、無附件上傳元件，BR-6），按「送出」（`owner-order-dispute-btn-submit`）帶 `Idempotency-Key` 呼叫 E26。
3. 系統鎖訂單列、重驗 BR-5，同一交易：訂單 `IN_PROGRESS → DISPUTED`；建立 `admin_tasks`（`type=SERVICE_DISPUTE`、`OPEN`、關聯訂單）；寫 `order_logs`（payload 含問題說明）；排入給 SITTER 的通知（BR-20）。ADMIN 不收站內通知或 Email，只從待辦清單得知（BASELINE O13、SD-ORDER CQ-9）。
4. 之後雙方畫面依 4.4 呈現；ADMIN 調解結案（O15 → `COMPLETED` / O17 → `CANCELLED`）見 PRD-020。

### 4.4 結案後與爭議中的畫面

| 訂單狀態 | 飼主詳情頁 | 保母詳情頁 | 前端分頁 |
|---------|-----------|-----------|---------|
| `IN_PROGRESS`（行程全結束） | 4.1 / 4.3 的按鈕或提示；行程清單與「查看日誌」 | 「等待飼主確認結案」與自動結案時間（`sitter-order-detail-auto-complete-at`，BR-21） | 進行中 |
| `DISPUTED` | 橫幅「訂單爭議處理中，請等候平台處理」（`owner-order-detail-disputed-banner`）；行程清單與「查看日誌」照常；不顯示確認結案、回報問題、申請變更、付款動作 | 同樣橫幅（`sitter-order-detail-disputed-banner`）；行程照常可執行（PRD-008） | 進行中 |
| `COMPLETED` | 狀態「已結案」與結案時間（`owner-order-detail-completed-at`）；行程清單與「查看日誌」照常；不顯示任何操作按鈕 | 狀態「已結案」與結案時間（`sitter-order-detail-completed-at`）；不顯示任何操作按鈕 | 歷史 |

- 行程清單（`owner-order-detail-visit-item-{visitId}`）與「查看日誌」（`owner-order-detail-btn-view-log-{visitId}`，只對已送出日誌顯示）在 `CONFIRMED` / `IN_PROGRESS` / `DISPUTED` / `COMPLETED` 顯示，`PENDING` / `PENDING_PAYMENT` / `CANCELLED` 不顯示（BR-24）。日誌與媒體的檢視規則、保留期限見 PRD-022、PRD-013；結案不縮短日誌編輯期（BR-25）。

### 4.5 保母帳務總覽

1. 保母進入「收款」分頁的帳務總覽（BR-22；保母訂單「歷史」分頁不另顯示總收入），月份選擇器（`sitter-ledger-select-month`）預設為當下 Asia/Taipei 月份。
2. 前端以 `month=YYYY-MM` 呼叫 E30。系統回傳該月（Asia/Taipei 自然月）內 `completed_at` 或 `cancelled_at` 落在月內的本人訂單，以及每筆的已收淨額與加總（BR-10、BR-11）；淨額 0 的 `CANCELLED` 訂單不列出（BR-22）。
3. 頁面頂端顯示「本月總收入 NT$ X」（`sitter-ledger-total-income`，整數、千分位）；下方列表每筆一列（`sitter-ledger-item-{orderId}`），欄位：訂單編號、飼主稱呼、服務期間（首末行程日）、終態（已結案 / 已取消）、終態日期、已收淨額（BR-22）；無資料 → 總收入 NT$ 0，顯示「本月沒有已結束的訂單」（`sitter-ledger-empty`）。
4. 線上 / 線下支付方式篩選、待撥款佇列、撥款日屬第二階段，留給 SD-BILLING / PRD-015（BR-13）。

### 4.6 飼主歷史訂單

1. 飼主在訂單頁切到「歷史」分頁（`owner-orders-tab-history`），列出本人 `COMPLETED` 與 `CANCELLED` 訂單（BR-9），每張卡片（`owner-order-card-{orderId}`）顯示保母名稱、服務期間（首末行程日）、狀態（已結案 / 已取消）、終態日期、實付淨額（已收淨額），依終態時間新到舊排序（BR-23）。
2. 可依狀態（全部 / 已結案 / 已取消，`owner-orders-history-filter-status`）與月份（`owner-orders-history-select-month`）篩選（BR §6）；預設狀態全部、不篩月份；月份歸屬依 `completed_at` / `cancelled_at` 的 Asia/Taipei 自然月（BR-23）。
3. 點卡片進入詳情，依 4.4（`COMPLETED`）或 PRD-027 / PRD-026（`CANCELLED` 的取消結果）呈現。

## 5. 狀態轉移

> 只列本卡造成的轉移；完整狀態機見 BASELINE §3.2。

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1 | `IN_PROGRESS` | `COMPLETED`（O11） | OWNER | 所有有效行程為 `DONE`（日誌已送出）或 `CLOSED_BY_SYSTEM`；無未結束變更 | 409 `ORDER_STATE_CONFLICT`（非 `IN_PROGRESS`，payload `currentStatus`）→ 409 `MODIFICATION_IN_PROGRESS` → 409 `VISITS_NOT_FINISHED` |
| T2 | `IN_PROGRESS` | `COMPLETED`（O12） | SYSTEM（排程 C8） | 所有有效行程為 `DONE` 或 `CLOSED_BY_SYSTEM`（不看日誌）；無未結束變更；`now ≥ completion_basis_at + 48h` | 不轉移、不寫資料 |
| T3 | `IN_PROGRESS` | `DISPUTED`（O13） | OWNER | 說明 1–500 字；行程條件同 T2；無未結束變更；`now < completion_basis_at + 48h`。附帶：建立 `admin_tasks`（`SERVICE_DISPUTE`、`OPEN`） | 400 `VALIDATION_FAILED`（說明）→ 409 `ORDER_STATE_CONFLICT` → 409 `MODIFICATION_IN_PROGRESS` → 409 `VISITS_NOT_FINISHED` → 409 `DEADLINE_PASSED`（`deadline=DISPUTE_WINDOW_ENDS_AT`、`deadlineAt`） |

`DISPUTED` 之後的出口（O15 / O17）見 PRD-020。

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | O11 前置條件：訂單 `IN_PROGRESS`；所有有效行程（非 `CANCELLED`）為 `DONE` 且照護日誌已送出，或 `CLOSED_BY_SYSTEM`；無未結束變更（`REQUESTED` / `QUOTED` / `AWAITING_SUPPLEMENT` / `AWAITING_REFUND`）。保母送出日誌後飼主即可確認，不等 `CARE_LOG_EDIT_HOURS` | BASELINE O11、§3.1 有效行程、CQ-27 |
| BR-2 | 錯誤判斷順序依 BASELINE §3.7：401 → 冪等回放 → 403 `ROLE_MISMATCH` → 404 → 403 `FORBIDDEN` → 400 `VALIDATION_FAILED`（含缺 `Idempotency-Key`、O13 說明）→ 冪等衝突 → 409 `ORDER_STATE_CONFLICT` → 409 `MODIFICATION_IN_PROGRESS` → 409 `VISITS_NOT_FINISHED` → 409 `DEADLINE_PASSED`（僅 O13） | BASELINE §3.7 判斷順序第 8–10 步（CQ-70、CQ-76；`VISITS_NOT_FINISHED` 列於第 9 步末，CQ-82）；SD-ORDER CQ-5、CQ-6（使用者決定 2026-11-22） |
| BR-3 | O12：條件同 T2；排程每 15 分鐘，到期判斷 `now ≥ completion_basis_at + AUTO_COMPLETE_HOURS`；先唯讀查候選，再逐筆獨立交易鎖住後重驗（`SKIP LOCKED`），一筆失敗不影響其他筆，可重跑；`completed_at` = 處理時間 | BASELINE O12、§3.6 排程、§5（`AUTO_COMPLETE_HOURS` 48，CQ-24；排程頻率）、CQ-40 |
| BR-4 | `completion_basis_at` 只引用 BASELINE §5：`DONE` 行程結束時間 = `completed_at + CARE_LOG_EDIT_HOURS`（24h）、`CLOSED_BY_SYSTEM` = 被關閉時間、`CANCELLED` 不計，再與最後一筆 `SETTLED` 變更結束時間取最大值；`REJECTED` / `WITHDRAWN` / `EXPIRED` 變更不推遲 | BASELINE §5、CQ-17、CQ-40、CQ-69；PRD-024 BR-8 |
| BR-5 | O13 前置條件：訂單 `IN_PROGRESS`；行程條件同 O12（不看日誌）；無未結束變更；`now < completion_basis_at + DISPUTE_WINDOW_HOURS`（48h）。視窗以時間判斷，排程是否已跑不影響：`now ≥` 視窗終點 → 409 `DEADLINE_PASSED`（payload `deadline=DISPUTE_WINDOW_ENDS_AT`、`deadlineAt = completion_basis_at + 48h`） | BASELINE O13 錯誤碼（CQ-82）、§3.2 期限以時間判斷（CQ-46）、§3.7 payload 與期限名稱 `DISPUTE_WINDOW_ENDS_AT`（CQ-71）、§5（CQ-24）；SD-ORDER CQ-5 |
| BR-6 | O13 只收問題說明：必填 1–500 字、不選問題類別、不可附圖；說明寫入 `order_logs` payload；同交易建立 `admin_tasks`（`type=SERVICE_DISPUTE`、`status=OPEN`、關聯訂單），`(type, order_id) WHERE status='OPEN'` 唯一；ADMIN 不收站內通知或 Email，只從待辦清單得知 | SD-ORDER CQ-5、CQ-9（使用者決定 2026-11-22）；BASELINE O13 附帶動作（CQ-82）、§3.6 ADMIN 待辦（CQ-55）；CQ-5（已決，使用者 2026-11-22） |
| BR-7 | `DISPUTED` 期間：自動結案暫停；雙方對 payment 與變更的所有操作 → 409 `ORDER_STATE_CONFLICT`；行程照常執行；只有 ADMIN 調解（O15 / O17，PRD-020）能離開 | BASELINE O13 附帶動作、§3.2 `DISPUTED` 凍結（CQ-48、CQ-57） |
| BR-8 | `COMPLETED` 為終態：再次 O11 / O13、O14、發起變更一律 409 `ORDER_STATE_CONFLICT`（payload `currentStatus=COMPLETED`）；`completed_at` 只寫一次，重複呼叫不得改動 | BASELINE §3.1 狀態圖（`COMPLETED --> [*]`）、§3.4 可發起時機（限 `CONFIRMED` / `IN_PROGRESS`）、§3.7 |
| BR-9 | 前端分頁：`CONFIRMED` / `IN_PROGRESS` / `DISPUTED` → 進行中；`COMPLETED` / `CANCELLED` → 歷史 | BASELINE §3.1 主狀態表 |
| BR-10 | 保母收入 = `COMPLETED` 與 `CANCELLED` 訂單的已收淨額（`Σ CHARGE VERIFIED − Σ REFUND VERIFIED`，含 `ADMIN_ADJUSTMENT`），由 payments 加總，不讀 `total_amount`；取消費因此計入 | BASELINE §4（CQ-14、CQ-34、CQ-41） |
| BR-11 | 帳務月份歸屬依訂單 `completed_at`（`COMPLETED`）或 `cancelled_at`（`CANCELLED`）換算 Asia/Taipei 後的自然月；付款日、行程日期不影響歸屬 | SD-ORDER CQ-8（使用者決定 2026-11-22）；BASELINE §5（日邊界 Asia/Taipei） |
| BR-12 | 非終態訂單（含 `DISPUTED`、`IN_PROGRESS` 已收款者）不列入帳務總覽與總收入，直到進入 `COMPLETED` / `CANCELLED` 的那個月 | BASELINE §4 保母收入 |
| BR-13 | 帳務總覽的支付方式（線上 / 線下）篩選、待撥款佇列、撥款日（D+3）屬第二階段線上付款，由 SD-BILLING / PRD-015 定義；線上撥款的爭議凍結亦同。第一階段所有 payment 為 `OFFLINE_TRANSFER` 或 `ADMIN_ADJUSTMENT` | BR §5.6.1、§5.6.2（待撥款佇列「僅於第二階段出現」）；BASELINE §2.1 線上刷卡（SD-BILLING）；呼叫方交辦（使用者決定 2026-11-22） |
| BR-14 | 帳務總覽無方案限制（FREE / BASIC / PRO / ULTIMATE 相同）；保母訂閱逾期 / 停權時仍可查看（屬既有訂單，不是新生意）。O11 / O12 / O13 同樣不受保母方案與鎖定影響 | BASELINE §2.1（無此能力列）、§2.2（只擋新生意，CQ-19） |
| BR-15 | 金額一律整數 TWD；畫面以千分位顯示 | BASELINE §4 |
| BR-16 | 時間 UTC 儲存、ISO-8601 傳輸，畫面以 Asia/Taipei `YYYY-MM-DD HH:mm` 顯示；`month` 參數為 Asia/Taipei 的 `YYYY-MM`，格式錯誤 → 400 `VALIDATION_FAILED` | BASELINE §5 |
| BR-17 | O11 / O13 帶 `Idempotency-Key`；同 key 同內容 → 回放第一次結果；同 key 不同內容 → 422 `IDEMPOTENCY_KEY_REUSED`；處理中 → 409 `IDEMPOTENCY_IN_PROGRESS`；缺 key → 400 `VALIDATION_FAILED`（`field=Idempotency-Key`） | BASELINE §3.6 冪等、§3.7 |
| BR-18 | O11 / O12 / O13 先鎖訂單列（`lock_timeout` 3s，逾時 409 `ORDER_BUSY` + `Retry-After: 1`）；`order_logs` 與狀態變更同交易；通知經 outbox 與轉移同交易寫入，Email 由排程寄送 | BASELINE §3.6 鎖、稽核、通知 |
| BR-19 | 行程何時成為 `DONE`、`CLOSED_BY_SYSTEM`（殭屍清理）與 `IN_PROGRESS` 行程逾時出口由 PRD-008 定義；日誌是否「已送出」由 PRD-022 定義。本卡只讀取結果 | BASELINE §6 行程 |
| BR-20 | 通知（與轉移同交易經 outbox 寫入，BR-18）：O11 → SITTER「飼主已確認結案」；O12 → OWNER 與 SITTER 各一則「訂單已自動結案」；O13 → SITTER「飼主回報服務問題，訂單進入爭議處理」。管道為站內 + Email，屬可關閉類別（不在 BASELINE §3.6 不可關閉清單），實際投遞依收件人 PRD-014 偏好；ADMIN 不通知。冪等回放、排程重跑、被略過的轉移不產生通知 | BASELINE O11–O13 附帶動作、§3.6 通知（CQ-73、CQ-82）；SD-ORDER CQ-9；CQ-1（已決，使用者 2026-11-22） |
| BR-21 | `IN_PROGRESS` 訂單所有有效行程已結束且無未結束變更時，畫面顯示 `completion_basis_at + 48h`（Asia/Taipei）：飼主端為「可回報問題至」（`owner-order-detail-dispute-window-ends-at`），保母端為「預計自動結案時間」（`sitter-order-detail-auto-complete-at`）；兩者同一時間點（`AUTO_COMPLETE_HOURS` = `DISPUTE_WINDOW_HOURS` = 48）。條件不成立（行程未結束、有未結束變更、非 `IN_PROGRESS`）時不顯示 | BASELINE §5（CQ-24）；CQ-8（已決，使用者 2026-11-22） |
| BR-22 | 保母帳務總覽放在保母「收款」分頁；保母訂單「歷史」分頁不另顯示總收入。列表欄位：訂單編號、飼主稱呼、服務期間（首末有效行程日）、終態（已結案 / 已取消）、終態日期（Asia/Taipei）、已收淨額；預設當下 Asia/Taipei 月份；已收淨額為 0 的 `CANCELLED` 訂單不列出（不影響總收入）；列表依終態時間新到舊排序（與 BR-23 飼主歷史一致） | BR §2.1、§5.6.2；CQ-2、CQ-3（已決，使用者 2026-11-22） |
| BR-23 | 飼主歷史卡片：保母名稱、服務期間（首末有效行程日）、狀態（已結案 / 已取消）、終態日期、實付淨額（已收淨額，BR-10 同算法）；排序依終態時間（`completed_at` / `cancelled_at`）新到舊；月份篩選依終態時間的 Asia/Taipei 自然月（與 BR-11 同口徑）；預設狀態全部、不篩月份 | BR §6；CQ-4（已決，使用者 2026-11-22） |
| BR-24 | 訂單詳情的行程清單在 `CONFIRMED` / `IN_PROGRESS` / `DISPUTED` / `COMPLETED` 顯示，`PENDING` / `PENDING_PAYMENT` / `CANCELLED` 不顯示；「查看日誌」只對已送出日誌顯示（owner 只能讀已送出的日誌，BASELINE CQ-81） | 原 PRD-009 A.4（2026-10-26 fix-batch13 K2）；BASELINE §1.2（CQ-81）；CQ-7（已決，使用者 2026-11-22） |
| BR-25 | 訂單進入 `COMPLETED` 不縮短 `CARE_LOG_EDIT_HOURS`：日誌可否暫存 / 修改 / 送出一律依 PRD-022 的時間規則，不看訂單是否經 O11 / O12 結案（取代舊 PRD-022 的 `ORDER_COMPLETED` 拒絕）。PRD-022 BR-4 目前對 `COMPLETED` 一律 409 `ORDER_STATE_CONFLICT`，與本條不一致，需由 PRD-022 修訂（§9 跨卡） | BASELINE §5 `CARE_LOG_EDIT_HOURS`、CQ-27；CQ-6（已決，使用者 2026-11-22；細節由 PRD-022 定義） |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 非該訂單 OWNER 確認結案 / 回報問題 | 拒絕，不改資料 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | SITTER 或 ADMIN token 呼叫 O11 / O13；OWNER 或 ADMIN token 呼叫帳務總覽 | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換身分後再操作」 |
| EX-3 | 訂單不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此訂單」 |
| EX-4 | 方案不足 / 保母訂閱鎖定 | 不適用：本卡功能不分方案，鎖定不擋（BR-14） | — | — |
| EX-5 | 訂單非 `IN_PROGRESS`（`CONFIRMED`、`DISPUTED`、`COMPLETED`、`CANCELLED` 等）時確認結案或回報問題 | 拒絕；`COMPLETED` 的 `completed_at` 不變 | 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`） | 「訂單狀態已更新，請重新整理」 |
| EX-6 | 有未結束變更 | 拒絕（與行程未結束同時成立時回本錯誤） | 409 `MODIFICATION_IN_PROGRESS` | 「訂單變更處理中，完成後才能操作」 |
| EX-7 | 行程未全部結束；O11 另含 `DONE` 行程日誌未送出 | 拒絕 | 409 `VISITS_NOT_FINISHED` | 「所有行程完成且保母送出照護日誌後，即可確認結案」（O11）/「所有行程結束後才能回報問題」（O13） |
| EX-8 | 爭議視窗已過才回報問題（含排程尚未結案的空窗） | 拒絕，訂單仍 `IN_PROGRESS`；飼主仍可確認結案 | 409 `DEADLINE_PASSED`（`deadline=DISPUTE_WINDOW_ENDS_AT`、`deadlineAt`） | 「已超過可回報問題的期限（YYYY-MM-DD HH:mm）」 |
| EX-9 | 問題說明空白或超過 500 字 | 拒絕，不改資料、不建待辦 | 400 `VALIDATION_FAILED` | 「請填寫問題說明（500 字以內）」 |
| EX-10 | 重複送出（同 `Idempotency-Key`） | 回放第一次結果，不重複轉移、不重複建待辦或寫 `order_logs` | 同第一次 | 同第一次 |
| EX-11 | 重複送出（不同 key，訂單已 `COMPLETED` / `DISPUTED`） | 拒絕 | 409 `ORDER_STATE_CONFLICT` | 同 EX-5 |
| EX-12 | 飼主確認結案與自動結案排程同時處理同一訂單 | 鎖序列化；恰一次轉移、一筆 `order_logs`；後到的 O11 回 409 `ORDER_STATE_CONFLICT`，後到的排程重驗失敗略過 | 依先後 | 依先後 |
| EX-13 | 鎖等待逾時；`@Version` 衝突（最後防線） | 不改資料 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「訂單處理中，請稍後再試」 |
| EX-14 | 自動結案排程某筆失敗 | 該筆交易回滾，其他筆照常；下次排程重試 | — | — |
| EX-15 | Cloud Scheduler 漏跑 / 延遲 | 下次執行以 `now ≥` 到期時間補處理；期間 O13 已依時間拒絕（EX-8），O11 照常可用 | — | — |
| EX-16 | Resend 寄信失敗 | 轉移照常提交；站內通知已寫入；Email 由排程重試最多 5 次（4xx 直接判失敗） | 原請求 200 | 站內通知正常出現 |
| EX-17 | GCS | 不適用：結案與回報問題不上傳檔案（BR-6）；日誌媒體檢視失敗見 PRD-022 | — | — |
| EX-18 | 帳務總覽 `month` 缺漏或格式錯誤 | 拒絕 | 400 `VALIDATION_FAILED`（`field=month`） | 前端一律帶選擇器的值，不會出現 |
| EX-19 | 同一請求違反多條規則 | 依 BR-2 回第一個（例：說明空白且訂單已 `DISPUTED` → 400） | 依 BR-2 | 依該錯誤 |
| EX-20 | 收件人已在 PRD-014 關閉 O11 / O12 / O13 通知所屬類別的某管道 | 轉移照常提交；只投遞收件人仍開啟的管道（可關閉類別，BR-20） | 原請求 200 | 依偏好收到或不收到 |

## 8. 驗收標準（AC）

> 共用前提（除非另寫）：時間皆 Asia/Taipei，括號內為 UTC；訂單屬 OWNER-A / SITTER-S；寫入請求皆帶 `Idempotency-Key`。

### 子模組 1：飼主確認結案與結案後畫面

- [ ] **AC-1.1 (確認結案成功與通知)**：Given SITTER-S 的通知偏好中該類別站內與 Email 皆開啟、SITTER-T 的 Email 已關閉（站內開啟），`IN_PROGRESS` 訂單，V1 `DONE`（`completed_at` 10 分鐘前、日誌已送出）、V2 `CLOSED_BY_SYSTEM`、V3 因變更 `CANCELLED`，無未結束變更，When OWNER-A 點 `owner-order-detail-btn-complete` 並在確認框點 `owner-order-complete-btn-confirm`，Then E25 回 200 `{ status: "COMPLETED", completedAt }`；訂單 `IN_PROGRESS → COMPLETED`、`completed_at` = 請求處理時間；`order_logs` 新增一筆（操作者 OWNER）；不需等 V1 的 24h 日誌期（`completed_at + 24h` 未到）；同交易新增一筆給 SITTER-S 的「飼主已確認結案」通知（站內 + Email 待寄），OWNER-A 與 ADMIN 無通知。Given 同條件但訂單屬 SITTER-T，Then SITTER-T 只收到站內通知、無 Email。對應：T1、BR-1、BR-20、EX-20
- [ ] **AC-1.2 (行程或日誌未完成)**：Given `IN_PROGRESS` 訂單 V1 `DONE` 但日誌仍為草稿、V2 `DONE` 日誌已送出，When OWNER-A 開啟詳情，Then 不顯示 `owner-order-detail-btn-complete`，`owner-order-detail-complete-hint` 顯示「所有行程完成且保母送出照護日誌後，即可確認結案」；When 直接呼叫 E25，Then 409 `VISITS_NOT_FINISHED`、訂單仍 `IN_PROGRESS`。Given V1 為 `IN_PROGRESS`，When 呼叫 E25，Then 同樣 409 `VISITS_NOT_FINISHED`。對應：T1、BR-1、EX-7
- [ ] **AC-1.3 (變更進行中，錯誤先後)**：Given `IN_PROGRESS` 訂單有一筆 `AWAITING_REFUND` 變更，V1、V2 `DONE`（日誌已送出），When OWNER-A 開啟詳情，Then 不顯示確認結案按鈕，提示「訂單變更處理中，完成後才能確認結案」；When 呼叫 E25，Then 409 `MODIFICATION_IN_PROGRESS`。Given 同時有 `QUOTED` 變更且 V2 仍 `IN_PROGRESS`，When 呼叫 E25，Then 409 `MODIFICATION_IN_PROGRESS`（不是 `VISITS_NOT_FINISHED`）。訂單皆不變。對應：BR-1、BR-2、EX-6
- [ ] **AC-1.4 (狀態不允許與終態不可改)**：Given 訂單為 `CONFIRMED` / `DISPUTED` / `CANCELLED`，When OWNER-A 呼叫 E25，Then 409 `ORDER_STATE_CONFLICT`、payload `currentStatus` 為當下狀態。Given 訂單已於 2026-11-23 10:00（`2026-11-23T02:00:00Z`）`COMPLETED`，When OWNER-A 以新 key 呼叫 E25，或對該訂單發起變更，Then 409 `ORDER_STATE_CONFLICT`（`currentStatus=COMPLETED`），`completed_at` 仍為 `2026-11-23T02:00:00Z`、`order_logs` 無新增。對應：BR-8、EX-5、EX-11
- [ ] **AC-1.5 (越權與缺 key)**：Given AC-1.1 的訂單，When OWNER-B 呼叫 E25，Then 403 `FORBIDDEN`；When SITTER-S（`currentRole=SITTER`）或 ADMIN 呼叫，Then 403 `ROLE_MISMATCH`；When 以不存在的訂單 id 呼叫，Then 404 `NOT_FOUND`；When OWNER-A 不帶 `Idempotency-Key`，Then 400 `VALIDATION_FAILED`（`field=Idempotency-Key`）。以上訂單皆仍 `IN_PROGRESS`。對應：EX-1、EX-2、EX-3、BR-17
- [ ] **AC-1.6 (冪等與併發)**：Given AC-1.1 成功的請求，When OWNER-A 以同 key 同內容重送，Then 回放相同 200 body，`completed_at` 不變、`order_logs` 與給 SITTER-S 的通知皆仍一筆。Given 符合 O11 且 `completion_basis_at + 48h` 已過，When OWNER-A 呼叫 E25 與排程 C8 同時處理該訂單，Then 恰一次 `IN_PROGRESS → COMPLETED`、一筆 `order_logs`；若排程先完成，E25 回 409 `ORDER_STATE_CONFLICT`。When 訂單鎖等待超過 3 秒，Then 409 `ORDER_BUSY`、回應標頭 `Retry-After: 1`。對應：BR-17、BR-18、EX-10、EX-12、EX-13
- [ ] **AC-1.7 (結案後畫面)**：Given AC-1.1 結案後的訂單，When OWNER-A 開啟詳情，Then 顯示「已結案」與 `owner-order-detail-completed-at`（2026-11-23 10:00 格式）；`owner-order-detail-visit-item-{V1}` 與 `owner-order-detail-btn-view-log-{V1}` 可見並可開啟日誌；`owner-order-detail-btn-complete`、`owner-order-detail-btn-report-issue` 與申請變更、付款按鈕皆不存在。When SITTER-S 開啟詳情，Then 顯示「已結案」與 `sitter-order-detail-completed-at`，無任何操作按鈕。訂單出現在雙方「歷史」分頁、不在「進行中」。對應：BR-8、BR-9
- [ ] **AC-1.8 (行程清單顯示範圍)**：Given OWNER-A 的訂單分別為 `CONFIRMED`、`IN_PROGRESS`、`DISPUTED`、`COMPLETED`，When 開啟詳情，Then 皆顯示行程清單，已送出日誌的行程有「查看日誌」，未送出日誌的行程沒有。Given 訂單為 `PENDING`、`PENDING_PAYMENT`、`CANCELLED`，When 開啟詳情，Then 不顯示行程清單。對應：§4.4、BR-24

### 子模組 2：自動結案與服務爭議回報

- [ ] **AC-2.1 (自動結案時間點與通知)**：Given OWNER-A 與 SITTER-S 的通知偏好中該類別站內與 Email 皆開啟，`IN_PROGRESS` 訂單 V1 `DONE`（`completed_at` 2026-11-20 10:00，日誌已送出 → 結束時間 2026-11-21 10:00）、V2 `CLOSED_BY_SYSTEM`（2026-11-21 00:05 關閉），無變更，`completion_basis_at` = 2026-11-21 10:00（`2026-11-21T02:00:00Z`），When 排程於 2026-11-23 09:59 執行，Then 訂單仍 `IN_PROGRESS`、無通知；When 排程於 2026-11-23 10:00 或之後第一次執行（≤ 15 分鐘內），Then 訂單 `COMPLETED`、`completed_at` = 該次處理時間、`order_logs` 一筆（操作者 SYSTEM）；同交易新增 OWNER-A 與 SITTER-S 各一筆「訂單已自動結案」通知（站內 + Email 待寄），ADMIN 無通知。對應：T2、BR-3、BR-4、BR-20
- [ ] **AC-2.2 (自動結案不看日誌)**：Given `IN_PROGRESS` 訂單 V1 `DONE`（`completed_at` 2026-11-20 10:00，日誌草稿已過 24h 未送出）、V2 `DONE`（`completed_at` 2026-11-20 18:00，日誌已送出），`completion_basis_at` = 2026-11-21 18:00，When OWNER-A 呼叫 E25，Then 409 `VISITS_NOT_FINISHED`；When 無人操作、排程於 2026-11-23 18:00 後第一次執行，Then 訂單 `COMPLETED`。對應：T2、BR-1、BR-3（CQ-40）
- [ ] **AC-2.3 (自動結案略過的訂單)**：Given 以下訂單皆 `completion_basis_at + 48h` 已過：(a) `DISPUTED`；(b) `IN_PROGRESS` 且有 `AWAITING_REFUND` 變更；(c) `IN_PROGRESS` 且有一個行程仍 `IN_PROGRESS`，When 排程執行，Then 三筆狀態皆不變、無 `order_logs` 新增。Given (b) 的變更之後 `SETTLED`，When 排程再執行，Then 依新的 `completion_basis_at`（= 變更結束時間）+ 48h 判斷，未到不結案。對應：T2、BR-3、BR-4、BR-7
- [ ] **AC-2.4 (排程失敗隔離與重跑)**：Given 兩筆符合 O12 的訂單 X、Y，When 排程處理 X 時發生例外，Then X 不變、Y `COMPLETED`；When 下一次排程執行，Then X `COMPLETED`。When 同一時間點重跑排程，Then 已 `COMPLETED` 的訂單不再處理，`order_logs` 每筆只有一筆結案紀錄、結案通知每位收件人只有一筆。對應：BR-3、EX-14、EX-15
- [ ] **AC-2.5 (回報問題成功與通知)**：Given SITTER-S 的通知偏好中該類別站內與 Email 皆開啟，AC-2.2 的訂單（V1 日誌未送出也可），時間 2026-11-22 12:00（視窗內），When OWNER-A 點 `owner-order-detail-btn-report-issue`、於 `owner-order-dispute-input-description` 輸入「保母未依約餵藥」、點 `owner-order-dispute-btn-submit`，Then E26 回 200 `{ status: "DISPUTED", adminTaskId }`；訂單 `IN_PROGRESS → DISPUTED`；`admin_tasks` 新增一筆 `type=SERVICE_DISPUTE`、`OPEN`，出現在 ADMIN 待辦清單（PRD-020），ADMIN 無站內通知與 Email；`order_logs` 一筆（payload 含說明）；同交易新增一筆給 SITTER-S 的「飼主回報服務問題，訂單進入爭議處理」通知（站內 + Email 待寄），OWNER-A 無通知；回報畫面只有說明欄、無問題類別選項；雙方詳情頁顯示爭議橫幅（`owner-order-detail-disputed-banner` / `sitter-order-detail-disputed-banner`），確認結案與回報問題按鈕消失；之後排程不再結案此訂單。對應：T3、BR-5、BR-6、BR-7、BR-20
- [ ] **AC-2.6 (爭議視窗邊界，以時間判斷)**：Given V1 `DONE`（`completed_at` 2026-11-20 10:00 → 結束時間 2026-11-21 10:00）、V2 `CLOSED_BY_SYSTEM`（2026-11-19 00:05）、V3 因變更 `CANCELLED`，該變更於 2026-11-22 15:00 `SETTLED`（晚於最後行程結束），`completion_basis_at` = 2026-11-22 15:00，When OWNER-A 開啟詳情，Then `owner-order-detail-dispute-window-ends-at` 顯示「可回報問題至 2026-11-24 15:00」，SITTER-S 詳情頁 `sitter-order-detail-auto-complete-at` 顯示同一時間 2026-11-24 15:00；該變更 `SETTLED` 前（仍未結束）兩者皆不顯示；When 於 2026-11-24 14:59 回報，Then 200、`DISPUTED`；Given 同條件另一筆訂單，When 於 2026-11-24 15:00（排程尚未執行）回報，Then 409 `DEADLINE_PASSED`、payload `deadline=DISPUTE_WINDOW_ENDS_AT`、`deadlineAt=2026-11-24T07:00:00Z`，訂單仍 `IN_PROGRESS`，回報按鈕不再顯示；此時 OWNER-A 呼叫 E25（日誌皆已送出）→ 200 `COMPLETED`。對應：T3、BR-4、BR-5、BR-21、EX-8
- [ ] **AC-2.7 (回報問題前置條件不符)**：Given `IN_PROGRESS` 訂單有 `REQUESTED` 變更，When OWNER-A 回報，Then 409 `MODIFICATION_IN_PROGRESS`；Given 同時有未結束變更且有行程 `PENDING`，Then 仍 409 `MODIFICATION_IN_PROGRESS`；Given 無變更但 V2 仍 `IN_PROGRESS`，Then 409 `VISITS_NOT_FINISHED`；Given 訂單 `CONFIRMED` / `DISPUTED` / `COMPLETED`，Then 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`）。以上皆不建待辦、訂單不變，且行程未結束或有變更時詳情頁不顯示回報按鈕。對應：T3、BR-2、BR-5、EX-5、EX-6、EX-7
- [ ] **AC-2.8 (說明驗證、越權與冪等)**：Given 符合 AC-2.5 條件，When 說明空白或 501 字，Then 400 `VALIDATION_FAILED`、不建待辦；When 說明 500 字，Then 200、`DISPUTED`；回報畫面無附件上傳元件。When OWNER-B 回報，Then 403 `FORBIDDEN`；When SITTER-S 或 ADMIN 呼叫 E26，Then 403 `ROLE_MISMATCH`。When OWNER-A 以成功請求的同 key 重送，Then 回放相同 body、`admin_tasks` 仍只有一筆 `OPEN`；以新 key 再送，Then 409 `ORDER_STATE_CONFLICT`；說明空白且訂單已 `DISPUTED`，Then 400 `VALIDATION_FAILED`。對應：BR-2、BR-6、BR-17、EX-1、EX-2、EX-9、EX-10、EX-19

### 子模組 3：保母帳務總覽與飼主歷史訂單

- [ ] **AC-3.1 (收入 = 已收淨額)**：Given SITTER-S 在 2026-11 有：訂單 A `COMPLETED`（`total_amount` 2000；CHARGE 2000 `VERIFIED`；變更退款 REFUND 500 `VERIFIED`）；訂單 B `CANCELLED`（`CONFIRMED` 整筆取消，CHARGE 1000 `VERIFIED`、REFUND 800 `VERIFIED`）；訂單 C `COMPLETED` 經 ADMIN 調解（CHARGE 1500 `VERIFIED`、`ADMIN_ADJUSTMENT` 300 `VERIFIED`）；訂單 I `CANCELLED`（未付款即取消，無 `VERIFIED` payment），When SITTER-S 選 2026-11，Then 列表 A 淨額 1500、B 200、C 1200，I 不出現，`sitter-ledger-total-income` 顯示「NT$ 2,900」（不是 `total_amount` 加總）；每列 `sitter-ledger-item-{orderId}` 顯示訂單編號、飼主稱呼、服務期間（首末行程日）、終態（A、C「已結案」/ B「已取消」）、終態日期、已收淨額。對應：BR-10、BR-15、BR-22
- [ ] **AC-3.2 (月份歸屬邊界)**：Given 訂單 D 於 2026-10-28 付款、行程 2026-11-01～11-03、`completed_at` = `2026-11-30T15:59:00Z`（11-30 23:59）；訂單 E `completed_at` = `2026-11-30T16:00:00Z`（12-01 00:00）；訂單 F `cancelled_at` = `2026-10-31T16:30:00Z`（11-01 00:30），When 查 2026-11，Then 含 D、F，不含 E；When 查 2026-12，Then 含 E；When 查 2026-10，Then 不含 D、F。對應：BR-11、BR-16
- [ ] **AC-3.3 (非終態不計，終態後才計入)**：Given 訂單 G `IN_PROGRESS`（CHARGE 3000 `VERIFIED`）、訂單 H 於 2026-11-20 `DISPUTED`（CHARGE 2000 `VERIFIED`），When 查 2026-11，Then G、H 皆不在列表、不計入總收入；Given H 於 2026-12-05 經 O15 `COMPLETED` 並登記 `ADMIN_ADJUSTMENT` 500，When 查 2026-12，Then H 淨額 1500 列入 12 月；2026-11 總收入不變。對應：BR-10、BR-11、BR-12
- [ ] **AC-3.4 (位置、月份篩選與空狀態)**：Given 今天 2026-11-22，When SITTER-S 進入「收款」分頁的帳務總覽，Then `sitter-ledger-select-month` 預設 2026-11 並顯示 11 月資料；When SITTER-S 切到訂單「歷史」分頁，Then 不顯示總收入；When 切到無資料的 2026-08，Then `sitter-ledger-total-income` 為「NT$ 0」、顯示 `sitter-ledger-empty`「本月沒有已結束的訂單」；When 直接以 `month=2026-13` 或缺 `month` 呼叫 E30，Then 400 `VALIDATION_FAILED`。對應：BR-16、BR-22、EX-18
- [ ] **AC-3.5 (權限、方案與鎖定)**：Given SITTER-S 與 SITTER-T 在 2026-11 各有已結束訂單，When SITTER-S 查 2026-11，Then 只看到自己的訂單；When OWNER-A（`currentRole=OWNER`）或 ADMIN 呼叫 E30，Then 403 `ROLE_MISMATCH`。Given SITTER-S 為 FREE，或訂閱已逾期鎖定 / 被停權，When 查帳務總覽，Then 200 並顯示與 PRO 相同的資料與欄位。對應：BR-14、EX-2、EX-4
- [ ] **AC-3.6 (飼主訂單分頁歸屬)**：Given OWNER-A 有 `PENDING`、`PENDING_PAYMENT`、`CONFIRMED`、`IN_PROGRESS`、`DISPUTED`、`COMPLETED`、`CANCELLED` 各一筆，When 切到「歷史」分頁 `owner-orders-tab-history`，Then 只出現 `COMPLETED` 與 `CANCELLED` 兩張 `owner-order-card-{orderId}`；`CONFIRMED` / `IN_PROGRESS` / `DISPUTED` 在「進行中」，`PENDING` / `PENDING_PAYMENT` 在「評估中」；OWNER-B 的訂單不出現。對應：BR-9
- [ ] **AC-3.7 (飼主歷史卡片與篩選)**：Given OWNER-A 歷史分頁有 `COMPLETED`（`completed_at` 2026-11-10）、`CANCELLED`（`cancelled_at` 2026-11-12）、`COMPLETED`（`completed_at` 2026-10-05）各一筆，When 進入歷史分頁（預設狀態全部、不篩月份），Then 三筆依終態時間新到舊排列（11-12、11-10、10-05），每張卡片顯示保母名稱、服務期間、狀態、終態日期、實付淨額；When 選 `owner-orders-history-filter-status` = 已結案，Then 只剩兩筆 `COMPLETED`；When 再選 `owner-orders-history-select-month` = 2026-11，Then 只剩 11-10 那筆；When 狀態選全部、月份 2026-11，Then 11-12 的 `CANCELLED` 排在 11-10 之前（終態時間新到舊）。對應：§4.6、BR-23

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | `orders.status`（`COMPLETED` / `DISPUTED`）、`orders.completed_at`（O11 / O12 寫入一次）；`admin_tasks`（新增 `type=SERVICE_DISPUTE`）；`order_logs`（結案、回報問題，含說明）；讀取 `orders.completion_basis_at`、`visits.status`、照護日誌送出狀態、`modification_requests`、`payments`。以上表皆歸 SD-ORDER，本卡不新增實體 |
| 畫面 | 飼主訂單詳情（結案區塊、確認框、回報問題表單、爭議橫幅、結案資訊、行程清單）；保母訂單詳情（等待結案 / 爭議 / 已結案資訊）；保母「收款」分頁的帳務總覽（BR-22）；飼主訂單「歷史」分頁與篩選 |
| 通知 | O11 → SITTER；O12 → OWNER 與 SITTER；O13 → SITTER；站內 + Email，可關閉類別，依 PRD-014 偏好（BR-20，BASELINE CQ-82）。ADMIN 不收 O13 通知，只看待辦清單（SD-ORDER CQ-9）。通知範本文案由 SD-NOTIFY / PRD-014 收錄 |
| SD-ORDER 需補欄位 | 本卡定案後 SD-ORDER 契約缺以下欄位 / 參數，本卡不改 SD，交 SD-ORDER 修訂：① E30 `orders[]` 補飼主稱呼（`ownerDisplayName`）、服務期間（首末有效行程日 `firstServiceDate` / `lastServiceDate`），並排除已收淨額 0 的 `CANCELLED`（BR-22）；② E2 補「可回報問題至 / 預計自動結案時間」（= `completion_basis_at + 48h`，BR-21 條件不成立時為 null）；③ E2 `visits[]` 補日誌是否已送出（供「查看日誌」與結案提示判斷，BR-24、§4.1；若改由 SD-FULFILL / PRD-022 端點提供則免）；④ E3 `tab=HISTORY` 補 `status`（已結案 / 已取消）與 `month`（終態時間 Asia/Taipei 月份）篩選參數、排序改終態時間新到舊（目前為 `created_at` 倒序）、`content[]` 補最後行程日、終態時間（`completedAt` / `cancelledAt`）與已收淨額（`netPaid`）（BR-23） |
| 跨卡 | PRD-022 BR-4（`COMPLETED` 一律 409）需依 BR-25 修訂：經 O11 / O12 結案者不縮短 `CARE_LOG_EDIT_HOURS` |
| 排程 | 有：C8 自動結案，每 15 分鐘，基準 `completion_basis_at + AUTO_COMPLETE_HOURS`（SD-ORDER 實作） |
| 連動 | `COMPLETED` 後：媒體保留自結案日起算（PRD-013）；飼主成為該保母熟客，下次送單免填問卷（BASELINE §3.5、CQ-6）；訂單計入保母收入（本卡子模組 3） |

**E2E 選擇器（testid，依 SD-FRONTEND-SPEC §4）**

| 元素 | testid |
|------|--------|
| 確認結案按鈕 / 不可結案提示 | `owner-order-detail-btn-complete`、`owner-order-detail-complete-hint` |
| 確認結案確認框「確認」 | `owner-order-complete-btn-confirm` |
| 回報問題按鈕 / 視窗截止時間 | `owner-order-detail-btn-report-issue`、`owner-order-detail-dispute-window-ends-at` |
| 回報問題說明 / 送出 | `owner-order-dispute-input-description`、`owner-order-dispute-btn-submit` |
| 爭議橫幅 | `owner-order-detail-disputed-banner`、`sitter-order-detail-disputed-banner` |
| 結案時間 | `owner-order-detail-completed-at`、`sitter-order-detail-completed-at` |
| 保母端自動結案時間 | `sitter-order-detail-auto-complete-at` |
| 行程清單項目 / 查看日誌 | `owner-order-detail-visit-item-{visitId}`、`owner-order-detail-btn-view-log-{visitId}` |
| 帳務總覽 | `sitter-ledger-select-month`、`sitter-ledger-total-income`、`sitter-ledger-item-{orderId}`、`sitter-ledger-empty` |
| 飼主歷史分頁 | `owner-orders-tab-history`、`owner-order-card-{orderId}`、`owner-orders-history-filter-status`、`owner-orders-history-select-month` |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | O11 / O12 / O13 的通知對象與管道：BASELINE 與 BR 都沒有定義（原卡 A.3 的「通知保母」出自 Draft，無出處） | O11 → SITTER「飼主已確認結案」；O12 → OWNER 與 SITTER「訂單已自動結案」；O13 → SITTER「飼主回報服務問題，訂單進入爭議處理」。皆站內 + Email，屬可關閉類別（依 PRD-014 偏好，不在 BASELINE §3.6 不可關閉清單）；ADMIN 不通知（SD-ORDER CQ-9）。定案後補進 AC-1.1、AC-2.1、AC-2.5 | 已決（使用者 2026-11-22）：採建議，與 BASELINE CQ-82 一致；寫入 BR-20、EX-20、AC-1.1、AC-1.6、AC-2.1、AC-2.4、AC-2.5 |
| CQ-2 | 帳務總覽列表欄位、預設月份與是否列出淨額 0 的取消單（原卡欄位與「預設當月」出自 Draft；`CANCELLED` 含大量未付款即取消的單，淨額 0） | 欄位：訂單編號、飼主稱呼、服務期間（首末行程日）、終態（已結案 / 已取消）、終態日期、已收淨額；預設當下 Asia/Taipei 月份；淨額 0 的 `CANCELLED` 訂單不列出（不影響總收入）。注意 SD-ORDER E30 目前回傳欄位不含飼主稱呼與服務期間，需 SD 補 | 已決（使用者 2026-11-22）：採建議；寫入 §4.5、BR-22、AC-3.1、AC-3.4；SD 缺口列 §9「SD-ORDER 需補欄位」① |
| CQ-3 | 帳務總覽放哪裡：BR §2.1 把「總收入」放在保母「訂單」分頁的歷史，BR §5.6.2 把按月收款紀錄放在「收款」分頁 | 放在保母「收款」分頁（收款紀錄）；保母訂單「歷史」分頁不另算總收入，避免兩個數字口徑不同 | 已決（使用者 2026-11-22）：採建議；寫入 §4.5、BR-22、AC-3.4 |
| CQ-4 | 飼主歷史訂單的卡片欄位、排序與月份歸屬（BR §6 只說「依月份、order_status 篩選」） | 卡片：保母名稱、服務期間、狀態（已結案 / 已取消）、終態日期、實付淨額（已收淨額）；排序終態時間新到舊；月份依 `completed_at` / `cancelled_at` 的 Asia/Taipei 自然月（與帳務總覽同口徑）；預設不篩月份 | 已決（使用者 2026-11-22）：採建議；寫入 §4.6、BR-23、AC-3.7；SD 缺口列 §9「SD-ORDER 需補欄位」④ |
| CQ-5 | 原卡 D.2 回報問題要選「問題類別」，SD-ORDER CQ-5 只定義說明 1–500 字 | 不收類別，只收說明 | 已決（使用者 2026-11-22）：採建議；寫入 §4.3、BR-6、AC-2.5 |
| CQ-6 | 結案與照護日誌編輯期的關係（第 1 輪 #42）：舊 PRD-022 對 `COMPLETED` 訂單一律拒絕日誌操作（`ORDER_COMPLETED`）。依 CQ-27，O11 只在所有 `DONE` 行程日誌已送出時成立，未送出的日誌不會被結案切斷；O12 的時間點已包含 `CARE_LOG_EDIT_HOURS`。剩下的問題是：若改寫後的 PRD-022 允許在 `CARE_LOG_EDIT_HOURS` 內修改已送出日誌，飼主提早確認結案是否縮短這段時間 | `COMPLETED` 不縮短 `CARE_LOG_EDIT_HOURS`；日誌可否編輯一律依 PRD-022 的時間規則，不看訂單是否已結案。由 PRD-022 定案 | 已決（使用者 2026-11-22）：採建議；寫入 BR-25、§4.4；PRD-022 BR-4 需對齊（§9 跨卡） |
| CQ-7 | 行程清單顯示範圍（原卡 A.4，2026-10-26 fix-batch13 K2）沿用為規格：`CONFIRMED` / `IN_PROGRESS` / `DISPUTED` / `COMPLETED` 顯示，`PENDING` / `PENDING_PAYMENT` / `CANCELLED` 不顯示 | 沿用（`CANCELLED` 中只有 O17 可能有過行程，O17 前提是無已提供服務，沒有日誌可看） | 已決（使用者 2026-11-22）：採建議；寫入 §4.4、BR-24、AC-1.8 |
| CQ-8 | 是否在畫面顯示「可回報問題至」/「預計自動結案時間」（`completion_basis_at + 48h`，兩者同一時間點） | 顯示：飼主端 `owner-order-detail-dispute-window-ends-at`、保母端 `sitter-order-detail-auto-complete-at`；API 需回傳此時間（SD 確認 E2 是否已含） | 已決（使用者 2026-11-22）：採建議；寫入 §4.3、§4.4、BR-21、AC-2.6；E2 目前未含，列 §9「SD-ORDER 需補欄位」② |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-22 | BR-22 補帳務列表排序（終態時間新到舊） | 主流程（跨卡一致性） |
| 2026-05-09 | 建立 | |
| 2026-11-22 | 依新範本全面改寫（保留編號）：3 個子模組（確認結案與結案後畫面 8 條、自動結案與服務爭議 8 條、帳務總覽與飼主歷史 7 條，共 23 條 AC）；歸屬 SD-FULFILL，O11 / O12 / O13 / 帳務查詢端點引用 SD-ORDER E25 / C8 / E26 / E30；狀態 In Review | 使用者決定 2026-11-22（SD-ORDER 擁有 O1–O17）；DOMAIN-BASELINE 第 2 版 |
| 2026-11-22 | 第 1 輪 #32：O11 起點 `CONFIRMED` → `IN_PROGRESS`（T1） | `00-DOMAIN-BASELINE.prd.review.md` 第 1 輪 #32 |
| 2026-11-22 | 第 1 輪 #33、第 2 輪 #10：自動結案條件改為 `IN_PROGRESS`、有效行程 `DONE` / `CLOSED_BY_SYSTEM`（不看日誌）、無未結束變更、`completion_basis_at + 48h`；`CLOSED_BY_SYSTEM` 放行與自動結案寫在同一處（§4.2、BR-3、BR-4）；AC-2.6 加「變更在最後行程後 SETTLED」範例；殭屍清理本身移到 PRD-008 | 第 1 輪 #33、第 2 輪 #10；BASELINE O12、§5、CQ-40、CQ-69 |
| 2026-11-22 | 第 1 輪 #34：O13 起點改 `IN_PROGRESS`（T3）；視窗已過 409 `DEADLINE_PASSED`、待辦 `SERVICE_DISPUTE`、說明 1–500 字、不通知 ADMIN | 第 1 輪 #34；SD-ORDER CQ-5、CQ-9 |
| 2026-11-22 | 第 1 輪 #35：刪除 `MODIFYING` / `REFUND_VERIFY` / 補款 `PENDING_PAYMENT` / `PAID` 主狀態，改為「有未結束變更 → 409 `MODIFICATION_IN_PROGRESS`」，先於 `VISITS_NOT_FINISHED`；行程清單顯示規則改依主狀態（§4.4、CQ-7） | 第 1 輪 #35；BASELINE CQ-1；SD-ORDER CQ-6 |
| 2026-11-22 | 第 1 輪 #36：刪除例外表「飼主有爭議（未來擴充）」，爭議由子模組 2 定義 | 第 1 輪 #36 |
| 2026-11-22 | 第 1 輪 #37：段落重編，帳務總覽為 §4.5 / 子模組 3；歸屬確認為 SD-FULFILL（查詢端點 E30 在 SD-ORDER），線上 / 線下篩選留 SD-BILLING | 第 1 輪 #37；呼叫方交辦（使用者決定 2026-11-22） |
| 2026-11-22 | 第 1 輪 #38：總收入改為 `COMPLETED` 與 `CANCELLED` 訂單的已收淨額，月份依終態時間；AC-3.1 驗含退款、取消費、`ADMIN_ADJUSTMENT` | 第 1 輪 #38；BASELINE §4；SD-ORDER CQ-8 |
| 2026-11-22 | 第 1 輪 #39：刪除 ADMIN「調整最終訂單金額 / 強制結案」段落，ADMIN 調解改為「見 PRD-020」（O15 / O17 不改 `total_amount`，登記 `ADMIN_ADJUSTMENT`） | 第 1 輪 #39；BASELINE CQ-18 |
| 2026-11-22 | 第 1 輪 #40：`BigDecimal(18,4)` 改整數 TWD（BR-15） | 第 1 輪 #40；BASELINE §4 |
| 2026-11-22 | 第 1 輪 #41：「每日執行」改每 15 分鐘、`now ≥` 判斷；O13 視窗以時間判斷，排程未跑也拒絕（BR-3、BR-5、AC-2.6） | 第 1 輪 #41、#15；BASELINE §5、CQ-46 |
| 2026-11-22 | 第 1 輪 #42：手動結案與日誌補送期——依 CQ-27，O11 須所有 `DONE` 行程日誌已送出，結案不會切斷未送出日誌（AC-1.1、AC-1.2）；已送出日誌在結案後能否編輯列 CQ-6 交 PRD-022 | 第 1 輪 #42；BASELINE CQ-27、CQ-40 |
| 2026-11-22 | 其他：刪除零行程規則（`IN_PROGRESS` 必有有效行程）；刪除付款日 / 撥款日 / D+3 與線上撥款爭議凍結（移 SD-BILLING / PRD-015，BR-13）；刪除「爭議處理雙軌制」與信用扣分（無出處）；刪除帳務匯出「未來擴充」；新增飼主歷史訂單（AC-3.6、AC-3.7）與結案後畫面（AC-1.7、AC-1.8）；無出處項目列 CQ-1~8 | 呼叫方交辦範圍；BASELINE §3.1、§4 |
| 2026-11-22 | CQ-1~8 全部採建議預設值並寫回正文：新增 BR-20（O11 / O12 / O13 通知，與 BASELINE CQ-82 一致）、BR-21（視窗截止 / 預計自動結案時間顯示）、BR-22（帳務總覽放「收款」分頁、欄位、排除淨額 0 的取消單）、BR-23（飼主歷史卡片、排序、月份口徑）、BR-24（行程清單顯示範圍）、BR-25（結案不縮短 `CARE_LOG_EDIT_HOURS`）、EX-20（通知偏好關閉）；BR-6 補不收問題類別；AC-1.1 / 2.1 / 2.5 補通知斷言（Given 固定偏好），AC-1.6 / 2.4 補通知不重複，AC-2.6 補保母端自動結案時間，AC-3.1 / 3.4 / 3.7 補欄位、位置、排序；O13 錯誤碼、判斷順序、通知改引用 BASELINE 第 3 版（CQ-82）；§9 新增「SD-ORDER 需補欄位」（E30 / E2 / E3）與 PRD-022 BR-4 跨卡對齊。子模組與 AC 數不變（3 個；8 / 8 / 7） | 使用者決定 2026-11-22；DOMAIN-BASELINE 第 3 版修訂 CQ-82 |
