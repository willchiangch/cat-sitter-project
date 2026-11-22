# TS-006: 保母訂單評估、事前問卷與報價

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-006（Approved 2026-11-22）；前置資料另用 PRD-005（送單）、PRD-007（上傳 / 核對憑證）、PRD-026（飼主撤回）、PRD-002 / PRD-003 / PRD-004 / PRD-025（毛孩、方案、問卷、收款帳號設定） |
| 對應 SD | SD-ORDER（Approved 2026-11-22）：E1、E2、E5、E6、E8、E10、E11、E14、E25、C2、§6.5 錯誤碼、§7.2 通知、§9.1 testid、§10.4 測試支援端點；SD-FULFILL FT1（造 COMPLETED 訂單） |
| 類型 | 旅程（打真後端，§2.1–§2.11、§3）＋ 模組（mock，只有 §2.12 的 QM 列：正式站無法造出的錯誤回應與前端重試行為） |
| 自動化路徑 | 旅程 `frontend/e2e/journeys/order-evaluation-quote.spec.ts`；模組 `frontend/e2e/order-eval.spec.ts`（改寫，須 `assertNoUnmockedApi`） |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> 報價是狀態轉移 + 權限 + 冪等 + 跨角色（飼主撤回）互動，主體用旅程型。模組型只補「真後端造不出來的回應」對畫面的影響。
> PRD-006 AC 內「情境 n」逐列對應為 `AC-x.y-n`。

---

## 0. 測試邏輯（Given / When / Then）

- **Given**：每個 `test.describe` 自己 provision 飼主與保母（`@e2e-journey.test`），以 E1 建立 `PENDING` 訂單；需要的方案、訂閱等級、收款帳號、問卷由 `sitter-setup` / `owner-setup` 造好。
- **When**：保母在 `/sitter/orders/{orderId}` 檢視評估頁、原價接單或調價報價；或以其他身分 / 錯誤內容直接呼叫 E5、E6。
- **Then**：畫面依 PRD-006 §4 顯示；E6 成功時訂單 `PENDING → PENDING_PAYMENT`、建立唯一一筆 INITIAL payment、期限依 baseline §5 公式；失敗時回 PRD-006 §4.3 判斷順序中第一個不符的錯誤碼，訂單維持 `PENDING`、無 payment。

## 1. 前置資料

> 不寫死 UUID：訂單、毛孩、方案、題目 id 一律取自建立時的回應。每個 describe 可獨立重跑。

**記號**

| 記號 | 意義 |
|------|------|
| `D(n)` | 執行當下 Asia/Taipei 的今天 + n 日（`YYYY-MM-DD`） |
| `TPE0(d)` | 日期 d 的 Asia/Taipei 00:00，以 UTC 表示 = 前一日 `T16:00:00Z` |
| `T` | E6 回應的 `quotedAt` |
| `H(x)` | x 小時；`URGENT_WINDOW_HOURS` = 2（baseline §5） |
| 期限公式 | `dueAt = max(TPE0(首日) − H(24), T + H(2))`；`escalateAt = max(TPE0(首日), dueAt) + H(2)`；`reviewExpiresAt = max(TPE0(首日), 送單時間 + H(2))`（baseline §5） |
| `ownerToken(X)` / `sitterToken(X)` | 帳號 X 以 `currentRole=OWNER` / `SITTER` 取得的 token；同一帳號切換角色後的 token 用於 `ROLE_MISMATCH` 情境 |

**帳號**

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `POST /api/internal/test-data/provision-account`（`@e2e-journey.test`，顯示名稱「王小明」）＋ `owner-setup`：`pets` = `M`、`N`（CAT，未刪除）、`emailPreferences.ORDER_AFFAIR = 開` | — |
| `ownerMuted` | OWNER | 同上，`emailPreferences.ORDER_AFFAIR = 關`；`pets` = `K` | — |
| `owner2` | OWNER | 同 `owner`，`pets` = `L` | 熟客對照組 |
| `sitterPro` | SITTER | provision ＋ `sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` = 四欄測試值（記為 `ACC`）、`plans` = `P`（「到府餵食」，單價 600，`active`）、`questions` = Q1 單行文字、Q2 單選、Q3 多選（皆必填、啟用） | — |
| `sitterFree` | SITTER | 同上但 `tier=FREE`、`questions = []` | 本月 0 筆 `CONFIRMED` |
| `sitterQuota` | SITTER | 同 `sitterFree` | §2.10 專用 |
| `sitterUlt` | SITTER | 同 `sitterPro` 但 `tier=ULTIMATE`、`questions = []` | — |
| `sitterNoPay` | SITTER | 同 `sitterPro` 但 `payoutAccount = null`、`questions = []` | — |
| `sitterLock` | SITTER | 同 `sitterPro`、`questions = []`；建好訂單後再以 `sitter-setup` 改 `subscriptionExpiresAt = D(-2)` 或 `suspended = true` | 先可接單，後鎖定 |
| `sitterOther` | SITTER | 同 `sitterPro` | 越權用 |
| `admin` | ADMIN | provision-account（ADMIN 角色，見 §5 #1） | — |

