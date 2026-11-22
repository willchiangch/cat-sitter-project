# TS-JOURNEY-03: 服務執行到結案（行程、SOP、照護日誌、結案 / 爭議 / 自動結案 / 系統出口）

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-008（行程生命週期）、PRD-028（單日 SOP 檢核項）、PRD-022（照護日誌與媒體）、PRD-009（結案、服務爭議、自動結案），皆 Approved 2026-11-22；PRD-005 / 006 / 007（建單、報價、付款）只用於造 `CONFIRMED` 訂單，不在本 TS 驗收 |
| 對應 SD | SD-FULFILL（F1–F4、F6–F8、F12–F16、F17、F18、FC1、FT2、§6.5 錯誤碼、§7.1 `order_logs`、§7.2 通知、§9.1 / §9.2 路由與 testid、§9.3 直傳三段式、§10.2 inspect `fulfill` 區塊）；SD-ORDER（E1、E2 `serverTime` / `completionHint` / `disputeWindowEndsAt`、E3、E6、E11、E14、E25、E26、E28、C7、C8（`?orderId=`）、§10.4 provision-account / sitter-setup / owner-setup / inspect / shift-time / cleanup） |
| 類型 | 旅程（打真後端、正式站、真 GCS 直傳） |
| 自動化路徑 | `frontend/e2e/journeys/service-execution-completion.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD-008 / 028 / 022 / 009、SD-FULFILL、SD-ORDER、SD-FRONTEND-SPEC、DOMAIN-BASELINE 撰寫，未參考程式碼。
> 旅程定位：跨四張 PRD 的端到端主線，每步標出依據的 PRD AC 與對應模組 TS 步驟 ID（TS-008 `V-`、TS-028 `K-`、TS-022 `CL-`、TS-009 `OC-`）。不重複模組 TS 的細項（格式、額度、判斷順序、併發、冪等變體），那些以模組 TS 為準。
> 與模組 TS 的差異：模組 TS 大量用 FT1 直接造行程狀態；本旅程主線**所有行程與日誌轉移都走正式端點**（F3 / F4 / F12 / F14–F18 / E25 / E26），只用 FT2 平移時間、SD-ORDER `shift-time COMPLETION_BASIS_AT` 平移結案基準，並以 C7 / FC1 / C8 帶 `?orderId=` 觸發排程。
> 正式站無法注入時鐘：時間點以 E2 `serverTime` 為準、以相對位移造出；分鐘 / 秒級邊界交後端整合測試（§4）。

---

## 1. 前置資料

> 不寫死 UUID：id 一律取自建立回應（`sitter-setup` / `owner-setup` 以請求 `key` 回傳 `planId` / `petId`）；行程 id 由 F1 `GET /api/orders/{orderId}/visits` 取得（依 `serviceDate`、`sequenceInDay` 排序）。每個 `test()` 自建帳號與訂單、可獨立重跑。

### 1.1 記號

沿用 TS-008 §1.1：`D0` / `D+n` / `D-n`（以 E2 `serverTime` 換算的 Asia/Taipei 日期）、`[t0, t1]`（操作前後各取一次 E2 `serverTime`）、`TPE(x)`（ISO → Asia/Taipei `YYYY-MM-DD HH:mm`）、`ZA(d)`（`VISIT_ZOMBIE_AT` = d 日 00:00 Asia/Taipei + 72h）、`新 key`。另：

| 記號 | 意義 |
|------|------|
| `[INS]` | `GET /api/internal/test-data/orders/{orderId}/inspect`（`X-Internal-Secret`）：`status`、`completionBasisAt`、`orderLogs[]`、`notifications[]`、`adminTasks[]`、`fulfill.visits / careLogs / media / checklistItems / uploads`。「log +n」= 操作前後該 `actionType` 列數差；「通知 +n」同理 |
| `[NOTI]` | 通知文案斷言：以收件者帳號呼叫現行站內通知列表 API（SD-NOTIFY 未重建，沿用 TS-008 §1.1 的 `GET /api/notifications?role=…`）比對內容與連結；筆數、類型、Email 投遞以 `[INS]` 為準 |
| `[EMAIL]` | `[INS] notifications[].emailStatus ∈ {PENDING, SENT, FAILED}`，即「已建立 Email 投遞」，不是 `SKIPPED_PREFERENCE` / `NOT_APPLICABLE`（SD-ORDER §10.4 列舉；理由見 §5 #4） |
| `業務 log` | `[INS] orderLogs` 排除 `TEST_VISIT_FORCED`、`TEST_TIME_SHIFTED` 與第一個行程開始之前（建單、報價、付款階段）的列 |
| `頁面送出` | 以 Playwright 監聽該頁實際發出的請求（`waitForRequest` / `waitForResponse`），斷言次數、標頭、body 與回應 |

### 1.2 端點代號（路徑取自 SD）

| 代號 | 端點 | 出處 |
|------|------|------|
| F1 / F2 | `GET /api/orders/{orderId}/visits`、`GET …`（`…` = `/api/orders/{orderId}/visits/{visitId}`） | SD-FULFILL §6.3 |
| F3 / F4 | `POST …/start`、`POST …/complete`（必帶 `Idempotency-Key`） | 同上 |
| F6 / F7 | `GET /api/visits/sitter/dashboard`、`GET /api/visits/owner/today` | 同上 |
| F8 / F12 | `GET …/checklist`、`PUT …/checklist/items/{itemId}/check`（body `checked`） | 同上 |
| F13 / F14 / F15 | `GET …/care-log`、`PUT …/care-log/draft`、`POST …/care-log/submit`（F15 必帶 key） | 同上 |
| F16 / F17 / F18 | `POST …/care-log/media-uploads`（申請，必帶 key）、`GET …/care-log/media-uploads/{uploadId}/target`（取傳檔位置）、`POST …/care-log/media-uploads/{uploadId}/complete`（確認） | 同上；SD-FULFILL §9.3 |
| FC1 | `POST /api/internal/cron/visits/in-progress-timeout?orderId={orderId}`（`X-Internal-Secret`） | SD-FULFILL §6.3 |
| C7 | `POST /api/internal/cron/visits/zombie?orderId={orderId}`（`X-Internal-Secret`） | SD-ORDER §8.1、§10.4 |
| C8 | `POST /api/internal/cron/orders/auto-complete?orderId={orderId}`（`X-Internal-Secret`） | SD-ORDER §8.1、§10.4 |
| FT2 | `POST /api/internal/test-data/visits/{visitId}/shift-time`（`target`、`minutes` / `days`） | SD-FULFILL §6.3 |
| SHIFT-BASIS | `POST /api/internal/test-data/orders/{orderId}/shift-time`（`target=COMPLETION_BASIS_AT`、`minutes`） | SD-ORDER §10.4 |
| E2 / E3 | `GET /api/orders/{orderId}`、飼主訂單列表（`tab=HISTORY` / `ACTIVE`） | SD-ORDER §6.2 |
| E25 / E26 | `POST /api/orders/{orderId}/complete`（O11）、`POST /api/orders/{orderId}/service-disputes`（O13），皆必帶 key | SD-ORDER §6.2 |
| E28 | `GET /api/admin/tasks?type=SERVICE_DISPUTE` | SD-ORDER §6.2 |

排程回應計數格式 `processed / skipped / failed`（以訂單為單位，只算 `orderId` 指定的那筆）。

### 1.3 帳號

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `sitter` | SITTER | `provision-account`（`@e2e-journey.test`，`roles=[SITTER]`，`displayName="保母J3"`）+ `sitter-setup`：`tier=PRO`（照片 10、影片 2）、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄、`emailPreferences.ORDER_AFFAIR=開`、`plans` = `key=P1`「到府餵食」單價 500、`defaultTasks` = 餵乾糧、換貓砂、清水碗，`active=true`、可預約區間涵蓋 `D0`–`D+30` | 每個 test 新建 |
| `owner` | OWNER | `provision-account`（`roles=[OWNER]`，`displayName="飼主J3"`）+ `owner-setup`：`pets` = `key=PET1` 咪咪（CAT）、`emailPreferences.ORDER_AFFAIR=開` | 每個 test 新建 |
| `sitterB` / `ownerB` | SITTER / OWNER | 同上（各自獨立，不與 `owner` / `sitter` 有任何訂單） | 越權用 |
| `admin` | ADMIN | `provision-account`（`roles=[ADMIN]`） | — |

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4：訂單相關表含 `visit_checklist_items`、`visits`、`visit_service_reports`、`service_report_media`、`care_log_media_uploads`、`admin_tasks`、`order_logs`、`idempotency_records`，commit 後刪 GCS 物件；跨模組含通知、通知偏好、Email 投遞紀錄、方案、毛孩、帳號）。

### 1.4 造資料程序

| 程序 | 步驟 |
|------|------|
| `MK-CONFIRMED(groups)` | 同 TS-008 §1.4「建 CONFIRMED 訂單」：`owner` E1（`groups[]` 每組 `planId=P1`、`dates`、`dailyCount`、`petIds=[咪咪]`、`expectedAmount`、新 key）→ `sitter` E6 原價報價（媒體額度於此快照為 PRO）→ `owner` E11 上傳測試憑證 → `sitter` E14 核對 → 輪詢 E2 至 `status=CONFIRMED`。建單日期一律 ≥ `D+1` |
| `平移日期(V, n)` | FT2 `target=SERVICE_DATE`、`days=n`（同 `orderItemId` 整群組一起移） |
| `SHIFT-WINDOW(order, Δ)` | 讀 `[INS] completionBasisAt` 與 E2 `serverTime`，以 SHIFT-BASIS 位移使 `completionBasisAt + 48h = serverTime + Δ`。**必須在該訂單最後一次 F4 / FC1 / FT2 之後呼叫**（這些會重算基準）。以 `[INS]` 而非 E2 `disputeWindowEndsAt` 計算，因 `DISPUTED` 訂單的 E2 不回 `disputeWindowEndsAt`（§5 #3） |
| `UPLOAD-UI(V, fixture, caption)` | 服務面板 `sitter-care-log-input-file` 選檔、`sitter-care-log-input-caption` 填說明、點 `sitter-care-log-btn-upload`；頁面依 SD-FULFILL §9.3 自動走 F16 → F17 → PUT（`storage.googleapis.com`）→ F18 |
| 跨日防護 | 台北時間 23:50–00:10 之間不開始任何 test（避免 `D0` 跨日） |

測試檔案：`jpg500`（約 500 KB 的 JPEG），沿用 TS-022 §1.3 fixture（`frontend/e2e/fixtures/care-log/`）。

### 1.5 各段訂單

| 代號 | 建立 | 行程 | 用於 |
|------|------|------|------|
| `J` | `MK-CONFIRMED`：P1 `[D+1]` × 每日 2 次（總額 `$ 1,000`）→ `平移日期(V1, −1)` | V1「到府餵食 第 1 次」、V2「到府餵食 第 2 次」，皆 `D0`、各 3 項 SOP（自方案帶入） | §2.1 主線 |
| `JB` | 同 `J` | VB1、VB2（`D0`） | §2.2 回報問題（O13） |
| `JC` | 同 `J` | VC1、VC2（`D0`） | §2.3 無人操作自動結案（O12） |
| `JD` | `MK-CONFIRMED`：P1 `[D+1, D+2]` × 每日 1 次 → `平移日期(VD1, −4)` | VD1 = `D-3`、VD2 = `D-2`（名稱皆「到府餵食」） | §2.4 系統出口（C7 殭屍關閉、FC1 逾時結束） |

---

## 2. 測試步驟

> 步驟 ID 前綴 `J3-`；e2e spec 的 `step()` / `apiStep()` 標籤必須以同一 ID 開頭。
> AC 欄寫全名縮寫：`008 AC-1.1` = PRD-008 AC-1.1；`-n` = 情境 n。「模組 TS」欄為對應的模組 TS 步驟，細項以該步驟為準。
> 「無檔案進入儲存服務」「確認作廢」等上傳細節不在本旅程斷言，見 TS-022。

### 2.1 主線：開始行程 → SOP 打勾 → 結束 → 上傳照片並送出日誌 → 飼主查看 → 第二行程 → 飼主確認結案（訂單 `J`）

| 步驟 | AC | 模組 TS | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|--------|------|------|---------|---------------|------|
| J3-01 | 008 AC-3.1-1（部分）；008 BR-14、BR-15 | V-01、V-65 | sitter | 開啟 `/sitter/visits` | `sitter-dashboard-section-today` 依序有 `sitter-dashboard-visit-card-{V1}`、`-{V2}`，名稱「到府餵食 第 1 次」「到府餵食 第 2 次」；`sitter-dashboard-visit-status-*` 皆「待執行」；不顯示 `sitter-dashboard-section-overdue` | F6 200：`today` 的 `visitId` = [V1, V2]、`overdue = []` | 截圖 + API JSON |
| J3-02 | 008 AC-1.1（前段）；028 AC-1.1-1、AC-2.2-1（畫面）；022 AC-1.1-1（畫面） | V-02、K-05、K-34、CL-03 | sitter | 點 `sitter-dashboard-visit-btn-open-{V1}` | URL = `/sitter/orders/{J}/visits/{V1}`；`sitter-visit-panel` 顯示名稱「到府餵食 第 1 次」、日期 `D0`、飼主「飼主J3」、毛孩「咪咪」；`sitter-visit-panel-status`「待執行」；`sitter-visit-panel-btn-start` 可按；`sitter-visit-panel-checklist` 依序 3 個 `sitter-visit-panel-checklist-item-{itemId}`「餵乾糧」「換貓砂」「清水碗」，各 `sitter-visit-panel-checklist-check-{itemId}` 停用並顯示「開始行程後才能打勾」；無 `sitter-care-log-btn-save`、`sitter-care-log-input-text` | F2 200：`status=PENDING`、`dateReached=true`、`zombieDeadlinePassed=false`、`allowedActions` 含 `START`、不含 `CHECK_ITEM` / `EDIT_CARE_LOG` / `SUBMIT_CARE_LOG`；F8 V1 3 項皆 `isCompleted=false`、`createdSource=SYSTEM`（記 `I1`、`I2`、`I3`） | 截圖 + API JSON |
| J3-03 | 009 AC-1.8（`CONFIRMED`）；028 AC-2.3（`PENDING` 行程）；008 AC-3.3（部分） | OC-76、K-06 | owner | 開啟 `/owner/orders/{J}`，展開 V1 行程列 | `owner-order-detail-visit-row-{V1}`、`-{V2}` 皆在；`-visit-status-*`「待執行」；`-visit-started-at-*`、`-visit-completed-at-*`「—」；`owner-order-detail-visit-checklist-{V1}` 內 3 個 `owner-order-detail-visit-checklist-item-{itemId}` 皆 `data-checked=false`；無 `owner-order-detail-btn-view-log-*`；無結案區塊（無 `owner-order-detail-btn-complete`、`-complete-hint`、`-btn-report-issue`） | E2 `status=CONFIRMED`、無 `completionHint`、無 `disputeWindowEndsAt`；F1（OWNER）`visits[].careLog` 只有 `submitted=false` | 截圖 + API JSON |
| J3-04 | 008 AC-1.1；T1、T1a；BR-18 | V-03 | sitter | 回 V1 面板，點 `sitter-visit-panel-btn-start` | `sitter-visit-panel-status`「執行中」並顯示「開始時間 TPE(startedAt)」；出現 `sitter-visit-panel-btn-complete`；檢核項勾選框可操作；出現 `sitter-care-log-input-text`、`sitter-care-log-btn-save`；`sitter-care-log-btn-submit` 停用並顯示「行程結束後才能送出」 | 頁面送出 F3 恰 1 次、帶 `Idempotency-Key`；200 `status=IN_PROGRESS`、`orderStatus=IN_PROGRESS`、`startedAt` 介於 `[t0, t1]`；E2 `status=IN_PROGRESS`；[INS] log +1 `ORDER_STARTED`（`payload.visitId=V1`、`cause=STARTED`、`operatorRole=SITTER`），無 `VISIT_STARTED`；通知 +0（開始行程不通知飼主） | 截圖 + API JSON |
| J3-05 | 009 AC-1.2（行程未結束）；008 AC-3.3（部分）、AC-3.4-1 | OC-01、V-70 | owner | 重新整理 `/owner/orders/{J}`；開啟 `/owner/visits` | 詳情：`-visit-status-{V1}`「執行中」、`-visit-started-at-{V1}` = TPE(startedAt)、V2「待執行」；`owner-order-detail-complete-hint`「所有行程完成且保母送出照護日誌後，即可確認結案」；無確認結案、無回報問題、無 `owner-order-detail-dispute-window-ends-at`。行程頁：依序 `owner-dashboard-visit-card-{V1}`（「保母J3」「到府餵食 第 1 次」「執行中」）、`-{V2}`（「待執行」） | E2 `completionHint.code=VISITS_NOT_FINISHED`、`allowedActions` 不含 `COMPLETE` / `REPORT_SERVICE_ISSUE`；F7 `today` = [V1, V2] | 截圖 ×2 + API JSON |
| J3-06 | 028 AC-2.1-1；BR-11、BR-14 | K-37 | sitter | V1 面板依序點 `sitter-visit-panel-checklist-check-{I1}`、`-{I2}` | I1、I2 已勾，`sitter-visit-panel-checklist-item-{I1}` / `-{I2}` 內出現 `sitter-visit-panel-checklist-checked-at-*` = TPE(checkedAt)；I3 無打勾時間 | 頁面送出 F12 兩次、皆不帶 `Idempotency-Key`、body `checked=true`；200 `isCompleted=true`、`checkedAt` 介於 `[t0, t1]`；[INS] `checklistItems` I1、I2 `updatedBy = sitter.userId`；log +0、通知 +0 | 截圖 + API JSON |
| J3-07 | 022 AC-1.1-4、AC-1.2-1、AC-1.4-1（畫面）；BR-19 | CL-02、CL-06、CL-15 | sitter | 日誌區塊：確認標頭為「到府餵食 第 1 次」；`sitter-care-log-input-text` 輸入「乾乾吃完半碗」，點 `sitter-care-log-btn-save` | `sitter-care-log-text-counter`「6 / 1000」；`sitter-care-log-btn-submit` 仍停用 | F14 200 `{ careLogId, status: "DRAFT", text: "乾乾吃完半碗" }`（記 `careLogId`）；[INS] `careLogs` V1 恰 1 筆 `DRAFT`；log +0、通知 +0（暫存不寫 log、不通知） | 截圖 + API JSON |
| J3-08 | 008 AC-1.5-1（確認框）；028 AC-2.1-2；BR-5 | V-10、K-39 | sitter | 點 `sitter-visit-panel-btn-complete` | 出現 `sitter-visit-panel-complete-dialog`，含「結束後無法再開始此行程。請於結束後 24 小時內送出照護日誌，逾時日誌將無法送出。」與「尚有 1 項未打勾（不影響結束）」 | F2 `checklist.total=3`、`checklist.completed=2`；`careLog.status=DRAFT` | 截圖 |
| J3-09 | 008 AC-1.5-1；T2 | V-11、K-40 | sitter | 點 `sitter-visit-panel-btn-complete-confirm` | `sitter-visit-panel-status`「已完工」；顯示「結束時間 TPE(completedAt)」與「日誌可送出至 TPE(completedAt + 24h)」；`-btn-complete` 消失 | 頁面送出 F4 恰 1 次、帶 `Idempotency-Key`；200 `status=DONE`、`completionSource=SITTER`、`completedAt` 介於 `[t0, t1]`、`careLogDeadlineAt = completedAt + 24h`；E2 仍 `IN_PROGRESS`；[INS] log +1 `VISIT_COMPLETED`（`visitId=V1`、`operatorRole=SITTER`）；`completionBasisAt` 為 null（V2 未結束）；通知 +0 | 截圖 + API JSON |
| J3-10 | 028 AC-2.1-3；022 AC-1.4-2（前段）；BR-5（PRD-022） | K-41、CL-16 | sitter | 檢視 V1 面板；以 API 送 F12（I3，`checked=true`） | 檢核項唯讀（勾選框皆不可操作），I1、I2 仍顯示已勾與打勾時間，I3 未勾；`sitter-care-log-btn-submit` 啟用，`sitter-care-log-deadline`「請於 TPE(completedAt + 24h) 前送出」 | F12 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=DONE`、`completionSource=SITTER`；F8 I3 仍 `isCompleted=false`；F13 V1 `deadlineAt = completedAt + 24h`、`editable=true`、`submittable=true` | 截圖 + API JSON |
| J3-11 | 022 AC-2.5-1、AC-1.3-3；BR-8、BR-28 | CL-09、CL-10 | sitter | 輸入框改為「乾乾吃完一碗，已換貓砂」（**不暫存**）；`UPLOAD-UI(V1, jpg500, "睡午覺")` | 出現 1 個 `sitter-care-log-media-item-{mediaId}`（說明「睡午覺」）；`sitter-care-log-quota`「照片 1 / 10　影片 0 / 2」；`sitter-care-log-input-text` 仍為「乾乾吃完一碗，已換貓砂」 | 頁面依序送出：F16 恰 1 次（帶 `Idempotency-Key`；`file.mediaType=IMAGE`、`file.contentType=image/webp`、`file.sizeBytes < 1048576`、`file.sha256` 為 64 個小寫十六進位、`caption="睡午覺"`）→ 201 `{ uploadId, receivedAt, expiresAt }`；F17 200 `alreadyCompleted=false`、`method=PUT`、`headers.x-goog-content-length-range = {sizeBytes},{sizeBytes}`；PUT 到 `uploadUrl`（host `storage.googleapis.com`）2xx；F18 201 `mediaType=IMAGE`、`caption="睡午覺"`、`careLogId` = J3-07 值、`quota.imageUsed=1`、`imageLimit=10`；[INS] `uploads` 該槽位 `COMPLETED`、`media` V1 1 筆；log +1 `MEDIA_UPLOADED`（`visitId=V1`、`uploadId`、`mediaId`、`objectKey`、`mediaType=IMAGE`、`sizeBytes`、`careLogCreated=false`）；F13 V1 `text` 仍為「乾乾吃完半碗」（未暫存文字不被覆蓋，伺服器也未改） | 截圖 + API JSON |
| J3-12 | 022 AC-3.2-2、AC-3.1-3；BR-16 | CL-28、CL-76、CL-22 | owner | 重新整理 `/owner/orders/{J}`；以 OWNER token 呼叫 F13 V1 | `owner-order-detail-visit-row-{V1}` 內無 `owner-order-detail-btn-view-log-{V1}` | F13 404 `NOT_FOUND`（草稿不給飼主）；[INS] 無 `CARE_LOG_SUBMITTED` 通知（暫存、上傳皆不通知） | 截圖 + API JSON |
| J3-13 | 022 AC-1.4-2、AC-1.5-1（畫面）、AC-3.1-1；L3；BR-17、BR-19 | CL-18 | sitter | V1 面板點 `sitter-care-log-btn-submit` | 送出後面板無 `sitter-care-log-input-text`、`-btn-save`、`-btn-submit`、`-btn-upload`、`-btn-delete-media-*`；顯示文字「乾乾吃完一碗，已換貓砂」與照片 | 頁面送出 F15 恰 1 次（`Idempotency-Key` 記為 K3，body `{ text: "乾乾吃完一碗，已換貓砂" }`）→ 200 `{ careLogId, status: "SUBMITTED", submittedAt }`；[INS] `careLogs` V1 `SUBMITTED`；log +1 `CARE_LOG_SUBMITTED`（`visitId=V1`、`textLength=11`、`imageCount=1`、`videoCount=0`）；通知 +1 `CARE_LOG_SUBMITTED`（`recipientUserId=owner`、`recipientRole=OWNER`、`mandatory=false`、[EMAIL]） | 截圖 + API JSON |
| J3-14 | 022 AC-3.5-1 | CL-19 | sitter | 以 K3 與相同 body 重送 F15 | — | 200，body 與 J3-13 完全相同；[INS] `CARE_LOG_SUBMITTED` log 與通知仍各 1 筆 | API JSON |
| J3-15 | 022 AC-3.1-1；SD-FULFILL §7.2 | CL-18 | owner | [NOTI] 讀 `owner` 通知；開啟現行通知中心頁，點該則通知（元素以業務名稱描述，見 §5 #2） | 導向 `/orders/{J}/visits/{V1}/care-log`，`owner-care-log-view` 可見 | 通知內容「{D0} 到府餵食 第 1 次 的照護日誌已由保母回報，快來看看毛孩的近況吧！」、連結 `/orders/{J}/visits/{V1}/care-log` | 截圖 + API JSON |
| J3-16 | 022 AC-3.2-1；008 BR-14（預設名稱「第 n 次」） | CL-75 | owner | 檢視日誌頁 | `owner-care-log-visit-name`「到府餵食 第 1 次」；文字「乾乾吃完一碗，已換貓砂」；1 張照片已載入（`naturalWidth > 0`）、說明「睡午覺」；送出時間 = TPE(submittedAt)；無任何編輯元素 | F13（OWNER）200：無 `status` / `editable` / `quota` 等保母欄位；`media[0].url` 含 `X-Goog-Expires=900` | 截圖 + API JSON |
| J3-17 | 009 AC-1.8（`IN_PROGRESS`）、AC-1.2；008 AC-3.3（部分）；028 AC-2.3 | OC-03、OC-76、V-74、K-45 | owner | 回 `/owner/orders/{J}`，展開 V1、V2 | V1「已完工」、開始 / 結束時間 = TPE(startedAt) / TPE(completedAt)，有 `owner-order-detail-btn-view-log-{V1}`；V2「待執行」、無查看日誌；`owner-order-detail-visit-checklist-{V1}` 的 I1、I2 `data-checked=true`（打勾圖示）、I3 `data-checked=false`（時鐘圖示）；V2 3 項皆 `data-checked=false`；檢核項區無任何輸入框、按鈕、勾選框；提示仍為「所有行程完成且保母送出照護日誌後，即可確認結案」 | E2 `visits[V1].careLogSubmitted=true`、`completedAt` 同 J3-09；`completionHint.code=VISITS_NOT_FINISHED` | 截圖 + API JSON |
| J3-18 | 008 AC-1.2-1；§4.6-3；BR-2 | V-13 | sitter | 開啟 `/sitter/orders/{J}`，點文字為「到府餵食 第 2 次」的 `sitter-order-visit-label` 行程列（§5 #1）；在面板點 `sitter-visit-panel-btn-start` | URL = `/sitter/orders/{J}/visits/{V2}`；「執行中」 | F3 200 `orderStatus=IN_PROGRESS`；[INS] log +1 `VISIT_STARTED`（`visitId=V2`、`operatorRole=SITTER`），`ORDER_STARTED` 仍只有 J3-04 那 1 列 | 截圖 + API JSON |
| J3-19 | 008 AC-1.5-1；028 AC-2.1-2（n = 3）；PRD-009 BR-4 | V-10、V-11 | sitter | V2 不打勾，點 `-btn-complete` → 確認框 → `-btn-complete-confirm` | 確認框含「尚有 3 項未打勾（不影響結束）」；確認後「已完工」 | F4 200 `status=DONE`；[INS] `completionBasisAt = V2.completedAt + 24h`（= max(V1、V2 `completedAt`) + 24h） | 截圖 + API JSON |
| J3-20 | 009 AC-1.2（日誌未送出）、BR-21、BR-26 iv；009 §4.3-1 按鈕條件 | OC-03、OC-04 | owner、sitter | `owner` 重新整理詳情；`sitter` 開 `/sitter/orders/{J}`；`owner` 以 API 呼叫 E25（新 key） | 飼主：無 `owner-order-detail-btn-complete`；`owner-order-detail-complete-hint`「所有行程完成且保母送出照護日誌後，即可確認結案」；`owner-order-detail-btn-report-issue` 可見；`owner-order-detail-dispute-window-ends-at`「可回報問題至 TPE(disputeWindowEndsAt)」；V2 列無查看日誌。保母：`sitter-order-detail-auto-complete-at` 顯示同一時間 | E2 `completionHint.code=CARE_LOG_PENDING`、`disputeWindowEndsAt = [INS] completionBasisAt + 48h`、`visits[V2].careLogSubmitted=false`、`allowedActions` 含 `REPORT_SERVICE_ISSUE`、不含 `COMPLETE`；E25 409 `VISITS_NOT_FINISHED`；E2 仍 `IN_PROGRESS` | 截圖 ×2 + API JSON |
| J3-21 | 022 L3（日誌不存在直接送出）、AC-1.4-3（近似，非 FREE）、AC-3.1-1 | CL-38 | sitter | V2 面板（從未暫存）輸入「第二次服務完成」，直接點 `sitter-care-log-btn-submit` | 面板轉唯讀 | F15 200 `SUBMITTED`；[INS] `careLogs` V2 `SUBMITTED`；`CARE_LOG_SUBMITTED` log 累計 2、通知累計 2；[NOTI] 新通知內容「{D0} 到府餵食 第 2 次 的照護日誌已由保母回報，快來看看毛孩的近況吧！」 | 截圖 + API JSON |
| J3-22 | 009 AC-1.1（前置）；BR-1、BR-26 v | OC-09 | owner | 重新整理 `/owner/orders/{J}` | `owner-order-detail-btn-complete` 可見；無 `owner-order-detail-complete-hint`；`owner-order-detail-btn-report-issue` 仍可見；V1、V2 皆有查看日誌 | E2 無 `completionHint`（null）、`allowedActions` 含 `COMPLETE` 與 `REPORT_SERVICE_ISSUE` | 截圖 + API JSON |
| J3-23 | 009 AC-1.1；T1；BR-18、BR-20 | OC-10、OC-11 | owner | 點 `owner-order-detail-btn-complete` → 確認框 → `owner-order-complete-btn-confirm` | 確認框文字「確認結案後將無法再回報服務問題，確定要結案嗎？」；送出後狀態「已結案」、`owner-order-detail-completed-at` = TPE(completedAt) | 頁面送出 E25 恰 1 次、帶 `Idempotency-Key`、無 body；200 `{ orderId: J, status: "COMPLETED", completedAt }`；`completedAt < V2.completedAt + 24h`（不需等日誌期）；[INS] log +1 `ORDER_COMPLETED`（`operatorRole=OWNER`、`payload.completedBy=OWNER`）；通知 +1 `ORDER_COMPLETED_BY_OWNER`（`recipientUserId=sitter`、`SITTER`、`mandatory=false`、[EMAIL]）；無 `ORDER_AUTO_COMPLETED`；無收件者為 `owner` 或 ADMIN 的結案通知 | 截圖 ×2 + API JSON |
| J3-24 | 009 AC-1.7、AC-1.8（`COMPLETED`）；022 AC-3.2-5（`COMPLETED`） | OC-14、OC-15、OC-16、CL-67 | owner、sitter | `owner`：詳情點 `owner-order-detail-btn-view-log-{V2}`；回 `/owner/orders` 點 `owner-orders-tab-history`、再點 `owner-orders-tab-active`。`sitter`：開 `/sitter/orders/{J}` | 飼主：「已結案」；V1、V2 列與查看日誌照常，V2 日誌頁顯示「第二次服務完成」；無 `-btn-complete`、`-btn-report-issue`、`owner-order-detail-btn-modify`、付款元件；歷史分頁有 `owner-order-card-{J}`、進行中分頁沒有。保母：「已結案」、`sitter-order-detail-completed-at` 與飼主同文字；無任何操作按鈕、無 `sitter-order-detail-auto-complete-at` | E3 `tab=HISTORY` 含 J、`tab=ACTIVE` 不含 | 截圖 ×4 + API JSON |
| J3-25 | 022 AC-1.8-4；009 AC-1.4；028 EX-7 | CL-68、OC-13 | sitter、owner | `sitter`：F14 V1（`text="結案後補寫"`）、F12（V2 的 `I1`，`checked=true`）；`owner`：E25（新 key） | — | 三者皆 409 `ORDER_STATE_CONFLICT`、`currentStatus=COMPLETED`（F14 不是 `CARE_LOG_STATE_CONFLICT`）；E2 `completedAt` 同 J3-23；[INS] log、通知筆數與 J3-23 後相同 | API JSON |
| J3-26 | 008 BR-18；022 BR-17、BR-19；009 BR-20；028 BR-11 | — | system | [INS] 訂單 `J` | — | 業務 log 依 `createdAt` 恰為：`ORDER_STARTED`（V1）→ `VISIT_COMPLETED`（V1）→ `MEDIA_UPLOADED`（V1）→ `CARE_LOG_SUBMITTED`（V1）→ `VISIT_STARTED`（V2）→ `VISIT_COMPLETED`（V2）→ `CARE_LOG_SUBMITTED`（V2）→ `ORDER_COMPLETED`，各 1 列，無任何檢核項、暫存相關列；J3-04 之後的通知恰為 `CARE_LOG_SUBMITTED` ×2（OWNER）、`ORDER_COMPLETED_BY_OWNER` ×1（SITTER）；`fulfill.visits` V1、V2 皆 `DONE`、`completionSource=SITTER`；`careLogs` 皆 `SUBMITTED`；`media` 1 筆；`uploads` 1 筆 `COMPLETED` | API JSON |

