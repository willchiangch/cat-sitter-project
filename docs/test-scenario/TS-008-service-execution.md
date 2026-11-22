# TS-008: 行程生命週期：開始、結束、系統關閉與行程檢視

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-008（Approved 2026-11-22，含 §4.6 步驟 3、§4.7 步驟 2 核准後回寫）；前置資料另經 PRD-005 / 006 / 007（建單、報價、付款）、PRD-016 / 023 / 024（變更、款項爭議）、PRD-009（O11 結案）、PRD-020（E29 調解） |
| 對應 SD | SD-FULFILL（F1–F7、F12、F13、FC1、FT1、FT2、§2.3.3 / §10.2 inspect 的 `fulfill` 區塊、§6.5 錯誤碼、§9.1 testid 與文案）；SD-ORDER（E1、E2、E6、E11、E14、E17、E19、E20、E22–E25、E27、E29、C7、§10.4 provision-account / sitter-setup / owner-setup / inspect / cleanup） |
| 類型 | 旅程（打真後端，正式站） |
| 自動化路徑 | `frontend/e2e/journeys/visit-lifecycle.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD-008、SD-FULFILL、SD-ORDER、DOMAIN-BASELINE 撰寫，未參考程式碼。
> 類型：行程狀態轉移、O9、排程結果、`VISIT_STATE_CONFLICT` 文案、權限都要真後端 → 全部旅程型。正式站無法注入 `Clock`，「時間點」一律以 FT1 / FT2 造資料並以相對時間斷言；分鐘級邊界（`now = 到期時間`）交後端整合測試（§4）。排程一律帶 `orderId` 只處理本測試訂單，正式自動排程不處理測試帳號訂單（SD-FULFILL §6.3 FC1、SD-ORDER §10.4），所以計數可精確斷言。

---

## 1. 前置資料

> 不寫死 UUID：所有 id 取自建立回應（`sitter-setup` / `owner-setup` 以請求 `key` 回傳 `planId` / `petId`）；「不存在的 id」於執行時以 `crypto.randomUUID()` 產生。每個 `test()` 自建帳號與訂單、可獨立重跑。

### 1.1 記號

| 記號 | 意義 |
|------|------|
| `D0`、`D+n`、`D-n` | 測試開始時以 E2 回應 `serverTime` 換算的 Asia/Taipei 今天，及其前後 n 天（`YYYY-MM-DD`） |
| `[t0, t1]` | 該操作前、後各取一次 E2 `serverTime`；時間欄位斷言「介於 `[t0, t1]`」 |
| `TPE(x)` | 把 ISO 時間 x 轉成 Asia/Taipei `YYYY-MM-DD HH:mm`（畫面比對用） |
| `ZA(d)` | `VISIT_ZOMBIE_AT` = d 日 00:00（Asia/Taipei）+ 72h，換成 UTC ISO（例 `ZA(D-3)` = `D-1` 16:00Z） |
| `新 key` | 執行時產生的新 `Idempotency-Key`（UUID 字串） |
| `[INS]` | 以 inspect（§1.2）讀該訂單：`orderLogs`、`notifications`（`type`、`recipientRole`、`mandatory`、`emailStatus`）、`completionBasisAt`、`fulfill.visits` / `fulfill.checklistItems`。「log 新增 n 列」= 操作前後 `orderLogs` 中該 `actionType` 的列數差 |
| `[NOTI]` | 通知文案斷言，以收件者帳號呼叫現行站內通知列表 API（SD-NOTIFY 未重建，沿用 TS-JOURNEY-02 的 `GET /api/notifications?role=…`）比對內容與連結；筆數、類型、Email 投遞以 [INS] `notifications` 為準 |

### 1.2 端點代號（路徑取自 SD）

| 代號 | 端點 | 出處 |
|------|------|------|
| F1 | `GET /api/orders/{orderId}/visits` | SD-FULFILL §6.3 |
| F2 | `GET /api/orders/{orderId}/visits/{visitId}` | 同上 |
| F3 / F4 / F5 | `POST …/start`、`POST …/complete`、`PUT …/name`（`…` = `/api/orders/{orderId}/visits/{visitId}`） | 同上 |
| F6 / F7 | `GET /api/visits/sitter/dashboard`、`GET /api/visits/owner/today` | 同上 |
| F12 | `PUT …/checklist/items/{itemId}/check`（只用於 V-17 造「全部已打勾」） | 同上 |
| F13 | `GET …/care-log`（只用於比對 `visitName`） | 同上 |
| FC1 | `POST /api/internal/cron/visits/in-progress-timeout?orderId={orderId}`（`X-Internal-Secret`） | SD-FULFILL §6.3 |
| C7 | `POST /api/internal/cron/visits/zombie?orderId={orderId}`（`X-Internal-Secret`） | SD-ORDER §8.1、§10.4 |
| FT1 | `POST /api/internal/test-data/visits/{visitId}/force-status`（`IN_PROGRESS` / `DONE` / `CLOSED_BY_SYSTEM` / `CANCELLED`） | SD-FULFILL §6.3 |
| FT2 | `POST /api/internal/test-data/visits/{visitId}/shift-time`（`SERVICE_DATE` 以 `days` 整群組平移；`STARTED_AT` / `COMPLETED_AT` 以 `minutes`） | 同上 |
| inspect | `GET /api/internal/test-data/orders/{orderId}/inspect`（`X-Internal-Secret`） | SD-ORDER §10.4 |
| E1 / E2 / E6 / E11 / E14 | 建單、訂單詳情、報價、上傳付款憑證、核對收款 | SD-ORDER §6.2 |
| E17 / E19 / E20 / E22 / E23 / E24 | 發起變更、查變更、變更報價、撤回變更、同意變更、款項爭議 | SD-ORDER §6.2 |
| E25 / E27 / E29 | 飼主確認結案（O11）、ADMIN 訂單詳情、ADMIN 調解 | SD-ORDER §6.2 |

### 1.3 帳號

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `sitter` | SITTER | `provision-account`（`@e2e-journey.test`，`roles=[SITTER]`）+ `sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄、`emailPreferences.ORDER_AFFAIR=true`、`plans` = `key=P1`「到府餵食」單價 500（`defaultTasks` = 餵乾糧、換貓砂、清水碗）、`key=P2`「陪玩」單價 300（`defaultTasks` = []），兩方案 `active=true`、可預約區間涵蓋 `D0`–`D+30` | 每個 test 新建 |
| `owner` | OWNER | `provision-account`（`roles=[OWNER]`）+ `owner-setup`：`pets` = `key=PET1` 咪咪（CAT）、`emailPreferences.ORDER_AFFAIR=true` | 每個 test 新建 |
| `sitterB` | SITTER | 同 `sitter`（自己的方案） | 越權用 |
| `ownerB` | OWNER | 同 `owner` | 越權用 |
| `admin` | ADMIN | `provision-account`（`roles=[ADMIN]`） | — |
| `dual` / `dual-as-owner` | OWNER + SITTER | `provision-account`（`roles=[OWNER, SITTER]`）+ `sitter-setup` 同 `sitter`；`dual-as-owner` = 以既有角色切換取得的 `currentRole=OWNER` token | 只用於 V-E02、V-E13（`dual` 當保母、`owner` 當飼主建一張 CONFIRMED 訂單） |

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4：訂單相關表含 `visit_checklist_items`、`visits`、`order_logs`、`idempotency_records`；跨模組含通知、通知偏好、Email 投遞紀錄、方案、毛孩、帳號）。

### 1.4 造資料程序（spec 共用 helper，名稱由 e2e-author 決定）

