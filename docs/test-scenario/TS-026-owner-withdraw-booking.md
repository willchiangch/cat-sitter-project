# TS-026: 飼主撤回預約（付款前）

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-026（Approved 2026-11-22）；前置資料用到 PRD-005（建單）、PRD-006（報價）、PRD-007（上傳 / 核對憑證）、PRD-027（婉拒、O4 排程）、PRD-009（結案、回報問題） |
| 對應 SD | SD-ORDER（E8 `POST /api/orders/{orderId}/withdraw`；E1、E2、E3、E4、E6、E7、E10、E11、E12、E14、E25、E26；C1、C3（帶 `orderId`）；§10.4 `shift-time`、`inspect`；§6.5 錯誤碼；§9.1 / §9.3 testid）；SD-FULFILL（FT1 `force-status`） |
| 類型 | 旅程（打真後端）：狀態轉移、權限、冪等、跨角色併發、排程互動 |
| 自動化路徑 | `frontend/e2e/journeys/owner-withdraw-booking.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> PRD-026 卡首規定以「AC-x.y-情境 n」逐列對應，本 TS 的 AC 欄照此寫法。

---

## 0. 共用約定

| 項目 | 約定 |
|------|------|
| 日期記號 | `D+n` = 今天（Asia/Taipei）+ n 天，`YYYY-MM-DD`。PRD 範例的固定日期（2026-11-25）換成 `D+4` |
| 撤回請求 | E8 body `{ "expectedStatus": …, "reason": … }`（`reason` 選填），標頭 `Idempotency-Key` = 每次新產生的 UUID（除非步驟指定沿用） |
| 「資料不變」的查法 | 撤回前後各取一次：E2（`status`、`cancellation` 欄位是否存在、`visits[].status`）、E10（`payments[].status`）、E12（submission 數量與狀態，有 payment 時）、雙方站內通知中該訂單撤回類通知計數；前後完全相同 |
| `inspect` 查法 | `GET /api/internal/test-data/orders/{orderId}/inspect`（`X-Internal-Secret`，SD-ORDER §10.4）：`orderLogs[]`、`notifications[]`（`type`、`recipientUserId`、`recipientRole`、`mandatory`、`emailStatus`）、`payments[]`（`status`、`voidedAt`）、`fulfill.visits`。下文「inspect」即指此端點。「取消類紀錄」= `ORDER_WITHDRAWN`、`ORDER_REJECTED`、`ORDER_REVIEW_EXPIRED`、`ORDER_CANCELLED_BY_SITTER`、`PAYMENT_EXPIRED`；計數時排除 `TEST_TIME_SHIFTED` / `TEST_VISIT_FORCED` |
| 撤回通知類型 | SD-ORDER §7.2：保母 `BOOKING_WITHDRAWN`、飼主 `BOOKING_WITHDRAWN_SELF`，皆 `mandatory=false` |
| 站內通知文案查法 | 標題 / 內容 / 連結由收件者以自己的 token 呼叫站內通知清單核對（SD-014 §4.1 `GET /api/notifications?role=…`；SD-NOTIFY 重建後以其契約為準），連結 OWNER `/owner/orders/{orderId}`、SITTER `/sitter/orders/{orderId}` |
| 排程呼叫 | C1 / C3 一律帶 `?orderId={order}`（SD-ORDER §6.3），只處理該測試訂單、計數只算該筆；自動觸發會跳過測試帳號訂單，因此不需避開排程時刻 |
| null 欄位 | 回應中值為 null 的欄位不輸出（SD-ORDER §6.1）；「原因為 null」= `cancellation.reason` 欄位不存在 |
| 空白 | 原因「去除前後空白」= SD-GLOBAL-SPEC §2.7 `strip()`，長度以 code point 計 |
| 時間避開 | spec 開始時若 Asia/Taipei 時間在 23:50–00:10 之間，等待到 00:10 後再跑（避免 `D+n` 跨日） |

## 1. 前置資料

> 不寫死 UUID。每個 test 自己 provision、可獨立重跑。帳號 email `{代號}-{runId}@e2e-journey.test`，以 `POST /api/internal/test-data/provision-account`（`roles[]`、`displayName`）建立後走真實登入。`sitter-setup` / `owner-setup` 為 upsert（只覆寫出現的欄位），`planId` / `petId` 依回應的 `key` 對應（SD-ORDER §10.4）。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `S-w` | SITTER | `sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`subscriptionExpiresAt=D+30`、`payoutAccount` 四欄齊、`plans` = P1（`key=P1`、單價 500、適用 CAT）、`questions=[]`、`emailPreferences.ORDER_AFFAIR=開` | 可預約 |
| `owner-O` | OWNER | `owner-setup`：1 隻貓（`key=A`）；`emailPreferences.ORDER_AFFAIR=開` | — |
| `owner-P` | OWNER | `owner-setup`：1 隻貓 | 他人 |
| `admin` | ADMIN | `provision-account`（`roles=[ADMIN]`） | — |

