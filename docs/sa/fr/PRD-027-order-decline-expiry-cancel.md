# PRD-027: 保母婉拒、評估逾期與付款前保母取消

| 項目 | 內容 |
|------|------|
| 狀態 | In Review |
| 建立日期 | 2026-11-21 |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0；`00-SD-PLAN.md` 與 `00-PRD-PLAN.md` 尚未登記本卡，待主流程同步） |
| 測試情境 | TS-027 |
| 相依 PRD | PRD-006（評估頁、報價 O2；本卡自 PRD-006 拆出）；PRD-005（O1 寫入 `review_expires_at`、飼主撤回 O3 OWNER / O6）；PRD-007（首次收款 payment、駁回、O8 付款逾期）；PRD-010（轉介本身）；PRD-014（通知偏好） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1、§1.2（角色、資源歸屬）；§2.2（訂閱鎖定範圍）；§3.1、§3.2 O3 / O4 / O7（轉移與前置條件、期限以時間判斷）；§3.3（payment `UNPAID → VOID`、`SUBMITTED` 不得由保母取消）；§3.6（鎖、稽核、通知、冪等、排程）；§3.7（錯誤碼）；§5（`review_expires_at`、`due_at`、排程頻率） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念只能**引用** DOMAIN-BASELINE，不得在這裡重新定義。
> 本卡是 PRD-006 拆卡（2026-11-21）：處理保母評估後「不成立」的三個出口。飼主撤回不在本卡（PRD-005）；付款逾期 O8 不在本卡（PRD-007）。

---

## 1. 背景與目的

保母評估後不一定接單；飼主不回應問卷、保母不處理時訂單也不能永遠卡在 `PENDING`；報價後若保母發現無法服務，在飼主付款前也需要出口。本卡定義三個出口：保母婉拒（O3 SITTER，可接轉介）、評估逾期由系統取消（O4）、付款前保母取消（O7）。三者都讓訂單進入 `CANCELLED` 並通知飼主，且都不能在飼主已上傳付款憑證後發生。

## 2. 使用者故事

- **US-1** 身為保母，我希望婉拒不適合的預約並告知原因，以便飼主盡快另找保母。
- **US-2** 身為保母，我希望婉拒時能順手轉介信任圈夥伴，以便不讓飼主落空。
- **US-3** 身為飼主，我希望保母一直沒回應時預約會自動取消並通知我，以便不必一直等待。
- **US-4** 身為保母，我希望報價後、飼主付款前發現無法服務時能取消訂單並說明原因，以便不讓飼主付了款才被取消。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 婉拒預約（O3） | ✗（飼主撤回見 PRD-005） | 本人訂單 | ✗ | ✗ | 無；訂閱鎖定不擋（BR-7） |
| 婉拒後轉介 | ✗ | 本人訂單（規則見 PRD-010） | ✗ | ✗ | 依 PRD-010 |
| 評估逾期取消（O4） | ✗ | ✗ | ✗ | ✗ | 僅 SYSTEM 排程 |
| 付款前取消（O7） | ✗（飼主撤回見 PRD-005） | 本人訂單 | ✗ | ✗ | 無；訂閱鎖定不擋（BR-7） |

- 「本人訂單」以 token 身分判斷；存在但不屬於呼叫者 → 403 `FORBIDDEN`，不存在 → 404 `NOT_FOUND`；`currentRole` 不符 → 403 `ROLE_MISMATCH`；未登入 → 401 `UNAUTHENTICATED`（baseline §1.1、§1.2）。
- 排程端點 `/api/internal/cron/*` 只接受 Cloud Scheduler 身分（baseline §3.6）。

## 4. 主流程

### 4.1 保母婉拒（O3 SITTER）

