# TS-006: 保母訂單評估、事前問卷與報價

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-006（Approved 2026-11-22，含同日核准後回寫）；前置資料另用 PRD-005（送單）、PRD-007（上傳 / 核對憑證）、PRD-026（飼主撤回）、PRD-002 / PRD-003 / PRD-004 / PRD-025（毛孩、方案、問卷、收款帳號，以 `sitter-setup` / `owner-setup` upsert 造資料） |
| 對應 SD | SD-ORDER（Approved 2026-11-22）：E1、E2、E5、E6、E8、E10、E11、E14、E25、E31、C2、§6.3 排程端點（`orderId`）、§6.5 錯誤碼、§7.1 `order_logs`、§7.2 通知、§9.1 / §9.3 testid、§10.4 測試支援端點（含 `inspect`）；SD-FULFILL FT1（造 COMPLETED 訂單） |
| 類型 | 旅程（打真後端，§2.1–§2.11、§3）＋ 模組（mock，只有 §2.12 的 QM 列：正式站無法造出的錯誤回應與前端重試行為） |
| 自動化路徑 | 旅程 `frontend/e2e/journeys/order-evaluation-quote.spec.ts`；模組 `frontend/e2e/order-eval.spec.ts`（改寫，須 `assertNoUnmockedApi`） |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> 報價是狀態轉移 + 權限 + 冪等 + 跨角色（飼主撤回）互動，主體用旅程型。模組型只補「真後端造不出來的回應」對畫面的影響。
> PRD-006 AC 內「情境 n」逐列對應為 `AC-x.y-n`。

---

## 0. 測試邏輯（Given / When / Then）

- **Given**：每個 `test.describe` 自己 provision 飼主與保母（`@e2e-journey.test`），以 E1 建立 `PENDING` 訂單；方案、訂閱等級、收款帳號、問卷、毛孩由 `sitter-setup` / `owner-setup`（upsert）造好與修改。
- **When**：保母在 `/sitter/orders/{orderId}` 檢視評估頁、原價接單或調價報價；或以其他身分 / 錯誤內容直接呼叫 E5、E6。
- **Then**：畫面依 PRD-006 §4 顯示；E6 成功時訂單 `PENDING → PENDING_PAYMENT`、建立唯一一筆 INITIAL payment、期限依 baseline §5 公式、`inspect` 可見一筆 `ORDER_QUOTED` 與一則報價通知；失敗時回 PRD-006 §4.3 判斷順序中第一個不符的錯誤碼，訂單維持 `PENDING`、無 payment、無 log、無通知。

## 1. 前置資料

> 不寫死 UUID：訂單、毛孩、方案、題目 id 一律取自建立時的回應（`sitter-setup` / `owner-setup` 以請求的 `key` 回傳 id）。每個 describe 可獨立重跑。

**記號**

| 記號 | 意義 |
|------|------|
| `D(n)` | E5 / E31 回傳 `serverTime` 換算的 Asia/Taipei 今天 + n 日（`YYYY-MM-DD`） |
| `TPE0(d)` | 日期 d 的 Asia/Taipei 00:00，以 UTC 表示 = 前一日 `T16:00:00Z` |
| `T` | E6 回應的 `quotedAt` |
| `H(x)` | x 小時；`URGENT_WINDOW_HOURS` = 2（baseline §5） |
| 期限公式 | `dueAt = max(TPE0(首日) − H(24), T + H(2))`；`escalateAt = max(TPE0(首日), dueAt) + H(2)`；`reviewExpiresAt = max(TPE0(首日), 送單時間 + H(2))`（baseline §5） |
| 金額 | 畫面一律 SD-FRONTEND-SPEC §6.4.1 格式 `$ 1,800`（`$` 後為 U+00A0；Playwright `toHaveText` 會正規化空白）；PRD 的「NT$」字面同此格式 |
| `ownerToken(X)` / `sitterToken(X)` | 雙角色帳號 X 以 `currentRole=OWNER` / `SITTER` 取得的 token |
| **未寫入** | 飼主 E2 `status=PENDING`、保母 E10 回 `[]`、`inspect.orderLogs` 無 `ORDER_QUOTED`、`inspect.notifications` 無報價類通知 |
| 需寄送 / 偏好略過 | `inspect.notifications[].emailStatus` 表示「會寄 Email」或「依偏好不寄」的值（列舉由 SD-NOTIFY 定義，見 §5 #3） |