| 程序 | 步驟 |
|------|------|
| 建 CONFIRMED 訂單（`groups`） | `owner` E1（`groups[]` 每組 `planId`、`dates`、`dailyCount`、`petIds=[咪咪]`；`expectedAmount` = Σ 單價 × 日期數 × 每日次數；帶新 key）→ `sitter` E6 原價報價（`expectedAmount` 同上、不加價不折扣）→ `owner` E11 上傳測試憑證（取 E6 回應 `payment.paymentId`）→ `sitter` E14 核對 → 輪詢 E2 直到 `status=CONFIRMED`。建單日期一律 ≥ `D+1`（避開 E6 `FIRST_VISIT_DATE_PASSED`），需要今天 / 過去日期時再以 FT2 平移 |
| 建 PENDING_PAYMENT 訂單 | E1 → E6，停在 `PENDING_PAYMENT`（FT2 只能平移 `SERVICE_DATE`） |
| 平移日期（群組, n） | 對該群組任一行程呼叫 FT2 `target=SERVICE_DATE`、`days=n`（同 `orderItemId` 全部行程一起移） |
| 強制狀態 | FT1；`IN_PROGRESS` 帶 `startedAt`，`DONE` 帶 `startedAt` / `completedAt` / `completionSource`，`CLOSED_BY_SYSTEM` 帶 `closedAt`，`CANCELLED` 帶 `cancelledAt`。允許 `PENDING → IN_PROGRESS / DONE / CLOSED_BY_SYSTEM / CANCELLED`、`IN_PROGRESS → DONE`。FT1 對 `CONFIRMED` 訂單離開 `PENDING`（`CANCELLED` 除外）會經 `onVisitLeftPending` 觸發 O9；`CANCELLED` 不觸發 O9 |
| 跨日防護 | 台北時間 23:50–00:10 之間不開始任何 test（避免 `D0` 跨日） |

---

## 2. 測試步驟

> 步驟 ID 前綴 `V-`；spec `step()` / `apiStep()` 標籤以同一 ID 開頭。AC 欄格式 `AC-x.y-n` = PRD-008 AC-x.y 情境 n（無情境者省略）。
> 「頁面送出」= 以 Playwright 監聽該頁實際發出的請求（`page.waitForRequest` / `waitForResponse`），斷言次數、標頭與回應。
> 排程回應計數格式 `processed / skipped / failed`。

### 2.1 開始與結束（主流程，訂單 A）

前置：建 CONFIRMED 訂單 A：G1 = P1 `[D+1, D+2]` × 1（V1、V2）、G2 = P2 `[D+1]` × 1（V3）；平移 G1 −1、G2 −1 → V1 = `D0`、V2 = `D+1`、V3 = `D0`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-01 | AC-3.1（部分）；§4.5 | sitter | 開啟 `/sitter/visits` | `sitter-dashboard-section-today` 依序有 `sitter-dashboard-visit-card-{V1}`、`-{V3}`；兩卡 `sitter-dashboard-visit-status-*` 皆「待執行」；V2 卡不存在；`sitter-dashboard-section-overdue` 不顯示 | F6 200：`today` 的 `visitId` 依序 = [V1, V3]、`overdue = []`；V1 `name`「到府餵食」、V3「陪玩」 | 截圖 + API JSON |
| V-02 | AC-1.1 | sitter | 點 `sitter-dashboard-visit-btn-open-{V1}` | URL = `/sitter/orders/{A}/visits/{V1}`；`sitter-visit-panel` 顯示名稱「到府餵食」、日期 `D0`、飼主顯示名稱、毛孩「咪咪」；`sitter-visit-panel-status`「待執行」；`sitter-visit-panel-btn-start` 可按；`-date-not-reached-message`、`-deadline-passed-message` 皆不存在 | F2 200：`status=PENDING`、`dateReached=true`、`zombieDeadlinePassed=false`、`allowedActions` 含 `START`、`zombieAt = ZA(D0)` | 截圖 + API JSON |
| V-03 | AC-1.1；BR-18 | sitter | 點 `sitter-visit-panel-btn-start` | `sitter-visit-panel-status`「執行中」並顯示「開始時間 TPE(startedAt)」；出現 `sitter-visit-panel-btn-complete`；`-btn-start` 消失 | 頁面送出 F3 恰 1 次，帶 `Idempotency-Key`（記為 K1）；200 `status=IN_PROGRESS`、`orderStatus=IN_PROGRESS`、`startedAt` 介於 `[t0, t1]`；E2 `status=IN_PROGRESS`；F1 V2、V3 仍 `PENDING`；[INS] `orderLogs` 新增恰 1 列 `ORDER_STARTED`（`payload.visitId=V1`、`cause=STARTED`、`operatorRole=SITTER`），無 `VISIT_STARTED`；`notifications` 無任何 `recipientRole=OWNER` 的新列（開始行程不通知飼主） | 截圖 + API JSON |
| V-04 | AC-1.7-1；EX-16 | sitter | 以 K1 重送 F3（V1） | — | 200，body 與 V-03 完全相同（`startedAt` 相同）；[INS] `fulfill.visits` V1 `startedAt` 不變；`ORDER_STARTED` 仍只有 1 列 | API JSON |
| V-05 | AC-1.7-2；AC-1.4-1（`IN_PROGRESS`） | sitter | 以新 key K2 送 F3（V1）；再以 K2 重送 | — | 兩次皆 409 `VISIT_STATE_CONFLICT`，`payload.currentVisitStatus=IN_PROGRESS`、無 `completionSource`，兩次 body 相同（回放）；[INS] 無新增 log | API JSON |
| V-06 | AC-1.7-3；EX-16 | sitter | 以 K1 對 V2 路徑送 F3 | — | 422 `IDEMPOTENCY_KEY_REUSED`；F1 V2 仍 `PENDING` | API JSON |
| V-07 | AC-1.3-1；EX-8 | sitter | 開啟 `/sitter/orders/{A}/visits/{V2}` | `sitter-visit-panel-btn-start` 停用；`sitter-visit-panel-date-not-reached-message`「行程日期為 {D+1}，當天才能開始」；無 `-deadline-passed-message` | F2 `dateReached=false`、`allowedActions` 不含 `START` | 截圖 + API JSON |
| V-08 | AC-1.3-1；EX-8 | sitter | 以新 key 送 F3（V2） | — | 422 `VISIT_DATE_NOT_REACHED`，`payload.serviceDate = D+1`；F1 V2 仍 `PENDING`；[INS] 無新增 log | API JSON |
| V-09 | AC-1.4-2（`PENDING`） | sitter | 以新 key 送 F4（V2） | — | 409 `VISIT_STATE_CONFLICT`，`currentVisitStatus=PENDING` | API JSON |
| V-10 | AC-1.5-1；BR-5 | sitter | 回到 V1 面板，點 `sitter-visit-panel-btn-complete` | 出現 `sitter-visit-panel-complete-dialog`，含「結束後無法再開始此行程。請於結束後 24 小時內送出照護日誌，逾時日誌將無法送出。」與「尚有 3 項未打勾（不影響結束）」 | F2 `checklist.total=3`、`checklist.completed=0`；`careLog.status` 不輸出（無草稿） | 截圖 |
| V-11 | AC-1.5-1；T2 | sitter | 點 `sitter-visit-panel-btn-complete-confirm` | `sitter-visit-panel-status`「已完工」；顯示「結束時間 TPE(completedAt)」與「日誌可送出至 TPE(completedAt + 24h)」 | 頁面送出 F4 恰 1 次、帶 `Idempotency-Key`；200 `status=DONE`、`completionSource=SITTER`、`completedAt` 介於 `[t0, t1]`、`careLogDeadlineAt = completedAt + 24h`；E2 `status=IN_PROGRESS`；[INS] `completionBasisAt` 為 null（V2、V3 未結束）；`orderLogs` 新增 1 列 `VISIT_COMPLETED`（`payload.visitId=V1`、`operatorRole=SITTER`） | 截圖 + API JSON |
| V-12 | AC-1.4-2（`DONE`）；AC-1.4-1（`DONE`）；EX-7 | sitter | 以新 key 各送 F4、F3（V1） | — | 皆 409 `VISIT_STATE_CONFLICT`，`currentVisitStatus=DONE`、`completionSource=SITTER`；[INS] V1 `completedAt` 不變、無新增 log | API JSON |
| V-13 | AC-1.2-1；BR-2 | sitter | 開啟 V3 面板，點 `sitter-visit-panel-btn-start` | 「執行中」 | 200 `orderStatus=IN_PROGRESS`；E2 仍 `IN_PROGRESS`；[INS] 新增 1 列 `VISIT_STARTED`（`visitId=V3`），`ORDER_STARTED` 仍只有 V-03 那 1 列 | 截圖 + API JSON |
| V-14 | AC-1.5-2 | sitter | V3 面板點 `sitter-visit-panel-btn-complete`，看完後關閉確認框（不確認） | `sitter-visit-panel-complete-dialog` 含 24 小時日誌提醒，不含「尚有」字樣 | F2 V3 `checklist.total=0` | 截圖 |
| V-15 | EX-7（`IN_PROGRESS` 文案）；BR-8 | sitter | 平移 G1 −1（V1 → `D-1`、V2 → `D0`）；開啟 V2 面板（`-btn-start` 可按）；背景以 API 送 F3（V2）成功後，在**未重新整理**的面板點 `sitter-visit-panel-btn-start` | 顯示「此行程已開始」 | 頁面送出的 F3 回 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=IN_PROGRESS`；點擊到文案出現之間頁面未送出 F2（不重新讀取） | 截圖 + API JSON |
| V-16 | AC-1.5-2（n = 0） | sitter | 重新整理 V2 面板；以 F12 把 V2 的 3 個檢核項全部勾選（PRD-028）；點 `sitter-visit-panel-btn-complete` → `-btn-complete-confirm` | 確認框不含「尚有」字樣、含 24 小時提醒；確認後「已完工」 | F2 `checklist.completed=3`；F4 200 | 截圖 + API JSON |
| V-17 | EX-7（`DONE` + `SITTER` 文案）；AC-2.4-3 對照 | sitter | 開啟 V3 面板（「執行中」）；背景以 API 送 F4（V3）成功後，在未重新整理的面板點 `-btn-complete` → `-btn-complete-confirm` | 顯示「此行程已結束」 | 頁面送出的 F4 回 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=DONE`、`completionSource=SITTER`；其間未送出 F2 | 截圖 + API JSON |
| V-18 | AC-1.5-1（結束時間計入基準）；§4.2-5 | system | [INS] 訂單 A；E2 | — | `completionBasisAt` = max(V1、V2、V3 `completedAt`) + 24h；E2 `disputeWindowEndsAt = completionBasisAt + 48h`；`status` 仍 `IN_PROGRESS`（本卡不寫主狀態） | API JSON |