1. 保母在評估頁（PRD-006）按「婉拒預約」→ 對話框要求填寫原因（可點常用原因帶入文字，CQ-1），並提示「婉拒後飼主將收到通知，含您填寫的原因」。
2. 保母送出（帶 `Idempotency-Key`）→ 系統鎖訂單列，檢查：訂單 `PENDING`（問卷子狀態不限）、`now < review_expires_at`（CQ-2）、原因非空白 → 同一交易：訂單 `PENDING → CANCELLED`、`cancelledBy = SITTER`、記錄原因；寫 `order_logs`（`ORDER_REJECTED`，含原因）；通知飼主。
3. 回應成功後，對話框顯示「轉介信任圈夥伴」入口；保母選擇後進入 PRD-010 轉介流程（另一個請求）。不轉介則關閉。
4. 飼主訂單詳情顯示「保母已婉拒此預約」與原因；保母端該訂單移到「歷史」，無任何接單 / 報價按鈕。

### 4.2 評估逾期（O4）

1. 排程每 15 分鐘執行一次（baseline §5），先唯讀查出 `status = PENDING` 且 `now ≥ review_expires_at` 的訂單。
2. 逐筆以獨立交易處理：鎖訂單列（`SKIP LOCKED`）後重驗條件 → 仍成立則 `PENDING → CANCELLED`、`cancelledBy = SYSTEM`、原因「保母未於期限內回覆」；寫 `order_logs`（`ORDER_REVIEW_EXPIRED`）；通知雙方。條件不成立（已被報價 / 撤回 / 婉拒）則略過。
3. 一筆失敗不影響其他筆；排程可重跑。
4. 排程未跑之前，使用者在期限後的操作已依時間被拒（PRD-006 AC-3.4、本卡 AC-1.3、PRD-005），排程只負責補寫終態（baseline §3.2、CQ-46）。

### 4.3 付款前保母取消（O7）

1. 保母在 `PENDING_PAYMENT` 訂單詳情按「取消訂單」→ 對話框要求填寫原因，提示「取消後飼主將收到通知，含您填寫的原因」。
2. 保母送出（帶 `Idempotency-Key`）→ 系統鎖訂單列，檢查：訂單 `PENDING_PAYMENT`、首次收款 payment 為 `UNPAID`、`now < due_at`（CQ-2）、原因非空白 → 同一交易：payment `UNPAID → VOID`；訂單 `PENDING_PAYMENT → CANCELLED`、`cancelledBy = SITTER`、記錄原因；寫 `order_logs`（`ORDER_CANCELLED_BY_SITTER`）；通知飼主。
3. payment 為 `SUBMITTED`（飼主已上傳憑證）時不可取消，畫面引導保母先核對或駁回（PRD-007）；駁回後 payment 回 `UNPAID`，才能取消。