**帳號**

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `POST /api/internal/test-data/provision-account`（`@e2e-journey.test`、`displayName=王小明`、`roles=[OWNER]`）＋ `owner-setup`：`pets` = key `M`、`N`（CAT）、`emailPreferences.ORDER_AFFAIR = 開` | — |
| `ownerMuted` | OWNER | 同上，`ORDER_AFFAIR = 關`，`pets` = key `K` | — |
| `owner2` | OWNER | 同 `owner`，`pets` = key `L` | 熟客對照組 |
| `sitterPro` | OWNER + SITTER | provision（`roles=[OWNER, SITTER]`）＋ `sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄測試值（記為 `ACC`）、`plans` = key `P`（「到府餵食」，單價 600，`active`）、`questions` = key `Q1` 單行文字、`Q2` 單選、`Q3` 多選（皆必填、啟用） | 雙角色供 `ROLE_MISMATCH` |
| `sitterFree` | SITTER | 同上但 `roles=[SITTER]`、`tier=FREE`、`questions = []` | 本月 0 筆 `CONFIRMED` |
| `sitterQuota` | SITTER | 同 `sitterFree` | §2.10 專用 |
| `sitterUlt` | SITTER | 同 `sitterPro` 但 `tier=ULTIMATE`、`questions = []` | — |
| `sitterNoPay` | SITTER | 同 `sitterPro` 但 `payoutAccount = null`、`questions = []` | — |
| `sitterLock` | SITTER | 同 `sitterPro`、`questions = []`；建好訂單後以 `sitter-setup` upsert `subscriptionExpiresAt = D(-2)` 或 `suspended = true` | 先可接單，後鎖定 |
| `sitterOther` | SITTER | 同 `sitterPro` | 越權用 |
| `admin` | ADMIN | provision-account `roles=[ADMIN]` | — |

**共用 helper（e2e-author 實作）**

| helper | 內容 |
|--------|------|
| `createPendingOrder(owner, sitter, groups, answers?)` | E31 `GET /api/sitters/{sitterId}/booking-context` 取方案、題目與 `serverTime` → E1 `POST /api/orders`（新 `Idempotency-Key`，`expectedAmount` = Σ 單價 × 日數 × `dailyCount`）→ 斷言 201、`status=PENDING` |
| `quote(sitter, order, body, key?)` | E6 `POST /api/orders/{orderId}/quote`，`Idempotency-Key` 預設新 UUID |
| `payAndConfirm(owner, sitter, order)` | E10 取 INITIAL `paymentId` → E11 上傳（`disclaimerAgreed=true`、`lastFive=12345`、1×1 PNG）→ E14（`submissionId`）→ 斷言 `orderStatus=CONFIRMED` |
| `completeOrder(owner, sitter, order)` | `payAndConfirm` → 每個行程 SD-FULFILL FT1 `force-status`（`DONE`、`careLogSubmitted=true`）→ E25 `POST /api/orders/{orderId}/complete` → E2 `status=COMPLETED` |
| `upsertSitter(sitter, 欄位)` | `POST /api/internal/test-data/sitter-setup`：`plans[]` / `questions[]` 依 `key` 逐項 upsert，只送要改的那一項與欄位（例 `plans:[{key:"P", price:800}]`）；未列出的方案 / 題目不變；停用用 `active=false`（SD-ORDER §10.4） |
| `shiftReviewExpiry(order, 分鐘)` | `POST /api/internal/test-data/orders/{orderId}/shift-time`，`target=REVIEW_EXPIRES_AT` |
| `inspect(order)` | `GET /api/internal/test-data/orders/{orderId}/inspect`：`orderLogs`、`notifications`（`type`、`recipientUserId`、`recipientRole`、`mandatory`、`emailStatus`）、`payments`（`remindAt`、`escalateAt`、`verifiedAt`、`voidedAt`）、`totalAmount` |
| `notificationText(user, orderId)` | 以該使用者 token 讀通知清單取標題 / 內文（SD-NOTIFY 通知查詢 API，路徑由 e2e-author 依 SD-NOTIFY 對應；`inspect` 不含內文） |
| `runCron(path, orderId)` | `POST /api/internal/cron/{path}?orderId={orderId}`（`X-Internal-Secret`，CI secret，不寫入證據）；計數只算該訂單 |

**標準訂單**（未特別說明時）：`groups = [{ planId: P, dates: [D(10), D(11), D(12)], dailyCount: 1, petIds: [M] }]` → `baseAmount = 1800`、`reviewExpiresAt = TPE0(D(10))`。

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4：訂單相關表與 GCS 物件、`idempotency_records`，並依「Cleanup 跨模組範圍」清方案、題目、收款帳號、通知、毛孩、訂閱、帳號）。

## 2. 測試步驟

> 步驟 ID 前綴 `Q-`（旅程）、`QM-`（模組），spec 的 `step()` / `apiStep()` 標籤以同一 ID 開頭。

### 2.1 評估頁內容與問卷答案（describe「評估頁」：`owner` × `sitterPro`）

前置：`ordEval` = `createPendingOrder(owner, sitterPro, [{P, [D(10), D(11)], 1, [M]}, {P, [D(12)], 1, [N]}], answers = Q1「每天早晚各一次」、Q2 選項 1、Q3 選項 1 與 3)` → E1 `questionnaireStatus=COMPLETED`、`baseAmount=1800`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-01 | AC-1.1-1 | sitterPro | 開 `/sitter/orders/{ordEval}` | 行程 3 列：`D(10)`、`D(11)`、`D(12)`，每日 1 次、方案「到府餵食」、單價 `$ 600`；原始試算 `$ 1,800`；飼主「王小明」；`sitter-order-eval-pet-{M}` 在第 1 群組、`sitter-order-eval-pet-{N}` 在第 2 群組；`sitter-order-eval-review-expires-at` 文字 = `{D(10)} 00:00`；頁面不含「工項」「SOP」字樣、無任何檢核項清單元素 | E5 `GET /api/orders/{ordEval}/evaluation` → 200、`status=PENDING`、`baseAmount=1800`、`owner.displayName=王小明`、`groups.length=2`、`groups[0].pets=[{petId:M, deleted:false}]`、`groups[1].pets=[{petId:N, deleted:false}]`、`reviewExpiresAt=TPE0(D(10))`、`precheck` 四個阻擋欄皆 false、`serverTime` 存在 | 截圖 + API JSON |
| Q-02 | AC-1.2-1 | sitterPro | 同頁捲到問卷區 | `sitter-order-eval-questionnaire-answers` 依序顯示 Q1、Q2、Q3 送單時題目文字與答案（「每天早晚各一次」、選項 1、選項 1 與 3）；頁面無「要求填寫問卷」「改為免填」字樣、問卷區無任何按鈕或輸入框 | E5 `questionnaire.status=COMPLETED`、`exemptReason` 不輸出、`answers.length=3`，順序與題目 `sortOrder` 一致，`questionText` / `questionType` / `answer` 與送單內容相同 | 截圖 + API JSON |
| Q-03 | AC-1.2-2 | sitterPro | `upsertSitter(sitterPro, questions:[{key:"Q1", text:"（已修改）餵食頻率"}, {key:"Q2", active:false}])`（`Q3` 未列出，不變）；重新開評估頁 | 問卷區仍顯示 Q1 原文字與 Q2、Q3 原題目與答案 | E5 `answers[0].questionText` = 送單時原文字；`answers.length=3` | 截圖 + API JSON |
| Q-04 | AC-1.1-2 | owner → sitterPro | `owner-setup` upsert：`M` `deleted=true`（經 SD-CARE 邏輯刪除）；保母重新開評估頁 | 仍有 `sitter-order-eval-pet-{M}`，旁邊 `sitter-order-eval-pet-deleted-label-{M}` 文字「已刪除」；`sitter-order-eval-btn-quote-original` enabled | E5 → 200、`groups[0].pets[0].deleted=true`；飼主 E2 `groups[0].pets[0].deleted=true` | 截圖 + API JSON |
| Q-05 | AC-1.1-2（EX-23） | sitterPro | 點 `sitter-order-eval-btn-quote-original` | `order-status-badge`「待付款」 | E6 → 200、`status=PENDING_PAYMENT`、`totalAmount=1800` | 截圖 + API JSON |

### 2.2 免填原因與熟客標記（describe「免填顯示」：`sitterFree` 無題目）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-06 | AC-1.3-2 | owner → sitterFree | `ord1 = createPendingOrder(owner, sitterFree, 標準)`；保母開評估頁 | `sitter-order-eval-questionnaire-exempt-label` 文字「免填：保母無題目」；無 `sitter-order-eval-regular-badge` | E1 `questionnaireStatus=NOT_REQUIRED`；E5 `questionnaire.exemptReason=NO_ACTIVE_QUESTIONS`、`isRegularOwner=false` | 截圖 + API JSON |
| Q-07 | AC-1.3-1（前置） | owner、sitterFree | `completeOrder(owner, sitterFree, ord1)` | — | E2 `status=COMPLETED` | API JSON |
| Q-08 | AC-1.3-1 | owner → sitterFree | `ord2 = createPendingOrder(owner, sitterFree, 標準)`；保母開評估頁 | 免填標籤「免填：熟客」；`sitter-order-eval-regular-badge` 文字「熟客」 | E1 `questionnaireStatus=NOT_REQUIRED`；E5 `exemptReason=REGULAR_OWNER`（熟客與無題目同時成立記 `REGULAR_OWNER`，baseline CQ-67）、`isRegularOwner=true` | 截圖 + API JSON |
| Q-09 | AC-1.3-3 | owner2 → sitterFree | `ord3 = createPendingOrder(owner2, …)` → 飼主 E8 撤回（`expectedStatus=PENDING`）→ `ord4 = createPendingOrder(owner2, …)`；保母開 `ord4` 評估頁 | 無 `sitter-order-eval-regular-badge`；免填標籤「免填：保母無題目」 | E2(`ord3`) `status=CANCELLED`；E5(`ord4`) `isRegularOwner=false`、`exemptReason=NO_ACTIVE_QUESTIONS` | 截圖 + API JSON |

### 2.3 評估頁與報價越權（describe「越權」：`ordAuth = createPendingOrder(owner, sitterPro, 標準)`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-10 | AC-1.4-1（EX-1） | sitterOther | E5 讀 `ordAuth`；UI 開 `/sitter/orders/{ordAuth}` | 「無權限操作此訂單」 | E5 → 403 `FORBIDDEN` | 截圖 + API JSON |
| Q-11 | AC-1.4-2（EX-2） | sitterPro（OWNER 角色） | 以 `ownerToken(sitterPro)` 呼叫 E5 | — | 403 `ROLE_MISMATCH` | API JSON |
| Q-12 | AC-1.4-3（EX-3） | admin | E5 讀 `ordAuth` | — | 403 `ROLE_MISMATCH` | API JSON |
| Q-13 | AC-1.4-4（EX-5） | 匿名 | 不帶 token 呼叫 E5；UI 未登入開 `/sitter/orders/{ordAuth}` | 導向登入頁 | 401 `UNAUTHENTICATED` | 截圖 + API JSON |
| Q-14 | AC-1.4-5（EX-4） | sitterPro | E5 讀執行時產生的隨機 UUID；UI 開該網址 | 「找不到此訂單」 | 404 `NOT_FOUND` | 截圖 + API JSON |
| Q-15 | AC-3.1-1（EX-1） | sitterOther | E6 `ordAuth`，body `{expectedAmount:1800}` | — | 403 `FORBIDDEN`；**未寫入** | API JSON |
| Q-16 | AC-3.1-2（EX-2） | sitterPro（OWNER 角色） | 以 `ownerToken(sitterPro)` 呼叫 E6 同 body | — | 403 `ROLE_MISMATCH`；**未寫入** | API JSON |
| Q-17 | AC-3.1-3（EX-2） | admin | E6 同 body | — | 403 `ROLE_MISMATCH`；**未寫入** | API JSON |
| Q-18 | AC-3.1-4（EX-4） | sitterPro | E6 隨機 UUID 訂單 | — | 404 `NOT_FOUND` | API JSON |
| Q-19 | （EX-5） | 匿名 | 不帶 token 呼叫 E6 | — | 401 `UNAUTHENTICATED`；**未寫入** | API JSON |

### 2.4 報價預檢、訂閱鎖定、收款帳號（describe「預檢」）

> 預檢阻擋（鎖定 / 額度滿 / 無收款帳號）時三顆報價按鈕 `sitter-order-eval-btn-quote-original`、`-btn-quote-adjust`、`-btn-quote-submit` **全部停用**，優先於 AC-3.2 的鎖頭提示（PRD-006 選 A、SD-ORDER §9.3）。以下「三鈕停用」指此。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-20 | AC-1.5-1、AC-3.6-2（EX-7） | sitterLock | `ordLock = createPendingOrder(owner, sitterLock, 標準)` → `sitter-setup` upsert `subscriptionExpiresAt=D(-2)` → 開評估頁 | `sitter-order-eval-precheck-banner` 文字「您的訂閱已到期或遭停權，暫時無法接新訂單」；三鈕停用；`sitter-order-eval-btn-decline` enabled | E5 → 200、`precheck.subscriptionLocked=true` | 截圖 + API JSON |
| Q-21 | AC-3.6-1（EX-7） | sitterLock | E6 `{expectedAmount:1800}`；再以新 key E6 `{expectedAmount:1800, surchargeAmount:100, pricingNotes:"車馬費"}` | — | 兩次皆 403 `SUBSCRIPTION_LOCKED`；**未寫入** | API JSON |
| Q-22 | AC-3.6-1、AC-3.6-2（停權） | sitterLock | `sitter-setup` upsert `subscriptionExpiresAt=D(30)`、`suspended=true` → 開評估頁 → E6 原價 | banner 同 Q-20；三鈕停用 | E5 200、`precheck.subscriptionLocked=true`；E6 403 `SUBSCRIPTION_LOCKED`；**未寫入** | 截圖 + API JSON |
| Q-23 | AC-1.5-3、AC-3.8-1（EX-14） | sitterNoPay | `ordPay = createPendingOrder(owner, sitterNoPay, 標準)`；開評估頁 | banner 文字含「請先設定收款帳號」，內含連到收款帳號設定頁（PRD-025）的連結；三鈕停用；婉拒 enabled | E5 `precheck.payoutAccountMissing=true` | 截圖 + API JSON |
| Q-24 | AC-3.8-1 | sitterNoPay | E6 `{expectedAmount:1800}` | — | 422 `PAYOUT_ACCOUNT_REQUIRED`；**未寫入** | API JSON |
| Q-25 | AC-3.8-1 | sitterNoPay | 點 banner 內連結 | 進入收款帳號設定頁 | — | 截圖 |
| Q-26 | AC-3.8-2 | sitterNoPay | `sitter-setup` upsert `payoutAccount=ACC2`；回評估頁點 `sitter-order-eval-btn-quote-original` | banner 消失；`order-status-badge`「待付款」 | E5 `precheck.payoutAccountMissing=false`；E6 200；E10 `[0].accountSnapshot` 四欄 = `ACC2` | 截圖 + API JSON |

### 2.5 原價接單、首次收款與期限（describe「原價接單」：`sitterFree2`，設定同 `sitterFree`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-30 | AC-2.1-1、AC-2.4 | sitterFree2 | `ordStd = createPendingOrder(owner, sitterFree2, 標準)`；開評估頁點 `sitter-order-eval-btn-quote-original` | `order-status-badge`「待付款」 | E6 → 200：`status=PENDING_PAYMENT`、`baseAmount=1800`、`surchargeAmount=0`、`discountAmount=0`、`totalAmount=1800`、`quotedAt=T`、`payment.amount=1800`、`payment.dueAt=TPE0(D(9))`、`payment.escalateAt=TPE0(D(10)) + H(2)` | 截圖 + API JSON |
| Q-31 | AC-2.4 | sitterFree2 | E10 `GET /api/orders/{ordStd}/payments`；`inspect(ordStd)` | — | E10 長度 1：`direction=CHARGE`、`purpose=INITIAL`、`status=UNPAID`、`amount=1800`、`dueAt` / `escalateAt` 同 Q-30、`accountSnapshot` 四欄 = `ACC`、`verifiedAt` / `voidedAt` 不輸出；飼主 E2 `quote.quotedAt=T`、`quote.totalAmount=1800`；`inspect.payments[0].remindAt` 有值（報價時未過提醒點）、`remindedAt` 不輸出 | API JSON |
| Q-32 | AC-2.1-1、AC-2.4（BR-11、BR-19） | owner | `inspect(ordStd)`；`notificationText(owner, ordStd)` | — | `inspect.orderLogs` 恰 1 筆 `ORDER_QUOTED`：`operatorRole=SITTER`、payload `baseAmount=1800`、`surchargeAmount=0`、`discountAmount=0`、`totalAmount=1800`、`paymentId`、`dueAt`、`escalateAt`、`reminderMerged=false`、`transitions[]` 含 ORDER `PENDING→PENDING_PAYMENT` 與 PAYMENT 建立；`inspect.notifications` 恰 1 則 `type=QUOTE_SENT`、收件者 = `owner`、`mandatory=false`、`emailStatus` = 需寄送；內文「保母已報價 $ 1,800，請於 {D(9)} 00:00 前完成付款」、連結 `/owner/orders/{ordStd}` | API JSON |
| Q-33 | AC-2.1-1（BR-16） | owner | 開 `/owner/orders/{ordStd}` | `owner-order-detail-quote-breakdown` 顯示原始試算 `$ 1,800`、加價 `$ 0`、折扣 `$ 0`、總額 `$ 1,800`；付款區塊（`payment-amount`）顯示 `$ 1,800`，頁面無 `order-total-amount` | — | 截圖 |
| Q-34 | AC-2.7 | sitterFree2 | 重新開 `/sitter/orders/{ordStd}`；再以新 key E6 `{expectedAmount:1800}` | 無 `sitter-order-eval-btn-quote-original`、`-btn-quote-adjust`、`-btn-quote-submit`、`-input-surcharge`、`-input-discount`、`-input-pricing-notes`；行程與金額為純文字 | E6 → 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=PENDING_PAYMENT`；E10 仍 1 筆、`amount=1800`、`paymentId` 不變；`inspect.orderLogs` 仍只有 1 筆 `ORDER_QUOTED` | 截圖 + API JSON |
| Q-35 | AC-2.1-2 | ownerMuted、sitterFree2 | `ordMute = createPendingOrder(ownerMuted, sitterFree2, 標準, petIds [K])` → E6 原價 | — | 200；`inspect(ordMute).notifications` 恰 1 則 `QUOTE_SENT`、`mandatory=false`、`emailStatus` = 偏好略過 | API JSON |
| Q-36 | AC-2.5-1、AC-3.4-4 | owner、sitterFree2 | `ordToday = createPendingOrder(owner, sitterFree2, [{P, [D(0)], 1, [M]}])` → E6 原價 | — | E1 `reviewExpiresAt` = 送出當下 + `H(2)`（以 E31 `serverTime` 為基準，誤差 ≤ 60 秒）；E6 200（首日 = 今天且未逾期）、`payment.dueAt = T + H(2)`、`payment.escalateAt = T + H(4)`；`inspect.payments[0].remindAt` 不輸出（報價時已過提醒點） | API JSON |
| Q-37 | AC-2.5-2 | owner、sitterFree2 | `ordTmr = createPendingOrder(…, [{P, [D(1)], 1, [M]}])` → E6 原價 | — | `dueAt = T + H(2)`；`escalateAt = max(TPE0(D(1)), T + H(2)) + H(2)`；`inspect.orderLogs` 的 `ORDER_QUOTED.payload.reminderMerged=true` | API JSON |
| Q-38 | AC-2.5-3（BR-11） | ownerMuted、sitterFree2 | `ordUrg = createPendingOrder(ownerMuted, sitterFree2, [{P, [D(1)], 1, [K]}])` → E6 原價 → `runCron(payments/reminder, ordUrg)` | — | `inspect(ordUrg).notifications`：恰 1 則 `type=QUOTE_SENT_WITH_PAYMENT_REMINDER`、`mandatory=true`、`emailStatus` = 需寄送（偏好關閉仍寄，付款提醒類不可關閉）；C2 → 200、`processed=0`、`skipped=0`、`failed=0`；之後仍只有這 1 則、無 `PAYMENT_REMINDER`；`payments[0].remindedAt` 不輸出 | API JSON |