**共用 helper（業務名稱，e2e-author 實作）**

| helper | 內容 |
|--------|------|
| `createPendingOrder(owner, sitter, groups, answers?)` | 先 `GET /api/sitters/{sitterId}/booking-context`（E31）取方案與題目 id → E1 `POST /api/orders`（新 `Idempotency-Key`，`expectedAmount` = Σ 單價 × 日數 × `dailyCount`）→ 斷言 201、`status=PENDING` |
| `quote(sitter, order, body, key?)` | E6 `POST /api/orders/{orderId}/quote`，`Idempotency-Key` 預設新 UUID |
| `payAndConfirm(owner, sitter, order)` | E10 取 INITIAL `paymentId` → E11 上傳（`disclaimerAgreed=true`、`lastFive=12345`、1×1 PNG）→ E14（`submissionId`）→ 斷言 `orderStatus=CONFIRMED` |
| `completeOrder(owner, sitter, order)` | `payAndConfirm` → 每個行程 SD-FULFILL FT1 `force-status`（`DONE`、`careLogSubmitted=true`）→ E25 `POST /api/orders/{orderId}/complete` → E2 `status=COMPLETED` |
| `shiftReviewExpiry(order, 分鐘)` | `POST /api/internal/test-data/orders/{orderId}/shift-time`，`target=REVIEW_EXPIRES_AT` |
| `notificationsOf(user, orderId)` | 以該使用者 token 讀通知清單（SD-NOTIFY 通知查詢 API，路徑由 e2e-author 依 SD-NOTIFY 對應），篩 `orderId` |

**標準訂單**（未特別說明時）：`groups = [{ planId: P, dates: [D(10), D(11), D(12)], dailyCount: 1, petIds: [M] }]` → `baseAmount = 1800`、`reviewExpiresAt = TPE0(D(10))`。

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4 已擴充涵蓋 payments、submissions 的 GCS 物件、`order_plan_snapshots`、`order_snapshots`、`idempotency_records`）。

## 2. 測試步驟

> 步驟 ID 前綴 `Q-`（旅程）、`QM-`（模組），spec 的 `step()` / `apiStep()` 標籤以同一 ID 開頭。
> 「E2 PENDING、無 payment」= 飼主 E2 `status=PENDING` 且保母 E10 回 `[]`，以下簡寫為「**未寫入**」。

### 2.1 評估頁內容與問卷答案（describe「評估頁」：`owner` × `sitterPro`）