### 2.2 開始的日期與殭屍期限（訂單 B）

前置：建 CONFIRMED 訂單 B：G1 = P1 `[D+1]` × 1（VB1）、G2 = P2 `[D+2]` × 1（VB2）；平移 G1 −3（VB1 = `D-2`）、G2 −5（VB2 = `D-3`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-19 | AC-1.3-4；EX-9 | sitter | 開啟 VB2 面板 | `sitter-visit-panel-btn-start` 停用；`sitter-visit-panel-deadline-passed-message`「此行程已超過可開始期限，系統將自動關閉」；無 `-date-not-reached-message` | F2 `status=PENDING`、`zombieDeadlinePassed=true`、`zombieAt = ZA(D-3)`、`allowedActions` 不含 `START` | 截圖 + API JSON |
| V-20 | AC-1.3-3；EX-9；BR-4 | sitter | 以新 key 送 F3（VB2） | — | 409 `DEADLINE_PASSED`，`payload.deadline=VISIT_ZOMBIE_AT`、`deadlineAt = ZA(D-3)`；F1 VB2 仍 `PENDING`；E2 仍 `CONFIRMED`；[INS] 無新增 log | API JSON |
| V-21 | AC-1.3-4（對照） | sitter | 開啟 VB1 面板 | `-btn-start` 可按；無 `-deadline-passed-message` | F2 `zombieDeadlinePassed=false`、`zombieAt = ZA(D-2)` | 截圖 + API JSON |
| V-22 | AC-1.3-2；BR-3 | sitter | VB1 面板點 `-btn-start` | 「執行中」 | F3 200；E2 `IN_PROGRESS`（O9） | 截圖 + API JSON |
| V-23 | AC-2.2-1；T3 | system | 呼叫 C7（`orderId=B`） | — | 200，計數 1 / 0 / 0；[INS] VB2 `CLOSED_BY_SYSTEM`、`closedAt` 介於 `[t0, t1]`；E2 仍 `IN_PROGRESS`；`orderLogs` 新增 `VISITS_CLOSED_BY_SYSTEM`（`payload.visitIds=[VB2]`），無 `operatorRole=SYSTEM` 的 `ORDER_STARTED` | API JSON |
| V-24 | AC-1.4-1（`CLOSED_BY_SYSTEM`） | sitter | 以新 key 送 F3（VB2） | — | 409 `VISIT_STATE_CONFLICT`，`currentVisitStatus=CLOSED_BY_SYSTEM` | API JSON |

### 2.3 訂單狀態不允許（訂單 P、C）

前置：建 PENDING_PAYMENT 訂單 P：P1 `[D+1]` × 1（VP）；平移 −1（VP = `D0`）。建 CONFIRMED 訂單 C：P1 `[D+1]` × 1（VC）→ FT1 VC `DONE`（`startedAt = now − 26h`、`completedAt = now − 25h`、`careLogSubmitted=true`）→ `owner` E25 確認結案 → E2 `COMPLETED`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-25 | AC-1.4-3；EX-6 | sitter | 以新 key 送 F3（VP） | — | 409 `ORDER_STATE_CONFLICT`，`currentStatus=PENDING_PAYMENT`；F1 VP 仍 `PENDING` | API JSON |
| V-26 | §4.6-3（SD-FULFILL CQ-11） | sitter | 開啟 `/sitter/orders/{P}` | 行程區塊顯示：`sitter-order-visit-label`「到府餵食」、`-date` `D0`、`-status`「待執行」；點行程列不進入服務面板（URL 不變） | F1（SITTER）200：`orderStatus=PENDING_PAYMENT`、VP `name`「到府餵食」 | 截圖 + API JSON |
| V-27 | AC-2.2-3；BR-11 | system | `owner` E11 上傳首次付款憑證（不核對）；平移 VP −3（VP = `D-3`）；呼叫 C7（`orderId=P`） | — | 200，計數 0 / 0 / 0（`PENDING_PAYMENT` 不列入候選）；F1 VP 仍 `PENDING`；E2 仍 `PENDING_PAYMENT`；[INS] `notifications` 無 `VISIT_CLOSED_BY_SYSTEM_*` | API JSON |
| V-28 | AC-1.4-4；EX-6 | sitter | 以新 key 各送 F3、F4（VC） | — | 皆 409 `ORDER_STATE_CONFLICT`，`currentStatus=COMPLETED`（訂單檢查先於行程檢查） | API JSON |
| V-29 | AC-3.7-4；AC-3.7-6（後半） | sitter | F5（VC）`name=早上服務` | — | 409 `ORDER_STATE_CONFLICT`，`currentStatus=COMPLETED`（VC `completedAt + 24h` 已過，仍先回訂單狀態錯誤，非 `DEADLINE_PASSED`）；[INS] VC `customName` 為 null | API JSON |

