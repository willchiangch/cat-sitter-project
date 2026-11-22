# TS-007: 線下付款憑證上傳與核對

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 對應 PRD | PRD-007（前置資料借用 PRD-005 送單、PRD-006 報價、PRD-016 / PRD-023 變更、PRD-024 款項爭議、PRD-027 保母取消、PRD-025 收款帳號） |
| 對應 SD | SD-ORDER（§6.3 E2、E10–E16、E27、E28、C1–C10；§8.1 C2–C4；§9.1、§9.3 testid；§10.3 後端測試類別；§10.4 測試資料端點與 inspect） |
| 類型 | 旅程（打真後端） |
| 自動化路徑 | `frontend/e2e/journeys/offline-payment.spec.ts`（新建） |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD-007、SD-ORDER、SD-FULFILL、DOMAIN-BASELINE 撰寫，未參考程式碼。
> 既有 spec：`frontend/e2e/offline-payment.spec.ts`（全 mock、打舊 API `payment-proof` / `verify-payment`，抽樣缺陷 #16）與 `frontend/e2e/payment-reject-button-contrast.spec.ts`（`btn-reject-payment` 對比度迴歸）。前者依 SD-ORDER §8.4 M30 由 e2e-author 刪除或改寫為只驗版面的 mock spec（須 `assertNoUnmockedApi`）；本 TS 的狀態轉移、權限、冪等、競爭一律在新的 journey spec 打真後端。後者改指向新付款區塊後保留。
> 取代舊 TS-007：舊版自動化路徑指向後端 `PaymentControllerTest`、寫死 UUID 種子資料（抽樣缺陷 #14），本版全數改為每個 test 自建資料。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 用下列 builder 自建資料、可獨立重跑；所有帳號 `@e2e-journey.test`。測試資料端點皆在 `/api/internal/test-data/**`、帶 `X-Internal-Secret`（值取自 CI secret，不寫進 spec 或證據）。
> 日期：F = 以 E31 / E2 `serverTime` 換算的台北今天 + 7 天（一般單，不落入緊急窗與提醒點）；緊急單 F = 台北明天。所有期限的「目標時間」由 `serverTime` 推算，不用瀏覽器時間。
> 排程一律以 `POST /api/internal/cron/...?orderId={O}` 只處理本測試訂單（SD-ORDER §6.3 C1–C10），計數只算該筆；Cloud Scheduler 自動觸發會跳過測試帳號訂單，不會搶先推進。

### 1.1 帳號

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `sitter` | OWNER + SITTER 雙角色 | `provision-account`：`roles=["OWNER","SITTER"]`、`displayName=測試保母`；`sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` = X（`812` / `台北分行` / `12345678901` / `王小明`）、`plans=[{ key: "p1", 單價 600、適用貓、active: true }]`；回應 `plans[key=p1].planId` 為 `planId` | 可接單、已設收款帳號；以角色切換取得 `currentRole=OWNER` token 供 PV-22 |
| `sitterB` | SITTER | 同 `sitter`（`roles=["SITTER"]`），`payoutAccount` = `700` / `信義分行` / `98765432100` / `李大華` | 「他人訂單的保母」 |
| `sitterFree` | SITTER | 同 `sitter`（`roles=["SITTER"]`）但 `tier=FREE`（月上限 3，baseline §2.1） | AC-2.1 專用，整個 test 只用此帳號接單 |
| `owner` | OWNER | `provision-account`：`roles=["OWNER"]`；`owner-setup`：`pets=[{ key: "cat1", 名稱「小橘」, 種類貓 }]`、`emailPreferences.ORDER_AFFAIR=true`；回應 `pets[key=cat1].petId` | — |
| `ownerMuted` | OWNER | 同 `owner`，`emailPreferences.ORDER_AFFAIR=false` | AC-3.2 不可關閉類別 |
| `owner2` | OWNER | 同 `owner` | 非當事人（BOLA） |
| `admin` | ADMIN | `provision-account`：`roles=["ADMIN"]` | — |

- `sitter-setup` / `owner-setup` 為 upsert：同一 `userId` 再次呼叫只覆寫出現的欄位（PV-01 以此設定訂閱鎖定）。

### 1.2 訂單 builder

| 代號 | 建立方式 | 狀態 |
|------|---------|------|
| `O_PENDING` | `owner` E1 `POST /api/orders`：`sitterId=sitter`、`groups[0]` = `planId`、日期 F 至 F+4 共 5 天、`dailyCount=1`、`petIds=[小橘]`、`expectedAmount=3000` | `PENDING`，無 payment |
| `O_PP` | `O_PENDING` → `sitter` E6 `POST /api/orders/{orderId}/quote`：`expectedAmount=3000`（原價） | `PENDING_PAYMENT`；E6 回應 `payment.amount=3000`；記下 `paymentId`、`dueAt`、`escalateAt` |
| `O_URGENT` | 同 `O_PP`，日期改為台北明天 1 天、`expectedAmount=600` | `PENDING_PAYMENT`；緊急單 |
| `O_SUB` | `O_PP` → `owner` E11 上傳（`disclaimerAgreed=true`、`lastFive=12345`、約 200 KB PNG） | INITIAL payment `SUBMITTED`；記下 `submissionId`（X） |
| `O_CONF` | `O_SUB` → `sitter` E14 `{ submissionId: X }`，記下所用 key `Kv` | `CONFIRMED` |
| `O_REFUND` | `O_CONF` → `owner` E17 `type=CHANGE`、`removeVisitIds` = 最後 2 個行程、`reason=少去兩天` → `sitter` E20 同內容、不加減 → `owner` E23 | 變更 `AWAITING_REFUND`；`MODIFICATION_REFUND` payment `UNPAID`、`amount=1200` |
| `O_REFUND_SUB` | `O_REFUND` → `sitter` E11 對退款 payment 上傳（`lastFive=54321`） | 退款 payment `SUBMITTED` |
| `O_SUPP` | `O_CONF` → SD-FULFILL FT1 `force-status` 使首個行程進入執行、訂單 O9 → E2 確認 `status=IN_PROGRESS` → `owner` E17 `type=CHANGE`、`addItems=[{ date: F+5, planId, dailyCount: 1, petIds: [小橘] }]`、`reason=多加一天` → `sitter` E20 同內容 → `owner` E23 | 變更 `AWAITING_SUPPLEMENT`；`SUPPLEMENT` payment `UNPAID`、`amount=600`、`dueAt` = 同意時間 + 72h |
| `O_SUPP_SUB` | `O_SUPP` → `owner` E11 對補款 payment 上傳（`lastFive=24680`） | 補款 payment `SUBMITTED` |
| `O_DISP_RU` | `O_REFUND` → `owner` E24 `description=保母遲未退款` | `DISPUTED`；退款 payment `UNPAID` |
| `O_DISP_RS` | `O_REFUND_SUB` → `sitter` E24 `description=飼主遲未確認` | `DISPUTED`；退款 payment `SUBMITTED` |
| `O_DISP_SS` | `O_SUPP_SUB` → `POST /api/internal/test-data/orders/{orderId}/shift-time` `target=SUBMISSION_AT`、`submissionId`、`minutes=-4321`（72h + 1 分）→ `owner` E24 `description=保母遲未核對補款` | `DISPUTED`；補款 payment `SUBMITTED` |
| `O_ADJ` | `O_DISP_RS` → `admin` E29 `refundDecisions=[{ paymentId: 退款 payment, exported: true }]` | 終態；新增 `method=ADMIN_ADJUSTMENT` 退款 payment `VERIFIED`、`amount=1200` |
| `O_ESC` | `O_SUB` → shift-time `target=ESCALATE_AT`、`paymentId`、`minutes` = −（`escalateAt` − `serverTime` 的分鐘數 + 1）→ C4 `POST /api/internal/cron/payments/escalation?orderId={O}` | C4 回 `processed=1`；OPEN `INITIAL_PAYMENT_UNVERIFIED` 待辦 |