### 2.6 手動調價與快照（describe「調價」）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-40 | AC-2.2 | sitterPro | `ordAdj = createPendingOrder(owner, sitterPro, 標準, answers)`；開評估頁點 `sitter-order-eval-btn-quote-adjust`，`sitter-order-eval-input-surcharge` 填 300、`-input-discount` 填 100、`-input-pricing-notes` 填「車馬費；首次優惠」，點 `sitter-order-eval-btn-quote-submit` | 原因欄標示含「飼主會看到」；送出後徽章「待付款」 | E6 → 200：`baseAmount=1800`、`surchargeAmount=300`、`discountAmount=100`、`totalAmount=2000`、`payment.amount=2000`；`inspect.orderLogs` `ORDER_QUOTED.payload.pricingNotes=車馬費；首次優惠` | 截圖 + API JSON |
| Q-41 | AC-2.2（BR-16） | owner | 開 `/owner/orders/{ordAdj}` | `owner-order-detail-quote-breakdown` 依序含：原始試算 `$ 1,800`、加價 `$ 300`、折扣 `$ 100`、原因：車馬費；首次優惠、總額 `$ 2,000` | E2 `quote` = `{surchargeAmount:300, discountAmount:100, pricingNotes:"車馬費；首次優惠", totalAmount:2000}`、`baseAmount=1800` | 截圖 + API JSON |
| Q-42 | AC-2.2 | sitterUlt | `createPendingOrder(owner, sitterUlt, 標準)` → E6 `{expectedAmount:1800, surchargeAmount:300, discountAmount:100, pricingNotes:"車馬費；首次優惠"}` | — | 200、`totalAmount=2000` | API JSON |
| Q-43 | AC-3.2-1（EX-6） | sitterFree | `ordFreeAdj = createPendingOrder(owner, sitterFree, 標準)`（預檢全通過）；開評估頁點 `sitter-order-eval-btn-quote-adjust` | 按鈕帶鎖頭圖示；點擊後出現「手動調價為專業版功能」與升級入口；不出現 `sitter-order-eval-input-surcharge` | E5 `precheck.planAllowsAdjustment=false`、其餘阻擋欄 false | 截圖 + API JSON |
| Q-44 | AC-3.2-2（EX-6） | sitterFree | E6 `{expectedAmount:1800, surchargeAmount:100, pricingNotes:"車馬費"}`；新 key E6 `{expectedAmount:1800, discountAmount:100, pricingNotes:"優惠"}`；`sitter-setup` upsert `tier=BASIC` 後重送前者（新 key） | — | 三次皆 403 `PLAN_REQUIRED`；**未寫入** | API JSON |
| Q-45 | AC-2.3 | sitterPro、owner | `ordAdj`（Q-40 已報價）之後：`upsertSitter(sitterPro, plans:[{key:"P", price:800, title:"到府餵食（新）"}], tier:"BASIC")`；讀保母 E2；飼主再 `createPendingOrder(owner, sitterPro, 標準)` | — | E2(`ordAdj`) `groups[0].unitPrice=600`（報價後取 `order_plan_snapshots`，SD-ORDER E2）、`groups[0].planTitle=到府餵食`、`quote.totalAmount=2000`；新訂單 E1 `baseAmount=2400`（媒體額度 / 保留期限快照見 §4） | API JSON |

