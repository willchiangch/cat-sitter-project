# TS-025: 保母收款帳號設定

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 對應 PRD | PRD-025（前置資料借用 PRD-005 送單、PRD-006 報價、PRD-007 付款頁快照、PRD-014 通知偏好） |
| 對應 SD | SD-SUPPLY（**尚未撰寫**，收款帳號 API、畫面路由、testid、稽核資料皆未定義）；快照與報價部分 SD-ORDER（§2.3.2 / §2.4.2 `PayoutAccountQuery`、§6.3 E5、E6、E10、§8.4.1 SD-SUPPLY 過渡實作、§10.4 測試資料端點） |
| 類型 | 旅程（打真後端） |
| 自動化路徑 | `frontend/e2e/journeys/sitter-payout-account.spec.ts`（新建；SD-SUPPLY 核准前無法實作，見 §5-1） |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD-025、SD-ORDER、DOMAIN-BASELINE 撰寫，未參考程式碼。SD-SUPPLY 未定義的 API 路徑、畫面路由與 testid 一律以業務名稱描述，由 e2e-author 於 SD-SUPPLY 核准後對應，不虛構路徑。
> 下文「讀取 API」「設定 API」「刪除請求」= 保母本人收款帳號的 GET / PUT / DELETE（以 token 定位本人，不帶 sitter ID，PRD-025 §3）；欄位名稱依 PRD-025 §4.1：`configured`、`bankCode`、`branch`、`accountNumber`、`accountName`、`updatedAt`。
> 「收款帳號頁」= 保母「我的 > 收款帳號」畫面。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 自建資料、可獨立重跑；所有帳號 `@e2e-journey.test`，由 `POST /api/internal/test-data/provision-account` 建立；測試資料端點帶 `X-Internal-Secret`（值取自 CI secret）。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `sitterNew` | SITTER | `provision-account`：`roles=["SITTER"]`、`displayName=新保母` → `POST /api/internal/test-data/sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount=null`、`plans=[{ key: "p1", 單價 600、適用貓、active: true }]`；回應 `plans[key=p1].planId` | 尚未設定收款帳號 |
| `sitterSet` | SITTER | 同上，`payoutAccount` = X（`812` / `台北分行` / `12345678901` / `王小明`） | 已設定 X |
| `sitterLocked` | SITTER | 同 `sitterSet`，再以 `sitter-setup`（upsert，只送此欄）設 `subscriptionExpiresAt` = `serverTime` − 1 天 | 訂閱鎖定 |
| `sitterNoProfile` | SITTER | `provision-account`：`roles=["SITTER"]`、`sitterProfile=false`（SD-ORDER §10.4）；不呼叫 `sitter-setup` | 無保母 profile |
| `owner` | OWNER | `provision-account`：`roles=["OWNER"]` → `POST /api/internal/test-data/owner-setup`：`pets=[{ key: "cat1", 名稱「小橘」, 種類貓 }]`；回應 `pets[key=cat1].petId` | — |
| `admin` | ADMIN | `provision-account`：`roles=["ADMIN"]` | — |
| `orderPending(s)` | — | `owner` E1 `POST /api/orders` 向保母 s 預約：日期台北今天 + 7 天 1 天、`dailyCount=1`、`petIds=[小橘]`、`expectedAmount=600` | `PENDING` |
| `orderQuoted(s)` | — | `orderPending(s)` → s E6 `POST /api/orders/{orderId}/quote` `expectedAmount=600` | `PENDING_PAYMENT`；INITIAL payment `UNPAID` |

- 帳號 Y = `700` / `信義分行` / `98765432100` / `李大華`；帳號 X' = `812` / `台北分行` / `98765478901` / `王小明`（後五碼與 X 相同，只改帳號）。
- 「payout-inspect」= 讀取該保母收款帳號稽核紀錄與帳號類通知（type、`mandatory`、Email 投遞狀態）的唯讀測試端點。SD-ORDER §10.4 的訂單 inspect 只涵蓋訂單相關資料，不含此項；**待 SD-SUPPLY 定義**（§5-2）。未提供前，標「payout-inspect」的斷言由 §4 後端測試承擔，站內通知可見性仍以通知列表驗證。
- 訂單相關斷言（快照、報價結果）可用 SD-ORDER §10.4 `GET /api/internal/test-data/orders/{orderId}/inspect`。