### 2.4 殭屍關閉（訂單 Z1、Z2、Z3）

前置：建 CONFIRMED 訂單 Z1：P1 `[D+1, D+2, D+3]` × 1 → 平移 −4（VZ1 = `D-3`、VZ2 = `D-2`、VZ3 = `D-1`）。V-E11 須在 V-31 之前執行。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-30 | EX-13 前置 | sitter | 開啟 VZ2 面板並保持不重新整理 | `-btn-start` 可按 | F2 `status=PENDING` | 截圖 |
| V-31 | AC-2.1；T3；T3a；BR-11；BR-18 | system | 呼叫 C7（`orderId=Z1`） | — | 200，計數 1 / 0 / 0；[INS] VZ1 `CLOSED_BY_SYSTEM`、`closedAt` 介於 `[t0, t1]`；VZ2、VZ3 仍 `PENDING`；`status=IN_PROGRESS`；`orderLogs` 新增 `VISITS_CLOSED_BY_SYSTEM`（`visitIds=[VZ1]`）與 `operatorRole=SYSTEM` 的 `ORDER_STARTED`（`visitId=VZ1`、`cause=CLOSED_BY_SYSTEM`）；`notifications` 恰新增 2 列：`VISIT_CLOSED_BY_SYSTEM_SITTER`（`recipientRole=SITTER`）、`VISIT_CLOSED_BY_SYSTEM_OWNER`（`recipientRole=OWNER`），皆 `mandatory=false`、`emailStatus` 有值（雙方 Email 偏好開啟，已建立投遞）；[NOTI] `sitter` 內容「{D-3} 到府餵食 未於期限內開始，已由系統關閉」、連結 `/sitter/orders/{Z1}`；`owner` 內容「保母未於期限內開始 {D-3} 到府餵食，系統已關閉此行程」、連結 `/owner/orders/{Z1}` | API JSON |
| V-32 | AC-2.6-3；BR-13 | system | 再呼叫一次 C7（`orderId=Z1`） | — | 200，計數 0 / 0 / 0；[INS] VZ1 `closedAt` 與 V-31 相同；`notifications`、`orderLogs` 無新增 | API JSON |
| V-33 | EX-13（殭屍先）；EX-7（`CLOSED_BY_SYSTEM` 文案） | sitter | FT1 把 VZ2 設為 `CLOSED_BY_SYSTEM`（模擬排程先完成）；在 V-30 未重新整理的面板點 `-btn-start` | 顯示「此行程已由系統關閉」 | 頁面送出的 F3 回 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=CLOSED_BY_SYSTEM`；其間未送出 F2 | 截圖 + API JSON |
| V-34 | AC-3.7-2（`CLOSED_BY_SYSTEM`）；AC-3.7-6（前半）；EX-7 | sitter | F5（VZ2）`name=晚上服務`；再送 `name` = 去空白後 21 字 | — | 第一次 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=CLOSED_BY_SYSTEM`；第二次 400 `VALIDATION_FAILED`、`field=name`（長度先於 409）；[INS] VZ2 `customName` 為 null | API JSON |
| V-35 | AC-2.5-1（循序版：C7 先）；EX-14（循序）；§4.3-4 | sitter | VZ3 面板點 `-btn-start` | 「執行中」 | F3 200 `orderStatus=IN_PROGRESS`；[INS] 新增 `VISIT_STARTED`（`visitId=VZ3`），`ORDER_STARTED` 仍只有 V-31 的 SYSTEM 那 1 列（O9 只由先完成者觸發）；F2 VZ1 `allowedActions = []`（終態） | 截圖 + API JSON |

前置：建 CONFIRMED 訂單 Z2：G1 = P1 `[D+1]` × 1（VY1）、G2 = P2 `[D+1]` × 1（VY2）→ FT1 VY2 `DONE`（`startedAt = now − 27h`、`completedAt = now − 26h`；訂單經 O9 → `IN_PROGRESS`）→ 平移 G1 −4（VY1 = `D-3`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-36 | AC-2.2-1；BR-2 | system | 呼叫 C7（`orderId=Z2`） | — | 計數 1 / 0 / 0；[INS] VY1 `CLOSED_BY_SYSTEM`；`status` 仍 `IN_PROGRESS`；C7 前後 `ORDER_STARTED` 列數不變；`completionBasisAt = VY1.closedAt`（VY1 是最後結束者，晚於 VY2 `completedAt + 24h`） | API JSON |

前置：建 CONFIRMED 訂單 Z3：P1 `[D+1, D+2]` × 1 → 平移 −5（VX1 = `D-4`、VX2 = `D-3`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-37 | AC-2.2-4；BR-18 | system | 呼叫 C7（`orderId=Z3`） | — | 計數 1 / 0 / 0（以訂單計，見 §5 L-13）；[INS] VX1、VX2 皆 `CLOSED_BY_SYSTEM`；`notifications` 恰新增 `VISIT_CLOSED_BY_SYSTEM_SITTER`、`VISIT_CLOSED_BY_SYSTEM_OWNER` 各 1 列；[NOTI] 兩則內容皆同時含 `{D-4}`、`{D-3}` 與行程名稱「到府餵食」 | API JSON |

### 2.5 `IN_PROGRESS` 逾時（訂單 T1、T2）