- 「把期限移到過去」一律以 shift-time 將該期限設為 `serverTime − 1 分鐘`（`minutes` 由 spec 依 E2 `serverTime` 與 E10 期限值計算）。
- 測試圖檔由 spec 產生（PNG / JPEG / WebP / GIF / HEIC / PDF 皆帶正確 magic bytes；6 MB JPEG 以填充產生），不放 repo 大檔。
- 「inspect」= `GET /api/internal/test-data/orders/{orderId}/inspect`（SD-ORDER §10.4）：`orderLogs`、`notifications`（`type`、`recipientUserId`、`recipientRole`、`mandatory`、`emailStatus`）、`adminTasks`（含 `resolution`、`resolvedAt`）、`payments`（`amount`、`direction`、`purpose`、`remindAt`、`remindedAt`、`escalateAt`、`escalatedAt`、`verifiedAt`、`voidedAt`）、`totalAmount`、`fulfill.visits`（含 `serviceDate`、`planId`）。
- 「Email 已排入」= `emailStatus=SUPPRESSED_TEST`：測試帳號收件者依偏好（或不可關閉類別）應寄的 Email 一律標此值、不實際送 Resend；偏好關閉且可關閉類別 = `SKIPPED_PREFERENCE`（`emailStatus` 列舉：`PENDING` / `SENT` / `SKIPPED_PREFERENCE` / `FAILED` / `NOT_APPLICABLE` / `SUPPRESSED_TEST`）。
- 畫面狀態文字（付款：待付款 / 待核對 / 已確認 / 已作廢；憑證：待核對 / 已確認 / 已駁回）依 SD-FRONTEND-SPEC §6.4（該表使用者尚未確認，文字若調整以 §6.4 為準）。

清除：`POST /api/internal/cron/test-data/cleanup-e2e-journeys`（SD-ORDER §10.4：訂單相關表硬刪除、交易外刪憑證 GCS 物件；跨模組範圍含通知、收款帳號、方案、毛孩、帳號）。

## 2. 測試步驟

> 步驟 ID 前綴：`P-`（子模組 1 付款頁與上傳）、`PV-`（子模組 2 確認 / 駁回 / 檢視）、`PS-`（子模組 3 期限 / 排程 / ADMIN）。e2e spec 的 `step()` / `apiStep()` 標籤以同一 ID 開頭。
> 「E2」等代號 = SD-ORDER §2.3.4 端點；`{O}` = 該步驟使用的訂單 id；`{P}` = 該步驟的 paymentId；畫面上單筆 payment 一律以 `payment-block-{paymentId}` 容器定位（SD-ORDER §9.3）。
> 金額畫面值依 SD-FRONTEND-SPEC §6.4.1 `formatMoney`（`$ 3,000`，`$` 後為 U+00A0）。

