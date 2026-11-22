# TS-024: 訂單變更：款項爭議與協商期間的訂單限制

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-024（Approved 2026-11-22）；旅程中順帶使用 PRD-016 / PRD-023（變更、補款、退款，只作前置）、PRD-007（憑證）、PRD-008（行程開始）、PRD-009（O11 / O12 / O13 與 `DISPUTED` 畫面列 §4.4）、PRD-005（公開頁預約被鎖定擋下）；ADMIN 調解行為依 PRD-024 §4.5 與 SD-ORDER E29（PRD-020 待改寫） |
| 對應 SD | SD-ORDER（§3.5、§6.3 E2、E10–E15、E17–E29、§6.5、§7.1、§7.2、§8.1 C4 / C6 / C8、§9.1、§10.4）；SD-FULFILL（§6.3 F3、FT1、FT2） |
| 類型 | 旅程（打真後端）：款項爭議申報、`DISPUTED` 凍結、ADMIN 調解結果、協商期間結案限制、結案基準、訂閱鎖定不擋 |
| 自動化路徑 | `frontend/e2e/journeys/order-modification-dispute.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 自己建資料、可獨立重跑。帳號一律 `@e2e-journey.test`，由 `POST /api/internal/test-data/provision-account` 建立；測試資料端點帶 `X-Internal-Secret`（取自 CI 環境變數）。`D` = 執行當下 Asia/Taipei 日期。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `provision-account`；`owner-setup`：`pets` = M1、M2（CAT） | — |
| `ownerB` | OWNER | `provision-account`；`owner-setup` 一隻毛孩 | 第三人；AC-2.8 另用來送新預約 |
| `sitter` | SITTER | `provision-account`；`sitter-setup`：`tier=PRO`、`subscriptionExpiresAt = now + 30 天`、`suspended=false`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄、`plans` = P（單價 500、CAT、無區間）、`questions=[]`；建單後依情境改等級（預設 BASIC） | — |
| `admin` | ADMIN | `provision-account`（ADMIN 角色；見 TS-016 §5 待補 1） | — |
| `order` | — | `owner` E1 預約 P：`dates=[D+3, D+4, D+5]`、`dailyCount=1`、`petIds=[M1, M2]`、`expectedAmount=1500` → `sitter` E6 原價 → `owner` E11 首次付款（`lastFive=12345`）→ `sitter` E14；F1 取 V1、V2、V3 | `CONFIRMED`；`total_amount=1500`；`netPaid=1500` |

前置動作縮寫（同 TS-023 §1）：`REQ(x)`、`QUOTE(adj)`、`PROPOSE(x, adj)`、`ACCEPT`、`UPLOAD(pay, last5)`、`VERIFY(pay)`、`EXPIRE_SUP(pay)`。另：

| 縮寫 | 內容 |
|------|------|
| `REFUND_WAIT` | `PROPOSE(減少 V3)`（1000、差額 −500）→ `ACCEPT` → 變更 `AWAITING_REFUND`、退款 payment R（500）`UNPAID` |
| `SUP_SUBMITTED` | `REQ(新增 D+7 一次)` → `QUOTE()`（2000、差額 +500）→ `ACCEPT` → `UPLOAD(補款 S, 54321)` → 補款 S `SUBMITTED` |
| `SHIFT_SUB(sub, m)` | `POST /api/internal/test-data/orders/{order}/shift-time`：`target=SUBMISSION_AT`、`submissionId=sub`、`minutes=m` |
| `DISPUTE(role, text)` | 該角色 E24 `POST /api/orders/{order}/payment-disputes`，`description=text` |

- 斷言來源：訂單狀態 E2；待辦 E28 `GET /api/admin/tasks?type=MODIFICATION_PAYMENT_DISPUTE&status=OPEN`（以 `orderId` 篩）與 E27 `adminTasks[]`；`order_logs` 在訂單進入 `DISPUTED` 後可由 ADMIN 經 E27 `logs[]` 讀取；結案基準以 E2 `disputeWindowEndsAt`（= `completion_basis_at + 48h`）間接斷言；通知 `GET /api/notifications`（SD-014）。

清除：`cleanup-e2e-journeys`（含 `admin_tasks`、`ADMIN_ADJUSTMENT` payment 的 `source_payment_id` 先刪順序，SD-ORDER §10.4）。

## 2. 測試步驟

> 前綴：`PD` = 子模組 1 款項爭議申報；`PF` = 子模組 2 爭議凍結、調解結果與協商期間限制；`PE` = §3 權限與例外。

### 2.1 子模組 1：款項爭議申報

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| PD-01 | AC-1.1 | owner | `order`（BASIC）+ FT1 V1 `DONE`（`IN_PROGRESS`）；`REFUND_WAIT`；飼主開 `/orders/{order}/modifications/{mod}`，點 `modification-btn-payment-dispute` | 出現 `payment-dispute-input-description` 與 `payment-dispute-btn-submit`；對話框內無檔案上傳元件（`input[type=file]` 數量 0） | E19 `allowedActions` 含 `PAYMENT_DISPUTE` | 截圖 + API JSON |
| PD-02 | AC-1.1 | owner | 填「保母遲未退款」，點 `payment-dispute-btn-submit` | 訂單詳情 `/owner/orders/{order}` 顯示 `owner-order-detail-disputed-banner`「訂單爭議處理中，請等候平台處理」；不顯示申請變更、付款動作（PRD-009 §4.4） | 頁面發出的 E24 恰一次，200：`status=DISPUTED`、`adminTaskId` 有值；E2 `status=DISPUTED`；E19 仍 `AWAITING_REFUND`；E10 R 仍 `UNPAID` | 截圖 + API JSON |
| PD-03 | AC-1.1 | admin、sitter | admin 查 E28、E27；admin 與 sitter 各查 `GET /api/notifications` | — | E28 恰 1 筆 `type=MODIFICATION_PAYMENT_DISPUTE`、`orderId`=本單、`paymentId`=R、`taskId`=`adminTaskId`、`status=OPEN`；E27 `logs[]` 有 1 筆 `PAYMENT_DISPUTE_OPENED`，`payload.description=保母遲未退款`、`payload.paymentId`=R；admin 無本單任何通知；sitter 1 筆 `type=PAYMENT_DISPUTE_OPENED`「訂單已進入爭議處理」 | API JSON |
| PD-04 | AC-1.2 | sitter | 另一張 `order`（`CONFIRMED`）：owner E17 `CANCEL_ALL` → `QUOTE()`（0、差額 −1500）→ `ACCEPT`（V1~V3 `CANCELLED`）→ `UPLOAD(退款, 11111)`（sitter，`SUBMITTED`）；保母協商頁點 `modification-btn-payment-dispute`，填「飼主遲未確認」送出 | 保母訂單詳情顯示 `sitter-order-detail-disputed-banner` | E24 200；E2 `CONFIRMED → DISPUTED`；E28 該單 1 筆 `OPEN`；owner 1 筆 `PAYMENT_DISPUTE_OPENED` | 截圖 + API JSON |
| PD-05 | AC-1.2 | owner | 另一張 `order`：`REQ(減少 V3、新增 D+7 兩次)` → `QUOTE()` → `ACCEPT` → `EXPIRE_SUP`（產生 `initiatedBy=SYSTEM` 的 `AWAITING_REFUND`）；`DISPUTE(owner, 系統退款未收到)` | — | 200、E2 `DISPUTED`；E28 該單 1 筆 `OPEN`、`paymentId` = 系統退款 payment | API JSON |
| PD-06 | AC-1.3 | owner | 另一張 `order`：`SUP_SUBMITTED`；讀 E12 目前 submission `submittedAt` 為 s1；`SHIFT_SUB(該 submission, -(72*60-1))`；讀 E12 新的 `submittedAt` 為 s1'；飼主開協商頁；`DISPUTE(owner, 保母未核對)` | 顯示 `payment-dispute-available-at`，內容為 `s1' + 72h` 的 Asia/Taipei `YYYY-MM-DD HH:mm` | E24 409 `PAYMENT_STATE_CONFLICT`，`payload.disputeAvailableAt = s1' + 72h`；E2 仍 `CONFIRMED`；E28 該單無待辦 | 截圖 + API JSON |
| PD-07 | AC-1.3 | sitter | `SHIFT_SUB(同一 submission, -2)`；`DISPUTE(sitter, 憑證有問題)` | — | 200、E2 `DISPUTED` | API JSON |
| PD-08 | AC-1.3 | owner、sitter | 另一張 `order`：`SUP_SUBMITTED`（submission A）→ `SHIFT_SUB(A, -(72*60+10))` → sitter E15 駁回 A（`reason=看不清楚`）→ `UPLOAD(補款, 67890)`（submission B，`due_at` 未過）；讀 B 的 `submittedAt` 為 sB；`DISPUTE(owner, 測試)` | — | 409 `PAYMENT_STATE_CONFLICT`，`disputeAvailableAt = sB + 72h`（不是 A 的時間 + 72h） | API JSON |
| PD-09 | AC-1.3 | owner | `SHIFT_SUB(B, -(72*60+1))`；`DISPUTE(owner, 測試)` | — | 200、E2 `DISPUTED` | API JSON |
| PD-10 | AC-1.4 | owner、sitter | 各建一張 `order`：(a) 無變更；(b) `REQ(減少 V3)`（`REQUESTED`）；(c) 再 `QUOTE()`（`QUOTED`）；每張由 owner 與 sitter 各 E24 | 協商頁（b、c）無 `modification-btn-payment-dispute` | 皆 409 `MODIFICATION_STATE_CONFLICT`；E2 狀態不變；E28 無待辦 | 截圖 + API JSON |
| PD-11 | AC-1.4 | owner | 另一張 `order`：`REQ(新增 D+7 一次)` → `QUOTE()` → `ACCEPT`（補款 `UNPAID`）；`DISPUTE(owner, 測試)` | 協商頁無 `modification-btn-payment-dispute` | 409 `PAYMENT_STATE_CONFLICT`，payload 無 `disputeAvailableAt`；E28 無待辦 | 截圖 + API JSON |
| PD-12 | AC-1.4 | owner | PD-02 的 `DISPUTED` 單換新 key E24；另建 `orderDone`（FT1 V1~V3 `DONE`、`careLogSubmitted=true` → owner E25 `COMPLETED`）與 `orderCxl`（只 E1 → owner E8 撤回）各 E24 | — | 皆 409 `ORDER_STATE_CONFLICT`，`payload.currentStatus` = `DISPUTED` / `COMPLETED` / `CANCELLED`；E28 PD-02 單仍只有 1 筆 `OPEN`、其餘兩單無待辦 | API JSON |
| PD-13 | AC-1.6 | owner、sitter | 另一張 `order`：`REFUND_WAIT`；以 `Promise.all` 同時送 owner E24（key K_o）與 sitter E24（key K_s） | — | 恰一個 200；另一個 409 `ORDER_STATE_CONFLICT`；E28 該單恰 1 筆 `OPEN` | API JSON |
| PD-14 | AC-1.6 | 成功者 | 以成功者的同一 key、同一 body 重送 | — | 回放 200，body（含 `adminTaskId`）與第一次相同；E28 仍 1 筆 | API JSON |
| PD-15 | AC-1.7 | owner、sitter | 另一張 `order`：`REFUND_WAIT` → `UPLOAD(退款 R, 22222)`（sitter，`SUBMITTED`）；`Promise.all` 同時送 owner E14（R、`submissionId`）與 sitter E24 | — | 恰一個 200。確認先：E19 `SETTLED`、E24 409 `MODIFICATION_STATE_CONFLICT`、E2 非 `DISPUTED`。申報先：E2 `DISPUTED`、E14 409 `ORDER_STATE_CONFLICT`、E10 R 仍 `SUBMITTED` | API JSON |
| PD-16 | AC-1.8 | owner | 另一張 `order`：`REFUND_WAIT`；E24 依序：`description=""`、`"   "`、501 字（每次換 key） | 飼主 UI 留空送出時，說明欄下方顯示「請填寫爭議說明（500 字以內）」 | 皆 400 `VALIDATION_FAILED`；E2 仍 `CONFIRMED`；E28 無待辦 | 截圖 + API JSON |
| PD-17 | AC-1.8 | owner | E24 `description` = 500 字 | — | 200、E2 `DISPUTED` | API JSON |

### 2.2 子模組 2：爭議凍結、調解結果與協商期間限制

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| PF-01 | AC-2.1 | sitter、owner | 接 PD-02（`DISPUTED`、變更 `AWAITING_REFUND`、R `UNPAID`，保母為發起者）；記錄 E19、E10、E12 為快照；sitter `UPLOAD(R)`；owner E17（新增 D+8）；sitter E17（減少 V2）；sitter E22 撤回該變更 | 保母協商頁無上傳 / 撤回 / 申報按鈕，顯示「訂單爭議處理中，請等候平台處理」 | 皆 409 `ORDER_STATE_CONFLICT`（`currentStatus=DISPUTED`）；E17 不回 `MODIFICATION_IN_PROGRESS`；E19、E10、E12 與快照相同 | 截圖 + API JSON |
| PF-02 | AC-2.1 | owner | 接 PD-04（退款 `SUBMITTED` 後 `DISPUTED`，保母發起）；owner E14（`submissionId` = 目前那筆）；owner E22 | — | E14 → 409 `ORDER_STATE_CONFLICT`、R 仍 `SUBMITTED`；owner E22 → 403 `FORBIDDEN`（非發起者先於凍結） | API JSON |
| PF-03 | AC-2.2 | sitter | 接 PD-07 的 `DISPUTED` 單（V1~V3 `PENDING`）；FT2 `POST /api/internal/test-data/visits/{V1}/shift-time`（`target=SERVICE_DATE`、`days=-3`，使 V1 = D）；sitter F3 `POST /api/orders/{order}/visits/{V1}/start` | 保母訂單詳情行程照常可開始；橫幅 `sitter-order-detail-disputed-banner` 仍在 | F3 200：`status=IN_PROGRESS`、`orderStatus=DISPUTED`；E2 仍 `DISPUTED` | 截圖 + API JSON |
| PF-04 | AC-2.3 | admin | 另一張 `order`：`REFUND_WAIT` → `DISPUTE(owner, 測試)`；記下 E4 `totalAmount`（T0）與 E2 `netPaid=1500`；admin E29 `POST /api/admin/orders/{order}/mediation`（body `{}`，帶 `Idempotency-Key`） | — | 200：`status` 為 `COMPLETED` 或 `CANCELLED`（由行程判斷）、`adjustmentPaymentIds=[]`；E19 `REJECTED`、`endedAt` 在 `[t0, t1]`；E10 R `VOID`、無 `ADMIN_ADJUSTMENT`；E27 總額 = T0、`netPaid=1500`；E27 `adminTasks[]` 該待辦 `RESOLVED`（`resolution=ADMIN_MEDIATED`）；E28 `OPEN` 無此單 | API JSON |
| PF-05 | AC-2.4 | admin | 另一張 `order`：`REFUND_WAIT` → `UPLOAD(R, 33333)`（`SUBMITTED`）→ `DISPUTE(sitter, 測試)`；admin E29 不帶 `refundDecisions` | — | 400 `VALIDATION_FAILED`，`payload.paymentIds=[R]`；E19 仍 `AWAITING_REFUND`、R 仍 `SUBMITTED`、E2 仍 `DISPUTED`、待辦仍 `OPEN` | API JSON |
| PF-06 | AC-2.4 | admin | 同單 admin E29 `refundDecisions=[{paymentId: R, exported: true}]`（新 key） | ADMIN 開 `/admin/orders/{order}` 看到 `submission-history` 中 R 的 submission 標示已作廢 | 200、`adjustmentPaymentIds` 長度 1；E10 R `VOID`，另有 1 筆 `direction=REFUND`、`method=ADMIN_ADJUSTMENT`、`amount=500`、`status=VERIFIED`；E27 `netPaid=1000`；E27 `logs[]` `ORDER_MEDIATED` 的 `refundDecisions=[{paymentId: R, exported: true}]` | 截圖 + API JSON |
| PF-07 | AC-2.4 | admin | 另一張單同 PF-05 前置；E29 `refundDecisions=[{paymentId: R, exported: false}]` | — | 200；R `VOID`；無 `ADMIN_ADJUSTMENT`；`netPaid=1500`；`ORDER_MEDIATED` 記 `exported: false` | API JSON |
| PF-08 | AC-2.4 | admin | 另一張 `order`：`SUP_SUBMITTED` → `SHIFT_SUB(-(72*60+1))` → `DISPUTE(owner, 測試)`；admin E29 `{}` | — | 200；E10 補款 S `VOID`、無任何新 payment；E19 `REJECTED`；`netPaid=1500`；雙方各 1 筆 `type=SUPPLEMENT_VOIDED_BY_ADMIN`「此筆補款由雙方自行處理」 | API JSON |
| PF-09 | AC-2.5 | admin | 接 PD-07 的 `DISPUTED` 單；admin E27；admin E13（補款 submission）；開 `/admin/orders/{order}` | 頁面顯示 `payment-account-snapshot`、`submission-history`；沒有可編輯變更或付款金額的輸入元件 | E27 200：含 `modifications`、`payments`（補款 `accountSnapshot` 四欄）、`logs`；E13 200 `url`、`expiresAt` | 截圖 + API JSON |
| PF-10 | AC-2.5 | admin | 建 `orderEsc`：E1 → E6 → owner `UPLOAD(INITIAL)`（`SUBMITTED`）→ shift-time `ESCALATE_AT`（`paymentId`=INITIAL、`minutes=-1` 使其到期）→ C4 `POST /api/internal/cron/payments/escalation`；admin E27 | — | E28 該單 1 筆 `INITIAL_PAYMENT_UNVERIFIED` `OPEN`；E27 200（非 `DISPUTED` 但有 OPEN 待辦） | API JSON |
| PF-11 | AC-2.5 | admin | 一張普通 `CONFIRMED` `order`（無待辦）；admin E27；開 `/admin/orders/{order}` | 顯示「無權限檢視此訂單」 | E27 403 `FORBIDDEN` | 截圖 + API JSON |
| PF-12 | AC-2.6 | owner | 另一張 `order`：FT1 V1、V2 `DONE`（`careLogSubmitted=true`）；owner E17 `CANCEL_ALL` → `QUOTE()`（1000）→ `ACCEPT`（V3 `CANCELLED`、`AWAITING_REFUND`）；開 `/owner/orders/{order}`；owner E25；owner E26 `POST /api/orders/{order}/service-disputes`（`description=服務有問題`） | 不顯示確認結案與回報問題按鈕（提示文案依 PRD-009 §4.1 `MODIFICATION_IN_PROGRESS`） | E2 `completionHint.code=MODIFICATION_IN_PROGRESS`、`allowedActions` 不含 `COMPLETE` / `REPORT_SERVICE_ISSUE`、`disputeWindowEndsAt` 不輸出；E25 → 409 `MODIFICATION_IN_PROGRESS`；E26 → 409 `MODIFICATION_IN_PROGRESS`；E2 仍 `IN_PROGRESS` | 截圖 + API JSON |
| PF-13 | AC-2.6 | — | 同單 shift-time `COMPLETION_BASIS_AT`（`minutes=-(48*60+5)`）→ C8 `POST /api/internal/cron/orders/auto-complete` | — | E2 仍 `IN_PROGRESS` | API JSON |
| PF-14 | AC-2.7 | owner、sitter | 另一張 `order`：FT1 V1 `DONE`（`completedAt = now − 30h`、`careLogSubmitted=true`）、FT1 V2 `CLOSED_BY_SYSTEM`（`closedAt = now − 48h`）；`PROPOSE(減少 V3)` → `ACCEPT` → `UPLOAD(R)` → `VERIFY(R)`（`SETTLED`）；讀 E19 `endedAt` 為 Ts | — | E2 `disputeWindowEndsAt = Ts + 48h`（基準 = `SETTLED` 結束時間，大於 V1 的 `completedAt + 24h` 與 V2 的關閉時間） | API JSON |
| PF-15 | AC-2.7 | owner | 同單 owner E26（`description=回報測試`） | — | 200、E2 `DISPUTED`（在視窗內） | API JSON |
| PF-16 | AC-2.7 | — | 另一張單重做 PF-14 至 `SETTLED`；shift-time `COMPLETION_BASIS_AT` `minutes=-(48*60-5)` → C8；再 shift `minutes=-10` → C8 | — | 第一次 C8 後 E2 仍 `IN_PROGRESS`；第二次後 `COMPLETED`；雙方各 1 筆 `ORDER_AUTO_COMPLETED`「訂單已自動結案」 | API JSON |
| PF-17 | AC-2.7 | owner | 另一張 `order`：FT1 V1 `DONE`（`completedAt = now − 30h`）、FT1 V2 `CLOSED_BY_SYSTEM`（`closedAt = now − 48h`）；owner E17 `CHANGE` 只新增 D+7（`REQUESTED`，此時 V3 仍 `PENDING`）；FT1 V3 `DONE`（`completedAt = now − 20h`、`careLogSubmitted=true`），讀 F1 V3 `completedAt` 為 c3；owner E22 撤回，讀 E19 `endedAt` 為 Tw；呼叫 C8 | — | E19 `WITHDRAWN`、`endedAt=Tw`；E2 `disputeWindowEndsAt = c3 + 24h + 48h`（≠ `Tw + 48h`，撤回不推遲）；C8 後 E2 仍 `IN_PROGRESS`（基準未到） | API JSON |
| PF-18 | AC-2.8 | sitter | 以 `sitter` PRO 建好以下訂單後，`sitter-setup` 改 `subscriptionExpiresAt = now − 2 天`（鎖定）：o1 `CONFIRMED`；o2 有 owner `REQ(減少 V3)`；o3 有 owner `REQ(減少 V3)`；o4 `SUP_SUBMITTED`；o5 `REFUND_WAIT`；o6 `REFUND_WAIT`。依序：sitter E17 於 o1（`CHANGE` 減少 V3、不帶加減）；sitter E20 於 o2（不帶加減）；sitter E21 於 o3（`reason=無法服務`）；sitter E22 撤回 o1 的變更；sitter E14 確認 o4 補款；sitter E11 上傳 o5 退款；sitter E24 於 o6 | — | o1 E17 201、`QUOTED`、`newTotalAmount=1000`；o2 E20 200 `QUOTED`；o3 E21 200 `REJECTED`；o1 E22 200 `WITHDRAWN`；o4 E14 200、補款 `VERIFIED`、變更 `SETTLED`；o5 E11 201 `SUBMITTED`；o6 E24 200、`DISPUTED`；以上皆無 403 `SUBSCRIPTION_LOCKED` | API JSON |
| PF-19 | AC-2.8 | ownerB | `ownerB` 開 `/booking/{sitter}` 並以 E1 送新預約 | `public-booking-unavailable-message` 或送出時顯示訂閱鎖定文案（PRD-005） | E1 403 `SUBSCRIPTION_LOCKED` | 截圖 + API JSON |
| PF-20 | AC-2.8 | sitter、ownerB | 另一組 `sitter`：改為 `subscriptionExpiresAt` 未過期但 `suspended=true`，重做 PF-18 的 o1（E17）、o4（E14）、o6（E24）與 PF-19 | — | E17 201、E14 200、E24 200；E1 403 `SUBSCRIPTION_LOCKED` | API JSON |

## 3. 權限與例外

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| PE-01 | EX-1、AC-1.5 | `REFUND_WAIT` 的訂單；`ownerB` 呼叫 E24 | 403 `FORBIDDEN`；E28 無待辦、E2 不變 |
| PE-02 | EX-2、AC-1.5 | `admin` 呼叫 E24；`sitter` 帳號以 `currentRole=OWNER` token 呼叫 E24（見 TS-016 §5 待補 2） | 皆 403 `ROLE_MISMATCH`；E28 無待辦 |
| PE-03 | AC-1.5 | owner 以隨機 UUID 當 `orderId` 呼叫 E24 | 404 `NOT_FOUND` |
| PE-04 | EX-3 | 不適用（申報不分方案、鎖定不擋）；見 PF-18、PF-20 | — |
| PE-05 | EX-4、EX-5、EX-6 | 見 PD-10、PD-11、PD-12、PD-06 | 409 `ORDER_STATE_CONFLICT` / `MODIFICATION_STATE_CONFLICT` / `PAYMENT_STATE_CONFLICT` |
| PE-06 | EX-7、EX-10 | 見 PD-13、PD-14 | 恰一筆成功、回放不重建待辦 |
| PE-07 | EX-8 | 見 PD-15 | 依先後 409 |
| PE-08 | EX-11 | 見 PF-01、PF-02 | 409 `ORDER_STATE_CONFLICT`，資料不變 |
| PE-09 | EX-14 | 見 PD-16 | 400 `VALIDATION_FAILED` |
| PE-10 | EX-15 | 見 PF-05 | 400，整筆調解不生效 |
| PE-11 | EX-16、BR-16 | PD-02 的 `DISPUTED` 單：owner E24 `description=""`（新 key）；另見 PF-01（E17 回 `ORDER_STATE_CONFLICT` 不回 `MODIFICATION_IN_PROGRESS`） | 400 `VALIDATION_FAILED`（先於 `ORDER_STATE_CONFLICT`） |
| PE-12 | EX-13 | 不適用（不可附圖）；UI 無上傳元件見 PD-01 | — |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.3（固定時刻 09:59 / 10:00 邊界與 `disputeAvailableAt=2026-11-24T02:00:00Z` 精確值） | 需固定時鐘；E2E 以 `SUBMISSION_AT` 平移後的相對值斷言 | `PaymentDisputeIntegrationTest`（注入 `Clock`，等於與差 1 秒） |
| AC-1.6、AC-1.7（指定先後的兩種結果都要驗） | `Promise.all` 只能驗恰一方成功且結果一致 | `PaymentDisputeIntegrationTest`（`CountDownLatch`） |
| AC-2.4（調解畫面「已匯出 / 未匯出」必選欄的 UI） | 調解畫面屬 PRD-020（待依 baseline 改寫），testid 未定義；E2E 只打 E29 | PRD-020 改寫後補 TS；`MediationIntegrationTest` |
| AC-2.6（C8 候選查詢排除有未結束變更的訂單）以外的排程時間點細節 | E2E 以 `COMPLETION_BASIS_AT` 平移後直接呼叫 C8 | `CompletionIntegrationTest`、`CompletionBasisIntegrationTest` |
| AC-2.7（固定時刻 `2026-11-22T07:00:00Z` 等精確值、「≤ 15 分鐘內第一次排程」） | 需固定時鐘；頻率屬 Cloud Scheduler | `CompletionBasisIntegrationTest`；手動驗證 Cloud Scheduler job `orders/auto-complete` 每 15 分鐘 |
| AC-2.8（全部動作以後端逐一斷言不檢查 `SUBSCRIPTION_LOCKED`） | E2E 已逐一驗證；後端另守護 | `SubscriptionLockedOrderIntegrationTest` |
| EX-9（鎖等待逾時 `ORDER_BUSY`） | 正式站無法持鎖 3 秒 | `PaymentDisputeIntegrationTest`、`OrderLockServiceTest` |
| EX-12（Resend 寄信失敗） | Resend 測試模式只寄專案擁有者信箱，無法注入失敗 | `PaymentDisputeIntegrationTest`（Resend mock）；真實寄信手動驗證 |
| AC-1.1（ADMIN 不寄 Email） | Email 投遞無 E2E 可讀端點；E2E 只驗 ADMIN 無站內通知 | `PaymentDisputeIntegrationTest`（`notifications` 無 ADMIN 收件列） |

## 5. 已知限制 / 待補

1. 帳號、角色切換、目前總額讀取來源、`sitter-setup` 重複呼叫等共通限制同 TS-016 §5（待補 1、2、4、5）。
2. **依賴未重寫的 PRD**：AC-2.3、AC-2.4 的調解端點 E29 與 AC-2.6、AC-2.7 的 E25 / E26 / C8 契約取自 SD-ORDER（Approved），但 PRD-020 仍待依 baseline 改寫；PRD-020 改寫後若 E29 契約變動，本 TS 同步修訂。
3. **結案基準無直接欄位**：E2 / E27 未輸出 `completion_basis_at`，以 `disputeWindowEndsAt`（只在 `IN_PROGRESS`、有效行程皆結束、無未結束變更時輸出）間接斷言；訂單 `DISPUTED` 後無法再讀。
4. **PF-03 依賴 FT2 `SERVICE_DATE` 整群組平移**才能以正式路徑 F3 開始行程；若 FT2 不允許 `DISPUTED` 訂單，改用 FT1 `IN_PROGRESS` 並註記未走正式路徑。
5. **PF-10 的 `ESCALATE_AT` 平移**：SD-ORDER §10.4 `shift-time` 支援 `ESCALATE_AT`；若平移後 C4 未建待辦，需確認 `escalated_at` 初始為 null。
6. **正式站排程**：直接呼叫 C4 / C6 / C8 會處理其他到期資料，斷言只看本測試資料。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 建立：依 PRD-024 與 SD-ORDER 撰寫款項爭議申報、`DISPUTED` 凍結、ADMIN 調解結果、協商期間結案限制、結案基準、訂閱鎖定不擋 | PRD-024、SD-ORDER Approved 2026-11-22 |
