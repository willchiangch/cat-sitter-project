# TS-005: 公開預約頁與送出預約

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 對應 PRD | PRD-005（Approved 2026-11-22） |
| 對應 SD | SD-ORDER（E1 `POST /api/orders`、E2、E3、E4、E5、E6、E10、E11、E14、E25、E31、C1；§6.5 錯誤碼；§9.1 `/booking/:sitterId` testid；§10.4 測試支援，含 `inspect`）；SD-FULFILL（FT1 `force-status`，只用來造熟客 / 月額度前置） |
| 類型 | 旅程（打真後端）：門禁、權限、冪等、跨角色互動、狀態轉移都要真後端驗證；純畫面互動（AC-2.1、AC-2.4）同檔以真後端資料執行 |
| 自動化路徑 | `frontend/e2e/journeys/public-booking-gating.spec.ts`（§2.1、§3 PB-E01~E04）；`frontend/e2e/journeys/public-booking-content.spec.ts`（§2.2、§3 PB-E05~E06）；`frontend/e2e/journeys/public-booking-submit.spec.ts`（§2.3） |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> PRD-005 卡首規定以「AC-x.y-情境 n」逐列對應，本 TS 的 AC 欄照此寫法。

---

## 0. 共用約定

| 項目 | 約定 |
|------|------|
| 日期記號 | `D+n` = 今天 + n 天，格式 `YYYY-MM-DD`；「今天」取 E31 回應的 `serverTime` 換算 Asia/Taipei（SD-ORDER E31），不用執行機時鐘；`D-1` = 昨天。PRD 範例的固定日期（2026-11-25 等）一律換成相對日期，期限邊界的固定時刻由後端測試（注入 `Clock`）覆蓋，見 §4 |
| 金額顯示 | 畫面金額一律為 SD-FRONTEND-SPEC §6.4.1 格式 `$ 4,600`（`$` 後為 U+00A0）；API 金額為整數 |
| 標準請求 | E1 body：`sitterId`、`groups[]`（`planId`、`dates[]`、`dailyCount`、`petIds[]`）、`answers[]`（需填問卷時）、`expectedAmount`（= 依當下單價算出的正確值），標頭 `Idempotency-Key` = 每次新產生的 UUID（除非步驟指定沿用）。下文「標準請求」指除步驟另述的欄位外全部合法 |
| 「無新訂單」的查法 | 送出前後各呼叫一次 `owner` 的 E3 `GET /api/orders/owner?tab=EVALUATING` 與保母的 E4 `GET /api/orders/sitter?tab=EVALUATING`，`totalElements` 不變 |
| 「回應一致」的比對 | 比對 `code`、`message`、`payload` 全部鍵與值，排除 `traceId`、`timestamp`（SD-ORDER §6.1） |
| null 欄位 | 回應中值為 null 的欄位不輸出（SD-ORDER §6.1）；斷言「= null」一律寫成「欄位不存在」 |
| `inspect` 查法 | `GET /api/internal/test-data/orders/{orderId}/inspect`（`X-Internal-Secret`，SD-ORDER §10.4）：`orderLogs[]`（`actionType`、`operatorRole`、`operatorUserId`、`payload`）、`notifications[]`（`type`、`recipientUserId`、`recipientRole`、`mandatory`、`emailStatus`）。下文「inspect」即指此端點；計數時排除 `TEST_TIME_SHIFTED` / `TEST_VISIT_FORCED` |
| 站內通知文案查法 | 通知類型與 Email 投遞以 inspect 斷言；標題 / 連結文字由收件者以自己的 token 呼叫站內通知清單（SD-014 §4.1 `GET /api/notifications?role=…`；SD-NOTIFY 重建後以其契約為準）核對 |
| 時間避開 | spec 開始時若 `serverTime` 換算 Asia/Taipei 在 23:50–00:10 之間則等待到 00:10 後再跑，避免 `D+n` 在執行中跨日 |

## 1. 前置資料

