# TS-JOURNEY-02: 預約到付款成立（Booking to Payment）

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 對應 PRD | PRD-005（送出預約）、PRD-006（評估與報價）、PRD-007（線下付款上傳與核對）、PRD-025（保母收款帳號）、PRD-026（飼主撤回）、PRD-027（保母婉拒 / 付款前取消）；皆 Approved 2026-11-22。共用定義依 `docs/sa/00-DOMAIN-BASELINE.md` §3.2 O1、O2、O3、O5、O6、O7，§3.3，§3.6，§5 |
| 對應 SD | SD-ORDER（§6.3 E1–E15、E31；§7.1 `order_logs`、§7.2 通知；§9.1 路由與 testid、§9.3 補定 testid；§10.4 測試支援：`provision-account` `roles[]`、`sitter-setup` / `owner-setup` upsert、`inspect`、排程 `?orderId=`、cleanup）；SD-FRONTEND-SPEC §4（testid 命名）、§6.4（`order-status-badge` 狀態文字）、§6.4.1（金額格式）。PRD-025 收款帳號 API / 畫面屬 SD-SUPPLY（**尚未撰寫**，§5-1） |
| 類型 | 旅程（打真後端）：跨 PRD 主線 + 少數跨角色關鍵分支；細項邊界由模組 TS 承擔 |
| 自動化路徑 | `frontend/e2e/journeys/booking-to-payment.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD、SD、DOMAIN-BASELINE 撰寫，**未參考程式碼**。照程式寫出的 TS 只會把 bug 驗證成規格。
> 取代舊版 TS-JOURNEY-02（PRD-000 / 002 / 021 舊 SD 版本、打舊 API `/api/orders/booking`、`/confirm`、`/payment-proof`、`/verify-payment`、`/reject`）。舊版「越權 / 401 不對正式站探測」規則作廢：本版依範本，越權一律以另一個真實 `@e2e-journey.test` 帳號打 API（§3）。

---

## 0. 定位、共用約定與涵蓋範圍

### 0.1 定位

本旅程只驗證「跨 PRD、跨角色才看得到」的主線與關鍵分支：飼主送單 → 保母評估報價 → 飼主上傳憑證 → 保母核對 → `CONFIRMED`；分支為駁回後重傳、付款前飼主撤回、保母婉拒、收款帳號未設定時先設定再報價。每一步標出依據的 PRD AC 與對應模組 TS 步驟 ID（括號內）。欄位邊界、錯誤碼先後、冪等全集、併發、排程邊界不在本旅程重複，見各模組 TS（TS-005 / 006 / 007 / 025 / 026 / 027）。

### 0.2 共用約定

| 項目 | 約定 |
|------|------|
| 端點代號 | E1–E31 = SD-ORDER §6.2 / §6.3；`{O}` = 該段使用的訂單 id、`{P}` = INITIAL paymentId、`{X}` / `{Y}` = submissionId |
| 日期 | `F` = 以 E31 `serverTime` 換算 Asia/Taipei 的今天 + 7 天（`YYYY-MM-DD`）。F 距今 7 天：不落入緊急窗、報價時未過付款提醒點（首日 − 48h）。不用執行機時鐘 |
| 跨日避開 | spec 開始時若 `serverTime` 換算 Asia/Taipei 在 23:50–00:10 之間，等待到 00:10 後再跑（同 TS-005 §0） |
| 金額 | 畫面依 SD-FRONTEND-SPEC §6.4.1（`$ 1,800`，`$` 後為 U+00A0）；API 為整數 |
| null 欄位 | 回應中值為 null 的欄位不輸出（SD-ORDER §6.1）；「= null」一律斷言為「欄位不存在」 |
| 時間顯示 | 畫面期限一律 Asia/Taipei `YYYY-MM-DD HH:mm`；預約頁確認區依 PRD-005 §4.4 為 `YYYY/MM/DD HH:mm` |
| inspect | `GET /api/internal/test-data/orders/{orderId}/inspect`（`X-Internal-Secret`，SD-ORDER §10.4）：`status`、`totalAmount`、`orderLogs[]`（`actionType`、`operatorRole`、`operatorUserId`、`payload`、`createdAt`）、`notifications[]`（`type`、`recipientUserId`、`recipientRole`、`mandatory`、`emailStatus`）、`payments[]`（`paymentId`、`direction`、`purpose`、`amount`、`status`、`remindAt`、`remindedAt`、`escalateAt`、`verifiedAt`、`voidedAt`）、`fulfill.visits[]`（`visitId`、`serviceDate`、`planId`、`status`）。計數時排除 `TEST_TIME_SHIFTED` / `TEST_VISIT_FORCED` |
| 「Email 應寄」 | 先呼叫 C11 `POST /api/internal/cron/notifications/email-dispatch?orderId={O}`（`X-Internal-Secret`），再讀 inspect：該則 `emailStatus=SUPPRESSED_TEST`（收件者為 `@e2e-journey.test`、依偏好本應寄出，C11 不呼叫 Resend；SD-ORDER §10.4）。值域 `PENDING` / `SENT` / `SKIPPED_PREFERENCE` / `FAILED` / `NOT_APPLICABLE` / `SUPPRESSED_TEST`；本旅程所有收件者偏好皆開，Email 斷言一律收斂為 `SUPPRESSED_TEST` |
| 狀態文字 | 訂單徽章依 SD-FRONTEND-SPEC §6.4；付款（待付款 / 待核對 / 已確認 / 已作廢）、憑證（待核對 / 已確認 / 已駁回）等子流程狀態文字「依 SD-FRONTEND-SPEC §6.4」（使用者 2026-11-22 確認） |
| 站內通知文案 | type / `mandatory` / `emailStatus` 以 inspect 斷言；標題、內文、連結由收件者以自己的 token 呼叫站內通知列表 `GET /api/notifications?role={OWNER|SITTER}` 核對（SD-NOTIFY 未重建，沿用現行端點，§5-2） |
| 「僅產生單筆」查法 | 訂單數：飼主 E3 `GET /api/orders/owner?tab=EVALUATING` 與保母 E4 `GET /api/orders/sitter?tab=EVALUATING` 的 `totalElements`；payment 數：E10 陣列長度；submission 數：E12 陣列長度；log / 通知數：inspect `orderLogs` / `notifications` 依 `actionType` / `type` 計數 |
| 頁面請求攔截 | 「頁面發出的 Ex 恰 1 次」= spec 以 `page.on('request')` 記錄該路由樣板的請求數，並斷言帶 `Idempotency-Key` 標頭、body 不含 `ownerId` / `sitterId`（呼叫者身分取自 token，SD-ORDER §6.1；E1 的 `sitterId` 為操作對象例外） |
| 證據 | UI 步驟截圖；API 斷言以 `apiStep()` 留 JSON（token、`X-Internal-Secret`、`X-Goog-Signature` / `X-Goog-Credential` 遮蔽，SD-FRONTEND-SPEC §4.1） |

### 0.3 涵蓋範圍（PRD AC → 本旅程步驟）

| PRD | AC | 本旅程步驟 | 模組 TS 主要覆蓋 |
|-----|----|-----------|----------------|
| PRD-005 | AC-1.1-情境 1、2、3 | J2-01、J2-02、J2-E1 | TS-005 PB-01–PB-03 |
| PRD-005 | AC-2.1（單群組試算） | J2-01 | TS-005 §2.2 |
| PRD-005 | AC-2.4-情境 1（毛孩預設勾選） | J2-02 | TS-005 §2.2 |
| PRD-005 | AC-3.1、AC-3.2-情境 1 | J2-03、J2-04、J2-05 | TS-005 PB-56–PB-59 |
| PRD-005 | AC-3.4-情境 1（同 key 重送不建第二張） | J2-E2 | TS-005 PB-67 |
| PRD-005 | AC-3.5（新客戶填問卷） | J2-02、J2-04、J2-06 | TS-005 PB-70–PB-73 |
| PRD-005 | AC-3.8-情境 1（我的最愛、新預約通知） | J2-05 | TS-005 PB-92 |
| PRD-006 | AC-1.1-情境 1、AC-1.2-情境 1 | J2-06 | TS-006 Q-01、Q-02 |
| PRD-006 | AC-1.4-情境 1、AC-3.1-情境 1 | J2-E3 | TS-006 Q-10、Q-15 |
| PRD-006 | AC-1.5-情境 3、AC-3.8 | J2-51、J2-54 | TS-025 PA-03、PA-07 |
| PRD-006 | AC-2.1-情境 1（原價接單） | J2-21、J2-31、J2-54 | TS-006 Q-30 |
| PRD-006 | AC-2.2（PRO 調價、報價明細） | J2-07、J2-10 | TS-006 Q-40、Q-41 |
| PRD-006 | AC-2.4（首次收款與期限） | J2-07、J2-08 | TS-006 Q-30、Q-31 |
| PRD-006 | AC-2.7（報價後不可編輯） | J2-09 | TS-006 Q-34 |
| PRD-007 | AC-1.1（付款頁與帳號快照） | J2-04、J2-10 | TS-007 P-02、P-04 |
| PRD-007 | AC-1.3（讀取與上傳越權） | J2-E4 | TS-007 P-11 |
| PRD-007 | AC-1.4（上傳成功） | J2-11、J2-12、J2-23 | TS-007 P-17、P-18 |
| PRD-007 | AC-1.7（VOID 後上傳） | J2-33 | TS-007 P-35 |
| PRD-007 | AC-2.1（確認 → 訂單成立） | J2-14、J2-15、J2-24 | TS-007 PV-02、PV-03 |
| PRD-007 | AC-2.3（駁回、重新上傳） | J2-21、J2-22、J2-23 | TS-007 PV-07–PV-09 |
| PRD-007 | AC-2.5（確認權限） | J2-E6 | TS-007 PV-20、PV-21 |
| PRD-007 | AC-2.7（憑證檢視與簽名網址） | J2-13、J2-24 | TS-007 PV-30、PV-31 |
| PRD-025 | AC-1.1（首次設定、未設定不能報價） | J2-51–J2-54 | TS-025 PA-01–PA-07 |
| PRD-025 | AC-1.4（快照為報價當下帳號） | J2-10、J2-54 | TS-025 PA-21–PA-23 |
| PRD-026 | AC-1.2（報價後付款前撤回） | J2-31、J2-32 | TS-026 WD-13、WD-14 |
| PRD-026 | AC-1.3（已上傳憑證不可撤回） | J2-11、J2-E5 | TS-026 WD-15、WD-16 |
| PRD-026 | AC-1.4-情境 1、2（付款後與終態不可撤回） | J2-16、J2-44 | TS-026 §2 |
| PRD-026 | AC-1.6-情境 1、2（越權與角色） | J2-E7 | TS-026 WD-29、WD-30 |
| PRD-027 | AC-1.1-情境 1、AC-1.2-情境 1–3、AC-1.5-情境 3 | J2-41–J2-45 | TS-027 QR-01–QR-10 |
| PRD-027 | AC-1.3-情境 2（他人婉拒） | J2-E8 | TS-027 QR-17 |
| PRD-027 | AC-3.2-情境 1、2（已上傳不可取消、駁回後可取消） | J2-13、J2-E5、J2-21 | TS-027 §2.3 |
| PRD-027 | AC-3.3-情境 1、2（狀態不允許取消） | J2-16、J2-33 | TS-027 §2.3 |

其餘 AC（門禁、格式上限、冪等全集、期限邊界、排程、ADMIN 作廢、併發、通知偏好關閉）不在本旅程，由上表右欄的模組 TS 與 SD-ORDER §10.3 後端測試覆蓋。

## 1. 前置資料

> 不寫死 UUID。每個 `test()` 自己 provision、可獨立重跑；所有帳號 email 為 `{代號}-{runId}@e2e-journey.test`，以 `POST /api/internal/test-data/provision-account`（`email`、`password`、`displayName`、`roles[]`）建立後走真實登入取 token。測試資料端點皆帶 `X-Internal-Secret`（值取自 CI secret，不寫進 spec 或證據）。`sitter-setup` / `owner-setup` 為 upsert，方案 / 題目 / 毛孩以 `key` 對應回應中的 id（SD-ORDER §10.4）。

### 1.1 帳號

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `sitter` | SITTER | `provision-account`：`roles=["SITTER"]`、`displayName=旅程保母`；`sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`subscriptionExpiresAt` = `serverTime` + 30 天、`suspended=false`、`payoutAccount` = X（`812` / `台北分行` / `12345678901` / `王小明`）、`plans=[{ key: "p1", 標題「到府貓咪照護」, 單價 600, 適用 CAT, active: true }]`、`questions=[{ key: "q1", 必填簡答「貓咪每天吃幾餐？」, active: true }]`、`emailPreferences.ORDER_AFFAIR=開`；回應取 `planId`（p1）、`questionId`（q1） | PRO、KYC 通過、已設收款帳號、可接單、有 1 題必填問卷 |
| `sitterNoPay` | SITTER | 同 `sitter`（`displayName=新手保母`），但 `payoutAccount=null` | 尚未設定收款帳號（只用於 §2.5） |
| `sitterB` | SITTER | 同 `sitter`（`displayName=他人保母`） | 「他人訂單的保母」（越權） |
| `owner` | OWNER | `provision-account`：`roles=["OWNER"]`、`displayName=旅程飼主`；`owner-setup`：`pets=[{ key: "cat1", 名稱「小橘」, 種類 CAT }]`、`favoriteSitterIds=[]`、`emailPreferences.ORDER_AFFAIR=開`；回應取 `petId`（cat1） | 非熟客（與 `sitter` 無 `COMPLETED` 訂單） |
| `owner2` | OWNER | 同 `owner`（`displayName=他人飼主`，自己的貓「小黑」） | 非當事人（越權） |

