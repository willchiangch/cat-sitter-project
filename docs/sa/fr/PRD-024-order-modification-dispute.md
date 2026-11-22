# PRD-024: 訂單變更：款項爭議與協商期間的訂單限制

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 核准 | 使用者，2026-11-22 |
| 建立日期 | 2026-11-21 |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0） |
| 測試情境 | TS-024 |
| 相依 PRD | PRD-016（變更發起與報價；錯誤判斷順序 BR-28；鎖定期間計價 BR-17、BR-18）；PRD-023（補款、退款、結清）；PRD-007（payment 上傳 / 確認）；PRD-008（行程執行）；PRD-009（確認結案 O11、自動結案 O12、回報問題 O13 的端點與排程；**待依 baseline 改寫**，本卡 §4.5 列出需要的行為）；PRD-012（訂閱鎖定、停權通知）；PRD-020（ADMIN 調解 O15 / O17、待辦處理；**待依 baseline 改寫**，本卡 §4.5 列出需要的行為） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.2（ADMIN 唯讀「`DISPUTED` 或有 OPEN 待辦」、收款帳號快照）；§2.2（訂閱逾期 / 停權只擋新生意，CQ-19）；§3.1（`DISPUTED`）；§3.2 O11、O12、O13、O14（含 72h 起算點 CQ-64 與錯誤碼）、O15、O16（作廢補款比照）、O17、DISPUTED 凍結（CQ-48、CQ-57）；§3.3（`SUBMITTED` 不逾期作廢、ADMIN 作廢已匯退款須登記 ADMIN_ADJUSTMENT）；§3.4（可發起時機 CQ-69、補款 `SUBMITTED` 72h 未核對可申報 O14、退款無逾時、協商期間、訂閱鎖定）；§3.6（鎖、稽核、通知、冪等、`admin_tasks`）；§3.7（錯誤碼、判斷順序 CQ-70）；§5（`MODIFICATION_TIMEOUT_HOURS`、`completion_basis_at` 只看 SETTLED 變更 CQ-69、`AUTO_COMPLETE_HOURS`、`DISPUTE_WINDOW_HOURS`） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念（角色、SaaS 額度、訂單狀態、時間起算點）只能**引用** DOMAIN-BASELINE，不得在這裡重新定義；要改就改 baseline。
> 本卡由原 PRD-016 拆出（2026-11-21）。結案（O11–O13）的端點與排程屬 PRD-009 / SD-FULFILL（待依 baseline 改寫）；ADMIN 調解操作屬 PRD-020（待依 baseline 改寫）。本卡定義「有未結束變更」與「變更結束時間」如何影響它們、調解對變更與 payment 的結果，並在 §4.5 列出這兩張卡改寫時必須提供的行為；AC-2.3、AC-2.4、AC-2.6、AC-2.7 以這些行為的可觀察結果表示。上傳 / 確認細節見 PRD-007。

---

## 1. 背景與目的

退款沒有逾時（CQ-30），補款已上傳後也不會因逾時被作廢（CQ-42），因此兩者都可能因一方不動作而卡住：保母不退款、飼主不確認、保母不核對補款。款項爭議（O14）讓雙方任一方把訂單交給 ADMIN 處理。同時，變更協商期間訂單不能結案、也不能回報服務問題（CQ-17），進入爭議後付款與變更全部凍結（CQ-48）。保母訂閱逾期或被停權時，既有訂單的變更仍須照常進行，避免飼主付了錢退不回來（CQ-19）。

## 2. 使用者故事

- **US-1** 身為飼主，當保母遲遲不退款，或我收到的退款金額不對時，我希望能申報款項爭議，以便平台介入。
- **US-2** 身為保母，當我已退款但飼主一直不按確認，或我發現補款憑證有問題時，我希望能申報款項爭議，以便變更不會永遠掛著。
- **US-3** 身為飼主，我希望變更協商期間訂單不會被自動結案，以便協商結果能完整反映在最終金額上。
- **US-4** 身為飼主，即使保母訂閱已逾期，我仍希望能完成取消與退款，以便不被保母的帳號狀態牽連。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 申報款項爭議（O14） | 本人訂單 | 本人訂單 | ✗（403 `ROLE_MISMATCH`） | ✗ | 無；訂閱鎖定不擋 |
| 檢視爭議中訂單的變更、payment、憑證、收款帳號快照 | 本人訂單（快照限該 payment 付款方） | 本人訂單 | 唯讀，限 BASELINE §1.2 可讀訂單（`DISPUTED` 或有 OPEN 待辦的訂單） | ✗ | 無 |
| 爭議期間操作變更 / payment | ✗（409 `ORDER_STATE_CONFLICT`） | ✗（同左） | 只能經 O15 / O17 調解（調解操作見 PRD-020，待依 baseline 改寫） | ✗ | — |

- 訂單存在但不屬於呼叫者 → 403 `FORBIDDEN`；不存在 → 404 `NOT_FOUND`；token 角色不符 → 403 `ROLE_MISMATCH`。
- 多個錯誤同時成立時依 PRD-016 BR-28（BASELINE §3.7 判斷順序）回第一個（BR-16）。

## 4. 主流程

### 4.1 申報款項爭議（O14）

1. OWNER 或 SITTER 在協商頁看到「申報款項爭議」按鈕的條件（BASELINE O14）：
   - 訂單 `CONFIRMED` / `IN_PROGRESS`，且
   - 變更為 `AWAITING_REFUND`（退款 payment 為 `UNPAID` 或 `SUBMITTED` 皆可），或
   - 變更為 `AWAITING_SUPPLEMENT` 且補款 payment 為 `SUBMITTED`，並自**目前這筆 `SUBMITTED` submission 的上傳時間**起已超過 `MODIFICATION_TIMEOUT_HOURS`（72h）未核對；駁回後重新上傳者，自新 submission 上傳時間重新起算（BASELINE O14、CQ-64）。