### 2.7 金額與欄位驗證（describe「驗證」：`sitterPro3`（PRO）、`ordV = createPendingOrder(owner, sitterPro3, 標準)`）

> 每次呼叫用新 key；每列結尾斷言 **未寫入**。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-50 | AC-3.3-1（EX-8） | sitterPro3 | E6 `{expectedAmount:1800, discountAmount:1800, pricingNotes:"全額折扣"}` | — | 422 `INVALID_QUOTE_AMOUNT` | API JSON |
| Q-51 | AC-3.3-1（EX-8） | sitterPro3 | UI 調價：折扣填 1800、原因「全額折扣」，送出 | 「報價總額至少需 $ 1」；徽章仍「待評估」 | 頁面發出的 E6 回 422 `INVALID_QUOTE_AMOUNT` | 截圖 + API JSON |
| Q-52 | AC-3.3-3（EX-9） | sitterPro3 | 依序 E6：`surchargeAmount:-100`；`surchargeAmount:100.5`（JSON 數字字面值）；`surchargeAmount:1000000`；`discountAmount:-1`；`surchargeAmount:100, pricingNotes:"   "`；`surchargeAmount:100, pricingNotes:"　 "`（U+3000 + U+00A0）；`surchargeAmount:100, pricingNotes:` strip 後 201 字；`surchargeAmount:100` 不帶 `pricingNotes`（以上皆帶 `expectedAmount:1800`） | — | 每次 400 `VALIDATION_FAILED`；`payload.field` 依序 = `surchargeAmount` ×3、`discountAmount`、`pricingNotes` ×4 | API JSON |
| Q-53 | AC-3.3-4（EX-9） | sitterPro3 | E6 `{expectedAmount:1800, surchargeAmount:999000, pricingNotes:"上限測試"}`（總額 1,000,800） | — | 400 `VALIDATION_FAILED`、`payload.field=totalAmount` | API JSON |
| Q-54 | AC-3.3-5（EX-9） | sitterPro3 | E6 body `{}`；E6 `{expectedAmount:1800}` 但不帶 `Idempotency-Key` 標頭 | — | 400 `VALIDATION_FAILED`、`payload.field` 分別 = `expectedAmount`、`Idempotency-Key` | API JSON |
| Q-55 | AC-3.3-2 | sitterPro3 | E6 `{expectedAmount:1800, discountAmount:1799, pricingNotes:` 前後各 1 個 U+3000 + 恰 200 字（199 中文字 +「🐱」）`}` | — | 200、`totalAmount=1`、`payment.amount=1`；E2 `quote.pricingNotes` = strip 後的 200 字 | API JSON |