### 2.1 子模組 1：付款頁與上傳憑證

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| P-01 | AC-1.1 | sitter | 前置 `O_PP`；`sitter` 以收款帳號設定 API（PRD-025）把帳號改為 Y（`700` / `信義分行` / `98765432100` / `李大華`） | — | 設定 API 200 | API JSON |
| P-02 | AC-1.1 | owner | 開啟 `/owner/orders/{O}`，定位 `payment-block-{P}` | 容器內 `payment-amount` 為 `$ 3,000`；`payment-account-snapshot` 含「812」「台北分行」「12345678901」「王小明」且不含「98765432100」「李大華」；`payment-due-at` 為 E10 `dueAt` 換算 Asia/Taipei 的 `YYYY-MM-DD HH:mm`；全頁 `order-total-amount` 數量 0；容器內不出現訂單總額或差額公式（同頁報價明細 `order-detail-quote-original-total`、`order-detail-quote-current-total` 可顯示 `$ 3,000`，且尚無變更時 `order-detail-quote-modification-*` 數量 0，PRD-007 BR-1、PRD-006 BR-16）；`submission-history` 顯示「尚未上傳憑證」 | owner E10 `GET /api/orders/{O}/payments` → 200，陣列 1 筆：`direction=CHARGE`、`purpose=INITIAL`、`amount=3000`、`status=UNPAID`、`accountSnapshot={ bankCode: "812", branch: "台北分行", accountNumber: "12345678901", accountName: "王小明" }`、無 `currentSubmissionId` / `verifiedAt` / `voidedAt` 欄位、`allowedActions=["UPLOAD"]` | 截圖 + API JSON |
| P-03 | AC-1.1 | sitter | sitter E10 同一訂單 | — | 200；`accountSnapshot` 同 P-02（收款方看自己的快照）；`allowedActions` 不含 `UPLOAD` | API JSON |
| P-04 | AC-1.1 | owner | 前置 `O_PENDING`；開啟 `/owner/orders/{O}` | 不顯示付款區塊：`payment-amount`、`proof-submit-btn` 數量 0 | owner E10 → 200 `[]` | 截圖 + API JSON |
| P-05 | AC-1.2 | sitter | 前置 `O_REFUND`；開啟 `/sitter/orders/{O}`，定位退款的 `payment-block-{退款 P}` | 容器內 `payment-amount` 為 `$ 1,200`；文案「退款帳號請與飼主自行聯繫」；容器內 `payment-account-snapshot`、`payment-due-at` 數量 0；`submission-history` 顯示「尚未上傳憑證」；`proof-disclaimer-checkbox` 文字為「我瞭解平台不代管此筆線下款項，此筆交易風險由飼主與保母雙方自行承擔」；`proof-last5-input` 標籤「您匯出帳戶的後五碼」；未勾免責時 `proof-submit-btn` 停用 | sitter E10 退款那筆：`direction=REFUND`、`purpose=MODIFICATION_REFUND`、`amount=1200`、`status=UNPAID`、無 `accountSnapshot` 欄位、無 `dueAt` 欄位、`allowedActions=["UPLOAD"]` | 截圖 + API JSON |
| P-06 | AC-1.2 | sitter | API：E11 `POST /api/orders/{O}/payments/{退款 P}/submissions`，`disclaimerAgreed=false`、`lastFive=54321`、有效 PNG、新 key | — | 400 `VALIDATION_FAILED`、`payload.field=disclaimerAgreed`；E10 退款仍 `UNPAID` | API JSON |
| P-07 | AC-1.2 | sitter | 前置 `O_ADJ`；開啟 `/sitter/orders/{O}`，定位 `ADMIN_ADJUSTMENT` 那筆的 `payment-block-{P}` | 容器內無 `proof-file-input`、`proof-submit-btn` | sitter E10 該筆：`method=ADMIN_ADJUSTMENT`、`direction=REFUND`、`status=VERIFIED`、`amount=1200`、有 `verifiedAt`、`allowedActions=[]` | 截圖 + API JSON |
| P-08 | AC-1.2 | sitter | API：E11 對 `ADMIN_ADJUSTMENT` payment，`disclaimerAgreed=true`、`lastFive=54321`、有效 PNG | — | 409 `PAYMENT_STATE_CONFLICT`、`payload.currentStatus=VERIFIED` | API JSON |
| P-09 | AC-1.2 | owner | API：E14 `POST /api/orders/{O}/payments/{ADMIN_ADJUSTMENT P}/verify`，`submissionId` = 執行期 `crypto.randomUUID()` | — | 409 `PAYMENT_STATE_CONFLICT`；E10 該筆仍 `VERIFIED` | API JSON |
| P-10 | AC-1.3 | 匿名 | 前置 `O_PP`；不帶 token 呼叫 E10、E11（有效欄位） | — | 兩者皆 401 `UNAUTHENTICATED`（§6.1 錯誤格式） | API JSON |
| P-11 | AC-1.3 | owner2 | API：E10、E11（有效欄位、新 key）；UI 開啟 `/owner/orders/{O}` | 「無權限操作此訂單」 | E10 403 `FORBIDDEN`；E11 403 `FORBIDDEN` | 截圖 + API JSON |
| P-12 | AC-1.3 | sitter | API：E11 對 INITIAL payment（有效欄位） | — | 403 `FORBIDDEN` | API JSON |
| P-13 | AC-1.3 | admin | API：E11 對 INITIAL payment（有效欄位） | — | 403 `ROLE_MISMATCH` | API JSON |
| P-14 | AC-1.3 | owner | 前置另建 `O_REFUND`（同 owner / sitter）；API：E11 對其退款 payment | — | 403 `FORBIDDEN` | API JSON |
| P-15 | AC-1.3 | owner | API：E10 `/api/orders/{執行期 randomUUID}/payments`；E11 `/api/orders/{O_PP}/payments/{O_REFUND 的退款 P}/submissions` | — | 兩者皆 404 `NOT_FOUND` | API JSON |
| P-16 | AC-1.3 | owner | P-10 至 P-15 之後讀取；inspect `O_PP` | — | E10 `O_PP` INITIAL 仍 `UNPAID`、無 `currentSubmissionId`；E12 `GET .../payments/{P}/submissions` → `[]`；退款 payment 仍 `UNPAID`；inspect `orderLogs` 無 `PAYMENT_PROOF_SUBMITTED`；GCS 無新物件由 §4 承擔 | API JSON |
| P-17 | AC-1.4 | owner | 前置 `O_PP`；開啟 `/owner/orders/{O}`，在 `payment-block-{P}` 勾 `proof-disclaimer-checkbox`、`proof-last5-input` 填 `12345`、`proof-file-input` 選 2 MB PNG、點 `proof-submit-btn`；攔截頁面發出的 E11 | 上傳表單消失；`submission-history` 內出現 `submission-history-item-{submissionId}`，後五碼 12345 | 頁面發出的 E11 恰 1 次，帶 `Idempotency-Key` 標頭；回應 201 `{ submissionId, paymentId: P, paymentStatus: "SUBMITTED", submittedAt }`，無 `url` 欄位；E10 `status=SUBMITTED`、`currentSubmissionId`=回應 `submissionId`；E12 1 筆 `status=SUBMITTED`、`lastFive=12345`、`disclaimerAgreedAt` 介於點擊前後的 `serverTime`、無 `reviewedAt`；E2 `status=PENDING_PAYMENT` | 截圖 + API JSON |
| P-18 | AC-1.4 | — | inspect 該訂單；`sitter` 站內通知列表 | — | `orderLogs` 恰 1 筆 `PAYMENT_PROOF_SUBMITTED`：`operatorRole=OWNER`、`operatorUserId=owner`、payload `paymentId`、`purpose=INITIAL`、`submissionId`、`lastFive=12345`；`notifications` 恰 1 筆 `type=PAYMENT_PROOF_SUBMITTED`、`recipientUserId=sitter`、`recipientRole=SITTER`、`mandatory=false`、`emailStatus=SUPPRESSED_TEST`；`payments[0]`：`amount=3000`、`direction=CHARGE`、`purpose=INITIAL`、`status=SUBMITTED`；`sitter` 站內通知列表可見該則 | API JSON |
| P-19 | AC-1.5 | owner | 前置 `O_PP`；UI 不勾免責，填 `12345`、選有效 PNG | `proof-submit-btn` 停用 | — | 截圖 |
| P-20 | AC-1.5 | owner | API：E11 `disclaimerAgreed=false`（其餘有效） | — | 400 `VALIDATION_FAILED`、`payload.field=disclaimerAgreed` | API JSON |
| P-21 | AC-1.5 | owner | API：E11 `lastFive=1234`；再 `lastFive=12a45` | — | 兩者皆 400 `VALIDATION_FAILED`、`payload.field=lastFive` | API JSON |
| P-22 | AC-1.5 | owner | API：E11 依序附 GIF、HEIC、PDF（各 < 1 MB，其餘有效，各用新 key） | — | 三者皆 422 `PROOF_FILE_TYPE_UNSUPPORTED` | API JSON |
| P-23 | AC-1.5 | owner | UI：`proof-file-input` 選 6 MB JPEG；監聽 E11 請求 | 顯示「憑證圖片需小於 5MB」 | 期間 E11 請求數 0 | 截圖 |
| P-24 | AC-1.5 | owner | API：E11 附 6 MB JPEG（其餘有效） | — | 422 `PROOF_FILE_TOO_LARGE`、`payload.maxBytes=5242880` | API JSON |
| P-25 | AC-1.5 | owner | API：E11 `disclaimerAgreed=false` 且附 GIF | — | 400 `VALIDATION_FAILED`（欄位先於檔案格式） | API JSON |
| P-26 | AC-1.5 | owner | P-19 至 P-25 之後讀取；inspect | — | E10 仍 `UNPAID`；E12 `[]`；inspect `orderLogs` 無 `PAYMENT_PROOF_SUBMITTED` | API JSON |
| P-27 | AC-1.5 | owner | 另建 2 張 `O_PP`，分別以 JPEG、WebP（< 5 MB）上傳（PNG 已由 P-17 驗證） | — | 兩者皆 201、`paymentStatus=SUBMITTED` | API JSON |
| P-28 | AC-1.6 | owner | 前置 `O_PP`；API：E11 key K、`lastFive=12345`、固定內容 PNG | — | 201，記 body B1 | API JSON |
| P-29 | AC-1.6 | owner | 以 K、相同欄位、相同檔案重送 | — | 201，body 與 B1 完全相同、不含 `url`；E12 仍 1 筆；inspect：`PAYMENT_PROOF_SUBMITTED` log 恰 1 筆、`PAYMENT_PROOF_SUBMITTED` 通知恰 1 筆 | API JSON |
| P-30 | AC-1.6 | owner | 以 K 改附另一張 PNG | — | 422 `IDEMPOTENCY_KEY_REUSED`；E12 仍 1 筆 | API JSON |
| P-31 | AC-1.6 | owner | 以新 key K2、與 P-28 相同內容 | — | 409 `PAYMENT_STATE_CONFLICT`、`payload.currentStatus=SUBMITTED` | API JSON |
| P-32 | AC-1.6 | owner | 另建 `O_PP` 第二張；以 K、相同欄位與檔案上傳到第二張的 payment | — | 422 `IDEMPOTENCY_KEY_REUSED`（路徑變數入 hash，SD-ORDER §3.6.2）；第二張 E12 `[]` | API JSON |
| P-33 | AC-1.7 | owner | 前置 `O_SUB`；API：E11 新 key、有效欄位 | — | 409 `PAYMENT_STATE_CONFLICT`、`currentStatus=SUBMITTED` | API JSON |
| P-34 | AC-1.7 | owner | 前置 `O_CONF`；API：E11 新 key | — | 409 `PAYMENT_STATE_CONFLICT`、`currentStatus=VERIFIED` | API JSON |
| P-35 | AC-1.7 | owner | 前置 `O_PP` → `sitter` E9 `reason=臨時有事`（200、`CANCELLED`）；API：E11 新 key | — | 409 `PAYMENT_STATE_CONFLICT`、`currentStatus=VOID`；E10 有 `voidedAt` | API JSON |
| P-36 | AC-1.7 | owner | 前置 `O_SUB` → shift-time `PAYMENT_DUE_AT` 至過去；API：E11 新 key | — | 409 `PAYMENT_STATE_CONFLICT`（非 `DEADLINE_PASSED`） | API JSON |
| P-37 | AC-1.7 | sitter | 前置 `O_DISP_RU`（`DISPUTED`、`MODIFICATION_REFUND` `UNPAID`）；`sitter`（付款方）API：E11 對退款 payment（有效欄位） | — | 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=DISPUTED`；E10 退款仍 `UNPAID` | API JSON |
| P-38 | AC-1.7 | owner | 前置 `O_PP` → shift-time `PAYMENT_DUE_AT` 至 `serverTime − 1 分`（測試訂單不會被自動 C3 處理）；UI 勾免責、填 `12345`、選 PNG 送出 | 顯示「已超過付款期限」 | E11 409 `DEADLINE_PASSED`、`payload.deadline=PAYMENT_DUE_AT`、`payload.deadlineAt` = E10 平移後的 `dueAt` 字串；E10 仍 `UNPAID`；E2 仍 `PENDING_PAYMENT` | 截圖 + API JSON |
| P-39 | AC-1.7 | owner | 前置 `O_CONF`；開啟 `/owner/orders/{O}` | INITIAL 的 `payment-block-{P}` 內無 `proof-file-input`、`proof-submit-btn` | — | 截圖 |
| P-40 | AC-1.8 | owner、sitter | 前置 `O_PP`；`Promise.all` 同時送出 `owner` E11（key A、有效欄位）與 `sitter` E9（key B、`reason=臨時有事`）；重複 3 次（每次新訂單），記錄每次勝方 | — | 每次恰一方成功：上傳勝 → E11 201、E9 409 `PAYMENT_ALREADY_SUBMITTED`、E2 `PENDING_PAYMENT`、E10 `SUBMITTED`；取消勝 → E9 200、E11 409 `PAYMENT_STATE_CONFLICT`、E2 `CANCELLED` 且 `cancellation.cancelledBy=SITTER`、E10 `VOID`、E12 `[]`；inspect：`PAYMENT_PROOF_SUBMITTED` 與 `ORDER_CANCELLED_BY_SITTER` 恰出現其一且各至多 1 筆 | API JSON |

### 2.2 子模組 2：收款方確認 / 駁回與憑證檢視

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| PV-01 | AC-2.1 | sitterFree | 前置：`owner` 向 `sitterFree` 建 4 張 `O_PP`（O1–O4，全部在任何確認前報價）；O1–O3 各走 `owner` E11 + `sitterFree` E14 → `CONFIRMED`；O4 `owner` E11（X）；再以 `sitter-setup`（upsert，只送 `subscriptionExpiresAt`）設為 `serverTime` − 1 天 | — | O4 E5 `GET /api/orders/{O4}/evaluation` → `precheck.quotaReached=true`、`precheck.subscriptionLocked=true`；E4 `GET /api/orders/sitter?tab=ACTIVE` 中 `status=CONFIRMED` 恰 3 筆 | API JSON |
| PV-02 | AC-2.1 | sitterFree | 開啟 `/sitter/orders/{O4}`，點 `btn-verify-payment` | 付款狀態改為已確認、`order-status-badge` 為「已確認」 | 頁面發出的 E14 恰 1 次，body `submissionId`=X；回應 200 `{ paymentId, paymentStatus: "VERIFIED", orderStatus: "CONFIRMED" }`、無 `modification`；E10 有 `verifiedAt`；E12 X `status=ACCEPTED`、`reviewedAt` = `verifiedAt`；E2 `status=CONFIRMED`；E4 `tab=ACTIVE` `CONFIRMED` 恰 4 筆 | 截圖 + API JSON |
| PV-03 | AC-2.1 | — | inspect O4；雙方站內通知列表 | — | `orderLogs` 恰 1 筆 `PAYMENT_VERIFIED`：`operatorRole=SITTER`、payload `purpose=INITIAL`、`amount=3000`、有 `confirmedAt`、`transitions` 含 `ORDER` `PENDING_PAYMENT → CONFIRMED`；`notifications` `ORDER_ACTIVATED` 恰 2 筆（`owner`、`sitterFree` 各 1）、`mandatory=false`；`payments[0].verifiedAt` 有值；雙方站內通知列表含「訂單啟動成功」 | API JSON |
| PV-04 | AC-2.1 | sitter | 前置 `O_SUB` → shift-time `PAYMENT_DUE_AT` 至過去；API：E14 `{ submissionId: X }` | — | 200、`orderStatus=CONFIRMED`（不檢查日期） | API JSON |
| PV-05 | AC-2.2 | sitter | 前置 `O_SUPP_SUB`；開啟 `/orders/{O}/modifications/{modificationId}`，補款 `payment-block-{補款 P}` 點 `btn-verify-payment` | 補款狀態已確認 | E14 200 `{ paymentStatus: "VERIFIED", orderStatus: "IN_PROGRESS", modification: { status: "SETTLED" } }`；E2 `status=IN_PROGRESS` | 截圖 + API JSON |
| PV-06 | AC-2.2 | owner | 前置 `O_REFUND_SUB`；開啟 `/owner/orders/{O}`，退款 `payment-block-{退款 P}` 點 `btn-verify-payment`（文字「確認收到退款」） | 退款狀態已確認 | E14 200 `{ paymentStatus: "VERIFIED", orderStatus: "CONFIRMED", modification: { status: "SETTLED" } }`；E2 `status=CONFIRMED`（主狀態不變） | 截圖 + API JSON |
| PV-07 | AC-2.3 | sitter | 前置 `O_SUB`（X）；開啟 `/sitter/orders/{O}`，點 `btn-reject-payment`、`reject-reason-input` 填「金額不符」、點 `reject-submit-btn` | 不出現 `reject-overdue-dialog`；`submission-history-item-{X}` 顯示「已駁回：金額不符」 | 頁面發出的 E15 恰 1 次；回應 200 `{ paymentId, paymentStatus: "UNPAID", submissionId: X, submissionStatus: "REJECTED" }`；E12 X `rejectReason=金額不符`、有 `reviewedAt`、無 `verifiedAt`；E2 `PENDING_PAYMENT` | 截圖 + API JSON |
| PV-08 | AC-2.3 | — | inspect；`owner` 站內通知列表 | — | `orderLogs` 恰 1 筆 `PAYMENT_REJECTED`：payload `reason=金額不符`、`afterDueAt=false`；`notifications` `PAYMENT_REJECTED` 恰 1 筆給 `owner`、`mandatory=true`；`owner` 站內通知內容含「金額不符」 | API JSON |
| PV-09 | AC-2.3 | owner | UI 再上傳：`lastFive=67890`、PNG | `submission-history` 依時間由新到舊：`submission-history-item-{Y}`（待核對）在上、`submission-history-item-{X}`（已駁回：金額不符）在下 | E11 201（Y）；E12 依 `submittedAt` 倒序 `[Y, X]` | 截圖 + API JSON |
| PV-10 | AC-2.3 | sitter | API：E15 對 Y，`reason` 依序為 `""`、`"   "`、`"　"`（全形空白，GLOBAL §2.7 strip 後為空）、201 字（各新 key） | — | 四者皆 400 `VALIDATION_FAILED`、`payload.field=reason`；E10 仍 `SUBMITTED`、`currentSubmissionId=Y` | API JSON |
| PV-11 | AC-2.3 | sitter | API：E15 對 Y，`reason` 恰 200 字 | — | 200、`submissionStatus=REJECTED` | API JSON |
| PV-12 | AC-2.3 | sitter | 前置 `O_SUPP_SUB` → shift-time `PAYMENT_DUE_AT`（補款 P）至過去；在補款 `payment-block-{補款 P}` 點 `btn-reject-payment` | `reject-overdue-dialog` 文字「已過補款期限，駁回後新增的行程將不會生效（已減少的行程維持取消）」；點 `reject-overdue-dialog-btn-cancel` 後關閉 | 期間 E15 請求數 0；E10 補款仍 `SUBMITTED` | 截圖 |
| PV-13 | AC-2.3 | sitter | 再點 `btn-reject-payment` → `reject-overdue-dialog-btn-confirm` → `reject-reason-input` 填「未收到款項」→ `reject-submit-btn` | — | E15 200、`paymentStatus=UNPAID`；inspect `PAYMENT_REJECTED` payload `afterDueAt=true` | 截圖 + API JSON |
| PV-14 | AC-2.3 | owner | API：E11 對補款 payment（測試訂單不會被自動 C6 處理） | — | 409 `DEADLINE_PASSED`、`payload.deadline=PAYMENT_DUE_AT`、`deadlineAt` = 補款 `dueAt`；後續逾時屬 PRD-023 AC-2.7，不在本 TS | API JSON |
| PV-15 | AC-2.4 | owner | 前置 `O_REFUND_SUB`；開啟 `/owner/orders/{O}` 退款 `payment-block-{退款 P}` | 容器內只有 `btn-verify-payment`（「確認收到退款」）與 `payment-refund-dispute-link`（「如有異議，請申報款項爭議」）；`btn-reject-payment` 數量 0；點 `payment-refund-dispute-link` 導向 `/orders/{O}/modifications/{modificationId}` 且可見 `modification-btn-payment-dispute` | — | 截圖 |
| PV-16 | AC-2.4 | owner | API：E15 對退款 payment，`submissionId`=目前那筆、`reason=金額不對` | — | 403 `FORBIDDEN`；E10 退款仍 `SUBMITTED` | API JSON |
| PV-17 | AC-2.4 | owner | 前置 `O_DISP_RS`；API：E15（同 PV-16） | — | 403 `FORBIDDEN`（方向檢查先於凍結）；退款仍 `SUBMITTED` | API JSON |
| PV-18 | AC-2.4 | owner | 同 `O_DISP_RS`；API：E14 `{ submissionId }` | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=DISPUTED`；退款仍 `SUBMITTED` | API JSON |
| PV-19 | AC-2.5 | 匿名 | 前置 `O_SUB`；不帶 token 呼叫 E14 | — | 401 `UNAUTHENTICATED` | API JSON |
| PV-20 | AC-2.5 | owner | API：E14、E15（`reason=測試`） | — | 兩者皆 403 `FORBIDDEN` | API JSON |
| PV-21 | AC-2.5 | sitterB | API：E14 | — | 403 `FORBIDDEN` | API JSON |
| PV-22 | AC-2.5 | admin | API：E14、E15 | — | 兩者皆 403 `ROLE_MISMATCH` | API JSON |
| PV-23 | AC-2.5 | sitter | 以 `sitter` 雙角色帳號切換為 OWNER 取得的 token 呼叫 E14 | — | 403 `ROLE_MISMATCH`（G4a） | API JSON |
| PV-24 | AC-2.5 | sitter、admin | 前置 `O_REFUND_SUB`；`sitter` E14 對退款；`admin` E15 對退款 | — | `sitter` 403 `FORBIDDEN`；`admin` 403 `ROLE_MISMATCH` | API JSON |
| PV-25 | AC-2.5 | — | PV-19 至 PV-24 之後讀取；inspect | — | `O_SUB` INITIAL 與 `O_REFUND_SUB` 退款皆仍 `SUBMITTED`；兩訂單 `orderLogs` 無 `PAYMENT_VERIFIED` / `PAYMENT_REJECTED` | API JSON |
| PV-26 | AC-2.6 | sitter | 前置 `O_CONF`（確認用 key `Kv`，記第一次 body B）；API：E14 新 key | — | 409 `PAYMENT_STATE_CONFLICT`、`currentStatus=VERIFIED` | API JSON |
| PV-27 | AC-2.6 | sitter | API：E14 以 `Kv` 與相同 body 重送 | — | 200，body 與 B 完全相同；inspect `PAYMENT_VERIFIED` log 恰 1 筆、`ORDER_ACTIVATED` 通知恰 2 筆 | API JSON |
| PV-28 | AC-2.6 | sitter | 前置 `O_SUB`（X）；`sitter` 開啟 `/sitter/orders/{O}` 停在 X；另以 API：`sitter` E15 駁回 X、`owner` E11 上傳 Y；回到原頁點 `btn-verify-payment` | 顯示「付款狀態已變更，請重新整理」 | 頁面 E14 body `submissionId=X` → 409 `PAYMENT_STATE_CONFLICT`；E10 仍 `SUBMITTED`、`currentSubmissionId=Y` | 截圖 + API JSON |
| PV-29 | AC-2.6 | sitter | 前置 `O_DISP_SS`；API：E14、E15 對補款 payment | — | 兩者皆 409 `ORDER_STATE_CONFLICT`、`currentStatus=DISPUTED` | API JSON |
| PV-30 | AC-2.7 | owner、sitter | 沿用 PV-09 的訂單（X `REJECTED`、Y `SUBMITTED`）；雙方各呼叫 E12 | — | 各回 2 筆 `[Y, X]`，每筆含 `status`、`lastFive`、`submittedAt`、`disclaimerAgreedAt`；X `rejectReason=金額不符`、有 `reviewedAt`；Y 無 `rejectReason`、無 `reviewedAt` | API JSON |
| PV-31 | AC-2.7 | owner | API：E13 `GET .../submissions/{Y}/proof-url`；GET 回傳的 `url`；GET 同物件去掉 query 的網址；UI `submission-history-image-{Y}` | 圖片載入完成（`naturalWidth > 0`） | E13 200 `{ url, expiresAt }`；`url` 主機 `storage.googleapis.com`、路徑含 `payment-proofs/` 與 `/{O}/{P}/`、query 含 `X-Goog-Expires=300`；`expiresAt` − 回應時間介於 295–305 秒；GET `url` 200 且 `Content-Type` 為 `image/png`；去 query 的網址 403；證據 JSON 遮蔽 `X-Goog-Signature` / `X-Goog-Credential` | 截圖 + API JSON |
| PV-32 | AC-2.7 | owner2 | API：E12、E13（Y） | — | 兩者皆 403 `FORBIDDEN` | API JSON |
| PV-33 | AC-2.7 | owner | 另建 `owner` 的 `O_PP`（O_other）；API：E12 `/api/orders/{O_other}/payments/{P}/submissions`、E13 `/api/orders/{O_other}/payments/{P}/submissions/{Y}/proof-url` | — | 兩者皆 404 `NOT_FOUND` | API JSON |
| PV-34 | AC-2.7 | admin | 前置 `O_ESC`；API：E10、E12、E27 `GET /api/admin/orders/{O}`；UI 開啟 `/admin/orders/{O}` | `payment-account-snapshot` 顯示 X 四欄、區塊內無可編輯輸入框與編輯按鈕；`submission-history` 可見 | E10 200，INITIAL `accountSnapshot` = X 四欄；E12 200；E27 200、`role=ADMIN`、`payments[0].accountSnapshot` = X | 截圖 + API JSON |
| PV-35 | AC-2.7 | admin | 前置 `O_ESC` → `sitter` E14（待辦 `RESOLVED`、訂單 `CONFIRMED`）；API：E10、E12、E13、E27；UI 開啟 `/admin/orders/{O}`。另以 `O_ADJ`（調解後終態，曾有 `MODIFICATION_PAYMENT_DISPUTE` 待辦）呼叫 E27 | 唯讀頁面可見 `payment-account-snapshot`、`submission-history`；`btn-admin-void` 數量 0（無作廢入口） | `O_ESC`：E10 200 且 INITIAL `accountSnapshot` = X 四欄、E12 200、E13 200、E27 200（`adminTasks[0].status=RESOLVED`、`resolution=SITTER_VERIFIED`，`allowedActions` 不含 `VOID_INITIAL_PAYMENT`）；`O_ADJ`：E27 200（唯讀範圍 = `DISPUTED` 或曾有任何待辦，baseline CQ-84） | 截圖 + API JSON |
| PV-36 | AC-2.7 | admin | 前置 `O_SUB`（從未有任何待辦、非 `DISPUTED`）；API：E10、E12、E27；UI 開啟 `/admin/orders/{O}` | 「無權限檢視此訂單」 | 三者皆 403 `FORBIDDEN` | 截圖 + API JSON |
| PV-37 | AC-2.8 | sitter | 前置 `O_SUB`；API：E14；觸發 C11 `POST /api/internal/cron/notifications/email-dispatch?orderId={O}`；inspect | — | E14 200、`orderStatus=CONFIRMED`；E2 `CONFIRMED`；雙方站內通知列表含「訂單啟動成功」；inspect 兩筆 `ORDER_ACTIVATED` 的 `emailStatus=SUPPRESSED_TEST`（測試帳號不送 Resend），C11 後訂單與 payment 狀態不變；Resend 5xx 與重試 5 次見 §4 | API JSON |