### 1.2 訂單 builder（分支用，主線一律走 UI）

| 代號 | 建立方式 | 狀態 |
|------|---------|------|
| `apiPending(s)` | `owner` E1 `POST /api/orders`（新 `Idempotency-Key`）：`sitterId=s`、`groups=[{ planId: s 的 p1, dates: [F, F+1, F+2], dailyCount: 1, petIds: [cat1] }]`、`answers=[{ questionId: s 的 q1, value: "早晚各一餐" }]`、`expectedAmount=1800` | 201、`PENDING`、`baseAmount=1800`、3 個 `PENDING` 行程 |
| `apiQuoted(s)` | `apiPending(s)` → s E6 `POST /api/orders/{O}/quote`：`expectedAmount=1800`（原價，不帶調整） | `PENDING_PAYMENT`；E6 回應 `payment.amount=1800`，記下 `paymentId` |
| `apiSubmitted(s, lastFive)` | `apiQuoted(s)` → `owner` E11 `POST /api/orders/{O}/payments/{P}/submissions`（multipart：`disclaimerAgreed=true`、`lastFive`、spec 產生的約 200 KB PNG） | INITIAL payment `SUBMITTED`，記下 `submissionId` |

- 測試圖檔由 spec 產生（PNG，帶正確 magic bytes），不放 repo。
- 需要的後端前置全部已在 SD-ORDER §10.4 定義（`roles[]`、`sitterProfile`、`sitter-setup` 的 `payoutAccount=null` / `questions[]` / `emailPreferences`、`owner-setup`、`inspect`）；本旅程不需要 shift-time 與排程觸發。

清除：`POST /api/internal/cron/test-data/cleanup-e2e-journeys`（SD-ORDER §10.4：訂單、行程、payment、submission 與 GCS 憑證硬刪除；跨模組範圍含通知、收款帳號與其稽核紀錄、方案、題目、我的最愛、毛孩、帳號）。

## 2. 測試步驟

> 步驟 ID 前綴 `J2-`：主線 J2-01–J2-17、分支 R J2-21–J2-24、分支 W J2-31–J2-33、分支 D J2-41–J2-45、分支 A J2-51–J2-54、權限例外 J2-E1–J2-E8。e2e spec 的 `step()` / `apiStep()` 標籤以同一 ID 開頭。
> spec 結構：§2.1 主線一個 `test()`（依序執行，§3 標了插入時機的例外列在同一 test 內執行）；§2.2–§2.5 各一個 `test()`，各自 provision、各自建單。
> AC 欄寫法：`PRD-NNN AC-x.y`，括號內為對應模組 TS 步驟 ID。