### 2.8 冪等與跨角色併發（describe「冪等併發」：`sitterPro`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-60 | AC-2.6-1（EX-18） | sitterPro | `ordI = createPendingOrder(…標準)`；E6 key `K` `{expectedAmount:1800}` → 回應 `B1`；同 key 同 body 再送 | — | 第二次 200，body 與 `B1` 完全相同（含 `quotedAt`、`payment.paymentId`）；E10 長度 1；`inspect.orderLogs` 恰 1 筆 `ORDER_QUOTED`；`inspect.notifications` 報價類恰 1 則 | API JSON |
| Q-61 | （EX-19） | sitterPro | key `K`、body `{expectedAmount:1799}` | — | 422 `IDEMPOTENCY_KEY_REUSED`；E10 仍 1 筆 | API JSON |
| Q-62 | AC-2.6-2（EX-15） | sitterPro | 新 key `{expectedAmount:1800}` | — | 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=PENDING_PAYMENT` | API JSON |
| Q-63 | AC-2.6-3（EX-16） | owner、sitterPro | 3 輪，每輪新訂單：同時送出（`Promise.all`）飼主 E8 `{expectedStatus:"PENDING"}` 與保母 E6 `{expectedAmount:1800}` | — | 每輪恰一方 200、另一方 409 `ORDER_STATE_CONFLICT`（`currentStatus` = 勝方造成的狀態）；最終二擇一：E2 `status=CANCELLED`、`cancellation.cancelledBy=OWNER`、E10 `[]`、`inspect.orderLogs` 有 `ORDER_WITHDRAWN` 無 `ORDER_QUOTED`；或 E2 `status=PENDING_PAYMENT`、E10 長度 1、`orderLogs` 有 `ORDER_QUOTED` 無 `ORDER_WITHDRAWN`。誰勝不固定，確定性覆蓋見 §4 | API JSON（每輪） |

### 2.9 評估期限（describe「期限」：`sitterPro`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-70 | AC-3.4-1（EX-10） | sitterPro | `ordDL = createPendingOrder(…標準)`；`shiftReviewExpiry(ordDL, −(距到期分鐘數 + 5))`；讀 E2 得新值 `R'`；E6 `{expectedAmount:1800}` | — | E2 `reviewExpiresAt=R'` < `serverTime`；E6 409 `DEADLINE_PASSED`、`payload.deadline=REVIEW_EXPIRES_AT`、`payload.deadlineAt=R'`；**未寫入** | API JSON |
| Q-71 | AC-3.4-1（EX-10） | sitterPro | 開評估頁 | 「此預約已超過評估期限」；三顆報價按鈕停用；`sitter-order-eval-btn-decline` enabled | E5 `precheck.reviewExpired=true`；頁面未發出任何 E6 | 截圖 + API JSON |