### 2.3 子模組 3：首次付款期限、提醒、逾期、升級與 ADMIN 作廢

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| PS-01 | AC-3.1 | owner | 前置 `O_PP`（F = 台北今天 + 7）；讀 E2、E10；開啟 `/owner/orders/{O}` | `payment-due-at` 顯示「請於 {F − 1 天} 00:00 前完成付款」（Asia/Taipei `YYYY-MM-DD HH:mm`） | E10 `dueAt` = F 00:00 台北 − 24h 的 UTC ISO 字串（因 `quotedAt + 2h` 較早）；`escalateAt` = F 00:00 台北 + 2h 的 UTC ISO 字串；spec 以 E2 `firstServiceDate`、`quote.quotedAt` 套 baseline §5 公式計算預期值後逐字比對 | 截圖 + API JSON |
| PS-02 | AC-3.1 | owner | 前置 `O_URGENT`（F = 台北明天）；讀 E2、E10；開啟頁面 | `payment-due-at` 為 `dueAt` 的台北時間 | `dueAt` = max(F 00:00 台北 − 24h, `quotedAt` + 2h)；`escalateAt` = max(F 00:00 台北, `dueAt`) + 2h；逐字比對（固定日期範例值見 §4） | 截圖 + API JSON |
| PS-03 | AC-3.2 | ownerMuted | 前置 `ownerMuted` 的 `O_PP`；shift-time `target=REMIND_AT` 至過去；C2 `POST /api/internal/cron/payments/reminder?orderId={O}` | — | C2 200 `processed=1`、`skipped=0`、`failed=0`；inspect：`notifications` `PAYMENT_REMINDER` 恰 1 筆給 `ownerMuted`、`mandatory=true`、`emailStatus=SUPPRESSED_TEST`（偏好關閉仍排入）；對照：同訂單報價時的 `QUOTE_SENT`（`mandatory=false`）`emailStatus=SKIPPED_PREFERENCE`；`payments[0].remindedAt` 有值；`orderLogs` 無新增業務 action；站內通知列表可見 | API JSON |
| PS-04 | AC-3.2 | — | 再呼叫 C2（同 `orderId`） | — | `processed=0`、`skipped=0`、`failed=0`；inspect `PAYMENT_REMINDER` 仍 1 筆 | API JSON |
| PS-05 | AC-3.2 | — | 前置 `O_SUB`；shift-time `REMIND_AT` 至過去；C2 `?orderId` | — | `processed=0`；inspect 無 `PAYMENT_REMINDER`、`remindedAt` 無值 | API JSON |
| PS-06 | AC-3.2 | — | 前置 `O_PP`；shift-time `REMIND_AT` 與 `PAYMENT_DUE_AT` 皆至過去（模擬停擺至 `due_at` 後）；C2 `?orderId` | — | `processed=0`；inspect 無 `PAYMENT_REMINDER` | API JSON |
| PS-07 | AC-3.2 | — | 前置 `O_URGENT`；C2 `?orderId` | — | `processed=0`；inspect：`payments[0]` 無 `remindAt`；無 `PAYMENT_REMINDER`；報價時已有 `QUOTE_SENT_WITH_PAYMENT_REMINDER` 恰 1 筆、`mandatory=true`（PRD-006 AC-2.5） | API JSON |
| PS-08 | AC-3.2 | — | 前置 `O_SUPP`；C2 `?orderId` | — | `processed=0`；inspect 該訂單無 `PAYMENT_REMINDER` | API JSON |
| PS-09 | AC-3.3 | owner | 前置 `O_PP`；shift-time `PAYMENT_DUE_AT` 至過去；C3 `POST /api/internal/cron/payments/initial-expiry?orderId={O}`；開啟 `/owner/orders/{O}` | `order-status-badge` 為「已取消」 | C3 200 `processed=1`、`skipped=0`、`failed=0`；E2 `status=CANCELLED`、`cancellation.cancelledBy=SYSTEM`、`visits` 5 筆皆 `CANCELLED`；E10 `status=VOID`、有 `voidedAt`；inspect：`orderLogs` 恰 1 筆 `PAYMENT_EXPIRED`、`operatorRole=SYSTEM`、`transitions` 含 `PAYMENT UNPAID → VOID`、`ORDER PENDING_PAYMENT → CANCELLED`；`notifications` `PAYMENT_EXPIRED` 恰 2 筆（`owner`、`sitter` 各 1）、`mandatory=true`；`fulfill.visits` 5 筆皆 `CANCELLED` | 截圖 + API JSON |
| PS-10 | AC-3.3 | — | 再呼叫 C3（同 `orderId`） | — | `processed=0`；inspect `PAYMENT_EXPIRED` log 仍 1 筆、通知仍 2 筆 | API JSON |
| PS-11 | AC-3.4 | sitter | 前置 `O_SUB`；shift-time `PAYMENT_DUE_AT` 至過去；C3 `?orderId`；之後 `sitter` E14 | — | C3 `processed=0`；E2 仍 `PENDING_PAYMENT`、E10 仍 `SUBMITTED`；E14 200、`orderStatus=CONFIRMED` | API JSON |
| PS-12 | AC-3.4 | sitter | 前置 `O_SUB`；shift-time `PAYMENT_DUE_AT` 至過去；開啟 `/sitter/orders/{O}` 點 `btn-reject-payment` | `reject-overdue-dialog` 文字「已過付款期限，駁回後訂單將被取消」；點 `reject-overdue-dialog-btn-cancel` 後關閉 | 期間 E15 請求數 0；E10 仍 `SUBMITTED` | 截圖 |
| PS-13 | AC-3.4 | sitter | 再點 `btn-reject-payment` → `reject-overdue-dialog-btn-confirm` → `reject-reason-input` 填「查無入帳」→ `reject-submit-btn` | — | E15 200、`paymentStatus=UNPAID` | 截圖 + API JSON |
| PS-14 | AC-3.4 | owner | API：E11 | — | 409 `DEADLINE_PASSED`、`deadline=PAYMENT_DUE_AT`、`deadlineAt` = `dueAt` | API JSON |
| PS-15 | AC-3.4 | — | C3 `?orderId` | — | `processed=1`；E2 `CANCELLED`、`cancelledBy=SYSTEM`；E10 `VOID`（無寬限） | API JSON |
| PS-16 | AC-3.4 | sitter | `now < due_at` 駁回不顯示確認框：同 PV-07 | `reject-overdue-dialog` 數量 0 | — | 截圖（引用 PV-07） |
| PS-17 | AC-3.5 | admin | 前置 `O_SUB`；shift-time `ESCALATE_AT` 至過去；C4 `?orderId`；`admin` E28 `GET /api/admin/tasks?status=OPEN&type=INITIAL_PAYMENT_UNVERIFIED`（翻頁至找到）；UI 開啟 `/admin/tasks` | 可見 `admin-task-item-{taskId}`（該訂單） | C4 `processed=1`、`skipped=0`、`failed=0`；E28 中 `orderId={O}` 恰 1 筆、`paymentId={P}`、`status=OPEN`；inspect：`adminTasks` 恰 1 筆 `type=INITIAL_PAYMENT_UNVERIFIED`、`status=OPEN`；`payments[0].escalatedAt` 有值；`notifications` `PAYMENT_ESCALATED` 恰 2 筆、`mandatory=false`；`orderLogs` `PAYMENT_ESCALATED` 恰 1 筆 | 截圖 + API JSON |
| PS-18 | AC-3.5 | admin | 再呼叫 C4（同 `orderId`）；E28 | — | `processed=0`；`orderId={O}` 仍恰 1 筆 OPEN；inspect `adminTasks` 仍 1 筆 | API JSON |
| PS-19 | AC-3.5 | sitter | `sitter` E14 | — | 200、`CONFIRMED`；inspect 待辦 `status=RESOLVED`、`resolution=SITTER_VERIFIED`、有 `resolvedAt`；E28 OPEN 不再含 `{O}` | API JSON |
| PS-20 | AC-3.5 | sitter | 另建 `O_SUB` → `ESCALATE_AT` 平移 → C4 `?orderId`（`processed=1`）→ `sitter` E15 `reason=金額不符` | — | E15 200；E2 `PENDING_PAYMENT`；inspect 待辦 `RESOLVED`、`resolution=SITTER_REJECTED`；E28 OPEN 不含該訂單 | API JSON |
| PS-21 | AC-3.5 | sitter | 另建 `O_SUB` → `ESCALATE_AT` 平移 → `sitter` E14 → C4 `?orderId` | — | C4 `processed=0`；inspect 該訂單 `adminTasks=[]`、無 `PAYMENT_ESCALATED`；E28 不含 | API JSON |
| PS-22 | AC-3.6 | admin | 前置 `O_ESC`；開啟 `/admin/orders/{O}`；點 `btn-admin-void`、`admin-void-input-reason` 填「雙方說法不一致」、點 `admin-void-btn-confirm` | 可見 `payment-account-snapshot`（X）與 `submission-history-image-{X}`；頁面無確認收款按鈕（`btn-verify-payment` 數量 0） | 頁面 E16 `POST /api/admin/orders/{O}/payments/{P}/void` 恰 1 次 → 200 `{ orderId, status: "CANCELLED", cancelledBy: "ADMIN", paymentStatus: "VOID" }` | 截圖 + API JSON |
| PS-23 | AC-3.6 | owner、sitter | `owner` E2、E10、E12、E13；開啟 `/owner/orders/{O}`；`sitter` E13 | `submission-history-item-{X}` 標示「已作廢」，`submission-history-image-{X}` 可載入 | E2 `CANCELLED`、`cancellation={ cancelledBy: "ADMIN", reason: "雙方說法不一致" }`、`visits` 全部 `CANCELLED`；E10 `VOID`、有 `voidedAt`；E12 X `status=SUBMITTED`、`voided=true`；雙方 E13 200 | 截圖 + API JSON |
| PS-24 | AC-3.6 | — | inspect；雙方站內通知列表 | — | 待辦 `RESOLVED`、`resolution=ADMIN_VOIDED`；`orderLogs` 恰 1 筆 `PAYMENT_VOIDED_BY_ADMIN`、`operatorRole=ADMIN`、payload `reason=雙方說法不一致`、`submissionId=X`、`adminTaskId`；`notifications` `PAYMENT_VOIDED_BY_ADMIN` 恰 2 筆、`mandatory=true`；通知內容含「平台已作廢此筆付款，款項請由雙方自行處理」 | API JSON |
| PS-25 | AC-3.7 | admin | 前置 `O_SUB`（未到 `escalate_at`、該訂單從未有任何 ADMIN 待辦、非 `DISPUTED`；inspect `adminTasks=[]`）；API：E16 `reason=測試`；E27 | — | 兩者皆 403 `FORBIDDEN`；E10 仍 `SUBMITTED` | API JSON |
| PS-26 | AC-3.7 | admin | 前置 `O_ESC`；`admin` 先開啟 `/admin/orders/{O}`；之後 `sitter` E14；`admin` 在舊畫面點 `btn-admin-void` → 填原因 → `admin-void-btn-confirm`；之後 E27 | 顯示「此待辦已處理，無法作廢」 | E16 403 `FORBIDDEN`；E2 仍 `CONFIRMED`；E27 仍 200（唯讀範圍含曾有待辦，baseline CQ-84） | 截圖 + API JSON |
| PS-27 | AC-3.7 | admin | 前置 `O_ESC` → `sitter` E15；`admin` E16；E27 | — | E16 403 `FORBIDDEN`；E10 `UNPAID`；E27 200 | API JSON |
| PS-28 | AC-3.7 | admin | 前置 `O_DISP_SS`；`admin` E16 對補款 payment | — | 403 `FORBIDDEN`；E10 補款仍 `SUBMITTED` | API JSON |
| PS-29 | AC-3.7 | admin | 前置 `O_ESC`；E16 `reason=""`；再 `reason` 201 字 | — | 兩者皆 400 `VALIDATION_FAILED`、`payload.field=reason`；E10 仍 `SUBMITTED`；inspect 待辦仍 OPEN | API JSON |
| PS-30 | AC-3.7 | admin | ADMIN 訂單頁無確認收款按鈕：同 PS-22 | `btn-verify-payment` 數量 0 | — | 截圖（引用 PS-22） |
| PS-31 | AC-3.8 | sitter、admin | 前置 `O_ESC`；`Promise.all` 同時送 `sitter` E14 與 `admin` E16（`reason=競爭測試`）；重複 3 次（每次新訂單），記錄勝方 | — | 恰一方 200：保母勝 → E2 `CONFIRMED`、`admin` 409 `PAYMENT_STATE_CONFLICT`（若 ADMIN 授權檢查晚於保母提交則為 403 `FORBIDDEN`，兩者皆接受，嚴格 409 分支見 §4）；ADMIN 勝 → E2 `CANCELLED`、`sitter` 409 `PAYMENT_STATE_CONFLICT`；inspect `PAYMENT_VERIFIED` 與 `PAYMENT_VOIDED_BY_ADMIN` 恰出現其一 | API JSON |
| PS-32 | AC-3.8 | sitter、admin | 同 PS-31，保母改送 E15 `reason=金額不符` | — | 保母勝 → E10 `UNPAID`、inspect 待辦 `RESOLVED` 且 `resolution=SITTER_REJECTED`、`admin` 409 或 403；ADMIN 勝 → E2 `CANCELLED`、`sitter` 409 `PAYMENT_STATE_CONFLICT`；`PAYMENT_REJECTED` 與 `PAYMENT_VOIDED_BY_ADMIN` 恰出現其一 | API JSON |