前置：建 CONFIRMED 訂單 T1：P1 `[D+1]` × 1（VT）→ 平移 −1（VT = `D0`）→ FT1 VT `IN_PROGRESS`、`startedAt = now − 24h − 10m`（訂單 → `IN_PROGRESS`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-38 | AC-2.4-3 前置 | sitter | 開啟 VT 面板並保持不重新整理 | 「執行中」、`-btn-complete` 可見 | — | 截圖 |
| V-39 | AC-2.3；T4；BR-12；BR-18 | system | 呼叫 FC1（`orderId=T1`） | — | 200，計數 1 / 0 / 0；[INS] VT `DONE`、`completionSource=SYSTEM`、`completedAt` 介於 `[t0, t1]`；`orderLogs` 新增 `VISIT_AUTO_COMPLETED`（`operatorRole=SYSTEM`、`payload.visitId=VT`、`startedAt`、`completedAt`）；`notifications` 恰新增 1 列 `VISIT_AUTO_COMPLETED`（`recipientRole=SITTER`、`mandatory=false`），無 `recipientRole=OWNER` 新列；`completionBasisAt = completedAt + 24h`；[NOTI] `sitter` 內容「{D0} 到府餵食 開始後超過 24 小時未結束，系統已自動結束。請於 TPE(completedAt + 24h) 前送出照護日誌」、連結 `/sitter/orders/{T1}/visits/{VT}` | API JSON |
| V-40 | AC-2.4-3；EX-13（逾時先）；EX-7（`DONE` + `SYSTEM` 文案） | sitter | 在 V-38 未重新整理的面板點 `-btn-complete` → `-btn-complete-confirm` | 顯示「此行程已由系統自動結束」 | 頁面送出的 F4 回 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=DONE`、`completionSource=SYSTEM`；其間未送出 F2 | 截圖 + API JSON |
| V-41 | AC-2.3；§4.4-4 | sitter | 重新整理 VT 面板 | 「已完工」；`sitter-visit-panel-auto-completed-label`「系統自動結束」；日誌送出入口可用（PRD-022 `sitter-care-log-btn-submit` 可按） | F2 `allowedActions` 含 `SUBMIT_CARE_LOG` | 截圖 |
| V-42 | AC-2.3；BR-17 | owner | 開啟 `/owner/orders/{T1}` | `owner-order-detail-visit-status-{VT}`「已完工」；`owner-order-detail-visit-auto-completed-{VT}`「系統自動結束」 | — | 截圖 |
| V-43 | AC-2.6-3 | system | 再呼叫一次 FC1（`orderId=T1`） | — | 計數 0 / 0 / 0；[INS] VT `completedAt` 不變；`notifications` 無新增 | API JSON |

前置：建 CONFIRMED 訂單 T2：P1 `[D+1]` × 1（VT2）→ 平移 −1 → FT1 VT2 `IN_PROGRESS`、`startedAt = now − 24h − 5m`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-44 | AC-2.4-2 | sitter | VT2 面板點 `-btn-complete` → `-btn-complete-confirm` | 「已完工」，無「系統自動結束」 | F4 200 `completionSource=SITTER`、`completedAt` 介於 `[t0, t1]` | 截圖 + API JSON |
| V-45 | AC-2.4-2；EX-13（保母先） | system | 呼叫 FC1（`orderId=T2`） | — | 計數 0 / 0 / 0（已非 `IN_PROGRESS`，不在候選）；[INS] VT2 `completionSource=SITTER`、`completedAt` 與 V-44 相同；無 `VISIT_AUTO_COMPLETED` log 與通知 | API JSON |

### 2.6 變更、爭議與 ADMIN 調解（訂單 M、M2）

前置：建 CONFIRMED 訂單 M：G1 = P1 `[D+1 … D+5]` × 1（VM1–VM5）、G2 = P2 `[D+1]` × 1（VM6），總額 `$ 2,800`；平移 G1 −3（VM1 = `D-2`、VM2 = `D-1`、VM3 = `D0`、VM4 = `D+1`、VM5 = `D+2`）；FT1 VM1 `DONE`（`startedAt = now − 3h`、`completedAt = now − 2h`；訂單 → `IN_PROGRESS`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-46 | EX-22 前置 | owner / sitter | `owner` E17 發起 `CHANGE` 減少 [VM3]；`sitter` E20 報價新總額 2300 | — | E19 變更 `status=QUOTED` | API JSON |
| V-47 | EX-22；AC-1.8-1（循序版：開始先） | sitter | VM3 面板點 `-btn-start` | 「執行中」 | F3 200（協商期間照常開始） | 截圖 + API JSON |
| V-48 | AC-1.8-1（循序版）；EX-11（開始先） | owner | E23 同意 V-46 的變更 | — | 409 `VISIT_ALREADY_STARTED`（payload 含 VM3）；E19 變更仍 `QUOTED`；F1 VM3 仍 `IN_PROGRESS` | API JSON |
| V-49 | — | owner | E22 撤回該變更 | — | 變更不再為未結束狀態（E19） | API JSON |
| V-50 | AC-1.8-2 前置 | sitter | 開啟 VM2 面板並保持不重新整理（`-btn-start` 可按） | — | — | 截圖 |
| V-51 | AC-1.8-2 前置 | owner / sitter | `owner` E17 減少 [VM2] → `sitter` E20 報價新總額 2300 → `owner` E23 同意 | — | E23 200；F1 VM2 `CANCELLED`、`cancelledAt` 不為空；E19 變更進入待退款（`AWAITING_REFUND`） | API JSON |
| V-52 | AC-1.8-2（循序版：同意先）；EX-11；EX-7（`CANCELLED` 文案） | sitter | 在 V-50 未重新整理的面板點 `-btn-start` | 顯示「此行程已取消」 | 頁面送出的 F3 回 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=CANCELLED`；其間未送出 F2；E2 仍 `IN_PROGRESS`（取消不觸發 O9） | 截圖 + API JSON |
| V-53 | AC-3.7-2（`CANCELLED`）；EX-7 | sitter | F5（VM2）`name=晚上服務` | — | 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=CANCELLED` | API JSON |
| V-54 | AC-3.3（`CANCELLED` 列）；BR-17 | owner | 開啟 `/owner/orders/{M}` | `owner-order-detail-visit-row-{VM2}` 存在；`-visit-status-{VM2}`「已取消」；開始 / 結束時間「—」 | F1（OWNER）含 VM2 `status=CANCELLED` | 截圖 |
| V-55 | §4.6-3（SD-FULFILL CQ-11） | sitter | 開啟 `/sitter/orders/{M}` | VM2 列 `sitter-order-visit-status`「已取消」、`-label`「到府餵食」 | — | 截圖 |
| V-56 | BR-7 前置 | owner | E24 申報款項爭議 | — | E2 `status=DISPUTED` | API JSON |
| V-57 | AC-1.2-2；BR-7 | sitter | 平移 G2 −1（VM6 = `D0`）；VM6 面板點 `-btn-start` | 「執行中」 | F3 200 `orderStatus=DISPUTED`；E2 仍 `DISPUTED`；[INS] 新增 `VISIT_STARTED`（`visitId=VM6`）、無新 `ORDER_STARTED` | 截圖 + API JSON |
| V-58 | AC-3.5-4；EX-4 | admin | 以 `admin` 呼叫 F1（M）；開啟 `/admin/orders/{M}` 行程區塊 | 行程區塊唯讀：無開始 / 結束 / 命名 / 檢核項編輯元素 | F1 200；`careLog` 只含 `submitted`；E27 `logs[]` 含 `VISIT_STARTED`（VM3、VM6）且與 [INS] `orderLogs` 一致；V-48、V-52、V-53 被拒的操作沒有對應列 | 截圖 + API JSON |
| V-59 | AC-2.2-2；AC-2.5-3 / EX-23（循序版：同意先）；BR-7 | system | 平移 G1 −4（VM4 = `D-3`、VM5 = `D-2`）；呼叫 C7（`orderId=M`） | — | 計數 1 / 0 / 0；[INS] VM4 `CLOSED_BY_SYSTEM`、VM5 仍 `PENDING`、VM2（已 `CANCELLED`、日期已逾期）仍 `CANCELLED`；`status` 仍 `DISPUTED`；新增 `VISITS_CLOSED_BY_SYSTEM`（`visitIds=[VM4]`），無 SYSTEM 的 `ORDER_STARTED` | API JSON |
| V-60 | AC-2.4-4；BR-7 | system | FT2 VM3 `STARTED_AT`、`minutes=-1450`；呼叫 FC1（`orderId=M`） | — | 計數 1 / 0 / 0；[INS] VM3 `DONE`、`completionSource=SYSTEM`；VM6（剛開始）仍 `IN_PROGRESS`；`status` 仍 `DISPUTED`；新增 `VISIT_AUTO_COMPLETED`（`visitId=VM3`） | API JSON |
| V-61 | EX-12（開始先） | admin | E29 選 O15 調解結案（不登記退款） | — | 409 `VISITS_NOT_FINISHED`，`payload.visitIds=[VM6]`；E2 仍 `DISPUTED` | API JSON |
| V-62 | EX-12 前置 | sitter / admin | `sitter` 結束 VM6（F4）；`admin` 再送 E29 O15 | — | E29 200；E2 `COMPLETED`；[INS] VM5 `CANCELLED`（剩餘 `PENDING` 行程取消） | API JSON |
| V-63 | EX-12（ADMIN 先） | sitter | 以新 key 送 F3（VM5） | — | 409 `ORDER_STATE_CONFLICT`，`currentStatus=COMPLETED` | API JSON |

前置：建 CONFIRMED 訂單 M2：P1 `[D+1, D+2]` × 1（VN1、VN2），總額 `$ 1,000`；平移 −1（VN1 = `D0`、VN2 = `D+1`）；`owner` E17 減少 [VN2] → `sitter` E20 報價新總額 500 → `owner` E23 同意（VN2 `CANCELLED`、變更待退款）→ `owner` E24 → `DISPUTED`（無任何 `DONE` / `CLOSED_BY_SYSTEM` 行程）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-64 | AC-1.8-3（循序版：O17 先）；EX-12 | admin / sitter | 開啟 VN1 面板並保持不重新整理；`admin` E29 選 O17 調解取消；在未重新整理的面板點 `-btn-start` | 顯示「訂單狀態已變更，請重新整理」 | E29 200；E2 `CANCELLED`；[INS] VN1 `CANCELLED`；頁面送出的 F3 回 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED` | 截圖 + API JSON |