前置：`ordEval` = `createPendingOrder(owner, sitterPro, [{P, [D(10), D(11)], 1, [M]}, {P, [D(12)], 1, [N]}], answers = Q1「每天早晚各一次」、Q2 選項 1、Q3 選項 1 與 3)` → E1 `questionnaireStatus=COMPLETED`、`baseAmount=1800`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-01 | AC-1.1-1 | sitterPro | 開 `/sitter/orders/{ordEval}` | 行程 3 列：`D(10)`、`D(11)`、`D(12)`，每日 1 次、方案「到府餵食」、單價 `$ 600`；原始試算 `$ 1,800`；飼主「王小明」；`sitter-order-eval-pet-{M}` 在第 1 群組、`sitter-order-eval-pet-{N}` 在第 2 群組；`sitter-order-eval-review-expires-at` 文字 = `{D(10)} 00:00`；頁面不含「工項」「SOP」字樣、無任何檢核項清單元素 | E5 `GET /api/orders/{ordEval}/evaluation` → 200、`status=PENDING`、`baseAmount=1800`、`owner.displayName=王小明`、`groups.length=2`、`groups[0].pets=[{petId:M, deleted:false}]`、`groups[1].pets=[{petId:N, deleted:false}]`、`reviewExpiresAt=TPE0(D(10))` | 截圖 + API JSON |
| Q-02 | AC-1.2-1 | sitterPro | 同頁捲到問卷區 | `sitter-order-eval-questionnaire-answers` 依序顯示 Q1、Q2、Q3 送單時題目文字與答案（「每天早晚各一次」、選項 1、選項 1 與 3）；頁面無「要求填寫問卷」「改為免填」字樣、問卷區無任何按鈕或輸入框 | E5 `questionnaire.status=COMPLETED`、`exemptReason` 不輸出、`answers.length=3`，順序與題目 `sortOrder` 一致，`questionText` / `questionType` / `answer` 與送單內容相同 | 截圖 + API JSON |
| Q-03 | AC-1.2-2 | sitterPro | 在問卷設定（PRD-004）把 Q1 文字改為「（已修改）餵食頻率」、停用 Q2；重新開評估頁 | 問卷區仍顯示 Q1 原文字與 Q2、Q3 原題目與答案 | E5 `answers[0].questionText` = 送單時原文字；`answers.length=3` | 截圖 + API JSON |
| Q-04 | AC-1.1-2 | owner → sitterPro | 飼主在毛孩管理（PRD-002）刪除 `M`；保母重新開評估頁 | 仍有 `sitter-order-eval-pet-{M}`，旁邊 `sitter-order-eval-pet-deleted-label-{M}` 文字「已刪除」；`sitter-order-eval-btn-quote-original` 可點 | E5 → 200、`groups[0].pets[0].deleted=true`；飼主 E2 `groups[0].pets[0].deleted=true`（BR-21 飼主端同樣照常顯示） | 截圖 + API JSON |
| Q-05 | AC-1.1-2（EX-23） | sitterPro | 點 `sitter-order-eval-btn-quote-original` | `order-status-badge` 顯示「待付款」 | E6 → 200、`status=PENDING_PAYMENT`、`totalAmount=1800`（已刪除毛孩不影響報價） | 截圖 + API JSON |

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

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-20 | AC-1.5-1、AC-3.6-2（EX-7） | sitterLock | `ordLock = createPendingOrder(owner, sitterLock, 標準)` → `sitter-setup` 改 `subscriptionExpiresAt=D(-2)` → 開評估頁 | `sitter-order-eval-precheck-banner` 文字「您的訂閱已到期或遭停權，暫時無法接新訂單」；`sitter-order-eval-btn-quote-original`、`-btn-quote-adjust`、`-btn-quote-submit`（若存在）皆 disabled；`sitter-order-eval-btn-decline` enabled | E5 → 200、`precheck.subscriptionLocked=true` | 截圖 + API JSON |
| Q-21 | AC-3.6-1（EX-7） | sitterLock | E6 `{expectedAmount:1800}`；再以新 key E6 `{expectedAmount:1800, surchargeAmount:100, pricingNotes:"車馬費"}` | — | 兩次皆 403 `SUBSCRIPTION_LOCKED`；**未寫入** | API JSON |
| Q-22 | AC-3.6-1、AC-3.6-2（停權） | sitterLock | `sitter-setup` 改回 `subscriptionExpiresAt=D(30)`、`suspended=true` → 開評估頁 → E6 原價 | banner 同 Q-20 | E5 200、`precheck.subscriptionLocked=true`；E6 403 `SUBSCRIPTION_LOCKED`；**未寫入** | 截圖 + API JSON |
| Q-23 | AC-1.5-3、AC-3.8-1（EX-14） | sitterNoPay | `ordPay = createPendingOrder(owner, sitterNoPay, 標準)`；開評估頁 | banner 文字含「請先設定收款帳號」，內含連到收款帳號設定頁（PRD-025）的連結；三個報價按鈕 disabled；婉拒 enabled | E5 `precheck.payoutAccountMissing=true` | 截圖 + API JSON |
| Q-24 | AC-3.8-1 | sitterNoPay | E6 `{expectedAmount:1800}` | — | 422 `PAYOUT_ACCOUNT_REQUIRED`；**未寫入** | API JSON |
| Q-25 | AC-3.8-1 | sitterNoPay | 點 banner 內連結 | 進入收款帳號設定頁 | — | 截圖 |
| Q-26 | AC-3.8-2 | sitterNoPay | 在收款帳號設定（PRD-025）設好帳號 `ACC2`；回評估頁點 `sitter-order-eval-btn-quote-original` | banner 消失；`order-status-badge`「待付款」 | E5 `precheck.payoutAccountMissing=false`；E6 200；E10 `[0].accountSnapshot` 四欄 = `ACC2` | 截圖 + API JSON |