### 2.2 分支：飼主回報問題（O13 → `DISPUTED`，訂單 `JB`）

| 步驟 | AC | 模組 TS | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|--------|------|------|---------|---------------|------|
| J3-30 | 前置；009 BR-5（不看日誌） | OC-03 | sitter | 以 API 走正式端點（主線已驗 UI）：F3 VB1（O9）→ F4 VB1 → F14 VB1（`text="草稿未送出"`）→ F3 VB2 → F4 VB2 → F15 VB2（`text="VB2 已送出"`） | — | 各步 2xx；E2 `status=IN_PROGRESS`、`completionHint.code=CARE_LOG_PENDING`、`allowedActions` 含 `REPORT_SERVICE_ISSUE` | API JSON |
| J3-31 | 009 AC-2.5；T3；BR-6 | OC-40 | owner | 開 `/owner/orders/{JB}`，點 `owner-order-detail-btn-report-issue`，於 `owner-order-dispute-input-description` 輸入「保母未依約餵藥」，點 `owner-order-dispute-btn-submit` | 表單只有說明欄（無問題類別選項、表單內無 `input[type=file]`）；`owner-order-dispute-description-counter` 剩餘 493；送出後 `owner-order-detail-disputed-banner`「訂單爭議處理中，請等候平台處理」；確認結案、回報問題按鈕消失；V2 查看日誌照常 | 頁面送出 E26 恰 1 次、帶 `Idempotency-Key`、body 只有 `description`；200 `{ orderId: JB, status: "DISPUTED", adminTaskId }` | 截圖 ×2 + API JSON |
| J3-32 | 009 AC-2.5；BR-6、BR-20 | OC-42、OC-43 | admin、system | `admin` 呼叫 E28；[INS] 訂單 `JB` | — | E28 含 `taskId=adminTaskId`、`type=SERVICE_DISPUTE`、`orderId=JB`、`status=OPEN`；[INS] `adminTasks` 恰 1 筆同值；log +1 `SERVICE_DISPUTE_OPENED`（`operatorRole=OWNER`、`payload.description="保母未依約餵藥"`、`payload.adminTaskId`）；通知 +1 `SERVICE_DISPUTE_OPENED`（`recipientUserId=sitter`、[EMAIL]），無 `owner` 或 ADMIN 收件者 | API JSON |
| J3-33 | 009 §4.4（`DISPUTED` 列） | OC-41 | sitter | 開 `/sitter/orders/{JB}` | `sitter-order-detail-disputed-banner`「訂單爭議處理中，請等候平台處理」；無 `sitter-order-detail-auto-complete-at` | E2（SITTER）`status=DISPUTED` | 截圖 + API JSON |
| J3-34 | 022 AC-1.8-1；009 BR-7；008 BR-7 | CL-64 | sitter | 開 VB1 面板（`DISPUTED` 期間），輸入框改為「爭議中補寫完成」，點 `sitter-care-log-btn-submit` | 面板轉唯讀 | F15 200 `SUBMITTED`；[INS] `careLogs` VB1 `SUBMITTED`；通知 +1 `CARE_LOG_SUBMITTED`（OWNER） | 截圖 + API JSON |
| J3-35 | 022 AC-3.2-5（`DISPUTED`）；009 AC-1.8（`DISPUTED`） | CL-65、OC-76 | owner | 重新整理 `/owner/orders/{JB}`，點 `owner-order-detail-btn-view-log-{VB1}` | 爭議橫幅仍在；VB1、VB2 皆有查看日誌；VB1 日誌頁顯示「爭議中補寫完成」 | — | 截圖 ×2 |
| J3-36 | 022 AC-3.4-1；008 AC-3.5-4（前半） | CL-63、V-58 | admin | `admin` 呼叫 F1、F13 VB1；開 `/orders/{JB}/visits/{VB1}/care-log` | `owner-care-log-view` 顯示 VB1 文字；無任何編輯元素 | F1 200（`careLog` 只含 `submitted`）；F13 200 | 截圖 + API JSON |
| J3-37 | 009 AC-2.5（之後 C8 不再結案）、AC-2.3 (a)；BR-7 | OC-48 | system | `SHIFT-WINDOW(JB, −60 分)` → C8（`orderId=JB`） | — | C8 200，計數 0 / 0 / 0；E2 仍 `DISPUTED`；[INS] 無 `ORDER_COMPLETED`、無 `ORDER_AUTO_COMPLETED` 通知 | API JSON |
| J3-38 | 009 AC-2.8（新 key 再送）、AC-1.4（`DISPUTED`） | OC-46 | owner | E26（新 key，`description="再回報"`）；E25（新 key） | — | 皆 409 `ORDER_STATE_CONFLICT`、`currentStatus=DISPUTED`；[INS] `adminTasks` 仍 1 筆 `OPEN` | API JSON |