## 3. 權限與例外

> PRD-007 §7 每個 EX 的覆蓋位置。越權一律以另一個真實帳號打 API（`owner2`、`sitterB`、`admin`）。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| P-11、PV-21、PV-32 | EX-1 | `owner2` 讀付款頁 / 上傳 / 讀憑證；`sitterB` 確認他人訂單 | 403 `FORBIDDEN`，資料不變 |
| P-15、PV-33 | EX-2 | 不存在訂單；payment / submission 不屬路徑訂單 | 404 `NOT_FOUND` |
| P-12、P-14、PV-16、PV-17、PV-20、PV-24 | EX-3 | 方向不符（含 `DISPUTED` 時飼主駁回退款） | 403 `FORBIDDEN` |
| P-13、PV-22、PV-23、PV-24 | EX-4 | ADMIN 呼叫上傳 / 確認 / 駁回；當事人錯角色 | 403 `ROLE_MISMATCH` |
| P-10、PV-19 | EX-5 | 未登入 | 401 `UNAUTHENTICATED` |
| P-06、P-20、P-21、P-25、PV-10、PS-29；PX-01 | EX-6 | 欄位錯誤、原因空白 / 超長、缺 key | 400 `VALIDATION_FAILED` |
| P-22 | EX-7 | GIF / HEIC / PDF | 422 `PROOF_FILE_TYPE_UNSUPPORTED` |
| P-23、P-24 | EX-8 | 6 MB | 前端擋；後端 422 `PROOF_FILE_TOO_LARGE` |
| P-08、P-33 至 P-36 | EX-9 | payment 非 `UNPAID` | 409 `PAYMENT_STATE_CONFLICT` |
| P-09、PV-26、PV-28 | EX-10 | 非 `SUBMITTED` / `submissionId` 過期 / `ADMIN_ADJUSTMENT` | 409 `PAYMENT_STATE_CONFLICT` |
| P-37、PV-18、PV-29 | EX-11 | `DISPUTED` 凍結（飼主駁回退款除外，見 EX-3） | 409 `ORDER_STATE_CONFLICT`、`currentStatus=DISPUTED` |
| P-38、PV-14、PS-14 | EX-12 | `receivedAt ≥ due_at` 上傳 | 409 `DEADLINE_PASSED`，payload 兩欄 |
| P-29、PV-27 | EX-13 | 同 key 同內容 | 回放第一次狀態碼與 body |
| P-30、P-32 | EX-14 | 同 key 不同內容 / 不同訂單 | 422 `IDEMPOTENCY_KEY_REUSED` |
| §4 | EX-15 | 同 key 處理中 | 後端測試 |
| P-31 | EX-16 | 新 key 再上傳 | 409 `PAYMENT_STATE_CONFLICT` |
| §4 | EX-17、EX-24 | 鎖逾時、`@Version` | 後端測試 |
| P-40、PS-31、PS-32 | EX-18 | 跨角色競爭 | 恰一方成功 |
| §4 | EX-19、EX-20、EX-21 | GCS 上傳失敗、交易失敗刪物件、簽名失敗 | 後端測試 |
| PV-37；§4 | EX-22 | Resend 寄送失敗 | E2E：主操作 200、站內通知存在（測試帳號 `SUPPRESSED_TEST`）；實際 5xx / 重試由後端測試 |
| PX-02 | EX-23 | SaaS 方案 | FREE 且鎖定也能確認收款 |
| PS-31、PS-32；§4 | EX-25 | 作廢通過授權後重驗失敗 | 409 `PAYMENT_STATE_CONFLICT` |
| PV-36、PS-25；PV-35（範圍內對照） | EX-26 | ADMIN 讀取從未有任何待辦且非 `DISPUTED` 的訂單；對照：待辦已 RESOLVED / 調解後仍可讀 | 403 `FORBIDDEN`；對照 200 |
| PS-26、PS-27 | EX-27 | 待辦已關閉後作廢 | 403 `FORBIDDEN` |
| PV-12、PV-13、PS-12、PS-13 | EX-28 | 逾期後駁回確認框 | 兩種文案；駁回 200 |
| PS-05、PS-06、PS-11、PS-21；§4 | EX-29 | 排程 vs 使用者 | 條件不符不處理（`processed=0`） |
| PS-28 | EX-30 | ADMIN 作廢非 INITIAL | 403 `FORBIDDEN` |
| PX-01 | EX-6、BR-18 | `owner` E11 不帶 `Idempotency-Key`（其餘有效） | 400 `VALIDATION_FAILED`、`payload.field=Idempotency-Key`；E10 仍 `UNPAID` |
| PX-02 | EX-23、BR-10 | 見 PV-01 / PV-02：FREE 且訂閱鎖定的保母確認收款 | 200（不回 `SUBSCRIPTION_LOCKED` / `PLAN_REQUIRED`） |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.3、AC-1.5、AC-1.7「GCS 無新物件」 | inspect 不含 GCS 物件清單，正式站無法判斷「未新增」；E2E 只驗 payment / submission / log 不變 | `PaymentSubmissionIntegrationTest`（`LocalMediaStorageServiceImpl` 替身斷言零物件） |
| AC-1.5 5 MB 邊界（恰 5,242,880 bytes 通過、+1 byte 拒絕） | 邊界值以整合測試驗證較穩，E2E 只驗 6 MB | `PaymentSubmissionIntegrationTest` |
| AC-1.6 / EX-15 同 key 處理中 409 `IDEMPOTENCY_IN_PROGRESS`；`locked_until` 接手 | 正式站無法穩定讓第一次請求停在處理中 | `PaymentSubmissionIdempotencyIntegrationTest` |
| AC-1.7 固定時刻邊界（16:01:00 → `DEADLINE_PASSED`；15:59:59 收到、進交易已過期仍 201；進交易前 O8 已執行 → 409 且物件被刪） | 需注入 `Clock` 與控制 GCS 上傳耗時 | `PaymentSubmissionIntegrationTest`（`receivedAt`）、`PaymentSubmissionConcurrencyIntegrationTest`（O8 先到、物件刪除） |
| AC-1.8 / EX-19 GCS 5xx → 503 `STORAGE_UNAVAILABLE`、同 key 恢復後 201；EX-20 取消先到時物件被刪 | 正式站無法注入 GCS 故障、無法觀察物件刪除 | `PaymentSubmissionConcurrencyIntegrationTest` |
| AC-2.6 / EX-17、EX-24 鎖 > 3 秒 409 `ORDER_BUSY` + `Retry-After: 1` | 需另一連線持有訂單列鎖 | `PaymentReviewIntegrationTest`（SD-ORDER §10.1 鎖逾時做法） |
| AC-2.7 簽名網址實際於 5 分鐘後失效、前端重新請求新網址 | 等待 5 分鐘拖慢 CI；前端判斷失效的機制 SD 未定義 | 手動驗證：取得 E13 網址，等待 5 分 10 秒後 GET 應得 403，畫面重新整理圖片後可再載入；E2E 以 PV-31 `X-Goog-Expires=300` 代替；TTL 設定由 `PaymentQueryIntegrationTest` 驗證 |
| AC-2.8 Resend 5xx、outbox 重試 5 次後標記失敗 | 測試帳號收件者一律 `SUPPRESSED_TEST`、不送 Resend；重試排程屬 SD-NOTIFY | `PaymentReviewIntegrationTest`（Resend 不在交易內呼叫、狀態不回滾）；重試次數由 SD-NOTIFY 投遞排程測試（類別待 SD-NOTIFY 定義） |
| AC-3.1 範例固定值（2026-11-23T16:00:00Z 等） | 正式站時間不可控；E2E 以公式比對 | `QuoteIntegrationTest` |
| AC-3.2「台北 11/23 00:00 後第一次排程 15 分鐘內」 | 實際排程時刻不可控；E2E 以 shift-time + 帶 `orderId` 觸發 C2 | `PaymentCronIntegrationTest` |
| AC-3.8 嚴格情境「兩者皆已通過授權檢查」才回 409 | E2E 無法保證兩請求都先通過鎖前檢查 | `AdminVoidConcurrencyIntegrationTest` |
| EX-21 簽名網址產生失敗 503 | 無法注入 | `PaymentQueryIntegrationTest` |
| EX-25 作廢鎖後重驗失敗 | 同 AC-3.8 | `AdminVoidConcurrencyIntegrationTest` |
| EX-29 排程與使用者的鎖序列化（同時發生時的 `SKIP LOCKED` / 重驗略過） | 需控制交錯時序；E2E 只驗先後發生的結果 | `PaymentCronIntegrationTest`、`PaymentSubmissionConcurrencyIntegrationTest` |
| 交易回滾後 `order_logs` 與通知不存在 | 需注入交易失敗 | `OrderLogWriterTest`、`PaymentReviewIntegrationTest` |
| 真實 Email 內容 | Resend 測試模式只寄專案擁有者信箱 | 手動驗證：以專案擁有者信箱註冊的保母 / 飼主走一次 P-17、PV-07、PS-24，檢查信件內容不含完整收款帳號 |
| 收款帳號快照加密存放（BR-2，NFR-03） | E2E 讀不到 DB 原始值 | `QuoteIntegrationTest`（快照非明文、可解回四欄）；`APP_CRYPTO_KEY` 無預設值依 SD-ORDER §8.3 D1 部署驗證 |