## 5. 狀態轉移

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1（O3） | 訂單 `PENDING` | `CANCELLED`（`cancelledBy=SITTER`） | SITTER | 原因非空白；`now < review_expires_at`（CQ-2） | 409 `ORDER_STATE_CONFLICT` / `DEADLINE_PASSED`；400 `VALIDATION_FAILED` |
| T2（O4） | 訂單 `PENDING` | `CANCELLED`（`cancelledBy=SYSTEM`） | SYSTEM | `now ≥ review_expires_at` | 不符則略過 |
| T3（O7） | 訂單 `PENDING_PAYMENT` | `CANCELLED`（`cancelledBy=SITTER`） | SITTER | 首次收款 payment `UNPAID`；`now < due_at`（CQ-2）；原因非空白 | 409 `PAYMENT_ALREADY_SUBMITTED` / `ORDER_STATE_CONFLICT` / `DEADLINE_PASSED`；400 `VALIDATION_FAILED` |
| T4 | 首次收款 payment `UNPAID` | `VOID` | SYSTEM（隨 T3 同交易） | T3 成功 | — |

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 婉拒只在 `PENDING`；需原因；問卷子狀態（含 `PENDING_OWNER`）不影響婉拒 | baseline O3；BR §3.6 步驟 4 |
| BR-2 | 婉拒通知飼主，內容含保母原因，連到該訂單詳情；飼主訂單詳情顯示「保母已婉拒此預約」與原因；送出前確認框文字與實際行為一致 | 沿用原 PRD-006 D 段（2026-10-26 fix-batch13 K1）；BR §3.6「取消接單需信件通知」 |
| BR-3 | 轉介入口在婉拒成功後提供；轉介是獨立操作，成功與否不影響已成立的婉拒；轉介對象、黑名單過濾、通知內容依 PRD-010 | BR §5.3 轉介流程 1；PRD-010 B 段；兩步驟待確認 CQ-3 |
| BR-4 | 評估逾期：`now ≥ review_expires_at` 且仍 `PENDING` → `CANCELLED`、`cancelledBy=SYSTEM`，通知雙方；問卷子狀態不影響 | baseline O4、§5、CQ-12 |
| BR-5 | 排程每 15 分鐘；候選唯讀查出後逐筆獨立交易、鎖後重驗、`SKIP LOCKED`、可重跑；判斷以「`now ≥` 到期時間」為準，不依賴排程頻率 | baseline §3.6 排程、§5 |
| BR-6 | 付款前保母取消：只在 `PENDING_PAYMENT` 且首次收款 payment `UNPAID`；`SUBMITTED` 時只能駁回（PRD-007），取消 → 409 `PAYMENT_ALREADY_SUBMITTED`；需原因；同交易 payment → `VOID` | baseline O7、§3.3、CQ-13 |
| BR-7 | 婉拒、付款前取消不受訂閱鎖定限制（baseline 鎖定範圍只列公開頁預約、報價、方案 / 問卷 / 公開檔案設定） | baseline §2.2、CQ-19 |
| BR-8 | 使用者操作時已過對應期限（婉拒看 `review_expires_at`，取消看 `due_at`）→ 409 `DEADLINE_PASSED`（payload 帶期限名稱），訂單由排程以 `cancelledBy=SYSTEM` 取消 | baseline §3.2、CQ-46；適用待確認 CQ-2 |
| BR-9 | 原因 1–200 字，去除前後空白後不得為空 | baseline O3、O7（需原因）；長度待確認 CQ-1 |
| BR-10 | 每次轉移寫一筆 `order_logs`，與狀態變更同交易；通知以 outbox 同交易寫入站內通知，Email 依 PRD-014 偏好由排程寄送 | baseline §3.6 |
| BR-11 | 婉拒、取消帶 `Idempotency-Key`，依 baseline §3.6 回放 | baseline §3.6 |
| BR-12 | 訂單 `CANCELLED` 後所有行程隨之為 `CANCELLED`（不再是有效行程）；O7 取消不影響月接單額度（額度只在 `CONFIRMED` 計數） | baseline §3.1 有效行程、§2.2；原 PRD-006 AC-7「訂單與行程轉 CANCELLED」 |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 其他保母婉拒 / 取消 | 拒絕，不改資料 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | 以 OWNER 角色呼叫婉拒 / 保母取消 | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換至保母身分」 |
| EX-3 | ADMIN 呼叫婉拒 / 保母取消 | 拒絕（ADMIN 不是觸發者；ADMIN 作廢為 O16，見 PRD-007） | 403 `ROLE_MISMATCH` | — |
| EX-4 | 訂單不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此訂單」 |
| EX-5 | 原因空白或超過上限 | 拒絕 | 400 `VALIDATION_FAILED` | 欄位下方「請填寫原因」/「原因最多 200 字」 |
| EX-6 | 婉拒時訂單已非 `PENDING`（已報價、飼主已撤回、已逾期） | 拒絕 | 409 `ORDER_STATE_CONFLICT` | 「訂單狀態已變更，請重新整理」 |
| EX-7 | 取消時訂單非 `PENDING_PAYMENT`（已 `CONFIRMED`、已取消） | 拒絕；`CONFIRMED` 後改走變更協商整筆取消（PRD-016） | 409 `ORDER_STATE_CONFLICT` | 「訂單已成立，如需取消請申請訂單變更」或「訂單狀態已變更，請重新整理」 |
| EX-8 | 取消時飼主已上傳憑證（payment `SUBMITTED`） | 拒絕，不改資料 | 409 `PAYMENT_ALREADY_SUBMITTED` | 「飼主已上傳付款憑證，請先核對或駁回」與核對頁連結（PRD-007） |
| EX-9 | 已過 `review_expires_at`（婉拒）/ `due_at`（取消），排程尚未處理 | 拒絕 | 409 `DEADLINE_PASSED`（`deadline=review_expires_at` / `due_at`） | 「此訂單已超過期限，系統將自動取消」 |
| EX-10 | 跨角色併發：保母取消與飼主上傳憑證（PRD-007）同時 | 鎖訂單列序列化。上傳先 → 取消得 EX-8；取消先 → 上傳得 PRD-007 定義的錯誤 | 409 | 依先後 |
| EX-11 | 跨角色併發：保母婉拒與飼主撤回（PRD-005）同時 | 先到者生效（`cancelledBy` 記先到者），後到者 EX-6 | 409 `ORDER_STATE_CONFLICT` | 同 EX-6 |
| EX-12 | 排程與保母報價 / 婉拒在期限邊界同時 | 排程鎖後重驗：已非 `PENDING` 則略過；使用者端在 `now ≥ review_expires_at` 時已被 EX-9 擋下 | — | — |
| EX-13 | 鎖等待逾時 | 不改資料；排程則略過該筆，下次重跑 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「系統忙碌，請稍後再試」 |
| EX-14 | 重複送出（同 key 同內容）/ 同 key 不同內容 / 處理中 | 回放第一次結果 / 拒絕 / 拒絕 | 同第一次 / 422 `IDEMPOTENCY_KEY_REUSED` / 409 `IDEMPOTENCY_IN_PROGRESS` | 同第一次 / 「請重新操作」 |
| EX-15 | 保母訂閱鎖定時婉拒或取消 | 照常執行（BR-7） | 200 | 同正常流程 |
| EX-16 | 轉介失敗（PRD-010 回錯） | 婉拒已成立不回滾；轉介對話框顯示錯誤並可重試 | 依 PRD-010 | 「轉介未送出，請稍後再試」；訂單仍為已婉拒 |
| EX-17 | Resend 寄信失敗 | 不影響轉移；站內通知已同交易寫入；Email 排程重試最多 5 次，4xx 直接判失敗 | — | 站內通知照常 |
| EX-18 | GCS | 不適用：本卡無檔案上傳 | — | — |
| EX-19 | 排程單筆處理失敗（例外） | 該筆交易回滾，其他筆照常；下次排程重試 | — | — |