### 2.3 分支：無人操作自動結案（O12，日誌逾期未送出，訂單 `JC`）

| 步驟 | AC | 模組 TS | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|--------|------|------|---------|---------------|------|
| J3-40 | 前置；022 L4（逾期為衍生值） | OC-36、CL-26 | sitter、system | 以 API：F3 VC1 → F4 VC1 → F15 VC1（`text="VC1 已送出"`）→ F3 VC2 → F4 VC2 → F14 VC2（`text="VC2 草稿未送出"`）→ FT2 VC2 `target=COMPLETED_AT`、`minutes=-1441` | — | F13 VC2（SITTER）`expired=true`、`editable=false`、`submittable=false`；[INS] `careLogs` VC2 仍 `DRAFT`；`completionBasisAt = VC1.completedAt + 24h`（VC2 平移後較早）；E2 `completionHint.code=CARE_LOG_EXPIRED`、`completionHint.autoCompleteAt = disputeWindowEndsAt = completionBasisAt + 48h` | API JSON |
| J3-41 | 009 AC-2.2；BR-21、BR-26 iii | OC-36、OC-37 | owner、sitter | `owner` 開 `/owner/orders/{JC}`；`sitter` 開 `/sitter/orders/{JC}` | 飼主：無 `owner-order-detail-btn-complete`；`owner-order-detail-complete-hint`「保母未於期限內送出照護日誌，訂單將於 TPE(autoCompleteAt) 自動結案」；`owner-order-detail-btn-report-issue` 可見；`owner-order-detail-dispute-window-ends-at` 時間與提示相同；VC1 有、VC2 無查看日誌。保母：`sitter-order-detail-auto-complete-at` 顯示同一時間 | — | 截圖 ×2 |
| J3-42 | 022 AC-1.7-1、AC-1.6-2（近似）；EX-9 | CL-27、CL-26 | sitter | 開 VC2 面板；以 API 送 F15 VC2（新 key，`text="補送"`） | `sitter-care-log-status`「已逾期，未送出」；顯示行程名稱「到府餵食 第 2 次」與文字「VC2 草稿未送出」；無任何輸入與按鈕 | F15 409 `DEADLINE_PASSED`、`deadline=CARE_LOG_EDIT_ENDS_AT`、`deadlineAt` = F13 VC2 `deadlineAt`；[INS] VC2 仍 `DRAFT` | 截圖 + API JSON |
| J3-43 | 009 AC-2.2；EX-7 | OC-38 | owner | E25（新 key） | — | 409 `VISITS_NOT_FINISHED` | API JSON |
| J3-44 | 009 AC-2.1（未到期） | OC-30 | system | `SHIFT-WINDOW(JC, +10 分)` → C8（`orderId=JC`） | — | 計數 0 / 0 / 0；E2 仍 `IN_PROGRESS`；[INS] 無 `ORDER_COMPLETED` | API JSON |
| J3-45 | 009 AC-2.6（視窗已過、排程未跑）、BR-21（CQ-15）；EX-8 | OC-31、OC-39、OC-53 | owner、sitter | `SHIFT-WINDOW(JC, −1 分)`（不跑 C8）；雙方重新整理詳情；`owner` E26（新 key，`description="逾期回報"`） | 飼主：`owner-order-detail-complete-hint` 與 `owner-order-detail-dispute-window-ends-at` 皆「即將自動結案」；無 `owner-order-detail-btn-report-issue`。保母：`sitter-order-detail-auto-complete-at`「即將自動結案」 | E2 `serverTime ≥ disputeWindowEndsAt`、`allowedActions` 不含 `REPORT_SERVICE_ISSUE`；E26 409 `DEADLINE_PASSED`、`deadline=DISPUTE_WINDOW_ENDS_AT`、`deadlineAt = [INS] completionBasisAt + 48h`；E2 仍 `IN_PROGRESS`；[INS] `adminTasks = []` | 截圖 ×2 + API JSON |
| J3-46 | 009 AC-2.1、AC-2.2；T2；BR-3、BR-20 | OC-32、OC-39 | system、owner | C8（`orderId=JC`）；`owner` 重新整理詳情 | 「已結案」 | C8 200，計數 1 / 0 / 0；E2 `status=COMPLETED`、`completedAt` 介於 C8 回應 `startedAt` 與 `finishedAt`；[INS] log +1 `ORDER_COMPLETED`（`operatorRole=SYSTEM`、`payload.completedBy=SYSTEM`）；通知 +2 `ORDER_AUTO_COMPLETED`（`owner` / OWNER 與 `sitter` / SITTER 各 1，皆 `mandatory=false`、[EMAIL]），無 `ORDER_COMPLETED_BY_OWNER`、無 ADMIN 收件者；`careLogs` VC2 仍 `DRAFT`（自動結案不看日誌） | 截圖 + API JSON |
| J3-47 | 009 AC-2.4（重跑） | OC-34 | system | 再呼叫 C8（`orderId=JC`） | — | 計數 0 / 0 / 0；`completedAt` 不變；[INS] `ORDER_COMPLETED` 仍 1 筆、`ORDER_AUTO_COMPLETED` 仍 2 筆 | API JSON |
| J3-48 | 022 AC-1.8-5、AC-1.7-2；009 AC-1.7 | CL-69、CL-28 | sitter、owner | `sitter` F14 VC2（`text="結案後補寫"`）；`owner` 開詳情、以 OWNER token 呼叫 F13 VC2 | 飼主：「已結案」；VC1 有、VC2 無查看日誌 | F14 409 `ORDER_STATE_CONFLICT`（`currentStatus=COMPLETED`，不是 `DEADLINE_PASSED`）；F13 404 `NOT_FOUND`；[INS] `CARE_LOG_SUBMITTED` 通知只有 VC1 那 1 筆 | 截圖 + API JSON |