### 2.1 主線：匿名瀏覽 → 登入送單 → PRO 調價報價 → 上傳憑證 → 核對成立

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| J2-01 | PRD-005 AC-1.1-情境 1、AC-2.1（TS-005 PB-01） | 匿名 | 未登入開啟 `/booking/{sitter}`；新增群組，選方案 p1，日期選 F、F+1、F+2，每日 1 次 | 出現 1 個 `public-booking-plan-card-{p1}`；`public-booking-group-0` 出現；`public-booking-estimate-amount` 顯示 `$ 1,800`，下方「最終總額由保母評估後確認」；不出現任何 `public-booking-group-0-pet-checkbox-*` 與 `public-booking-question-*`；`public-booking-unavailable-message` 數量 0 | E31 `GET /api/sitters/{sitter}/booking-context`（無 token）→ 200、`bookability=BOOKABLE`、`plans` 長度 1（`unitPrice=600`）、無 `questionnaire` 欄位、`serverTime` 有值（spec 以此計算 F） | 截圖 + API JSON |
| J2-02 | PRD-005 AC-1.1-情境 2、AC-2.4-情境 1、AC-3.5（TS-005 PB-02、PB-70） | 匿名 → owner | 點 `public-booking-btn-submit`；在登入頁以 `owner` 的 Email、密碼登入（登入頁欄位與按鈕以畫面業務名稱定位，PRD-000 範圍，§5-4） | 導向登入頁；登入後回到 `/booking/{sitter}`，`public-booking-group-0` 仍在、方案 p1 與 F–F+2 保留、試算仍 `$ 1,800`；出現 `public-booking-group-0-pet-checkbox-{cat1}` 且為勾選；出現 `public-booking-question-{q1}`「貓咪每天吃幾餐？」並標「必填」；q1 未作答時 `public-booking-btn-submit` 停用 | 登入前後頁面發出的 E1 請求數 0；登入後 E31（帶 owner token）`questionnaire.required=true`、`questions` 含 q1 | 截圖 + API JSON |
| J2-03 | PRD-005 AC-3.2-情境 1（TS-005 PB-58） | owner | q1 填「早晚各一餐」，進入送出前確認區 | `public-booking-review-expires-at` =「保母需於 {F 以 YYYY/MM/DD 表示} 00:00 前回覆，逾時將自動取消」；`public-booking-urgent-hint` 數量 0；`public-booking-btn-submit` 可按 | — | 截圖 |
| J2-04 | PRD-005 AC-3.1、AC-3.2-情境 1、AC-3.5；PRD-007 AC-1.1（無 payment 不顯示付款區塊）（TS-005 PB-56、PB-59；TS-007 P-04） | owner | 點 `public-booking-btn-submit`；攔截頁面發出的 E1 | 導向 `/owner/orders/{O}`；`order-status-badge` 顯示「待評估」；不顯示付款區塊（`payment-amount`、`proof-submit-btn` 數量 0）；出現 `owner-order-detail-btn-withdraw` | 頁面發出的 E1 `POST /api/orders` 恰 1 次：帶 `Idempotency-Key`；body `sitterId=sitter`、`groups[0]` = `{ planId: p1, dates: [F, F+1, F+2], dailyCount: 1, petIds: [cat1] }`、`answers=[{ questionId: q1, value: "早晚各一餐" }]`、`expectedAmount=1800`、無 `ownerId`；回應 201 `{ orderId, status: "PENDING", baseAmount: 1800, reviewExpiresAt, questionnaireStatus: "COMPLETED" }`，`reviewExpiresAt` = F 前一天的 `T16:00:00Z`（= F 00:00 Asia/Taipei）；owner E2 `status=PENDING`、`payments=[]`、無 `quote` 欄位、`allowedActions` 含 `WITHDRAW`；owner E10 → 200 `[]`；記下 E1 的 key 與 body 供 J2-E2 | 截圖 + API JSON |
| J2-05 | PRD-005 AC-3.1、AC-3.8-情境 1（TS-005 PB-57、PB-92） | — | inspect `{O}`；`sitter` 呼叫站內通知列表；`owner` 讀我的最愛清單（PRD-019 現行 API，同 TS-005 PB-92） | — | inspect：`orderLogs` 恰 1 筆 `ORDER_CREATED`、`operatorRole=OWNER`、`operatorUserId=owner`、payload `baseAmount=1800`、`groupCount=1`、`visitCount=3`、`questionnaireStatus=COMPLETED`、無 `questionnaireExemptReason`；`notifications` 恰 1 筆 `NEW_BOOKING_REQUEST`、`recipientUserId=sitter`、`recipientRole=SITTER`、`mandatory=false`、Email 應寄（§0.2）；`fulfill.visits` 3 筆、`serviceDate` 依序 F、F+1、F+2、`planId=p1`、皆 `PENDING`；`sitter` 站內通知該則標題「新預約申請」、連結 `/sitter/orders/{O}`；`owner` 我的最愛含 `sitter` 恰 1 次；E3 `tab=EVALUATING` `totalElements=1`、E4 `tab=EVALUATING` `totalElements=1` | API JSON |
| J2-06 | PRD-006 AC-1.1-情境 1、AC-1.2-情境 1（TS-006 Q-01、Q-02） | sitter | 以 `sitter` 登入開啟 `/sitter/orders`，點 `sitter-orders-tab-evaluating`，點 `sitter-order-card-{O}` | 進入 `/sitter/orders/{O}`；顯示 3 個行程日期 F、F+1、F+2、每日 1 次、方案「到府貓咪照護」、單價 `$ 600`、原始試算 `$ 1,800`、飼主「旅程飼主」；出現 `sitter-order-eval-pet-{cat1}`；`sitter-order-eval-review-expires-at` 顯示「{F} 00:00」（Asia/Taipei `YYYY-MM-DD HH:mm`）；`sitter-order-eval-questionnaire-answers` 顯示「貓咪每天吃幾餐？」與「早晚各一餐」，問卷區無任何按鈕或輸入框；`sitter-order-eval-regular-badge`、`sitter-order-eval-precheck-banner` 數量 0；`sitter-order-eval-btn-quote-original`、`sitter-order-eval-btn-quote-adjust`、`sitter-order-eval-btn-decline` 可按；頁面無工項清單 / SOP 欄位 | E4 `tab=EVALUATING` 含 `{O}`；E5 `GET /api/orders/{O}/evaluation` → 200：`status=PENDING`、`baseAmount=1800`、`owner.displayName=旅程飼主`、`isRegularOwner=false`、`groups[0].unitPrice=600`、`groups[0].pets[0].petId=cat1`、`questionnaire.status=COMPLETED`、`answers` 長度 1（`questionText=貓咪每天吃幾餐？`、`answer=早晚各一餐`）、`precheck` = `{ subscriptionLocked: false, quotaReached: false, payoutAccountMissing: false, reviewExpired: false, planAllowsAdjustment: true }` | 截圖 + API JSON |
| J2-07 | PRD-006 AC-2.2、AC-2.4（TS-006 Q-40、Q-30） | sitter | 點 `sitter-order-eval-btn-quote-adjust`；`sitter-order-eval-input-surcharge` 填 300、`sitter-order-eval-input-discount` 填 100、`sitter-order-eval-input-pricing-notes` 填「車馬費；首次優惠」；點 `sitter-order-eval-btn-quote-submit`；攔截頁面發出的 E6 | 原因欄標示含「飼主會看到」；送出後留在 `/sitter/orders/{O}`、不整頁 reload（期間無 document 導覽請求），頁面以 E6 回應就地重新渲染：`order-status-badge` 顯示「待付款」、三顆報價鈕與調整輸入框消失（SD-ORDER §9.1） | 頁面發出的 E6 `POST /api/orders/{O}/quote` 恰 1 次：帶 `Idempotency-Key`；body `{ expectedAmount: 1800, surchargeAmount: 300, discountAmount: 100, pricingNotes: "車馬費；首次優惠" }`、無 `sitterId`；回應 200：`status=PENDING_PAYMENT`、`baseAmount=1800`、`surchargeAmount=300`、`discountAmount=100`、`totalAmount=2000`、`quotedAt` 有值、`payment.amount=2000`、`payment.dueAt` = max(F 00:00 台北 − 24h, `quotedAt` + 2h) = F 前兩天的 `T16:00:00Z`、`payment.escalateAt` = max(F 00:00 台北, `dueAt`) + 2h = F 前一天的 `T18:00:00Z`（spec 依 baseline §5 公式計算後逐字比對） | 截圖 + API JSON |
| J2-08 | PRD-006 AC-2.4（BR-11 報價通知）（TS-006 Q-31、Q-32） | — | inspect `{O}`；`owner` 呼叫站內通知列表 | — | inspect：`orderLogs` 恰 1 筆 `ORDER_QUOTED`、`operatorRole=SITTER`、`operatorUserId=sitter`、payload `baseAmount=1800`、`surchargeAmount=300`、`discountAmount=100`、`totalAmount=2000`、`pricingNotes=車馬費；首次優惠`、`paymentId={P}`、`reminderMerged=false`；`notifications` 恰 1 筆 `QUOTE_SENT`（非 `QUOTE_SENT_WITH_PAYMENT_REMINDER`）、`recipientUserId=owner`、`mandatory=false`、Email 應寄（§0.2）；`payments` 恰 1 筆：`paymentId={P}`、`direction=CHARGE`、`purpose=INITIAL`、`amount=2000`、`status=UNPAID`、有 `remindAt`、無 `remindedAt`；`totalAmount=2000`；`owner` 站內通知內文「保母已報價 $ 2,000，請於 {`dueAt` 換算 Asia/Taipei `YYYY-MM-DD HH:mm`} 前完成付款」、連結 `/owner/orders/{O}` | API JSON |
| J2-09 | PRD-006 AC-2.7、AC-2.2（BR-16 雙方報價明細）（TS-006 Q-34） | sitter | 瀏覽器返回（`page.goBack()`，SPA 導覽不 reload）到 `/sitter/orders`；再點 `sitter-order-card-{O}`；sitter 呼叫 E2；最後以新 key 呼叫 E6 `{ expectedAmount: 1800 }` | 不 reload 的列表中 `sitter-order-card-{O}` 的 `order-status-badge` 為「待付款」（E4 列表 query 已於報價成功時失效重抓，SD-ORDER §9.1），仍在「評估中」分頁（baseline §3.1）；訂單頁無 `sitter-order-eval-btn-quote-original`、`sitter-order-eval-btn-quote-adjust`、`sitter-order-eval-btn-quote-submit`、`sitter-order-eval-input-surcharge`、`sitter-order-eval-input-discount`、`sitter-order-eval-input-pricing-notes`；金額與行程為純文字；`owner-order-detail-quote-breakdown`（雙方共用）內 `order-detail-quote-original-total` 含原總額 `$ 2,000`、`order-detail-quote-current-total` 為 `$ 2,000`、`order-detail-quote-modification-*` 數量 0 | sitter E2：`quote.original.totalAmount=2000`、`quote.adjustments=[]`、`quote.totalAmount=2000`；`sitterTier=PRO`、`modificationPricingMode=CUSTOM_ALLOWED`（只回 SITTER）；`groups[0]` 無 `bookableFrom` / `bookableTo` 欄位（方案未設區間）；E6 → 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=PENDING_PAYMENT`；E10 仍恰 1 筆、`paymentId={P}`、`amount=2000`；inspect `ORDER_QUOTED` 仍 1 筆 | 截圖 + API JSON |
| J2-10 | PRD-006 AC-2.2（BR-16）；PRD-007 AC-1.1；PRD-025 AC-1.4；PRD-026 AC-1.2（入口）（TS-006 Q-41；TS-007 P-02；TS-026 WD-13） | owner | 開啟 `/owner/orders/{O}`；定位 `payment-block-{P}` | `owner-order-detail-quote-breakdown` 三段：`order-detail-quote-original-total` 依序含原始試算 `$ 1,800`、加價 `$ 300`、折扣 `$ 100`、原因：車馬費；首次優惠、原總額 `$ 2,000`；`order-detail-quote-modification-*` 數量 0（無調整時整段不顯示）；`order-detail-quote-current-total` 為 `$ 2,000`；`payment-block-{P}` 內 `payment-amount` 為 `$ 2,000`、付款狀態「待付款」（依 SD-FRONTEND-SPEC §6.4）、`payment-account-snapshot` 含「812」「台北分行」「12345678901」「王小明」、`payment-due-at` 為 `dueAt` 的 Asia/Taipei `YYYY-MM-DD HH:mm`、`submission-history` 顯示「尚未上傳憑證」、容器內無總額或差額公式；全頁 `order-total-amount` 數量 0；`owner-order-detail-btn-withdraw` 可見 | owner E2 `status=PENDING_PAYMENT`、`baseAmount=1800`、`quote.original` = `{ baseAmount: 1800, surchargeAmount: 300, discountAmount: 100, pricingNotes: "車馬費；首次優惠", totalAmount: 2000, quotedAt }`、`quote.adjustments=[]`、`quote.totalAmount=2000`；無 `sitterTier`、`modificationPricingMode` 欄位、`groups[0]` 無 `bookableFrom` / `bookableTo`（OWNER 不回，SD-ORDER E2）；`allowedActions` 含 `WITHDRAW`；owner E10 長度 1：`direction=CHARGE`、`purpose=INITIAL`、`method=OFFLINE_TRANSFER`、`amount=2000`、`status=UNPAID`、`accountSnapshot={ bankCode: "812", branch: "台北分行", accountNumber: "12345678901", accountName: "王小明" }`、無 `currentSubmissionId`、`allowedActions=["UPLOAD"]` | 截圖 + API JSON |
| J2-11 | PRD-007 AC-1.4；PRD-026 AC-1.3（畫面）（TS-007 P-17；TS-026 WD-16） | owner | 在 `payment-block-{P}` 勾 `proof-disclaimer-checkbox`、`proof-last5-input` 填 `12345`、`proof-file-input` 選約 200 KB PNG、點 `proof-submit-btn`；攔截頁面發出的 E11；完成後 reload 頁面 | 上傳表單消失；付款狀態「待核對」；`submission-history-item-{X}` 出現、後五碼 12345、狀態「待核對」（兩者依 SD-FRONTEND-SPEC §6.4）；`order-status-badge` 仍「待付款」；reload 後 `owner-order-detail-btn-withdraw` 數量 0，出現 `owner-order-detail-withdraw-blocked-message`「已上傳付款憑證，無法撤回；如需取消請與保母聯繫」 | 頁面發出的 E11 恰 1 次（multipart，帶 `Idempotency-Key`）；回應 201 `{ submissionId: X, paymentId: P, paymentStatus: "SUBMITTED", submittedAt }`，無 `url` 欄位；E10 `status=SUBMITTED`、`currentSubmissionId=X`；E12 恰 1 筆 `status=SUBMITTED`、`lastFive=12345`、有 `disclaimerAgreedAt`；E2 `status=PENDING_PAYMENT`、`allowedActions` 不含 `WITHDRAW` | 截圖 + API JSON |
| J2-12 | PRD-007 AC-1.4（TS-007 P-18） | — | inspect `{O}`；`sitter` 呼叫站內通知列表 | — | `orderLogs` 恰 1 筆 `PAYMENT_PROOF_SUBMITTED`：`operatorRole=OWNER`、payload `paymentId={P}`、`purpose=INITIAL`、`submissionId=X`、`lastFive=12345`；`notifications` 恰 1 筆 `PAYMENT_PROOF_SUBMITTED`、`recipientUserId=sitter`、`recipientRole=SITTER`、`mandatory=false`、Email 應寄（§0.2）；`sitter` 站內通知列表可見該則、連結 `/sitter/orders/{O}` | API JSON |
| J2-13 | PRD-007 AC-2.7；PRD-027 AC-3.2-情境 1（畫面）（TS-007 PV-31；TS-027 §2.3） | sitter | 開啟 `/sitter/orders/{O}`，定位 `payment-block-{P}`；sitter 呼叫 E13 `GET /api/orders/{O}/payments/{P}/submissions/{X}/proof-url` | `payment-amount` 為 `$ 2,000`；`submission-history-item-{X}` 後五碼 12345；`submission-history-image-{X}` 載入完成（`naturalWidth > 0`）；`btn-verify-payment`、`btn-reject-payment` 可按；`sitter-order-detail-btn-cancel` 數量 0；出現 `sitter-order-cancel-blocked-message`「飼主已上傳付款憑證，請先核對或駁回」與核對頁連結 | E13 200 `{ url, expiresAt }`：`url` 主機 `storage.googleapis.com`、路徑含 `payment-proofs/`、query 含 `X-Goog-Expires=300`；同物件去掉 query 的網址 GET → 403；sitter E10 `allowedActions` 含 `VERIFY`、`REJECT`；E2 `allowedActions` 不含 `SITTER_CANCEL` | 截圖 + API JSON |
| J2-14 | PRD-007 AC-2.1（TS-007 PV-02） | sitter | 點 `btn-verify-payment`；攔截頁面發出的 E14 | 付款狀態「已確認」、`submission-history-item-{X}` 狀態「已確認」（依 SD-FRONTEND-SPEC §6.4）；`order-status-badge` 顯示「已確認」；`btn-verify-payment`、`btn-reject-payment` 消失；留在本頁不整頁 reload | 頁面發出的 E14 `POST /api/orders/{O}/payments/{P}/verify` 恰 1 次、帶 `Idempotency-Key`、body `{ submissionId: X }`；回應 200 `{ paymentId: P, paymentStatus: "VERIFIED", orderStatus: "CONFIRMED" }`、無 `modification` 欄位；E10 `status=VERIFIED`、有 `verifiedAt`；E12 X `status=ACCEPTED`、`verifiedAt` = `reviewedAt` | 截圖 + API JSON |
| J2-15 | PRD-007 AC-2.1（TS-007 PV-03） | — | inspect `{O}`；雙方呼叫站內通知列表；E3 / E4 `tab=ACTIVE` | — | `orderLogs` 恰 1 筆 `PAYMENT_VERIFIED`：`operatorRole=SITTER`、payload `purpose=INITIAL`、`submissionId=X`、`amount=2000`、有 `confirmedAt`、`transitions` 含 `PAYMENT SUBMITTED → VERIFIED`、`SUBMISSION SUBMITTED → ACCEPTED`、`ORDER PENDING_PAYMENT → CONFIRMED`；`notifications` `ORDER_ACTIVATED` 恰 2 筆（`owner`、`sitter` 各 1）、`mandatory=false`、皆 Email 應寄（§0.2）；`payments[0]` `status=VERIFIED`、`verifiedAt` 有值；雙方站內通知列表含「訂單啟動成功」；E3 `tab=ACTIVE` 含 `{O}`、`tab=EVALUATING` 不含；E4 同 | API JSON |
| J2-16 | PRD-026 AC-1.4-情境 1、2；PRD-027 AC-3.3-情境 1（TS-026 §2；TS-027 §2.3） | owner、sitter | `owner` 開啟 `/owner/orders/{O}`；`owner` E8 `{ expectedStatus: "CONFIRMED" }`（新 key）；`sitter` E9 `{ reason: "臨時有事" }`（新 key）；`sitter` 開啟 `/sitter/orders/{O}` | 飼主頁 `order-status-badge`「已確認」；`owner-order-detail-btn-withdraw`、`owner-order-detail-withdraw-blocked-message`、`proof-submit-btn` 數量 0；出現 `owner-order-detail-btn-modify`（PRD-016 入口，SD-ORDER §9.3）、`owner-order-detail-btn-open-modification` 數量 0（無未結束變更）；保母頁 `sitter-order-detail-btn-cancel` 數量 0 | E8 → 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=CONFIRMED`；E9 → 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=CONFIRMED`；owner E2 `allowedActions` 含 `REQUEST_MODIFICATION`、不含 `WITHDRAW`；E2 `status` 仍 `CONFIRMED` | 截圖 + API JSON |
| J2-17 | 旅程總斷言（baseline §3.6 稽核：每次轉移一筆 log） | — | inspect `{O}` | — | `orderLogs`（依 `createdAt` 排序、排除 `TEST_*`）的 `actionType` 恰為 `[ORDER_CREATED, ORDER_QUOTED, PAYMENT_PROOF_SUBMITTED, PAYMENT_VERIFIED]`，操作者依序 OWNER、SITTER、OWNER、SITTER；`notifications` 依 type 計數恰為 `NEW_BOOKING_REQUEST` 1、`QUOTE_SENT` 1、`PAYMENT_PROOF_SUBMITTED` 1、`ORDER_ACTIVATED` 2（J2-E1–J2-E6 的失敗請求不產生 log 與通知）；`status=CONFIRMED`、`totalAmount=2000`；`fulfill.visits` 3 筆皆 `PENDING` | API JSON |

### 2.2 分支 R：保母駁回 → 飼主重新上傳 → 保母確認

前置：`apiSubmitted(sitter, "11111")` → 訂單 `{O}`、payment `{P}`、submission `{X}`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| J2-21 | PRD-007 AC-2.3；PRD-006 AC-2.1-情境 1（前置原價報價）；PRD-027 AC-3.2-情境 2（TS-007 PV-07；TS-027 §2.3） | sitter | 開啟 `/sitter/orders/{O}`；點 `btn-reject-payment`、`reject-reason-input` 填「金額不符」、點 `reject-submit-btn`；攔截頁面發出的 E15；完成後 reload | 不出現 `reject-overdue-dialog`（`now < due_at`）；`submission-history-item-{X}` 顯示「已駁回：金額不符」、付款狀態回到「待付款」（依 SD-FRONTEND-SPEC §6.4）；reload 後出現 `sitter-order-detail-btn-cancel`、`sitter-order-cancel-blocked-message` 數量 0 | 頁面發出的 E15 恰 1 次、帶 `Idempotency-Key`、body `{ submissionId: X, reason: "金額不符" }`；回應 200 `{ paymentId: P, paymentStatus: "UNPAID", submissionId: X, submissionStatus: "REJECTED" }`；E12 X `rejectReason=金額不符`、有 `reviewedAt`、無 `verifiedAt`；E2 `status=PENDING_PAYMENT`、sitter 端 `allowedActions` 含 `SITTER_CANCEL` | 截圖 + API JSON |
| J2-22 | PRD-007 AC-2.3（TS-007 PV-08） | — | inspect `{O}`；`owner` 呼叫站內通知列表 | — | `orderLogs` 恰 1 筆 `PAYMENT_REJECTED`：`operatorRole=SITTER`、payload `submissionId=X`、`reason=金額不符`、`afterDueAt=false`；`notifications` 恰 1 筆 `PAYMENT_REJECTED`、`recipientUserId=owner`、`mandatory=true`、Email 應寄（§0.2）；`owner` 站內通知內文含「金額不符」 | API JSON |
| J2-23 | PRD-007 AC-2.3、AC-1.4（TS-007 PV-09） | owner | 開啟 `/owner/orders/{O}`；在 `payment-block-{P}` 重新勾免責、`proof-last5-input` 填 `67890`、選 PNG、點 `proof-submit-btn` | 送出前：上傳表單重新出現、`submission-history-item-{X}` 顯示「已駁回：金額不符」；送出後 `submission-history` 由新到舊為 `submission-history-item-{Y}`（待核對）、`submission-history-item-{X}`（已駁回） | 頁面發出的 E11 恰 1 次 → 201（`submissionId=Y`）；E12 依 `submittedAt` 倒序 `[Y, X]`；E10 `status=SUBMITTED`、`currentSubmissionId=Y` | 截圖 + API JSON |
| J2-24 | PRD-007 AC-2.1、AC-2.7（TS-007 PV-02、PV-30） | sitter | 開啟 `/sitter/orders/{O}`；`submission-history-image-{Y}` 載入後點 `btn-verify-payment` | `order-status-badge`「已確認」 | 頁面 E14 body `{ submissionId: Y }` → 200、`orderStatus=CONFIRMED`；E12 Y `status=ACCEPTED`、X 仍 `REJECTED` 且保有 `rejectReason`；inspect `orderLogs` 依序 `[ORDER_CREATED, ORDER_QUOTED, PAYMENT_PROOF_SUBMITTED, PAYMENT_REJECTED, PAYMENT_PROOF_SUBMITTED, PAYMENT_VERIFIED]`，最後一筆 payload `submissionId=Y`；`ORDER_ACTIVATED` 通知恰 2 筆 | 截圖 + API JSON |

### 2.3 分支 W：報價後付款前飼主撤回（O6）

前置：`apiQuoted(sitter)` → 訂單 `{O}`、payment `{P}`（`UNPAID`）。§3 J2-E7 在 J2-31 之前執行。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| J2-31 | PRD-026 AC-1.2；PRD-006 AC-2.1-情境 1（前置原價報價）（TS-026 WD-13、WD-14） | owner | 開啟 `/owner/orders/{O}`；點 `owner-order-detail-btn-withdraw`、`owner-order-withdraw-input-reason` 填「改日期重下」、點 `owner-order-withdraw-btn-confirm`；攔截頁面發出的 E8 | 撤回前可見 `owner-order-detail-quote-breakdown` 與 `payment-block-{P}`；撤回後 `order-status-badge`「已取消」、`payment-block-{P}` 付款狀態「已作廢」（依 SD-FRONTEND-SPEC §6.4），`owner-order-detail-btn-withdraw` 與 `proof-submit-btn` 數量 0 | 頁面發出的 E8 `POST /api/orders/{O}/withdraw` 恰 1 次、帶 `Idempotency-Key`、body `{ expectedStatus: "PENDING_PAYMENT", reason: "改日期重下" }`；回應 200 `{ orderId, status: "CANCELLED", cancelledBy: "OWNER" }` | 截圖 + API JSON |
| J2-32 | PRD-026 AC-1.2（TS-026 WD-14、WD-04） | — | owner E2、E10；inspect `{O}`；`sitter` 呼叫站內通知列表 | — | E2 `status=CANCELLED`、`cancellation={ cancelledBy: "OWNER", reasonCode: "OWNER_WITHDRAWN", reason: "改日期重下", cancelledAt }`、`visits` 3 筆皆 `CANCELLED`；E10 `status=VOID`、有 `voidedAt`；inspect：`orderLogs` 取消類恰 1 筆 `ORDER_WITHDRAWN`、`operatorRole=OWNER`、payload `fromStatus=PENDING_PAYMENT`、`reason=改日期重下`、`transitions` 含 `ORDER PENDING_PAYMENT → CANCELLED`、`PAYMENT UNPAID → VOID` 與 3 筆 `VISIT PENDING → CANCELLED`；`payments[0].voidedAt` 有值；`notifications` 恰 2 筆新增：`BOOKING_WITHDRAWN`（`sitter`）、`BOOKING_WITHDRAWN_SELF`（`owner`），皆 `mandatory=false`；`sitter` 站內通知標題「飼主已撤回預約（F）」、內容含「原因：改日期重下」 | API JSON |
| J2-33 | PRD-026 AC-1.2（歷史分頁）；PRD-027 AC-3.3-情境 2；PRD-007 AC-1.7（VOID 後上傳）（TS-026 WD-05；TS-007 P-35） | sitter、owner | `sitter` 開啟 `/sitter/orders`，點 `sitter-orders-tab-history`、再點 `sitter-orders-tab-evaluating`；開啟 `/sitter/orders/{O}`；`sitter` E9 `{ reason: "臨時有事" }`；`owner` E11 對 `{P}`（有效欄位、新 key） | 歷史分頁有 `sitter-order-card-{O}`；評估中分頁沒有；訂單頁無 `sitter-order-eval-btn-quote-original`、`sitter-order-detail-btn-cancel`、`btn-verify-payment` | E4 `tab=HISTORY` 含 `{O}`、`tab=EVALUATING` 不含；E9 → 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=CANCELLED`；E11 → 409 `PAYMENT_STATE_CONFLICT`、`payload.currentStatus=VOID`；E12 `[]`；inspect 無 `PAYMENT_PROOF_SUBMITTED`、無 `ORDER_CANCELLED_BY_SITTER` | 截圖 + API JSON |