2. 點擊後填寫爭議說明（必填，1–500 字；不可附圖，ADMIN 以既有憑證與 `order_logs` 判斷），帶 `Idempotency-Key` 送出（BR-13）。
3. 系統鎖訂單列、重驗條件，同一交易：訂單 → `DISPUTED`；建立 `admin_tasks`（`type=MODIFICATION_PAYMENT_DISPUTE`、`OPEN`，關聯訂單與該 payment）；寫 `order_logs`（payload 含爭議說明）；通知另一方「訂單已進入爭議處理」。ADMIN 不另收通知或 Email，只從待辦清單得知（BR-4）。變更與 payment 狀態不變。
4. 之後交由 ADMIN 調解（調解操作見 PRD-020，待依 baseline 改寫）。

### 4.2 爭議期間（DISPUTED 凍結）

1. 雙方對變更與 payment 的所有操作（發起、報價、拒絕、撤回、同意、上傳 / 確認 / 駁回憑證）一律 409 `ORDER_STATE_CONFLICT`（BASELINE §3.2、CQ-48）。
2. 行程照常執行（CQ-48、CQ-57）。
3. ADMIN 調解結案（O15 `COMPLETED` / O17 `CANCELLED`）同一交易：
   - 未結束變更（`AWAITING_SUPPLEMENT` / `AWAITING_REFUND`）→ `REJECTED`，記錄結束時間 = 轉移時間；
   - `UNPAID` payment → `VOID`；
   - `SUBMITTED` 退款 payment：調解畫面對每筆必選「已匯出 / 未匯出」（必填，寫入 `order_logs`）。已匯出 → payment `VOID`，並自動建立等額 `ADMIN_ADJUSTMENT` 退款（`direction=REFUND`、`method=ADMIN_ADJUSTMENT`、`VERIFIED`，計入已收淨額，並計入 O15 / O17「調解退款 ≤ 已收淨額」上限檢查）；未匯出 → 只 `VOID`（BR-14）；
   - `SUBMITTED` 補款 payment：`VOID`，比照 O16 不登記任何金額，通知雙方「此筆補款由雙方自行處理」；已收淨額不含該筆（BR-15）；
   - ADMIN 不改 `total_amount`。

### 4.3 協商期間對結案的限制

1. 訂單有未結束變更（`REQUESTED` / `QUOTED` / `AWAITING_SUPPLEMENT` / `AWAITING_REFUND`）時：
   - OWNER 確認結案（O11，PRD-009）→ 409 `MODIFICATION_IN_PROGRESS`；
   - 自動結案（O12，PRD-009）不觸發；
   - OWNER 回報問題（O13，PRD-009）→ 409 `MODIFICATION_IN_PROGRESS`；
   - 款項爭議（O14）依 4.1 可用。
2. `completion_basis_at = max(各有效行程的結束時間, 最後一筆 SETTLED 變更請求的結束時間)`（BASELINE §5、CQ-69）：只有 `SETTLED` 變更推遲結案基準；`REJECTED` / `WITHDRAWN` / `EXPIRED` 的變更雖記錄結束時間，但不推遲。自動結案（`completion_basis_at + AUTO_COMPLETE_HOURS`）與 O13 視窗（`completion_basis_at + DISPUTE_WINDOW_HOURS`）依此計算。
3. 協商期間行程照常執行（BASELINE §3.4）；減少目標行程若在 `QUOTED` 期間被開始，飼主同意時回 409 `VISIT_ALREADY_STARTED`（PRD-023）。

### 4.4 訂閱鎖定 / 停權

1. 保母訂閱逾期或被 ADMIN 停權時，以下動作照常、不回 403 `SUBSCRIPTION_LOCKED`：發起變更、報價、拒絕、撤回（PRD-016）；確認 / 駁回補款、上傳退款憑證（PRD-023 / PRD-007）；申報款項爭議（本卡）。
2. 鎖定 / 停權期間的變更計價規則與驗收只在 PRD-016 定義（BR-17、BR-18、AC-2.8），本卡不重複。
3. 鎖定時對受影響飼主的通知見 PRD-012。

### 4.5 對 PRD-009 / PRD-020 的行為需求（兩卡改寫時照此實作）

| 端點 / 排程 | 所屬 | 本卡要求的行為 | 本卡驗收 |
|------------|------|--------------|---------|
| O11 確認結案 | PRD-009 | 訂單有未結束變更 → 409 `MODIFICATION_IN_PROGRESS`，訂單不變（與 `VISITS_NOT_FINISHED` 同時成立時的先後由 PRD-009 改寫時定義） | AC-2.6 |
| O12 自動結案排程 | PRD-009 | 候選條件加「無未結束變更」；時間以 `completion_basis_at`（只計 SETTLED 變更）判斷；鎖住後重驗 | AC-2.6、AC-2.7 |
| O13 回報問題 | PRD-009 | 訂單有未結束變更 → 409 `MODIFICATION_IN_PROGRESS`；視窗以 `completion_basis_at + DISPUTE_WINDOW_HOURS` 判斷 | AC-2.6、AC-2.7 |
| `completion_basis_at` 維護 | PRD-009 / SD-ORDER | 變更進入 `SETTLED` 的同一交易內重算；其他終態不重算 | AC-2.7 |
| O15 / O17 調解結案 | PRD-020 | 同交易：變更 `REJECTED` + 結束時間；`UNPAID` payment `VOID`；`SUBMITTED` 退款必填「已匯出 / 未匯出」（缺 → 400 `VALIDATION_FAILED`），已匯出自動建 `ADMIN_ADJUSTMENT`；`SUBMITTED` 補款 `VOID` 不登記金額並通知雙方；待辦 `RESOLVED`；不改 `total_amount` | AC-2.3、AC-2.4 |
| ADMIN 讀取訂單 | PRD-020 | 限 `DISPUTED` 或有 OPEN 待辦，回應含變更歷史、payments、憑證、收款帳號快照；其他 → 403 `FORBIDDEN` | AC-2.5 |