### 2.10 月接單額度（describe「月額度」：`sitterQuota`（FREE），所有訂單皆日期 `[D(10)]`、單日，`baseAmount=600`）

> 同日多張都能報價，順帶驗證 BR-17（不做容量 / 檔期控管）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-80 | AC-3.5-3（前置） | owner、sitterQuota | 建 `o1`–`o6`；`o1`、`o2` 報價後 `payAndConfirm` | — | `o1`、`o2` E14 `orderStatus=CONFIRMED`；E10 `verifiedAt` 有值 | API JSON |
| Q-81 | AC-3.5-3、BR-17 | sitterQuota | `o3`、`o4`、`o5` 依序 E6 原價；接著 `o6` E6 原價 | — | 四次皆 200（`o6` 報價時本月 2 筆 `CONFIRMED` + 3 筆 `PENDING_PAYMENT`，待付款不計數） | API JSON |
| Q-82 | AC-3.5-1（前置） | owner、sitterQuota | `o3` `payAndConfirm` | — | 本月 `CONFIRMED` = 3 | API JSON |
| Q-83 | AC-3.5-1（EX-12） | sitterQuota | 建 `o7` → E6 原價 | — | 422 `MONTHLY_QUOTA_REACHED`；**未寫入** | API JSON |
| Q-84 | AC-1.5-2（EX-12） | sitterQuota | 開 `o7` 評估頁 | banner 含「本月額度已滿」，內有 `sitter-order-eval-precheck-upgrade-link`；三鈕停用（不出現鎖頭提示互動）；`sitter-order-eval-btn-decline` enabled | E5 200、`precheck.quotaReached=true` | 截圖 + API JSON |
| Q-85 | BR-12 | owner、sitterQuota | `o4` `payAndConfirm` | — | E14 200、`orderStatus=CONFIRMED`（已報價待付款者不受上限影響，本月確認數成為 4） | API JSON |

### 2.11 畫面金額過期（describe「金額過期」：`sitterPrice`，設定同 `sitterPro`、`questions = []`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-90 | AC-3.7-1（EX-13） | sitterPrice | `ordP = createPendingOrder(…標準)`；開評估頁（原始試算 `$ 1,800`）；另以 `upsertSitter(sitterPrice, plans:[{key:"P", price:700}])`；回頁面點 `sitter-order-eval-btn-quote-original` | 提示「訂單金額已更新為 $ 2,100，請確認後再送出」；原始試算更新為 `$ 2,100`；徽章仍「待評估」 | 頁面發出的 E6（`expectedAmount=1800`）→ 409 `PRICE_CHANGED`、`payload.baseAmount=2100`、`payload.totalAmount=2100`；**未寫入** | 截圖 + API JSON |
| Q-91 | AC-3.7-1 | sitterPrice | 再點 `sitter-order-eval-btn-quote-original` | 徽章「待付款」 | 頁面發出的 E6 帶新 key、`expectedAmount=2100` → 200、`totalAmount=2100` | 截圖 + API JSON |
| Q-92 | AC-3.7-1（BR-7） | sitterPrice | `ordP2 = createPendingOrder(…標準)`（建立時單價 700，base 2100）；`upsertSitter(sitterPrice, plans:[{key:"P", price:800}])`；E6 `{expectedAmount:2100, surchargeAmount:300, discountAmount:100, pricingNotes:"車馬費"}` | — | 409 `PRICE_CHANGED`、`payload.baseAmount=2400`、`payload.totalAmount=2600` | API JSON |
| Q-93 | AC-3.7-2 | sitterPrice | `ordP3 = createPendingOrder(…標準)`（單價 800，base 2400）；`upsertSitter(sitterPrice, plans:[{key:"P", active:false}])`；E6 `{expectedAmount:2400}` | — | 200、`baseAmount=2400`、E2 `groups[0].unitPrice=800` | API JSON |

