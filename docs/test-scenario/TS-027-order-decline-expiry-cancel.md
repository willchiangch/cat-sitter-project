# TS-027: 保母婉拒、評估逾期與付款前保母取消

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-027（Approved 2026-11-22，含同日核准後回寫）；前置資料另用 PRD-005（送單）、PRD-006（報價）、PRD-007（上傳 / 駁回 / 核對憑證、O8）、PRD-026（飼主撤回）、PRD-010（轉介入口後的選單，僅入口） |
| 對應 SD | SD-ORDER（Approved 2026-11-22）：E2、E4、E5、E6、E7、E8、E9、E10、E11、E12、E14、E15、C1、C3、§6.3 排程端點（`orderId`）、§6.5 錯誤碼、§7.1 `order_logs`、§7.2 通知、§9.1 / §9.3 testid、§10.4 測試支援端點（含 `inspect`） |
| 類型 | 旅程（打真後端，§2.1–§2.6、§3）＋ 模組（mock，只有 §2.7 的 QRM 列：轉介失敗、`ORDER_BUSY` 重試等真後端造不出的回應） |
| 自動化路徑 | 旅程 `frontend/e2e/journeys/order-decline-expiry-cancel.spec.ts`；模組 `frontend/e2e/order-decline-cancel.spec.ts`（須 `assertNoUnmockedApi`） |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> 三個出口都是狀態轉移 + 權限 + 冪等 + 跨角色 / 排程競爭，主體用旅程型。
> PRD-027 AC 內「情境 n」逐列對應為 `AC-x.y-n`。

---

## 0. 測試邏輯（Given / When / Then）

- **Given**：每個 `test.describe` 自己 provision 飼主與保母（`@e2e-journey.test`），以 E1 建立 `PENDING` 訂單，需要 `PENDING_PAYMENT` 者再以 E6 原價報價；排程情境以 `shift-time` 把 `review_expires_at` / `due_at` 平移到過去。測試帳號訂單不會被 Cloud Scheduler 自動處理（SD-ORDER §6.3），排程只由測試以 `?orderId=` 觸發。
- **When**：保母在評估頁婉拒（E7）、在待付款訂單詳情取消（E9）；或觸發 C1（評估逾期）/ C3（付款逾期，PRD-007）。
- **Then**：訂單 `CANCELLED`，`cancellation.cancelledBy` / `reasonCode` / `reason` 正確，剩餘行程 `CANCELLED`、未結束 payment `VOID`，`inspect` 可見恰一筆對應 `order_logs` 與雙方各一則通知；被拒時回 PRD-027 BR-14 判斷順序中第一個不符的錯誤碼且不改資料、不寫 log、不發通知。

## 1. 前置資料

> 不寫死 UUID：訂單、payment、submission、毛孩、方案 id 一律取自建立時的回應（`sitter-setup` / `owner-setup` 以請求的 `key` 回傳 id）。每個 describe 可獨立重跑。

**記號**

| 記號 | 意義 |
|------|------|
| `D(n)` | E5 / E31 `serverTime` 換算的 Asia/Taipei 今天 + n 日 |
| `ownerToken(X)` / `sitterToken(X)` | 雙角色帳號 X 以 `currentRole=OWNER` / `SITTER` 取得的 token |
| **不變** | 飼主 E2 的 `status`、`cancellation`、`visits[].status`，保母 E10 的 payment `status`，以及 `inspect` 的 `orderLogs`、`notifications` 列數皆與操作前相同 |
| 200 字 | `String.strip()`（SD-GLOBAL-SPEC §2.7，含 U+3000、U+00A0）後以 Unicode code point 計；測試字串 = 199 個中文字 +「🐱」（UTF-16 為 201 個單位，用來抓以 UTF-16 長度計數的錯誤） |
| 需寄送 / 偏好略過 | `inspect.notifications[].emailStatus` 表示「會寄 Email」或「依偏好不寄」的值（列舉由 SD-NOTIFY 定義，見 §5 #3） |