## 8. 驗收標準（AC）

### 子模組 1：保母婉拒（O3 SITTER）與轉介入口

- [ ] **AC-1.1 (婉拒成功)**：Given SITTER A 的訂單 `PENDING`（問卷 `NOT_REQUIRED` 或 `PENDING_OWNER`），When A 填原因「該週檔期已滿」送出婉拒，Then 200；訂單 `CANCELLED`、`cancelledBy = SITTER`、原因已記錄；寫 `order_logs`（`ORDER_REJECTED`，payload 含原因）；飼主收到「預約已婉拒」通知，內容含「該週檔期已滿」並連到訂單詳情；飼主詳情顯示「保母已婉拒此預約」與原因；保母端該訂單在「歷史」且無接單 / 報價按鈕。對應：T1、BR-1、BR-2、BR-10
- [ ] **AC-1.2 (婉拒確認框)**：Given A 在評估頁按「婉拒預約」，Then 對話框顯示原因欄位（必填）與文字「婉拒後飼主將收到通知，含您填寫的原因」；原因空白時「確認婉拒」不可按；直接呼叫 API 帶空白原因或 201 字 → 400 `VALIDATION_FAILED`。對應：BR-2、BR-9、EX-5
- [ ] **AC-1.3 (婉拒被拒)**：Given 訂單已 `PENDING_PAYMENT` 或 `CANCELLED` → 409 `ORDER_STATE_CONFLICT`；Given `now ≥ review_expires_at` 且排程尚未處理 → 409 `DEADLINE_PASSED`（`deadline = review_expires_at`）；Given SITTER B → 403 `FORBIDDEN`；Given A 以 OWNER 角色 → 403 `ROLE_MISMATCH`；Given 不存在 → 404 `NOT_FOUND`。皆不改資料、不發通知。對應：EX-1、EX-2、EX-4、EX-6、EX-9
- [ ] **AC-1.4 (婉拒併發與重送)**：Given 飼主撤回（PRD-005）與 A 婉拒同時送出，Then 只有一方成功，另一方 409 `ORDER_STATE_CONFLICT`，`cancelledBy` 為成功者，`order_logs` 只有一筆取消紀錄；Given 婉拒已以 key K 成功，When 同 key 同內容重送，Then 回放同一 200，通知不重複。對應：BR-11、EX-11、EX-14
- [ ] **AC-1.5 (轉介入口)**：Given 婉拒成功，Then 對話框顯示「轉介信任圈夥伴」按鈕，點擊進入 PRD-010 轉介選單；When 轉介請求失敗，Then 訂單仍為 `CANCELLED`、顯示「轉介未送出，請稍後再試」且可重試；不點轉介直接關閉則不送任何轉介通知。對應：BR-3、EX-16
- [ ] **AC-1.6 (鎖定時仍可婉拒)**：Given A 訂閱已逾期鎖定，When A 婉拒 `PENDING` 訂單，Then 200，結果同 AC-1.1。對應：BR-7、EX-15