### 2.5 原價接單、首次收款與期限（describe「原價接單」：`sitterFree2`，設定同 `sitterFree`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-30 | AC-2.1-1、AC-2.4 | sitterFree2 | `ordStd = createPendingOrder(owner, sitterFree2, 標準)`；開評估頁點 `sitter-order-eval-btn-quote-original` | `order-status-badge`「待付款」 | E6 → 200：`status=PENDING_PAYMENT`、`baseAmount=1800`、`surchargeAmount=0`、`discountAmount=0`、`totalAmount=1800`、`quotedAt=T`、`payment.amount=1800`、`payment.dueAt=TPE0(D(9))`、`payment.escalateAt=TPE0(D(10)) + H(2)` | 截圖 + API JSON |
| Q-31 | AC-2.4 | sitterFree2 | E10 `GET /api/orders/{ordStd}/payments` | — | 長度 1：`direction=CHARGE`、`purpose=INITIAL`、`status=UNPAID`、`amount=1800`、`dueAt` / `escalateAt` 同 Q-30、`accountSnapshot` 四欄 = `ACC`；飼主 E2 `quote.quotedAt=T`、`quote.totalAmount=1800` | API JSON |
| Q-32 | AC-2.1-1、AC-2.4（BR-11） | owner | `notificationsOf(owner, ordStd)` | — | 恰 1 則報價通知：類型 `QUOTE_SENT`（非附提醒類，因 `T` < `TPE0(D(10)) − H(48)`）、內容「保母已報價 NT$1800，請於 {D(9)} 00:00 前完成付款」、連結 `/owner/orders/{ordStd}` | API JSON |
| Q-33 | AC-2.1-1（BR-16） | owner | 開 `/owner/orders/{ordStd}` | `owner-order-detail-quote-breakdown` 顯示原始試算 1,800、加價 0、折扣 0、總額 1,800（金額格式見 §5 #2） | — | 截圖 |
| Q-34 | AC-2.7 | sitterFree2 | 重新開 `/sitter/orders/{ordStd}`；再以新 key E6 `{expectedAmount:1800}` | 無 `sitter-order-eval-btn-quote-original`、`-btn-quote-adjust`、`-btn-quote-submit`、`-input-surcharge`、`-input-discount`、`-input-pricing-notes`；行程與金額為純文字 | E6 → 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=PENDING_PAYMENT`；E10 仍 1 筆且 `amount=1800`、`paymentId` 不變 | 截圖 + API JSON |
| Q-35 | AC-2.1-2 | ownerMuted、sitterFree2 | `ordMute = createPendingOrder(ownerMuted, sitterFree2, 標準, petIds [K])` → E6 原價 | — | 200；`notificationsOf(ownerMuted, ordMute)` 恰 1 則 `QUOTE_SENT`（Email 未寄見 §4） | API JSON |
| Q-36 | AC-2.5-1、AC-3.4-4 | owner、sitterFree2 | `ordToday = createPendingOrder(owner, sitterFree2, [{P, [D(0)], 1, [M]}])` → E6 原價 | — | E1 `reviewExpiresAt` 與送出當下相差介於 `H(2) − 60 秒` 與 `H(2)`；E6 200（首日 = 今天且未逾期）、`payment.dueAt = T + H(2)`、`payment.escalateAt = T + H(4)` | API JSON |
| Q-37 | AC-2.5-2 | owner、sitterFree2 | `ordTmr = createPendingOrder(…, [{P, [D(1)], 1, [M]}])` → E6 原價 | — | `dueAt = T + H(2)`；`escalateAt = max(TPE0(D(1)), T + H(2)) + H(2)` | API JSON |
| Q-38 | AC-2.5-3（BR-11） | ownerMuted、sitterFree2 | `ordUrg = createPendingOrder(ownerMuted, sitterFree2, [{P, [D(1)], 1, [K]}])` → E6 原價 → 呼叫 C2 `POST /api/internal/cron/payments/reminder`（`X-Internal-Secret`） | — | `notificationsOf(ownerMuted, ordUrg)`：恰 1 則類型 `QUOTE_SENT_WITH_PAYMENT_REMINDER`（`T ≥ TPE0(D(1)) − H(48)`）；C2 → 200 之後再查仍只有這 1 則、無 `PAYMENT_REMINDER`（Email 不可關閉見 §4） | API JSON |

### 2.6 手動調價與快照（describe「調價」）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-40 | AC-2.2 | sitterPro | `ordAdj = createPendingOrder(owner, sitterPro, 標準, answers)`；開評估頁點 `sitter-order-eval-btn-quote-adjust`，`sitter-order-eval-input-surcharge` 填 300、`-input-discount` 填 100、`-input-pricing-notes` 填「車馬費；首次優惠」，點 `sitter-order-eval-btn-quote-submit` | 原因欄標示含「飼主會看到」；送出後徽章「待付款」 | E6 → 200：`baseAmount=1800`、`surchargeAmount=300`、`discountAmount=100`、`totalAmount=2000`、`payment.amount=2000` | 截圖 + API JSON |
| Q-41 | AC-2.2（BR-16） | owner | 開 `/owner/orders/{ordAdj}` | `owner-order-detail-quote-breakdown` 依序含：原始試算 1,800、加價 300、折扣 100、原因：車馬費；首次優惠、總額 2,000（金額格式見 §5 #2） | E2 `quote` = `{surchargeAmount:300, discountAmount:100, pricingNotes:"車馬費；首次優惠", totalAmount:2000}`、`baseAmount=1800` | 截圖 + API JSON |
| Q-42 | AC-2.2 | sitterUlt | `createPendingOrder(owner, sitterUlt, 標準)` → E6 `{expectedAmount:1800, surchargeAmount:300, discountAmount:100, pricingNotes:"車馬費；首次優惠"}` | — | 200、`totalAmount=2000` | API JSON |
| Q-43 | AC-3.2-1（EX-6） | sitterFree | `ordFreeAdj = createPendingOrder(owner, sitterFree, 標準)`；開評估頁點 `sitter-order-eval-btn-quote-adjust` | 按鈕帶鎖頭圖示；點擊後出現「手動調價為專業版功能」與升級入口；不出現 `sitter-order-eval-input-surcharge` | — | 截圖 |
| Q-44 | AC-3.2-2（EX-6） | sitterFree | E6 `{expectedAmount:1800, surchargeAmount:100, pricingNotes:"車馬費"}`；新 key E6 `{expectedAmount:1800, discountAmount:100, pricingNotes:"優惠"}`；`sitter-setup` 改 `tier=BASIC` 後重送前者（新 key） | — | 三次皆 403 `PLAN_REQUIRED`；**未寫入** | API JSON |
| Q-45 | AC-2.3 | sitterPro、owner | `ordAdj`（Q-40 已報價）之後：保母在方案管理（PRD-003）把 `P` 單價改為 800、方案名改為「到府餵食（新）」，`sitter-setup` 改 `tier=BASIC`；讀保母 E2；飼主再 `createPendingOrder(owner, sitterPro, 標準)` | — | E2(`ordAdj`) `groups[0].unitPrice=600`、`groups[0].planTitle=到府餵食`、`quote.totalAmount=2000`；新訂單 E1 `baseAmount=2400`（媒體額度 / 保留期限快照見 §4） | API JSON |

### 2.7 金額與欄位驗證（describe「驗證」：`sitterPro3`（PRO）、`ordV = createPendingOrder(owner, sitterPro3, 標準)`）

> 每次呼叫用新 key；每列結尾斷言 **未寫入**。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-50 | AC-3.3-1（EX-8） | sitterPro3 | E6 `{expectedAmount:1800, discountAmount:1800, pricingNotes:"全額折扣"}` | — | 422 `INVALID_QUOTE_AMOUNT` | API JSON |
| Q-51 | AC-3.3-1（EX-8） | sitterPro3 | UI 調價：折扣填 1800、原因「全額折扣」，送出 | 「報價總額至少需 NT$1」；徽章仍「待評估」 | 頁面發出的 E6 回 422 `INVALID_QUOTE_AMOUNT` | 截圖 + API JSON |
| Q-52 | AC-3.3-3（EX-9） | sitterPro3 | 依序 E6：`surchargeAmount:-100`；`surchargeAmount:100.5`（JSON 數字字面值）；`surchargeAmount:1000000`；`discountAmount:-1`；`surchargeAmount:100, pricingNotes:"   "`；`surchargeAmount:100, pricingNotes:` 去空白後 201 字；`surchargeAmount:100` 不帶 `pricingNotes`（以上皆帶 `expectedAmount:1800`，金額欄非該列者省略） | — | 每次 400 `VALIDATION_FAILED`；`payload.field` 依序 = `surchargeAmount`、`surchargeAmount`、`surchargeAmount`、`discountAmount`、`pricingNotes`、`pricingNotes`、`pricingNotes` | API JSON |
| Q-53 | AC-3.3-4（EX-9） | sitterPro3 | E6 `{expectedAmount:1800, surchargeAmount:999000, pricingNotes:"上限測試"}`（總額 1,000,800） | — | 400 `VALIDATION_FAILED`、`payload.field=totalAmount` | API JSON |
| Q-54 | AC-3.3-5（EX-9） | sitterPro3 | E6 body `{}`；E6 `{expectedAmount:1800}` 但不帶 `Idempotency-Key` 標頭 | — | 400 `VALIDATION_FAILED`、`payload.field` 分別 = `expectedAmount`、`Idempotency-Key` | API JSON |
| Q-55 | AC-3.3-2 | sitterPro3 | E6 `{expectedAmount:1800, discountAmount:1799, pricingNotes:` 前後各 2 個空白 + 恰 200 字 `}` | — | 200、`totalAmount=1`、`payment.amount=1` | API JSON |

### 2.8 冪等與跨角色併發（describe「冪等併發」：`sitterPro`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-60 | AC-2.6-1（EX-18） | sitterPro | `ordI = createPendingOrder(…標準)`；E6 key `K` `{expectedAmount:1800}` → 回應 `B1`；同 key 同 body 再送 | — | 第二次 200，body 與 `B1` 完全相同（含 `quotedAt`、`payment.paymentId`）；E10 長度 1；`notificationsOf(owner, ordI)` 報價通知仍 1 則 | API JSON |
| Q-61 | （EX-19） | sitterPro | key `K`、body `{expectedAmount:1799}` | — | 422 `IDEMPOTENCY_KEY_REUSED`；E10 仍 1 筆 | API JSON |
| Q-62 | AC-2.6-2（EX-15） | sitterPro | 新 key `{expectedAmount:1800}` | — | 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=PENDING_PAYMENT` | API JSON |
| Q-63 | AC-2.6-3（EX-16） | owner、sitterPro | 3 輪，每輪新訂單：同時送出（`Promise.all`）飼主 E8 `{expectedStatus:"PENDING"}` 與保母 E6 `{expectedAmount:1800}` | — | 每輪恰一方 200、另一方 409 `ORDER_STATE_CONFLICT`（`currentStatus` = 勝方造成的狀態）；最終二擇一：E2 `status=CANCELLED`、`cancellation.cancelledBy=OWNER`、E10 `[]`；或 E2 `status=PENDING_PAYMENT`、E10 長度 1。誰勝不固定，確定性覆蓋見 §4 | API JSON（每輪） |

