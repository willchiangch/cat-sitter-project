# PRD-027: 保母婉拒、評估逾期與付款前保母取消

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 核准 | 使用者，2026-11-22 |
| 建立日期 | 2026-11-21 |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0） |
| 測試情境 | TS-027 |
| 相依 PRD | PRD-006（評估頁、報價 O2；本卡自 PRD-006 拆出）；PRD-005（O1 寫入 `review_expires_at`）；PRD-026（飼主撤回 O3 OWNER / O6）；PRD-007（付款：首次收款 payment、上傳憑證、駁回、O8 付款逾期）；PRD-010（轉介本身；轉介前置條件須依本卡 CQ-5 改寫，屬 PRD-010 修訂）；PRD-014（通知偏好） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1、§1.2（角色、資源歸屬）；§2.2（訂閱鎖定範圍、月額度計數時點）；§3.1、§3.2 O3 / O4 / O7（轉移與前置條件；期限以時間判斷及取消類操作例外 CQ-61；取消的共同附帶動作 CQ-65）；§3.3（payment `UNPAID → VOID`、`SUBMITTED` 不得由保母取消）；§3.6（鎖、稽核、通知與不可關閉類別 CQ-73、冪等、排程）；§3.7（錯誤碼、判斷順序 CQ-70、payload 共通欄位 `currentStatus`、缺 `Idempotency-Key`）；§5（`review_expires_at`、`due_at`、排程頻率） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念只能**引用** DOMAIN-BASELINE，不得在這裡重新定義。
> 本卡是 PRD-006 拆卡（2026-11-21）：處理保母評估後「不成立」的三個出口。飼主撤回見 PRD-026；付款與付款逾期 O8 見 PRD-007；轉介本身見 PRD-010。
> AC 內以「情境 n」分隔的每個情境各自有完整的 Given / When / Then，TS 以「AC-x.y-情境 n」逐列對應。

---

## 1. 背景與目的

保母評估後不一定接單；保母一直不處理時訂單也不能永遠卡在 `PENDING`；報價後若保母發現無法服務，在飼主付款前也需要出口。本卡定義三個出口：保母婉拒（O3 SITTER，可接轉介）、評估逾期由系統取消（O4）、付款前保母取消（O7）。三者都讓訂單進入 `CANCELLED`、同交易取消剩餘行程與作廢未結束 payment，並通知雙方；保母取消不能在飼主已上傳付款憑證後發生。

## 2. 使用者故事

- **US-1** 身為保母，我希望婉拒不適合的預約並告知原因，以便飼主盡快另找保母。
- **US-2** 身為保母，我希望婉拒時能順手轉介信任圈夥伴，以便不讓飼主落空。
- **US-3** 身為飼主，我希望保母一直沒回應時預約會自動取消並通知我，以便不必一直等待。
- **US-4** 身為保母，我希望報價後、飼主付款前發現無法服務時能取消訂單並說明原因，以便不讓飼主付了款才被取消。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 婉拒預約（O3） | ✗ 403 `ROLE_MISMATCH`（飼主撤回見 PRD-026） | 本人訂單 | ✗ 403 `ROLE_MISMATCH` | ✗ 401 | 無（FREE / BASIC / PRO / ULTIMATE 皆同）；訂閱鎖定、月額度已滿皆不擋（BR-7） |
| 婉拒後轉介 | ✗ | 本人訂單（前置條件見 BR-3，轉介規則見 PRD-010） | ✗ | ✗ | 依 PRD-010 |
| 評估逾期取消（O4） | ✗ | ✗ | ✗ | ✗ | 僅 SYSTEM 排程 |
| 付款前取消（O7） | ✗ 403 `ROLE_MISMATCH`（飼主撤回見 PRD-026） | 本人訂單 | ✗ 403 `ROLE_MISMATCH`（ADMIN 作廢為 O16，見 PRD-007） | ✗ 401 | 無；訂閱鎖定不擋（BR-7） |

- 「本人訂單」以 token 身分判斷；存在但不屬於呼叫者 → 403 `FORBIDDEN`，不存在 → 404 `NOT_FOUND`；`currentRole` 不符 → 403 `ROLE_MISMATCH`；未登入 → 401 `UNAUTHENTICATED`（baseline §1.1、§1.2）。
- 排程端點 `/api/internal/cron/*` 只接受 Cloud Scheduler 身分（baseline §3.6）；其他身分呼叫 → 403 `FORBIDDEN`，不處理任何訂單（EX-20；身分驗證方式見 SD-GLOBAL-SPEC）。

## 4. 主流程

### 4.1 保母婉拒（O3 SITTER）