### 2.4 分支 D：保母婉拒（O3 SITTER）

前置：`apiPending(sitter)` → 訂單 `{O}`（`PENDING`）。§3 J2-E8 在 J2-41 之前執行。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| J2-41 | PRD-027 AC-1.2-情境 1、2、3（TS-027 QR-01–QR-03） | sitter | 開啟 `/sitter/orders/{O}`，點 `sitter-order-eval-btn-decline`；原因欄空白時檢查確認鈕；點 `sitter-order-decline-btn-quick-reason-1` | 對話框含 `sitter-order-decline-input-reason`、`sitter-order-decline-btn-quick-reason-1`–`4`（「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」）與文字「婉拒後飼主將收到通知，含您填寫的原因」；空白時 `sitter-order-decline-btn-confirm` 停用；點快速帶入後原因欄值為「檔期已滿」且可編輯 | 期間頁面發出的 E7 請求數 0 | 截圖 |
| J2-42 | PRD-027 AC-1.1-情境 1（TS-027 QR-04） | sitter | 點 `sitter-order-decline-btn-confirm`；攔截頁面發出的 E7 | 對話框改顯示 `sitter-order-decline-btn-referral`「轉介信任圈夥伴」；不點轉介，關閉對話框 | 頁面發出的 E7 `POST /api/orders/{O}/decline` 恰 1 次、帶 `Idempotency-Key`、body `{ reason: "檔期已滿" }`；回應 200 `{ orderId, status: "CANCELLED", cancelledBy: "SITTER" }` | 截圖 + API JSON |
| J2-43 | PRD-027 AC-1.1-情境 1（BR-2、BR-12）（TS-027 QR-06、QR-07） | — | owner E2；sitter E10；inspect `{O}`；雙方呼叫站內通知列表 | — | E2 `status=CANCELLED`、`cancellation.cancelledBy=SITTER`、`cancellation.reasonCode=SITTER_DECLINED`、`cancellation.reason=檔期已滿`、`visits` 3 筆皆 `CANCELLED`、`payments=[]`；E10 `[]`；inspect：`orderLogs` 取消類恰 1 筆 `ORDER_REJECTED`、`operatorRole=SITTER`、payload `reason=檔期已滿`、`transitions` 含 `ORDER PENDING → CANCELLED` 與 3 筆 `VISIT PENDING → CANCELLED`；`notifications` 恰 2 筆新增：`BOOKING_DECLINED`（`owner`）、`BOOKING_DECLINED_SELF`（`sitter`），皆 `mandatory=false`；`owner` 內文「預約已婉拒」含「檔期已滿」、連結 `/owner/orders/{O}`；`sitter` 內文「您已婉拒 旅程飼主 的預約」 | API JSON |
| J2-44 | PRD-027 AC-1.1-情境 1（飼主畫面）；PRD-026 AC-1.4-情境 1（TS-027 QR-08） | owner | 開啟 `/owner/orders/{O}`；`owner` E8 `{ expectedStatus: "PENDING" }`（新 key） | `order-status-badge`「已取消」；`owner-order-detail-cancel-result` 含標題「保母已婉拒此預約」與「檔期已滿」；`owner-order-detail-btn-withdraw` 數量 0；無付款區塊 | E8 → 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=CANCELLED`；E2 `cancellation.cancelledBy` 仍 `SITTER` | 截圖 + API JSON |
| J2-45 | PRD-027 AC-1.1-情境 1（保母歷史）、AC-1.5-情境 3（TS-027 QR-09、QR-10） | sitter | 開啟 `/sitter/orders`，點 `sitter-orders-tab-history`，點 `sitter-order-card-{O}`；以新 key 呼叫 E6 `{ expectedAmount: 1800 }` | 歷史分頁有 `sitter-order-card-{O}`；訂單頁無 `sitter-order-eval-btn-quote-original`、`sitter-order-eval-btn-quote-adjust`、`sitter-order-eval-btn-decline`、`sitter-order-decline-btn-referral` | E4 `tab=HISTORY` 含 `{O}`、`tab=EVALUATING` 不含；E2 `allowedActions` 不含 `DECLINE`、`QUOTE`；E6 → 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=CANCELLED`；inspect 無 `ORDER_QUOTED` | 截圖 + API JSON |

