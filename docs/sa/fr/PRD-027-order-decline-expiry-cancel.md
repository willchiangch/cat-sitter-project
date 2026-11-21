# PRD-027: 保母婉拒、評估逾期與付款前保母取消

| 項目 | 內容 |
|------|------|
| 狀態 | Needs Clarification |
| 建立日期 | 2026-11-21 |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0） |
| 測試情境 | TS-027 |
| 相依 PRD | PRD-006（評估頁、報價 O2；本卡自 PRD-006 拆出）；PRD-005（O1 寫入 `review_expires_at`）；PRD-026（飼主撤回 O3 OWNER / O6）；PRD-007（首次收款 payment、駁回、O8 付款逾期）；PRD-010（轉介本身）；PRD-014（通知偏好） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1、§1.2（角色、資源歸屬）；§2.2（訂閱鎖定範圍、月額度計數時點）；§3.1、§3.2 O3 / O4 / O7（轉移與前置條件；期限以時間判斷及取消類操作例外 CQ-61；取消的共同附帶動作 CQ-65）；§3.3（payment `UNPAID → VOID`、`SUBMITTED` 不得由保母取消）；§3.6（鎖、稽核、通知、冪等、排程）；§3.7（錯誤碼）；§5（`review_expires_at`、`due_at`、排程頻率） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念只能**引用** DOMAIN-BASELINE，不得在這裡重新定義。
> 本卡是 PRD-006 拆卡（2026-11-21）：處理保母評估後「不成立」的三個出口。飼主撤回不在本卡（PRD-026）；付款逾期 O8 不在本卡（PRD-007）。

---

## 1. 背景與目的

保母評估後不一定接單；保母一直不處理時訂單也不能永遠卡在 `PENDING`；報價後若保母發現無法服務，在飼主付款前也需要出口。本卡定義三個出口：保母婉拒（O3 SITTER，可接轉介）、評估逾期由系統取消（O4）、付款前保母取消（O7）。三者都讓訂單進入 `CANCELLED`、同交易取消剩餘行程與作廢未結束 payment，並通知飼主；保母取消不能在飼主已上傳付款憑證後發生。

## 2. 使用者故事

- **US-1** 身為保母，我希望婉拒不適合的預約並告知原因，以便飼主盡快另找保母。
- **US-2** 身為保母，我希望婉拒時能順手轉介信任圈夥伴，以便不讓飼主落空。
- **US-3** 身為飼主，我希望保母一直沒回應時預約會自動取消並通知我，以便不必一直等待。
- **US-4** 身為保母，我希望報價後、飼主付款前發現無法服務時能取消訂單並說明原因，以便不讓飼主付了款才被取消。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 婉拒預約（O3） | ✗（飼主撤回見 PRD-026） | 本人訂單 | ✗ | ✗ | 無；訂閱鎖定、月額度已滿皆不擋（BR-7） |
| 婉拒後轉介 | ✗ | 本人訂單（規則見 PRD-010） | ✗ | ✗ | 依 PRD-010 |
| 評估逾期取消（O4） | ✗ | ✗ | ✗ | ✗ | 僅 SYSTEM 排程 |
| 付款前取消（O7） | ✗（飼主撤回見 PRD-026） | 本人訂單 | ✗ | ✗ | 無；訂閱鎖定不擋（BR-7） |

- 「本人訂單」以 token 身分判斷；存在但不屬於呼叫者 → 403 `FORBIDDEN`，不存在 → 404 `NOT_FOUND`；`currentRole` 不符 → 403 `ROLE_MISMATCH`；未登入 → 401 `UNAUTHENTICATED`（baseline §1.1、§1.2）。
- 排程端點 `/api/internal/cron/*` 只接受 Cloud Scheduler 身分（baseline §3.6）。

## 4. 主流程

### 4.1 保母婉拒（O3 SITTER）