1. 保母在評估頁（PRD-006）按「婉拒預約」（`sitter-order-eval-btn-decline`）→ 對話框要求填寫原因（`sitter-order-decline-input-reason`），提供「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」快速帶入按鈕（`sitter-order-decline-btn-quick-reason-{n}`，n = 1–4；帶入後可編輯，送出的仍是文字），並提示「婉拒後飼主將收到通知，含您填寫的原因」。
2. 保母按「確認婉拒」（`sitter-order-decline-btn-confirm`，帶 `Idempotency-Key`）→ 系統依 baseline §3.7 判斷順序檢查（BR-14）：401 → 冪等回放 → 403 `ROLE_MISMATCH` → 404 → 403 `FORBIDDEN` → 400 `VALIDATION_FAILED`（缺 `Idempotency-Key`；原因去除前後空白（含全形空白，定義見 SD-GLOBAL-SPEC）後為空或超過 200 字）→ 冪等衝突 → 鎖訂單列後訂單不是 `PENDING` → 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`）。**不檢查** `review_expires_at`：已過評估期限、但排程尚未把訂單寫成 `CANCELLED` 時仍允許婉拒（baseline §3.2 例外、CQ-61）。
3. 通過後同一交易：訂單 `PENDING → CANCELLED`、`cancelledBy = SITTER`、記錄原因；剩餘 `PENDING` 行程 → `CANCELLED`（BR-12）；寫 `order_logs`（`ORDER_REJECTED`，含原因）；通知雙方：飼主「預約已婉拒」（含原因、連到訂單詳情），保母「您已婉拒 {飼主顯示名稱} 的預約」（BR-2）。
4. 回應成功後，對話框顯示「轉介信任圈夥伴」（`sitter-order-decline-btn-referral`）；保母選擇後進入 PRD-010 轉介流程（另一個請求）。不轉介則關閉；關閉後不再提供轉介入口（含歷史訂單頁，BR-3）。
5. 飼主訂單詳情（`owner-order-detail-cancel-result`）顯示「保母已婉拒此預約」與原因；保母端該訂單移到「歷史」，無任何接單 / 報價按鈕。

### 4.2 評估逾期（O4）

1. 排程每 15 分鐘執行一次（baseline §5），先唯讀查出 `status = PENDING` 且 `now ≥ review_expires_at` 的訂單。
2. 逐筆以獨立交易處理：鎖訂單列（`SKIP LOCKED`）後重驗條件 → 仍成立則 `PENDING → CANCELLED`、`cancelledBy = SYSTEM`、原因「保母未於期限內回覆」；剩餘 `PENDING` 行程 → `CANCELLED`（BR-12）；寫 `order_logs`（`ORDER_REVIEW_EXPIRED`）；通知雙方：飼主「保母未於期限內回覆，預約已自動取消」，保母「預約已逾期自動取消」；飼主訂單詳情 `owner-order-detail-cancel-result` 標題顯示「保母未於期限內回覆，預約已自動取消」（SD-ORDER CQ-24，使用者決定 2026-11-22）。條件不成立（已被報價 / 撤回 / 婉拒）則略過。
3. 一筆失敗不影響其他筆（該筆交易回滾、寫錯誤 log）；排程可重跑。排程端點回 200，body 含 `processed`（已取消筆數）、`skipped`（重驗不成立或鎖不到而略過）、`failed`（拋例外）計數（BR-15）。
4. 排程未跑之前：非取消類操作（報價）在期限後依時間被拒（PRD-006 AC-3.4）；取消類操作（保母婉拒、飼主撤回 PRD-026）仍允許，與排程以鎖訂單列序列化，先取得鎖者決定 `cancelledBy`（baseline §3.2、CQ-46、CQ-61）。

### 4.3 付款前保母取消（O7）

1. 保母在 `PENDING_PAYMENT` 訂單詳情按「取消訂單」（`sitter-order-detail-btn-cancel`）→ 對話框要求填寫原因（`sitter-order-cancel-input-reason`，快速帶入按鈕 `sitter-order-cancel-btn-quick-reason-{n}`，同 4.1），提示「取消後飼主將收到通知，含您填寫的原因」。
2. 保母按「確認取消」（`sitter-order-cancel-btn-confirm`，帶 `Idempotency-Key`）→ 系統依 baseline §3.7 判斷順序檢查（BR-14）：同 4.1 步驟 2 至冪等衝突 → 鎖訂單列後訂單不是 `PENDING_PAYMENT` → 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`）→ 首次收款 payment 為 `SUBMITTED` → 409 `PAYMENT_ALREADY_SUBMITTED`。**不檢查** `due_at`：已過付款期限、但 O8 排程（PRD-007）尚未把訂單寫成 `CANCELLED` 時仍允許（baseline §3.2 例外、CQ-61）。
3. 通過後同一交易：payment `UNPAID → VOID`；訂單 `PENDING_PAYMENT → CANCELLED`、`cancelledBy = SITTER`、記錄原因；剩餘 `PENDING` 行程 → `CANCELLED`（BR-12）；寫 `order_logs`（`ORDER_CANCELLED_BY_SITTER`）；通知雙方：飼主「保母已取消訂單」（含原因），保母「您已取消訂單」（BR-2）；飼主訂單詳情 `owner-order-detail-cancel-result` 標題顯示「保母已取消此訂單」與原因（SD-ORDER CQ-24，使用者決定 2026-11-22）。
4. payment 為 `SUBMITTED`（飼主已上傳憑證）時不可取消：保母開啟訂單詳情即顯示 `sitter-order-cancel-blocked-message`、隱藏「取消訂單」按鈕（與訂單詳情 `allowedActions` 不含取消一致），並引導保母先核對或駁回（PRD-007）；直接呼叫 API 仍回 409 `PAYMENT_ALREADY_SUBMITTED`。駁回後 payment 回 `UNPAID`，提示消失、按鈕恢復顯示，才能取消。
5. 取消成功後不提供轉介入口（BR-13）。

## 5. 狀態轉移

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1（O3） | 訂單 `PENDING` | `CANCELLED`（`cancelledBy=SITTER`） | SITTER | 本人訂單；原因去空白後 1–200 字；不看 `review_expires_at`（CQ-61） | 401 `UNAUTHENTICATED`；403 `ROLE_MISMATCH` / `FORBIDDEN`；404 `NOT_FOUND`；400 `VALIDATION_FAILED`；409 `ORDER_STATE_CONFLICT` |
| T2（O4） | 訂單 `PENDING` | `CANCELLED`（`cancelledBy=SYSTEM`） | SYSTEM | `now ≥ review_expires_at` | 不符則略過（計入 `skipped`） |
| T3（O7） | 訂單 `PENDING_PAYMENT` | `CANCELLED`（`cancelledBy=SITTER`） | SITTER | 本人訂單；首次收款 payment `UNPAID`；原因去空白後 1–200 字；不看 `due_at`（CQ-61） | 同 T1；另 409 `PAYMENT_ALREADY_SUBMITTED` |
| T4（附帶，CQ-65） | 首次收款 payment `UNPAID` | `VOID` | 同交易（附帶 T3） | T3 成功 | — |
| T5（附帶，CQ-65） | 行程 `PENDING` | `CANCELLED` | 同交易（附帶 T1 / T2 / T3） | T1 / T2 / T3 成功 | — |