### 2.9 評估期限（describe「期限」：`sitterPro`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-70 | AC-3.4-1（EX-10） | sitterPro | `ordDL = createPendingOrder(…標準)`；`shiftReviewExpiry(ordDL, −(距到期分鐘數 + 5))`；讀 E2 得新值 `R'`；E6 `{expectedAmount:1800}` | — | E2 `reviewExpiresAt=R'` 且 `R'` < now；E6 409 `DEADLINE_PASSED`、`payload.deadline=REVIEW_EXPIRES_AT`、`payload.deadlineAt=R'`；**未寫入** | API JSON |
| Q-71 | AC-3.4-1（EX-10） | sitterPro | 開評估頁點 `sitter-order-eval-btn-quote-original` | 「此預約已超過評估期限」；三個報價按鈕 disabled；`sitter-order-eval-btn-decline` enabled | 頁面發出的 E6 回 409 `DEADLINE_PASSED` | 截圖 + API JSON |

### 2.10 月接單額度（describe「月額度」：`sitterQuota`（FREE），所有訂單皆標準日期 `[D(10)]`、單日，`baseAmount=600`）

> 同日多張都能報價，順帶驗證 BR-17（不做容量 / 檔期控管）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-80 | AC-3.5-3（前置） | owner、sitterQuota | 建 `o1`–`o6`；`o1`、`o2` 報價後 `payAndConfirm` | — | `o1`、`o2` E14 `orderStatus=CONFIRMED` | API JSON |
| Q-81 | AC-3.5-3、BR-17 | sitterQuota | `o3`、`o4`、`o5` 依序 E6 原價；接著 `o6` E6 原價 | — | 四次皆 200（`o6` 報價時本月 2 筆 `CONFIRMED` + 3 筆 `PENDING_PAYMENT`，待付款不計數） | API JSON |
| Q-82 | AC-3.5-1（前置） | owner、sitterQuota | `o3` `payAndConfirm` | — | 本月 `CONFIRMED` = 3 | API JSON |
| Q-83 | AC-3.5-1（EX-12） | sitterQuota | 建 `o7` → E6 原價 | — | 422 `MONTHLY_QUOTA_REACHED`；**未寫入** | API JSON |
| Q-84 | AC-1.5-2（EX-12） | sitterQuota | 開 `o7` 評估頁 | banner 含「本月額度已滿」與升級入口；三個報價按鈕 disabled；`sitter-order-eval-btn-decline` enabled | E5 200、`precheck.quotaReached=true` | 截圖 + API JSON |
| Q-85 | BR-12 | owner、sitterQuota | `o4` `payAndConfirm` | — | E14 200、`orderStatus=CONFIRMED`（已報價待付款者不受上限影響，本月確認數成為 4） | API JSON |