### 子模組 2：評估逾期（O4）

- [ ] **AC-2.1 (逾期取消)**：Given 訂單 `PENDING`、`review_expires_at = 2026-12-10T16:00:00Z`，When 排程於 `2026-12-10T16:05:00Z` 執行，Then 訂單 `CANCELLED`、`cancelledBy = SYSTEM`、原因「保母未於期限內回覆」；寫 `order_logs`（`ORDER_REVIEW_EXPIRED`）；飼主收到「保母未於期限內回覆，預約已自動取消」、保母收到「預約已逾期自動取消」。Given 同一訂單問卷為 `PENDING_OWNER`，Then 同樣取消。對應：T2、BR-4
- [ ] **AC-2.2 (未到期不處理)**：Given `review_expires_at = 2026-12-10T16:00:00Z`，When 排程於 `15:59:00Z` 執行，Then 訂單維持 `PENDING`、無通知。對應：BR-4、BR-5
- [ ] **AC-2.3 (重驗與略過)**：Given 候選查出後、處理前保母已報價（`PENDING_PAYMENT`）或飼主已撤回，When 排程處理該筆，Then 略過，不改狀態、不寫 log、不發通知；Given 同一批次中一筆處理拋例外，Then 其他筆照常取消，下次排程可再處理該筆。對應：BR-5、EX-12、EX-19
- [ ] **AC-2.4 (重跑冪等)**：Given 訂單已被 O4 取消，When 排程再跑一次，Then 不再出現於候選，通知與 `order_logs` 各只有一筆。對應：BR-5

### 子模組 3：付款前保母取消（O7）