- 附帶轉移不單獨觸發，觸發者與 `order_logs` 操作者記在主轉移；寫法與 PRD-026 一致（主流程一致性決定 2026-11-21）。

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 婉拒只在 `PENDING`；需原因；問卷狀態不影響婉拒 | baseline O3、§3.5；BR §3.6 步驟 4 |
| BR-2 | 婉拒、付款前取消、評估逾期都通知雙方：婉拒 → 飼主「預約已婉拒」（含保母原因、連到訂單詳情）、保母「您已婉拒 {飼主顯示名稱} 的預約」；O7 → 飼主「保母已取消訂單」（含原因）、保母「您已取消訂單」；O4 → 飼主「保母未於期限內回覆，預約已自動取消」、保母「預約已逾期自動取消」。飼主訂單詳情 `owner-order-detail-cancel-result` 顯示結果標題與原因：O3「保母已婉拒此預約」、O7「保母已取消此訂單」、O4「保母未於期限內回覆，預約已自動取消」；送出前確認框文字與實際行為一致 | BR §3.6 步驟 4「取消接單…需信件通知雙方」；沿用原 PRD-006 D 段（2026-10-26 fix-batch13 K1）；保母端文案為審查第 1 輪 #1；主流程一致性決定 #4；O4 / O7 詳情標題為使用者決定 2026-11-22（SD-ORDER CQ-24） |
| BR-3 | 婉拒與轉介為兩步驟：婉拒先成立，成功後在同一對話框提供轉介入口；轉介是獨立請求，成功與否不影響已成立的婉拒（轉介失敗不回滾）。轉介前置條件（由 PRD-010 實作）：訂單 `CANCELLED`、`cancelledBy=SITTER`、有 `ORDER_REJECTED` log、呼叫者為該訂單 sitter。入口只在婉拒成功的對話框提供，關閉後不再提供（含歷史訂單頁）。轉介對象、黑名單過濾、通知內容依 PRD-010；PRD-010 的「不接單並轉介」單一動作須改為本卡兩步驟（卡外） | BR §5.3 轉介流程 1；PRD-010 B 段；使用者決定 2026-11-21（CQ-3、CQ-5） |
| BR-4 | 評估逾期：`now ≥ review_expires_at` 且仍 `PENDING` → `CANCELLED`、`cancelledBy=SYSTEM`，通知雙方 | baseline O4、§5、CQ-12 |
| BR-5 | 排程每 15 分鐘；候選唯讀查出後逐筆獨立交易、鎖後重驗、`SKIP LOCKED`、可重跑；判斷以「`now ≥` 到期時間」為準（等於到期時間即處理），不依賴排程頻率 | baseline §3.6 排程、§5 |
| BR-6 | 付款前保母取消：只在 `PENDING_PAYMENT` 且首次收款 payment `UNPAID`；`SUBMITTED` 時只能駁回（PRD-007），取消 → 409 `PAYMENT_ALREADY_SUBMITTED`；需原因 | baseline O7、§3.3、CQ-13 |
| BR-7 | 婉拒、付款前取消不受訂閱鎖定限制（baseline 鎖定範圍只列公開頁預約、報價、方案 / 問卷 / 公開檔案設定）；也不受月接單額度影響（額度只擋 O1 與 O2） | baseline §2.2、O1、O2、CQ-19、CQ-62 |
| BR-8 | 期限例外：婉拒、付款前取消的結果本身就是取消，訂單尚未進入終態前，即使已過 `review_expires_at` / `due_at` 仍允許，`cancelledBy = SITTER`、寫本卡的 log 動作（`ORDER_REJECTED` / `ORDER_CANCELLED_BY_SITTER`），不回 `DEADLINE_PASSED`。與排程（O4 / O8）同時發生時以鎖訂單列序列化：保母先 → 排程鎖後重驗略過；排程先 → 保母 409 `ORDER_STATE_CONFLICT` | baseline §3.2（期限例外）、CQ-46、CQ-61；使用者決定 2026-11-21（CQ-2，取代原建議「套用 `DEADLINE_PASSED`」） |
| BR-9 | 原因先去除前後空白，結果須為 1–200 字（以 Unicode 字元計），空白或超過 → 400 `VALIDATION_FAILED`；前端提供「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」快速帶入文字，送出與保存的是文字，不另存選項代碼 | baseline O3、O7（需原因）；使用者決定 2026-11-21（CQ-1） |
| BR-10 | 每次轉移寫一筆 `order_logs`，與狀態變更同交易；通知以 outbox 同交易寫入站內通知（雙方必寫），Email 依 PRD-014 偏好由排程寄送（婉拒、O4、O7 通知皆不屬 baseline §3.6 / CQ-73 不可關閉類別） | baseline §3.6、CQ-73 |
| BR-11 | 婉拒、取消須帶 `Idempotency-Key`（缺少 → 400 `VALIDATION_FAILED`，`field=Idempotency-Key`），依 baseline §3.6 回放 | baseline §3.6、§3.7 |
| BR-12 | 取消的共同附帶動作：T1 / T2 / T3 同一交易內把剩餘 `PENDING` 行程轉 `CANCELLED`、未結束 payment 轉 `VOID`。O3 / O4 時訂單尚未報價、沒有 payment，實際只取消行程；O7 時首次收款 payment `UNPAID → VOID`（T4）。取消不影響月接單額度（額度只在 `CONFIRMED` 計數） | baseline §3.2（取消的共同附帶動作）、CQ-65、§2.2；使用者決定 2026-11-21 |
| BR-13 | O7 付款前取消成功後不提供轉介入口；轉介只在婉拒（O3）後提供 | BR §5.3（只寫評估頁）；使用者決定 2026-11-21（CQ-4） |
| BR-14 | 錯誤判斷順序依 baseline §3.7（CQ-70）：401 → 冪等回放 → 403 `ROLE_MISMATCH` → 404 → 403 `FORBIDDEN` → 400 → 冪等衝突 → 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`，前端依此選文案）→ 409 `PAYMENT_ALREADY_SUBMITTED`（僅 O7） | baseline §3.7、CQ-70、payload 共通欄位 |
| BR-15 | O4 排程端點回 200，body 含 `processed` / `skipped` / `failed` 計數；失敗筆寫錯誤 log（含訂單 id），不影響其他筆；非 Cloud Scheduler 身分 → 403 `FORBIDDEN`，不處理任何訂單 | baseline §3.6 排程；審查第 1 輪 #4、#8 |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 其他保母婉拒 / 取消 | 拒絕，不改資料 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | 以 OWNER 角色呼叫婉拒 / 保母取消 | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換至保母身分」 |
| EX-3 | ADMIN 呼叫婉拒 / 保母取消 | 拒絕（ADMIN 不是觸發者；ADMIN 作廢為 O16，見 PRD-007） | 403 `ROLE_MISMATCH` | — |
| EX-4 | 訂單不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此訂單」 |
| EX-5 | 原因去空白後為空或超過 200 字；缺 `Idempotency-Key` | 拒絕 | 400 `VALIDATION_FAILED`（`field=reason` / `Idempotency-Key`） | 欄位下方「請填寫原因」/「原因最多 200 字」 |
| EX-6 | 婉拒時訂單已非 `PENDING`（已報價、飼主已撤回、已被 O4 取消） | 拒絕 | 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`） | 「訂單狀態已變更，請重新整理」 |
| EX-7 | 取消時訂單非 `PENDING_PAYMENT`（已 `CONFIRMED`、已取消含 O8、仍 `PENDING`） | 拒絕；`CONFIRMED` 後改走變更協商整筆取消（PRD-016） | 409 `ORDER_STATE_CONFLICT`（payload `currentStatus`） | `currentStatus=CONFIRMED` / `IN_PROGRESS` →「訂單已成立，如需取消請申請訂單變更」；其他 →「訂單狀態已變更，請重新整理」 |
| EX-8 | 取消時飼主已上傳憑證（payment `SUBMITTED`） | 拒絕，不改資料 | 409 `PAYMENT_ALREADY_SUBMITTED` | 開啟訂單詳情即顯示 `sitter-order-cancel-blocked-message`「飼主已上傳付款憑證，請先核對或駁回」與核對頁連結（PRD-007），`sitter-order-detail-btn-cancel` 隱藏；409 只在直接呼叫 API 或畫面過期時出現 |
| EX-9 | 已過 `review_expires_at`（婉拒）/ `due_at`（取消），排程尚未處理 | 照常執行（BR-8），`cancelledBy = SITTER` | 200 | 同正常流程 |
| EX-10 | 跨角色併發：保母取消與飼主上傳憑證（PRD-007）同時 | 鎖訂單列序列化。上傳先 → 取消得 EX-8；取消先 → 上傳被拒，已上傳的 GCS 物件刪除（PRD-007 AC-1.8） | 取消後到：409 `PAYMENT_ALREADY_SUBMITTED`；上傳後到：409 `PAYMENT_STATE_CONFLICT` | 依先後 |
| EX-11 | 跨角色併發：保母婉拒與飼主撤回（PRD-026）同時 | 先到者生效（`cancelledBy` 記先到者），後到者 EX-6 | 409 `ORDER_STATE_CONFLICT` | 同 EX-6 |
| EX-12 | 排程（O4 / O8）與保母婉拒 / 取消在期限後同時 | 鎖訂單列序列化：保母先 → 排程鎖後重驗略過，不寫逾期 log、不發逾期通知；排程先 → 保母得 EX-6 / EX-7。排程與保母報價同時：報價在 `now ≥ review_expires_at` 時已被 PRD-006 EX-10 擋下 | 保母後到 409 `ORDER_STATE_CONFLICT` | 「訂單狀態已變更，請重新整理」 |
| EX-13 | 鎖等待逾時 | 不改資料；排程則略過該筆（計入 `skipped`），下次重跑 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「系統忙碌，請稍後再試」 |
| EX-14 | 重複送出（同 key 同內容）/ 同 key 不同內容 / 處理中 | 回放第一次結果 / 拒絕 / 拒絕 | 同第一次 / 422 `IDEMPOTENCY_KEY_REUSED` / 409 `IDEMPOTENCY_IN_PROGRESS` | 同第一次 / 「請重新操作」 |
| EX-15 | 保母訂閱鎖定時婉拒或取消 | 照常執行（BR-7） | 200 | 同正常流程 |
| EX-16 | 轉介失敗（PRD-010 回錯） | 婉拒已成立不回滾；轉介對話框顯示錯誤並可重試 | 依 PRD-010 | 「轉介未送出，請稍後再試」；訂單仍為已婉拒 |
| EX-17 | Resend 寄信失敗 | 不影響轉移；站內通知已同交易寫入；Email 排程重試最多 5 次，4xx 直接判失敗 | — | 站內通知照常 |
| EX-18 | GCS | 本卡自身無檔案上傳；與上傳併發時的物件刪除見 EX-10（由 PRD-007 處理） | — | — |
| EX-19 | 排程單筆處理失敗（例外） | 該筆交易回滾（含行程取消），寫錯誤 log，其他筆照常；下次排程重試 | 排程端點 200，body `failed` 計數 +1 | — |
| EX-20 | 非 Cloud Scheduler 身分呼叫 `/api/internal/cron/*` | 拒絕，不處理任何訂單 | 403 `FORBIDDEN` | — |