### 2.4 分支：系統出口 — 殭屍行程關閉（C7）與忘記結束的逾時出口（FC1），訂單仍能結案（訂單 `JD`）

| 步驟 | AC | 模組 TS | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|--------|------|------|---------|---------------|------|
| J3-50 | 008 AC-3.2；BR-15、BR-16 | V-66 | sitter | 開 `/sitter/visits` | `sitter-dashboard-section-today` 無卡片；`sitter-dashboard-section-overdue` 依序 `sitter-dashboard-visit-card-{VD1}`、`-{VD2}`，皆「待執行」；VD1 卡「系統將於 TPE(ZA(D-3)) 關閉」、VD2 卡「系統將於 TPE(ZA(D-2)) 關閉」 | F6 `today=[]`、`overdue` = [VD1, VD2]；`zombieAt` 分別 = ZA(D-3)、ZA(D-2) | 截圖 + API JSON |
| J3-51 | 008 AC-1.3-3、AC-1.3-4；BR-4；EX-9 | V-19、V-20 | sitter | 點 `sitter-dashboard-visit-btn-open-{VD1}`；以 API 送 F3 VD1（新 key） | `sitter-visit-panel-btn-start` 停用；`sitter-visit-panel-deadline-passed-message`「此行程已超過可開始期限，系統將自動關閉」 | F2 `zombieDeadlinePassed=true`、`allowedActions` 不含 `START`；F3 409 `DEADLINE_PASSED`、`deadline=VISIT_ZOMBIE_AT`、`deadlineAt = ZA(D-3)`；E2 仍 `CONFIRMED` | 截圖 + API JSON |
| J3-52 | 008 AC-2.1；T3、T3a；BR-11、BR-18 | V-31 | system | C7（`orderId=JD`） | — | 200，計數 1 / 0 / 0；[INS] VD1 `CLOSED_BY_SYSTEM`、`closedAt` 介於 `[t0, t1]`；VD2 仍 `PENDING`；`status=IN_PROGRESS`；log +1 `VISITS_CLOSED_BY_SYSTEM`（`payload.visitIds=[VD1]`）、+1 `ORDER_STARTED`（`operatorRole=SYSTEM`、`visitId=VD1`、`cause=CLOSED_BY_SYSTEM`）；通知 +2：`VISIT_CLOSED_BY_SYSTEM_SITTER`（SITTER）、`VISIT_CLOSED_BY_SYSTEM_OWNER`（OWNER），皆 `mandatory=false`、[EMAIL]；[NOTI] `sitter`「{D-3} 到府餵食 未於期限內開始，已由系統關閉」、連結 `/sitter/orders/{JD}`；`owner`「保母未於期限內開始 {D-3} 到府餵食，系統已關閉此行程」、連結 `/owner/orders/{JD}` | API JSON |
| J3-53 | 008 AC-3.3（系統結案列）；009 AC-1.2 | V-74、OC-01 | owner | 開 `/owner/orders/{JD}` | `-visit-status-{VD1}`「系統結案」、VD2「待執行」；`owner-order-detail-complete-hint`「所有行程完成且保母送出照護日誌後，即可確認結案」 | E2 `completionHint.code=VISITS_NOT_FINISHED` | 截圖 + API JSON |
| J3-54 | 008 AC-1.3-2、AC-3.2（關閉後消失）；EX-14（循序）；BR-3 | V-22、V-35、V-69 | sitter | 重新整理 `/sitter/visits`；點 `sitter-dashboard-visit-btn-open-{VD2}` → `sitter-visit-panel-btn-start` | 待處理區只剩 VD2；面板「執行中」 | F6 `overdue = [VD2]`；F3 200 `orderStatus=IN_PROGRESS`；[INS] log +1 `VISIT_STARTED`（`visitId=VD2`），`ORDER_STARTED` 仍只有 J3-52 的 SYSTEM 那 1 列 | 截圖 + API JSON |
| J3-55 | 008 AC-2.3；T4；BR-12、BR-18 | V-38、V-39 | sitter、system | VD2 面板保持開啟、不重新整理；FT2 VD2 `target=STARTED_AT`、`minutes=-1450`；FC1（`orderId=JD`） | — | FC1 200，計數 1 / 0 / 0；[INS] VD2 `DONE`、`completionSource=SYSTEM`、`completedAt` 介於 `[t0, t1]`；log +1 `VISIT_AUTO_COMPLETED`（`operatorRole=SYSTEM`、`visitId=VD2`、`startedAt`、`completedAt`）；通知 +1 `VISIT_AUTO_COMPLETED`（SITTER、`mandatory=false`），無 OWNER 新列；`completionBasisAt = VD2.completedAt + 24h`（晚於 VD1 `closedAt`）；[NOTI] `sitter`「{D-2} 到府餵食 開始後超過 24 小時未結束，系統已自動結束。請於 TPE(completedAt + 24h) 前送出照護日誌」、連結 `/sitter/orders/{JD}/visits/{VD2}` | API JSON |
| J3-56 | 008 AC-2.4-3；EX-13（逾時先）；EX-7 | V-40 | sitter | 在未重新整理的 VD2 面板點 `-btn-complete` → `-btn-complete-confirm` | 顯示「此行程已由系統自動結束」 | 頁面送出的 F4 回 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=DONE`、`completionSource=SYSTEM`；點擊到文案出現之間頁面未送出 F2 | 截圖 + API JSON |
| J3-57 | 008 AC-2.3（面板標示）、§4.4-4；022 AC-1.6-4（近似）、BR-27 | V-41、CL-30、CL-31 | sitter | 重新整理 VD2 面板；輸入「忘記按結束，補送日誌」，點 `sitter-care-log-btn-submit` | 「已完工」與 `sitter-visit-panel-auto-completed-label`「系統自動結束」；`sitter-care-log-deadline`「請於 TPE(completedAt + 24h) 前送出」；送出後轉唯讀 | F13 VD2 `deadlineAt = completedAt + 24h`、`submittable=true`；F15 200 `SUBMITTED` | 截圖 + API JSON |
| J3-58 | 008 AC-3.3（系統自動結束列）；009 AC-1.1（含 `CLOSED_BY_SYSTEM`）、BR-1 | V-42、V-74、OC-09 | owner | 重新整理 `/owner/orders/{JD}` | VD1「系統結案」；VD2「已完工」並有 `owner-order-detail-visit-auto-completed-{VD2}`「系統自動結束」、有查看日誌；`owner-order-detail-btn-complete` 可見、無提示 | E2 無 `completionHint`、`allowedActions` 含 `COMPLETE` | 截圖 + API JSON |
| J3-59 | 009 AC-1.1 | OC-10 | owner | 點 `owner-order-detail-btn-complete` → `owner-order-complete-btn-confirm` | 「已結案」 | E25 200 `COMPLETED`；[INS] log +1 `ORDER_COMPLETED`（OWNER）；通知 +1 `ORDER_COMPLETED_BY_OWNER`（SITTER） | 截圖 + API JSON |
| J3-60 | 008 AC-2.6-3；BR-13、BR-18 | V-32、V-43 | system | 再呼叫 C7、FC1（`orderId=JD`）；[INS] 訂單 `JD` | — | C7、FC1 皆計數 0 / 0 / 0；業務 log 依序為 {`VISITS_CLOSED_BY_SYSTEM`、`ORDER_STARTED`（SYSTEM）}（同一交易，兩者順序不拘）→ `VISIT_STARTED`（VD2）→ `VISIT_AUTO_COMPLETED`（VD2）→ `CARE_LOG_SUBMITTED`（VD2）→ `ORDER_COMPLETED`；通知恰為 `VISIT_CLOSED_BY_SYSTEM_SITTER`、`VISIT_CLOSED_BY_SYSTEM_OWNER`、`VISIT_AUTO_COMPLETED`、`CARE_LOG_SUBMITTED`、`ORDER_COMPLETED_BY_OWNER` 各 1 | API JSON |

## 3. 權限與例外

> 旅程層只抽查跨角色的關鍵越權，越權一律以另一個真實帳號對正式站打 API；完整矩陣見模組 TS（TS-008 §3、TS-028 §3、TS-022 §3、TS-009 §3）。
> 執行時機：J3-E1–J3-E5 於主線 J3-17 之後、J3-18 之前執行（訂單 `J` 為 `IN_PROGRESS`、V1 日誌已送出、V2 `PENDING`），每步後以 [INS] 確認訂單 `J` 的行程、日誌、檢核項、log 筆數不變。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| J3-E1 | 008 AC-1.6-1；028 AC-1.6-1；022 AC-3.3-2（TS-008 §3、TS-028 §3、CL-78） | `sitterB` 對訂單 `J` V2 送 F3（新 key）、F12（V2 的 `I1`）、F14 | 皆 403 `FORBIDDEN`；V2 仍 `PENDING` |
| J3-E2 | 022 AC-3.3-3；009 AC-1.5、EX-1（CL-78、OC-17） | `ownerB` 呼叫 F13 V1、E25 訂單 `J`（新 key） | 皆 403 `FORBIDDEN` |
| J3-E3 | 008 AC-1.6-2；022 AC-3.3-4；009 AC-1.5、AC-2.8、EX-2（OC-18、OC-50） | `owner`（`currentRole=OWNER`）呼叫 F3 V2、F15 V1；`sitter`（`currentRole=SITTER`）呼叫 E25、E26 訂單 `J` | 皆 403 `ROLE_MISMATCH` |
| J3-E4 | 022 AC-3.3-1；009 AC-1.5 | 不帶 token 呼叫 F13 V1、E25 訂單 `J` | 皆 401 `UNAUTHENTICATED` |
| J3-E5 | 008 AC-3.5-4（後半）；022 AC-3.4-2（V-58、CL-80） | `admin` 呼叫 F1、F13 V1（訂單 `J` 為 `IN_PROGRESS`、無 OPEN 待辦） | 皆 403 `FORBIDDEN` |
| J3-E6 | 008 AC-2.6-4、EX-21；009 AC-2.4（OC-35） | 以 `owner` token、不帶 `X-Internal-Secret` 呼叫 C7、FC1、C8（`orderId=J`） | 皆 403 `FORBIDDEN`；訂單 `J` 不變 |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| 各 PRD 的分鐘 / 秒級邊界（殭屍 `now = ZA`、逾時 `started_at + 24h`、日誌 `completed_at + 24h`、爭議視窗 `completion_basis_at + 48h`） | 正式站不能注入 `Clock`；本旅程只驗期限前後兩側 | 模組 TS §4 所列後端整合測試（`VisitStartIntegrationTest`、`InProgressTimeoutCronIntegrationTest`、`VisitCareLogDeadlineIntegrationTest`、`CompletionIntegrationTest` 等） |
| 跨角色真併發（開始 vs 同意變更、結束 vs FC1、送出 vs E25 / E29、O11 vs C8、O13 vs C8）、`ORDER_BUSY` | 正式站無法穩定造出；本旅程只走循序版（J3-56 為 FC1 先、保母後） | `VisitConcurrencyIntegrationTest`、`VisitCareLogConcurrencyIntegrationTest`、`CompletionIntegrationTest`；TS-008 / 022 / 009 循序步驟 |
| 真實 Email 寄送與內容 | Resend 測試模式只寄專案擁有者信箱；本旅程只驗 `[EMAIL]` 投遞紀錄 | 手動驗證（以專案擁有者信箱為收件者的帳號另行觸發） |
| GCS 物件實際存在 / 刪除、簽名網址 15 分鐘過期 | 無對外 API；E2E 只驗 PUT 成功、槽位 `COMPLETED`、網址含 `X-Goog-Expires=900` | `VisitCareLogMediaUploadIntegrationTest`、`VisitCareLogReadIntegrationTest`、`ServiceReportUploadOrphanCronIntegrationTest` |
| 排程實際時間推移（Cloud Scheduler 每 15 分自動觸發） | E2E 以帶 `orderId` 的手動觸發取代；自動觸發跳過測試帳號訂單 | `InternalTestDataIntegrationTest`、各排程整合測試 |
| PRD-008 AC-1.4、1.7、1.8、2.2、2.5、3.6、3.7；PRD-028 AC-1.2–1.5、2.4；PRD-022 AC-1.3-1/2、2.1–2.4、2.6–2.8、3.6–3.8；PRD-009 AC-1.3、1.6、2.7、3.1–3.8 | 屬單一模組細項，旅程不重複 | TS-008、TS-028、TS-022、TS-009 |

## 5. 已知限制 / 待補（撰寫時發現的問題，未改 PRD / SD）

| # | 項目 | 影響 | 建議 |
|---|------|------|------|
| 1 | 保母訂單詳情行程列 testid `sitter-order-visit-label` / `-date` / `-status`（PRD-008 §4.6-3、SD-FULFILL §9.1）沒有 `{visitId}` 後綴，同訂單多個行程時無法以 testid 唯一定位 | J3-18 只能以「testid + 文字」或索引定位第 2 列，行程名稱相同（例如未命名的每日 1 次多日行程）時會失準 | SD-FULFILL §9.1 改為 `sitter-order-visit-row-{visitId}` / `-label-{visitId}` 等（需使用者同意修訂 Approved SD） |
| 2 | 站內通知中心的畫面路由與 testid 未定義（SD-NOTIFY 未重建），通知文案也不在 inspect 內 | J3-15 點通知以業務名稱描述；文案經現行 `GET /api/notifications?role=…`（同 TS-008 L-11） | SD-NOTIFY 重建時定義通知中心 testid 與列表 API，再回寫本 TS |
| 3 | TS-009 / TS-022 的 `SHIFT-WINDOW` 以 E2 `disputeWindowEndsAt` 計算位移，但 SD-ORDER E2 規定 `DISPUTED` 時不回此欄（只在 `IN_PROGRESS` 且行程皆結束時回傳），TS-009 OC-48 對 `DISPUTED` 訂單無法依定義計算 | 本旅程改以 `[INS] completionBasisAt` 計算（J3-37 對 `DISPUTED` 訂單） | TS-009 / TS-022 的 `SHIFT-WINDOW` 定義同步改用 inspect `completionBasisAt` |
| 4 | Email 投遞狀態的終值不確定：SD-ORDER §10.4 寫 C11「同規則」（自動觸發跳過測試帳號），但 C11 屬 SD-NOTIFY（未重建），是否以收件者網域跳過未明寫；若 C11 會處理測試帳號，Resend 測試模式對非擁有者信箱回 4xx → `FAILED` | `[EMAIL]` 只能斷言「已建立投遞」（`PENDING` / `SENT` / `FAILED`），無法精確斷言 `PENDING` | SD-NOTIFY 重建時明寫 C11 自動觸發是否跳過 `@e2e-journey.test` 收件者；確定後把 `[EMAIL]` 收斂為 `PENDING` |
| 5 | PRD-008 §4.5-2 規定「待處理」區 `PENDING` 行程顯示「系統將於 {關閉時刻} 關閉」，但已過殭屍期限、排程尚未處理的行程（J3-50 的 VD1）會顯示過去的時間，與服務面板「此行程已超過可開始期限，系統將自動關閉」（BR-4）口徑不一致 | J3-50 依 PRD 字面斷言過去時間 | 列為 PRD-008 待釐清：建議預設值為「`now ≥ zombieAt` 時卡片改顯示『已超過可開始期限，系統將自動關閉』」，需使用者決定 |
| 6 | PRD-022 AC-1.4 情境 3（從未暫存直接送出）只寫 FREE 快照；非 FREE 的直接送出由 §5 L3 推得 | J3-21 標「近似」 | 無需修改，僅註記 |

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依 PRD-008 / 028 / 022 / 009（Approved）、SD-FULFILL、SD-ORDER、SD-FRONTEND-SPEC 全文重寫為新範本，狀態 Draft。主線改為正式端點走完「開始 → SOP 打勾 → 結束 → 直傳照片 → 送出日誌 → 飼主查看 → 第二行程 → O11」；新增分支 O13（`JB`）、無人操作 O12（`JC`）、系統出口 C7 + FC1（`JD`）；斷言改以 inspect；排程帶 `orderId`；步驟前綴 `J3-`（J3-01–J3-60、J3-E1–J3-E6）；自動化路徑改為 `service-execution-completion.spec.ts`。舊版（Check-in / Check-out、`/api/visits/{visitId}/start`、`/visit-reports/*` 路由、`trackOrderActionCalls`）作廢；舊版「本測試發現並修復的真實 bug」屬舊實作（`StatusBadge` 英文狀態、`isServiceStage` 遺漏），保留於 git 歷史 | 全面重建 2026-11；上游 PRD / SD 核准 |