- [ ] **AC-3.1 (取消成功)**：Given 訂單 `PENDING_PAYMENT`、首次收款 payment `UNPAID`、`now < due_at`，When SITTER A 填原因「臨時生病無法服務」送出取消，Then 200；payment `VOID`；訂單 `CANCELLED`、`cancelledBy = SITTER`、原因已記錄；所有行程 `CANCELLED`；寫 `order_logs`（`ORDER_CANCELLED_BY_SITTER`）；飼主收到「保母已取消訂單」通知含原因，飼主詳情不再顯示付款入口。對應：T3、T4、BR-6、BR-12
- [ ] **AC-3.2 (已上傳憑證不可取消)**：Given payment `SUBMITTED`，When A 取消 → 409 `PAYMENT_ALREADY_SUBMITTED`，訂單與 payment 不變；畫面顯示「飼主已上傳付款憑證，請先核對或駁回」與核對頁連結。Given A 駁回（PRD-007，payment 回 `UNPAID`）後再取消，Then 200。對應：BR-6、EX-8
- [ ] **AC-3.3 (狀態與期限不允許)**：Given 訂單 `CONFIRMED` → 409 `ORDER_STATE_CONFLICT`，畫面提示「訂單已成立，如需取消請申請訂單變更」；Given 訂單 `PENDING`（應走婉拒）或 `CANCELLED` → 409 `ORDER_STATE_CONFLICT`；Given `now ≥ due_at`、payment `UNPAID` 且排程尚未處理 → 409 `DEADLINE_PASSED`（`deadline = due_at`）。對應：EX-7、EX-9、BR-8
- [ ] **AC-3.4 (越權與原因)**：Given SITTER B → 403 `FORBIDDEN`；Given A 以 OWNER 角色 → 403 `ROLE_MISMATCH`；Given ADMIN → 403 `ROLE_MISMATCH`；Given 原因空白 → 400 `VALIDATION_FAILED`。皆不改資料。對應：EX-1–EX-3、EX-5
- [ ] **AC-3.5 (跨角色併發)**：Given 飼主上傳憑證（PRD-007）與 A 取消同時送出，Then 只有一個成立：上傳先 → A 得 409 `PAYMENT_ALREADY_SUBMITTED`、payment `SUBMITTED`；取消先 → payment `VOID`、訂單 `CANCELLED`，飼主上傳被拒（碼依 PRD-007）。不會出現 payment `VOID` 但留有 `SUBMITTED` submission 的狀態。對應：EX-10
- [ ] **AC-3.6 (重送與鎖定)**：Given 取消已以 key K 成功，When 同 key 重送，Then 回放同一 200、payment 不重複作廢、通知不重複；Given A 訂閱鎖定，When 取消 `UNPAID` 訂單，Then 200。對應：BR-7、BR-11、EX-14、EX-15

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | 訂單：`status`、`cancelledBy`、取消原因；行程隨訂單取消；首次收款 payment `VOID`；`order_logs` 動作 `ORDER_REJECTED`、`ORDER_REVIEW_EXPIRED`、`ORDER_CANCELLED_BY_SITTER`。DDL 在 SD-ORDER |
| 畫面 | 保母：評估頁「婉拒預約」對話框與轉介入口（PRD-006 頁面）、`PENDING_PAYMENT` 訂單詳情「取消訂單」對話框；飼主：訂單詳情的婉拒 / 取消 / 逾期結果顯示 |
| 通知 | 婉拒 → OWNER（含原因）；評估逾期 → OWNER、SITTER；付款前取消 → OWNER（含原因）。皆站內 + Email（依 PRD-014 偏好） |
| 排程 | 有：評估逾期，每 15 分鐘，`/api/internal/cron/*`；時間基準 `orders.review_expires_at`（UTC 儲存，顯示 Asia/Taipei） |
| API | 婉拒、付款前取消、評估逾期排程端點。契約在 SD-ORDER |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | 婉拒 / 取消原因的長度與常用選項（原 PRD-006 為「可選擇 / 填寫」，未填可送出；baseline 改為必填） | 必填 1–200 字；前端提供「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」快速帶入文字，送出的仍是文字 | Open |
| CQ-2 | baseline §3.2「已過期限的使用者操作 → `DEADLINE_PASSED`」是否也適用於婉拒（`review_expires_at`）與保母取消（`due_at`）；兩者結果都是取消，差別只在 `cancelledBy` | 適用，回 409 `DEADLINE_PASSED`，由排程以 `SYSTEM` 取消，維持規則一致 | Open |
| CQ-3 | 婉拒與轉介是一個請求（「不接單並轉介」，PRD-010 B.1）還是兩步驟 | 兩步驟：婉拒先成立，再開轉介；轉介失敗不回滾婉拒 | Open |
| CQ-4 | O7 付款前取消後是否也提供轉介入口（BR §5.3 只寫評估頁） | 不提供 | Open |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-21 | 建立（自 PRD-006 拆卡），狀態 `In Review`。內容：原 PRD-006 D 段婉拒（含 2026-10-26 fix-batch13 K1 通知與確認框，改為原因必填）、baseline O4 評估逾期、baseline O7 付款前保母取消（第 1 輪衝突 #19、CQ-13）、`PAYMENT_ALREADY_SUBMITTED`（第 2 輪 #3） | PRD-006 子模組超過 3 個；`docs/reviews/00-DOMAIN-BASELINE.prd.review.md` 第 1、2 輪 |