### 2.12 模組（mock）：真後端造不出的回應

> 檔案 `frontend/e2e/order-eval.spec.ts`；`mockDemoSession(page, 'SITTER')`，E5 回 `PENDING` 訂單（`baseAmount=1800`，`precheck` 阻擋欄全 false、`planAllowsAdjustment=true`、`serverTime`）；`afterEach` 呼叫 `assertNoUnmockedApi`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QM-01 | （EX-17） | sitter | mock E6 連續兩次回 409 `ORDER_BUSY` + `Retry-After: 1`；點原價接單 | 「系統忙碌，請稍後再試」 | 攔截到恰 2 次 E6，`Idempotency-Key` 相同（SD-ORDER §9.2） | 截圖 + 請求紀錄 |
| QM-02 | AC-3.4-3（EX-11） | sitter | mock E6 回 409 `FIRST_VISIT_DATE_PASSED`；點原價接單 | 「首個行程日期已過，無法報價，請婉拒或請飼主重新預約」 | — | 截圖 |
| QM-03 | （EX-19） | sitter | mock E6 第一次 409 `IDEMPOTENCY_IN_PROGRESS`、第二次 200；點原價接單 | 不出現錯誤提示，徽章「待付款」 | 恰 2 次 E6，key 相同 | 截圖 + 請求紀錄 |
| QM-04 | （EX-19） | sitter | mock E6 回 422 `IDEMPOTENCY_KEY_REUSED`；點原價接單 | 「請重新操作」 | — | 截圖 |
| QM-05 | AC-1.4-4（EX-5） | 匿名 | `mockSessionApis`，E5 回 401 `UNAUTHENTICATED` | 導向登入頁 | — | 截圖 |
| QM-06 | AC-3.3-3（EX-9） | sitter | mock E6 回 400 `VALIDATION_FAILED`、`payload.field=pricingNotes`；調價填加價 100、原因留空送出 | `sitter-order-eval-input-pricing-notes` 下方出現錯誤訊息 | — | 截圖 |

## 3. 權限與例外（判斷順序與破壞性測試）

> 驗證 PRD-006 BR-22 / §4.3 步驟 2 的先後：同時違反兩條規則時，回應必須是順序在前者。每列用新訂單、新 key。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| Q-E1 | EX-15、EX-16 | 保母開 `ordX` 評估頁不重新整理；飼主 E8 撤回；保母點原價接單 | E6 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；畫面「訂單狀態已變更，請重新整理」；E10 `[]` |
| Q-E2 | BR-22（`PLAN_REQUIRED` 先於 `SUBSCRIPTION_LOCKED`） | FREE 保母建單後鎖定（upsert `subscriptionExpiresAt=D(-2)`），E6 帶 `surchargeAmount:100, pricingNotes:"x"` | 403 `PLAN_REQUIRED` |
| Q-E3 | BR-22（`PAYOUT_ACCOUNT_REQUIRED` 先於 `SUBSCRIPTION_LOCKED`） | `sitterNoPay` 建單後鎖定，E6 原價 | 422 `PAYOUT_ACCOUNT_REQUIRED` |
| Q-E4 | BR-22（`DEADLINE_PASSED` 先於業務規則） | `sitterNoPay` 建單、`shiftReviewExpiry` 到過去，E6 原價 | 409 `DEADLINE_PASSED` |
| Q-E5 | BR-22（格式先於狀態） | 已 `PENDING_PAYMENT` 的訂單，E6 `surchargeAmount:-1` | 400 `VALIDATION_FAILED`（不是 409） |
| Q-E6 | BR-22（`INVALID_QUOTE_AMOUNT` 先於 `PLAN_REQUIRED`） | FREE 保母，E6 `{expectedAmount:1800, discountAmount:1800, pricingNotes:"x"}` | 422 `INVALID_QUOTE_AMOUNT` |
| Q-E7 | BR-22（`PRICE_CHANGED` 先於 `PLAN_REQUIRED`） | FREE 保母，E6 `{expectedAmount:1799, surchargeAmount:100, pricingNotes:"x"}` | 409 `PRICE_CHANGED`、`baseAmount=1800`、`totalAmount=1900` |
| Q-E8 | （資料污染） | PRO 保母調價，原因填 `<img src=x onerror=alert(1)>`（加價 1） | 200；飼主 `owner-order-detail-quote-breakdown` 原樣顯示該字串為文字，頁面未觸發任何 dialog；E2 `quote.pricingNotes` 與輸入相同 |
| Q-E9 | EX-1（冪等範圍含使用者） | `sitterOther` 對 `ordAuth` 帶合法 body 與 key `K2` 呼叫 E6 後，`sitterPro` 以同一 key 同 body 呼叫 E6 | 前者 403 `FORBIDDEN`；後者 200（不得回放他人的 403） |
| Q-E10 | §4.3 步驟 2.1–2.2（401 先於冪等回放） | Q-60 的 key `K` 與 body，不帶 token 重送 | 401 `UNAUTHENTICATED`（不得回放 200） |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-2.1、AC-2.5-3 的 Email **實際寄達** | Resend 為測試模式只能寄專案擁有者；E2E 只斷言 `inspect.notifications[].emailStatus` | SD-NOTIFY C11 測試；真實寄信手動驗證 |
| AC-2.3 媒體額度（PRO 10 照片 + 2 影片）、保留期限 90 天快照 | `order_snapshots` 不在 E2 / `inspect` 回應內 | `QuoteIntegrationTest` |
| AC-2.4 精確時刻（2026-12-01 10:00 報價 → `quoted_at=2026-12-01T02:00:00Z` 等字面值）、AC-2.5-1 / 2 的字面時刻 | 正式站無法注入 `Clock`；E2E 以 §1 公式對回應的 `quotedAt` 驗證 | `QuoteIntegrationTest`（注入 `Clock`） |
| AC-2.6-3 確定性、AC-2.6-4（列鎖逾時 3 秒 → 409 `ORDER_BUSY` + `Retry-After: 1`）、EX-17 後端部分 | 正式站無法穩定控制交錯、無法持有列鎖 | `QuoteConcurrencyIntegrationTest` |
| EX-19 同 key 處理中 409 `IDEMPOTENCY_IN_PROGRESS`（後端） | 需控制處理中時間窗 | `QuoteConcurrencyIntegrationTest`、SD-ORDER §10.2 |
| AC-3.4-2（`now = review_expires_at` 整點） | 需精確時間 | `QuoteIntegrationTest` |
| AC-3.4-3（首個行程日期已過但未逾期） | 需在台北 23:30 送單並跨日報價；E1 不收過去日期，§10.4 無平移 `first_service_date` 的端點 | `QuoteIntegrationTest`；畫面文案由 QM-02 |
| AC-3.5-1 中「其中 1 筆之後已取消仍計數」、AC-3.5-2（跨月 `2026-12-31T16:00:00Z` 後可報價）、AC-3.5-4（BASIC 20、PRO / ULTIMATE 50、PRO 5 筆後降 FREE） | 取消 `CONFIRMED` 需走變更協商、跨月需注入時間、造 20 / 50 筆成本過高 | `QuoteIntegrationTest` |
| AC-3.6「到期日隔天 00:00（Asia/Taipei）」邊界 | 需精確時間 | `QuoteIntegrationTest` |
| EX-20 Resend 失敗不影響轉移 | 正式站無法注入故障 | SD-NOTIFY C11 測試；`QuoteIntegrationTest`（Resend mock 不在交易內呼叫） |
| EX-21 GCS | 不適用（本功能無上傳） | — |
| EX-22 `@Version` 衝突 → 409 `ORDER_BUSY` | 列鎖之後的最後防線，正式站無法觸發 | `QuoteConcurrencyIntegrationTest` |
| BR-19 主交易回滾時 log / 通知不存在 | 需注入交易失敗 | `OrderLogWriterTest`、`QuoteIntegrationTest` |