### 2.11 畫面金額過期（describe「金額過期」：`sitterPrice`，設定同 `sitterPro`、`questions = []`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| Q-90 | AC-3.7-1（EX-13） | sitterPrice | `ordP = createPendingOrder(…標準)`；開評估頁（原始試算 `$ 1,800`）；另以 API 在方案管理把 `P` 改為 700；回頁面點 `sitter-order-eval-btn-quote-original` | 提示「訂單金額已更新為 NT$2100，請確認後再送出」；原始試算更新為 2,100（格式見 §5 #2）；徽章仍「待評估」 | 頁面發出的 E6（`expectedAmount=1800`）→ 409 `PRICE_CHANGED`、`payload.baseAmount=2100`、`payload.totalAmount=2100`；**未寫入** | 截圖 + API JSON |
| Q-91 | AC-3.7-1 | sitterPrice | 再點 `sitter-order-eval-btn-quote-original` | 徽章「待付款」 | 頁面發出的 E6 帶新 key、`expectedAmount=2100` → 200、`totalAmount=2100` | 截圖 + API JSON |
| Q-92 | AC-3.7-1（BR-7） | sitterPrice | `ordP2 = createPendingOrder(…標準)`（建立時單價 700，base 2100）；方案改為 800；E6 `{expectedAmount:2100, surchargeAmount:300, discountAmount:100, pricingNotes:"車馬費"}` | — | 409 `PRICE_CHANGED`、`payload.baseAmount=2400`、`payload.totalAmount=2600` | API JSON |
| Q-93 | AC-3.7-2 | sitterPrice | `ordP3 = createPendingOrder(…標準)`（單價 800，base 2400）；在方案管理停用 `P`；E6 `{expectedAmount:2400}` | — | 200、`baseAmount=2400`、E2 `groups[0].unitPrice=800` | API JSON |

### 2.12 模組（mock）：真後端造不出的回應