> 不寫死 UUID。每個 test 自己 provision、可獨立重跑。所有帳號 email 為 `{代號}-{runId}@e2e-journey.test`，以 `POST /api/internal/test-data/provision-account`（`roles[]`、`displayName`）建立後走真實登入取 token。`sitter-setup` / `owner-setup` 為 upsert（只覆寫請求中出現的欄位），請求中每個方案 / 題目 / 毛孩帶 `key`（例 `P1`、`Q1`、`A`），`planId`、`questionId`、`petId` 依回應的 `key` 對應取得（SD-ORDER §10.4）。下文「重呼叫 `sitter-setup` 改 X」= 只帶 `userId` 與 X 欄位。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `S-base` | SITTER | `sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`subscriptionExpiresAt=D+30`、`payoutAccount` 四欄齊、`plans` = P1（單價 500、適用 CAT）、P2（單價 800、適用 DOG），`questions=[]`，`emailPreferences.ORDER_AFFAIR=開` | 可預約 |
| `S-anon` | SITTER | 同 `S-base`，另加 1 題啟用中的非必填簡答 Qa | 可預約 |
| `S-kyc` | SITTER | 同 `S-base`，`kycStatus=PENDING_REVIEW`；情境 2 重呼叫改 `UNVERIFIED` | 不可預約 |
| `S-black` | SITTER | 同 `S-base`，`gatekeeper.globalBlacklist=[owner-X]` | PRO 黑名單生效 |
| `S-hidden` | SITTER | 同 `S-base`，`profileVisible=false` | 隱藏 |
| `S-ult` | SITTER | `tier=ULTIMATE`，plans = P1（白名單 `[owner-X]`）、P2（黑名單 `[owner-Y]`）、P3（無名單），單價皆 500 | — |
| `S-gwl` | SITTER | `tier=ULTIMATE`，`gatekeeper.globalWhitelist=[owner-X]`，plans = P1 | — |
| `S-lock` | SITTER | 同 `S-base`，先 `subscriptionExpiresAt=D+30` 建 1 張 `PENDING` 訂單後，重呼叫改 `subscriptionExpiresAt=D-1` | 已鎖定 |
| `S-lock-today` | SITTER | 同 `S-base`，`subscriptionExpiresAt=D+0`（今天到期，明天 00:00 才鎖） | 未鎖定 |
| `S-susp` | SITTER | 同 `S-base`（`kycStatus=VERIFIED`），`suspended=true` | ADMIN 停權 |
| `S-free` | SITTER | 同 `S-base`，`tier=FREE` | 本月 3 張 `CONFIRMED`（造法見 PB-24） |
| `S-free2` | SITTER | 同 `S-base`，`tier=FREE` | 5 張 `PENDING`、0 張 `CONFIRMED` |
| `U` | SITTER + OWNER | `provision-account`（`roles=[OWNER, SITTER]`）＋ `sitter-setup`（同 `S-base`）＋ `owner-setup`（1 隻貓 Ua）；以既有角色切換分別取得 `currentRole=OWNER` / `SITTER` 的 token | 雙角色 |
| `S-range` | SITTER | `tier=PRO`，plans = P1（單價 500，可預約區間 `D+30`~`D+33`）、P4（單價 500，可預約區間 `D+0`~`D+3`） | — |
| `S-big` | SITTER | 同 `S-base`，plans = P9（單價 6,000，CAT）、P10（單價 333,333，CAT） | — |
| `S-q{n}` | SITTER | 同 `S-base`，`questions` = Q1（必填單選，選項「是」「否」，排序 1）、Q2（必填簡答，排序 2）、Q3（非必填詳答，排序 3）、Q4（停用簡答）；AC-3.5、AC-3.6 每個情境各用一位（`S-q1`~`S-q8`，情境 5 另有 `S-q5b`），互不干擾 | — |
| `S-reg` | SITTER | 同 `S-q1` 題目組 | `owner-X` 的熟客（造法見 PB-85） |
| `T-noq` | SITTER | 同 `S-base`，`questions` = 2 題皆停用 | — |
| `S-same` | SITTER | 同 `S-base` | `D+4` 已有 3 張他人 `PENDING`、1 張他人 `CONFIRMED` |
| `S-other` | SITTER | 同 `S-base`（提供「他人的方案」） | — |
| `owner-O` | OWNER | `owner-setup`：`pets` = 貓 A、貓 B、狗 C、貓 D（`deleted=true`）；`favoriteSitterIds=[]`；`emailPreferences.ORDER_AFFAIR=開` | — |
| `owner-X`、`owner-Y`、`owner-Z` | OWNER | `owner-setup`：各 1 隻貓 | — |
| `owner-P` | OWNER | `owner-setup`：1 隻貓 Pp（「他人的毛孩」） | — |
| `owner-H` | OWNER | `owner-setup`：1 隻貓；用來替保母造 `PENDING` / `CONFIRMED` / `COMPLETED` 前置訂單 | — |
| `owner-empty` | OWNER | `owner-setup`：`pets=[]` | — |
| `owner-F` | OWNER | `owner-setup`：1 隻貓，`favoriteFillerCount=50`（回應 `fillerSitterIds` 長度 50，不含 `S-base`） | 清單已滿 |
| `admin` | ADMIN | `provision-account`（`roles=[ADMIN]`） | — |

**造 `CONFIRMED` 訂單的標準鏈（以下稱「確認鏈」）**：`owner-H` 以標準請求 E1 建單 → 保母 E6 `POST /api/orders/{id}/quote`（`expectedAmount` = `baseAmount`，不帶調整）→ `owner-H` E10 取 `paymentId` → E11 上傳 JPEG 憑證（`disclaimerAgreed=true`、`lastFive=12345`）→ 保母 E14 `verify`（`submissionId`）→ E2 `status=CONFIRMED`。

**造 `COMPLETED` 訂單（「結案鏈」）**：確認鏈 → 對每個行程 FT1 `force-status`（`status=IN_PROGRESS`）→ FT1（`status=DONE`、`careLogSubmitted=true`）→ 飼主 E25 `POST /api/orders/{id}/complete` → E2 `status=COMPLETED`。

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4 擴充後涵蓋訂單、行程、payment、submission 與 GCS 憑證）；新資料型態是否清得掉見 §5-7。

## 2. 測試步驟

> 步驟 ID 前綴 `PB-`，e2e spec 的 `step()` / `apiStep()` 標籤必須以同一 ID 開頭。

### 2.1 子模組 1：預約頁可預約判斷與門禁（`public-booking-gating.spec.ts`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| PB-01 | AC-1.1-情境 1 | 匿名 | 未登入開啟 `/booking/{S-anon}` | 出現 2 個 `public-booking-plan-card-{planId}`（P1、P2）；新增群組選 P1、`D+4`、每日 1 次後 `public-booking-estimate-amount` 顯示 `$ 500`；不出現任何 `public-booking-group-0-pet-checkbox-*` 與 `public-booking-question-*` | E31 `GET /api/sitters/{S-anon}/booking-context`（無 token）→ 200、`bookability=BOOKABLE`、`plans` 長度 2、`questionnaire` 欄位不存在、`serverTime` 有值 | 截圖 + API JSON |
| PB-02 | AC-1.1-情境 2 | 匿名 → owner-X | 承 PB-01，點 `public-booking-btn-submit` | 導向登入頁；以 `owner-X` 登入後回到 `/booking/{S-anon}`，`public-booking-group-0` 仍在且方案 P1、日期 `D+4` 保留；出現 `public-booking-group-0-pet-checkbox-{owner-X 的貓}`（已勾選）與 `public-booking-question-{Qa}` | 登入前後無 E1 請求 | 截圖 |
| PB-03 | AC-1.1-情境 3、EX-1 | 匿名 | 無 `Authorization` 標頭，以標準請求打 E1（`sitterId=S-anon`） | — | 401 `UNAUTHENTICATED`；`S-anon` 的 E4 `tab=EVALUATING` `totalElements=0` | API JSON |
| PB-04 | AC-1.2-情境 1、EX-4 | owner-X | 開啟 `/booking/{S-kyc}`（`PENDING_REVIEW`） | `public-booking-unavailable-message` =「此保母尚未完成實名認證，暫不開放預約」；`public-booking-plan-card-*` 數量 0 | E31 → `bookability=NOT_VERIFIED`、`plans=[]` | 截圖 + API JSON |
| PB-05 | AC-1.2-情境 1、EX-4 | owner-X | 以標準請求打 E1（`sitterId=S-kyc`，planId 取 `sitter-setup` 回應） | — | 403 `SITTER_NOT_BOOKABLE`、`payload.reason=NOT_VERIFIED`；無新訂單 | API JSON |
| PB-06 | AC-1.2-情境 2 | owner-X | 重呼叫 `sitter-setup` 把 `S-kyc` 改 `UNVERIFIED`，重做 PB-04、PB-05 | 同 PB-04 | 同 PB-05 | 截圖 + API JSON |
| PB-07 | AC-1.3-情境 1、EX-5 | owner-X | 開啟 `/booking/{S-black}`（PRO，`owner-X` 在全域黑名單） | `public-booking-unavailable-message` =「保母目前不開放預約」；方案卡數量 0 | E31（owner-X token）→ `bookability=UNAVAILABLE`、`plans=[]` | 截圖 + API JSON |
| PB-08 | AC-1.3-情境 1、EX-5 | owner-X | 標準請求打 E1（`sitterId=S-black`）；保存回應為 R-black | — | 403 `SITTER_NOT_BOOKABLE`、`payload.reason=UNAVAILABLE`；無新訂單 | API JSON |
| PB-09 | AC-1.3-情境 2、EX-5 | owner-Y | 開啟 `/booking/{S-hidden}` 並以標準請求打 E1；保存回應為 R-hidden | 同 PB-07 文案、方案卡數量 0 | E31 → `UNAVAILABLE`；E1 403 `SITTER_NOT_BOOKABLE`；R-hidden 與 R-black 回應一致（§0 比對規則） | 截圖 + API JSON |
| PB-10 | AC-1.3-情境 3 | owner-X | 重呼叫 `sitter-setup` 把 `S-black` 改 `tier=BASIC`（名單保留），開啟頁面並以標準請求送單 | 出現 P1、P2 方案卡，無 `public-booking-unavailable-message` | E31 → `BOOKABLE`；E1 → 201、`status=PENDING` | 截圖 + API JSON |
| PB-11 | AC-1.4-情境 1 | owner-X、owner-Y、匿名 | 三者分別開啟 `/booking/{S-ult}` | owner-X 看到 P1、P2、P3 方案卡；owner-Y 只看到 P3；匿名看到 P2、P3 | 三次 E31 的 `plans[].planId` 集合與畫面一致 | 截圖 ×3 + API JSON |
| PB-12 | AC-1.4-情境 1、EX-8 | owner-Y | 標準請求打 E1，群組 planId 分別為 P1、P2（兩次，各自新 key） | — | 兩次皆 422 `PLAN_NOT_BOOKABLE`；無新訂單 | API JSON |
| PB-13 | AC-1.4-情境 2 | 匿名、owner-Y | `sitter-setup` 把 `S-ult` 改 `tier=PRO`，匿名與 owner-Y 開啟頁面 | 匿名看到 P1、P2、P3；owner-Y 看到 P1、P3 | E31 `plans` 與畫面一致 | 截圖 ×2 + API JSON |
| PB-14 | AC-1.4-情境 3 | owner-Y | `S-ult` 改 `tier=BASIC`，owner-Y 開啟頁面 | 看到 P1、P2、P3 | E31 `plans` 長度 3 | 截圖 + API JSON |
| PB-15 | AC-1.5-情境 1、EX-5 | owner-Y、匿名 | 分別開啟 `/booking/{S-gwl}`（ULTIMATE，全域白名單 = owner-X） | 兩者 `public-booking-unavailable-message` =「保母目前不開放預約」、方案卡數量 0 | E31 兩次皆 `UNAVAILABLE`、`plans=[]` | 截圖 ×2 + API JSON |
| PB-16 | AC-1.5-情境 1、EX-5 | owner-Y | 標準請求打 E1（`sitterId=S-gwl`）；保存為 R-gwl | — | 403 `SITTER_NOT_BOOKABLE`、`reason=UNAVAILABLE`；R-gwl 與 R-black 回應一致；無新訂單 | API JSON |
| PB-17 | AC-1.5-情境 2 | owner-X | 開啟 `/booking/{S-gwl}` 並以標準請求送單 | 看到 P1 方案卡 | E31 `BOOKABLE`；E1 201 | 截圖 + API JSON |
| PB-18 | AC-1.5-情境 3 | owner-Y | `S-gwl` 改 `tier=PRO`，owner-Y 標準請求送單 | — | 201、`status=PENDING` | API JSON |
| PB-19 | AC-1.6-情境 1、EX-6 | owner-X | 開啟 `/booking/{S-lock}`（`subscriptionExpiresAt=D-1`） | 方案卡可見；`public-booking-btn-submit` 為 disabled；`public-booking-unavailable-message` =「保母目前暫停接受新預約」 | E31 → `bookability=PAUSED`、`plans` 非空 | 截圖 + API JSON |
| PB-20 | AC-1.6-情境 1、EX-6 | owner-X | 標準請求打 E1（`sitterId=S-lock`） | — | 403 `SUBSCRIPTION_LOCKED`；無新訂單；鎖定前建立的那張訂單 E2（建單飼主 token）仍 `status=PENDING` | API JSON |
| PB-21 | AC-1.6-情境 2 | owner-X | 標準請求打 E1（`sitterId=S-lock-today`，今天到期） | — | 201、`status=PENDING`（到期日當天仍可送單；00:00 邊界見 §4） | API JSON |
| PB-22 | AC-1.6-情境 3、EX-6 | owner-X | 開啟 `/booking/{S-susp}` 並以標準請求送單 | 同 PB-19（不出現「尚未完成實名認證」） | E31 `PAUSED`；E1 403 `SUBSCRIPTION_LOCKED`（不是 `SITTER_NOT_BOOKABLE`）；無新訂單 | 截圖 + API JSON |
| PB-23 | AC-1.7-情境 4 | owner-H | 對 `S-free2`（FREE、0 張 `CONFIRMED`）連續以標準請求建 5 張訂單，再送第 6 張 | — | 6 次皆 201；`S-free2` E4 `tab=EVALUATING` `totalElements=6` | API JSON |
| PB-24 | AC-1.7-情境 1 前置 | owner-H、S-free | 對 `S-free` 走 3 次確認鏈（首日 `D+4`） | — | 3 張 E2 皆 `CONFIRMED`；第 3 次 E6 未回 `MONTHLY_QUOTA_REACHED`（確認時計數 2 < 3） | API JSON |
| PB-25 | AC-1.7-情境 1、EX-7 | owner-X | 開啟 `/booking/{S-free}` | 方案卡可見；`public-booking-btn-submit` disabled；`public-booking-unavailable-message` =「保母目前暫停接受新預約」（與 PB-19 同文案） | E31 → `PAUSED` | 截圖 + API JSON |
| PB-26 | AC-1.7-情境 1、EX-7 | owner-X | 標準請求打 E1（`sitterId=S-free`） | — | 422 `MONTHLY_QUOTA_REACHED`；無新訂單 | API JSON |
| PB-27 | AC-1.7-情境 3 | owner-X | `sitter-setup` 把 `S-free` 改 `tier=BASIC`，標準請求送單 | — | 201（上限依送單當下等級 20） | API JSON |
| PB-28 | AC-1.8-情境 1、EX-3 | U（OWNER） | U 切換為 `currentRole=OWNER`，開啟 `/booking/{U}` | 方案卡可見；`public-booking-btn-submit` disabled；`public-booking-unavailable-message` =「這是您自己的預約頁，無法預約」 | E31（U OWNER token）→ `bookability=SELF` | 截圖 + API JSON |
| PB-29 | AC-1.8-情境 1、EX-3 | U（OWNER） | 以 U 的貓作 `petIds`，標準請求打 E1（`sitterId=U`） | — | 422 `SELF_BOOKING_NOT_ALLOWED`；無新訂單 | API JSON |
| PB-30 | AC-1.8-情境 2、BR-25 | U（OWNER） | `sitter-setup` 把 U 改 `subscriptionExpiresAt=D-1`、`profileVisible=false`，重做 PB-28、PB-29 | 仍顯示「這是您自己的預約頁，無法預約」（不是「保母目前不開放預約」或「暫停接受新預約」） | E31 仍 `SELF`；E1 仍 422 `SELF_BOOKING_NOT_ALLOWED` | 截圖 + API JSON |
| PB-31 | AC-1.8-情境 3、EX-2 | U（SITTER） | U 切換為 `currentRole=SITTER`，以 U 的貓作 `petIds` 對 `S-base` 打 E1 | — | 403 `ROLE_MISMATCH`；無新訂單 | API JSON |
| PB-32 | AC-1.8-情境 3、EX-2 | U（SITTER） | 以 SITTER 身分開啟 `/booking/{S-base}`，選 P1、`D+4` 後點 `public-booking-btn-submit` | 顯示「請切換為飼主身分後再預約」（SD-ORDER §9.1），未導頁 | E1 若有送出則為 403 `ROLE_MISMATCH` | 截圖 |
| PB-33 | AC-1.8-情境 4、EX-2 | admin | ADMIN token 以標準請求（任一合法 body）打 E1（`sitterId=S-base`） | — | 403 `ROLE_MISMATCH`；無新訂單 | API JSON |

### 2.2 子模組 2：預約內容（`public-booking-content.spec.ts`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| PB-34 | AC-2.1 | owner-O | 開啟 `/booking/{S-base}`；群組 0 = P1、選連續區間 `D+4`~`D+6`、每日 2 次，只勾貓 A（取消 B、C）；新增群組 1 = P2、跳選 `D+8` 與 `D+11`、每日 1 次，只勾狗 C | 出現 `public-booking-group-0`、`public-booking-group-1`；`public-booking-estimate-amount` = `$ 4,600`（3 × 2 × 500 + 2 × 1 × 800）；金額下方顯示「最終總額由保母評估後確認」 | — | 截圖 |
| PB-35 | AC-2.1 | owner-O | 群組 0 再勾選貓 B（1 隻 → 2 隻） | 試算仍為 `$ 4,600` | — | 截圖 |
| PB-36 | AC-2.1 | owner-O | 刪除群組 1 | `public-booking-group-1` 消失；試算 = `$ 3,000` | — | 截圖 |
| PB-37 | AC-2.2-情境 1、EX-9 | owner-O | 開啟日期選擇器 | `D-1` 及更早為停用；`D+0` 可選（`D` 依 E31 `serverTime`） | E31 `serverTime` 換算 Asia/Taipei 的日期 = 畫面上第一個可選日 | 截圖 + API JSON |
| PB-38 | AC-2.2-情境 1、EX-9 | owner-O | 標準請求打 E1，群組日期 = `[D-1]` | — | 422 `INVALID_VISIT_DATE`、`payload.dates` 含 `D-1`；無新訂單 | API JSON |
| PB-39 | AC-2.2-情境 2 | owner-O | 日期選擇器翻到一年後 | `D+365` 可選、`D+366` 停用 | — | 截圖 |
| PB-40 | AC-2.2-情境 2、EX-9 | owner-O | 標準請求打 E1，日期 = `[D+4, D+366]` | — | 422 `INVALID_VISIT_DATE`、`payload.dates` 含 `D+366`；無新訂單 | API JSON |
| PB-41 | AC-2.2-情境 2 | owner-O | 標準請求打 E1，日期 = `[D+4, D+365]` | — | 201、`status=PENDING` | API JSON |
| PB-42 | AC-2.3-情境 1、EX-9 | owner-O | 開啟 `/booking/{S-range}`（PRO），群組先選日期 `D+28`，再開啟方案選擇視窗 | P1（區間 `D+30`~`D+33`）顯示為不可選並附紅字提示 | E31 → P1 `bookableFrom=D+30`、`bookableTo=D+33` | 截圖 + API JSON |
| PB-43 | AC-2.3-情境 1、EX-9 | owner-O | 標準請求打 E1：P1 + `[D+28]` | — | 422 `INVALID_VISIT_DATE`、`payload.dates` 含 `D+28`；無新訂單 | API JSON |
| PB-44 | AC-2.3-情境 2 | owner-O | `sitter-setup` 把 `S-range` 改 `tier=BASIC`，再以 P1 + `[D+28]` 送單 | — | E31 → P1 的 `bookableFrom` / `bookableTo` 不存在；E1 → 201 | API JSON |
| PB-45 | AC-2.3-情境 3 | owner-O | `S-range` 改回 `tier=PRO`，以 P4（區間 `D+0`~`D+3`）+ `[D+0]` 送單 | — | 201（不檢查置前天數） | API JSON |
| PB-46 | AC-2.4-情境 1 | owner-O | 登入後開啟 `/booking/{S-base}`，新增群組 0 | 出現 `public-booking-group-0-pet-checkbox-{A}`、`-{B}`、`-{C}` 且皆為勾選；不出現 `-{D}` | — | 截圖 |
| PB-47 | AC-2.4-情境 2 | owner-O | 取消勾選 C，為群組 0 選方案 P2（適用 DOG）、`D+4`、每日 1 次 | 出現 `public-booking-pet-type-warning`「您沒有適合此方案的寵物，請先至個人檔案新增或選擇其他方案」 | — | 截圖 |
| PB-48 | AC-2.4-情境 2 | owner-O | 關閉提示後點 `public-booking-btn-submit` | 導向 `/owner/orders/{orderId}` | E1 201；E2 `groups[0].pets[].petId` = {A, B} | 截圖 + API JSON |
| PB-49 | AC-2.4-情境 3、EX-27 | owner-empty | 開啟 `/booking/{S-base}`，選 P1、`D+4` | `public-booking-btn-submit` disabled；`public-booking-no-pet-message` =「您尚未新增毛孩，請先至個人檔案新增」 | — | 截圖 |
| PB-50 | AC-2.6-情境 2 | owner-O | 標準請求打 E1：10 個群組皆為 P1，每組 6 個互不重疊的日期（`D+1`~`D+60`），每日 3 次，`petIds=[A]`，`expectedAmount=90000` | — | 201、`baseAmount=90000`；E2 `visits` 長度 180 | API JSON |
| PB-51 | AC-2.6-情境 3 | owner-O | 標準請求：群組 0 = P1 `[D+10]`、群組 1 = P2 `[D+10]` | — | 201 | API JSON |
| PB-52 | AC-2.7、EX-17 | owner-O | 前置：`owner-H` 對 `S-same` 以 `[D+4]` 建 3 張 `PENDING`，並走 1 次確認鏈（`[D+4]`）；owner-O 以 `[D+4]` 標準請求送單 | — | 201、`status=PENDING`；`S-same` E4 `tab=EVALUATING` `totalElements=4`、`tab=ACTIVE` `totalElements=1` | API JSON |
| PB-53 | AC-2.8-情境 1、EX-8 | owner-O | 開啟 `/booking/{S-base}` 選 P1、`D+4`；保母以方案管理（PRD-003 方案停用）把 P1 停用；owner-O 點送出 | 顯示「此方案目前無法預約，請重新整理後再選擇」 | E1 422 `PLAN_NOT_BOOKABLE`；無新訂單 | 截圖 + API JSON |
| PB-54 | AC-2.8-情境 2、EX-8 | owner-O | 標準請求打 E1（`sitterId=S-base`），planId = `S-other` 的 P1；保存為 R-other | — | 422 `PLAN_NOT_BOOKABLE` | API JSON |
| PB-55 | AC-2.8-情境 3、EX-8 | owner-O | 同 PB-54，planId = 隨機產生的 UUID；保存為 R-random | — | 422 `PLAN_NOT_BOOKABLE`（不是 404）；R-random 與 R-other 回應一致 | API JSON |

### 2.3 子模組 3：事前問卷與送出預約（`public-booking-submit.spec.ts`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| PB-56 | AC-3.1 | owner-O | 開啟 `/booking/{S-base}`（無題目）；群組 0 = P1、`D+4`~`D+6`、每日 2 次，勾 A、B（取消 C）；群組 1 = P2、`D+8`、`D+11`、每日 1 次，只勾 C；點 `public-booking-btn-submit` | 不出現 `public-booking-question-*`；導向 `/owner/orders/{orderId}`（評估中） | 頁面發出的 E1 恰 1 次，body `expectedAmount=4600`、不含 `ownerId`；回應 201：`orderId` 有值、`status=PENDING`、`baseAmount=4600`、`reviewExpiresAt` 有值、`questionnaireStatus=NOT_REQUIRED` | 截圖 + API JSON |
| PB-57 | AC-3.1 | owner-O | 呼叫 E2 `GET /api/orders/{orderId}` | — | `questionnaireExemptReason=NO_ACTIVE_QUESTIONS`；`groups` 長度 2：群組 P1 `dailyCount=2`、`pets`={A, B}，群組 P2 `dailyCount=1`、`pets`={C}；`visits` 長度 8 且全為 `PENDING`：`D+4`、`D+5`、`D+6` 各 2 個，`D+8`、`D+11` 各 1 個；inspect `orderLogs` 恰 1 筆 `ORDER_CREATED`、`operatorRole=OWNER`、`operatorUserId` = owner-O、payload `baseAmount=4600`、`groupCount=2`、`visitCount=8`、`questionnaireStatus=NOT_REQUIRED`、`questionnaireExemptReason=NO_ACTIVE_QUESTIONS`；inspect `fulfill.visits` 8 筆皆 `PENDING` | API JSON |
| PB-58 | AC-3.2-情境 1 | owner-O | 選 P1、首日 `D+4`，進入送出前確認區 | `public-booking-review-expires-at` =「保母需於 {D+4 以 YYYY/MM/DD 表示} 00:00 前回覆，逾時將自動取消」；不出現 `public-booking-urgent-hint` | — | 截圖 |
| PB-59 | AC-3.2-情境 1 | owner-O | 送出 | — | 201、`reviewExpiresAt` = `D+3` 的 `16:00:00Z`（= `D+4` 00:00 Asia/Taipei）；E2 `reviewExpiresAt` 相同 | API JSON |
| PB-60 | AC-3.2-情境 3 | owner-O | 選 P1、首日 `D+0`，進入確認區；記錄送出前時間 t0，送出，記錄收到回應時間 t1 | 出現 `public-booking-urgent-hint`「距服務日很近，保母可能來不及回覆」；`public-booking-review-expires-at` 顯示的時刻在 t0 + 2h 前後 1 分鐘內 | 201；`reviewExpiresAt` 介於 t0 + 2h 與 t1 + 2h 之間（緊急單 = 送單時間 + 2h） | 截圖 + API JSON |
| PB-61 | AC-3.3-情境 1、EX-10 | owner-O | 開啟 `/booking/{S-base}`，群組 = P1、`D+4`~`D+6`、每日 2 次（試算 3,000）；保母以方案管理（PRD-003 方案編輯）把 P1 單價改 600；owner-O 點送出 | 顯示「方案價格已更新，請確認新金額後再送出」；`public-booking-estimate-amount` 更新為 `$ 3,600` | E1（`expectedAmount=3000`）→ 409 `PRICE_CHANGED`、`payload.baseAmount=3600`；無新訂單 | 截圖 + API JSON |
| PB-62 | AC-3.3-情境 1 | owner-O | 再次點送出（前端產生新 key） | 導向訂單詳情 | E1（`expectedAmount=3600`）→ 201、`baseAmount=3600` | 截圖 + API JSON |
| PB-63 | AC-3.3-情境 2、EX-10 | owner-O | 單價未變，以標準請求但 `expectedAmount=1` 打 E1 | — | 409 `PRICE_CHANGED`、`payload.baseAmount` = 正確值；無新訂單 | API JSON |
| PB-64 | AC-3.3-情境 3、EX-29 | owner-O | 開啟 `/booking/{S-big}`，群組 = P9、`D+1`~`D+60` 連續區間、每日 3 次 | `public-booking-estimate-amount` = `$ 1,080,000`；`public-booking-btn-submit` disabled；`public-booking-amount-limit-message` =「單筆預約金額不可超過 $ 999,999，請拆成多筆預約」（`$` 後為 U+00A0，SD-FRONTEND-SPEC §6.4.1） | — | 截圖 |
| PB-65 | AC-3.3-情境 3、EX-29 | owner-O | 同內容直接打 E1（`expectedAmount=1080000`） | — | 400 `VALIDATION_FAILED`、`payload.field=baseAmount`、`payload.max=999999`；無新訂單 | API JSON |
| PB-66 | AC-3.3-情境 4 | owner-O | 標準請求：P10、`[D+4]`、每日 3 次、`expectedAmount=999999` | — | 201、`baseAmount=999999` | API JSON |
| PB-67 | AC-3.4-情境 1、EX-11 | owner-O | 以 key K 標準請求對 `S-base` 送單得 `orderId=A`；以 K 與完全相同 body 重送 | — | 第二次 201，body 與第一次完全相同（`orderId=A`）；owner-O E3 `tab=EVALUATING` `totalElements` 只增加 1；訂單 A 的 inspect：`orderLogs` 中 `ORDER_CREATED` 仍恰 1 筆、`notifications` 中 `type=NEW_BOOKING_REQUEST` 仍恰 1 則 | API JSON |
| PB-68 | AC-3.4-情境 2、EX-12 | owner-O | 承 PB-67，以 K 送三個變體：(a) 日期改 `D+5`；(b) `petIds` 改 B；(c) body 其他不變、另加 `answers=[{questionId: S-q1 的 Q1, value: "是"}]`（request hash 含問卷答案，BR-15） | — | 三次皆 422 `IDEMPOTENCY_KEY_REUSED`；訂單數不變 | API JSON |
| PB-69 | AC-3.4-情境 4、EX-14 | owner-O | 不帶 `Idempotency-Key` 以標準請求打 E1 | — | 400 `VALIDATION_FAILED`、`payload.field=Idempotency-Key`；無新訂單 | API JSON |
| PB-70 | AC-3.5 | owner-O | 開啟 `/booking/{S-q1}`，選 P1、`D+4` | 依序出現 `public-booking-question-{Q1}`、`-{Q2}`、`-{Q3}`，無 `-{Q4}`；Q1、Q2 標「必填」；Q1、Q2 未答時 `public-booking-btn-submit` disabled | E31（owner-O token）→ `questionnaire.required=true`、`questions` 依 `sortOrder` 為 Q1、Q2、Q3 | 截圖 + API JSON |
| PB-71 | AC-3.5 | owner-O | Q1 選「是」、Q2 填「每天餵兩次」、Q3 留空，送出 | 導向訂單詳情 | 201、`questionnaireStatus=COMPLETED`；E2 無 `questionnaireExemptReason` 欄位 | 截圖 + API JSON |
| PB-72 | AC-3.5 | S-q1 | 呼叫 E5 `GET /api/orders/{orderId}/evaluation` | — | `questionnaire.answers` 長度 3：Q1（`questionText` = 原題目文字、`questionType` = 單選、`answer`「是」）、Q2（簡答、「每天餵兩次」）、Q3（詳答、空答案） | API JSON |
| PB-73 | AC-3.5 | S-q1 | 保母以問卷管理（PRD-004）修改 Q1 題目文字、停用 Q2；再呼叫 E5 | — | `answers` 與 PB-72 完全相同（題目文字仍為送出時版本，Q2 仍在） | API JSON |
| PB-74 | AC-3.6-情境 1、EX-25 | owner-O | 對 `S-q2` 打 E1，`answers` 只含 Q1「是」 | — | 409 `QUESTIONNAIRE_CHANGED`；`payload.questions[].questionId` = {Q1, Q2, Q3}，每題含 `text`、`type`、`options`、`required`、`sortOrder`；無新訂單 | API JSON |
| PB-75 | AC-3.6-情境 2、EX-25 | owner-O | 對 `S-q2` 打 E1，Q1「是」、Q2 =「   」（只有空白） | — | 409 `QUESTIONNAIRE_CHANGED`；無新訂單 | API JSON |
| PB-76 | AC-3.6-情境 3、EX-25 | owner-O | 開啟 `/booking/{S-q3}` 填 Q1「是」、Q2「每天餵兩次」、Q3「備註」；保母以問卷管理新增必填簡答 Q5；owner-O 送出 | 顯示「保母的問卷已更新，請確認後再送出」；問卷重新載入出現 `public-booking-question-{Q5}`，Q1–Q3 已填答案保留 | E1 409 `QUESTIONNAIRE_CHANGED`、`payload.questions` 含 Q5 | 截圖 + API JSON |
| PB-77 | AC-3.6-情境 3 | owner-O | 補答 Q5「好」後送出 | 導向訂單詳情 | 201、`questionnaireStatus=COMPLETED` | 截圖 + API JSON |
| PB-78 | AC-3.6-情境 4、EX-28 | owner-O | 開啟 `/booking/{S-q4}` 填 Q1、Q2、Q3；保母停用 Q2；owner-O 送出 | 導向訂單詳情 | 201；`S-q4` 的 E5 `answers` 只有 Q1、Q3（無 Q2） | 截圖 + API JSON |
| PB-79 | AC-3.6-情境 5、EX-25 | owner-O | 開啟 `/booking/{S-q5}`，Q1 選「否」、填 Q2；保母刪除 Q1 的「否」選項；owner-O 送出 | 顯示「保母的問卷已更新，請確認後再送出」 | E1 409 `QUESTIONNAIRE_CHANGED`（不是 400）；無新訂單 | 截圖 + API JSON |
| PB-80 | AC-3.6-情境 5、EX-25 | owner-O | 對 `S-q5b`（同題目組）以 Q1「是」直接組好請求；保母把 Q1 改為簡答題型；送出原請求 | — | 409 `QUESTIONNAIRE_CHANGED` | API JSON |
| PB-81 | AC-3.6-情境 6 | owner-O | 開啟 `/booking/{S-q6}` 填 Q1、Q2；保母只修改 Q1 題目文字為新文字；owner-O 送出 | 導向訂單詳情 | 201；E5 Q1 的 `questionText` = 新文字 | 截圖 + API JSON |
| PB-82 | AC-3.6-情境 7、EX-26 | owner-O | 對 `S-q7` 打 E1，Q2 = 前後各 2 個空白 + 201 個中文字 | — | 400 `VALIDATION_FAILED`、`payload.questionId=Q2`；無新訂單 | API JSON |
| PB-83 | AC-3.6-情境 7 | owner-O | 同上，Q2 = 前後各 2 個空白 + 200 個中文字（新 key） | — | 201 | API JSON |
| PB-84 | AC-3.6-情境 8、EX-14 | owner-O | 對 `S-q8` 打 E1，`answers` 中 Q1 出現兩次（「是」「是」） | — | 400 `VALIDATION_FAILED`、`payload.questionId=Q1`；無新訂單 | API JSON |
| PB-85 | AC-3.7-情境 1 前置 | owner-X、S-reg | `owner-X` 對 `S-reg` 走結案鏈（建單時需作答 Q1、Q2） | — | 該訂單 E2 `status=COMPLETED` | API JSON |
| PB-86 | AC-3.7-情境 1 | owner-X | 開啟 `/booking/{S-reg}`，選 P1、`D+4` | 不出現 `public-booking-question-*` | E31 → `questionnaire.required=false`、`questionnaire.exemptReason=REGULAR_OWNER` | 截圖 + API JSON |
| PB-87 | AC-3.7-情境 1 | owner-X | 標準請求打 E1，額外帶 `answers`（Q1「是」、Q2「x」） | — | 201、`questionnaireStatus=NOT_REQUIRED`；E2 `questionnaireExemptReason=REGULAR_OWNER`；`S-reg` 的 E5 `questionnaire.answers` 為空陣列（帶來的答案不存） | API JSON |
| PB-88 | AC-3.7-情境 2 | owner-Y | 開啟 `/booking/{T-noq}` 並以標準請求送單 | 不出現問卷 | 201、`NOT_REQUIRED`；E2 `questionnaireExemptReason=NO_ACTIVE_QUESTIONS` | 截圖 + API JSON |
| PB-89 | AC-3.7-情境 3 | owner-X | 前置：`owner-X` 對 `T-noq` 走結案鏈；再以標準請求對 `T-noq` 送單 | — | 201、`NOT_REQUIRED`；E2 `questionnaireExemptReason=REGULAR_OWNER` | API JSON |
| PB-90 | AC-3.7-情境 4 | owner-Z | 前置：`owner-Z` 對 `S-reg` 建單後以 E8 撤回（`expectedStatus=PENDING`），E2 `status=CANCELLED`；`owner-Z` 開啟 `/booking/{S-reg}` | 出現 Q1、Q2、Q3 問卷 | — | 截圖 |
| PB-91 | AC-3.7-情境 4 | owner-Z | 作答 Q1、Q2 後送出 | 導向訂單詳情 | 201、`questionnaireStatus=COMPLETED`；E2 無 `questionnaireExemptReason` 欄位 | 截圖 + API JSON |
| PB-92 | AC-3.8-情境 1、BR-14 | owner-O、S-base | 確認 `GET /api/owner/favorites`（SD-019）不含 `S-base`；`S-base` 的 `emailPreferences.ORDER_AFFAIR=開`（`sitter-setup`）；以 UI 送單 | 導向訂單詳情 | 201；owner-O 我的最愛含 `S-base`；inspect `notifications` 恰 1 則：`type=NEW_BOOKING_REQUEST`、`recipientUserId=S-base`、`recipientRole=SITTER`、`mandatory=false`、`emailStatus` 為已排入投遞（非「不寄送」）；`S-base` 站內通知清單該則標題「新預約申請」、`linkUrl=/sitter/orders/{orderId}`；`S-base` 無任何「被收藏」類通知 | 截圖 + API JSON |
| PB-93 | AC-3.8-情境 2 | owner-X、S-base | 重呼叫 `sitter-setup` 改 `emailPreferences.ORDER_AFFAIR=關`，owner-X 標準請求送單 | — | 201；inspect `notifications` 恰 1 則 `NEW_BOOKING_REQUEST`（收件者 `S-base`），`emailStatus` 為不寄送（無 Email 投遞）。步驟結束改回開 | API JSON |
| PB-94 | AC-3.8-情境 3、EX-18 | owner-O | 承 PB-92，再對 `S-base` 送第二張單 | — | 201；owner-O 我的最愛中 `S-base` 仍只出現 1 次 | API JSON |
| PB-95 | AC-3.8-情境 4、EX-18 | owner-F | `owner-F` 我的最愛已 50 位（`favoriteFillerCount=50`）且不含 `S-base`，以 UI 對 `S-base` 送單 | 導向訂單詳情，無任何「清單已滿」提示 | 201；我的最愛仍 50 位、不含 `S-base` | 截圖 + API JSON |

## 3. 權限與例外

> 已併入 §2 的 EX：EX-1（PB-03）、EX-2（PB-31~33）、EX-3（PB-28~30）、EX-4（PB-04~06）、EX-5（PB-07~09、PB-15~16）、EX-6（PB-19~22）、EX-7（PB-25~26）、EX-8（PB-12、PB-53~55）、EX-9（PB-38、PB-40、PB-43）、EX-10（PB-61、PB-63）、EX-11（PB-67）、EX-12（PB-68）、EX-17（PB-52）、EX-18（PB-94~95）、EX-25（PB-74~80）、EX-26（PB-82）、EX-27（PB-49、PB-E06）、EX-28（PB-78）、EX-29（PB-64~65）。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| PB-E01 | EX-15 | owner-O 以標準請求打 E1，`sitterId` = 隨機產生的 UUID | 404 `NOT_FOUND`；owner-O 訂單數不變 |
| PB-E02 | EX-15 | owner-O 開啟 `/booking/{隨機 UUID}` | E31 404 `NOT_FOUND`；畫面顯示「找不到此保母」 |
| PB-E03 | AC-1.3、EX-5 | 在 PB-10 降級之前執行：owner-X（在 PRO 保母 `S-black` 全域黑名單內）以自己的 token、body 多帶 `ownerId=owner-Y` 打 E1 | 403 `SITTER_NOT_BOOKABLE`、`reason=UNAVAILABLE`（身分取自 token，`ownerId` 被忽略）；owner-Y 名下無新訂單 |
| PB-E04 | AC-1.8-情境 3 | 保母 `S-base` 以 `currentRole=SITTER` 打 E1（對 `S-other`） | 403 `ROLE_MISMATCH` |
| PB-E05 | AC-2.5-情境 1、EX-22 | owner-O 標準請求，2 個群組，`groups[1].petIds=[]` | 400 `VALIDATION_FAILED`、`payload.field=petIds`、`payload.groupIndex=1`；無新訂單 |
| PB-E06 | AC-2.5-情境 1、EX-27 | owner-empty 直接打 E1，`groups[0].petIds=[]` | 400 `VALIDATION_FAILED`、`field=petIds`、`groupIndex=0` |
| PB-E07 | AC-2.5-情境 2、EX-23 | owner-O 標準請求，`petIds=[A, Pp]`（Pp 屬 owner-P） | 403 `FORBIDDEN`；無新訂單 |
| PB-E08 | AC-2.5-情境 3、EX-24 | owner-O 標準請求，`petIds=[D]`（已刪除） | 404 `NOT_FOUND`；無新訂單 |
| PB-E09 | AC-2.5-情境 3、EX-24 | owner-O 標準請求，`petIds=[隨機 UUID]` | 404 `NOT_FOUND`；無新訂單 |
| PB-E10 | AC-2.5-情境 4、BR-25 | owner-O 標準請求，`groups[0].petIds=[Pp]`、`groups[1].petIds=[]` | 403 `FORBIDDEN`（403 先於 400）；無新訂單 |
| PB-E11 | AC-2.6-情境 1、EX-14 | owner-O 標準請求逐一改為：`groups=[]`；`groups[0].dates=[]`；`dailyCount=0`；`dailyCount=4`；11 個群組；日期總數 61（10 組，9 組各 6 個日期、1 組 7 個日期，互不重疊）；P1 的 `D+20` 同時出現在群組 0 與群組 1（各子案新 key） | 每一子案皆 400 `VALIDATION_FAILED`，`payload.field` 指向對應欄位（`groups` / `dates` / `dailyCount` / `groups` / `dates` / `dates`）；無新訂單 |
| PB-E12 | EX-14、BR-18 | owner-O 標準請求，日期字串為 `2026/13/40` | 400 `VALIDATION_FAILED`；無新訂單 |
| PB-E13 | BR-25 | owner-O 對 `S-lock`（鎖定）以 `expectedAmount=1` 送單 | 409 `PRICE_CHANGED`（業務規則先於最後一步 `SUBSCRIPTION_LOCKED`） |
| PB-E14 | BR-25 | owner-O 對 `S-kyc`（`PENDING_REVIEW`）送單，`petIds=[Pp]` | 403 `FORBIDDEN`（歸屬檢查先於業務規則 `SITTER_NOT_BOOKABLE`） |
| PB-E15 | BR-25（401 先於冪等回放） | 承 PB-67，以同一 key K 與相同 body、但不帶 `Authorization` 重送 E1 | 401 `UNAUTHENTICATED`（不回放 201）；訂單 A 的 inspect `ORDER_CREATED` 仍 1 筆 |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.6-情境 1、2 的 00:00 邊界（到期日隔天 00:00 Asia/Taipei 剛好鎖定、前 1 分鐘仍可送） | 正式站無法注入時間；E2E 只驗「昨天到期已鎖」「今天到期未鎖」（PB-19~21） | `BookingIntegrationTest`（注入 `Clock`）；`BookingContextIntegrationTest` |
| AC-1.7-情境 1「其中 1 張之後被取消」與月底 23:59 | 取消 `CONFIRMED` 訂單需走完整變更協商退款鏈，且月份邊界需控時間 | `BookingIntegrationTest` |
| AC-1.7-情境 2（12/01 00:00 跨月重置） | 需控時間 | `BookingIntegrationTest` |
| AC-2.2-情境 1 的 UTC / Asia/Taipei 錯開時刻（00:30 台北 = 前一日 UTC） | 需控時間；E2E 以相對日期驗上下限（PB-37~41） | `BookingIntegrationTest` |
| AC-3.2-情境 2（首日 = 明天、送單 23:00 的緊急單）與情境 1、3 的固定時刻 | 需控時間；E2E 以「首日 = `D+4`」與「首日 = 今天」驗兩種公式（PB-58~60） | `BookingIntegrationTest`（注入 `Clock`） |
| AC-3.4-情境 3、EX-13（同 key 第一次仍在處理 → 409 `IDEMPOTENCY_IN_PROGRESS`；前端自動重試） | 正式站無法穩定製造處理中視窗 | `BookingIdempotencyIntegrationTest`（SD-ORDER §10.2 全部） |
| AC-3.7-情境 4 的「舊門禁 `NO_QUESTIONNAIRE` 規則無效」 | `sitter-setup` 的 `gatekeeper` 只能造黑 / 白名單，造不出舊規則資料；E2E 只驗「只有 `CANCELLED` 訂單不算熟客」（PB-90~91） | `BookingQuestionnaireIntegrationTest` |
| AC-3.8-情境 5、EX-16（Resend 回 5xx，投遞紀錄待重試） | 正式站無法注入 Resend 故障；Resend 為測試模式只寄專案擁有者信箱 | `BookingSideEffectIntegrationTest`（Resend mock）；真實寄信內容手動驗證 |
| BR-14「加入我的最愛失敗不回滾訂單」 | 正式站無法注入故障 | `BookingSideEffectIntegrationTest` |
| BR-16「`order_logs`、通知 enqueue 與建單同一交易」 | 需注入交易失敗 | `OrderLogWriterTest`；SD-ORDER §10.1「交易失敗」 |
| EX-19（狀態不允許的轉移）、EX-20（列鎖 / 樂觀鎖）、EX-21（GCS 失敗） | PRD 標為不適用：O1 無前置主狀態、不修改既有訂單、不上傳檔案 | 不需測試 |

## 5. 已知限制 / 待補

未解決：

1. **方案停用 / 改價、題目新增 / 停用 / 改選項 / 改題型（PB-53、61、73、76、78~81）** 透過保母端既有的方案管理（PRD-003）與問卷管理（PRD-004）API 執行；SD-SUPPLY 尚未重建，路徑由 e2e-author 依現行 API 對應，不在本 TS 寫死。（`sitter-setup` upsert 可替代改價 / 停用，但會繞過保母端真實 API；兩者擇一由 e2e-author 決定，建議用真實 API 以驗證保母端改動對預約頁的影響。）
2. **inspect `notifications[].emailStatus` 的值域**由 SD-NOTIFY 定義（SD-NOTIFY 尚未重建）；PB-92 / PB-93 以「已排入投遞」/「不寄送」描述，e2e-author 依 SD-NOTIFY 實際值對應。站內通知標題 / 連結仍用 SD-014 舊版清單 API 核對。
3. **月額度步驟（PB-24~27）在月底最後一天 23:00 後執行可能跨月**：spec 依 E31 `serverTime` 判斷，在 Asia/Taipei 月底最後一天 23:00 後跳過該組並標 skip 原因。
4. **PB-E03**：PRD-005 沒有直接定義 body 帶 `ownerId` 的行為；依 SD-ORDER §6.1「body 不收代表呼叫者的 `ownerId`」與 baseline §1.2「身分取自 token」驗證，屬安全迴歸。

已解決（2026-11-22 SD-ORDER §10.4 修訂）：讀 `order_logs` / 通知 / Email 投遞狀態 → inspect；`sitter-setup` / `owner-setup` upsert 與以 `key` 回傳 id；雙角色帳號與 ADMIN 帳號 → `provision-account roles[]`；我的最愛造滿 50 位 → `favoriteFillerCount`；保母 Email 偏好 → `sitter-setup.emailPreferences`；cleanup 跨模組範圍 → §10.4「Cleanup 跨模組範圍」；日期基準 → E31 `serverTime`；金額上限提示文案 → PRD-005 EX-29 / §4.2 步驟 5 已回寫為 `$ 999,999` 格式（PB-64 照現行字面）。本 TS 不呼叫任何排程端點（結案鏈只用 FT1 與 E25）。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

> 舊版 TS-005（SD-005 時期，依舊端點 `POST /api/orders/booking`）的 bug 紀錄保留在 git 歷史（commit `7d47d3b` 及之前版本），舊端點已由 SD-ORDER E1 取代，不再沿用。

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依 commit 477c75d 的核准後修訂更新：`order_logs` / 通知 / Email 投遞斷言改用 inspect（PB-57、67、92、93），移出 §4；前置改用 `provision-account roles[]`（U、admin）、`sitter-setup` / `owner-setup` upsert 與 `key` 對應、`sitter-setup.emailPreferences`、`favoriteFillerCount`（PB-95）；日期基準改 E31 `serverTime`（§0、PB-01、PB-37）；畫面金額改 `$ 4,600` 格式；新增 PB-E15（401 先於冪等回放，PRD-005 已回寫）；PB-64 金額上限提示改照 PRD-005 現行字面 `$ 999,999`；§5 移除已解決項。狀態維持 Draft | SD-ORDER §10.4、E31 修訂；PRD-005 回寫 |
| 2026-11-22 | 依 Approved 的 PRD-005（24 條 AC、29 條 EX）與 SD-ORDER 全文重寫：改用新範本；步驟前綴 `PB-`；旅程型三支 spec；`order_logs` / Email 投遞紀錄斷言列待測試支援；時間邊界交後端測試。狀態 Draft | 全面重建（PRD-005、SD-ORDER 於 2026-11-22 核准） |
| 2026-11-22 | 使用者核准：狀態 Draft → Approved | 使用者 2026-11-22 |