## 5. 已知限制 / 待補

| # | 項目 | 影響 | 建議預設值 / 處理 |
|---|------|------|------------------|
| 1 | （已解決 2026-11-22）provision 雙角色 / ADMIN / `displayName` | — | SD-ORDER §10.4 `roles[]`、`displayName` |
| 2 | （已解決 2026-11-22）金額格式 | — | 依 SD-FRONTEND-SPEC §6.4.1，PRD-006 已回寫 |
| 3 | `inspect.notifications[].emailStatus` 的列舉值（需寄送 / 依偏好略過 / 已寄 / 失敗）SD-ORDER 未列，屬 SD-NOTIFY | Q-32、Q-35、Q-38 的 Email 斷言 | SD-NOTIFY 撰寫時定義；e2e-author 屆時對應 |
| 4 | 通知標題 / 內文不在 `inspect` 內，仍需 SD-NOTIFY 通知查詢 API（未撰寫） | Q-32 內文斷言 | SD-NOTIFY 定義後回填路徑 |
| 5 | （已解決 2026-11-22）`sitter-setup` 陣列語意 | — | SD-ORDER §10.4：依 `key` 逐項 upsert（既有 key 只覆寫出現欄位、新 key 新建、未列出的既有項不變、停用用 `active=false`）；Q-03、Q-45、Q-90、Q-92、Q-93 只送要改的那一項 |
| 6 | （已解決）EX-22 後端類別 | — | `QuoteConcurrencyIntegrationTest` |
| 7 | （已解決）E5 `serverTime`、`precheck.reviewExpired` | — | Q-71 改為開頁即停用 |
| 8 | （已解決）E2 報價後 `unitPrice` 取快照 | — | SD-ORDER E2 |
| 9 | Q-36 / Q-37 依「今天 / 明天」建單；台北 23:50–00:10 執行時送單與報價可能跨日 | 偶發 `FIRST_VISIT_DATE_PASSED` | spec 以 E31 `serverTime` 判斷，該時段 `test.skip` 並註明原因 |
| 10 | （已解決）排程端點帶 `orderId`、自動觸發跳過測試帳號 | — | Q-38 斷言精確計數 |
| 11 | （已解決）預檢阻擋時三鈕全停用（PRD-006 選 A） | — | Q-20、Q-22、Q-23、Q-84 |
| 12 | （已解決）`order_logs` 讀取 | — | `inspect.orderLogs` |

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | §1 新增 `upsertSitter` helper，明定 `plans[]` / `questions[]` 依 key 逐項 upsert、只送要改的項目；Q-03（`questions:[{key:"Q1", text}, {key:"Q2", active:false}]`）、Q-45、Q-90、Q-92（`plans:[{key:"P", …}]`）、Q-93（`plans:[{key:"P", active:false}]`）依此語意；§5 #5 標已解決 | SD-ORDER §10.4 補明陣列 upsert 語意 |
| 2026-11-22 | 依核准後修訂更新：`order_logs`、通知類型 / `mandatory` / `emailStatus`、`remindAt` 改由 `inspect` 在 E2E 斷言（Q-31、Q-32、Q-34、Q-35、Q-36–Q-38、Q-40、Q-60、Q-63），§4 移除對應後端列；C2 帶 `orderId` 斷言精確計數（Q-38）；provision `roles[]` / `displayName`、`sitter-setup` / `owner-setup` upsert 取代 PRD-002 / 003 / 004 / 025 正式 API 造資料（Q-03、Q-04、Q-26、Q-45、Q-90–Q-93）；預檢阻擋三鈕全停用與 `sitter-order-eval-precheck-upgrade-link`（Q-20–Q-23、Q-84）；Q-71 改依 `precheck.reviewExpired` 開頁即停用；金額格式一律 `$ 1,800`（Q-32、Q-33、Q-41、Q-51、Q-90）；strip 含 U+3000 / U+00A0（Q-52、Q-55）；新增 Q-E10（401 先於冪等回放）；§5 標註已解決項 | SD-ORDER / PRD-006 核准後修訂（commit 477c75d）；使用者決定 2026-11-22 |
| 2026-11-22 | 依新範本全文重寫，狀態 Draft。舊版 Scenario 1–10（`AUTH_PLAN_LIMIT`、樂觀鎖 version、`/confirm`、`/reject`、飼主 `/cancel`、`OrderEvaluationTest` 等）與舊實作綁定，全數作廢；婉拒移至 TS-027、飼主撤回移至 TS-026。舊版「本測試發現並修復的真實 bug」屬舊實作，不沿用 | PRD-006、SD-ORDER Approved 2026-11-22；全面重建 2026-11 |