### 2.7 保母「行程」頁與飼主「行程」頁

前置（依序建立，訂單建立時間 Y < X < W）：
- 訂單 Y：CONFIRMED，P1 `[D+1, D+2]` × 1 → 平移 −1（VY1 = `D0`、VY2 = `D+1`）。
- 訂單 X：CONFIRMED，G1 = P1 `[D+1]` × 2、G2 = P2 `[D+1]` × 1 → 平移 G1 −1（VX1 第 1 次、VX2 第 2 次，皆 `D0`）、G2 −1（VX5 = `D0`）→ FT1 VX1 `DONE`（訂單 → `IN_PROGRESS`）→ FT1 VX5 `CANCELLED`。
- 訂單 Zp：PENDING_PAYMENT，P1 `[D+1]` × 1 → 平移 −1（VZp = `D0`）。
- 訂單 W：CONFIRMED，G1 = P1 `[D+1]` × 1、G2 = P2 `[D+1]` × 2 → 平移 G1 −3（VW6 = `D-2`）、G2 −2（VW7、VW8 = `D-1`）→ FT1 VW7 `IN_PROGRESS`（`startedAt = now − 1h`）、FT1 VW8 `DONE`。
- `sitterB` 與 `ownerB` 另建 CONFIRMED 訂單 B2：`sitterB` 方案 `[D+1]` × 1 → 平移 −1（VB = `D0`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-65 | AC-3.1-1；BR-1；BR-14；BR-15；BR-17；AC-3.5-5 | sitter | 開啟 `/sitter/visits` | `sitter-dashboard-section-today` 卡片依序 VY1、VX1、VX2；VY1 名稱「到府餵食」「待執行」；VX1「到府餵食 第 1 次」「已完工」；VX2「到府餵食 第 2 次」「待執行」；無 VY2、VX5（已取消）、VZp、VB 卡；每卡有 `sitter-dashboard-visit-btn-open-{visitId}`、`sitter-dashboard-visit-link-pet-notes-{visitId}`，顯示 `owner` 顯示名稱與「咪咪」 | F6 `today` 的 `visitId` 恰為 [VY1, VX1, VX2]；回應任何位置都不含 VX5、VB | 截圖 + API JSON |
| V-66 | AC-3.2；BR-15；BR-16 | sitter | 同頁看待處理區 | `sitter-dashboard-section-overdue` 依序 VW6、VW7，無 VW8；VW6 卡顯示「系統將於 {D+1} 00:00 關閉」；VW7「執行中」 | F6 `overdue` = [VW6, VW7]；VW6 `zombieAt = ZA(D-2)`；VW7 無 `zombieAt` | 截圖 + API JSON |
| V-67 | §4.5-2 | sitter | 點 `sitter-dashboard-visit-link-pet-notes-{VY1}` | URL = `/care-notes/manage/{sitter.userId}/{owner.userId}`（SD-FULFILL §9.1，PRD-021 照護記事本） | F6 VY1 `counterpart.userId = owner.userId` | 截圖 |
| V-68 | §4.5-2 | sitter | 返回，點 `sitter-dashboard-visit-btn-open-{VY1}` | URL = `/sitter/orders/{Y}/visits/{VY1}`，顯示 `sitter-visit-panel` | — | 截圖 |
| V-69 | AC-3.2（關閉後消失） | sitter | 平移 W 的 G1 −1（VW6 = `D-3`）；呼叫 C7（`orderId=W`）；重新整理 `/sitter/visits` | 待處理區只剩 VW7 | C7 計數 1 / 0 / 0；F6 `overdue` = [VW7] | 截圖 + API JSON |
| V-70 | AC-3.4-1；BR-15 | owner | 開啟 `/owner/visits` | 依序 `owner-dashboard-visit-card-{VY1}`、`-{VX1}`、`-{VX2}`，各顯示 `sitter` 顯示名稱、行程名稱、狀態文字；無 VY2、VX5、VZp 卡 | F7 `today` 的 `visitId` 恰為 [VY1, VX1, VX2] | 截圖 + API JSON |
| V-71 | AC-3.4-1 | owner | 點 `owner-dashboard-visit-card-{VY1}` | URL = `/owner/orders/{Y}` | — | 截圖 |
| V-72 | AC-3.1-2 | sitter（新） | 另建一個無任何訂單的 SITTER，開啟 `/sitter/visits` | `sitter-dashboard-empty`「今天沒有行程」；無 today / overdue 卡片 | F6 `today=[]`、`overdue=[]` | 截圖 |
| V-73 | AC-3.4-2 | owner（新） | 另建一個無任何訂單的 OWNER，開啟 `/owner/visits` | `owner-dashboard-empty`「今天沒有保母到府行程」 | F7 `today=[]` | 截圖 |

### 2.8 飼主訂單詳情行程狀態（訂單 H）

前置：建 CONFIRMED 訂單 H：P1 `[D+1 … D+5]` × 1（VH1–VH5）。取當下整分鐘時間 m0：FT1 VH1 `DONE`（`startedAt = m0 − 5h`、`completedAt = m0 − 3h`、`completionSource=SITTER`）；FT1 VH2 `DONE`（`completionSource=SYSTEM`）；FT1 VH3 `CLOSED_BY_SYSTEM`；VH4 維持 `PENDING`；FT1 VH5 `CANCELLED`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-74 | AC-3.3；BR-16；BR-17 | owner | 開啟 `/owner/orders/{H}` | 5 列 `owner-order-detail-visit-row-{VH1…VH5}`；`-visit-status-*` 依序「已完工」「已完工」「系統結案」「待執行」「已取消」；`-visit-started-at-{VH1}` = TPE(m0 − 5h)、`-visit-completed-at-{VH1}` = TPE(m0 − 3h)；VH4 開始 / 結束皆「—」；`owner-order-detail-visit-auto-completed-*` 只有 VH2，文字「系統自動結束」；頁面文字不匹配 `/\b(PENDING\|IN_PROGRESS\|DONE\|CLOSED_BY_SYSTEM\|CANCELLED)\b/` | F1（OWNER）`visits` 5 筆，`careLog` 只含 `submitted` | 截圖 + API JSON |

### 2.9 行程命名與預設名稱（訂單 N）