> 檔案 `frontend/e2e/order-eval.spec.ts`；`mockDemoSession(page, 'SITTER')`，E5 回 `PENDING` 訂單（`baseAmount=1800`，`precheck` 全 false、`planAllowsAdjustment=true`）；`afterEach` 呼叫 `assertNoUnmockedApi`。

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
| Q-E2 | BR-22（`PLAN_REQUIRED` 先於 `SUBSCRIPTION_LOCKED`） | FREE 保母建單後鎖定（`subscriptionExpiresAt=D(-2)`），E6 帶 `surchargeAmount:100, pricingNotes:"x"` | 403 `PLAN_REQUIRED` |
| Q-E3 | BR-22（`PAYOUT_ACCOUNT_REQUIRED` 先於 `SUBSCRIPTION_LOCKED`） | `sitterNoPay` 建單後鎖定，E6 原價 | 422 `PAYOUT_ACCOUNT_REQUIRED` |
| Q-E4 | BR-22（`DEADLINE_PASSED` 先於業務規則） | `sitterNoPay` 建單、`shiftReviewExpiry` 到過去，E6 原價 | 409 `DEADLINE_PASSED` |
| Q-E5 | BR-22（格式先於狀態） | 已 `PENDING_PAYMENT` 的訂單，E6 `surchargeAmount:-1` | 400 `VALIDATION_FAILED`（不是 409） |
| Q-E6 | BR-22（`INVALID_QUOTE_AMOUNT` 先於 `PLAN_REQUIRED`） | FREE 保母，E6 `{expectedAmount:1800, discountAmount:1800, pricingNotes:"x"}` | 422 `INVALID_QUOTE_AMOUNT` |
| Q-E7 | BR-22（`PRICE_CHANGED` 先於 `PLAN_REQUIRED`） | FREE 保母，E6 `{expectedAmount:1799, surchargeAmount:100, pricingNotes:"x"}` | 409 `PRICE_CHANGED`、`baseAmount=1800`、`totalAmount=1900` |
| Q-E8 | （資料污染） | PRO 保母調價，原因填 `<img src=x onerror=alert(1)>`（加價 1） | 200；飼主 `owner-order-detail-quote-breakdown` 原樣顯示該字串為文字，頁面未觸發任何 dialog；E2 `quote.pricingNotes` 與輸入相同 |
| Q-E9 | EX-1（他人訂單不洩漏） | `sitterOther` 對 `ordAuth` 帶完全合法 body 與 key 呼叫 E6 後，`sitterPro` 以同一 key 同 body 呼叫 E6 | 前者 403 `FORBIDDEN`；後者 200（冪等範圍含使用者，不得回放他人的 403） |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-2.1-1 的 `order_logs` `ORDER_QUOTED`（payload 四個金額、`reminderMerged`）；AC-2.1 / AC-2.5-3 的 Email 投遞紀錄 | 無對外 API 讀 `order_logs` 與 Email 投遞紀錄；Resend 為測試模式只能寄專案擁有者 | `QuoteIntegrationTest`（`notifications` 列數、`mandatory`、`order_logs` payload）；Email 管道由 SD-NOTIFY 測試；真實寄信手動驗證 |
| AC-2.1-2（偏好關閉無 Email） | 同上 | `QuoteIntegrationTest` |
| AC-2.3 媒體額度（PRO 10 照片 + 2 影片）、保留期限 90 天快照 | `order_snapshots` 無任何對外讀取端點 | `QuoteIntegrationTest` |
| AC-2.4 精確時刻（2026-12-01 10:00 報價 → `quoted_at=2026-12-01T02:00:00Z` 等字面值）、AC-2.5-1 / 2 的字面時刻 | 正式站無法注入 `Clock`；E2E 以 §1 公式對回應的 `quotedAt` 驗證 | `QuoteIntegrationTest`（注入 `Clock`） |
| AC-2.6-3 確定性、AC-2.6-4（列鎖逾時 3 秒 → 409 `ORDER_BUSY` + `Retry-After: 1`）、EX-17 後端部分 | 正式站無法穩定控制交錯、無法持有列鎖 | `QuoteConcurrencyIntegrationTest` |
| EX-19 同 key 處理中 409 `IDEMPOTENCY_IN_PROGRESS`（後端） | 需控制處理中時間窗 | `QuoteConcurrencyIntegrationTest`、§10.2 冪等共通必測 |
| AC-3.4-2（`now = review_expires_at` 整點） | 需精確時間 | `QuoteIntegrationTest` |
| AC-3.4-3（首個行程日期已過但未逾期） | 需在台北 23:30 送單並跨日報價；E1 不收過去日期，§10.4 無平移 `first_service_date` 的端點 | `QuoteIntegrationTest`；畫面文案由 QM-02 |
| AC-3.5-1 中「其中 1 筆之後已取消仍計數」、AC-3.5-2（跨月 `2026-12-31T16:00:00Z` 後可報價）、AC-3.5-4（BASIC 20、PRO / ULTIMATE 50、PRO 5 筆後降 FREE） | 取消 `CONFIRMED` 需走變更協商、跨月需注入時間、造 20 / 50 筆成本過高 | `QuoteIntegrationTest` |
| AC-3.6 「到期日隔天 00:00（Asia/Taipei）」邊界 | 需精確時間 | `QuoteIntegrationTest` |
| EX-20 Resend 失敗不影響轉移 | 正式站無法注入故障 | SD-NOTIFY 排程測試；`QuoteIntegrationTest`（Resend mock 不在交易內呼叫） |
| EX-21 GCS | 不適用（本功能無上傳） | — |
| EX-22 `@Version` 衝突 → 409 `ORDER_BUSY` | 列鎖之後的最後防線，正式站無法觸發 | SD-ORDER §10.3 未指名類別（見 §5 #6）；建議 `QuoteConcurrencyIntegrationTest` |
| BR-19 主交易回滾時 log / 通知不存在 | 需注入交易失敗 | `OrderLogWriterTest`、`QuoteIntegrationTest` |