1. 保母在評估頁（PRD-006）按「婉拒預約」→ 對話框要求填寫原因，提供「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」快速帶入文字（帶入後可編輯，送出的仍是文字），並提示「婉拒後飼主將收到通知，含您填寫的原因」。
2. 保母送出（帶 `Idempotency-Key`）→ 系統鎖訂單列，檢查：訂單 `PENDING`、原因 1–200 字。**不檢查** `review_expires_at`：已過評估期限、但排程尚未把訂單寫成 `CANCELLED` 時仍允許婉拒（baseline §3.2 例外、CQ-61）→ 同一交易：訂單 `PENDING → CANCELLED`、`cancelledBy = SITTER`、記錄原因；剩餘 `PENDING` 行程 → `CANCELLED`（BR-12）；寫 `order_logs`（`ORDER_REJECTED`，含原因）；通知飼主。
3. 回應成功後，對話框顯示「轉介信任圈夥伴」入口；保母選擇後進入 PRD-010 轉介流程（另一個請求）。不轉介則關閉。
4. 飼主訂單詳情顯示「保母已婉拒此預約」與原因；保母端該訂單移到「歷史」，無任何接單 / 報價按鈕。

### 4.2 評估逾期（O4）

1. 排程每 15 分鐘執行一次（baseline §5），先唯讀查出 `status = PENDING` 且 `now ≥ review_expires_at` 的訂單。
2. 逐筆以獨立交易處理：鎖訂單列（`SKIP LOCKED`）後重驗條件 → 仍成立則 `PENDING → CANCELLED`、`cancelledBy = SYSTEM`、原因「保母未於期限內回覆」；剩餘 `PENDING` 行程 → `CANCELLED`（BR-12）；寫 `order_logs`（`ORDER_REVIEW_EXPIRED`）；通知雙方。條件不成立（已被報價 / 撤回 / 婉拒）則略過。
3. 一筆失敗不影響其他筆；排程可重跑。
4. 排程未跑之前：非取消類操作（報價）在期限後依時間被拒（PRD-006 AC-3.4）；取消類操作（保母婉拒、飼主撤回 PRD-026）仍允許，與排程以鎖訂單列序列化，先取得鎖者決定 `cancelledBy`（baseline §3.2、CQ-46、CQ-61）。

### 4.3 付款前保母取消（O7）

1. 保母在 `PENDING_PAYMENT` 訂單詳情按「取消訂單」→ 對話框要求填寫原因（同 4.1 的快速帶入），提示「取消後飼主將收到通知，含您填寫的原因」。
2. 保母送出（帶 `Idempotency-Key`）→ 系統鎖訂單列，檢查：訂單 `PENDING_PAYMENT`、首次收款 payment 為 `UNPAID`、原因 1–200 字。**不檢查** `due_at`：已過付款期限、但 O8 排程（PRD-007）尚未把訂單寫成 `CANCELLED` 時仍允許（baseline §3.2 例外、CQ-61）→ 同一交易：payment `UNPAID → VOID`；訂單 `PENDING_PAYMENT → CANCELLED`、`cancelledBy = SITTER`、記錄原因；剩餘 `PENDING` 行程 → `CANCELLED`（BR-12）；寫 `order_logs`（`ORDER_CANCELLED_BY_SITTER`）；通知飼主。
3. payment 為 `SUBMITTED`（飼主已上傳憑證）時不可取消，畫面引導保母先核對或駁回（PRD-007）；駁回後 payment 回 `UNPAID`，才能取消。
4. 取消成功後不提供轉介入口（BR-13）。