### 2.5 分支 A：收款帳號未設定 → 設定 → 報價（PRD-025 × PRD-006）

前置：`apiPending(sitterNoPay)` → 訂單 `{O}`（`PENDING`）。收款帳號 API 與「收款帳號頁」路由 / testid 屬 SD-SUPPLY（未撰寫），以下以業務名稱描述，由 e2e-author 於 SD-SUPPLY 核准後對應（§5-1）。本段在 SD-SUPPLY 核准前以 `test.fixme` 保留。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| J2-51 | PRD-006 AC-1.5-情境 3、AC-3.8-情境 1；PRD-025 AC-1.1（TS-025 PA-01、PA-03） | sitterNoPay | 收款帳號讀取 API；E5；開啟 `/sitter/orders/{O}`；以 API E6（key K1、`expectedAmount=1800`）直接報價 | `sitter-order-eval-precheck-banner` 顯示「請先設定收款帳號」與前往收款帳號頁的連結；`sitter-order-eval-btn-quote-original`、`sitter-order-eval-btn-quote-adjust`、`sitter-order-eval-btn-quote-submit` 全部停用；`sitter-order-eval-btn-decline` 可按 | 讀取 API 200、body 恰為 `{ "configured": false }`；E5 `precheck.payoutAccountMissing=true`；E6 → 422 `PAYOUT_ACCOUNT_REQUIRED`；E2 `status=PENDING`；E10 `[]`；inspect 無 `ORDER_QUOTED` | 截圖 + API JSON |
| J2-52 | PRD-025 AC-1.1（TS-025 PA-02、PA-04、PA-05） | sitterNoPay | 點預檢提示中的收款帳號頁連結；在收款帳號頁填銀行代碼 `812`、分行 `台北分行`、帳號 `12345678901`、戶名 `王小明` 送出 | 導到收款帳號頁，送出前顯示「尚未設定收款帳號」；送出後顯示四個已設定值；全程不要求輸入密碼或重新登入 | 頁面發出的設定請求恰 1 次（PUT 整筆）→ 200；讀取 API → `configured=true`、四欄與輸入相同、`updatedAt` 介於送出前後 | 截圖 + API JSON |
| J2-53 | PRD-025 AC-1.1（通知）（TS-025 PA-06） | sitterNoPay | 呼叫站內通知列表（`role=SITTER`） | — | 含「收款帳號已設定」1 則，內容不含「12345678901」「王小明」（不可關閉類別與 Email 排入由 payout-inspect 驗證，未定義前見 §4） | API JSON |
| J2-54 | PRD-006 AC-3.8-情境 2、AC-2.1-情境 1；PRD-025 AC-1.1、AC-1.4（TS-025 PA-07） | sitterNoPay | 回到 `/sitter/orders/{O}`；點 `sitter-order-eval-btn-quote-original`（前端新產生 key） | `sitter-order-eval-precheck-banner` 數量 0；三顆報價鈕可按；報價後 `order-status-badge`「待付款」 | 頁面發出的 E6 恰 1 次，`Idempotency-Key` ≠ K1（K1 重送會回放 422，baseline §3.6）→ 200、`status=PENDING_PAYMENT`、`totalAmount=1800`；owner E10 INITIAL `accountSnapshot={ bankCode: "812", branch: "台北分行", accountNumber: "12345678901", accountName: "王小明" }`；inspect `ORDER_QUOTED` 恰 1 筆 | 截圖 + API JSON |