## 8. 驗收標準（AC）

### 子模組 1：保母婉拒（O3 SITTER）與轉介入口

- [ ] **AC-1.1 (婉拒成功)**：Given SITTER A 的訂單 `PENDING`（3 個 `PENDING` 行程），飼主 O（顯示名稱「王小明」）。情境 1：Given 雙方訂單類 Email 偏好開啟，When A 在 `sitter-order-decline-input-reason` 填「該週檔期已滿」並按 `sitter-order-decline-btn-confirm`，Then 200；訂單 `CANCELLED`、`cancelledBy = SITTER`、原因已記錄；3 個行程皆 `CANCELLED`；無任何 payment；寫 `order_logs`（`ORDER_REJECTED`，payload 含原因）；O 有 1 則站內通知「預約已婉拒」（內容含「該週檔期已滿」並連到訂單詳情），A 有 1 則站內通知「您已婉拒 王小明 的預約」；雙方各 1 筆 Email 投遞紀錄；O 的 `owner-order-detail-cancel-result` 顯示「保母已婉拒此預約」與原因；保母端該訂單在「歷史」且無接單 / 報價按鈕。情境 2：Given 雙方訂單類 Email 偏好關閉，When A 婉拒，Then 雙方各 1 則站內通知、無 Email 投遞紀錄。對應：T1、T5、BR-1、BR-2、BR-10、BR-12
- [ ] **AC-1.2 (婉拒確認框與原因)**：情境 1：Given A 在評估頁按 `sitter-order-eval-btn-decline`，Then 對話框出現必填的 `sitter-order-decline-input-reason`、4 個 `sitter-order-decline-btn-quick-reason-{n}`（「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」）與文字「婉拒後飼主將收到通知，含您填寫的原因」。情境 2：When 點 `sitter-order-decline-btn-quick-reason-1`，Then 原因欄帶入「檔期已滿」且可編輯。情境 3：Given 原因欄空白，Then `sitter-order-decline-btn-confirm` 停用。情境 4：When 直接呼叫 API 帶空字串、全空白字元或去空白後 201 字的原因，Then 400 `VALIDATION_FAILED`（`field=reason`），訂單維持 `PENDING`。對應：BR-2、BR-9、EX-5
- [ ] **AC-1.3 (婉拒被拒)**：情境 1：Given 訂單已 `PENDING_PAYMENT`、已被飼主撤回或已被 O4 取消，When A 婉拒，Then 409 `ORDER_STATE_CONFLICT`，payload `currentStatus` 分別為 `PENDING_PAYMENT`、`CANCELLED`、`CANCELLED`。情境 2：When SITTER B 婉拒，Then 403 `FORBIDDEN`。情境 3：When A 以 OWNER 角色婉拒，Then 403 `ROLE_MISMATCH`。情境 4：When ADMIN 婉拒，Then 403 `ROLE_MISMATCH`。情境 5：When 婉拒不存在的訂單 id，Then 404 `NOT_FOUND`。情境 6：When 不帶 `Idempotency-Key`，Then 400 `VALIDATION_FAILED`（`field=Idempotency-Key`）。皆不改資料、不發通知。對應：BR-11、BR-14、EX-1~EX-6
- [ ] **AC-1.4 (婉拒併發與重送)**：情境 1：Given 訂單 `PENDING`，When 飼主撤回（PRD-026）與 A 婉拒同時送出，Then 只有一方成功，另一方 409 `ORDER_STATE_CONFLICT`（`currentStatus=CANCELLED`），`cancelledBy` 為成功者，`order_logs` 只有一筆取消紀錄。情境 2：Given 婉拒已以 key K 成功，When 同 key 同內容重送，Then 回放同一 200，雙方站內通知各仍 1 則。對應：BR-11、EX-11、EX-14
- [ ] **AC-1.5 (轉介入口)**：情境 1：Given 婉拒成功，Then 對話框出現 `sitter-order-decline-btn-referral`「轉介信任圈夥伴」，點擊進入 PRD-010 轉介選單。情境 2：Given 進入轉介後 PRD-010 回錯，Then 訂單仍為 `CANCELLED`、顯示「轉介未送出，請稍後再試」且可重試。情境 3：Given 婉拒成功後不點轉介直接關閉對話框，Then 不送任何轉介通知；When A 之後在「歷史」開啟該訂單，Then 不出現 `sitter-order-decline-btn-referral`。對應：BR-3、EX-16
- [ ] **AC-1.6 (鎖定或額度已滿仍可婉拒)**：情境 1：Given A 訂閱已逾期鎖定，When A 婉拒 `PENDING` 訂單，Then 200，結果同 AC-1.1 情境 1。情境 2：Given A 為 FREE 且本月已 3 筆 `CONFIRMED`，When A 婉拒 `PENDING` 訂單，Then 200，結果同 AC-1.1 情境 1。對應：BR-7、EX-15
- [ ] **AC-1.7 (期限後仍可婉拒)**：情境 1：Given 訂單 `PENDING`、`review_expires_at = 2026-12-10T16:00:00Z`、O4 排程尚未處理，When A 於 `2026-12-10T16:03:00Z` 婉拒，Then 200；訂單 `CANCELLED`、`cancelledBy = SITTER`、行程 `CANCELLED`、log 為 `ORDER_REJECTED`；When 之後 O4 排程執行，Then 略過該筆，無 `ORDER_REVIEW_EXPIRED` log、無逾期通知。情境 2：Given O4 已先取消該訂單，When A 婉拒，Then 409 `ORDER_STATE_CONFLICT`（`currentStatus=CANCELLED`），`cancelledBy` 維持 `SYSTEM`。對應：BR-8、EX-9、EX-12