## 5. 狀態轉移

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1（O3） | 訂單 `PENDING` | `CANCELLED`（`cancelledBy=SITTER`） | SITTER | 原因 1–200 字；不看 `review_expires_at`（CQ-61） | 409 `ORDER_STATE_CONFLICT`；400 `VALIDATION_FAILED` |
| T2（O4） | 訂單 `PENDING` | `CANCELLED`（`cancelledBy=SYSTEM`） | SYSTEM | `now ≥ review_expires_at` | 不符則略過 |
| T3（O7） | 訂單 `PENDING_PAYMENT` | `CANCELLED`（`cancelledBy=SITTER`） | SITTER | 首次收款 payment `UNPAID`；原因 1–200 字；不看 `due_at`（CQ-61） | 409 `PAYMENT_ALREADY_SUBMITTED` / `ORDER_STATE_CONFLICT`；400 `VALIDATION_FAILED` |
| T4 | 首次收款 payment `UNPAID` | `VOID` | SYSTEM（隨 T3 同交易） | T3 成功 | — |
| T5 | 行程 `PENDING` | `CANCELLED` | SYSTEM（隨 T1 / T2 / T3 同交易） | T1 / T2 / T3 成功 | — |

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 婉拒只在 `PENDING`；需原因；問卷狀態不影響婉拒 | baseline O3、§3.5；BR §3.6 步驟 4 |
| BR-2 | 婉拒通知飼主，內容含保母原因，連到該訂單詳情；飼主訂單詳情顯示「保母已婉拒此預約」與原因；送出前確認框文字與實際行為一致 | 沿用原 PRD-006 D 段（2026-10-26 fix-batch13 K1）；BR §3.6「取消接單需信件通知」 |
| BR-3 | 婉拒與轉介為兩步驟：婉拒先成立，成功後提供轉介入口；轉介是獨立請求，成功與否不影響已成立的婉拒（轉介失敗不回滾）；轉介對象、黑名單過濾、通知內容依 PRD-010 | BR §5.3 轉介流程 1；PRD-010 B 段；使用者決定 2026-11-21（CQ-3） |
| BR-4 | 評估逾期：`now ≥ review_expires_at` 且仍 `PENDING` → `CANCELLED`、`cancelledBy=SYSTEM`，通知雙方 | baseline O4、§5、CQ-12 |
| BR-5 | 排程每 15 分鐘；候選唯讀查出後逐筆獨立交易、鎖後重驗、`SKIP LOCKED`、可重跑；判斷以「`now ≥` 到期時間」為準，不依賴排程頻率 | baseline §3.6 排程、§5 |
| BR-6 | 付款前保母取消：只在 `PENDING_PAYMENT` 且首次收款 payment `UNPAID`；`SUBMITTED` 時只能駁回（PRD-007），取消 → 409 `PAYMENT_ALREADY_SUBMITTED`；需原因 | baseline O7、§3.3、CQ-13 |
| BR-7 | 婉拒、付款前取消不受訂閱鎖定限制（baseline 鎖定範圍只列公開頁預約、報價、方案 / 問卷 / 公開檔案設定）；也不受月接單額度影響（額度只擋 O1 與 O2） | baseline §2.2、O1、O2、CQ-19、CQ-62 |
| BR-8 | 期限例外：婉拒、付款前取消的結果本身就是取消，訂單尚未進入終態前，即使已過 `review_expires_at` / `due_at` 仍允許，`cancelledBy = SITTER`、寫本卡的 log 動作（`ORDER_REJECTED` / `ORDER_CANCELLED_BY_SITTER`），不回 `DEADLINE_PASSED`。與排程（O4 / O8）同時發生時以鎖訂單列序列化：保母先 → 排程鎖後重驗略過；排程先 → 保母 409 `ORDER_STATE_CONFLICT` | baseline §3.2（期限例外）、CQ-46、CQ-61；使用者決定 2026-11-21（CQ-2，取代原建議「套用 `DEADLINE_PASSED`」） |
| BR-9 | 原因 1–200 字，去除前後空白後不得為空；前端提供「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」快速帶入文字，送出與保存的是文字，不另存選項代碼 | baseline O3、O7（需原因）；使用者決定 2026-11-21（CQ-1） |
| BR-10 | 每次轉移寫一筆 `order_logs`，與狀態變更同交易；通知以 outbox 同交易寫入站內通知，Email 依 PRD-014 偏好由排程寄送 | baseline §3.6 |
| BR-11 | 婉拒、取消帶 `Idempotency-Key`，依 baseline §3.6 回放 | baseline §3.6 |
| BR-12 | 取消的共同附帶動作：T1 / T2 / T3 同一交易內把剩餘 `PENDING` 行程轉 `CANCELLED`、未結束 payment 轉 `VOID`。O3 / O4 時訂單尚未報價、沒有 payment，實際只取消行程；O7 時首次收款 payment `UNPAID → VOID`（T4）。取消不影響月接單額度（額度只在 `CONFIRMED` 計數） | baseline §3.2（取消的共同附帶動作）、CQ-65、§2.2；使用者決定 2026-11-21 |
| BR-13 | O7 付款前取消成功後不提供轉介入口；轉介只在婉拒（O3）後提供 | BR §5.3（只寫評估頁）；使用者決定 2026-11-21（CQ-4） |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 其他保母婉拒 / 取消 | 拒絕，不改資料 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | 以 OWNER 角色呼叫婉拒 / 保母取消 | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換至保母身分」 |
| EX-3 | ADMIN 呼叫婉拒 / 保母取消 | 拒絕（ADMIN 不是觸發者；ADMIN 作廢為 O16，見 PRD-007） | 403 `ROLE_MISMATCH` | — |
| EX-4 | 訂單不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此訂單」 |
| EX-5 | 原因空白或超過 200 字 | 拒絕 | 400 `VALIDATION_FAILED` | 欄位下方「請填寫原因」/「原因最多 200 字」 |
| EX-6 | 婉拒時訂單已非 `PENDING`（已報價、飼主已撤回、已被 O4 取消） | 拒絕 | 409 `ORDER_STATE_CONFLICT` | 「訂單狀態已變更，請重新整理」 |
| EX-7 | 取消時訂單非 `PENDING_PAYMENT`（已 `CONFIRMED`、已取消含 O8） | 拒絕；`CONFIRMED` 後改走變更協商整筆取消（PRD-016） | 409 `ORDER_STATE_CONFLICT` | 「訂單已成立，如需取消請申請訂單變更」或「訂單狀態已變更，請重新整理」 |
| EX-8 | 取消時飼主已上傳憑證（payment `SUBMITTED`） | 拒絕，不改資料 | 409 `PAYMENT_ALREADY_SUBMITTED` | 「飼主已上傳付款憑證，請先核對或駁回」與核對頁連結（PRD-007） |
| EX-9 | 已過 `review_expires_at`（婉拒）/ `due_at`（取消），排程尚未處理 | 照常執行（BR-8），`cancelledBy = SITTER` | 200 | 同正常流程 |
| EX-10 | 跨角色併發：保母取消與飼主上傳憑證（PRD-007）同時 | 鎖訂單列序列化。上傳先 → 取消得 EX-8；取消先 → 上傳得 PRD-007 定義的錯誤 | 409 | 依先後 |
| EX-11 | 跨角色併發：保母婉拒與飼主撤回（PRD-026）同時 | 先到者生效（`cancelledBy` 記先到者），後到者 EX-6 | 409 `ORDER_STATE_CONFLICT` | 同 EX-6 |
| EX-12 | 排程（O4 / O8）與保母婉拒 / 取消在期限後同時 | 鎖訂單列序列化：保母先 → 排程鎖後重驗略過，不寫逾期 log、不發逾期通知；排程先 → 保母得 EX-6 / EX-7。排程與保母報價同時：報價在 `now ≥ review_expires_at` 時已被 PRD-006 EX-10 擋下 | 保母後到 409 `ORDER_STATE_CONFLICT` | 「訂單狀態已變更，請重新整理」 |
| EX-13 | 鎖等待逾時 | 不改資料；排程則略過該筆，下次重跑 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「系統忙碌，請稍後再試」 |
| EX-14 | 重複送出（同 key 同內容）/ 同 key 不同內容 / 處理中 | 回放第一次結果 / 拒絕 / 拒絕 | 同第一次 / 422 `IDEMPOTENCY_KEY_REUSED` / 409 `IDEMPOTENCY_IN_PROGRESS` | 同第一次 / 「請重新操作」 |
| EX-15 | 保母訂閱鎖定時婉拒或取消 | 照常執行（BR-7） | 200 | 同正常流程 |
| EX-16 | 轉介失敗（PRD-010 回錯） | 婉拒已成立不回滾；轉介對話框顯示錯誤並可重試 | 依 PRD-010 | 「轉介未送出，請稍後再試」；訂單仍為已婉拒 |
| EX-17 | Resend 寄信失敗 | 不影響轉移；站內通知已同交易寫入；Email 排程重試最多 5 次，4xx 直接判失敗 | — | 站內通知照常 |
| EX-18 | GCS | 不適用：本卡無檔案上傳 | — | — |
| EX-19 | 排程單筆處理失敗（例外） | 該筆交易回滾（含行程取消），其他筆照常；下次排程重試 | — | — |