## 5. 狀態轉移

> 只列本卡造成的轉移；完整狀態機見 BASELINE §3.2、§3.4。

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1 | 訂單 `CONFIRMED` / `IN_PROGRESS` | `DISPUTED`（O14） | OWNER / SITTER | 爭議說明 1–500 字；變更 `AWAITING_REFUND`；或變更 `AWAITING_SUPPLEMENT` 且補款 payment `SUBMITTED`、自目前這筆 submission 上傳時間起超過 72h 未核對 | 400 `VALIDATION_FAILED`（說明不符）；409 `ORDER_STATE_CONFLICT`（訂單狀態不符）/ `MODIFICATION_STATE_CONFLICT`（無符合的變更）/ `PAYMENT_STATE_CONFLICT`（補款非 `SUBMITTED`，或未滿 72h 時 payload 帶 `disputeAvailableAt`）（BASELINE O14） |
| T2 | 變更 `AWAITING_SUPPLEMENT` / `AWAITING_REFUND` | `REJECTED` | ADMIN（隨 O15 / O17，PRD-020） | 訂單 `DISPUTED` 且 ADMIN 調解結案。附帶：記錄結束時間 = 轉移時間（不推遲 `completion_basis_at`） | 見 PRD-020 |
| T3 | payment `UNPAID` / `SUBMITTED` | `VOID` | ADMIN（隨 O15 / O17） | 同 T2；`SUBMITTED` 退款須已選「已匯出 / 未匯出」 | 400 `VALIDATION_FAILED`（未選）；其餘見 PRD-020 |
| T4 | — | payment `ADMIN_ADJUSTMENT` `VERIFIED` | ADMIN（隨 T3） | 被作廢的 `SUBMITTED` 退款選「已匯出」；金額 = 該退款 `amount` | — |

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | O14 申報者為訂單 OWNER 或 SITTER 任一方；`CONFIRMED` 與 `IN_PROGRESS` 皆可 | BASELINE O14、CQ-30、CQ-41 |
| BR-2 | O14 條件一：變更 `AWAITING_REFUND`（含系統建立的退款變更），不論退款 payment 為 `UNPAID` 或 `SUBMITTED`；退款無逾時，O14 是唯一的非合意出口 | BASELINE §3.4 退款、O14、CQ-30 |
| BR-3 | O14 條件二：變更 `AWAITING_SUPPLEMENT` 且補款 payment `SUBMITTED`，自目前這筆 `SUBMITTED` submission 的上傳時間起超過 `MODIFICATION_TIMEOUT_HOURS`（72h）未核對；駁回後重新上傳 → 以新 submission 上傳時間重新起算（`disputeAvailableAt = 該 submission 上傳時間 + 72h`）；補款 `UNPAID` 不可申報（由 PRD-023 逾時處理） | BASELINE O14、§3.4 補款、§5、CQ-42、CQ-64；使用者決定 2026-11-21（本卡 CQ-2） |
| BR-4 | O14 附帶：暫停自動結案（訂單已 `DISPUTED`）；建立 ADMIN 待辦 `type=MODIFICATION_PAYMENT_DISPUTE`，`(type, order_id) WHERE status='OPEN'` 唯一，與轉移同交易；待辦只出現在 ADMIN 待辦清單，不寄 Email、不發站內通知給 ADMIN | BASELINE O14、§3.6、CQ-55；使用者決定 2026-11-21（本卡 CQ-3） |
| BR-5 | `DISPUTED` 期間 payment 與變更的所有使用者操作 → 409 `ORDER_STATE_CONFLICT`；行程照常執行 | BASELINE §3.2、CQ-48、CQ-57 |
| BR-6 | ADMIN 調解結案時：未結束變更（`AWAITING_SUPPLEMENT` / `AWAITING_REFUND`）→ `REJECTED` 並記錄結束時間；`UNPAID` payment → `VOID`；`SUBMITTED` payment 依 BR-14（退款）、BR-15（補款）；ADMIN 不改 `total_amount`；待辦 `RESOLVED` | BASELINE O15、O17、§3.3、§3.4 狀態圖、CQ-18、CQ-48 |
| BR-7 | 有未結束變更時：O11 → 409 `MODIFICATION_IN_PROGRESS`；O12 不觸發；O13 → 409 `MODIFICATION_IN_PROGRESS` | BASELINE O11、O12、O13、§3.4 協商期間、CQ-17 |
| BR-8 | `completion_basis_at = max(各有效行程的結束時間, 最後一筆 SETTLED 變更請求的結束時間)`；`REJECTED` / `WITHDRAWN` / `EXPIRED` 不推遲；`DONE` 行程結束時間 = `completed_at + CARE_LOG_EDIT_HOURS`，`CLOSED_BY_SYSTEM` = 被關閉時間；`CANCELLED` 行程不計。搭配「發起變更需至少 1 個 `PENDING` 行程」（PRD-016 BR-2），反覆發起 / 撤回不能延後自動結案 | BASELINE §5、§3.4 可發起時機、CQ-17、CQ-40、CQ-69 |
| BR-9 | 訂閱逾期 / 停權不擋變更的發起、報價與回應、補款核對、退款上傳與款項爭議（屬既有訂單）；鎖定期間計價見 PRD-016 BR-18 | BASELINE §2.2、§3.4、CQ-19 |
| BR-10 | ADMIN 只能唯讀 `DISPUTED` 訂單與有 OPEN 待辦的訂單（含變更、payment、憑證、收款帳號快照）；其他訂單 → 403 `FORBIDDEN` | BASELINE §1.2、CQ-18、CQ-66 |
| BR-11 | O14 寫入端點帶 `Idempotency-Key`，同 key 回放；不同 key 重送 → 依當下狀態 409 | BASELINE §3.6 |
| BR-12 | 時間 UTC 儲存、Asia/Taipei 顯示；72h 判斷以 `now ≥ 起算點 + 72h` | BASELINE §5 |
| BR-13 | O14 爭議說明必填，1–500 字；不可附圖（API 不接受附件欄位）；說明寫入 `order_logs` payload 供 ADMIN 判斷 | 使用者決定 2026-11-21（本卡 CQ-1） |
| BR-14 | ADMIN 調解作廢 `SUBMITTED` 退款時，調解畫面對每筆必選「已匯出 / 未匯出」（未選 → 400 `VALIDATION_FAILED`），選擇寫入 `order_logs`。已匯出 → 同交易自動建立等額 `ADMIN_ADJUSTMENT` 退款（`VERIFIED`，計入已收淨額與調解退款上限檢查）；未匯出 → 只 `VOID` | BASELINE §3.3（CQ-48）；使用者決定 2026-11-21（本卡 CQ-4） |
| BR-15 | ADMIN 調解作廢 `SUBMITTED` 補款時，比照 O16：不登記任何金額，同交易通知雙方「此筆補款由雙方自行處理」；已收淨額不含該筆 | BASELINE O16；使用者決定 2026-11-21（本卡 CQ-5） |
| BR-16 | 多個錯誤同時成立時依 PRD-016 BR-28（BASELINE §3.7）回第一個。例：`DISPUTED` 且有 `AWAITING_REFUND` 的訂單發起新變更 → `ORDER_STATE_CONFLICT`；說明空白且訂單已 `DISPUTED` 的申報 → 400 `VALIDATION_FAILED` | BASELINE §3.7（CQ-70）；PRD-016 BR-28 |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 非該訂單當事人申報 | 拒絕 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | ADMIN 或角色不符呼叫申報 | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換身分後再操作」 |
| EX-3 | 方案不足 / 訂閱鎖定 | 不適用：申報不分方案，鎖定不擋（BR-9） | — | — |
| EX-4 | 訂單非 `CONFIRMED` / `IN_PROGRESS`（含已 `DISPUTED`） | 拒絕 | 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`） | 「此訂單目前無法申報款項爭議」 |
| EX-5 | 無 `AWAITING_REFUND` / `AWAITING_SUPPLEMENT` 變更 | 拒絕 | 409 `MODIFICATION_STATE_CONFLICT`（BASELINE §3.7） | 同上 |
| EX-6 | 補款 `UNPAID`，或 `SUBMITTED` 自目前 submission 上傳起未滿 72h | 拒絕；`SUBMITTED` 時 payload 帶 `disputeAvailableAt` = 目前 submission 上傳時間 + 72h | 409 `PAYMENT_STATE_CONFLICT`（BASELINE O14） | 「補款憑證上傳滿 72 小時仍未核對才可申報（可申報時間：YYYY-MM-DD HH:mm）」 |
| EX-7 | 雙方同時申報 | 鎖序列化；恰一筆成功 | 後到者 409 `ORDER_STATE_CONFLICT` | 「訂單已進入爭議處理」 |
| EX-8 | 申報與保母確認補款 / 飼主確認退款同時 | 鎖序列化；先確認 → 變更 `SETTLED`，申報 409 `MODIFICATION_STATE_CONFLICT`；先申報 → 確認 409 `ORDER_STATE_CONFLICT` | 依先後 | 依先後 |
| EX-9 | 鎖等待逾時 | 不改資料 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「訂單處理中，請稍後再試」 |
| EX-10 | 重複送出（同 key） | 回放，不重複建待辦 | 同第一次 | 同第一次 |
| EX-11 | 爭議期間任一方操作變更 / payment | 拒絕，不改資料 | 409 `ORDER_STATE_CONFLICT` | 「訂單爭議處理中，請等候平台處理」 |
| EX-12 | Resend 寄信失敗 | 轉移照常提交；站內通知已寫入；Email 排程重試最多 5 次 | 原請求 2xx | 站內通知正常出現 |
| EX-13 | GCS | 不適用：申報不可附圖、不上傳檔案（BR-13） | — | — |
| EX-14 | 爭議說明空白或超過 500 字 | 拒絕，不改資料、不建待辦 | 400 `VALIDATION_FAILED` | 「請填寫爭議說明（500 字以內）」 |
| EX-15 | ADMIN 調解時未對 `SUBMITTED` 退款選「已匯出 / 未匯出」 | 拒絕，整筆調解不生效（變更、payment、訂單、待辦皆不變） | 400 `VALIDATION_FAILED`（payload 指出 payment id） | 「請標示每筆已上傳的退款是否已實際匯出」 |
| EX-16 | 同一請求違反多條規則 | 依 BR-16 判斷順序回第一個 | 依 BR-16 | 依該錯誤 |

## 8. 驗收標準（AC）

> 共用前提（除非另寫）：快照單價 500；時間皆 Asia/Taipei。

### 子模組 1：款項爭議申報

- [ ] **AC-1.1 (飼主於等待退款時申報)**：Given `IN_PROGRESS` 訂單，變更 `AWAITING_REFUND`、退款 payment `UNPAID`，When OWNER 填寫說明「保母遲未退款」申報款項爭議，Then 200，訂單 `IN_PROGRESS → DISPUTED`；`admin_tasks` 新增一筆 `type=MODIFICATION_PAYMENT_DISPUTE`、`OPEN`（關聯訂單與退款 payment），出現在 ADMIN 待辦清單，ADMIN 無 Email 與站內通知；變更仍 `AWAITING_REFUND`、payment 仍 `UNPAID`；`order_logs` 新增一筆（payload 含說明）；SITTER 收到「訂單已進入爭議處理」。對應：T1、BR-1、BR-2、BR-4、BR-13
- [ ] **AC-1.2 (保母於 CONFIRMED 訂單申報)**：Given `CONFIRMED` 訂單（整筆取消、所有行程 `CANCELLED`），變更 `AWAITING_REFUND`、退款 payment `SUBMITTED`（飼主遲未確認），When SITTER 申報，Then 200、訂單 `CONFIRMED → DISPUTED`、待辦 `OPEN`、OWNER 收到通知。Given 退款變更為系統建立（`initiatedBy=SYSTEM`），When 任一方申報，Then 同樣 200、`DISPUTED`。對應：T1、BR-1、BR-2
- [ ] **AC-1.3 (補款待核對滿 72h，自目前 submission 起算)**：Given 變更 `AWAITING_SUPPLEMENT`，補款 payment 於 2026-11-21 10:00 上傳為 `SUBMITTED`，When 任一方於 2026-11-24 09:59 申報，Then 409 `PAYMENT_STATE_CONFLICT`，payload `disputeAvailableAt=2026-11-24T02:00:00Z`；When 於 2026-11-24 10:00 之後申報，Then 200、訂單 `DISPUTED`。Given 補款 `due_at` = 2026-11-24 13:00，第一筆 submission 於 2026-11-21 14:00 上傳、2026-11-22 09:00 被駁回、2026-11-22 10:00 重新上傳，When 於 2026-11-24 15:00 申報，Then 409 `PAYMENT_STATE_CONFLICT`、`disputeAvailableAt=2026-11-25T02:00:00Z`；When 於 2026-11-25 10:00 之後申報，Then 200、`DISPUTED`。對應：T1、BR-3、EX-6
- [ ] **AC-1.4 (不符申報條件)**：Given 訂單無未結束變更，或變更為 `REQUESTED` / `QUOTED`，When 申報，Then 409 `MODIFICATION_STATE_CONFLICT`。Given 變更 `AWAITING_SUPPLEMENT` 且補款 payment `UNPAID`，When 申報，Then 409 `PAYMENT_STATE_CONFLICT`（無 `disputeAvailableAt`）。Given 訂單已 `DISPUTED` / `COMPLETED` / `CANCELLED`，When 申報，Then 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`）。以上皆不建待辦、訂單狀態不變。對應：EX-4、EX-5、EX-6
- [ ] **AC-1.5 (越權)**：Given 訂單屬 OWNER-A / SITTER-S，When OWNER-B 申報，Then 403 `FORBIDDEN`；When ADMIN 呼叫，Then 403 `ROLE_MISMATCH`；When 以不存在的訂單 id 申報，Then 404 `NOT_FOUND`。以上皆不建待辦。對應：EX-1、EX-2
- [ ] **AC-1.6 (併發與冪等)**：Given 符合 AC-1.1 條件，When OWNER 與 SITTER 同時申報，Then 恰一筆 200，另一筆 409 `ORDER_STATE_CONFLICT`，`admin_tasks` 只有一筆 `OPEN`；When 成功者以同 key 重送，Then 回放相同 body、待辦仍一筆。對應：BR-4、BR-11、EX-7、EX-10
- [ ] **AC-1.7 (申報與確認款項併發)**：Given 退款 payment `SUBMITTED`，When OWNER 確認收到退款與 SITTER 申報同時送出，Then 先確認者成功時變更 `SETTLED`、申報 409 `MODIFICATION_STATE_CONFLICT`、訂單非 `DISPUTED`；先申報者成功時訂單 `DISPUTED`、確認 409 `ORDER_STATE_CONFLICT`、payment 仍 `SUBMITTED`。對應：EX-8
- [ ] **AC-1.8 (爭議說明)**：Given 符合 AC-1.1 條件，When 申報時說明空白或 501 字，Then 400 `VALIDATION_FAILED`，訂單狀態不變、不建待辦；When 說明 500 字，Then 200、訂單 `DISPUTED`；申報畫面無附件上傳元件。對應：BR-13、EX-14