### 子模組 2：評估逾期（O4）

- [ ] **AC-2.1 (逾期取消)**：Given 訂單 `PENDING`（2 個 `PENDING` 行程）、`review_expires_at = 2026-12-10T16:00:00Z`、雙方訂單類 Email 偏好開啟，When 排程於 `2026-12-10T16:05:00Z` 執行，Then 訂單 `CANCELLED`、`cancelledBy = SYSTEM`、原因「保母未於期限內回覆」；2 個行程皆 `CANCELLED`；寫 `order_logs`（`ORDER_REVIEW_EXPIRED`）；飼主有 1 則站內通知「保母未於期限內回覆，預約已自動取消」、保母有 1 則站內通知「預約已逾期自動取消」，雙方各 1 筆 Email 投遞紀錄；飼主 `owner-order-detail-cancel-result` 標題顯示「保母未於期限內回覆，預約已自動取消」；排程回 200、`processed=1`。對應：T2、T5、BR-2、BR-4、BR-12、BR-15
- [ ] **AC-2.2 (到期邊界)**：Given `review_expires_at = 2026-12-10T16:00:00Z`。情境 1：When 排程於 `2026-12-10T15:59:00Z` 執行，Then 訂單維持 `PENDING`、行程維持 `PENDING`、無通知。情境 2：When 排程於 `2026-12-10T16:00:00Z` 整執行，Then 訂單 `CANCELLED`、`cancelledBy = SYSTEM`，結果同 AC-2.1。對應：BR-4、BR-5
- [ ] **AC-2.3 (重驗與略過)**：情境 1：Given 候選查出後、處理前保母已報價（`PENDING_PAYMENT`）、已婉拒或飼主已撤回，When 排程處理該筆，Then 略過，不改狀態、不寫 log、不發通知，回應 `skipped` 計入該筆。情境 2：Given 同一批次 3 筆候選，其中 1 筆處理拋例外，When 排程執行，Then 該筆訂單與行程皆未變更、有含訂單 id 的錯誤 log，其他 2 筆照常取消，回 200、`processed=2`、`failed=1`；When 下次排程執行且例外原因已排除，Then 該筆被取消。對應：BR-5、BR-15、EX-12、EX-19
- [ ] **AC-2.4 (重跑冪等與端點身分)**：情境 1：Given 訂單已被 O4 取消，When 排程再跑一次，Then 該筆不再出現於候選，回應 `processed=0`，通知與 `order_logs` 各只有一筆。情境 2：When 以一般使用者 token（任一角色）呼叫 O4 排程端點，Then 403 `FORBIDDEN`，所有已到期的 `PENDING` 訂單維持 `PENDING`。對應：BR-5、BR-15、EX-20