## 8. 驗收標準（AC）

### 子模組 1：保母婉拒（O3 SITTER）與轉介入口

- [ ] **AC-1.1 (婉拒成功)**：Given SITTER A 的訂單 `PENDING`（3 個 `PENDING` 行程），When A 填原因「該週檔期已滿」送出婉拒，Then 200；訂單 `CANCELLED`、`cancelledBy = SITTER`、原因已記錄；3 個行程皆 `CANCELLED`；無任何 payment；寫 `order_logs`（`ORDER_REJECTED`，payload 含原因）；飼主收到「預約已婉拒」通知，內容含「該週檔期已滿」並連到訂單詳情；飼主詳情顯示「保母已婉拒此預約」與原因；保母端該訂單在「歷史」且無接單 / 報價按鈕。對應：T1、T5、BR-1、BR-2、BR-10、BR-12
- [ ] **AC-1.2 (婉拒確認框與原因)**：Given A 在評估頁按「婉拒預約」，Then 對話框顯示原因欄位（必填）、「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」快速帶入按鈕與文字「婉拒後飼主將收到通知，含您填寫的原因」；點「檔期已滿」後欄位帶入該文字且可編輯；原因空白時「確認婉拒」不可按；直接呼叫 API 帶空白原因（含全空白字元）或 201 字 → 400 `VALIDATION_FAILED`。對應：BR-2、BR-9、EX-5
- [ ] **AC-1.3 (婉拒被拒)**：Given 訂單已 `PENDING_PAYMENT`、已被飼主撤回或已被 O4 取消 → 409 `ORDER_STATE_CONFLICT`；Given SITTER B → 403 `FORBIDDEN`；Given A 以 OWNER 角色 → 403 `ROLE_MISMATCH`；Given 不存在 → 404 `NOT_FOUND`。皆不改資料、不發通知。對應：EX-1、EX-2、EX-4、EX-6
- [ ] **AC-1.4 (婉拒併發與重送)**：Given 飼主撤回（PRD-026）與 A 婉拒同時送出，Then 只有一方成功，另一方 409 `ORDER_STATE_CONFLICT`，`cancelledBy` 為成功者，`order_logs` 只有一筆取消紀錄；Given 婉拒已以 key K 成功，When 同 key 同內容重送，Then 回放同一 200，通知不重複。對應：BR-11、EX-11、EX-14
- [ ] **AC-1.5 (轉介入口)**：Given 婉拒成功，Then 對話框顯示「轉介信任圈夥伴」按鈕，點擊進入 PRD-010 轉介選單；When 轉介請求失敗，Then 訂單仍為 `CANCELLED`、顯示「轉介未送出，請稍後再試」且可重試；不點轉介直接關閉則不送任何轉介通知。對應：BR-3、EX-16
- [ ] **AC-1.6 (鎖定或額度已滿仍可婉拒)**：Given A 訂閱已逾期鎖定，或 A 為 FREE 且本月已 3 筆 `CONFIRMED`，When A 婉拒 `PENDING` 訂單，Then 200，結果同 AC-1.1。對應：BR-7、EX-15
- [ ] **AC-1.7 (期限後仍可婉拒)**：Given 訂單 `PENDING`、`review_expires_at = 2026-12-10T16:00:00Z`、O4 排程尚未處理，When A 於 `2026-12-10T16:03:00Z` 婉拒，Then 200；訂單 `CANCELLED`、`cancelledBy = SITTER`、行程 `CANCELLED`、log 為 `ORDER_REJECTED`；之後 O4 排程執行時略過該筆，無 `ORDER_REVIEW_EXPIRED` log、無逾期通知。Given O4 已先取消該訂單，When A 婉拒 → 409 `ORDER_STATE_CONFLICT`，`cancelledBy` 維持 `SYSTEM`。對應：BR-8、EX-9、EX-12