## 5. 已知限制 / 待補

1. **BR-1 與 PRD-006 BR-16 同頁（已解決）**：PRD-007 BR-1 / §4.1-1 與 PRD-006 BR-16 已回寫為「付款區塊內不顯示總額，同頁報價明細可顯示」。P-02 據此斷言：`payment-block-{paymentId}` 內不出現訂單總額或差額公式、全頁無 `order-total-amount`；同頁報價明細三段（`order-detail-quote-original-total`、`order-detail-quote-modification-{modificationId}`、`order-detail-quote-current-total`）顯示總額不算違規。
2. **Email 投遞（已解決）**：SD-NOTIFY / SD-ORDER §10.4 定義 `emailStatus` 列舉，測試帳號收件者依偏好應寄者標 `SUPPRESSED_TEST`、不送 Resend；E2E 的 Email 斷言收斂為 `SUPPRESSED_TEST` / `SKIPPED_PREFERENCE`。實際寄出與重試仍見 §4。
3. **站內通知列表 API（未解決）**：SD-NOTIFY 尚未重建，站內可見性沿用現行 `GET /api/notifications?role={ROLE}`；type / `mandatory` / `emailStatus` 改由 inspect 讀取。
4. **已解決（2026-11-22 依 SD-ORDER 核准後修訂）**：inspect 唯讀端點（§10.4）；ADMIN 與 OWNER + SITTER 雙角色 provision（`roles[]`）；排程帶 `orderId` 只處理測試訂單、自動觸發跳過測試訂單（不再需要避開排程分鐘與重跑因應）；SD-FULFILL FT1 支援讓訂單進入 `IN_PROGRESS`；testid 缺口由 SD-ORDER §9.3 補定（`payment-block-{paymentId}`、`reject-submit-btn`、`reject-overdue-dialog*`、`admin-void-*`、`admin-task-item-{taskId}`、`payment-refund-dispute-link`、`submission-history-item/image-{submissionId}`）；金額格式統一 SD-FRONTEND-SPEC §6.4.1；PRD-007 AC-1.7 Given 改為 `DISPUTED` + `MODIFICATION_REFUND` `UNPAID`；EX-11 / AC-2.4 對齊 403 / 409。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|
| 2026-10 | 舊版步驟 6–7（對應本版 PV-31） | 舊實作付款憑證以公開 ACL 上傳，DB 與 DTO 存永久公開網址；改為私有物件 + 授權後簽名網址。新設計 E13 沿用私有物件，PV-31 以去 query 網址 403 迴歸 | 舊 TS 未記錄 commit |
| 2026-10 | 舊版 Journey B 步驟 7（對應本版 PV-07） | 「駁回憑證」按鈕沿用 `.btn-primary` 漸層，文字對比度低於 WCAG AA；改白底錯誤色文字。迴歸 `payment-reject-button-contrast.spec.ts`，改指新付款區塊後保留 | 舊 TS 未記錄 commit |

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依核准後修訂同步（使用者 2026-11-22 同意）：ADMIN 唯讀範圍改為「`DISPUTED` 或曾有任何待辦」——新增 PV-35（待辦 RESOLVED 與調解後 E10 / E12 / E13 / E27 仍 200、無作廢入口），原 PV-35 / PV-36 改為 PV-36（從未有待辦 → 403）/ PV-37；PS-25 Given 改「從未有任何 ADMIN 待辦」，PS-26、PS-27 補 E27 仍 200；inspect 斷言補 `payments[]` `amount` / `direction` / `purpose`（P-18）；Email 斷言收斂為 `emailStatus=SUPPRESSED_TEST`（P-18、PS-03、PV-37），PS-03 補 `SKIPPED_PREFERENCE` 對照；P-02 改用報價明細三段 testid；狀態文字標依 SD-FRONTEND-SPEC §6.4；§5-2 標已解決；狀態維持 Approved | PRD-007 AC-2.7 / AC-3.7 核准後回寫（baseline CQ-84）；SD-ORDER §6.3 E2 / E27、§10.4 inspect 修訂 |
| 2026-11-22 | 依 SD-ORDER / PRD-007 核准後修訂（commit 477c75d）更新：前置資料改用 `provision-account` `roles[]`、setup upsert 與 key 對應 id；所有 order_logs / 通知 / 待辦 resolution / payment 時間欄斷言改由 inspect 在 E2E 驗證；C2–C4、C11 一律帶 `orderId` 並斷言精確 `processed` / `skipped` / `failed`，移除避開排程分鐘與被搶先重試的因應；套用 §9.3 testid（payment 容器、駁回送出、逾期確認框、ADMIN 作廢、待辦列表、退款爭議入口、submission 單筆與圖片）；補 E10 `verifiedAt` / `voidedAt`、E12 `reviewedAt` / `verifiedAt` 斷言；PV-10 補全形空白 strip；P-02 依 PRD-007 BR-1 / PRD-006 BR-16 回寫斷言付款區塊內無總額、同頁報價明細可顯示，§5-1 標已解決；AC-2.4 拆為 PV-17（駁回 403）、PV-18（確認 409），PV- 步驟改為 36 步；P-37 對齊新 AC-1.7 Given；§4 移除不可達 Given 列；§5 已解決項標註 | SD-ORDER §6.3、§9.3、§10.4 修訂；PRD-007 AC-1.7、AC-2.4、EX-11 修訂 |
| 2026-11-22 | 依 PRD-007（Approved 2026-11-22）與 SD-ORDER 全文重寫：改用新範本；三子模組 24 條 AC 全部對應步驟；EX-1–EX-30 對應表；自動化路徑改為 journey spec；移除寫死 UUID 與 Controller 測試路徑；收款帳號設定移至 TS-025；狀態 Draft | 全面重建；抽樣缺陷 #14、#15、#16 |
| 2026-11-22 | 使用者核准：狀態 Draft → Approved | 使用者 2026-11-22 |