## 3. 權限與例外

> 只列跨角色關鍵越權與「已進入下一階段後前一角色不能再動」的保護；越權一律以另一個真實帳號打 API。完整矩陣見各模組 TS §3。每列皆斷言失敗後資料不變（E2 狀態、E10 / E12 長度、inspect 無新增 log 與通知）。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| J2-E1 | PRD-005 AC-1.1-情境 3、EX-1（TS-005 PB-03） | 主線 J2-01 前：不帶 `Authorization` 以合法 body 打 E1（`sitterId=sitter`） | 401 `UNAUTHENTICATED`（SD-ORDER §6.1 錯誤格式）；`sitter` E4 `tab=EVALUATING` `totalElements=0` |
| J2-E2 | PRD-005 AC-3.4-情境 1、EX-11（TS-005 PB-67） | 主線 J2-05 後：`owner` 以 J2-04 記下的同一 `Idempotency-Key` 與完全相同的 body 重送 E1 | 201，body 的 `orderId` 與 J2-04 相同；E3 `tab=EVALUATING` `totalElements` 仍 1；inspect `ORDER_CREATED` 仍 1 筆、`NEW_BOOKING_REQUEST` 仍 1 筆 |
| J2-E3 | PRD-006 AC-1.4-情境 1、AC-3.1-情境 1、EX-1（TS-006 Q-10、Q-15） | 主線 J2-06 前：`sitterB` 呼叫 E5；`sitterB` 呼叫 E6 `{ expectedAmount: 1800 }`（新 key） | 兩者皆 403 `FORBIDDEN`；owner E2 仍 `PENDING`；E10 `[]`；inspect 無 `ORDER_QUOTED` |
| J2-E4 | PRD-007 AC-1.3、EX-1（TS-007 P-11） | 主線 J2-10 後、J2-11 前：`owner2` 呼叫 E2、E10，及 E11（有效欄位、新 key）；`owner2` 以 UI 開啟 `/owner/orders/{O}` | E2、E10、E11 皆 403 `FORBIDDEN`；畫面顯示「無權限操作此訂單」；owner E10 仍 `UNPAID`、E12 `[]` |
| J2-E5 | PRD-026 AC-1.3、EX-4；PRD-027 AC-3.2-情境 1、EX-8（TS-026 WD-15） | 主線 J2-12 後、J2-14 前：`owner` E8 `{ expectedStatus: "PENDING_PAYMENT" }`；`sitter` E9 `{ reason: "臨時有事" }`（各新 key） | 兩者皆 409 `PAYMENT_ALREADY_SUBMITTED`；E2 仍 `PENDING_PAYMENT`；E10 仍 `SUBMITTED`；`fulfill.visits` 仍全 `PENDING`；inspect 無 `ORDER_WITHDRAWN`、`ORDER_CANCELLED_BY_SITTER`、`BOOKING_WITHDRAWN*`、`SITTER_CANCELLED*` |
| J2-E6 | PRD-007 AC-2.5、EX-1、EX-3（TS-007 PV-20、PV-21） | 主線 J2-12 後、J2-14 前：`owner` E14 `{ submissionId: X }`（飼主確認自己付的 CHARGE）；`sitterB` E14 同 body | 兩者皆 403 `FORBIDDEN`；E10 仍 `SUBMITTED`；inspect 無 `PAYMENT_VERIFIED` |
| J2-E7 | PRD-026 AC-1.6-情境 1、2、EX-1、EX-2（TS-026 WD-29、WD-30） | 分支 W J2-31 前：`owner2` E8 `{ expectedStatus: "PENDING_PAYMENT" }`；`sitter`（`currentRole=SITTER`）E8 同 body | `owner2` 403 `FORBIDDEN`；`sitter` 403 `ROLE_MISMATCH`；E2 仍 `PENDING_PAYMENT`、E10 仍 `UNPAID` |
| J2-E8 | PRD-027 AC-1.3-情境 2、EX-1（TS-027 QR-17） | 分支 D J2-41 前：`sitterB` E7 `{ reason: "檔期已滿" }`（新 key） | 403 `FORBIDDEN`；E2 仍 `PENDING`；inspect 無 `ORDER_REJECTED`、無 `BOOKING_DECLINED*` |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| 跨角色併發：撤回 vs 報價 / 婉拒（PRD-006 AC-2.6-情境 3、PRD-026 AC-1.7、PRD-027 AC-1.4）、上傳 vs 保母取消（PRD-007 AC-1.8、PRD-027 AC-3.5） | 旅程只驗主線先後結果；兩請求同時送出的競爭由模組 TS 以 `Promise.all` 重複驗證，嚴格交錯由整合測試控制 | TS-007 P-40；`QuoteConcurrencyIntegrationTest`、`OrderWithdrawConcurrencyIntegrationTest`、`PaymentSubmissionConcurrencyIntegrationTest` |
| 期限與排程：評估逾期 O4、付款提醒 C2、付款逾期 O8、待核對升級 C4、ADMIN 作廢 O16（PRD-007 子模組 3、PRD-027 子模組 2） | 需 shift-time 與排程觸發，屬模組行為，不在本主線 | TS-007 PS-01–PS-32；TS-027 §2.2；`PaymentCronIntegrationTest`、`ReviewExpiryCronIntegrationTest` |
| 鎖逾時 409 `ORDER_BUSY` + `Retry-After: 1` | 需另一連線持有訂單列鎖 | SD-ORDER §10.1 鎖逾時做法（各整合測試類別） |
| Email 實際寄出與內容 | C11 對 `@e2e-journey.test` 收件者不呼叫 Resend（記 `SUPPRESSED_TEST`）；Resend 為測試模式，只能寄給專案擁有者信箱 | 手動驗證：以專案擁有者信箱 provision 為飼主（另用一個 `@e2e-journey.test` 保母），手動走 J2-04、J2-07、J2-11、J2-14，檢查四封信（新預約不寄飼主、報價、上傳、啟動成功）內容金額為 `$ 2,000` 格式、不含完整收款帳號 |
| PRD-025 收款帳號通知的不可關閉類別、Email 排入、稽核紀錄（J2-53 後半） | payout-inspect 唯讀端點未定義（SD-SUPPLY 未撰寫） | TS-025 PA-06 所列後端測試（待 SD-SUPPLY 定類別名） |
| 收款帳號與快照加密存放（PRD-025 BR-4、PRD-007 BR-2） | E2E 讀不到 DB 原始值 | `QuoteIntegrationTest`（快照非明文、可解回四欄）；SD-SUPPLY 對應測試 |
| 簽名網址實際於 5 分鐘後失效 | 等待拖慢 CI | TS-007 §4 手動驗證；E2E 以 J2-13 `X-Goog-Expires=300` 代替 |
| 轉介流程本身（PRD-027 AC-1.5-情境 1、2） | 屬 PRD-010；旅程只驗入口出現與關閉後不再出現 | TS-027 QR-05、QRM-01 |