### 子模組 2：評估逾期（O4）

- [ ] **AC-2.1 (逾期取消)**：Given 訂單 `PENDING`（2 個 `PENDING` 行程）、`review_expires_at = 2026-12-10T16:00:00Z`，When 排程於 `2026-12-10T16:05:00Z` 執行，Then 訂單 `CANCELLED`、`cancelledBy = SYSTEM`、原因「保母未於期限內回覆」；2 個行程皆 `CANCELLED`；寫 `order_logs`（`ORDER_REVIEW_EXPIRED`）；飼主收到「保母未於期限內回覆，預約已自動取消」、保母收到「預約已逾期自動取消」。對應：T2、T5、BR-4、BR-12
- [ ] **AC-2.2 (未到期不處理)**：Given `review_expires_at = 2026-12-10T16:00:00Z`，When 排程於 `15:59:00Z` 執行，Then 訂單維持 `PENDING`、行程維持 `PENDING`、無通知。對應：BR-4、BR-5
- [ ] **AC-2.3 (重驗與略過)**：Given 候選查出後、處理前保母已報價（`PENDING_PAYMENT`）、已婉拒或飼主已撤回，When 排程處理該筆，Then 略過，不改狀態、不寫 log、不發通知；Given 同一批次中一筆處理拋例外，Then 該筆訂單與行程皆未變更，其他筆照常取消，下次排程可再處理該筆。對應：BR-5、EX-12、EX-19
- [ ] **AC-2.4 (重跑冪等)**：Given 訂單已被 O4 取消，When 排程再跑一次，Then 不再出現於候選，通知與 `order_logs` 各只有一筆。對應：BR-5