前置：`owner` E1 建訂單 N：G1 = P1 `[D+1]` × 2（VN1、VN2）、G2 = P2 `[D+1]` × 1（VN3），停在 `PENDING`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-75 | §4.6-3（SD-FULFILL CQ-11）；BR-14（CQ-15） | sitter | 開啟 `/sitter/orders/{N}`（報價前） | 行程區塊顯示 3 列：`sitter-order-visit-label`「到府餵食 第 1 次」「到府餵食 第 2 次」「陪玩」、`-status`「待執行」；點行程列不進入服務面板 | F1（SITTER）200 `orderStatus=PENDING`，`name` 同畫面 | 截圖 + API JSON |
| V-76 | §4.6-3（對照 PRD-009 BR-24） | owner | 開啟 `/owner/orders/{N}` | 不顯示行程區塊（無 `owner-order-detail-visit-row-*`） | — | 截圖 |
| V-77 | AC-3.6-5 前段；BR-14（CQ-15：報價前用 O1 帶入的方案名稱） | sitter | `sitter-setup` upsert 把 `key=P1` 的標題改為「貓咪到府」；重新開啟 `/sitter/orders/{N}` | 標籤仍為「到府餵食 第 1 次」「到府餵食 第 2 次」 | F1（SITTER）VN1 / VN2 `name` 仍為「到府餵食 第 n 次」 | 截圖 + API JSON |
| V-78 | 前置；BR-14（CQ-9：O2 快照） | sitter / owner | 完成 E6 → E11 → E14（CONFIRMED）；平移 G1 −1、G2 −1（皆 `D0`）；FT1 VN1 `DONE`（`completedAt = now − 1h`） | — | E2 `IN_PROGRESS`；F1 VN2 `name`「貓咪到府 第 2 次」（報價快照取報價當下的方案名稱） | API JSON |
| V-79 | AC-3.6-1；§4.7-2（含全形空白） | sitter | VN2 面板點 `sitter-visit-panel-btn-edit-name`；`sitter-visit-panel-input-name` 的 placeholder 為「貓咪到府 第 2 次」；輸入「（U+3000）晚上服務  」（前置全形空白、後置兩個半形空白），點 `sitter-visit-panel-btn-save-name` | 面板名稱「晚上服務」 | 頁面送出 F5 恰 1 次，不帶 `Idempotency-Key`；200 `name=晚上服務`、`customName=晚上服務`；[INS] 新增 `VISIT_RENAMED`（`oldName=null`、`newName=晚上服務`）；`notifications` 無新增 | 截圖 + API JSON |
| V-80 | AC-3.6-1；§4.7-4 | sitter / owner | `sitter` 開啟 `/sitter/visits` 與 `/sitter/orders/{N}`；`owner` 開啟 `/owner/orders/{N}` | VN2 卡、`sitter-order-visit-label`、`owner-order-detail-visit-row-{VN2}` 皆顯示「晚上服務」 | F6、F1（雙方）VN2 `name=晚上服務` | 截圖 |
| V-81 | AC-3.6-2；BR-14 | sitter | F5（VN1，`DONE` 未滿 24h）`name=早上服務` | — | 200 `name=早上服務` | API JSON |
| V-82 | AC-3.6-3 | sitter | VN2 面板編輯名稱，清空後儲存 | 面板名稱回到「貓咪到府 第 2 次」 | F5 200 `name=貓咪到府 第 2 次`、`customName` 不輸出 | 截圖 + API JSON |
| V-83 | AC-3.6-4 | sitter | 開啟 VN3 面板 | 名稱「陪玩」（無「第 n 次」） | F2 VN3 `name=陪玩`、`slotsInDay=1` | 截圖 |
| V-84 | AC-3.6-5；BR-14（CQ-9、CQ-10） | sitter / owner | `sitter-setup` upsert 把 `key=P1` 再改名為「貓咪到府二」；雙方重新開啟訂單 N | `owner-order-detail-visit-row-{VN2}` 與 VN2 面板仍顯示「貓咪到府 第 2 次」 | F1、F2 VN2 `name=貓咪到府 第 2 次`；F13（SITTER）VN2 `visitName=貓咪到府 第 2 次`，回應無日誌標題欄位 | 截圖 + API JSON |
| V-85 | AC-3.7-1；EX-5 | sitter | VN2 面板輸入 21 字；再以 API 送 F5 `name` = 去空白後 21 字 | 輸入 21 字時 `sitter-visit-panel-btn-save-name` 停用 | 400 `VALIDATION_FAILED`、`field=name`；F1 VN2 名稱不變 | 截圖 + API JSON |
| V-86 | AC-3.7-3；EX-10 | sitter | FT2 VN1 `COMPLETED_AT`、`minutes=-1381`（`completedAt` = 約 now − 24h − 1m）；VN1 面板編輯名稱為「早上二」並儲存 | 顯示「已超過可編輯期限」 | 頁面送出的 F5 回 409 `DEADLINE_PASSED`，`deadline=CARE_LOG_EDIT_ENDS_AT`、`deadlineAt = VN1.completedAt + 24h`；[INS] VN1 `customName` 仍「早上服務」 | 截圖 + API JSON |

### 2.10 訂閱鎖定不擋（訂單 L）

前置：建 CONFIRMED 訂單 L：P1 `[D+1]` × 1（VL）→ 平移 −1；再以 `sitter-setup` upsert 把 `sitter` 改為 `tier=FREE`、`subscriptionExpiresAt = now − 1d`（鎖定）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| V-87 | AC-1.7-4；BR-6；EX-17 | sitter | VL 面板依序：開始行程、命名「早上服務」、結束行程 | 依序「執行中」、名稱「早上服務」、「已完工」 | F3、F5、F4 皆 200 | 截圖 + API JSON |

## 3. 權限與例外

> 越權一律用另一個真實帳號打 API；每列斷言後以 [INS] 確認目標行程 `status`、`startedAt`、`completedAt`、`customName` 不變、`orderLogs` 無新增。每列在所註明訂單所屬的 test 內執行；目標行程未註明者用 §2.1 訂單 A 中仍 `PENDING` 的行程（於 V-03 之前執行）。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| V-E01 | AC-1.6-1；EX-1 | `sitterB` 以新 key 送 F3、F4（訂單 A 的行程） | 皆 403 `FORBIDDEN` |
| V-E02 | AC-1.6-2；EX-2 | `dual` 當保母的 CONFIRMED 訂單（§1.3）：以 `dual-as-owner` 對其行程送 F3（新 key） | 403 `ROLE_MISMATCH` |
| V-E03 | AC-1.6-3；EX-2 | `admin` 以新 key 送 F3 | 403 `ROLE_MISMATCH` |
| V-E04 | AC-1.6-4；EX-3 | `sitter` 以新 key 送 F3，路徑 `orderId=A`、`visitId` = 隨機 UUID；另以 `orderId=A`、`visitId` = 同一 `sitter` / `owner` 在本 test 另建的 CONFIRMED 訂單 A' 的行程（路徑歸屬不符） | 皆 404 `NOT_FOUND`；A' 的行程不變 |
| V-E05 | AC-1.6-5；EX-5；BR-10 | `sitter` 送 F3 不帶 `Idempotency-Key` | 400 `VALIDATION_FAILED`，`field=Idempotency-Key` |
| V-E06 | AC-1.6-6；BR-8 | `sitterB` 送 F3（隨機 `visitId`），不帶 key | 404 `NOT_FOUND`（404 先於 400） |
| V-E07 | §3 權限表 | 未帶 token 送 F3、F1、F6 | 皆 401 `UNAUTHENTICATED` |
| V-E08 | AC-3.7-5；EX-1；EX-2 | `owner` 送 F5（訂單 N 的 VN2）；`sitterB` 送 F5 | 403 `ROLE_MISMATCH`；403 `FORBIDDEN` |
| V-E09 | AC-3.5-1、AC-3.5-2；EX-4 | `ownerB`、`sitterB` 各送 F1（訂單 H） | 皆 403 `FORBIDDEN` |
| V-E10 | AC-3.5-3 | `owner` 送 F1，`orderId` = 隨機 UUID | 404 `NOT_FOUND` |
| V-E11 | AC-2.6-4；EX-21；BR-13 | 於 V-31 之前：以 `sitter` 的使用者 token（不帶 `X-Internal-Secret`）呼叫 C7、FC1（`orderId=Z1`）；再以錯誤的 `X-Internal-Secret` 呼叫一次 | 皆 403 `FORBIDDEN`；[INS] VZ1 仍 `PENDING` |
| V-E12 | AC-3.5-4；EX-4 | `admin` 送 F1（訂單 A，`IN_PROGRESS` 且無 OPEN 待辦） | 403 `FORBIDDEN`（`DISPUTED` 的 200 見 V-58） |
| V-E13 | §3 權限表；SD-FULFILL §6.1 G4a | `owner` 送 F6；`sitter` 送 F7；`dual-as-owner` 送 F1（自己當保母的訂單） | 403 `ROLE_MISMATCH`；403 `ROLE_MISMATCH`；403 `ROLE_MISMATCH` |
| V-E14 | SD-FULFILL §6.3 FC1（`orderId` 範圍） | 以 `X-Internal-Secret` 呼叫 FC1、C7，`orderId` = 隨機 UUID | 皆 4xx（FC1 400 `VALIDATION_FAILED` `field=orderId`；C7 依 SD-ORDER §10.4 非測試訂單 404），不處理任何訂單 |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.3-1~3 的精確邊界（Taipei 23:59 / 72h 整點 = 拒絕） | 正式站不能注入 `Clock`；E2E 只驗日期前後與逾期一側（V-07、V-20、V-22） | `VisitStartIntegrationTest` |
| AC-1.7（EX-16 處理中 409 `IDEMPOTENCY_IN_PROGRESS`） | 需第一個請求卡在處理中，正式站無法穩定造出 | `VisitIdempotencyIntegrationTest` |
| AC-1.8-1、AC-1.8-2、AC-1.8-3 的真併發；AC-1.8-4、EX-15（`ORDER_BUSY` + `Retry-After: 1`） | 需兩條執行緒同時取鎖或持鎖 3 秒；E2E 只做循序版（V-47–V-52、V-61–V-64） | `VisitConcurrencyIntegrationTest` |
| AC-2.1 真實 Email 內容與實際寄達 | Resend 測試模式只寄專案擁有者信箱；E2E 只驗投遞紀錄已建立（V-31 `emailStatus`） | 手動驗證；`CloseZombieVisitsIntegrationTest`（投遞欄位依偏好） |
| AC-2.2-1 / AC-2.4-1 分鐘級邊界（15:59 / 16:00、01:59 / 02:00） | 不能注入 `Clock` | `CloseZombieVisitsIntegrationTest`、`InProgressTimeoutCronIntegrationTest` |
| AC-2.5-1~3、EX-14、EX-23 的真併發；AC-2.5-2（排程先 → 同意得 `VISIT_ALREADY_STARTED`） | 需同時取鎖；E2E 只做循序版（V-35：C7 先、開始後到；V-59：同意先、排程不處理） | `VisitConcurrencyIntegrationTest` |
| AC-2.6-1（候選查出後重驗略過）、AC-2.6-2（單筆例外隔離 `failed=1`）、AC-2.6-5（鎖住略過）；EX-20 | 需在候選查詢與處理之間插入動作、注入例外或持鎖 | `InProgressTimeoutCronIntegrationTest`、`CloseZombieVisitsIntegrationTest` |
| AC-3.7-3 `receivedAt = completed_at + 24h` 整點 | 不能注入 `Clock` | `VisitRenameIntegrationTest` |
| EX-18（Resend 寄信失敗） | 正式站無法注入故障 | SD-NOTIFY C11 測試（SD-FULFILL §10.1 已註明本 SD 不測） |
| EX-19（GCS） | 本卡不適用 | — |