清除：`POST /api/internal/cron/test-data/cleanup-e2e-journeys`（SD-ORDER §10.4「Cleanup 跨模組範圍」已含 SD-SUPPLY 收款帳號與其稽核紀錄）。

## 2. 測試步驟

> 步驟 ID 前綴 `PA-`；權限與例外補充列 `PAX-`。e2e spec 的 `step()` / `apiStep()` 標籤以同一 ID 開頭。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| PA-01 | AC-1.1 | sitterNew | 呼叫讀取 API | — | 200，body 恰為 `{ "configured": false }`（無其他欄位） | API JSON |
| PA-02 | AC-1.1 | sitterNew | 開啟收款帳號頁 | 顯示「尚未設定收款帳號」與 4 欄設定表單（銀行代碼、分行、帳號、戶名）；無刪除按鈕 | — | 截圖 |
| PA-03 | AC-1.1 | sitterNew | 前置 `orderPending(sitterNew)`；E5 `GET /api/orders/{O}/evaluation`；開啟 `/sitter/orders/{O}`；再以 API E6（key K1、`expectedAmount=600`）直接報價 | `sitter-order-eval-precheck-banner` 顯示「請先設定收款帳號」並提供前往收款帳號頁的連結；`sitter-order-eval-btn-quote-original`、`sitter-order-eval-btn-quote-adjust`、`sitter-order-eval-btn-quote-submit` 全部停用（PRD-006 預檢阻擋） | E5 `precheck.payoutAccountMissing=true`；E6 422 `PAYOUT_ACCOUNT_REQUIRED`；E2 `status=PENDING`；E10 `GET /api/orders/{O}/payments` → `[]`；訂單 inspect `orderLogs` 無 `ORDER_QUOTED`（PRD-006 AC-3.8 驗收，本卡確認碼名一致） | 截圖 + API JSON |
| PA-04 | AC-1.1 | sitterNew | 收款帳號頁填銀行代碼 `812`、分行 `台北分行`、帳號 `12345678901`、戶名 `王小明`，送出；攔截頁面發出的設定請求 | 顯示已設定的四個值；送出前後未要求輸入密碼或重新登入 | 設定請求恰 1 次（PUT 整筆、四欄皆在 body）→ 200 | 截圖 + API JSON |
| PA-05 | AC-1.1 | sitterNew | 呼叫讀取 API | — | 200：`configured=true`、`bankCode=812`、`branch=台北分行`、`accountNumber=12345678901`、`accountName=王小明`、`updatedAt` 為 ISO-8601 UTC 且介於 PA-04 送出前後 | API JSON |
| PA-06 | AC-1.1 | sitterNew | payout-inspect；`sitterNew` 站內通知列表（`GET /api/notifications?role=SITTER`） | — | 稽核紀錄恰 1 筆：操作者 = `sitterNew`、變更前為空、變更後「812 / …78901」、不含「12345678901」「王小明」；通知「收款帳號已設定」站內 1 則、`mandatory=true`、`emailStatus=SUPPRESSED_TEST`（payout-inspect 讀取方式待 SD-SUPPLY 定義）；站內通知內容不含「12345678901」「王小明」 | API JSON |
| PA-07 | AC-1.1 | sitterNew | 重新開啟 `/sitter/orders/{O}`，點 `sitter-order-eval-btn-quote-original`（前端新產生 key；API 版以新 key K2，K1 會回放 422，見 §5-3） | 預檢橫幅不再提示收款帳號、三個報價鈕可用；報價成功 | E6 200、`status=PENDING_PAYMENT`；E10 INITIAL `accountSnapshot={ bankCode: "812", branch: "台北分行", accountNumber: "12345678901", accountName: "王小明" }` | 截圖 + API JSON |
| PA-08 | AC-1.2 | sitterSet | 設定 API 送 `bankCode=81`；再送 `bankCode=8123`（其餘三欄為 X） | — | 兩者皆 400 `VALIDATION_FAILED`、`payload.field=bankCode` | API JSON |
| PA-09 | AC-1.2 | sitterSet | 設定 API 送 `branch=""`；`branch="   "`；`branch` = 31 字 | — | 三者皆 400 `VALIDATION_FAILED`、`payload.field=branch` | API JSON |
| PA-10 | AC-1.2 | sitterSet | 設定 API 送 `accountNumber=123456789`（9 位）；`12345678901234567`（17 位）；`12345abc901` | — | 三者皆 400 `VALIDATION_FAILED`、`payload.field=accountNumber` | API JSON |
| PA-11 | AC-1.2 | sitterSet | 設定 API 送 `accountName=""`；`accountName` = 51 字 | — | 兩者皆 400 `VALIDATION_FAILED`、`payload.field=accountName` | API JSON |
| PA-12 | AC-1.2 | sitterSet | PA-08 至 PA-11 之後讀取；payout-inspect；站內通知列表 | — | 讀取 API 仍為 X 四欄；稽核紀錄與「收款帳號已變更」通知皆無新增 | API JSON |
| PA-13 | AC-1.2 | sitterSet | 設定 API 送 `bankCode=812`、`branch` = 30 字、`accountNumber=1234567890`（10 位）、`accountName` = 50 字；再送同組但 `accountNumber=1234567890123456`（16 位） | — | 兩者皆 200；讀取 API 回傳最後一組值 | API JSON |
| PA-14 | AC-1.2 | sitterSet | 收款帳號頁輸入帳號 `1234-5678 901`、分行 ` 台北分行 `（前後空白）、其餘為 X，送出；攔截設定請求 | 送出成功，畫面顯示帳號 `12345678901`、分行 `台北分行` | 設定請求 body `accountNumber=12345678901`、`branch=台北分行`（前端正規化）；200 | 截圖 + API JSON |
| PA-15 | AC-1.2 | sitterSet | 設定 API 直接送 `accountNumber="1234-5678 901"`、`branch=" 台北分行 "`；讀取 | — | 200；讀取 `accountNumber=12345678901`、`branch=台北分行`（後端正規化） | API JSON |
| PA-16 | AC-1.2 | sitterSet | 目前為 X；設定 API 送 `accountNumber="123-4567-8901"`，其餘三欄與 X 相同；payout-inspect；站內通知列表 | — | 200；稽核紀錄筆數不變；「收款帳號已變更」通知筆數不變 | API JSON |
| PA-17 | AC-1.3 | owner | 以 `owner`（OWNER token）呼叫讀取 API、設定 API（body 為 X） | — | 兩者皆 403 `ROLE_MISMATCH` | API JSON |
| PA-18 | AC-1.3 | admin | 呼叫讀取 API、設定 API | — | 兩者皆 403 `ROLE_MISMATCH` | API JSON |
| PA-19 | AC-1.3 | 匿名 | 不帶 token 呼叫讀取 API、設定 API | — | 兩者皆 401 `UNAUTHENTICATED` | API JSON |
| PA-20 | AC-1.3 | sitterNoProfile | 呼叫讀取 API；設定 API（body 為 X）；再呼叫讀取 API | — | 三次皆 404 `NOT_FOUND`；第二次讀取仍 404，證明設定請求未自動建立保母 profile（若自動建立，讀取會回 200） | API JSON |
| PA-21 | AC-1.4 | sitterSet | 前置 `orderQuoted(sitterSet)` = 訂單 A（快照 X）；設定 API 改為 Y | — | 200 | API JSON |
| PA-22 | AC-1.4 | owner | 開啟 `/owner/orders/{A}`；E10 | `payment-account-snapshot` 顯示 X 四值，不含「98765432100」「李大華」 | E10 A 的 INITIAL `accountSnapshot` = X 四欄（PRD-007 AC-1.1） | 截圖 + API JSON |
| PA-23 | AC-1.4 | sitterSet | 之後前置 `orderQuoted(sitterSet)` = 訂單 B；`owner` E10 | — | B 的 INITIAL `accountSnapshot={ bankCode: "700", branch: "信義分行", accountNumber: "98765432100", accountName: "李大華" }`（四欄皆來自 Y） | API JSON |
| PA-24 | AC-1.4 | sitterSet | 訂單 A 由 `owner` 上傳憑證使 payment `SUBMITTED`；`sitterSet` 再把帳號改為 X'；`owner` E10 A | — | A 的 `accountSnapshot` 仍為 X（`SUBMITTED` 不受影響，BR-3） | API JSON |
| PA-25 | AC-1.5 | sitterSet | 目前為 X；設定 API 改為 X'（只改帳號為 `98765478901`）；payout-inspect | — | 200；新增稽核 1 筆：操作者 = `sitterSet`、時間介於送出前後、變動欄位「帳號」、前後「812 / …78901」→「812 / …78901」，不含「12345678901」「98765478901」「王小明」；儲存值非明文見 §4 | API JSON |
| PA-26 | AC-1.5 | sitterSet | 目前為 X'；設定 API 只把 `bankCode` 改為 `700`；payout-inspect | — | 200；新增稽核 1 筆：變動欄位「銀行代碼」、前後「812 / …78901」→「700 / …78901」 | API JSON |
| PA-27 | AC-1.5 | sitterSet | 以與 PA-26 相同的四欄重送；payout-inspect | — | 200；稽核筆數不變 | API JSON |
| PA-28 | AC-1.6 | sitterSet | 以 `sitter-setup`（upsert，只送 `emailPreferences.ORDER_AFFAIR=false`）關閉「訂單事務」Email；設定 API 把帳號由 X 改為 X'；payout-inspect；站內通知列表 | — | 200；「收款帳號已變更」站內 1 則、`mandatory=true`、`emailStatus=SUPPRESSED_TEST`（不可關閉類別，偏好關閉仍排入，非 `SKIPPED_PREFERENCE`；payout-inspect 讀取方式待 SD-SUPPLY 定義）；通知內容含變動欄位名稱「帳號」與「…78901」，不含「12345678901」「98765478901」「王小明」 | API JSON |
| PA-29 | AC-1.6 | sitterSet | 以相同四欄重送；payout-inspect；站內通知列表 | — | 200；「收款帳號已變更」通知筆數不變 | API JSON |
| PA-30 | AC-1.6 | sitterSet | 帳號由 X' 改回 X（收件者為 `@e2e-journey.test`，正式站 Resend 測試模式必定拒收，見 §5-4） | — | 設定 API 200；站內通知「收款帳號已變更」存在；讀取 API 為 X | API JSON |
| PA-31 | AC-1.6 | sitterSet | 開啟收款帳號頁修改分行為 `南京分行` 並送出 | 修改前與送出過程無密碼欄位、無重新登入提示，直接成功 | 頁面 `input[type=password]` 數量 0；設定請求 200 | 截圖 |
| PA-32 | AC-1.7 | sitterLocked | E31 `GET /api/sitters/{sitterLocked}/booking-context` 確認鎖定；設定 API 改為 Y；讀取 | — | E31 `bookability=PAUSED`；設定 API 200；讀取為 Y 四欄 | API JSON |
| PA-33 | AC-1.8 | sitterSet | 開啟收款帳號頁 | 已設定狀態，頁面無任何刪除按鈕（含文字「刪除」的按鈕數量 0） | — | 截圖 |
| PA-34 | AC-1.8 | sitterSet | 對收款帳號資源送 DELETE；讀取 | — | DELETE 405、body `code=METHOD_NOT_ALLOWED`（SD-ORDER §6.1 全域錯誤格式：`code`、`message`）；讀取仍 `configured=true` 且四欄不變 | API JSON |