### 子模組 3：付款前保母取消（O7）

- [ ] **AC-3.1 (取消成功)**：Given 訂單 `PENDING_PAYMENT`、首次收款 payment `UNPAID`、`now < due_at`，When SITTER A 填原因「臨時生病無法服務」送出取消，Then 200；payment `VOID`；訂單 `CANCELLED`、`cancelledBy = SITTER`、原因已記錄；所有行程 `CANCELLED`；寫 `order_logs`（`ORDER_CANCELLED_BY_SITTER`）；飼主收到「保母已取消訂單」通知含原因，飼主詳情不再顯示付款入口；取消成功畫面不顯示轉介入口。對應：T3、T4、T5、BR-6、BR-12、BR-13
- [ ] **AC-3.2 (已上傳憑證不可取消)**：Given payment `SUBMITTED`，When A 取消 → 409 `PAYMENT_ALREADY_SUBMITTED`，訂單、行程與 payment 不變；畫面顯示「飼主已上傳付款憑證，請先核對或駁回」與核對頁連結。Given A 駁回（PRD-007，payment 回 `UNPAID`）後再取消，Then 200。對應：BR-6、EX-8
- [ ] **AC-3.3 (狀態不允許)**：Given 訂單 `CONFIRMED` → 409 `ORDER_STATE_CONFLICT`，畫面提示「訂單已成立，如需取消請申請訂單變更」；Given 訂單 `PENDING`（應走婉拒）或 `CANCELLED` → 409 `ORDER_STATE_CONFLICT`。皆不改資料。對應：EX-7
- [ ] **AC-3.4 (越權與原因)**：Given SITTER B → 403 `FORBIDDEN`；Given A 以 OWNER 角色 → 403 `ROLE_MISMATCH`；Given ADMIN → 403 `ROLE_MISMATCH`；Given 原因空白 → 400 `VALIDATION_FAILED`。皆不改資料。對應：EX-1–EX-3、EX-5
- [ ] **AC-3.5 (跨角色併發)**：Given 飼主上傳憑證（PRD-007）與 A 取消同時送出，Then 只有一個成立：上傳先 → A 得 409 `PAYMENT_ALREADY_SUBMITTED`、payment `SUBMITTED`；取消先 → payment `VOID`、訂單 `CANCELLED`，飼主上傳被拒（碼依 PRD-007）。不會出現 payment `VOID` 但留有 `SUBMITTED` submission 的狀態。對應：EX-10
- [ ] **AC-3.6 (重送與鎖定)**：Given 取消已以 key K 成功，When 同 key 重送，Then 回放同一 200、payment 不重複作廢、通知不重複；Given A 訂閱鎖定，When 取消 `UNPAID` 訂單，Then 200。對應：BR-7、BR-11、EX-14、EX-15
- [ ] **AC-3.7 (付款期限後仍可取消)**：Given 訂單 `PENDING_PAYMENT`、payment `UNPAID`、`due_at = 2026-12-08T16:00:00Z`、O8 排程（PRD-007）尚未處理，When A 於 `2026-12-08T16:05:00Z` 取消，Then 200；payment `VOID`、訂單 `CANCELLED`、`cancelledBy = SITTER`、log 為 `ORDER_CANCELLED_BY_SITTER`；之後 O8 略過該筆。Given O8 已先取消，When A 取消 → 409 `ORDER_STATE_CONFLICT`，`cancelledBy` 維持 `SYSTEM`。對應：BR-8、EX-9、EX-12

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | 訂單：`status`、`cancelledBy`、取消原因；剩餘 `PENDING` 行程隨訂單同交易轉 `CANCELLED`；首次收款 payment `VOID`（O7）；`order_logs` 動作 `ORDER_REJECTED`、`ORDER_REVIEW_EXPIRED`、`ORDER_CANCELLED_BY_SITTER`。DDL 在 SD-ORDER |
| 畫面 | 保母：評估頁「婉拒預約」對話框（含快速帶入原因）與轉介入口（PRD-006 頁面）、`PENDING_PAYMENT` 訂單詳情「取消訂單」對話框；飼主：訂單詳情的婉拒 / 取消 / 逾期結果顯示 |
| 通知 | 婉拒 → OWNER（含原因）；評估逾期 → OWNER、SITTER；付款前取消 → OWNER（含原因）。皆站內 + Email（依 PRD-014 偏好） |
| 排程 | 有：評估逾期，每 15 分鐘，`/api/internal/cron/*`；時間基準 `orders.review_expires_at`（UTC 儲存，顯示 Asia/Taipei） |
| API | 婉拒、付款前取消、評估逾期排程端點。契約在 SD-ORDER |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | 婉拒 / 取消原因的長度與常用選項（原 PRD-006 為「可選擇 / 填寫」，未填可送出；baseline 改為必填） | 決定：必填 1–200 字；前端提供「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」快速帶入文字，送出的仍是文字（BR-9、AC-1.2） | 已決（使用者 2026-11-21） |
| CQ-2 | baseline §3.2「已過期限的使用者操作 → `DEADLINE_PASSED`」是否也適用於婉拒（`review_expires_at`）與保母取消（`due_at`） | （原建議：適用。）決定：不適用；訂單未進終態前仍允許，`cancelledBy = SITTER`（BR-8、AC-1.7、AC-3.7） | 已決（使用者 2026-11-21，baseline CQ-61） |
| CQ-3 | 婉拒與轉介是一個請求（「不接單並轉介」，PRD-010 B.1）還是兩步驟 | 決定：兩步驟：婉拒先成立，再開轉介；轉介失敗不回滾婉拒（BR-3、AC-1.5） | 已決（使用者 2026-11-21） |
| CQ-4 | O7 付款前取消後是否也提供轉介入口（BR §5.3 只寫評估頁） | 決定：不提供（BR-13、AC-3.1） | 已決（使用者 2026-11-21） |
| CQ-5 | 婉拒後的轉介：PRD-010（Draft）寫的是單一動作「不接單並轉介」，本卡改成兩步驟後，訂單在轉介時已是 `CANCELLED`。PRD-010 要接受哪種狀態的訂單？轉介入口關掉後還能從歷史訂單再開嗎？（審查第 1 輪 #2） | PRD-010 的轉介前置條件 = 訂單 `CANCELLED`、`cancelledBy=SITTER`、有 `ORDER_REJECTED` log、呼叫者為該訂單 sitter；入口只在婉拒成功的對話框提供，關掉就不再提供；PRD-010 的「不接單並轉介」改成本卡的兩步驟 | Open |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-21 | 依 baseline 第 2 版修訂與使用者決定改寫：婉拒 / 保母取消不再套用 `DEADLINE_PASSED`，訂單未進終態前允許（4.1、4.3、T1、T3、BR-8、EX-9、EX-12 改寫，新增 AC-1.7、AC-3.7，AC-1.3、AC-3.3 刪除期限條款）；取消共同附帶動作——O3 / O4 / O7 同交易取消剩餘 `PENDING` 行程、作廢未結束 payment（新增 T5，BR-12 改寫，AC-1.1、AC-2.1、AC-3.1 補行程斷言）；刪除問卷子狀態（`PENDING_OWNER`）相關敘述；BR-7 補月額度不擋婉拒；CQ-1、3、4 採建議預設值寫入 BR-9、BR-3、新增 BR-13；CQ-1~4 全部已決；飼主撤回改指向 PRD-026；表頭移除「尚未登記」註記（PRD-PLAN、SD-PLAN 已登記）。子模組 7 / 4 / 7 AC | 使用者決定 2026-11-21（baseline CQ-58、61、62、65；PRD-027 CQ-1、3、4 採建議預設值） |
| 2026-11-21 | 建立（自 PRD-006 拆卡），狀態 `In Review`。內容：原 PRD-006 D 段婉拒（含 2026-10-26 fix-batch13 K1 通知與確認框，改為原因必填）、baseline O4 評估逾期、baseline O7 付款前保母取消（第 1 輪衝突 #19、CQ-13）、`PAYMENT_ALREADY_SUBMITTED`（第 2 輪 #3） | PRD-006 子模組超過 3 個；`docs/reviews/00-DOMAIN-BASELINE.prd.review.md` 第 1、2 輪 |