### 子模組 3：付款前保母取消（O7）

- [ ] **AC-3.1 (取消成功)**：Given 訂單 `PENDING_PAYMENT`、首次收款 payment `UNPAID`、`now < due_at`、雙方訂單類 Email 偏好開啟，When SITTER A 按 `sitter-order-detail-btn-cancel`、在 `sitter-order-cancel-input-reason` 填「臨時生病無法服務」並按 `sitter-order-cancel-btn-confirm`，Then 200；payment `VOID`；訂單 `CANCELLED`、`cancelledBy = SITTER`、原因已記錄；所有行程 `CANCELLED`；寫 `order_logs`（`ORDER_CANCELLED_BY_SITTER`）；飼主有 1 則站內通知「保母已取消訂單」（含原因）、A 有 1 則站內通知「您已取消訂單」，雙方各 1 筆 Email 投遞紀錄；飼主詳情不再顯示付款入口，`owner-order-detail-cancel-result` 標題顯示「保母已取消此訂單」並顯示取消原因；取消成功畫面不出現 `sitter-order-decline-btn-referral`。對應：T3、T4、T5、BR-2、BR-6、BR-12、BR-13
- [ ] **AC-3.2 (已上傳憑證不可取消)**：情境 1：Given payment `SUBMITTED`，When A 開啟訂單詳情，Then 不出現 `sitter-order-detail-btn-cancel`，出現 `sitter-order-cancel-blocked-message`「飼主已上傳付款憑證，請先核對或駁回」與核對頁連結；When A 直接呼叫取消 API，Then 409 `PAYMENT_ALREADY_SUBMITTED`，訂單、行程與 payment 不變。情境 2：Given A 駁回（PRD-007，payment 回 `UNPAID`），When A 重新開啟訂單詳情，Then 出現 `sitter-order-detail-btn-cancel`、不出現 `sitter-order-cancel-blocked-message`；When A 取消，Then 200。對應：BR-6、EX-8
- [ ] **AC-3.3 (狀態不允許)**：情境 1：Given 訂單 `CONFIRMED`，When A 取消，Then 409 `ORDER_STATE_CONFLICT`（`currentStatus=CONFIRMED`），畫面提示「訂單已成立，如需取消請申請訂單變更」。情境 2：Given 訂單 `PENDING`（應走婉拒）或 `CANCELLED`，When A 取消，Then 409 `ORDER_STATE_CONFLICT`（`currentStatus` 分別為 `PENDING`、`CANCELLED`），畫面提示「訂單狀態已變更，請重新整理」。皆不改資料。對應：BR-14、EX-7
- [ ] **AC-3.4 (越權與原因)**：情境 1：When SITTER B 取消，Then 403 `FORBIDDEN`。情境 2：When A 以 OWNER 角色取消，Then 403 `ROLE_MISMATCH`。情境 3：When ADMIN 取消，Then 403 `ROLE_MISMATCH`。情境 4：When 原因空白或只有空白字元，Then 400 `VALIDATION_FAILED`（`field=reason`）。情境 5：When 不帶 `Idempotency-Key`，Then 400 `VALIDATION_FAILED`（`field=Idempotency-Key`）。皆不改資料。對應：BR-11、EX-1–EX-3、EX-5
- [ ] **AC-3.5 (跨角色併發)**：Given 訂單 `PENDING_PAYMENT`、payment `UNPAID`，When 飼主上傳憑證（PRD-007）與 A 取消同時送出，Then 只有一個成立。情境 1（上傳先）：A 得 409 `PAYMENT_ALREADY_SUBMITTED`、payment `SUBMITTED`、訂單 `PENDING_PAYMENT`。情境 2（取消先）：payment `VOID`、訂單 `CANCELLED`，飼主上傳得 409 `PAYMENT_STATE_CONFLICT`，該次上傳的 GCS 物件已刪除（PRD-007 AC-1.8），無新 submission。兩個情境都不會出現 payment `VOID` 但留有 `SUBMITTED` submission 的狀態。對應：EX-10
- [ ] **AC-3.6 (重送與鎖定)**：情境 1：Given 取消已以 key K 成功，When 同 key 同內容重送，Then 回放同一 200、payment 不重複作廢、雙方站內通知各仍 1 則。情境 2：Given A 訂閱鎖定，When 取消 `UNPAID` 訂單，Then 200。對應：BR-7、BR-11、EX-14、EX-15
- [ ] **AC-3.7 (付款期限後仍可取消)**：情境 1：Given 訂單 `PENDING_PAYMENT`、payment `UNPAID`、`due_at = 2026-12-08T16:00:00Z`、O8 排程（PRD-007）尚未處理，When A 於 `2026-12-08T16:05:00Z` 取消，Then 200；payment `VOID`、訂單 `CANCELLED`、`cancelledBy = SITTER`、log 為 `ORDER_CANCELLED_BY_SITTER`；When 之後 O8 執行，Then 略過該筆。情境 2：Given O8 已先取消，When A 取消，Then 409 `ORDER_STATE_CONFLICT`（`currentStatus=CANCELLED`），`cancelledBy` 維持 `SYSTEM`。對應：BR-8、EX-9、EX-12

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | 訂單：`status`、`cancelledBy`、取消原因；剩餘 `PENDING` 行程隨訂單同交易轉 `CANCELLED`；首次收款 payment `VOID`（O7）；`order_logs` 動作 `ORDER_REJECTED`、`ORDER_REVIEW_EXPIRED`、`ORDER_CANCELLED_BY_SITTER`。DDL 在 SD-ORDER |
| 畫面 | 保母：評估頁「婉拒預約」對話框（含快速帶入原因）與轉介入口（PRD-006 頁面）、`PENDING_PAYMENT` 訂單詳情「取消訂單」對話框；飼主：訂單詳情的婉拒 / 取消 / 逾期結果顯示（`owner-order-detail-cancel-result` 標題：O3「保母已婉拒此預約」、O7「保母已取消此訂單」、O4「保母未於期限內回覆，預約已自動取消」）。testid：`sitter-order-eval-btn-decline`、`sitter-order-decline-input-reason`、`sitter-order-decline-btn-quick-reason-{n}`、`sitter-order-decline-btn-confirm`、`sitter-order-decline-btn-referral`、`sitter-order-detail-btn-cancel`、`sitter-order-cancel-input-reason`、`sitter-order-cancel-btn-quick-reason-{n}`、`sitter-order-cancel-btn-confirm`、`sitter-order-cancel-blocked-message`、`owner-order-detail-cancel-result`（命名依 SD-FRONTEND-SPEC §4） |
| 通知 | 婉拒 → OWNER「預約已婉拒」（含原因）、SITTER「您已婉拒 {飼主} 的預約」；評估逾期 → OWNER「保母未於期限內回覆，預約已自動取消」、SITTER「預約已逾期自動取消」；付款前取消 → OWNER「保母已取消訂單」（含原因）、SITTER「您已取消訂單」。站內必寫，Email 依 PRD-014 偏好 |
| 排程 | 有：評估逾期，每 15 分鐘，`/api/internal/cron/*`（僅 Cloud Scheduler 身分）；時間基準 `orders.review_expires_at`（UTC 儲存，顯示 Asia/Taipei）；回 200 + `processed` / `skipped` / `failed` 計數 |
| API | 婉拒、付款前取消、評估逾期排程端點。契約在 SD-ORDER |
| 卡外依賴 | PRD-010 須把「不接單並轉介」改為本卡兩步驟，並寫入轉介前置條件（BR-3、CQ-5）；本卡不改 PRD-010 |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 / 決定 | 狀態 |
|----|------|-----------|------|
| CQ-1 | 婉拒 / 取消原因的長度與常用選項（原 PRD-006 為「可選擇 / 填寫」，未填可送出；baseline 改為必填） | 決定：必填，去除前後空白後 1–200 字；前端提供「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」快速帶入文字，送出的仍是文字（BR-9、AC-1.2） | 已決（使用者 2026-11-21） |
| CQ-2 | baseline §3.2「已過期限的使用者操作 → `DEADLINE_PASSED`」是否也適用於婉拒（`review_expires_at`）與保母取消（`due_at`） | （原建議：適用。）決定：不適用；訂單未進終態前仍允許，`cancelledBy = SITTER`（BR-8、AC-1.7、AC-3.7）；baseline CQ-61 | 已決（使用者 2026-11-21） |
| CQ-3 | 婉拒與轉介是一個請求（「不接單並轉介」，PRD-010 B.1）還是兩步驟 | 決定：兩步驟：婉拒先成立，再開轉介；轉介失敗不回滾婉拒（BR-3、AC-1.5） | 已決（使用者 2026-11-21） |
| CQ-4 | O7 付款前取消後是否也提供轉介入口（BR §5.3 只寫評估頁） | 決定：不提供（BR-13、AC-3.1） | 已決（使用者 2026-11-21） |
| CQ-5 | 婉拒後的轉介：PRD-010（Draft）寫的是單一動作「不接單並轉介」，本卡改成兩步驟後，訂單在轉介時已是 `CANCELLED`。PRD-010 要接受哪種狀態的訂單？轉介入口關掉後還能從歷史訂單再開嗎？（審查第 1 輪 #2） | 決定：PRD-010 的轉介前置條件 = 訂單 `CANCELLED`、`cancelledBy=SITTER`、有 `ORDER_REJECTED` log、呼叫者為該訂單 sitter；入口只在婉拒成功的對話框提供，關掉就不再提供（避免事後打擾飼主）；PRD-010 的「不接單並轉介」改成本卡的兩步驟（BR-3、AC-1.5 情境 3；PRD-010 修改屬卡外） | 已決（使用者 2026-11-21） |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-22 | 核准後回寫，使用者已同意 2026-11-22：TS 撰寫回饋：(1) §4.3 步驟 4、EX-8、AC-3.2：`sitter-order-cancel-blocked-message` 改為開頁即顯示、`sitter-order-detail-btn-cancel` 隱藏（與 `allowedActions` 一致），409 `PAYMENT_ALREADY_SUBMITTED` 只在直接呼叫 API 時出現；(2) §4.1 步驟 2 與 BR-14 改為 401 先於冪等回放（baseline §3.7）；(3) §4.1 步驟 2「去除前後空白」加註含全形空白（定義見 SD-GLOBAL-SPEC）。(4) 飼主取消結果標題（SD-ORDER §9.3、CQ-24，使用者決定 2026-11-22）：`owner-order-detail-cancel-result` O7「保母已取消此訂單」、O4「保母未於期限內回覆，預約已自動取消」（§4.2 步驟 2、§4.3 步驟 3、BR-2、AC-2.1、AC-3.1、§9）。本卡畫面與通知無金額，金額格式不需改。AC 數不變，狀態維持 Approved | 使用者 2026-11-22 同意 |
| 2026-11-22 | 狀態改 Approved | 使用者核准 |
| 2026-11-21 | 第 1 輪審查修正總覽：處理全部 12 項（重要 2、建議 10）；CQ-5 已決並寫入正文；錯誤判斷順序改依 baseline §3.7（新增 BR-14）；排程端點回應與身分（新增 BR-15、EX-20）；補 testid；狀態 Needs Clarification → In Review。子模組 7 / 4 / 7 = 18 條 AC | `docs/reviews/PRD-027-order-decline-expiry-cancel.review.md` 第 1 輪；使用者決定 2026-11-21；baseline 第 2 版 CQ-70、73；主流程一致性決定 2026-11-21 |
| 2026-11-21 | R1-#1（重要）：婉拒、O7 改為通知雙方，保母端文案「您已婉拒 {飼主} 的預約」「您已取消訂單」（§4.1、§4.3、BR-2、§9）；AC-1.1、AC-3.1 補保母站內通知斷言 | 第 1 輪 #1；BR §3.6 步驟 4；主流程一致性決定 #4 |
| 2026-11-21 | R1-#2（重要）：CQ-5 採建議預設值，轉介前置條件與「關閉後不再提供」寫入 BR-3、§4.1 步驟 4、AC-1.5 情境 3；PRD-010 改寫列為卡外依賴（表頭、§9） | 第 1 輪 #2；使用者決定 2026-11-21；主流程一致性決定 #10 |
| 2026-11-21 | R1-#3（建議）：AC-1.3 情境 4 ADMIN 婉拒 → 403 `ROLE_MISMATCH` | 第 1 輪 #3 |
| 2026-11-21 | R1-#4（建議）：非 Scheduler 身分呼叫 `/api/internal/cron/*` → 403 `FORBIDDEN`、不處理任何訂單（§3、BR-15、EX-20、AC-2.4 情境 2）；驗證方式引用 SD-GLOBAL-SPEC | 第 1 輪 #4 |
| 2026-11-21 | R1-#5（建議）：AC-2.2 情境 2 加邊界「排程於 `16:00:00Z` 整執行 → 取消」；BR-5 註明等於到期時間即處理 | 第 1 輪 #5 |
| 2026-11-21 | R1-#6（建議）：EX-10、AC-3.5 寫出飼主上傳後到 → 409 `PAYMENT_STATE_CONFLICT`，並斷言 GCS 物件已刪除（PRD-007 AC-1.8）；EX-18 改指向 EX-10 | 第 1 輪 #6；PRD-007 EX-18 |
| 2026-11-21 | R1-#7（建議）：`ORDER_STATE_CONFLICT` payload 帶 `currentStatus`（baseline §3.7 共通欄位），EX-6、EX-7 依 `currentStatus` 選文案，AC-1.3、AC-1.4、AC-1.7、AC-3.3、AC-3.7 斷言 | 第 1 輪 #7；baseline §3.7 payload 共通欄位 |
| 2026-11-21 | R1-#8（建議）：排程端點回 200 + `processed` / `skipped` / `failed`，失敗筆寫錯誤 log（§4.2 步驟 3、BR-15、EX-13、EX-19、AC-2.1、AC-2.3、AC-2.4） | 第 1 輪 #8 |
| 2026-11-21 | R1-#9（建議）：T4、T5 觸發者改「同交易（附帶 T3）」「同交易（附帶 T1 / T2 / T3）」，§5 註明與 PRD-026 一致 | 第 1 輪 #9；主流程一致性決定 #7 |
| 2026-11-21 | R1-#10（建議）：缺 `Idempotency-Key` → 400 `VALIDATION_FAILED`（BR-11、EX-5、AC-1.3 情境 6、AC-3.4 情境 5） | 第 1 輪 #10；baseline §3.7 |
| 2026-11-21 | R1-#11（建議）：AC-1.1、AC-2.1、AC-3.1 的 Given 固定雙方訂單類 Email 偏好，斷言站內通知文案與 Email 投遞紀錄；AC-1.1 新增偏好關閉情境；BR-10 註明三類通知不屬 CQ-73 不可關閉 | 第 1 輪 #11；主流程一致性決定 #5 |
| 2026-11-21 | R1-#12（建議）：補 testid（`sitter-order-decline-*`、`sitter-order-cancel-*`、`sitter-order-eval-btn-decline`、`sitter-order-detail-btn-cancel`、`owner-order-detail-cancel-result`），寫入 §4、AC 與 §9 | 第 1 輪 #12；主流程一致性決定 #8 |
| 2026-11-21 | 錯誤判斷順序改依 baseline §3.7（§4.1 步驟 2、§4.3 步驟 2、BR-14）；原因判空改為去除前後空白後計（BR-9）；多情境 AC 改為「情境 n」各自完整 Given / When / Then | baseline CQ-70 |
| 2026-11-21 | 依 baseline 第 2 版修訂與使用者決定改寫：婉拒 / 保母取消不再套用 `DEADLINE_PASSED`，訂單未進終態前允許（4.1、4.3、T1、T3、BR-8、EX-9、EX-12 改寫，新增 AC-1.7、AC-3.7，AC-1.3、AC-3.3 刪除期限條款）；取消共同附帶動作——O3 / O4 / O7 同交易取消剩餘 `PENDING` 行程、作廢未結束 payment（新增 T5，BR-12 改寫，AC-1.1、AC-2.1、AC-3.1 補行程斷言）；刪除問卷子狀態（`PENDING_OWNER`）相關敘述；BR-7 補月額度不擋婉拒；CQ-1、3、4 採建議預設值寫入 BR-9、BR-3、新增 BR-13；CQ-1~4 全部已決；飼主撤回改指向 PRD-026；表頭移除「尚未登記」註記（PRD-PLAN、SD-PLAN 已登記）。子模組 7 / 4 / 7 AC | 使用者決定 2026-11-21（baseline CQ-58、61、62、65；PRD-027 CQ-1、3、4 採建議預設值） |
| 2026-11-21 | 建立（自 PRD-006 拆卡），狀態 `In Review`。內容：原 PRD-006 D 段婉拒（含 2026-10-26 fix-batch13 K1 通知與確認框，改為原因必填）、baseline O4 評估逾期、baseline O7 付款前保母取消（第 1 輪衝突 #19、CQ-13）、`PAYMENT_ALREADY_SUBMITTED`（第 2 輪 #3） | PRD-006 子模組超過 3 個；`docs/reviews/00-DOMAIN-BASELINE.prd.review.md` 第 1、2 輪 |