## 5. 已知限制 / 待補

1. **收款帳號 API / 畫面未定義（阻擋分支 A）**：PRD-025 的讀取 / 設定 API 路徑、「收款帳號頁」路由與 testid、payout-inspect 屬 SD-SUPPLY，尚未撰寫。J2-51–J2-54 以業務名稱描述，spec 以 `test.fixme` 保留，SD-SUPPLY 核准後由 e2e-author 對應並移除 fixme。主線與其他分支以 `sitter-setup` `payoutAccount` 直接設定，不受影響。
2. **站內通知列表 API 沿用現行**：SD-NOTIFY 未重建，標題 / 內文 / 連結以現行 `GET /api/notifications?role=` 核對；type、`mandatory`、`emailStatus` 一律以 inspect 斷言。SD-NOTIFY 契約定案後改用新端點。
3. **Email 斷言（已解決，2026-11-22 依 SD-ORDER 核准後修訂）**：C11 支援 `?orderId=`，對測試帳號收件者不呼叫 Resend、記 `SUPPRESSED_TEST`；本旅程「Email 應寄」一律斷言 `SUPPRESSED_TEST`（§0.2）。實際信件內容仍需手動驗證（§4）。
4. **登入頁 testid 與回跳機制未在本範圍 SD 定義**：J2-02 依 PRD-005 §4.1-3 / SD-ORDER §9.1「未登入送單導向登入並保留內容」，登入頁欄位以畫面業務名稱（Email、密碼、登入按鈕）定位；登入頁屬 PRD-000 / SD-IDENTITY，未重建前由 e2e-author 依現行畫面對應。
5. **我的最愛清單 API**：J2-05 以 PRD-019 現行清單 API 核對（同 TS-005 PB-92）；SD-SUPPLY 重建後改用新契約。
6. **報價成功後的頁面行為（已解決，2026-11-22 依 SD-ORDER §9.1 核准後修訂）**：O2 成功後留在原頁、以 E6 回應就地重新渲染為「待付款」，並使 E2 / E4 列表 query 失效重抓，不整頁 reload。J2-07 斷言原頁重渲染，J2-09 以 `page.goBack()` 驗證列表不 reload 即顯示「待付款」（舊 bug 迴歸，§6），不得改用 `page.goto`。
7. **子流程狀態文字**（已解決，使用者 2026-11-22 確認 SD-FRONTEND-SPEC §6.4）：若定稿文字不同，e2e-author 依 §6.4 定稿值調整 J2-10、J2-11、J2-14、J2-21、J2-31 的斷言。
8. **`cancellation.reasonCode` 列舉（已解決，2026-11-22）**：SD-ORDER E2 已列 8 個值；J2-32 斷言 `OWNER_WITHDRAWN`、J2-43 斷言 `SITTER_DECLINED`。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|
| 2026-10-23 | 舊版步驟 5（對應本版 J2-09） | 保母接單成功後回到列表仍顯示「待評估」：成功後直接導頁、未 invalidate 訂單查詢快取（`staleTime` 60 秒）。本版 J2-09 以不 reload 的列表斷言「待付款」迴歸 | 舊 TS 未記錄 commit |
| 2026-10 | 舊版步驟 6–7（對應本版 J2-13） | 付款憑證為公開物件、永久公開網址；改私有物件 + 授權後簽名網址。本版 J2-13 以去 query 網址 403 迴歸 | 舊 TS 未記錄 commit |
| 2026-10-26 | 舊版 BR-03b / BR-04（對應本版 J2-43、J2-44） | 保母婉拒後飼主沒有收到通知、詳情不顯示婉拒原因。本版 J2-43 以 inspect `BOOKING_DECLINED` 與通知內文含原因、J2-44 以 `owner-order-detail-cancel-result` 含原因迴歸 | 舊 TS 未記錄 commit |