## 5. 已知限制 / 待補

| # | 項目 | 狀態 |
|---|------|------|
| L-1 | `order_logs` 唯讀查詢 | 已解決：SD-ORDER §10.4 inspect |
| L-2 | ADMIN 測試帳號 | 已解決：`provision-account` `roles=[ADMIN]` |
| L-3 | 雙角色帳號與 `currentRole` 切換 | 已解決：`provision-account` `roles=[OWNER, SITTER]` + 既有角色切換 |
| L-4 | FT2 對 `PENDING_PAYMENT` 訂單 | 已解決：只允許 `SERVICE_DATE`（V-27、訂單 P / Zp 只用此項） |
| L-5 | `sitter-setup` 更新既有保母 / 方案（改名、鎖訂閱） | 已解決：upsert，以 `key` 對應方案 |
| L-6 | cleanup 清通知 | 已解決：SD-ORDER §10.4 跨模組範圍含通知與 Email 投遞紀錄 |
| L-7 | SD-FULFILL §10.1 未列 EX-16 處理中、EX-18 歸屬 | 已解決：`VisitIdempotencyIntegrationTest` 補列；EX-18 歸 SD-NOTIFY C11 |
| L-8 | 正式排程搶先、計數為全站值 | 已解決：排程帶 `orderId`，自動觸發跳過測試帳號訂單 |
| L-9 | 變更 / 爭議造資料（§2.6 訂單 M、M2）依賴 PRD-016 / 023 / 024 完整 API 流程，耗時且任一環節失敗會連帶失敗 | 未解決，可接受：`DISPUTED` 仍需「待退款變更 + E24」路徑（FT1 只造行程狀態，不造訂單主狀態）；`CANCELLED` 行程已改用 FT1（§2.7、§2.8） |
| L-10 | `sitter-dashboard-visit-link-pet-notes-{visitId}` 目的路由 | 已解決：SD-FULFILL §9.1 `/care-notes/manage/{sitterId}/{ownerId}`（V-67） |
| L-11 | 站內通知文案無 test-data 查詢（inspect `notifications` 不含內容），文案仍經現行站內通知列表 API | 未解決：SD-NOTIFY 重建時確認列表 API 路徑，必要時更新 [NOTI] |
| L-12 | V-E14 C7 帶非測試 / 不存在 `orderId` 的錯誤碼：SD-FULFILL FC1 寫 400 `VALIDATION_FAILED`（`field=orderId`），SD-ORDER §10.4 寫訂單非測試帳號 → 404，`InternalTestDataIntegrationTest` 寫 C1–C10「非測試訂單 404」，兩模組不一致 | 未解決：本 TS 依各自 SD 斷言；建議 SD 統一 |
| L-13 | C7 計數單位：PRD-008 AC-2.1 / BR-13 與 SD-FULFILL §4.6 以「每張訂單一個交易」計 `processed`，SD-ORDER §6.3 C1–C10 寫「C7 只算該訂單的行程」，同批關閉 2 個行程（V-37）時是 1 還是 2 不明 | 未解決：本 TS 依 PRD 以訂單計（V-37 `processed=1`）；建議 SD-ORDER 釐清該句是「範圍」還是「單位」 |

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依 commit 477c75d 核准後修訂更新：`order_logs`、通知類型 / Email 投遞、`completion_basis_at` 改以 inspect 在 E2E 斷言（V-03、V-11、V-18、V-23、V-31、V-36、V-39 等）；排程一律帶 `orderId` 並斷言精確計數，刪除避開排程分鐘與被搶先重跑；ADMIN / 雙角色改用 `provision-account roles[]`（V-E02、V-E13）；`CANCELLED` 行程改用 FT1，AC-3.1 V5（V-65）、AC-3.3 `CANCELLED` 列（V-74）改回 E2E；新增 V-64（AC-1.8-3 O17 先）、V-77（報價前取 O1 方案名稱）、V-E14（排程 `orderId` 範圍）；V-79 加全形空白（PRD-008 §4.7-2 回寫）；注意事項連結目的地依 SD-FULFILL §9.1（V-67）；步驟重新編號（V-01–V-87）；§5 已解決項標註 | SD-ORDER §10.4、SD-FULFILL §6.3 / §9.1 / §10.1 / §10.2、PRD-008 核准後修訂 |
| 2026-11-22 | 依 PRD-008（Approved，baseline 第 2 版改寫）與 SD-FULFILL / SD-ORDER 全文重寫；舊版（Check-in / Check-out、寫死 UUID、`MSG_DATA_INVALID_STATUS`）作廢。舊版「本測試發現並修復的真實 bug」紀錄屬舊實作（`VisitReportManager`、`/api/visits/{visitId}/start`），保留於 git 歷史 | 全面重建 2026-11；PRD-008、SD-FULFILL 核准 |