**帳號**

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `POST /api/internal/test-data/provision-account`（`@e2e-journey.test`、`displayName=王小明`、`roles=[OWNER]`）＋ `owner-setup`：`pets` = key `M`（CAT）、`emailPreferences.ORDER_AFFAIR = 開` | — |
| `ownerMuted` | OWNER | 同上，`ORDER_AFFAIR = 關`，`pets` = key `K` | — |
| `sitterA` | OWNER + SITTER | provision（`roles=[OWNER, SITTER]`）＋ `sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄測試值、`plans` = key `P`（單價 600，`active`）、`questions = []`、`emailPreferences.ORDER_AFFAIR = 開`、`trustCirclePartnerIds = [sitterPartner]` | 雙角色供 `ROLE_MISMATCH` |
| `sitterPartner` | SITTER | 同 `sitterA`（`roles=[SITTER]`），無信任圈 | 轉介對象 |
| `sitterMuted` | SITTER | 同 `sitterA`（`roles=[SITTER]`），`emailPreferences.ORDER_AFFAIR = 關` | — |
| `sitterLock` | SITTER | 同 `sitterA`（`roles=[SITTER]`）；建好訂單（必要時已報價）後以 `sitter-setup` upsert `subscriptionExpiresAt = D(-2)` | 先可接單，後鎖定 |
| `sitterOther` | SITTER | 同 `sitterA`（`roles=[SITTER]`） | 越權用 |
| `admin` | ADMIN | provision-account `roles=[ADMIN]` | — |

**共用 helper（e2e-author 實作）**

| helper | 內容 |
|--------|------|
| `createPendingOrder(owner, sitter, dates)` | E31 取方案 → E1 `POST /api/orders`（`groups=[{planId:P, dates, dailyCount:1, petIds:[M]}]`、新 key）→ 201、`status=PENDING` |
| `createQuotedOrder(owner, sitter, dates)` | `createPendingOrder` → E6 `{expectedAmount}` → 200、`status=PENDING_PAYMENT`；回傳 INITIAL `paymentId` |
| `uploadProof(owner, order, paymentId)` | E11 multipart（`disclaimerAgreed=true`、`lastFive=12345`、1×1 PNG）→ 201、`paymentStatus=SUBMITTED`；回傳 `submissionId` |
| `shiftTime(order, target, 分鐘, paymentId?)` | `POST /api/internal/test-data/orders/{orderId}/shift-time`，`target=REVIEW_EXPIRES_AT` 或 `PAYMENT_DUE_AT`（後者帶 `paymentId`）；分鐘 = −(距到期分鐘數 + 5) |
| `runCron(path, orderId, headers?)` | `POST /api/internal/cron/{path}?orderId={orderId}`，預設帶 `X-Internal-Secret`（CI secret，不寫入證據）；C1 = `orders/review-expiry`、C3 = `payments/initial-expiry`；計數只算該訂單 |
| `inspect(order)` | `GET /api/internal/test-data/orders/{orderId}/inspect`：`orderLogs`（`actionType`、`operatorRole`、`payload`）、`notifications`（`type`、`recipientUserId`、`mandatory`、`emailStatus`）、`payments`（`voidedAt` 等） |
| `notificationText(user, orderId)` | 以該使用者 token 讀通知清單取標題 / 內文（SD-NOTIFY 通知查詢 API，路徑由 e2e-author 依 SD-NOTIFY 對應；`inspect` 不含內文） |

**標準日期**：`[D(10), D(11), D(12)]`（3 個行程、`baseAmount=1800`）；§2.5 用 `[D(10), D(11)]`（2 個行程）。

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4：訂單相關表、`payment_submissions` 的 GCS 物件、`referral_logs`、`idempotency_records`，並依「Cleanup 跨模組範圍」清信任圈、通知、方案、毛孩、訂閱、帳號）。

## 2. 測試步驟

> 步驟 ID 前綴 `QR-`（旅程）、`QRM-`（模組），spec 的 `step()` / `apiStep()` 標籤以同一 ID 開頭。

### 2.1 婉拒成功、確認框與轉介入口（describe「婉拒」：`owner` × `sitterA`）

前置：`ordD1 = createPendingOrder(owner, sitterA, 標準)`、`ordD2 = createPendingOrder(owner, sitterA, 標準)`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-01 | AC-1.2-1 | sitterA | 開 `/sitter/orders/{ordD1}`，點 `sitter-order-eval-btn-decline` | 對話框含必填的 `sitter-order-decline-input-reason`；`sitter-order-decline-btn-quick-reason-1`–`4` 文字依序「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」；文字「婉拒後飼主將收到通知，含您填寫的原因」 | — | 截圖 |
| QR-02 | AC-1.2-3（EX-5） | sitterA | 原因欄空白；改填 3 個半形空白；改填「　 」（U+3000 + U+00A0）；改填 201 字 | 空白與三種純空白時 `sitter-order-decline-btn-confirm` disabled；201 字時顯示「原因最多 200 字」且確認鈕 disabled | 期間未發出任何 E7 | 截圖 |
| QR-03 | AC-1.2-2 | sitterA | 清空後點 `sitter-order-decline-btn-quick-reason-1`，再在結尾輸入「（12/10–12/12）」 | 原因欄值 =「檔期已滿（12/10–12/12）」（帶入後可編輯） | — | 截圖 |
| QR-04 | AC-1.1-1 | sitterA | 清空原因改填「該週檔期已滿」，點 `sitter-order-decline-btn-confirm` | 對話框改顯示 `sitter-order-decline-btn-referral`「轉介信任圈夥伴」 | 頁面發出的 E7 恰 1 次、帶 `Idempotency-Key`、body `{reason:"該週檔期已滿"}` → 200 `{orderId, status:"CANCELLED", cancelledBy:"SITTER"}` | 截圖 + API JSON |
| QR-05 | AC-1.5-1 | sitterA | 點 `sitter-order-decline-btn-referral` | 進入轉介選單（PRD-010；元素依 PRD-010 定義對應），選單內可見 `sitterPartner` | 訂單仍 `CANCELLED` | 截圖 |
| QR-06 | AC-1.1-1（BR-10、BR-12） | owner、sitterA | 讀 E2、E10、`inspect(ordD1)` | — | 飼主 E2：`status=CANCELLED`、`cancellation.cancelledBy=SITTER`、`reasonCode=SITTER_DECLINED`、`reason=該週檔期已滿`、`visits` 3 筆皆 `CANCELLED`、`payments=[]`；保母 E10 `[]`；`inspect.orderLogs` 恰 1 筆 `ORDER_REJECTED`：`operatorRole=SITTER`、payload `reason=該週檔期已滿`、`transitions[]` 含 ORDER `PENDING→CANCELLED` 與 3 筆 VISIT `PENDING→CANCELLED` | API JSON |
| QR-07 | AC-1.1-1（BR-2） | owner、sitterA | `inspect(ordD1).notifications`；`notificationText` 雙方 | — | 恰 2 則：`BOOKING_DECLINED`（收件者 `owner`）、`BOOKING_DECLINED_SELF`（收件者 `sitterA`），皆 `mandatory=false`、`emailStatus` = 需寄送；飼主內文「預約已婉拒」並含「該週檔期已滿」、連結 `/owner/orders/{ordD1}`；保母內文「您已婉拒 王小明 的預約」、連結 `/sitter/orders/{ordD1}` | API JSON |
| QR-08 | AC-1.1-1 | owner | 開 `/owner/orders/{ordD1}` | `order-status-badge`「已取消」；`owner-order-detail-cancel-result` 含標題「保母已婉拒此預約」與「該週檔期已滿」；無 `owner-order-detail-btn-withdraw`、無付款區塊 | — | 截圖 |
| QR-09 | AC-1.1-1 | sitterA | 開 `/sitter/orders` 點 `sitter-orders-tab-history`，再開該訂單 | 歷史分頁有 `sitter-order-card-{ordD1}`；`sitter-orders-tab-evaluating` 下沒有；訂單頁無 `sitter-order-eval-btn-quote-original`、`-btn-quote-adjust`、`-btn-decline` | E4 `tab=HISTORY` 含 `ordD1`、`tab=EVALUATING` 不含；E2 `allowedActions` 不含 `DECLINE`、`QUOTE` | 截圖 + API JSON |
| QR-10 | AC-1.5-3 | sitterA | `ordD2` 婉拒（快速帶入「超出服務範圍」）成功後直接關閉對話框；重開 `/sitter/orders/{ordD2}`（歷史） | 歷史訂單頁無 `sitter-order-decline-btn-referral` | `inspect(ordD2).notifications` 只有 `BOOKING_DECLINED`、`BOOKING_DECLINED_SELF` 兩則；`notificationText(sitterPartner, ordD2)` 無任何通知 | 截圖 + API JSON |
| QR-11 | AC-1.1-2 | ownerMuted、sitterMuted | `ordM = createPendingOrder(ownerMuted, sitterMuted, 標準)`；E7 `{reason:"該週檔期已滿"}` | — | 200；`inspect(ordM).notifications` 恰 2 則（雙方各 1），皆 `emailStatus` = 偏好略過 | API JSON |

### 2.2 原因驗證與婉拒被拒（describe「婉拒被拒」：`sitterA`）

> 每次呼叫用新 key；除另註外每列結尾斷言 **不變**（訂單仍 `PENDING`、無 `ORDER_REJECTED`、無婉拒通知）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-12 | AC-1.2-4（EX-5） | sitterA | `ordV`；E7 依序 `reason:""`、`reason:"  \t  "`、`reason:"　 "`（U+3000 + U+00A0）、`reason:` 前後空白 + 201 字 | — | 皆 400 `VALIDATION_FAILED`、`payload.field=reason` | API JSON |
| QR-13 | AC-1.2-4（BR-9 邊界） | sitterA | `ordV` E7 `reason:` 前後各 1 個 U+3000 + 恰 200 字（含「🐱」） | — | 200、`status=CANCELLED`；E2 `cancellation.reason` = strip 後的 200 字；`inspect.orderLogs[ORDER_REJECTED].payload.reason` 同 | API JSON |
| QR-14 | AC-1.3-1 | sitterA | `ordQ = createQuotedOrder(…)`；E7 | — | 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=PENDING_PAYMENT`；E10 仍 1 筆 `UNPAID` | API JSON |
| QR-15 | AC-1.3-1 | owner、sitterA | `ordW`：飼主 E8 `{expectedStatus:"PENDING"}` 撤回；保母 E7 | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E2 `cancellation.cancelledBy=OWNER` 不變；`inspect.orderLogs` 只有 `ORDER_WITHDRAWN` | API JSON |
| QR-16 | AC-1.3-1 | sitterA | `ordX`：`shiftTime(REVIEW_EXPIRES_AT)` → `runCron(C1, ordX)` → E7 | — | C1 200、`processed=1`、`skipped=0`、`failed=0`；E2 `cancelledBy=SYSTEM`；E7 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；`inspect.orderLogs` 無 `ORDER_REJECTED` | API JSON |
| QR-17 | AC-1.3-2（EX-1） | sitterOther | `ordR`；E7 `{reason:"x"}` | — | 403 `FORBIDDEN` | API JSON |
| QR-18 | AC-1.3-3（EX-2） | sitterA（OWNER 角色） | 以 `ownerToken(sitterA)` E7 | — | 403 `ROLE_MISMATCH` | API JSON |
| QR-19 | AC-1.3-4（EX-3） | admin | E7 | — | 403 `ROLE_MISMATCH` | API JSON |
| QR-20 | AC-1.3-5（EX-4） | sitterA | E7 執行時產生的隨機 UUID | — | 404 `NOT_FOUND` | API JSON |
| QR-21 | AC-1.3-6（EX-5） | sitterA | `ordR` E7 不帶 `Idempotency-Key` | — | 400 `VALIDATION_FAILED`、`payload.field=Idempotency-Key`；QR-17–QR-21 後 `ordR` **不變** | API JSON |