- 舊版記錄的 `PlanGatingAspect` 無訂閱列即 403（照護記事本步驟）屬 PRD-021，已不在本旅程範圍，移除。

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 子流程狀態文字依 SD-FRONTEND-SPEC §6.4 定稿；狀態改 Approved | 使用者核准 |
| 2026-11-22 | 依核准後修訂同步（使用者 2026-11-22 同意）：§0.2 inspect 補 `payments[]` `direction` / `purpose` / `amount`、`fulfill.visits[]` `serviceDate` / `planId`，「Email 應寄」改為觸發 C11 `?orderId=` 後斷言 `SUPPRESSED_TEST`，新增子流程狀態文字約定；J2-05 / J2-08 / J2-15 / J2-22 對應斷言；J2-07 報價後留在原頁就地重渲染；J2-09 / J2-10 報價明細改三段 testid 與 E2 `quote.original` / `adjustments[]` / `totalAmount`，SITTER 限定欄位（`sitterTier`、`modificationPricingMode`、`bookableFrom/To`）雙向斷言；J2-11 / J2-14 / J2-21 / J2-31 子流程狀態文字；J2-16 `owner-order-detail-btn-open-modification` 不出現；§5-3、§5-6、§5-8 標已解決，新增 §5-7；狀態維持 Draft | SD-ORDER §6.3 E2、§9.1、§9.3、§10.4 與 SD-FRONTEND-SPEC §6.4 核准後修訂 |
| 2026-11-22 | 依 PRD-005 / 006 / 007 / 025 / 026 / 027（Approved 2026-11-22）、DOMAIN-BASELINE、SD-ORDER（§6.3、§7、§9.1、§9.3、§10.4）、SD-FRONTEND-SPEC 全文重寫：改用新範本與 `J2-` 步驟前綴；主線改為匿名瀏覽 → 登入送單（含問卷）→ PRO 調價報價 → 上傳 → 核對 `CONFIRMED`；新增分支 R（駁回重傳）、W（付款前撤回）、D（婉拒）、A（收款帳號未設定，待 SD-SUPPLY）；斷言改用 inspect 驗 `order_logs` 與通知；越權改以真實帳號打 API（舊「不對正式站探測」規則作廢）；每步標 PRD AC 與模組 TS 步驟 ID；移除舊 API 路徑、寵物建立、照護記事本與 `bootstrap*` helper 依賴；狀態 Draft | 全面重建（使用者指示） |
