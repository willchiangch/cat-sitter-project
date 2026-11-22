# TS-023: 訂單變更：飼主回應、補款、退款與結清

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-023（Approved 2026-11-22）；旅程中順帶使用 PRD-016（發起與報價，只作前置）、PRD-007（憑證上傳 / 確認 / 駁回）、PRD-008（行程開始）、PRD-009（結案 E25、帳務總覽 E30，只作後續觀察） |
| 對應 SD | SD-ORDER（§3.2、§3.3、§4.3、§4.4、§6.3 E10–E15、E17–E23、E25、E30、§6.5、§7.2、§8.1 C2 / C5 / C6、§9.1、§10.4）；SD-FULFILL（§6.3 F1、F3、FT1、FT2） |
| 類型 | 旅程（打真後端）：同意三分支、拒絕、補款、補款逾時、退款、結清後主狀態、跨角色併發、冪等 |
| 自動化路徑 | `frontend/e2e/journeys/order-modification-settlement.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 自己建資料、可獨立重跑。帳號一律 `@e2e-journey.test`，由 `POST /api/internal/test-data/provision-account` 建立；測試資料端點帶 `X-Internal-Secret`（取自 CI 環境變數）。
> `D` = 執行當下 Asia/Taipei 日期，`D+n` 由 spec 執行時計算。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `provision-account`；`owner-setup`：`pets` = M1、M2（CAT） | — |
| `ownerB` | OWNER | `provision-account`；`owner-setup` 一隻毛孩 | 第三人 |
| `sitter` | SITTER | `provision-account`；`sitter-setup`：`tier=PRO`（建單用）、`subscriptionExpiresAt = now + 30 天`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄、`plans` = P（單價 500、CAT、無區間）、`questions=[]`；建單後依情境改等級（預設 BASIC） | — |
| `admin` | ADMIN | `provision-account`（ADMIN 角色；見 TS-016 §5 待補 1） | — |
| `order` | — | `owner` E1 預約 P：`dates=[D+3, D+4, D+5]`、`dailyCount=1`、`petIds=[M1, M2]`、`expectedAmount=1500` → `sitter` E6 原價 → `owner` E11 首次付款憑證（`lastFive=12345`）→ `sitter` E14 確認；F1 取 V1、V2、V3 | `CONFIRMED`；`total_amount=1500`；`netPaid=1500` |
| `orderIP` | — | `order` + FT1 V1 `DONE`（`careLogSubmitted=true`） | `IN_PROGRESS` |
| `order1700` | — | 同 `order`，但 E6 帶 `surchargeAmount=200`、`pricingNotes=加價`（`sitter` PRO），首次付款 1700 | `CONFIRMED`；`total_amount=1700`；`netPaid=1700` |
| `order500` | — | 同 `order`，但 E6 帶 `discountAmount=1000`、`pricingNotes=折扣`，首次付款 500 | `CONFIRMED`；`total_amount=500`；`netPaid=500` |

前置動作的縮寫（皆走真 API，各帶新 `Idempotency-Key`）：

| 縮寫 | 內容 |
|------|------|
| `REQ(x)` | owner E17 `CHANGE`，內容 x，原因「測試」→ `REQUESTED` |
| `QUOTE(adj)` | sitter E20，內容維持預填，加減 `adj`（未寫 = 不帶）→ `QUOTED` |
| `PROPOSE(x, adj)` | sitter E17 `CHANGE` 內容 x，原因「測試」→ `QUOTED`（`initiatedBy=SITTER`） |
| `ACCEPT` | owner E23 |
| `UPLOAD(pay, last5)` | 付款方 E11：`disclaimerAgreed=true`、`lastFive=last5`、PNG 檔 |
| `VERIFY(pay)` | 收款方 E14：`submissionId` = E10 該 payment `currentSubmissionId` |
| `EXPIRE_SUP(pay)` | `POST /api/internal/test-data/orders/{order}/shift-time`（`target=PAYMENT_DUE_AT`、`paymentId`、`minutes=-(72*60+1)`）→ C6 `POST /api/internal/cron/modifications/supplement-expiry` |

- 斷言來源：變更 E19；付款 E10（`purpose` / `direction` / `amount` / `status` / `dueAt` / `accountSnapshot`）、E12（submission）；行程 F1；目前總額 E4 `content[].totalAmount`（見 TS-016 §5 待補 4）；已收淨額 E2 `netPaid`；通知 `GET /api/notifications`（SD-014），篩 SD-ORDER §7.2 的 `type`。
- 時間斷言以送出前 t0、回應後 t1 夾值（例 `t0+72h ≤ dueAt ≤ t1+72h`）；固定時鐘精確值交後端（§4）。

清除：`cleanup-e2e-journeys`（含 `initiated_by=SYSTEM` 變更、`SUPPLEMENT` / `MODIFICATION_REFUND` payment 與其 submission、GCS 憑證物件）。

## 2. 測試步驟

> 前綴：`SA` = 子模組 1 同意與拒絕；`SS` = 子模組 2 補款；`SR` = 子模組 3 退款與結清；`SE` = §3 權限與例外。

### 2.1 子模組 1：飼主同意與拒絕

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| SA-01 | AC-1.1 | owner | `orderIP`；`REQ(減少 V3、新增 D+7 一次)` → `QUOTE()`；飼主開 `/orders/{order}/modifications/{mod}` | 顯示新內容（減少 V3、新增 D+7）、`modification-detail-diff`「無差額」、`modification-detail-expires-at`；有 `modification-btn-accept`、`modification-btn-reject`、`modification-btn-withdraw` | E19 `newTotalAmount=1500`、`diff=0` | 截圖 + API JSON |
| SA-02 | AC-1.1 | owner | 點 `modification-btn-accept` | 動作按鈕消失，顯示唯讀「已生效」結果 | 頁面發出的 E23 恰一次，200：`status=SETTLED`、`payment` 不輸出、`orderStatus=IN_PROGRESS`、`totalAmount=1500`；E19 `endedAt` 在 `[t0, t1]`；F1 V3 `CANCELLED`、新增一個 D+7 / P `PENDING` 行程；E4 `totalAmount=1500`；E10 只有 `INITIAL`；雙方各 1 筆 `type=MODIFICATION_SETTLED`「變更已生效」 | 截圖 + API JSON |
| SA-03 | AC-1.2 | owner | `order`（BASIC）；記下 E10 `INITIAL` 與 E12 首次 submission 為快照 S0；`REQ(減少 V3、新增 D+7 兩次)` → `QUOTE()`（2000）；飼主協商頁點 `modification-btn-accept` | 協商頁出現補款區塊 | E23 200：`status=AWAITING_SUPPLEMENT`、`payment={direction: CHARGE, purpose: SUPPLEMENT, amount: 500}`、`dueAt` 在 `[t0+72h, t1+72h]`、`orderStatus=CONFIRMED`；F1 V3 `CANCELLED`、無 D+7 行程；E4 `totalAmount=1500`；E10 `SUPPLEMENT` `UNPAID`、`accountSnapshot` 四欄齊全 | 截圖 + API JSON |
| SA-04 | AC-1.2 | owner | 檢視補款區塊；查通知；呼叫 C2 `POST /api/internal/cron/payments/reminder` 後再查通知 | `payment-amount`「NT$500」（本次應補差額）；`payment-due-at` 顯示「請於 {dueAt 的 Asia/Taipei YYYY-MM-DD HH:mm} 前完成」；`payment-account-snapshot` 可見；頁面沒有以 NT$2,000 作為應付金額的元素、沒有 `order-total-amount` | owner 1 筆 `type=SUPPLEMENT_REQUIRED`，內容含「NT$500」與同一期限字串；sitter 1 筆 `MODIFICATION_ACCEPTED_SUPPLEMENT`；C2 後 owner 沒有本單新的 `PAYMENT_REMINDER` | 截圖 + API JSON |
| SA-05 | AC-1.2 | owner | 另一張 `order`（`sitter` PRO）：`REQ(減少 V3、新增 D+7 兩次)` → `QUOTE(surchargeAmount=200、adjustmentReason=加價)`（2200）→ `sitter-setup` 改 `subscriptionExpiresAt = now − 2 天`（鎖定）→ `ACCEPT` | — | 200、`AWAITING_SUPPLEMENT`、`payment.amount=700`；不回 403 `PLAN_REQUIRED` / `SUBSCRIPTION_LOCKED` | API JSON |
| SA-06 | AC-1.3 | owner、sitter | `order`；`PROPOSE(減少 V3)`（1000）；飼主協商頁點 `modification-btn-accept` | 飼主頁 `modification-detail-diff`「應退款 NT$500」，沒有 `btn-verify-payment`、沒有補款區塊；保母頁退款區塊顯示「退款帳號請與飼主自行聯繫」、不顯示 `payment-account-snapshot` | E23 200：`status=AWAITING_REFUND`、`payment={direction: REFUND, purpose: MODIFICATION_REFUND, amount: 500}`、`dueAt` 不輸出；F1 V3 `CANCELLED`；E19 `netPaid=1500`、`diff=-500`；E10 退款 `UNPAID`、`accountSnapshot` 不輸出；sitter 1 筆 `type=REFUND_REQUIRED`「請退還 NT$500 並上傳退款憑證」 | 截圖 + API JSON |
| SA-07 | AC-1.4 | owner | `order`；`REQ(減少 V2、V3)` → `QUOTE()`（500）→ FT1 V2 `IN_PROGRESS` → 飼主協商頁點 `modification-btn-accept` | 顯示「部分行程已開始，請重新協商」 | E23 409 `VISIT_ALREADY_STARTED`，`payload.visitIds=[V2]`；F1 V2 `IN_PROGRESS`、V3 `PENDING`；E10 無新 payment；E19 仍 `QUOTED` | 截圖 + API JSON |
| SA-08 | AC-1.4 | owner | 另一張 `order`；同上但 FT1 V2 `CLOSED_BY_SYSTEM`；`ACCEPT` | — | 409 `VISIT_ALREADY_STARTED`（`visitIds=[V2]`）；資料不變 | API JSON |
| SA-09 | AC-1.5 | owner、sitter | `order`；FT2 `POST /api/internal/test-data/visits/{V1}/shift-time`（`target=SERVICE_DATE`、`days=-3`，整群組平移，V1 = D）；`REQ(減少 V1)` → `QUOTE()`；以 `Promise.all` 同時送 sitter F3 `POST /api/orders/{order}/visits/{V1}/start` 與 owner E23 | — | 恰一個成功。同意先：E23 200、F1 V1 `CANCELLED`、F3 409 `VISIT_STATE_CONFLICT`、E2 `status=CONFIRMED`（不觸發 O9）。開始先：F3 200、V1 `IN_PROGRESS`、E2 `status=IN_PROGRESS`、E23 409 `VISIT_ALREADY_STARTED`、E19 仍 `QUOTED` | API JSON |
| SA-10 | AC-1.5 | owner、sitter | 另一張 `order`；`PROPOSE(新增 D+7 一次)`（2000、差額 +500）；`Promise.all` 同時送 owner E23 與 sitter E22 | — | 恰一個 200。同意先：`AWAITING_SUPPLEMENT`、撤回 409 `MODIFICATION_STATE_CONFLICT`。撤回先：`WITHDRAWN`、同意 409 `MODIFICATION_STATE_CONFLICT`、E10 無 `SUPPLEMENT`、F1 無 `CANCELLED` 行程 | API JSON |
| SA-11 | AC-1.6 | owner | `order`；`REQ(減少 V3)` → `QUOTE()`；飼主協商頁點 `modification-btn-reject` | 顯示唯讀「已拒絕」與結束時間 | E21 200：`status=REJECTED`、`endedAt` 在 `[t0, t1]`；F1、E4 `totalAmount=1500`、E10、E2 `status=CONFIRMED` 皆不變；sitter 1 筆 `type=MODIFICATION_REJECTED_BY_OWNER`「飼主已拒絕變更」 | 截圖 + API JSON |
| SA-12 | AC-1.6 | owner | 同單 `PROPOSE(減少 V3)` → owner E21（不帶 reason） | — | 200、`REJECTED`；資料不變 | API JSON |
| SA-13 | AC-1.7 | owner、ownerB、sitter | `order`；`REQ(減少 V3)`（`REQUESTED`）→ owner E23；`QUOTE()` → `ownerB` E23；sitter（SITTER token）E23 | — | owner 對 `REQUESTED` → 409 `MODIFICATION_STATE_CONFLICT`；ownerB → 403 `FORBIDDEN`；sitter → 403 `ROLE_MISMATCH`；E19 仍 `QUOTED` | API JSON |
| SA-14 | AC-1.7 | owner | 同單 shift-time `MODIFICATION_EXPIRES_AT`（`minutes=-(72*60+1)`），讀 E19 `expiresAt` 為 e1；owner E23；owner E21 | 協商頁無按鈕、顯示「此變更請求已逾期」 | 皆 409 `DEADLINE_PASSED`，`deadline=MODIFICATION_EXPIRES_AT`、`deadlineAt=e1`；E19 仍 `QUOTED`、F1、E10 不變 | 截圖 + API JSON |
| SA-15 | AC-1.8 | owner | `order`；`REQ(新增 D+7 一次)` → `QUOTE()`（差額 +500）；owner E23 帶 `K2`；同 key 重送；換新 key 再送 | — | 200（補款 P1）；回放：狀態碼與 body 相同、E10 `SUPPLEMENT` 仍只有 P1；新 key → 409 `MODIFICATION_STATE_CONFLICT`、E10 `SUPPLEMENT` 仍 1 筆 | API JSON |

### 2.2 子模組 2：補款

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| SS-01 | AC-2.1 | owner | 接 SA-03 的 `AWAITING_SUPPLEMENT`；補款區塊勾 `proof-disclaimer-checkbox`、`proof-last5-input` 填 `54321`、`proof-file-input` 選 PNG、點 `proof-submit-btn` | `submission-history` 出現後五碼 54321 一筆 | E11 201：`paymentStatus=SUBMITTED`；E10 `SUPPLEMENT` `SUBMITTED` | 截圖 + API JSON |
| SS-02 | AC-2.1 | sitter | 保母開 `/orders/{order}/modifications/{mod}` 補款區塊，點 `btn-verify-payment` | 核對區 `payment-amount`「NT$500」、`submission-history` 後五碼 54321；確認後顯示「變更已生效」唯讀結果 | E14 200：`paymentStatus=VERIFIED`、`modification.status=SETTLED`、`orderStatus=CONFIRMED`；E19 `endedAt` 在 `[t0, t1]`；F1 新增兩個 D+7 / P `PENDING` 行程；E4 `totalAmount=2000`；E2 `netPaid=2000`；雙方各 1 筆 `type=SUPPLEMENT_VERIFIED`「補款已確認，變更已生效」，本單無新的 `ORDER_ACTIVATED`（「訂單啟動成功」） | 截圖 + API JSON |
| SS-03 | AC-2.2 | — | 比對 S0 | — | E10 `INITIAL`：`status=VERIFIED`、`currentSubmissionId`、`amount=1500` 與 S0 相同；E12（`INITIAL`）submission 列數、`lastFive=12345`、`submittedAt`、`status` 與 S0 相同；E12（`SUPPLEMENT`）有自己的一筆 `lastFive=54321`、`status=ACCEPTED`（確認時間欄位見 §5 待補 2） | API JSON |
| SS-04 | AC-2.4 | — | `order1700`（`sitter` PRO，此前無變更）；`REQ(減少 V3、新增 D+7 兩次)` → `QUOTE(surchargeAmount=200、adjustmentReason=加價)`（2200）→ `ACCEPT`（V3 `CANCELLED`、補款 500）→ `EXPIRE_SUP(補款)` | — | C6 200；E10 補款 `VOID`；原變更 E19 `EXPIRED`、`endedAt ≥` C6 呼叫時間；F1 無 D+7 行程、V3 仍 `CANCELLED`；E4 `totalAmount=1200` | API JSON |
| SS-05 | AC-2.4 | — | E18 取 `initiatedBy=SYSTEM` 新列，查 E19 與通知 | 飼主 / 保母協商頁顯示此系統退款變更 | E19：`type=CHANGE`、`status=AWAITING_REFUND`、`reason=補款逾期自動退款`、`quotedContent` 無減少 / 新增、`systemAmount=1200`、`newTotalAmount=1200`、`surchargeAmount=0`、`discountAmount=0`、`adjustmentReason` 不輸出、`netPaid=1700`、`diff=-500`、`expiresAt` 不輸出、`sourceModificationId` = 原變更、`payment={direction: REFUND, purpose: MODIFICATION_REFUND, amount: 500, status: UNPAID}`；雙方各 1 筆 `SUPPLEMENT_EXPIRED`「補款逾期，新增行程未生效」；sitter 1 筆 `SYSTEM_REFUND_REQUIRED`「請退還 NT$500」 | 截圖 + API JSON |
| SS-06 | AC-2.5 | owner | `order`；`REQ(新增 D+7 一次)` → `QUOTE()`（2000）→ `ACCEPT` → `EXPIRE_SUP`；再 owner E17（新增 D+8） | — | 補款 `VOID`、變更 `EXPIRED`；E4 `totalAmount=1500`；E18 無 `initiatedBy=SYSTEM` 列；之後 E17 201 | API JSON |
| SS-07 | AC-2.6 | — | `order500`；`REQ(減少 V1、V2、V3，新增 D+7 兩次)` → `QUOTE()`（1000、差額 +500）→ `ACCEPT`（V1~V3 `CANCELLED`）→ `EXPIRE_SUP` | — | E4 `totalAmount=0`；SYSTEM 變更 `newTotalAmount=0`、`netPaid=500`、退款 payment `amount=500`；E2 `status=CONFIRMED`（有系統退款時不檢查 O10） | API JSON |
| SS-08 | AC-2.7 | owner、sitter | `order`；`REQ(新增 D+7 一次)` → `QUOTE()` → `ACCEPT` → `UPLOAD(補款, 54321)`（`SUBMITTED`）→ shift-time `PAYMENT_DUE_AT` 過期 → C6 | — | 補款仍 `SUBMITTED`；E19 仍 `AWAITING_SUPPLEMENT` | API JSON |
| SS-09 | AC-2.7 | sitter | 接 SS-08，`VERIFY(補款)` | — | 200：`VERIFIED`、變更 `SETTLED` | API JSON |
| SS-10 | AC-2.7 | sitter、owner | 另一張 `order` 同 SS-08 至 `SUBMITTED` 且 `due_at` 已過；讀 E10 `dueAt` 為 d1；sitter E15 駁回（`submissionId`、`reason=金額不符`）；owner 補款區塊重新上傳（UI）；再呼叫 C6 | 重新上傳後顯示「補款期限已過」 | E15 200、`paymentStatus=UNPAID`；E11 409 `DEADLINE_PASSED`，`deadline=PAYMENT_DUE_AT`、`deadlineAt=d1`；C6 後補款 `VOID`、變更 `EXPIRED` | 截圖 + API JSON |
| SS-11 | AC-2.8 | — | `order`；變更 A：`PROPOSE(減少 V2)`（1000）→ `ACCEPT` → `UPLOAD(退款, 11111)`（sitter）→ `VERIFY(退款)`（owner）；變更 B：`REQ(減少 V3、新增 D+7 兩次)` → `QUOTE()`（1500、差額 +500）→ `ACCEPT` → `EXPIRE_SUP` | — | A 後：A `SETTLED`、E4 `totalAmount=1000`、`netPaid=1000`。B 逾時後：E4 `totalAmount=500`；SYSTEM 變更 `newTotalAmount=500`、退款 `amount=500`；F1 V1 `PENDING`；E2 `status=CONFIRMED` | API JSON |

### 2.3 子模組 3：退款與變更結束後的訂單

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| SR-01 | AC-3.1 | sitter | 接 SA-06 的 `AWAITING_REFUND`；保母協商頁退款區塊勾 `proof-disclaimer-checkbox`、`proof-last5-input` 填 `22222`、`proof-file-input`、點 `proof-submit-btn` | `submission-history` 出現一筆 | E11 201、`SUBMITTED` | 截圖 + API JSON |
| SR-02 | AC-3.1 | owner | 飼主協商頁點 `btn-verify-payment`（確認收到退款） | 上傳前不存在、上傳後才出現此按鈕；確認後顯示「變更已完成」唯讀結果 | E14 200：`VERIFIED`、`modification.status=SETTLED`、`orderStatus=CONFIRMED`；E19 `endedAt` 在 `[t0, t1]`；E4 `totalAmount=1000`；E2 `netPaid=1000`；F1 V1、V2 `PENDING`；雙方各 1 筆 `type=REFUND_VERIFIED`「退款已確認，變更已完成」 | 截圖 + API JSON |
| SR-03 | AC-3.2 | owner | 另一張 `order`：`PROPOSE(減少 V3)` → `ACCEPT`（退款 `UNPAID`）；飼主開協商頁；owner E14（`submissionId` = 任一有效格式 UUID）；呼叫 C5、C6 | 飼主頁沒有 `btn-verify-payment` | E14 409 `PAYMENT_STATE_CONFLICT`；C5 / C6 後 E19 仍 `AWAITING_REFUND`、退款仍 `UNPAID`（30 天不逾時見 §4） | 截圖 + API JSON |
| SR-04 | AC-3.3 | owner | `order`（`sitter` PRO）；owner E17 `CANCEL_ALL` → `QUOTE(surchargeAmount=300、adjustmentReason=取消費)`（300、差額 −1200）→ `ACCEPT`（V1~V3 `CANCELLED`、退款 1200）→ `UPLOAD(退款, 33333)`（sitter）→ 飼主協商頁點 `btn-verify-payment` | 訂單詳情 `/owner/orders/{order}` 顯示已取消 | E14 200：`modification.status=SETTLED`、`orderStatus=CANCELLED`；E2 `cancellation.cancelledBy=SYSTEM`、`cancellation.reasonCode=MODIFICATION`；E10 無 `UNPAID` / `SUBMITTED`；雙方各 1 筆 `ORDER_CANCELLED_BY_MODIFICATION`「訂單已取消」；E30 `GET /api/orders/sitter/ledger?month={當月}` 該單 `netPaid=300`（`order_logs` payload 見 §4） | 截圖 + API JSON |
| SR-05 | AC-3.4 | owner | `order`（PRO）；owner E17 `CANCEL_ALL` → `QUOTE(surchargeAmount=1500、adjustmentReason=不退款)`（1500、差額 0）→ 飼主點 `modification-btn-accept` | — | E23 200：`status=SETTLED`、`payment` 不輸出、`orderStatus=CANCELLED`；F1 V1~V3 `CANCELLED`；E2 `cancellation.cancelledBy=SYSTEM`；E10 只有 `INITIAL`；E30 該單 `netPaid=1500` | 截圖 + API JSON |
| SR-06 | AC-3.5 | owner | `order`；FT1 V1 `DONE`（`careLogSubmitted=true`）、V2 `CLOSED_BY_SYSTEM`；owner E17 `CANCEL_ALL` → `QUOTE()`（1000、差額 −500）→ `ACCEPT` → `UPLOAD(退款)` → `VERIFY(退款)`；之後 owner E25 `POST /api/orders/{order}/complete` | — | 結清後：變更 `SETTLED`、F1 V3 `CANCELLED`、E2 `status=IN_PROGRESS`（未轉 `CANCELLED`）；E25 200 `COMPLETED`；E30 該單 `netPaid=1000` | API JSON |
| SR-07 | AC-3.6 | owner、sitter | 接 SS-07 的 SYSTEM 退款變更；owner E22、sitter E22 | 協商頁顯示「此退款由系統建立，無法撤回」、無撤回按鈕 | 皆 403 `FORBIDDEN`；仍 `AWAITING_REFUND` | 截圖 + API JSON |
| SR-08 | AC-3.6 | sitter、owner | `UPLOAD(退款, 44444)`（sitter）→ `VERIFY(退款)`（owner） | — | 變更 `SETTLED`；E4 `totalAmount=0`；E2 `status=CANCELLED`、`cancellation.cancelledBy=SYSTEM`、`reasonCode=MODIFICATION`、`netPaid=0`；E30 `orders[]` 不含此單（淨額 0 的 `CANCELLED` 不列，SD-ORDER E30） | API JSON |

## 3. 權限與例外

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| SE-01 | EX-1 | `QUOTED` 變更；`ownerB` 呼叫 E23、E21 | 皆 403 `FORBIDDEN`；E19 仍 `QUOTED` |
| SE-02 | EX-2 | sitter（SITTER token）呼叫 E23；`admin` 呼叫 E23、E21 | 皆 403 `ROLE_MISMATCH` |
| SE-03 | EX-3 | 不適用（同意 / 拒絕 / 補款 / 退款不分方案）；同意不重驗方案見 SA-05 | — |
| SE-04 | EX-4 | owner 對 `REQUESTED`（SA-13）、`AWAITING_SUPPLEMENT`（SA-03 後換 key 再 E23）、`REJECTED`（SA-11 後 E23）呼叫 E23 | 皆 409 `MODIFICATION_STATE_CONFLICT`（`currentStatus` 為當下狀態） |
| SE-05 | EX-5 | 見 SA-14 | 409 `DEADLINE_PASSED` |
| SE-06 | EX-6 | 見 SA-07、SA-08 | 409 `VISIT_ALREADY_STARTED`，不取消行程、不建 payment |
| SE-07 | EX-7 | SA-06 的 `AWAITING_REFUND` 上 owner E24 申報爭議（`DISPUTED`）；owner E23、owner E21 該變更；sitter `UPLOAD(退款)`；owner E14 | 皆 409 `ORDER_STATE_CONFLICT`（`currentStatus=DISPUTED`） |
| SE-08 | EX-8 | 見 SA-09、SA-10 | 後到者 409 |
| SE-09 | EX-11、EX-12 | 見 SA-15 | 回放不重複建 payment；換 key 409 |
| SE-10 | EX-13 | 見 SS-10 | 409 `DEADLINE_PASSED`（`PAYMENT_DUE_AT`） |
| SE-11 | EX-14 | 見 SR-03 | 409 `PAYMENT_STATE_CONFLICT` |
| SE-12 | EX-15 | 見 SR-07 | 403 `FORBIDDEN` |
| SE-13 | BR-21 | `REQ(減少 V3)` → `QUOTE()` → FT1 V3 `IN_PROGRESS` → shift-time `MODIFICATION_EXPIRES_AT` 過期 → owner E23 | 409 `DEADLINE_PASSED`（先於 `VISIT_ALREADY_STARTED`） |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.2（`due_at=2026-11-24T02:00:00Z` 精確值）、AC-1.6（結束時間 `2026-11-22T01:00:00Z`） | 需固定時鐘；E2E 以夾值斷言 | `ModificationSettlementIntegrationTest`、`ModificationLifecycleIntegrationTest`（注入 `Clock`） |
| AC-1.5（指定先後的兩種結果都要驗） | `Promise.all` 只能驗「恰一方成功且結果一致」，無法保證兩種先後都出現 | `ModificationConcurrencyIntegrationTest`（`CountDownLatch` 控制先後） |
| AC-2.1（保母當月接單計數不變） | 計數無直接讀取端點 | `ModificationSettlementIntegrationTest` |
| AC-2.2（首次付款「確認時間」不變） | E10 / E12 未輸出確認時間欄位 | `ModificationSettlementIntegrationTest`（見 §5 待補 2） |
| AC-2.3（報價後跨日、新增日期已過仍可同意且仍建立行程） | 測試端點無法平移變更內容中的新增日期；正式站無法等待跨日 | `ModificationSettlementIntegrationTest`（注入 `Clock` 跨日） |
| AC-2.4（排程於 `due_at` 後 ≤ 15 分鐘內執行） | 頻率屬 Cloud Scheduler 設定 | 手動驗證 Cloud Scheduler job `modifications/supplement-expiry` 每 15 分鐘；`SupplementExpiryIntegrationTest` |
| AC-3.2（退款 `UNPAID` / `SUBMITTED` 超過 30 天仍不逾時） | 退款無 `due_at`，無可平移的時間欄位 | `ModificationSettlementIntegrationTest`（注入 `Clock` +30 天後跑全部排程） |
| AC-3.3（O10 `order_logs` payload 含 `modificationId`、`initiatedBy`） | 訂單 `CANCELLED` 且無 OPEN 待辦，E27 不可讀；當事人無 log 端點 | `ModificationSettlementIntegrationTest`（`ORDER_CANCELLED_BY_MODIFICATION` 列） |
| EX-9（C6 補款逾時 vs SITTER 確認補款同時） | 無法在正式站控制排程與請求交錯；E2E 只驗 `SUBMITTED` 不被逾時（SS-08） | `SupplementExpiryIntegrationTest`（SD-ORDER §10.3 未列此競爭，見 §5 待補 3） |
| EX-10（鎖等待逾時 `ORDER_BUSY`） | 正式站無法持鎖 3 秒 | `ModificationConcurrencyIntegrationTest`、`OrderLockServiceTest` |
| EX-16（GCS 上傳失敗） | 正式站無法注入 GCS 故障 | `PaymentSubmissionConcurrencyIntegrationTest`、TS-007 |
| EX-17（Resend 寄信失敗） | Resend 測試模式只寄專案擁有者信箱，無法注入失敗 | `ModificationSettlementIntegrationTest`（Resend mock）；真實寄信手動驗證 |
| EX-18（補款逾時單筆失敗整筆回滾） | 正式站無法注入單筆例外 | `SupplementExpiryIntegrationTest`（單筆失敗整筆回滾） |

## 5. 已知限制 / 待補

1. 帳號、角色切換、testid、目前總額讀取來源、`sitter-setup` 重複呼叫等共通限制同 TS-016 §5（待補 1、3、4、5）。
2. **確認時間未輸出**：E10 / E12 沒有 payment 或 submission 的確認時間欄位，AC-2.2「首次付款確認時間不變」只能以 `status`、`currentSubmissionId`、`submittedAt` 間接斷言。
3. **後端測試缺口**：SD-ORDER §10.3 `SupplementExpiryIntegrationTest` 未列 PRD-023 EX-9（C6 vs E14 競爭）。
4. **SA-09 依賴 FT2 `SERVICE_DATE` 整群組平移**：把 V1~V3 一起往前 3 天使 V1 = D，才能用正式路徑 F3 開始行程；若 FT2 不允許 `CONFIRMED` 訂單平移，改由後端 `ModificationConcurrencyIntegrationTest` 單獨覆蓋。
5. **正式站排程**：直接呼叫 C2 / C5 / C6 會處理其他到期資料，斷言只看本測試資料。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 建立：依 PRD-023 與 SD-ORDER 撰寫同意三分支、拒絕、補款、補款逾時（含系統退款）、退款、O10 與 `IN_PROGRESS` 結清 | PRD-023、SD-ORDER Approved 2026-11-22 |