### 2.3 婉拒重送、併發與鎖定（describe「婉拒冪等併發」）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-22 | AC-1.4-2（EX-14） | sitterA | `ordI`；E7 key `K` `{reason:"檔期已滿"}` → 回應 `B1`；同 key 同 body 再送 | — | 第二次 200、body 與 `B1` 相同；`inspect.orderLogs` 恰 1 筆 `ORDER_REJECTED`；`inspect.notifications` 仍 2 則 | API JSON |
| QR-23 | （EX-14） | sitterA | key `K`、`{reason:"其他"}` | — | 422 `IDEMPOTENCY_KEY_REUSED` | API JSON |
| QR-24 | AC-1.4-1（EX-11） | owner、sitterA | 3 輪，每輪新訂單：同時送出飼主 E8 `{expectedStatus:"PENDING"}` 與保母 E7 `{reason:"檔期已滿"}` | — | 每輪恰一方 200、另一方 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E2 `cancellation.cancelledBy` = 勝方；`inspect.orderLogs` 恰 1 筆取消類 log（`ORDER_WITHDRAWN` 或 `ORDER_REJECTED`，與勝方一致）；通知只有勝方那組（`BOOKING_WITHDRAWN` / `_SELF` 或 `BOOKING_DECLINED` / `_SELF`）。誰勝不固定，確定性覆蓋見 §4 | API JSON（每輪） |
| QR-25 | AC-1.6-1（EX-15） | sitterLock | `ordL = createPendingOrder(owner, sitterLock, 標準)` → upsert 鎖定 → 開評估頁婉拒（原因「暫停接案」） | `sitter-order-eval-precheck-banner` 顯示鎖定文案，`sitter-order-eval-btn-decline` 仍 enabled；婉拒後出現轉介入口 | E5 `precheck.subscriptionLocked=true`；E7 200；E2 `cancelledBy=SITTER`、行程皆 `CANCELLED`；`inspect` 有 `ORDER_REJECTED` 與雙方各 1 則通知 | 截圖 + API JSON |