## 3. 權限與例外

> PRD-025 §7 每個 EX 的覆蓋位置。越權一律用另一個真實帳號。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| PA-17、PA-18 | EX-1 | OWNER、ADMIN 呼叫 | 403 `ROLE_MISMATCH` |
| PA-19 | EX-2 | 未登入 | 401 `UNAUTHENTICATED` |
| PAX-01 | EX-3 | 以 `sitterNew` token 呼叫讀取 API，並附上另一保母（`sitterSet`）的 userId 作為 query 參數 | 回應仍是 `sitterNew` 自己的資料（`configured=false`），不洩漏 `sitterSet` 帳號；端點不接受 sitter ID（BR-2） |
| PA-08 至 PA-12 | EX-4 | 欄位格式錯誤 | 400 `VALIDATION_FAILED`，資料不變 |
| PAX-02 | EX-5 | FREE 保母設定帳號：`sitter-setup` 建 `tier=FREE`、`payoutAccount=null` 的保母，設定 API 送 X | 200（四級皆可） |
| PA-32 | EX-6 | 訂閱鎖定 | 200 |
| PA-16、PA-27、PA-29 | EX-7 | 重複送出 | 200，不新增稽核、不發通知 |
| — | EX-8 | 樂觀鎖衝突 | 不適用（BR-7，同一使用者多裝置不處理） |
| PA-30；§4 | EX-9 | Resend 失敗 | 修改 200、站內通知存在；重試由後端測試 |
| PA-03、PA-07 | EX-10 | 未設定帳號報價 | 422 `PAYOUT_ACCOUNT_REQUIRED`、訂單 `PENDING`、不建 payment |
| PA-34 | EX-11 | DELETE | 405 `METHOD_NOT_ALLOWED` |
| PA-20 | EX-12 | 無保母 profile | 404 `NOT_FOUND`，不自動建立 |
| PA-01、PA-02 | EX-13 | 未設定時讀取 | 200 `{ "configured": false }` |
| §4 | EX-14 | 補款快照與修改同時發生 | 後端測試 |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.5「資料庫中帳號欄位的儲存值不等於明文」 | E2E 讀不到 DB 原始值 | 後端整合測試（SD-SUPPLY 撰寫時定義類別，建議 `PayoutAccountIntegrationTest`：寫入後以 JDBC 讀原始欄位，斷言不含明文 `98765478901` 且可解回四欄）；快照側由 SD-ORDER `QuoteIntegrationTest`（快照非明文、可解回四欄） |
| AC-1.1、AC-1.2、AC-1.5、AC-1.6 的稽核紀錄內容、通知 type / 不可關閉 / Email 已排入（payout-inspect 未定義前） | 無唯讀端點可讀稽核與帳號類通知（§5-2）；E2E 只驗站內通知可見與內容不含完整帳號 | 後端整合測試（SD-SUPPLY 撰寫時定義，建議 `PayoutAccountIntegrationTest`：首次設定 / 變動 / 相同值重送的稽核筆數與內容、`mandatory=true`、偏好關閉仍排入 Email） |
| BR-4 金鑰無程式內預設值、缺金鑰啟動失敗 | 部署層驗證 | SD-ORDER §8.3 D1 部署驗證（`gcloud run services describe` 只看 env 名稱含 `APP_CRYPTO_KEY`；prod 缺值啟動失敗） |
| AC-1.6 / EX-9 Resend 5xx、outbox 重試 5 次、4xx 直接失敗 | 正式站 Resend 為測試模式，只會 4xx；重試屬 SD-NOTIFY 排程 | SD-NOTIFY 投遞排程測試（類別待 SD-NOTIFY 定義）；本卡端只驗修改 200 與站內通知存在（PA-30） |
| EX-14 / BR-12 補款快照與保母修改同時發生，快照為修改前或修改後的完整四欄之一 | 無法在正式站穩定製造同時發生；且是單次讀取的實作保證 | 後端整合測試（SD-SUPPLY 撰寫時定義，建議 `PayoutAccountSnapshotConsistencyTest`：兩執行緒一邊修改一邊呼叫 `PayoutAccountQuery.getSnapshot`，斷言每次結果皆等於修改前或修改後的四欄）；SD-ORDER `QuoteIntegrationTest` 驗證快照四欄來自同一筆 |
| AC-1.1 未設定時報價 422 的完整判斷順序 | 屬 PRD-006 AC-3.8 | SD-ORDER `QuoteIntegrationTest` |
| 真實 Email 內容 | Resend 測試模式只寄專案擁有者信箱 | 手動驗證：以專案擁有者信箱註冊的保母走 PA-04、PA-28，確認信件含變動欄位與「銀行代碼 + 後五碼」、不含完整帳號與戶名 |