### 子模組 2：爭議凍結、調解結果與協商期間限制

- [ ] **AC-2.1 (DISPUTED 凍結)**：Given AC-1.1 的 `DISPUTED` 訂單（變更 `AWAITING_REFUND`），When SITTER 上傳退款憑證、OWNER 確認收到退款、任一方發起新變更或撤回，Then 皆 409 `ORDER_STATE_CONFLICT`（發起不回 `MODIFICATION_IN_PROGRESS`，依 BR-16）；變更、payment、submission 皆無變化。對應：BR-5、BR-16、EX-11
- [ ] **AC-2.2 (爭議期間行程照常)**：Given `DISPUTED` 訂單仍有 `PENDING` 行程 V3，When SITTER 開始 V3（PRD-008），Then V3 `IN_PROGRESS`，訂單仍 `DISPUTED`。對應：BR-5
- [ ] **AC-2.3 (調解結果：UNPAID 退款)**：Given `DISPUTED` 訂單、變更 `AWAITING_REFUND`、退款 payment `UNPAID`、已收淨額 1500、`total_amount` 1000，When ADMIN 調解結案（O15 或 O17，PRD-020），Then 同一交易：變更 `REJECTED`、結束時間 = 調解時間、退款 payment `VOID`、`total_amount` 仍 1000、已收淨額仍 1500、無 `ADMIN_ADJUSTMENT` 因此建立、待辦 `RESOLVED`。對應：T2、T3、BR-6
- [ ] **AC-2.4 (調解結果：SUBMITTED 退款與補款)**：Given `DISPUTED` 訂單、變更 `AWAITING_REFUND`、退款 payment `SUBMITTED` `amount=500`、已收淨額 1500。When ADMIN 調解標示該退款「已匯出」，Then 同一交易：退款 payment `VOID`、新增 payment `direction=REFUND`、`method=ADMIN_ADJUSTMENT`、`amount=500`、`VERIFIED`，已收淨額 = 1000，`order_logs` 記錄「已匯出」。When 改為標示「未匯出」，Then 退款 payment `VOID`、無 `ADMIN_ADJUSTMENT`、已收淨額仍 1500，`order_logs` 記錄「未匯出」。When 未標示即送出，Then 400 `VALIDATION_FAILED`、變更 / payment / 訂單 / 待辦皆不變。Given `DISPUTED` 訂單、變更 `AWAITING_SUPPLEMENT`、補款 payment `SUBMITTED` `amount=500`、已收淨額 1500，When ADMIN 調解結案，Then 補款 payment `VOID`、變更 `REJECTED`、無任何新 payment、已收淨額仍 1500，雙方收到「此筆補款由雙方自行處理」。對應：T2、T3、T4、BR-14、BR-15、EX-15
- [ ] **AC-2.5 (ADMIN 唯讀範圍)**：Given `DISPUTED` 訂單，When ADMIN 讀取訂單詳情，Then 200，回應含變更歷史、payments、憑證檢視連結與補款收款帳號快照（唯讀、無編輯入口）。Given 非 `DISPUTED` 但有 OPEN 待辦的訂單，When ADMIN 讀取，Then 200。Given 非 `DISPUTED` 且無 OPEN 待辦的訂單，When ADMIN 讀取，Then 403 `FORBIDDEN`。對應：BR-10
- [ ] **AC-2.6 (協商期間不可結案、不自動結案)**：Given `IN_PROGRESS` 訂單 V1、V2 `DONE`（日誌已送出）、V3 因整筆取消 `CANCELLED`，且有一筆 `AWAITING_REFUND` 變更。When OWNER 確認結案（PRD-009），Then 409 `MODIFICATION_IN_PROGRESS`；When OWNER 回報問題（PRD-009），Then 409 `MODIFICATION_IN_PROGRESS`；訂單仍 `IN_PROGRESS`。Given 同上且 `completion_basis_at + AUTO_COMPLETE_HOURS` 已過，When 自動結案排程執行，Then 訂單仍 `IN_PROGRESS`。對應：BR-7
- [ ] **AC-2.7 (結案基準只看 SETTLED 變更)**：Given V1 `DONE`（`completed_at` 2026-11-20 10:00 → 結束時間 2026-11-21 10:00）、V2 `CLOSED_BY_SYSTEM`（2026-11-19 00:05 關閉）、V3 因變更 `CANCELLED`，該變更於 2026-11-22 15:00 `SETTLED`，When 查詢訂單，Then `completion_basis_at` = 2026-11-22 15:00（`2026-11-22T07:00:00Z`）；OWNER 於 2026-11-24 14:59 回報問題，Then 200、訂單 `DISPUTED`；若無人操作，Then 自動結案於 2026-11-24 15:00 後第一次排程（≤ 15 分鐘）發生。Given V1、V2 同上、V3 `DONE`（`completed_at` 2026-11-21 08:00 → 結束時間 2026-11-22 08:00），另有一筆只含新增的變更於 2026-11-20 20:00 發起（當時 V3 仍 `PENDING`）、2026-11-22 15:00 被 OWNER 撤回，When 查詢訂單，Then 該變更結束時間 = 2026-11-22 15:00，但 `completion_basis_at` = 2026-11-22 08:00（`2026-11-22T00:00:00Z`，撤回不推遲）；若無人操作，Then 自動結案於 2026-11-24 08:00 後第一次排程發生。對應：BR-8
- [ ] **AC-2.8 (訂閱鎖定 / 停權不擋)**：Given 保母 S 鎖定前為 PRO，訂閱已於 2026-11-20 到期（2026-11-21 00:00 起鎖定）或被停權。When S 對 `CONFIRMED`、有 `PENDING` 行程、無未結束變更的訂單發起 `CHANGE`（減少 1 個行程、不帶加減），Then 201、變更 `QUOTED`、新總額 = 系統計算值。When S 對 `REQUESTED` 報價（不帶加減），Then 200、`QUOTED`。When S 拒絕 `REQUESTED`（附原因），Then 200、`REJECTED`。When S 撤回自己發起的 `QUOTED`，Then 200、`WITHDRAWN`。When S 確認 `SUBMITTED` 補款，Then 200、payment `VERIFIED`、變更 `SETTLED`。When S 對 `UNPAID` 退款上傳憑證（PRD-007），Then 201、payment `SUBMITTED`。When S 對 `AWAITING_REFUND` 的訂單申報款項爭議，Then 200、訂單 `DISPUTED`。以上皆不回 403 `SUBSCRIPTION_LOCKED`。When 飼主在 S 的公開頁送出新預約，Then 403 `SUBSCRIPTION_LOCKED`（見 PRD-005）。鎖定期間帶加減的計價見 PRD-016 AC-2.8。對應：BR-9

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | `orders.status`（O14 → `DISPUTED`）；`admin_tasks`（新增款項爭議待辦，SD-ORDER）；`modification_requests`（調解結案時 `REJECTED` + 結束時間）/ `payments`（調解時 `VOID`；已匯出退款另建 `ADMIN_ADJUSTMENT`），由 PRD-020 觸發；`orders.completion_basis_at`（只在變更 `SETTLED` 時重算）；`order_logs`（含爭議說明、ADMIN 的「已匯出 / 未匯出」選擇） |
| 畫面 | 協商頁「申報款項爭議」按鈕（雙方，依 4.1 條件顯示，未滿 72h 顯示可申報時間）與說明輸入框（必填、500 字上限、無附件）；爭議中（`DISPUTED`）訂單的詳情頁橫幅與隱藏的動作依 PRD-009 §4.4 `DISPUTED` 列（不分 O13 / O14，本卡不另定義）；ADMIN 爭議訂單唯讀頁與調解畫面的「已匯出 / 未匯出」必選欄（PRD-020） |
| 通知 | O14 → 另一方（站內 + Email）；ADMIN 只經待辦清單（`type=MODIFICATION_PAYMENT_DISPUTE`）得知，不寄 Email（BR-4）；調解作廢 `SUBMITTED` 補款 → 雙方「此筆補款由雙方自行處理」 |
| 排程 | 無新增；既有自動結案排程（PRD-009）需判斷「有未結束變更」與 `completion_basis_at`（§4.5） |