### 2.4 期限後婉拒（describe「期限後婉拒」）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-26 | AC-1.7-1（EX-9） | sitterA | `ordL1`；`shiftTime(REVIEW_EXPIRES_AT)`；開評估頁婉拒（原因「檔期已滿」） | 評估頁顯示「此預約已超過評估期限」、三顆報價按鈕停用，`sitter-order-eval-btn-decline` 仍 enabled | E5 `precheck.reviewExpired=true`、`status=PENDING`；E7 200；E2 `cancelledBy=SITTER`、`reasonCode=SITTER_DECLINED`、行程皆 `CANCELLED`（不回 `DEADLINE_PASSED`）；`inspect.orderLogs` 為 `ORDER_REJECTED` | 截圖 + API JSON |
| QR-27 | AC-1.7-1（EX-12） | 排程 | `runCron(C1, ordL1)` | — | 200、`processed=0`、`skipped=0`、`failed=0`；E2 `cancellation` 與 QR-26 後完全相同（含 `cancelledAt`）；`inspect.orderLogs` 無 `ORDER_REVIEW_EXPIRED`；`inspect.notifications` 無 `REVIEW_EXPIRED_OWNER` / `REVIEW_EXPIRED_SITTER` | API JSON |
| QR-28 | AC-1.7-2（EX-12） | sitterA | `ordL2`；`shiftTime` → `runCron(C1, ordL2)` → E7 | — | C1 `processed=1`；E2 `cancelledBy=SYSTEM`；E7 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E2 `cancelledBy` 仍 `SYSTEM`；`inspect.orderLogs` 只有 `ORDER_REVIEW_EXPIRED` | API JSON |

### 2.5 評估逾期 O4（describe「評估逾期」：`ordE = createPendingOrder(owner, sitterA, [D(10), D(11)])`）

> 步驟依序執行：先驗未到期不處理、非排程身分被拒，再平移後正式觸發。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-30 | AC-2.2-1 | 排程 | 未平移，`runCron(C1, ordE)` | — | 200、`processed=0`、`skipped=0`、`failed=0`；E2 `status=PENDING`、2 個行程 `PENDING`；`inspect.notifications` 無逾期通知 | API JSON |
| QR-31 | AC-2.4-2（EX-20） | 一般使用者 | `shiftTime(ordE, REVIEW_EXPIRES_AT)`；依序以 `sitterToken(sitterA)`、`ownerToken(owner)`（皆不帶 secret）、不帶任何標頭、`X-Internal-Secret: wrong` 呼叫 `/api/internal/cron/orders/review-expiry?orderId={ordE}` | — | 四次皆 403 `FORBIDDEN`（§6.1 錯誤格式）；之後 E2 `status=PENDING`、`inspect.orderLogs` 無 `ORDER_REVIEW_EXPIRED` | API JSON |
| QR-32 | AC-2.1（BR-4、BR-12、BR-15） | 排程 | `runCron(C1, ordE)` | — | 200：`job`、`processed=1`、`skipped=0`、`failed=0`、`startedAt`、`finishedAt`；E2：`status=CANCELLED`、`cancellation.cancelledBy=SYSTEM`、`reasonCode=REVIEW_EXPIRED`、`reason=保母未於期限內回覆`、2 個行程 `CANCELLED`、`payments=[]`；`inspect.orderLogs` 恰 1 筆 `ORDER_REVIEW_EXPIRED`（`operatorRole=SYSTEM`、`operatorUserId` 不輸出、payload `reviewExpiresAt`、`transitions[]` 含 2 筆 VISIT） | API JSON |
| QR-33 | AC-2.1（BR-2） | owner、sitterA | `inspect(ordE).notifications`；`notificationText` 雙方 | — | 恰 2 則：`REVIEW_EXPIRED_OWNER`（`owner`）、`REVIEW_EXPIRED_SITTER`（`sitterA`），`mandatory=false`、`emailStatus` = 需寄送；內文分別「保母未於期限內回覆，預約已自動取消」「預約已逾期自動取消」 | API JSON |
| QR-34 | AC-2.1 | owner | 開 `/owner/orders/{ordE}` | `owner-order-detail-cancel-result` 標題「保母未於期限內回覆，預約已自動取消」 | — | 截圖 |
| QR-35 | AC-2.4-1 | 排程 | 再 `runCron(C1, ordE)` | — | 200、`processed=0`、`skipped=0`、`failed=0`；E2 `cancellation.cancelledAt` 與 QR-32 後相同；`inspect` 的 `ORDER_REVIEW_EXPIRED` 仍 1 筆、逾期通知仍 2 則 | API JSON |