## 5. 已知限制 / 待補

1. **SD-SUPPLY 尚未撰寫，本 TS 無法自動化**：收款帳號的 API 路徑、request / response 契約、錯誤 payload `field` 名稱、收款帳號頁路由與 testid、稽核紀錄的資料表與讀取方式皆未定義。SD-ORDER §8.4 M11 說明現有收款帳號設定（舊 SD-007 `GET` / `PUT /api/sitter/payment-info`，欄位 `bankBranch` / `bankAccount` / `bankPayeeName`）維持為過渡實作，但其契約與 PRD-025 不符：未設定時的回應不是 `{ "configured": false }`、欄位名稱不同、無 `updatedAt`、找不到 profile 時自動建立（抽樣缺陷 #8，違反 AC-1.3）、無稽核與通知、分行 / 戶名長度上限 100 字（PRD 為 30 / 50）。本 TS 不以舊路徑為準；SD-SUPPLY 核准後由 e2e-author 對應路徑與 testid 並把自動化狀態改 ✅。
2. **payout-inspect 唯讀端點（待 SD-SUPPLY，未解決）**：讀取某保母的收款帳號稽核紀錄（操作者、時間、變動欄位、前後「銀行代碼 + 後五碼」）與帳號類通知（type、`mandatory`、Email 投遞狀態）。SD-ORDER §10.4 的訂單 inspect 以 `orderId` 為範圍，不含此項；由 SD-SUPPLY 定義（建議 `GET /api/internal/test-data/sitters/{userId}/payout-inspect`，`X-Internal-Secret`、只限 `@e2e-journey.test`）。PA-06、PA-12、PA-16、PA-25 至 PA-29 的稽核 / type / Email 斷言在此之前由 §4 後端測試承擔。
3. **未設定帳號報價後重試的冪等鍵（已回寫 PRD-025）**：E6 是冪等端點，422 業務錯誤會被記錄並回放 24 小時（SD-ORDER §3.6.2、§6.5 末）；PRD-025 AC-1.1「設定完成後同一報價可成功」只在使用新 `Idempotency-Key` 時成立。前端依 SD-ORDER §9.2 每次按送出產生新 key，故畫面路徑成立；API 直接重試同 key 會再得 422。
4. **Email 投遞（限制，未解決）**：正式站 Resend 為測試模式，寄給 `@e2e-journey.test` 一律被拒，E2E 只能斷言「已排入」。
5. **已解決（2026-11-22 依 SD-ORDER §10.4 修訂）**：`provision-account` `roles[]`（ADMIN）與 `sitterProfile=false`（無保母 profile）；cleanup 跨模組範圍已含收款帳號與其稽核紀錄；`sitter-setup` 新增 `emailPreferences`（PA-28 改用）且為 upsert（`sitterLocked`、PA-28 只覆寫單一欄位）；PRD-006 預檢阻擋時三個報價鈕全停用（PA-03 / PA-07 已改）。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依核准後修訂同步（使用者 2026-11-22 同意）：PA-06、PA-28 的 Email 斷言比照 TS-007 收斂為 `emailStatus=SUPPRESSED_TEST`（讀取方式仍待 SD-SUPPLY 定義 payout-inspect）；狀態維持 Approved | SD-ORDER §10.4 `emailStatus` 列舉；測試帳號收件者標 `SUPPRESSED_TEST` |
| 2026-11-22 | 依 SD-ORDER 核准後修訂（commit 477c75d）更新：前置資料改用 `provision-account` `roles[]` / `sitterProfile=false` / `displayName`、setup upsert 與 key 對應 id；PA-03 改為預檢阻擋時三個報價鈕全停用、以 API 驗 422，PA-07 重新開頁後報價；PA-28 改以 `sitter-setup` `emailPreferences` 關閉訂單事務 Email；稽核 / 帳號通知讀取改稱 payout-inspect，維持待 SD-SUPPLY；§5-3 冪等鍵標已回寫 PRD-025；訂單相關斷言可用 SD-ORDER inspect；§5 已解決項合併標註 | SD-ORDER §10.4 修訂；PRD-006 預檢阻擋回寫 |
| 2026-11-22 | 建立：依 PRD-025（Approved 2026-11-22）撰寫；AC-1.1–1.8 全部對應步驟（PA- 34 步，另 PAX- 2 步）；EX-1–EX-14 對應表；SD-SUPPLY 未撰寫，API / 畫面以業務名稱描述；狀態 Draft | PRD-025 自 PRD-007 拆出，收款帳號設定由舊 TS-007 TS-007-5 移入 |
| 2026-11-22 | 使用者核准：狀態 Draft → Approved | 使用者 2026-11-22 |