## 5. 已知限制 / 待補

| # | 項目 | 影響 | 建議預設值 / 處理 |
|---|------|------|------------------|
| 1 | §10.4 `provision-account` 未寫明能否建 ADMIN、能否指定顯示名稱；越權步驟需要「同一帳號切成 OWNER 角色」的 token | Q-11、Q-12、Q-16、Q-17、Q-01「王小明」 | 請後端確認 `provision-account` 支援 `role=ADMIN`、`displayName`，且 SITTER 帳號可經角色切換取得 OWNER token |
| 2 | 金額格式矛盾：PRD-006 AC-2.2、AC-3.7、EX-8、EX-13 寫「NT$1800」，SD-FRONTEND-SPEC §6.4.1 規定畫面一律 `formatMoney` → `$ 1,800` | Q-33、Q-41、Q-51、Q-90 畫面斷言 | 暫依 SD-FRONTEND-SPEC：畫面斷言 `$ 1,800`，提示文案中的數字依同格式；通知內文（SD-NOTIFY 範本）依 PRD 字面「NT$1800」。需使用者裁定 |
| 3 | `sitter-setup` 是否可重複呼叫並只更新指定欄位（upsert）未寫明；本 TS 用它做降級、鎖定、停權、恢復 | Q-20、Q-22、Q-44、Q-45、Q-E2、Q-E3 | 請後端確認為 upsert；否則需另給「改 tier / 訂閱到期 / 停權」的測試端點 |
| 4 | 改方案單價、改名、停用（PRD-003）、改 / 停用問卷題目（PRD-004）、刪毛孩（PRD-002）、設定收款帳號（PRD-025）都要走 SD-SUPPLY / SD-CARE 的正式 API，這些 SD 尚未重建 | Q-03、Q-04、Q-26、Q-45、Q-90–Q-93 | e2e-author 依當下正式 API 對應；若重建後路徑改變需同步 |
| 5 | 通知查詢 API 屬 SD-NOTIFY（未撰寫），且通知類型代碼（`QUOTE_SENT` 等）是否對外輸出未定 | Q-32、Q-35、Q-38、Q-60 | 若 API 不回類型代碼，改以內文斷言：附提醒者內文含付款提醒字樣（範本由 SD-NOTIFY 擬定） |
| 6 | SD-ORDER §10.3 沒有任何類別列出 EX-22（`@Version` → `ORDER_BUSY`） | 後端覆蓋缺口 | 建議補進 `QuoteConcurrencyIntegrationTest` 或 `LockFreshnessIntegrationTest` |
| 7 | E5 沒有 `serverTime` 也沒有「已逾評估期限」的預檢欄位，PRD-006 EX-10「報價按鈕停用」只能在 409 之後成立 | Q-71 只驗證送出後的狀態 | 若要求開頁即停用，需 E5 補欄位（SD 變更） |
| 8 | E2 契約只說 `groups[].unitPrice`、`planTitle`，未明寫報價後取 `order_plan_snapshots`（§3.5 只對 `planTitles` 有註記） | Q-45、Q-93 | 依 PRD-006 BR-8 預期報價後為快照值；實作若取即時單價即為 bug |
| 9 | Q-36 / Q-37 依「今天 / 明天」建單；台北 23:50–00:10 執行時送單與報價可能跨日 | 偶發 `FIRST_VISIT_DATE_PASSED` | spec 於該時段 `test.skip` 並註明原因 |
| 10 | Q-38 呼叫 C2、Q-E4 等依時間平移；正式站 C2 / C1 也由 Cloud Scheduler 每 15 分鐘觸發 | 呼叫正式排程會一併處理正式站其他到期資料（其本來就會被處理） | 可接受；斷言只看本測試訂單 |
| 11 | PRD-006 AC-1.5 要求鎖定 / 額度滿 / 缺收款帳號時 `-btn-quote-adjust` 停用；AC-3.2 要求 FREE / BASIC 的調價按鈕可點並顯示升級提示。FREE 且額度已滿時兩者衝突 | Q-84 | 建議預檢成立時一律停用（AC-1.5 優先），Q-84 依此斷言 |
| 12 | 無對外 API 可讀 `order_logs` | §4 第 1 列只能靠後端 | 建議 §10.4 增 `GET /api/internal/test-data/orders/{orderId}/logs`（唯讀，`@e2e-journey.test` 限定），之後可把 `ORDER_QUOTED` 斷言移回 E2E |

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依新範本全文重寫，狀態 Draft。舊版 Scenario 1–10（`AUTH_PLAN_LIMIT`、樂觀鎖 version、`/confirm`、`/reject`、飼主 `/cancel`、`OrderEvaluationTest` 等）與舊實作綁定，全數作廢；婉拒移至 TS-027、飼主撤回移至 TS-026。舊版「本測試發現並修復的真實 bug」屬舊實作，不沿用 | PRD-006、SD-ORDER Approved 2026-11-22；全面重建 2026-11 |