### 2.6 付款前保母取消 O7（describe「付款前取消」：`sitterA`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-40 | AC-3.1 | sitterA | `ordC = createQuotedOrder(owner, sitterA, 標準)`；開 `/sitter/orders/{ordC}`，點 `sitter-order-detail-btn-cancel` | 對話框含 `sitter-order-cancel-input-reason`、`sitter-order-cancel-btn-quick-reason-1`–`4`（文字同 QR-01）、文字「取消後飼主將收到通知，含您填寫的原因」 | E2 `allowedActions` 含 `SITTER_CANCEL` | 截圖 + API JSON |
| QR-41 | AC-3.1 | sitterA | 填「臨時生病無法服務」，點 `sitter-order-cancel-btn-confirm` | 成功畫面無 `sitter-order-decline-btn-referral`（BR-13） | 頁面發出的 E9 恰 1 次、帶 key → 200 `{status:"CANCELLED", cancelledBy:"SITTER"}` | 截圖 + API JSON |
| QR-42 | AC-3.1（BR-10、BR-12） | owner、sitterA | 讀 E2、E10、`inspect(ordC)` | — | E10 INITIAL `status=VOID`、`voidedAt` 有值；E2 `cancellation.cancelledBy=SITTER`、`reasonCode=SITTER_CANCELLED`、`reason=臨時生病無法服務`、3 個行程 `CANCELLED`；`inspect.orderLogs` 恰 1 筆 `ORDER_CANCELLED_BY_SITTER`（payload `reason`、`paymentId`、`transitions[]` 含 PAYMENT `UNPAID→VOID`） | API JSON |
| QR-43 | AC-3.1（BR-2） | owner、sitterA | `inspect(ordC).notifications`；`notificationText` 雙方 | — | 取消類恰 2 則：`SITTER_CANCELLED`（`owner`）、`SITTER_CANCELLED_SELF`（`sitterA`），`mandatory=false`、`emailStatus` = 需寄送；飼主內文「保母已取消訂單」並含「臨時生病無法服務」；保母內文「您已取消訂單」 | API JSON |
| QR-44 | AC-3.1 | owner | 開 `/owner/orders/{ordC}` | 無 `proof-file-input`、`proof-submit-btn`（付款入口消失）；`owner-order-detail-cancel-result` 標題「保母已取消此訂單」並含「臨時生病無法服務」 | — | 截圖 |
| QR-45 | AC-3.2-1（EX-8） | owner → sitterA | `ordC2 = createQuotedOrder(…)`；`uploadProof`；保母開 `/sitter/orders/{ordC2}` | 開頁即顯示 `sitter-order-cancel-blocked-message`「飼主已上傳付款憑證，請先核對或駁回」與前往核對區塊（`btn-verify-payment` / `btn-reject-payment` 所在處）的連結；不出現 `sitter-order-detail-btn-cancel` | E2 `allowedActions` 不含 `SITTER_CANCEL`；直接呼叫 E9 `{reason:"臨時有事"}` → 409 `PAYMENT_ALREADY_SUBMITTED`；E10 `status=SUBMITTED`；E2 `status=PENDING_PAYMENT`、行程 `PENDING`；`inspect.orderLogs` 無 `ORDER_CANCELLED_BY_SITTER` | 截圖 + API JSON |
| QR-46 | AC-3.2-2 | sitterA | 點 `btn-reject-payment`，`reject-reason-input` 填「金額不符」，`reject-submit-btn` 送出；重新整理後取消（原因「臨時有事」） | 駁回後 `sitter-order-cancel-blocked-message` 消失、`sitter-order-detail-btn-cancel` 出現；取消成功 | E15 200、`paymentStatus=UNPAID`；E9 200；E10 `status=VOID`；E12 該 submission `status=REJECTED`、`reviewedAt` 有值、`voided=false` | 截圖 + API JSON |
| QR-47 | AC-3.3-1（EX-7） | owner、sitterA | `ordC3 = createQuotedOrder(…)`；保母先開 `/sitter/orders/{ordC3}` 並打開取消對話框；另以 API `uploadProof` + E14 確認 → `CONFIRMED`；回頁面填原因送出 | 「訂單已成立，如需取消請申請訂單變更」 | 頁面發出的 E9 → 409 `ORDER_STATE_CONFLICT`、`currentStatus=CONFIRMED`；E2 `status=CONFIRMED` | 截圖 + API JSON |
| QR-48 | AC-3.3-2（EX-7） | sitterA | `ordC4 = createPendingOrder(…)`；E9 | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=PENDING`；**不變** | API JSON |
| QR-49 | AC-3.3-2（EX-7） | owner、sitterA | `ordC5 = createQuotedOrder(…)`；保母先開詳情頁並打開取消對話框；飼主 E8 `{expectedStatus:"PENDING_PAYMENT"}` 撤回；保母送出 | 「訂單狀態已變更，請重新整理」 | 頁面發出的 E9 → 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E2 `cancelledBy=OWNER` | 截圖 + API JSON |
| QR-50 | AC-3.4-1（EX-1） | sitterOther | `ordC6 = createQuotedOrder(…)`；E9 | — | 403 `FORBIDDEN` | API JSON |
| QR-51 | AC-3.4-2（EX-2） | sitterA（OWNER 角色） | `ownerToken(sitterA)` E9 | — | 403 `ROLE_MISMATCH` | API JSON |
| QR-52 | AC-3.4-3（EX-3） | admin | E9 | — | 403 `ROLE_MISMATCH` | API JSON |
| QR-53 | AC-3.4-4（EX-5、BR-9） | sitterA | E9 依序 `reason:""`、`reason:"   "`、`reason:"　 "`（U+3000 + U+00A0）、前後空白 + 201 字 | — | 皆 400 `VALIDATION_FAILED`、`payload.field=reason` | API JSON |
| QR-54 | AC-3.4-5（EX-5） | sitterA | E9 不帶 `Idempotency-Key` | — | 400 `VALIDATION_FAILED`、`payload.field=Idempotency-Key`；QR-50–QR-54 後 `ordC6` **不變**（`PENDING_PAYMENT`、`UNPAID`） | API JSON |
| QR-55 | AC-3.5（EX-10） | owner、sitterA | 3 輪，每輪新 `createQuotedOrder`：同時送出飼主 E11 上傳與保母 E9 | — | 每輪恰一個成立。上傳勝：E9 409 `PAYMENT_ALREADY_SUBMITTED`、E10 `SUBMITTED`、E2 `PENDING_PAYMENT`、`orderLogs` 有 `PAYMENT_PROOF_SUBMITTED` 無 `ORDER_CANCELLED_BY_SITTER`。取消勝：E11 409 `PAYMENT_STATE_CONFLICT`、E10 `VOID`、E2 `CANCELLED`、E12 `[]`、`orderLogs` 有 `ORDER_CANCELLED_BY_SITTER` 無 `PAYMENT_PROOF_SUBMITTED`。任何一輪 E12 都不得出現 `voided=true`。GCS 物件刪除見 §4 | API JSON（每輪） |
| QR-56 | AC-3.6-1（EX-14） | sitterA | `ordC7`；E9 key `K` → `B1`；同 key 同 body 再送；key `K` 改原因再送 | — | 第二次 200、body = `B1`、E10 仍 1 筆 `VOID`（`voidedAt` 不變）、`inspect` 的 `ORDER_CANCELLED_BY_SITTER` 仍 1 筆、取消類通知仍 2 則；第三次 422 `IDEMPOTENCY_KEY_REUSED` | API JSON |
| QR-57 | AC-3.6-2（EX-15） | sitterLock | `ordC8 = createQuotedOrder(owner, sitterLock, 標準)` → upsert 鎖定 → E9 | — | 200；E10 `VOID`；E2 `cancelledBy=SITTER` | API JSON |
| QR-58 | AC-3.7-1（EX-9、EX-12） | sitterA、排程 | `ordC9 = createQuotedOrder(…)`；`shiftTime(PAYMENT_DUE_AT, paymentId)` → E9；接著 `runCron(C3, ordC9)` | — | 平移後 E10 `dueAt` < `serverTime`；E9 200、`cancelledBy=SITTER`、`reasonCode=SITTER_CANCELLED`；C3 200、`processed=0`、`skipped=0`、`failed=0`；E2 `cancellation` 不變；`inspect` 無 `PAYMENT_EXPIRED` log 與通知 | API JSON |
| QR-59 | AC-3.7-2（EX-12） | sitterA、排程 | `ordC10 = createQuotedOrder(…)`；`shiftTime(PAYMENT_DUE_AT)` → `runCron(C3, ordC10)` → E9 | — | C3 `processed=1`；E2 `cancelledBy=SYSTEM`、`reasonCode=PAYMENT_EXPIRED`；E9 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；`cancelledBy` 仍 `SYSTEM`；`inspect.orderLogs` 只有 `PAYMENT_EXPIRED`、無 `ORDER_CANCELLED_BY_SITTER` | API JSON |

### 2.7 模組（mock）：真後端造不出的回應

> 檔案 `frontend/e2e/order-decline-cancel.spec.ts`；`mockDemoSession(page, 'SITTER')`，E2 / E5 回 `PENDING` 或 `PENDING_PAYMENT` 訂單；`afterEach` 呼叫 `assertNoUnmockedApi`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QRM-01 | AC-1.5-2（EX-16） | sitter | mock E7 200；mock 轉介送出 API（PRD-010）回 500；婉拒 → 點 `sitter-order-decline-btn-referral` → 選一位夥伴送出 | 「轉介未送出，請稍後再試」，可再按送出；訂單狀態顯示「已取消」 | 重試時再次送出轉介請求；E7 只發出 1 次（婉拒不回滾、不重送） | 截圖 + 請求紀錄 |
| QRM-02 | （EX-13） | sitter | mock E7 連續兩次 409 `ORDER_BUSY` + `Retry-After: 1`；送出婉拒 | 「系統忙碌，請稍後再試」 | 恰 2 次 E7，`Idempotency-Key` 相同 | 截圖 + 請求紀錄 |
| QRM-03 | （EX-7） | sitter | mock E9 回 409 `ORDER_STATE_CONFLICT`、`currentStatus=IN_PROGRESS`；送出取消 | 「訂單已成立，如需取消請申請訂單變更」 | — | 截圖 |
| QRM-04 | （EX-14） | sitter | mock E7 回 422 `IDEMPOTENCY_KEY_REUSED` | 「請重新操作」 | — | 截圖 |
| QRM-05 | （EX-1、EX-2、EX-4） | sitter | mock E7 分別回 403 `FORBIDDEN`、403 `ROLE_MISMATCH`、404 `NOT_FOUND` | 依序「無權限操作此訂單」「請切換至保母身分」「找不到此訂單」 | — | 截圖 |

## 3. 權限與例外（判斷順序與破壞性測試）

> 驗證 PRD-027 BR-14：同時違反兩條規則時回應必須是順序在前者。每列用新訂單、新 key。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| QR-E1 | （§3 未登入） | 不帶 token 呼叫 E7、E9 | 皆 401 `UNAUTHENTICATED`；訂單 **不變** |
| QR-E2 | EX-6 | 保母開 `ordS` 評估頁並打開婉拒對話框；飼主 E8 撤回；保母填原因送出 | E7 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；畫面「訂單狀態已變更，請重新整理」 |
| QR-E3 | BR-14（400 先於 409） | 已 `CANCELLED` 的訂單，E7 `reason:""` | 400 `VALIDATION_FAILED`、`field=reason` |
| QR-E4 | BR-14（`ROLE_MISMATCH` 先於 404） | `ownerToken(sitterA)` 對隨機 UUID 呼叫 E7 | 403 `ROLE_MISMATCH` |
| QR-E5 | BR-14（`FORBIDDEN` 先於 400） | `sitterOther` 對 `sitterA` 的訂單呼叫 E7，`reason:""` | 403 `FORBIDDEN` |
| QR-E6 | BR-14（`ORDER_STATE_CONFLICT` 先於 `PAYMENT_ALREADY_SUBMITTED`） | E9 打 `PENDING` 訂單（無 payment）與 `CONFIRMED` 訂單（payment `VERIFIED`） | 皆 409 `ORDER_STATE_CONFLICT`（`currentStatus` 分別 `PENDING`、`CONFIRMED`），不是 `PAYMENT_ALREADY_SUBMITTED` |
| QR-E7 | （資料污染） | 婉拒原因填 `<script>alert(1)</script>` | 200；飼主 `owner-order-detail-cancel-result` 原樣顯示該字串為文字、未觸發 dialog；通知內文為原字串 |
| QR-E8 | EX-12（C1 只取 `PENDING`） | `createQuotedOrder` 後 `shiftTime(REVIEW_EXPIRES_AT)` → `runCron(C1, 該單)` | C1 `processed=0`；訂單仍 `PENDING_PAYMENT`、payment `UNPAID` |
| QR-E9 | BR-14（401 先於冪等回放） | QR-22 的 key `K` 與 body，不帶 token 重送 | 401 `UNAUTHENTICATED`（不得回放 200） |
| QR-E10 | （排程端點範圍） | `runCron(C1, 隨機 UUID)`（非測試帳號訂單的 404 由 `InternalTestDataIntegrationTest` 覆蓋，E2E 拿不到正式訂單 id） | 404 `NOT_FOUND`，不處理任何資料 |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.1、AC-2.1、AC-3.1 的 Email **實際寄達** | Resend 為測試模式只寄專案擁有者；E2E 只斷言 `inspect.notifications[].emailStatus` | SD-NOTIFY C11 測試；真實寄信手動驗證 |
| AC-1.4-1 確定性 | 正式站無法控制交錯 | `SitterDeclineIntegrationTest`、`OrderWithdrawConcurrencyIntegrationTest` |
| AC-1.6-2（FREE 本月已 3 筆 `CONFIRMED` 仍可婉拒） | 需造 3 筆 `CONFIRMED`（各含 GCS 上傳）；同樣前置已在 TS-006 Q-80–Q-82 驗證額度計數，本卡只差「婉拒不檢查額度」 | `SitterDeclineIntegrationTest` |
| AC-1.7、AC-3.7 字面時刻（`2026-12-10T16:03:00Z` 等） | 正式站無法注入 `Clock`；E2E 以平移驗證相對關係 | `SitterDeclineIntegrationTest`、`SitterCancelIntegrationTest` |
| AC-2.2-1 字面時刻（15:59）、AC-2.2-2（`now = review_expires_at` 整點即處理） | 需精確時間 | `ReviewExpiryCronIntegrationTest` |
| AC-2.3-1、AC-2.3-2（候選查出後狀態改變而略過、單筆例外 `failed=1` 其他照常、下輪重試） | 需控制候選查詢與處理之間的時間窗、注入例外；帶 `orderId` 時批次只有一筆 | `ReviewExpiryCronIntegrationTest` |
| AC-2.4-2 log 不含 secret | 無法讀伺服器 log | `InternalCronSecurityTest` |
| AC-3.5 取消先到時「該次上傳的 GCS 物件已刪除」 | 需列 GCS bucket，E2E 無權限 | `PaymentSubmissionConcurrencyIntegrationTest`；孤兒兜底 `OrphanProofSweepIntegrationTest` |
| EX-12 排程與保母操作「同時」 | 需精準交錯 | `SitterDeclineIntegrationTest`、`SitterCancelIntegrationTest`（循序版本由 QR-26–QR-28、QR-58、QR-59 覆蓋） |
| EX-13 鎖等待逾時 409 `ORDER_BUSY`（後端）；排程鎖不到計 `skipped` | 正式站無法持有列鎖 | `SitterDeclineIntegrationTest`、`SitterCancelIntegrationTest`、`ReviewExpiryCronIntegrationTest`；畫面由 QRM-02 |
| EX-14 同 key 處理中 409 `IDEMPOTENCY_IN_PROGRESS` | 需控制處理中時間窗 | SD-ORDER §10.2 冪等共通必測 |
| EX-16 轉介 API 本身失敗的後端行為 | 屬 PRD-010 | TS-010（PRD-010 改寫後）；本卡畫面由 QRM-01 |
| EX-17 Resend 失敗 | 正式站無法注入故障 | SD-NOTIFY C11 測試 |
| EX-18 GCS | 本卡自身無上傳；併發刪物件見 AC-3.5 列 | — |
| EX-19 排程單筆失敗 | 需注入例外 | `ReviewExpiryCronIntegrationTest` |

## 5. 已知限制 / 待補

| # | 項目 | 影響 | 建議預設值 / 處理 |
|---|------|------|------------------|
| 1 | （已解決 2026-11-22）provision 雙角色 / ADMIN / `displayName` | — | SD-ORDER §10.4 |
| 2 | （已解決）`sitter-setup` upsert | — | SD-ORDER §10.4 |
| 3 | `inspect.notifications[].emailStatus` 的列舉值 SD-ORDER 未列，屬 SD-NOTIFY；保母端偏好已可由 `sitter-setup.emailPreferences` 設定（原 #3 已解決） | QR-07、QR-11、QR-33、QR-43 的 Email 斷言 | SD-NOTIFY 撰寫時定義；e2e-author 屆時對應 |
| 4 | PRD-010 為 Draft，轉介選單與轉介送出 API 未定義 | QR-05、QRM-01 只能以業務名稱描述 | PRD-010 改寫後回填 testid / API |
| 5 | （已解決）blocked message 開頁即顯示、取消鈕隱藏 | — | PRD-027 回寫；QR-45、QR-46 |
| 6 | （已解決）E7 / E9 原因 strip 後計數、存 strip 值 | — | SD-ORDER E7 / E9；QR-13、QR-53 |
| 7 | （已解決）`ORDER_BUSY`、`SKIP LOCKED` 後端類別 | — | SD-ORDER §10.3 |
| 8 | （已解決）正式排程競爭 | — | 自動觸發跳過測試帳號、`?orderId=` 精確計數 |
| 9 | （已解決）全形空白 strip | — | SD-GLOBAL-SPEC §2.7；QR-02、QR-12、QR-53 |
| 10 | （已解決）O4 / O7 飼主取消結果標題 | — | QR-34、QR-44 |
| 11 | 通知標題 / 內文不在 `inspect` 內，仍需 SD-NOTIFY 通知查詢 API（未撰寫） | QR-07、QR-10、QR-33、QR-43 內文斷言 | SD-NOTIFY 定義後回填路徑 |
| 12 | （已解決）`order_logs` 讀取 | — | `inspect.orderLogs` |
| 13 | （已解決）信任圈夥伴造資料 | — | `sitter-setup.trustCirclePartnerIds`；QR-05、QR-10 |

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依核准後修訂更新：`order_logs`、通知類型 / `mandatory` / `emailStatus`、`voidedAt` / `reviewedAt` 改由 `inspect` / E10 / E12 在 E2E 斷言（QR-06、QR-07、QR-11、QR-13、QR-15、QR-16、QR-22、QR-24–QR-28、QR-32、QR-33、QR-35、QR-42、QR-43、QR-45、QR-46、QR-55、QR-56、QR-58、QR-59），§4 移除對應後端列；C1 / C3 一律帶 `orderId` 並斷言精確計數，刪除「避開正式排程」因應（QR-16、QR-27、QR-28、QR-30–QR-32、QR-35、QR-58、QR-59、QR-E8）；新增 QR-34（O4 飼主取消結果標題）、QR-E9（401 先於冪等回放）、QR-E10（排程 `orderId` 範圍）；QR-44 加 O7 標題「保母已取消此訂單」；QR-45 / QR-46 改為 blocked message 開頁顯示、取消鈕隱藏；QR-26 改用 `precheck.reviewExpired` 走 UI；strip 含 U+3000 / U+00A0（QR-02、QR-12、QR-13、QR-53）；provision `roles[]` / `displayName`、`sitter-setup` 的 `emailPreferences` / `trustCirclePartnerIds`；§5 標註已解決項 | SD-ORDER / PRD-027 核准後修訂（commit 477c75d）；使用者決定 2026-11-22 |
| 2026-11-22 | 建立，狀態 Draft。原 TS-006 Scenario 10（婉拒）依 PRD 拆卡移入並依新規格重寫（原因必填、通知雙方、兩步驟轉介、期限後仍可婉拒） | PRD-027、SD-ORDER Approved 2026-11-22 |