**E2E 選擇器（testid，2026-11-22 補充，SD-ORDER CQ-10）**

| 元素 | testid |
|------|--------|
| 申報款項爭議入口 | `modification-btn-payment-dispute` |
| 爭議說明輸入 / 送出 | `payment-dispute-input-description`、`payment-dispute-btn-submit` |
| 可申報時間提示（未滿 72h） | `payment-dispute-available-at` |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | O14 申報是否必填說明、可否附圖 | 說明必填（1–500 字），不附圖；ADMIN 以既有憑證與 `order_logs` 判斷。已寫入 §4.1-2、T1、BR-13、EX-13、EX-14、AC-1.8 | 已決（使用者 2026-11-21） |
| CQ-2 | 「補款 `SUBMITTED` 超過 72h 未核對」的起算點：BASELINE 未定義（submission 上傳時間？`due_at`？）；駁回後重傳是否重新起算 | 以目前這筆 `SUBMITTED` submission 的上傳時間起算；駁回後重傳重新起算（已寫入 BASELINE O14、CQ-64）。已寫入 §4.1-1、T1、BR-3、EX-6、AC-1.3 | 已決（使用者 2026-11-21） |
| CQ-3 | 款項爭議待辦的 `type` 值與是否主動通知 ADMIN | `admin_tasks.type=MODIFICATION_PAYMENT_DISPUTE`；只出現在 ADMIN 待辦清單，不寄 Email。已寫入 §4.1-3、BR-4、§9、AC-1.1 | 已決（使用者 2026-11-21） |
| CQ-4 | ADMIN 調解作廢 `SUBMITTED` 退款時，「已實際匯出」（BASELINE §3.3 須登記等額 `ADMIN_ADJUSTMENT` 的條件）由誰判定 | ADMIN 調解畫面對每筆 `SUBMITTED` 退款必選「已匯出 / 未匯出」（必填，寫入 `order_logs`）；已匯出 → 同交易自動建立等額 `ADMIN_ADJUSTMENT`（`VERIFIED`）；未匯出 → 只 `VOID`。已寫入 §4.2-3、§4.5、T3、T4、BR-14、EX-15、AC-2.4 | 已決（使用者 2026-11-21） |
| CQ-5 | ADMIN 調解作廢 `SUBMITTED` 補款（飼主可能已匯款）時怎麼記 | 比照 O16：不登記任何金額，通知雙方「此筆補款由雙方自行處理」；已收淨額不含該筆。已寫入 §4.2-3、§4.5、BR-15、§9、AC-2.4 | 已決（使用者 2026-11-21） |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-22 | 核准後回寫，待使用者同意 2026-11-22：§9 畫面「爭議中訂單顯示『訂單爭議處理中』並隱藏變更 / 付款動作」改為引用 PRD-009 §4.4 `DISPUTED` 列（橫幅文字、testid、隱藏動作以 PRD-009 為準，與 PRD-009 §9 跨卡「PRD-024 引用本卡 §4.4」對齊）。不改 AC 與規則；狀態維持 Approved | SD-FULFILL 第 2 輪審查回寫（主流程交辦 2026-11-22） |
| 2026-11-22 | §9 補 E2E testid（SD-ORDER CQ-10，屬畫面識別補充，不改規格） | 主流程 |
| 2026-11-22 | 狀態改 Approved | 使用者核准 |
| 2026-11-21 | 建立：由原 PRD-016 拆出款項爭議、DISPUTED 凍結、協商期間結案限制、訂閱鎖定不擋變更 | 原 PRD-016 依 DOMAIN-BASELINE 重寫並拆卡（子模組 > 3） |
| 2026-11-21 | 第 1 輪 #53、第 2 輪 PRD 衝突 #5：原 PRD-016 備註「飼主長時間未確認可發起爭議」改為 O14——OWNER / SITTER 任一方、`CONFIRMED` / `IN_PROGRESS`、`AWAITING_REFUND` 或補款 `SUBMITTED` 72h 未核對（子模組 1） | 第 1 輪 #53、第 2 輪 #5、CQ-30、CQ-41、CQ-42 |
| 2026-11-21 | 原 PRD-016「暫停自動結案排程」改依 O11–O13 與 `completion_basis_at`（子模組 2）；新增 DISPUTED 凍結與 ADMIN 調解對變更的結果 | BASELINE O11–O15、O17、CQ-17、CQ-48 |
| 2026-11-21 | 待釐清 CQ-1~3 全部依建議預設值定案並寫入正文：爭議說明必填 1–500 字、不附圖（新增 BR-13、EX-14、AC-1.8）；72h 自目前 submission 上傳時間起算、駁回重傳重新起算（§4.1、T1、BR-3、EX-6、AC-1.3，對齊 BASELINE O14 / CQ-64）；待辦 `type=MODIFICATION_PAYMENT_DISPUTE`、不通知 ADMIN（§4.1、BR-4、§9、AC-1.1）。另：T1 錯誤碼對齊 BASELINE O14；`MODIFICATION_STATE_CONFLICT` 改標 BASELINE §3.7 錯誤碼（EX-5）；鎖定期間依 FREE 規則計價（§4.4、BR-9、AC-2.8，BASELINE CQ-66）；子模組 1 AC 7 → 8 | 使用者決定 2026-11-21；DOMAIN-BASELINE 第 2 版修訂（O14、CQ-64、CQ-66、§3.7） |
| 2026-11-22 | 審查第 1 輪 #1：新增 BR-14（`SUBMITTED` 退款由 ADMIN 必選「已匯出 / 未匯出」，已匯出自動建 `ADMIN_ADJUSTMENT`）、T4、EX-15；原 AC-2.3 拆為 AC-2.3（UNPAID）與 AC-2.4（SUBMITTED 已匯出 / 未匯出兩個 Given） | 審查第 1 輪 #1；使用者決定 2026-11-21（CQ-4） |
| 2026-11-22 | 審查第 1 輪 #2：AC-2.8 的 Then 改為每個動作的明確狀態碼與結果（發起 201 `QUOTED`、報價 200、拒絕 200、撤回 200、確認補款 200 `SETTLED`、上傳退款 201、申報 200 `DISPUTED`、公開頁 403），刪除與 PRD-016 重複的 `PLAN_REQUIRED` 驗收 | 審查第 1 輪 #2；主流程一致性修正 7 |
| 2026-11-22 | 審查第 1 輪 #3：§3、BR-10、AC-2.5 的 ADMIN 可讀範圍改「`DISPUTED` 或有 OPEN 待辦」；AC-2.5 補「有 OPEN 待辦 → 200」Given 與收款帳號快照（BASELINE CQ-66） | 審查第 1 輪 #3；BASELINE §1.2 |
| 2026-11-22 | 審查第 1 輪 #4：表頭與引言註明 PRD-009 / PRD-020 待依 baseline 改寫；新增 §4.5 列出兩卡改寫時必須提供的端點與排程行為（錯誤碼、排程條件、調解副作用），對應本卡 AC。00-PRD-PLAN 排程調整不在本次修改範圍（只改三檔），留待主流程處理 | 審查第 1 輪 #4；主流程一致性修正 9 |
| 2026-11-22 | 審查第 1 輪 #5：T2 刪除「其他未結束狀態」，只留 `AWAITING_SUPPLEMENT` / `AWAITING_REFUND`（BR-6 同步） | 審查第 1 輪 #5；BASELINE §3.4 狀態圖 |
| 2026-11-22 | 審查第 1 輪 #6：新增 BR-15（調解作廢 `SUBMITTED` 補款比照 O16，不登記金額、通知雙方），AC-2.4 補 Given | 審查第 1 輪 #6；使用者決定 2026-11-21（CQ-5） |
| 2026-11-22 | 審查第 1 輪 #7：§4.4-2、BR-9 改為引用 PRD-016 BR-17 / BR-18 / AC-2.8，本卡不再定義或驗收鎖定期間計價 | 審查第 1 輪 #7；主流程一致性修正 7 |
| 2026-11-22 | 審查第 1 輪 #8：風險已由 BASELINE CQ-69 處理——發起需至少 1 個 `PENDING` 行程、只有 `SETTLED` 變更推遲 `completion_basis_at`；§4.3-2、BR-8 改寫，AC-2.7 補「撤回不推遲」Given（取代審查 PRD-016 #5 建議的「`WITHDRAWN` 推遲」範例） | 審查第 1 輪 #8；BASELINE CQ-69；主流程一致性修正 1 |
| 2026-11-22 | 審查第 1 輪 #9：AC-1.3 的「補款 `UNPAID`」Given 移至 AC-1.4 | 審查第 1 輪 #9 |
| 2026-11-22 | 審查第 1 輪 #10：新增 BR-16 引用 PRD-016 BR-28 判斷順序；AC-2.1 註明發起回 `ORDER_STATE_CONFLICT` 不回 `MODIFICATION_IN_PROGRESS` | 審查第 1 輪 #10；BASELINE §3.7（CQ-70） |
| 2026-11-22 | 其他：T2 附帶「記錄結束時間」、AC-2.3 斷言；原 AC-2.5 / AC-2.6（O11、O13、O12）合併為 AC-2.6；AC 總數 16 不變（8 / 8）；子模組 2 改名；CQ-4、CQ-5 已決；狀態 Needs Clarification → In Review | 主流程一致性修正 3、7；使用者決定 2026-11-21 |