訂單造法（每個步驟群組各自新建，不共用）：

| 代號 | 造法 | 結果 |
|------|------|------|
| `ord-PENDING` | `owner-O` E1：P1、`dates=[D+4, D+5]`、`dailyCount=1`、`petIds=[A]`、`expectedAmount=1000` | `PENDING`，2 個 `PENDING` 行程，`reviewExpiresAt` = `D+4` 00:00 Asia/Taipei |
| `ord-PP` | `ord-PENDING` → `S-w` E6（`expectedAmount=1000`，不帶調整） | `PENDING_PAYMENT`；E6 回應 `payment.paymentId`；E10 該 payment `status=UNPAID` |
| `ord-SUB` | `ord-PP` → `owner-O` E11 上傳 JPEG（`disclaimerAgreed=true`、`lastFive=12345`） | `PENDING_PAYMENT`，payment `SUBMITTED` |
| `ord-CONF` | `ord-SUB` → `S-w` E14 `verify`（`submissionId`） | `CONFIRMED`，2 個 `PENDING` 行程（V1 = `D+4`、V2 = `D+5`）；E10 `verifiedAt` 有值 |

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4 擴充後涵蓋訂單、行程、payment、submission 與 GCS 憑證）。

## 2. 測試步驟

> 步驟 ID 前綴 `WD-`，e2e spec 的 `step()` / `apiStep()` 標籤必須以同一 ID 開頭。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| WD-01 | AC-1.1-情境 1 | owner-O | 建 `ord-PENDING`；開啟 `/owner/orders/{order}` | 出現 `owner-order-detail-btn-withdraw` | E2 `status=PENDING`、`allowedActions` 含 `WITHDRAW` | 截圖 + API JSON |
| WD-02 | AC-1.1-情境 1 | owner-O | 點 `owner-order-detail-btn-withdraw`，在 `owner-order-withdraw-input-reason` 填「行程取消」，點 `owner-order-withdraw-btn-confirm` | 狀態顯示「已取消」；`owner-order-detail-btn-withdraw` 消失 | 頁面發出的 E8 恰 1 次：body `expectedStatus=PENDING`、`reason=行程取消`，帶 `Idempotency-Key`；回應 200 `{ orderId, status: "CANCELLED", cancelledBy: "OWNER" }` | 截圖 + API JSON |
| WD-03 | AC-1.1-情境 1 | owner-O | 呼叫 E2 | — | `status=CANCELLED`；`cancellation.cancelledBy=OWNER`、`cancellation.reason=行程取消`、`cancellation.cancelledAt` 有值；`visits` 2 個皆 `CANCELLED`；`payments=[]`；inspect `orderLogs` 中取消類紀錄恰 1 筆 `ORDER_WITHDRAWN`：`operatorRole=OWNER`、`operatorUserId` = owner-O、payload `fromStatus=PENDING`、`reason=行程取消`、`transitions` 含 `ORDER` `PENDING→CANCELLED` 與 2 筆 `VISIT` `PENDING→CANCELLED`；inspect `fulfill.visits` 2 筆皆 `CANCELLED` | API JSON |
| WD-04 | AC-1.1-情境 1、BR-6 | S-w、owner-O | 呼叫 inspect；雙方查站內通知清單 | — | inspect `notifications` 恰 2 則：`BOOKING_WITHDRAWN`（`recipientUserId=S-w`、`recipientRole=SITTER`、`mandatory=false`）與 `BOOKING_WITHDRAWN_SELF`（`recipientUserId=owner-O`、`recipientRole=OWNER`、`mandatory=false`），兩則 `emailStatus` 皆為已排入投遞（非「不寄送」）；`S-w` 清單該則標題「飼主已撤回預約（{D+4}）」、內容含「原因：行程取消」、`linkUrl=/sitter/orders/{order}`；owner-O 清單該則「您已撤回預約」、`linkUrl=/owner/orders/{order}` | API JSON |
| WD-05 | AC-1.1-情境 1 | owner-O、S-w | owner-O 開啟 `/owner/orders` 點 `owner-orders-tab-history`；`S-w` 開啟 `/sitter/orders` 點 `sitter-orders-tab-history` | 飼主歷史分頁出現該訂單；保母歷史分頁出現 `sitter-order-card-{order}`；雙方評估中分頁（`owner-orders-tab-evaluating`、`sitter-orders-tab-evaluating`）沒有 | E3 `tab=HISTORY` 含 `orderId`、`tab=EVALUATING` 不含；E4 同 | 截圖 ×2 + API JSON |
| WD-06 | AC-1.1-情境 2 | owner-O、S-w | `owner-setup` 把 owner-O、`sitter-setup` 把 `S-w` 的 `emailPreferences.ORDER_AFFAIR` 改關；建新 `ord-PENDING`，E8 撤回（`expectedStatus=PENDING`、`reason=行程取消`） | — | 200；inspect `notifications` 恰 2 則（`BOOKING_WITHDRAWN`、`BOOKING_WITHDRAWN_SELF`），兩則 `emailStatus` 皆為不寄送。步驟結束把雙方偏好改回開 | API JSON |
| WD-07 | AC-1.1-情境 3、EX-19 | owner-O | 建新 `ord-PENDING`，UI 撤回時原因欄留空直接確認 | 狀態顯示「已取消」 | E8 body 無 `reason` 或為空字串；200；E2 `cancellation.reason` 欄位不存在；`S-w` 通知內容不含「原因：」 | 截圖 + API JSON |
| WD-08 | AC-1.1-情境 3、EX-19、BR-11 | owner-O | 建新 `ord-PENDING`，E8 `reason="   "`（只有空白） | — | 200；E2 `cancellation.reason` 欄位不存在；`S-w` 通知內容不含「原因：」 | API JSON |
| WD-09 | AC-1.1-情境 4、EX-18 | owner-O | 建新 `ord-PENDING`，UI 原因欄輸入 201 個中文字後確認 | 原因欄旁顯示「最多 200 字」；訂單仍為評估中 | 若有送出 E8 則 400；E2 `status=PENDING` | 截圖 |
| WD-10 | AC-1.1-情境 4、EX-18 | owner-O | 同一訂單，E8 `reason` = 前後各 2 個空白 + 201 個中文字 | — | 400 `VALIDATION_FAILED`、`payload.field=reason`；資料不變（§0） | API JSON |
| WD-11 | BR-11（邊界） | owner-O | 同一訂單，E8 `reason` = 前後各 2 個空白 + 200 個中文字（新 key） | — | 200；E2 `cancellation.reason` = `strip()` 後的 200 字（不含前後空白）；inspect `ORDER_WITHDRAWN` payload `reason` 同值 | API JSON |
| WD-12 | AC-1.1-情境 5、BR-9 | owner-O | 建新 `ord-PENDING` 後，重呼叫 `sitter-setup` 把 `S-w` 改 `subscriptionExpiresAt=D-1`（鎖定）；UI 撤回、原因「行程取消」 | 同 WD-02 | 200、`cancelledBy=OWNER`（不回 `SUBSCRIPTION_LOCKED`）；E2 與 WD-03 相同欄位值；通知同 WD-04。步驟結束把 `S-w` 改回 `subscriptionExpiresAt=D+30` | 截圖 + API JSON |
| WD-13 | AC-1.2 | owner-O | 建 `ord-PP`；開啟訂單詳情 | 出現報價明細 `owner-order-detail-quote-breakdown` 與 `owner-order-detail-btn-withdraw` | E2 `status=PENDING_PAYMENT`、`allowedActions` 含 `WITHDRAW`；E10 payment `status=UNPAID` | 截圖 + API JSON |
| WD-14 | AC-1.2、BR-1、BR-5 | owner-O | UI 撤回（不填原因） | 狀態顯示「已取消」 | E8 body `expectedStatus=PENDING_PAYMENT`；200、`cancelledBy=OWNER`；E2 `status=CANCELLED`、`visits` 全 `CANCELLED`；E10 payment `status=VOID`、`voidedAt` 有值；inspect：`ORDER_WITHDRAWN` 恰 1 筆、payload `fromStatus=PENDING_PAYMENT`、`transitions` 含 `PAYMENT` `UNPAID→VOID`，`payments[0].status=VOID`；`notifications` 恰 2 則（`BOOKING_WITHDRAWN`、`BOOKING_WITHDRAWN_SELF`），`emailStatus` 皆為已排入投遞；保母清單該則標題同 WD-04、無「原因：」 | 截圖 + API JSON |
| WD-15 | AC-1.3、EX-4 | owner-O | 建 `ord-SUB`；E8 `expectedStatus=PENDING_PAYMENT` | — | 409 `PAYMENT_ALREADY_SUBMITTED`；E2 `status=PENDING_PAYMENT`；E10 payment `SUBMITTED`；E12 1 筆 submission `status=SUBMITTED`；`visits` 全 `PENDING`；inspect 無 `ORDER_WITHDRAWN`、無 `BOOKING_WITHDRAWN` / `BOOKING_WITHDRAWN_SELF` 通知 | API JSON |
| WD-16 | AC-1.3、EX-4 | owner-O | 開啟該訂單詳情 | 不出現 `owner-order-detail-btn-withdraw`；出現 `owner-order-detail-withdraw-blocked-message`「已上傳付款憑證，無法撤回；如需取消請與保母聯繫」 | E2 `allowedActions` 不含 `WITHDRAW` | 截圖 + API JSON |
| WD-17 | AC-1.4-情境 1、EX-5 | owner-O | 建 `ord-CONF`；E8 `expectedStatus=PENDING_PAYMENT` | — | 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=CONFIRMED`；資料不變 | API JSON |
| WD-18 | AC-1.4-情境 2、EX-5 | owner-O | 開啟 `ord-CONF` 詳情 | 不出現 `owner-order-detail-btn-withdraw`；出現 `owner-order-detail-btn-modify`「申請變更」（SD-ORDER §9.3，整筆取消在精靈內選 `order-modify-type-CANCEL_ALL`） | E2 `allowedActions` 不含 `WITHDRAW`、含 `REQUEST_MODIFICATION` | 截圖 + API JSON |
| WD-19 | AC-1.4-情境 1、情境 2 | owner-O | 對 `ord-CONF` 的 V1 FT1 `force-status`（`status=IN_PROGRESS`）；E2 確認 `status=IN_PROGRESS`；E8 `expectedStatus=PENDING_PAYMENT`；開啟詳情 | 不出現撤回按鈕；出現 `owner-order-detail-btn-modify`（V2 仍 `PENDING`，入口照常顯示） | 409 `ORDER_STATE_CONFLICT`、`currentStatus=IN_PROGRESS`；資料不變 | 截圖 + API JSON |
| WD-20 | AC-1.4-情境 1 | owner-O | V1 FT1（`status=DONE`、`careLogSubmitted=true`）、V2 FT1（`PENDING → DONE`、`careLogSubmitted=true`）；owner-O E26 `POST /api/orders/{order}/service-disputes`（`description=測試爭議`）→ E2 `status=DISPUTED`；E8 `expectedStatus=PENDING_PAYMENT` | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=DISPUTED`；資料不變 | API JSON |
| WD-21 | AC-1.4-情境 1 | owner-O | 另建 `ord-CONF`，V1、V2 各 FT1（`PENDING → DONE`、`careLogSubmitted=true`）→ owner-O E25 `complete` → E2 `status=COMPLETED`；E8 `expectedStatus=PENDING_PAYMENT` | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=COMPLETED`；資料不變 | API JSON |
| WD-22 | AC-1.4-情境 1 | owner-O | 建 `ord-PENDING`，`S-w` E7 `decline`（`reason=無法接單`）→ E2 `cancellation.cancelledBy=SITTER`；owner-O E8 `expectedStatus=PENDING` | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；`cancelledBy` 仍為 `SITTER` | API JSON |
| WD-23 | AC-1.5-情境 1 前置 | owner-O | 建 `ord-PENDING`；以 `POST /api/internal/test-data/orders/{order}/shift-time`（`target=REVIEW_EXPIRES_AT`、`minutes` = −(距 `reviewExpiresAt` 的分鐘數 + 1)）把期限移到過去 | — | E2 `status=PENDING`、`reviewExpiresAt < serverTime` | API JSON |
| WD-24 | AC-1.5-情境 1、EX-6、BR-4 | owner-O | UI 撤回（原因「行程取消」） | 同 WD-02 | E8 200、`cancelledBy=OWNER`（不回 `DEADLINE_PASSED`）；`visits` 全 `CANCELLED` | 截圖 + API JSON |
| WD-25 | AC-1.5-情境 1、EX-6 | 系統 | 以 `X-Internal-Secret` 呼叫 C1 `POST /api/internal/cron/orders/review-expiry?orderId={order}` | — | 200、`processed=0`、`skipped=0`、`failed=0`（已 `CANCELLED` 不再是候選）；E2 仍 `cancellation.cancelledBy=OWNER`、`cancellation.reason=行程取消`；inspect 取消類紀錄恰 1 筆 `ORDER_WITHDRAWN`、無 `ORDER_REVIEW_EXPIRED`；`notifications` 恰 2 則（`BOOKING_WITHDRAWN`、`BOOKING_WITHDRAWN_SELF`），無 `REVIEW_EXPIRED_OWNER` / `REVIEW_EXPIRED_SITTER` | API JSON |
| WD-26 | AC-1.5-情境 2、EX-6 | owner-O | 建 `ord-PP`；`shift-time`（`target=PAYMENT_DUE_AT`、`paymentId`、`minutes` = −(距 `dueAt` 的分鐘數 + 1)）；E8 `expectedStatus=PENDING_PAYMENT`；再呼叫 C3 `POST /api/internal/cron/payments/initial-expiry?orderId={order}` | — | E8 200、`cancelledBy=OWNER`；E10 payment `VOID`；C3 回 200、`processed=0`、`skipped=0`、`failed=0`；C3 後 E2 仍 `cancelledBy=OWNER`；inspect 無 `PAYMENT_EXPIRED` log 與通知 | API JSON |
| WD-27 | AC-1.5-情境 3 前置 | 系統 | 建 `ord-PENDING`；`shift-time` 同 WD-23；呼叫 C1 `?orderId={order}` | — | C1 200、`processed=1`、`skipped=0`、`failed=0`；E2 `status=CANCELLED`、`cancellation.cancelledBy=SYSTEM`；inspect 有 1 筆 `ORDER_REVIEW_EXPIRED`（`operatorRole=SYSTEM`） | API JSON |
| WD-28 | AC-1.5-情境 3、EX-17 | owner-O | E8 `expectedStatus=PENDING` | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`（不是 `DEADLINE_PASSED`）；E2 `cancelledBy` 仍 `SYSTEM`；inspect 無 `ORDER_WITHDRAWN` | API JSON |
| WD-29 | AC-1.6-情境 1、EX-1 | owner-P | 建 `ord-PENDING`（owner-O 的）；owner-P 以合法 body 與新 key 打 E8 | — | 403 `FORBIDDEN`；資料不變 | API JSON |
| WD-30 | AC-1.6-情境 2、EX-2 | S-w（SITTER） | 該訂單的保母以 SITTER token 打 E8 | — | 403 `ROLE_MISMATCH`；資料不變 | API JSON |
| WD-31 | AC-1.6-情境 3、EX-2 | admin | ADMIN token 打 E8 | — | 403 `ROLE_MISMATCH`；資料不變 | API JSON |
| WD-32 | AC-1.6-情境 4、EX-3 | owner-O | E8 路徑 `orderId` = 隨機產生的 UUID | — | 404 `NOT_FOUND` | API JSON |
| WD-33 | AC-1.7-情境 1、EX-7 | owner-O、S-w | 建 `ord-PENDING`；owner-O 開啟詳情（畫面為評估中）；`S-w` 以 API E6 報價；owner-O 在原畫面點撤回並確認 | 顯示「訂單狀態已變更，請重新整理」 | 頁面的 E8 body `expectedStatus=PENDING` → 409 `ORDER_STATE_CONFLICT`、`currentStatus=PENDING_PAYMENT`；E2 `status=PENDING_PAYMENT`；E10 payment `UNPAID` | 截圖 + API JSON |
| WD-34 | AC-1.7-情境 1 | owner-O | 重新整理頁面 | 出現 `owner-order-detail-quote-breakdown` 與 `owner-order-detail-btn-withdraw` | E2 `allowedActions` 含 `WITHDRAW` | 截圖 |
| WD-35 | AC-1.7-情境 2、EX-8 | owner-O、S-w | 建 3 張 `ord-PENDING`；每張同時送出 owner-O E8（`expectedStatus=PENDING`）與 `S-w` E7（`reason=無法接單`）（`Promise.all`） | — | 每張恰一方 200、另一方 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E2 `cancelledBy` = 成功那方（`OWNER` 或 `SITTER`）；inspect 取消類紀錄恰 1 筆且為成功那方（`ORDER_WITHDRAWN` 或 `ORDER_REJECTED`）；`notifications` 只有成功那方的類型（`BOOKING_WITHDRAWN*` 或 `BOOKING_DECLINED*`） | API JSON |
| WD-36 | AC-1.8-情境 1、EX-10 | owner-O | 建 `ord-PENDING`；以 key K、`reason=行程取消` 打 E8；再以 K 與相同 body 重送 | — | 兩次皆 200，body 完全相同；inspect `ORDER_WITHDRAWN` 仍恰 1 筆、`notifications` 仍恰 2 則 | API JSON |
| WD-37 | AC-1.8-情境 2、EX-11 | owner-O | 以 K、`reason=改期` 重送 | — | 422 `IDEMPOTENCY_KEY_REUSED` | API JSON |
| WD-38 | AC-1.8-情境 3、EX-12 | owner-O | 以新 key、`expectedStatus=PENDING` 再打 E8 | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED` | API JSON |
| WD-39 | AC-1.8-情境 4、EX-18 | owner-O | 建 `ord-PENDING`；(a) 不帶 `Idempotency-Key`；(b) 帶新 key、body 不含 `expectedStatus` | — | (a) 400 `VALIDATION_FAILED`、`payload.field=Idempotency-Key`；(b) 400 `VALIDATION_FAILED`、`payload.field=expectedStatus`；兩次後 E2 仍 `PENDING` | API JSON |

## 3. 權限與例外

> 已併入 §2 的 EX：EX-1（WD-29）、EX-2（WD-30、31）、EX-3（WD-32）、EX-4（WD-15、16）、EX-5（WD-17~22）、EX-6（WD-24~26）、EX-7（WD-33）、EX-8（WD-35）、EX-10（WD-36）、EX-11（WD-37）、EX-12（WD-38）、EX-17（WD-27、28，依序執行的版本）、EX-18（WD-09、10、39）、EX-19（WD-07、08）。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| WD-E01 | §3 匿名 | 無 `Authorization` 標頭，以合法 body 打 E8（`ord-PENDING`） | 401 `UNAUTHENTICATED`；資料不變 |
| WD-E02 | EX-18、BR-10 | `ord-PENDING`，`expectedStatus=FOO` | 400 `VALIDATION_FAILED`、`payload.field=expectedStatus`；資料不變 |
| WD-E03 | BR-7、EX-18 | `ord-PENDING`，`expectedStatus=CONFIRMED`（合法主狀態但不是 `PENDING` / `PENDING_PAYMENT`） | 409 `ORDER_STATE_CONFLICT`、`currentStatus=PENDING`（不是 400；SD-ORDER E8、PRD-026 §4.1 步驟 2.4 / 2.6）；資料不變 |
| WD-E04 | BR-12 | owner-P 對 owner-O 的 `ord-PENDING` 打 E8，且不帶 `Idempotency-Key` | 403 `FORBIDDEN`（403 先於 400） |
| WD-E05 | BR-12 | 對隨機 UUID 訂單打 E8，且 body 無 `expectedStatus` | 404 `NOT_FOUND`（404 先於 400） |
| WD-E06 | BR-12 | `ord-SUB`，`expectedStatus=PENDING`（畫面過期） | 409 `ORDER_STATE_CONFLICT`、`currentStatus=PENDING_PAYMENT`（狀態衝突先於 `PAYMENT_ALREADY_SUBMITTED`）；資料不變 |
| WD-E07 | EX-16（依序版本） | `ord-PP` 撤回成功後，owner-O 對同一 `paymentId` 打 E11 上傳 JPEG | 409 `PAYMENT_STATE_CONFLICT`（payment 已 `VOID`，SD-ORDER E11）；E12 無 submission |
| WD-E08 | EX-16（依序版本） | `ord-SUB`（已上傳）後 owner-O 撤回 | 同 WD-15：409 `PAYMENT_ALREADY_SUBMITTED` |
| WD-E09 | BR-12（401 先於冪等回放） | 承 WD-36，以同一 key K 與相同 body、但不帶 `Authorization` 重送 E8 | 401 `UNAUTHENTICATED`（不回放 200）；inspect `ORDER_WITHDRAWN` 仍 1 筆 |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.2「保母當月接單計數不變」（BR-8） | 計數沒有對外查詢欄位（E5 `precheck.quotaReached` 只有布林）；以 E2E 造出可觀察差異需 3 張確認鏈，成本高且月底跨月不穩 | `OrderWithdrawIntegrationTest` |
| AC-1.5-情境 1 的固定時刻（`review_expires_at` 後 1 秒撤回） | 正式站無法注入時間；E2E 以 `shift-time` 把期限移到過去驗同一規則（WD-23~25） | `OrderWithdrawIntegrationTest`（注入 `Clock`，剛好等於期限、差 1 秒） |
| AC-1.7-情境 3、EX-9（訂單列被持有 > 3 秒 → 409 `ORDER_BUSY` + `Retry-After: 1`；前端自動重試一次） | 正式站無法從外部持有訂單列鎖 | `OrderWithdrawConcurrencyIntegrationTest`；`OrderLockServiceTest` |
| AC-1.7-情境 2 的「確定重疊」 | E2E 的 `Promise.all` 不保證兩請求在 DB 層重疊（斷言仍成立：恰一方成功）；確定重疊需 `CountDownLatch` | `OrderWithdrawConcurrencyIntegrationTest`（撤回 vs 報價、撤回 vs 婉拒） |
| EX-11 的 409 `IDEMPOTENCY_IN_PROGRESS` | 正式站無法穩定製造處理中視窗 | `OrderWithdrawIntegrationTest`（SD-ORDER §10.2 共通必測） |
| AC-1.8-情境 5、EX-13（Resend 回 5xx，投遞紀錄待重試、最多 5 次、4xx 直接失敗） | 正式站無法注入 Resend 故障；Resend 測試模式只寄專案擁有者信箱 | `OrderWithdrawIntegrationTest`（Resend mock，SD-ORDER §10.1「通知」）；真實寄信內容手動驗證 |
| EX-17 的真併發（撤回與 O4 / O8 排程同時鎖同一張單） | 無法控制排程與請求同時到達；E2E 驗兩種先後（WD-24~28） | `OrderWithdrawIntegrationTest`（期限後仍可撤回、排程後到略過）；`ReviewExpiryCronIntegrationTest`；`PaymentCronIntegrationTest` |
| EX-16（同一飼主多裝置同時上傳與撤回） | 既有產品決策不處理同一使用者自我併發；E2E 只驗依序結果（WD-E07、E08） | `PaymentSubmissionConcurrencyIntegrationTest` 覆蓋跨角色版本 |
| BR-10「`order_logs`、通知與狀態變更同一交易」 | 需注入交易失敗 | `OrderLogWriterTest`；SD-ORDER §10.1「交易失敗」 |
| EX-14（方案不足 / 訂閱鎖定）、EX-15（GCS 失敗） | PRD 標為不適用；鎖定不擋撤回已由 WD-12 驗證 | 不需測試 |

## 5. 已知限制 / 待補

未解決：

1. **inspect `notifications[].emailStatus` 的值域**由 SD-NOTIFY 定義（尚未重建）；WD-04、06、14 以「已排入投遞」/「不寄送」描述，e2e-author 依實際值對應。站內通知標題 / 內容 / 連結仍以 SD-014 舊版清單 API 核對。
2. **AC-1.2「當月接單計數不變」**：inspect 與 E5 皆未提供當月計數，仍由後端測試覆蓋（§4）。

已解決（2026-11-22 SD-ORDER 修訂，commit 477c75d）：`order_logs` / 通知 / Email 投遞狀態 → inspect；`expectedStatus` 合法主狀態非 `PENDING` / `PENDING_PAYMENT` → 409（WD-E03）；`sitter-setup` upsert 與 `emailPreferences`；ADMIN 帳號 → `provision-account roles[]`；排程帶 `orderId` 只處理測試訂單、自動觸發跳過測試帳號（WD-25~27 改斷言精確計數，刪除重呼叫因應）；訂單詳情變更入口 → `owner-order-detail-btn-modify`；分頁 testid → SD-ORDER §9.3；cleanup 跨模組範圍 → §10.4。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依 commit 477c75d 的核准後修訂更新：`order_logs` / 通知 / Email 投遞斷言改用 inspect（WD-03、04、06、11、14、15、25、27、28、35、36），移出 §4；C1 / C3 帶 `orderId` 並斷言精確計數（WD-25~27），刪除重呼叫因應；`ord-CONF` 改 2 個行程，FT1 直接 `PENDING → DONE`（WD-19~21）；變更入口改 `owner-order-detail-btn-modify`（WD-18、19）、分頁改 SD-ORDER §9.3 testid（WD-05）；`sitter-setup.emailPreferences`（WD-06）、`provision-account roles=[ADMIN]`；WD-E03 去掉待釐清（合法主狀態 → 409）；新增 WD-E09（401 先於冪等回放，PRD-026 已回寫）；§5 移除已解決項。狀態維持 Draft | SD-ORDER §6.3、§9.3、§10.4、E8 修訂；SD-FULFILL FT1；PRD-026 回寫 |
| 2026-11-22 | 建立：依 Approved 的 PRD-026（8 條 AC、19 條 EX）與 SD-ORDER；步驟前綴 `WD-`；旅程型單一 spec；`order_logs` / Email 投遞紀錄斷言列待測試支援；鎖逾時與 Resend 故障交後端測試。狀態 Draft | PRD-026 自 PRD-005 拆出後首次撰寫 TS |
