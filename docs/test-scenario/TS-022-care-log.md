# TS-022: 行程照護日誌與多媒體回報

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-022（Approved 2026-11-22，直傳修訂後再核准，含核准後回寫）；前置資料另用到 PRD-008（行程、命名、逾時轉 `DONE`）、PRD-009（O11 / O12 / O13）、PRD-020（ADMIN 讀取與 O15 調解） |
| 對應 SD | SD-FULFILL §1.3、§2.3.3、§3.2、§3.3（F18 失敗類別表）、§4.4、§4.5、§6.3（F1、F5、F13–F19、FC1、FT1、FT2）、§6.5、§7.1、§7.2、§9.1、§9.3、§10.1、§10.2；SD-ORDER E2、E25、E26、E29、C8、§10.4（`inspect`、provision、setup） |
| 類型 | 旅程（打真後端、正式站、真 GCS 直傳） |
| 自動化路徑 | `frontend/e2e/journeys/care-log.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> 取代 `TS-022-visit-report.md`（舊 SD-022 版本）。TS 只依 PRD 與 SD 撰寫，未參考程式碼。
> 日誌寫入涉及訂單鎖、狀態轉移、冪等、跨角色（O11 / O13 / O15）、真 GCS 簽名直傳，整份為旅程型。
> 正式站無法注入時鐘：期限類以 FT1 / FT2 把 `completed_at` 平移到「相對伺服器現在」的位置，不斷言 AC 範例的絕對時刻；秒級邊界與 30 分鐘槽位窗列 §4。
> PRD「申請上傳」= SD-FULFILL F16（預檢、建槽位）+ F17（取簽名網址）；「確認」= F18（SD-FULFILL §4.4）。
> 排程（FC1、C8）一律帶 `?orderId=`，計數只算該筆，斷言精確計數；自動觸發跳過測試帳號訂單。
> `order_logs`、通知（含 `emailStatus`）、行程 / 日誌 / 媒體 / 上傳槽位狀態一律以 SD-ORDER §10.4 `inspect`（含 SD-FULFILL §10.2 `fulfill` 區塊）斷言，下稱 `INSPECT`。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 自己 provision 帳號與訂單；行程 id 由 F1 `GET /api/orders/{orderId}/visits` 取得（依 `serviceDate` 排序，下稱 V1、V2…）。路徑前綴 `…/` = `/api/orders/{orderId}/visits/{visitId}`。

### 1.1 帳號

> 全部以 SD-ORDER §10.4 `provision-account`（`@e2e-journey.test`、`displayName`、`roles[]`）建立；`owner-setup` / `sitter-setup` 為 upsert（只覆寫出現欄位），報價後改 `tier` / `subscriptionExpiresAt` 以 upsert 進行。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `O1` | OWNER | `roles=[OWNER]`；`owner-setup`：寵物 1 隻、`emailPreferences.ORDER_AFFAIR = 開` | — |
| `O2` | OWNER | `roles=[OWNER]`；寵物 1 隻（他人飼主） | — |
| `S1` | OWNER + SITTER | `roles=[OWNER, SITTER]`（雙角色，供 F13 已知邊界）；`sitter-setup`：`tier=BASIC`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄、`plans=[{ key: "feed", 標題 "到府餵食", 單價 500 }]` | — |
| `S2` | SITTER | `roles=[SITTER]`，同 `S1` 設定（他人保母，另有自己的訂單 `B2`） | — |
| `SF` | SITTER | 同 `S1` 但 `roles=[SITTER]`、`tier=FREE`；報價後 upsert `tier=ULTIMATE`（AC-2.1） | — |
| `SP` | SITTER | `tier=PRO` | — |
| `SPD` | SITTER | `tier=PRO`；報價後 upsert `tier=FREE`（AC-2.2 情境 2） | — |
| `SU` | SITTER | `tier=ULTIMATE` | — |
| `SL` | SITTER | `tier=BASIC`；訂單 `CONFIRMED` 後 upsert `subscriptionExpiresAt` = 伺服器現在 − 2 天（鎖定，AC-1.8 情境 2） | — |
| `admin` | ADMIN | `roles=[ADMIN]` | — |

### 1.2 造單與工具程序

| 程序 | 步驟 | 結果 |
|------|------|------|
| `MK-CONFIRMED(owner, sitter, n)` | owner E1 `POST /api/orders`（方案 `feed`、n 個未來日期、每日 1 次）→ sitter E6 原價報價（媒體額度於此快照）→ owner E11 上傳付款憑證 → sitter E14 核對 | 訂單 `CONFIRMED`、n 個 `PENDING` 行程 |
| `START(Vx)` | FT1 `POST /api/internal/test-data/visits/{Vx}/force-status`（`status=IN_PROGRESS`）；第一個行程會觸發 O9 | 行程 `IN_PROGRESS`、訂單 `IN_PROGRESS` |
| `FINISH(Vx, completedAt)` | FT1 `status=DONE`、`completedAt`（未帶 = 伺服器現在）；`PENDING → DONE` 亦允許 | 行程 `DONE`、`completionSource=SITTER` |
| `EXPIRE(Vx)` | FT2 `POST /api/internal/test-data/visits/{Vx}/shift-time`（`target=COMPLETED_AT`、`minutes` 使 `completed_at + 24h` 早於伺服器現在 ≥ 1 分鐘）；訂單 `DISPUTED` 時亦可 | 編輯期限已過（衍生，不寫狀態） |
| `UPLOAD(Vx, file, caption, key)` | S F16 `POST …/care-log/media-uploads`（`Idempotency-Key`=key；`file.mediaType`、`contentType`、`sizeBytes`、`sha256`、影片 `durationSeconds`；`caption`）→ F17 `GET …/care-log/media-uploads/{uploadId}/target` → 以回應的 `uploadUrl` 與 `headers` PUT 檔案到 GCS → F18 `POST …/care-log/media-uploads/{uploadId}/complete` | 預期 F16 201、F17 200、PUT 200、F18 201 |
| `SHIFT-WINDOW(order, Δ)` | 同 TS-009：SD-ORDER `shift-time COMPLETION_BASIS_AT`，使 E2 `disputeWindowEndsAt = serverTime + Δ`（在最後一次 FT1 / FT2 之後呼叫） | — |
| `INSPECT(order)` | `GET /api/internal/test-data/orders/{order}/inspect`（`X-Internal-Secret`） | `orderLogs[]`、`notifications[]`、`fulfill.visits / careLogs / media / uploads` |

### 1.3 測試檔案（fixture，由 e2e-author 放在 `frontend/e2e/fixtures/care-log/`，SHA-256 於執行時計算）

| 代號 | 內容 |
|------|------|
| `jpg900`、`png900`、`webp900` | 約 900 KB 的 JPEG / PNG / WebP |
| `webp800`、`jpg500` | 約 800 KB WebP、約 500 KB JPEG |
| `jpg1048575` | 恰 1,048,575 bytes 的合法 JPEG |
| `jpg4m` | 約 4 MB、長邊 > 2048 px 的相機 JPEG（前端縮圖 + 壓縮用） |
| `noise-big` | 長邊 ≥ 2048 px 的全彩隨機雜訊圖；依 SD-FULFILL §9.3 縮至長邊 2048 px、WebP 品質 0.5 後仍 ≥ 1,048,576 bytes（e2e-author 以同一壓縮純函式先驗證此 fixture） |
| `gif`、`heic` | GIF、HEIC 圖檔 |
| `jpgB-same-size` | 與 `jpg500` 位元組數相同、內容不同的 JPEG（雜湊不符用） |
| `mp4-15s`、`mov-30s`、`mp4-20s`、`mp4-14s`、`mp4-31s` | 對應長度的影片（小檔） |
| `mp4-20s-30mb` | 20 秒、約 30 MB 的 MP4 |

### 1.4 各段訂單

| 代號 | owner / sitter | 建立 | 用途 |
|------|---------------|------|------|
| `A` | O1 / S1（BASIC） | `MK-CONFIRMED` n=5；S1 F5 `PUT …/name`（V1，`name="早上服務"`） | 開放條件、草稿、送出、唯讀、飼主檢視、越權 |
| `B` | O1 / S1 | `MK-CONFIRMED` n=3 → `START(W1)` | 期限、逾期草稿、判斷順序、Email 偏好關閉 |
| `B2` | O2 / S2 | `MK-CONFIRMED` n=1 | 跨訂單路徑 404 |
| `P` | O1 / SP（PRO） | `MK-CONFIRMED` n=3 → `START(P1)` | 申請後跨期限確認、系統逾時轉 `DONE` |
| `F` | O1 / SF（FREE 快照） | `MK-CONFIRMED` n=2 → `START(F1)` → SF upsert `tier=ULTIMATE` | FREE 快照、直接送出、預設名稱 |
| `Q` | O1 / S1（BASIC） | `MK-CONFIRMED` n=2 → `START(Q1)` | BASIC 額度、刪除、額度併發 |
| `R` | O1 / SPD（PRO 快照） | `MK-CONFIRMED` n=1 → `START(R1)` → SPD upsert `tier=FREE` | PRO 額度（降級不影響） |
| `U` | O1 / SU（ULTIMATE） | `MK-CONFIRMED` n=1 → `START(U1)` | ULTIMATE 額度 |
| `T` | O1 / SP（PRO） | `MK-CONFIRMED` n=3 → `START(T1)`、`START(T2)` | 格式、大小、時長、實際內容檢驗、傳檔失敗、上傳冪等 |
| `D` | O1 / S1（BASIC） | `MK-CONFIRMED` n=4（見 CL-62） | `DISPUTED` 可寫、ADMIN 唯讀、O15 介入 |
| `G` | O1 / S1 | `MK-CONFIRMED` n=2 | 送出 vs O11（依序） |
| `H` | O1 / S1 | `MK-CONFIRMED` n=2 | O12 結案後寫入 |
| `L` | O1 / SL（BASIC） | `MK-CONFIRMED` n=1 → SL 鎖定 → `START(L1)` | 訂閱鎖定不擋 |

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4 / SD-FULFILL §10.2：先收集 `service_report_media` 與 `care_log_media_uploads` 物件鍵 → DB 刪列 → commit 後刪 GCS 物件；跨模組範圍含帳號、方案、毛孩、通知與通知偏好、訂閱）。

---

## 2. 測試步驟

> 步驟 ID 前綴 `CL-`；e2e spec 的 `step()` / `apiStep()` 標籤以同一 ID 開頭。
> 保母畫面 = 服務執行面板 `/sitter/orders/{orderId}/visits/{visitId}`；飼主 / ADMIN 日誌檢視 = `/orders/{orderId}/visits/{visitId}/care-log`（SD-FULFILL §9.1）。
> 「無檔案進入儲存服務」在 E2E 以「F16 未回 `uploadId`，且 `INSPECT.fulfill.uploads` 無新槽位（拒絕時不建槽位、不簽網址）」證明；GCS 物件實際存在 / 刪除由後端測試驗（§4）。
> 「確認失敗、此次上傳作廢」以 `INSPECT.fulfill.uploads` 該槽位 `status=FAILED`、`failureCode` = 回應錯誤碼證明；「仍有效」= `status=PENDING`。
> 通知以 `INSPECT.notifications`（`type=CARE_LOG_SUBMITTED`、`recipientUserId`、`mandatory`、`emailStatus`）斷言；「有 Email」/「不寄」語意見 §5 #1。
> 「空白」依 SD-GLOBAL-SPEC §2.7 `strip()`（含全形空白）。

### 2.1 開放條件與草稿

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| CL-01 | AC-1.1 情境 3；EX-6 | S1 | 訂單 `A` 仍 `CONFIRMED`。F14 `PUT …/care-log/draft`（V2，`text="x"`） | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=CONFIRMED`；`INSPECT(A).fulfill.careLogs` 無 V2 | API JSON |
| CL-02 | AC-1.1 情境 4；BR-6 | S1 | `START(V1)`；開 V1 面板 | 日誌區塊標頭「早上服務」；`sitter-care-log-input-text`、`sitter-care-log-btn-save` 可見；`sitter-care-log-btn-submit` 停用並顯示「行程結束後才能送出」 | F13 V1 → `visitName="早上服務"`、`editable=true`、`submittable=false` | 截圖 + API JSON |
| CL-03 | AC-1.1 情境 1；EX-7 | S1 | 開 V2（`PENDING`）面板；F14 V2 | 面板無 `sitter-care-log-btn-save`、無 `sitter-care-log-input-text` | 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=PENDING`；`INSPECT(A).fulfill.careLogs` 無 V2 | 截圖 + API JSON |
| CL-04 | AC-1.1 情境 2；EX-7 | S1 | FT1 V4 `CLOSED_BY_SYSTEM`；開 V4 面板；F14 V4；FT1 V2 `CANCELLED`；F14 V2 | V4 面板無日誌編輯入口 | V4：409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=CLOSED_BY_SYSTEM`；V2：409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=CANCELLED`；`INSPECT(A).fulfill.careLogs` 無 V2、V4 | 截圖 + API JSON |
| CL-05 | AC-1.3 情境 1；BR-7 | S1 | V1 面板：看計數器；於 `sitter-care-log-input-text` 填入「貓」×1000；再按一個字「a」 | `sitter-care-log-text-counter`「0 / 1000」→「1000 / 1000」；按「a」後仍「1000 / 1000」、輸入框內容仍 1000 code point | — | 截圖 ×2 |
| CL-06 | AC-1.2 情境 1；L1；BR-24 | S1 | 清空後輸入「乾乾吃完半碗」，點 `sitter-care-log-btn-save` | 計數器「6 / 1000」 | F14 200 `{ careLogId, status: "DRAFT", text: "乾乾吃完半碗" }`；`INSPECT(A).fulfill.careLogs` V1 恰 1 筆 `DRAFT`、`createdBy=updatedBy=S1`；`orderLogs` 無暫存紀錄 | 截圖 + API JSON |
| CL-07 | AC-1.2 情境 2；BR-2 | S1 | 改為「乾乾吃完一碗」再暫存 | — | F14 200，`careLogId` 與 CL-06 相同；F13 V1 `text="乾乾吃完一碗"`；`INSPECT` V1 日誌仍 1 筆 | API JSON |
| CL-08 | AC-1.3 情境 2；EX-5 | S1 | API F14 V1：`"a"×1001`；再送 `"a"×998 + "🐱🐱"`（1000 code point） | — | 前者 400 `VALIDATION_FAILED`、`field=text`；後者 200，F13 `text` 為 1000 code point | API JSON |
| CL-09 | AC-1.3 情境 3；BR-8；AC-2.5 | S1 | V1 面板：輸入框改為「未存」（不暫存）；選 `webp800` 填 `sitter-care-log-input-caption`「吃飯中」→ `sitter-care-log-btn-upload`；再上傳 `jpg500`（說明「睡午覺」） | 兩次上傳後 `sitter-care-log-media-item-{mediaId}` ×2；`sitter-care-log-quota`「照片 2 / 5　影片 0 / 0」；`sitter-care-log-input-text` 仍為「未存」 | 每次頁面依序發出 F16（帶 `Idempotency-Key`、`file.mediaType=IMAGE`、`sizeBytes < 1048576`）→ F17 → PUT → F18 201（`quota.imageUsed` 1 → 2、`imageLimit=5`）；F13 V1 `text` 仍為 CL-08 暫存值 | 截圖 + API JSON |
| CL-10 | AC-2.5 情境 1；L1；BR-19 | S1 | `START(V3)`（無日誌）。V3：F16（`webp800`、`caption="睡午覺"`）→ F17 → PUT；此時讀 F13 V3 與 `INSPECT(A)`；再 F18；再 `INSPECT(A)` | — | F16 201 `{ uploadId, receivedAt, expiresAt }`；F18 前 F13 V3 無 `careLogId`、`quota.imageUsed=0`，`INSPECT` 該槽位 `status=PENDING`；F18 201 `{ mediaId, mediaType: "IMAGE", caption: "睡午覺", careLogId, quota.imageUsed: 1 }`；F18 後 `INSPECT`：槽位 `COMPLETED`、`careLogs` V3 `DRAFT`、`media` 1 筆，`orderLogs` 新增 1 筆 `MEDIA_UPLOADED`（`visitId=V3`、`mediaId`、`uploadId`、`objectKey`、`mediaType=IMAGE`、`sizeBytes`、`careLogCreated=true`） | API JSON |
| CL-11 | AC-2.5 情境 2、3；EX-5；BR-14 | S1 | V3 F16：`caption` 101 字；`file` 為含 2 個物件的陣列；不帶 `file`；不帶 `Idempotency-Key` | — | 依序 400 `VALIDATION_FAILED`，`field` = `caption`、`file`、`file`、`Idempotency-Key`；皆無 `uploadId`；`INSPECT(A).fulfill.uploads` 無新槽位；F13 V3 `quota.imageUsed` 仍 1 | API JSON |
| CL-12 | AC-1.2 情境 3；BR-2；BR-20 | S1 | `START(V5)`（無日誌）。V5：F16（`jpg500`）→ F17 → PUT；接著**同時**送出 F14（`text="同時暫存"`）與 F18 | — | F14 200、F18 201；`INSPECT(A)` V5：`careLogs` 恰 1 筆 `DRAFT`、`media` 1 筆；F13 V5 `text="同時暫存"` | API JSON |
| CL-13 | AC-3.8 情境 5 | S1 | V5（BASIC、可編輯）F16 宣告 `image/gif`、`sizeBytes=2097152` | — | 422 `CARE_LOG_MEDIA_TYPE_UNSUPPORTED`（格式先於大小）；無 `uploadId` | API JSON |

### 2.2 送出與唯讀

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| CL-15 | AC-1.4 情境 1；EX-7 | S1 | V1（`IN_PROGRESS`、有草稿）：看送出鈕；API F15 `POST …/care-log/submit`（新 key、`text="提早送"`） | `sitter-care-log-btn-submit` 停用、顯示「行程結束後才能送出」 | 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=IN_PROGRESS`；F13 V1 `status=DRAFT` | 截圖 + API JSON |
| CL-16 | AC-1.4 情境 2 | S1 | `FINISH(V1)`；重新整理 V1 面板 | 送出鈕啟用；`sitter-care-log-deadline`「請於 {deadlineAt} 前送出」 | F13 V1 `deadlineAt` = `INSPECT.fulfill.visits` V1 `completedAt` + 24h、`submittable=true` | 截圖 + API JSON |
| CL-17 | AC-1.4 情境 4；BR-10；EX-10 | S1 | F15 V1 `text="   "`；F15 V1 `text="　　"`（全形）；`FINISH(V3)` 後 F15 V3 `text=""`（V3 有媒體、無文字） | — | 三者皆 422 `CARE_LOG_CONTENT_EMPTY`；`INSPECT` V1、V3 仍 `DRAFT` | API JSON |
| CL-18 | AC-1.4 情境 2；L3；AC-3.1 情境 1；BR-17；BR-19 | S1、O1 | V1 面板輸入「已餵藥」，點 `sitter-care-log-btn-submit`；`INSPECT(A)`；O1 於通知中心點擊該則 | 送出後面板無 `sitter-care-log-input-text`、`-btn-save`、`-btn-submit`、`-btn-upload`、`-btn-delete-media-*`；通知點擊後到 `/orders/{A}/visits/{V1}/care-log`、`owner-care-log-view` 可見 | 頁面恰發出 1 次 F15（`Idempotency-Key` 記為 K，body `{ text: "已餵藥" }`）→ 200 `{ careLogId, status: "SUBMITTED", submittedAt }`；`INSPECT`：`careLogs` V1 `SUBMITTED`；`orderLogs` 恰 1 筆 `CARE_LOG_SUBMITTED`（`visitId=V1`、`textLength=3`、`imageCount=2`、`videoCount=0`）；`notifications` 恰 1 筆 `CARE_LOG_SUBMITTED`、收件者 O1、`mandatory=false`、有 Email；通知中心該則文案「{V1 serviceDate} 早上服務 的照護日誌已由保母回報，快來看看毛孩的近況吧！」 | 截圖 ×2 + API JSON |
| CL-19 | AC-3.5 情境 1、2、4、5；EX-15；EX-16 | S1 | 以 K 與相同 body 重送 F15；以 K 改送 `text="改字"`；新 key 送 F15；不帶 key 送 F15 | — | 依序 200（body 與 CL-18 相同）、422 `IDEMPOTENCY_KEY_REUSED`、409 `CARE_LOG_STATE_CONFLICT`（`currentCareLogStatus=SUBMITTED`）、400 `VALIDATION_FAILED`（`field=Idempotency-Key`）；`INSPECT` 的 `CARE_LOG_SUBMITTED` log 與通知仍各 1 筆 | API JSON |
| CL-20 | AC-1.5 情境 1；BR-11；EX-8 | S1 | V1：F14（`text="改"`）、F16（`jpg500`，新 key）、F19 `DELETE …/care-log/media/{CL-09 第一張 mediaId}` | — | 三者皆 409 `CARE_LOG_STATE_CONFLICT`；F16 無 `uploadId`；F13 V1 `text="已餵藥"`、`media` 2 筆 | API JSON |
| CL-21 | AC-1.5 情境 2；AC-3.8 情境 1；BR-26 | S1、S2 | `EXPIRE(V1)`；S1 F14 V1（`text="改"`）；S1 F14 V1（`"a"×1001`）；S2 F14 V1 | — | 依序 409 `CARE_LOG_STATE_CONFLICT`（不是 `DEADLINE_PASSED`）、400 `VALIDATION_FAILED`、403 `FORBIDDEN` | API JSON |
| CL-22 | AC-3.1 情境 3；BR-17 | — | `INSPECT(A).notifications` | — | 訂單 `A` 至此只有 CL-18 那 1 筆 `CARE_LOG_SUBMITTED`（CL-06~CL-13 的暫存、上傳、CL-20 的拒絕皆未產生通知） | API JSON |

### 2.3 期限（含系統逾時轉 DONE）與 Email 偏好

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| CL-25 | AC-1.6 情境 1（近似）；AC-3.1 情境 2 | O1、S1 | `owner-setup` upsert O1 `emailPreferences.ORDER_AFFAIR = 關`；訂單 `B`：`FINISH(W1, 伺服器現在 − 23h50m)`；F15 W1（`text="準時送出"`）；之後 upsert O1 改回 `開` | — | F15 200 `SUBMITTED`；`INSPECT(B).notifications` 恰 1 筆 `CARE_LOG_SUBMITTED`、收件者 O1、`emailStatus` 為不寄（站內通知仍寫入） | API JSON |
| CL-26 | AC-1.6 情境 2（近似）；EX-9；L4 | S1 | `START(W2)` → F14 W2（`text="乾乾吃完"`）→ `UPLOAD` ×2（`jpg500`）→ `FINISH(W2, 伺服器現在 − 23h)` → `EXPIRE(W2)`；之後對 W2 依序 F14、F16（`jpg500`，新 key）、F19（其中一張）、F15（新 key） | — | 四者皆 409 `DEADLINE_PASSED`、`deadline=CARE_LOG_EDIT_ENDS_AT`、`deadlineAt` = F13 W2 `deadlineAt`；F16 無 `uploadId`、無新槽位；`INSPECT(B)`：W2 日誌 `DRAFT`、`media` 2 筆；F13 W2 `expired=true` | API JSON |
| CL-27 | AC-1.7 情境 1；BR-5 | S1 | 開 W2 面板 | `sitter-care-log-status`「已逾期，未送出」；顯示行程名稱、文字「乾乾吃完」、2 張照片；無任何輸入框與按鈕 | F13 W2 `editable=false`、`submittable=false` | 截圖 |
| CL-28 | AC-1.7 情境 2；BR-16 | O1 | 開 `/owner/orders/{B}`；API F13 W2；`INSPECT(B).notifications` | `owner-order-detail-visit-row-{W2}` 內無 `owner-order-detail-btn-view-log-{W2}`；`{W1}` 有 | F13 W2（OWNER）→ 404 `NOT_FOUND`；`CARE_LOG_SUBMITTED` 通知只有 W1 那 1 筆 | 截圖 + API JSON |
| CL-29 | AC-1.6 情境 3（近似）；BR-5；BR-28 | SP | 訂單 `P`：`FINISH(P1, 伺服器現在 − 24h + 90 秒)`；立即 F16（`mp4-20s`，key KP）→ F17 → PUT；輪詢 F13 P1 直到 `expired=true`；F14 P1；F18 | — | F16 201（`receivedAt` < F13 `deadlineAt`）；F14 409 `DEADLINE_PASSED`；F18 201 `mediaType=VIDEO`、`quota.videoUsed=1`（期限以申請時間判斷）；`INSPECT(P)` 槽位 `COMPLETED`、`receivedAt` < 期限 | API JSON |
| CL-30 | AC-1.6 情境 4；EX-27；BR-27 | SP | `START(P2)` → FT2 P2 `target=STARTED_AT`、`minutes=-1441` → FC1 `POST /api/internal/cron/visits/in-progress-timeout?orderId={P}`（`X-Internal-Secret`）；開 P2 面板 | `sitter-visit-panel-auto-completed-label` 可見；送出鈕啟用；`sitter-care-log-deadline`「請於 {P2 completedAt + 24h} 前送出」 | FC1 200 `processed=1`、`skipped=0`、`failed=0`；`INSPECT(P).fulfill.visits` P2 `status=DONE`、`completionSource=SYSTEM`、`completedAt` 介於 FC1 `startedAt` 與 `finishedAt`；`orderLogs` 1 筆 `VISIT_AUTO_COMPLETED`（`visitId=P2`）；F13 P2 `deadlineAt = completedAt + 24h` | 截圖 + API JSON |
| CL-31 | AC-1.6 情境 5（近似） | SP | F15 P2（`text="系統結束後補送"`）；`START(P3)` → FT2 `STARTED_AT −1441` → FC1 `?orderId={P}` → `EXPIRE(P3)` → F15 P3 | — | P2 200 `SUBMITTED`；第二次 FC1 `processed=1`；P3 409 `DEADLINE_PASSED`、`deadlineAt` = F13 P3 `deadlineAt` | API JSON |

### 2.4 媒體額度、格式與實際內容

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| CL-35 | AC-2.1 情境 1；BR-9 | SF | 訂單 `F`（快照 FREE、SF 已升 ULTIMATE）：開 F1 面板 | 無 `sitter-care-log-btn-upload`；`sitter-care-log-quota`「此訂單方案僅支援文字回報」 | F13 F1 `quota.planTier=FREE`、`imageLimit=0`、`videoLimit=0` | 截圖 + API JSON |
| CL-36 | AC-2.1 情境 2；EX-11 | SF | F16 F1（`jpg500`） | — | 422 `CARE_LOG_MEDIA_QUOTA_EXCEEDED`、`mediaType=IMAGE`、`limit=0`、`used=0`；無 `uploadId`；`INSPECT(F)`：無槽位、無 F1 日誌 | API JSON |
| CL-37 | AC-2.1 情境 3 | SF | F14 F1（`text="文字回報"`） | — | 200 `DRAFT` | API JSON |
| CL-38 | AC-1.4 情境 3；L3 | SF | `FINISH(F2)`（從未暫存）；F15 F2（`text="一切正常"`） | — | 200 `SUBMITTED`；`INSPECT(F).fulfill.careLogs` F2 `SUBMITTED` | API JSON |
| CL-40 | AC-2.2 情境 1；EX-11 | S1 | 訂單 `Q`：Q1 `UPLOAD` ×5（`jpg500`）；開 Q1 面板；F16 第 6 張（`jpg500`）；F16 `mp4-20s`；面板選 `jpg500` | `sitter-care-log-quota`「照片 5 / 5　影片 0 / 0」；選檔後顯示「此行程照片額度已用完」 | 5 次 F18 皆 201；第 6 張 422 `CARE_LOG_MEDIA_QUOTA_EXCEEDED`（`limit=5`、`used=5`）；影片 422（`mediaType=VIDEO`、`limit=0`）；選檔後頁面未發出 F16 | 截圖 ×2 + API JSON |
| CL-41 | AC-2.2 情境 4 | S1 | `sitter-setup` upsert S1 `tier=ULTIMATE`；F16 Q1 第 6 張 | — | 422 `CARE_LOG_MEDIA_QUOTA_EXCEEDED`、`limit=5`（依快照） | API JSON |
| CL-42 | AC-2.6 情境 1；L2；BR-15；BR-19 | S1 | Q1 面板點 `sitter-care-log-btn-delete-media-{m1}`；再上傳 1 張 | m1 自列表消失；quota「照片 4 / 5」；上傳後「照片 5 / 5」 | F19 200 `{ mediaId: m1, quota.imageUsed: 4 }`；`INSPECT(Q)`：`media` 無 m1、m1 槽位 `status=DISCARDED`、`orderLogs` 1 筆 `MEDIA_DELETED`（`mediaId=m1`、`objectKey`、`mediaType=IMAGE`）；再上傳 F18 201、`imageUsed=5` | 截圖 ×2 + API JSON |
| CL-43 | AC-2.6 情境 2；BR-21；EX-2 | S1 | 再次 F19 m1 | — | 404 `NOT_FOUND` | API JSON |
| CL-44 | AC-2.6 情境 3；EX-2 | S1 | `START(Q2)`；以 Q2 路徑 F19 Q1 的媒體 m2 | — | 404 `NOT_FOUND`；`INSPECT(Q).fulfill.media` Q1 仍 5 筆、含 m2 | API JSON |
| CL-45 | AC-2.7；EX-17；EX-22；EX-32 | S1 | Q1 先 F19 一張（剩 4）；以 key Ka、Kb 各 F16 1 張 `jpg500`（皆 201）→ 各 F17 + PUT；**同時**送兩個 F18；對被拒的那個 `uploadId` 再送 F18 | — | 兩個 F16 皆 201；兩個 F18 恰 1 個 201、另 1 個 422 `CARE_LOG_MEDIA_QUOTA_EXCEEDED`（`used=5`）；`INSPECT(Q)`：Q1 `media` 5 筆、被拒槽位 `status=FAILED`、`failureCode=CARE_LOG_MEDIA_QUOTA_EXCEEDED`；被拒者再 F18 → 同一個 422（回放） | API JSON |
| CL-46 | AC-2.2 情境 2；AC-2.4 情境 1（部分） | SPD | 訂單 `R`（快照 PRO、SPD 已降 FREE）：R1 `UPLOAD` 10 張 `jpg500` + `mp4-15s` + `mov-30s`；再 F16 第 11 張照片、第 3 支影片（`mp4-20s`） | — | 12 次 F18 皆 201；第 11 張 422（`limit=10`）、第 3 支 422（`mediaType=VIDEO`、`limit=2`） | API JSON |
| CL-47 | AC-2.2 情境 3；AC-2.4 情境 1（30 MB） | SU | 訂單 `U`：U1 `UPLOAD` 25 張 `jpg500` + 4 支 `mp4-20s` + `mp4-20s-30mb`；再 F16 第 26 張、第 6 支 | — | 30 次 F18 皆 201（30 MB 那支 `sizeBytes` ≈ 30 MB、`durationSeconds=20`）；第 26 張 422（`limit=25`）、第 6 支 422（`limit=5`） | API JSON |
| CL-50 | AC-2.3 情境 1 | SP | 訂單 `T`：T1 `UPLOAD` `jpg900`、`png900`、`webp900` | — | 三次 F18 皆 201、`mediaType=IMAGE` | API JSON |
| CL-51 | AC-2.3 情境 2；EX-12 | SP | T1 F16 宣告 `contentType=image/gif`、`image/heic` | — | 皆 422 `CARE_LOG_MEDIA_TYPE_UNSUPPORTED`；無 `uploadId`、無新槽位 | API JSON |
| CL-52 | AC-2.3 情境 3；EX-13 | SP | T1 F16 宣告 JPEG `sizeBytes=1048576`；`UPLOAD(jpg1048575)` | — | 前者 422 `CARE_LOG_MEDIA_TOO_LARGE`、`maxBytes=1048576`、無 `uploadId`；後者 F18 201、`sizeBytes=1048575` | API JSON |
| CL-53 | AC-2.3 情境 6；EX-28；EX-32；BR-28 | SP | T1：F16 宣告 `contentType=image/jpeg`、`sizeBytes` 與 `sha256` = `gif` 檔的值 → F17 → 以 F17 標頭 PUT `gif` 位元組 → F18；再 F18 一次 | — | F18 422 `CARE_LOG_MEDIA_TYPE_UNSUPPORTED`；再送回放同一 422；`INSPECT(T)` 該槽位 `FAILED`、`failureCode=CARE_LOG_MEDIA_TYPE_UNSUPPORTED`；T1 `imageUsed` 不變、無新媒體 | API JSON |
| CL-54 | AC-2.3 情境 6 後半（正式環境替代斷言）；EX-30 | SP | T1：F16 宣告 900 KB JPEG（`jpg900` 的 `sha256` 與大小）→ F17 → PUT 一個 1,048,576 bytes 的檔案 | — | GCS 拒絕該 PUT（4xx，簽名綁定 `x-goog-content-length-range` = 宣告大小）；F18 400 `VALIDATION_FAILED`、`field=file`、`reason=OBJECT_MISSING`；`INSPECT(T)` 該槽位仍 `PENDING`、無新媒體、已用量不變（確認時實際大小 422 見 §4） | API JSON |
| CL-55 | EX-30（雜湊不符）；BR-28 | SP | T1：F16 宣告 `jpg500` 的 `sha256` 與大小 → F17 → PUT `jpgB-same-size` → F18；再 F18 一次 | — | F18 400 `VALIDATION_FAILED`、`field=file`、`reason=SHA256_MISMATCH`；再送回放同一 400；`INSPECT(T)` 該槽位 `FAILED`；無新媒體 | API JSON |
| CL-56 | EX-30（檔案不存在）；BR-28 | SP | T1：F16（`jpg500`）→ **不 PUT** 直接 F18；之後 F17 → PUT → F18 | — | 第一次 F18 400 `VALIDATION_FAILED`、`reason=OBJECT_MISSING`，`INSPECT` 槽位仍 `PENDING`、無新媒體；補傳後 F18 201、槽位 `COMPLETED` | API JSON |
| CL-57 | AC-2.4 情境 6；EX-28 | SP | T1：F16 宣告 `mp4`、`durationSeconds=20`、`sizeBytes` 與 `sha256` = `mp4-14s` → F17 → PUT `mp4-14s` → F18 | — | 422 `CARE_LOG_VIDEO_DURATION_INVALID`（`actualSeconds=14`）；槽位 `FAILED`；F13 `quota.videoUsed=0` | API JSON |
| CL-58 | AC-2.4 情境 1 | SP | T1 `UPLOAD(mp4-15s)`、`UPLOAD(mov-30s)` | — | 皆 201、`mediaType=VIDEO`、`durationSeconds` 為 15、30 | API JSON |
| CL-59 | AC-2.4 情境 2、3、4；EX-12；EX-13；EX-14 | SP | T1 F16：MP4 `durationSeconds=14`；`=31`；MP4 `sizeBytes=52428800`、`durationSeconds=20`；`contentType=video/webm`；`contentType=video/x-msvideo` | — | 依序 422 `CARE_LOG_VIDEO_DURATION_INVALID` ×2、`CARE_LOG_MEDIA_TOO_LARGE`、`CARE_LOG_MEDIA_TYPE_UNSUPPORTED` ×2；皆無 `uploadId`（影片額度已滿仍先回格式 / 大小 / 時長碼） | API JSON |
| CL-60a | AC-2.4 情境 5；EX-14 | SP | T2 面板選 `mp4-31s` | 顯示「影片長度需為 15–30 秒」 | 頁面未發出 F16 | 截圖 |
| CL-60b | AC-2.3 情境 4；BR-12 | SP | T2 面板選 `jpg4m` 上傳 | 上傳完成、quota 照片 +1 | 頁面發出的 F16 `file.contentType=image/webp`、`sizeBytes < 1048576`；F18 201 `sizeBytes < 1048576` | 截圖 + API JSON |
| CL-60c | AC-2.3 情境 5 | SP | T2 面板選 `noise-big` | 顯示「照片需小於 1MB」 | 頁面未發出 F16 | 截圖 |
| CL-61 | AC-2.8 情境 4；EX-21 (2) | SP | T2 面板：以 `page.route` 讓第一次對 `storage.googleapis.com` 的 PUT 中斷；選 `jpg500` 上傳；解除攔截後點重試 | 顯示「上傳失敗，請稍候重試」，保留已選檔案與重試按鈕；重試後媒體出現 | 失敗時 `INSPECT(T)` 該槽位 `PENDING`、T2 已用量不變；重試時頁面以**同一把** `Idempotency-Key` 送 F16 並得到與第一次相同的 `uploadId` → F17 → PUT → F18 201；槽位 `COMPLETED` | 截圖 ×2 + API JSON |

### 2.5 爭議、調解與結案後

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| CL-62 | AC-2.5；AC-2.6；AC-1.4；BR-19 | S1、O1 | 訂單 `D`：`START(D1)` → `UPLOAD(D1, jpg500)` 得 p1 → F19 p1 → `UPLOAD(D1, jpg500)` 得 p2 → F14 D1 → `FINISH(D1)` → F15 D1（`text="D1 已送出"`）；`START(D2)` → `FINISH(D2)` → F14 D2（`text="D2 草稿"`）；D3 同 D2；`START(D4)` → `FINISH(D4, 伺服器現在 − 23h)` → F14 D4；O1 E26（`description="爭議"`）→ `DISPUTED`；`INSPECT(D)` | — | E26 200 `DISPUTED`；`INSPECT.orderLogs` 的日誌類紀錄恰為：`MEDIA_UPLOADED` ×2（各含 `visitId=D1`、`objectKey`、`mediaType=IMAGE`、`sizeBytes`）、`MEDIA_DELETED` ×1（`mediaId=p1`）、`CARE_LOG_SUBMITTED` ×1（`visitId=D1`）；無任何暫存草稿的紀錄 | API JSON |
| CL-63 | AC-3.4 情境 1；BR-16 | admin | admin F13 D1、D2；開 `/orders/{D}/visits/{D1}/care-log` | `owner-care-log-view` 顯示 D1 文字與 p2；無任何編輯元素 | F13 D1 200（`text="D1 已送出"`、`media` 1 筆、`url` 含 `X-Goog-Expires=900`）；F13 D2 404 `NOT_FOUND` | 截圖 + API JSON |
| CL-64 | AC-1.8 情境 1；EX-26 | S1 | D3（訂單 `DISPUTED`）：F14（`text="爭議中補寫"`）→ F15（`text="爭議中送出"`） | — | F14 200、F15 200 `SUBMITTED` | API JSON |
| CL-65 | AC-3.2 情境 5（`DISPUTED`） | O1 | 開 `/orders/{D}/visits/{D1}/care-log` | `owner-care-log-view` 可見、圖片載入（`naturalWidth > 0`） | — | 截圖 |
| CL-66 | AC-2.8 情境 3；EX-22；AC-3.7 情境 1（依序模擬）；AC-1.8 情境 3；AC-3.8 情境 4 | S1、admin | `EXPIRE(D4)`（訂單 `DISPUTED`）；D2：F16（`jpg500`）→ F17 → PUT；admin E29 `POST /api/admin/orders/{D}/mediation`（無 `adjustment`）→ `COMPLETED`；S1 依序：D2 F18、D2 F18 再一次、D2 F14、D2 F15（新 key）、D4 F14 | — | E29 200 `COMPLETED`；D2 F18 409 `ORDER_STATE_CONFLICT`（`currentStatus=COMPLETED`），再送回放同一 409；`INSPECT(D)` 該槽位 `FAILED`、`failureCode=ORDER_STATE_CONFLICT`、D2 無媒體；D2 F14、F15 皆 409 `ORDER_STATE_CONFLICT`；D4（已逾期）F14 409 `ORDER_STATE_CONFLICT`（不是 `DEADLINE_PASSED`）；`careLogs` D2 `DRAFT`、D3 `SUBMITTED`（送出先於 O15 者保留） | API JSON |
| CL-67 | AC-3.2 情境 5（`COMPLETED`） | O1 | 開 `/orders/{D}/visits/{D1}/care-log` | 仍可查看 | — | 截圖 |
| CL-68 | AC-3.7 情境 2（依序模擬）；EX-18；AC-1.8 情境 4 | O1、S1 | 訂單 `G`：`START(G1)` → `FINISH(G1)` → F15 G1；`START(G2)` → `FINISH(G2)` → F14 G2。O1 E25；S1 F15 G2；O1 E25；S1 F14 G1 | — | 第一次 E25 409 `VISITS_NOT_FINISHED`；F15 G2 200；第二次 E25 200 `COMPLETED`；F14 G1 409 `ORDER_STATE_CONFLICT`（不是 `CARE_LOG_STATE_CONFLICT`） | API JSON |
| CL-69 | AC-1.8 情境 5 | S1、system | 訂單 `H`：`START(H1)` → `FINISH(H1, 伺服器現在 − 23h)` → F14 H1 → `EXPIRE(H1)`；`START(H2)` → `FINISH(H2)` → F15 H2；`SHIFT-WINDOW(H, −5 分)` → C8 `POST /api/internal/cron/orders/auto-complete?orderId={H}`；S1 F14 H1 | — | C8 200 `processed=1`；E2 `H` `status=COMPLETED`；F14 H1 409 `ORDER_STATE_CONFLICT`（不是 `DEADLINE_PASSED`） | API JSON |
| CL-70 | AC-1.8 情境 2；BR-23；EX-26 | SL | 訂單 `L`（SL 訂閱已鎖定）：L1 F14 → `UPLOAD(jpg500)` → `FINISH(L1)` → F15 | — | F14 200、F18 201、F15 200 | API JSON |

### 2.6 飼主檢視、權限、上傳冪等、判斷順序

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| CL-75 | AC-3.2 情境 1；BR-6；BR-18 | O1 | 開 `/owner/orders/{A}`，點 `owner-order-detail-btn-view-log-{V1}` | `owner-order-detail-visit-row-{V1}` 內有查看日誌、`{V3}`（`DRAFT`）內沒有；`owner-care-log-view` 顯示 `owner-care-log-visit-name`「早上服務」、文字「已餵藥」、2 張照片已載入（`naturalWidth > 0`）及說明「吃飯中」「睡午覺」、送出時間 = F15 `submittedAt` 的 Asia/Taipei `YYYY-MM-DD HH:mm` | F13 V1（OWNER）→ 無 `status` / `editable` 等保母欄位；`media[].url` 含 `X-Goog-Expires=900` | 截圖 ×2 + API JSON |
| CL-76 | AC-3.2 情境 2；BR-16 | O1 | F13 V3 | — | 404 `NOT_FOUND` | API JSON |
| CL-77 | AC-3.2 情境 3；BR-6 | O1 | 開 `/orders/{F}/visits/{F2}/care-log`（F2 未自訂名稱、每日 1 次） | `owner-care-log-visit-name`「到府餵食」 | — | 截圖 |
| CL-78 | AC-3.3 情境 1–5；EX-1–EX-4 | 匿名、S2、O2、O1、admin | 對訂單 `A` V1：無 token F13 / F14；無 token 以 K 與相同 body 重送 F15；S2 F13、F14、F16、F19、F15；O2 F13；O1（OWNER token）F14、F16、F19、F15；admin F14；隨機 UUID 行程 F13；以 `B2` 的 orderId 搭 V1 路徑 F13 | — | 依序 401 `UNAUTHENTICATED` ×3（401 先於冪等回放）；S2 403 `FORBIDDEN` ×5；O2 403 `FORBIDDEN`；O1 403 `ROLE_MISMATCH` ×4；admin 403 `ROLE_MISMATCH`；404 `NOT_FOUND` ×2；`INSPECT(A)` V1 日誌、媒體數與 CL-20 相同，`uploads` 無新槽位 | API JSON |
| CL-79 | AC-3.3（SD-FULFILL §6.3 F13 已知邊界） | S1 | S1 切換為 `currentRole=OWNER` 的 token，F13 訂單 `A` 的 V3（自己身為保母的草稿） | — | 404 `NOT_FOUND`（依 SD 已知行為，非 `ROLE_MISMATCH`） | API JSON |
| CL-80 | AC-3.4 情境 2 | admin | admin F13 訂單 `A`（`IN_PROGRESS`、無 OPEN 待辦）V1 | — | 403 `FORBIDDEN` | API JSON |
| CL-81 | AC-3.6 情境 1；EX-15；BR-28 | SP | `START(T3)` → `UPLOAD(T3, jpg500, key KU)` 得 mU；以 KU 與相同 body 重送 F16；F17；F18 | — | F16 回放 201、`uploadId` 與第一次相同；F17 200 `alreadyCompleted=true`（不需 PUT）；F18 201、`mediaId=mU`；`INSPECT(T)` T3 `media` 1 筆、槽位 1 個；F13 `imageUsed=1` | API JSON |
| CL-82 | AC-3.6 情境 2；EX-16 | SP | 以 KU 改宣告 `jpg900` 送 F16 | — | 422 `IDEMPOTENCY_KEY_REUSED` | API JSON |
| CL-83 | AC-3.6 情境 3（期限部分） | SP | `FINISH(T3)` → `EXPIRE(T3)`；以 KU、相同 body 重跑 F16 → F17 → F18 | — | F16 回放 201；F17 `alreadyCompleted=true`；F18 201、`mediaId=mU`（不是 `DEADLINE_PASSED`）；`INSPECT(T)` T3 `media` 仍 1 筆 | API JSON |
| CL-84 | AC-3.8 情境 2、3 | S1 | 訂單 `B`：`START(W3)` → F14 W3 → `UPLOAD` ×5（`jpg500`）→ `FINISH(W3, 伺服器現在 − 23h)` → `EXPIRE(W3)`；F16 W3（`jpg500`，新 key）；F16 W3（`jpg500`、`caption` 101 字） | — | 前者 409 `DEADLINE_PASSED`（不是額度 422）；後者 400 `VALIDATION_FAILED`、`field=caption`（不是 `DEADLINE_PASSED`）；皆無 `uploadId` | API JSON |

---

## 3. 權限與例外

> PRD-022 每個 EX 的對應位置；越權一律以另一個真實帳號對正式站打 API（依 `_TEMPLATE.md`）。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| CL-21、CL-78 | EX-1 | 他人保母 / 他人飼主讀寫 | 403 `FORBIDDEN`，資料不變 |
| CL-43、CL-44、CL-78、CL-79 | EX-2 | 媒體已刪 / 跨行程 / 跨訂單 / 不存在 | 404 `NOT_FOUND` |
| CL-78 | EX-3 | OWNER / ADMIN 呼叫保母端寫入 | 403 `ROLE_MISMATCH` |
| CL-78 | EX-4 | 未登入（含帶 key 重送） | 401 `UNAUTHENTICATED` |
| CL-08、CL-11 | EX-5 | 文字 > 1000、說明 > 100、檔案數不符、缺 key | 400 `VALIDATION_FAILED` |
| CL-01、CL-66、CL-68、CL-69 | EX-6 | 訂單 `CONFIRMED` / `COMPLETED`（O11 / O12 / O15） | 409 `ORDER_STATE_CONFLICT` + `currentStatus` |
| CL-03、CL-04、CL-15 | EX-7 | 行程 `PENDING` / `CLOSED_BY_SYSTEM` / `CANCELLED` 寫入；`IN_PROGRESS` 送出 | 409 `VISIT_STATE_CONFLICT` + `currentVisitStatus` |
| CL-19、CL-20、CL-21 | EX-8 | 已送出後任何寫入 | 409 `CARE_LOG_STATE_CONFLICT` |
| CL-26、CL-31、CL-84 | EX-9 | 逾期寫入 | 409 `DEADLINE_PASSED`（`CARE_LOG_EDIT_ENDS_AT`、`deadlineAt`），狀態仍 `DRAFT` |
| CL-17 | EX-10 | 送出空白文字（含全形）/ 只有媒體 | 422 `CARE_LOG_CONTENT_EMPTY` |
| CL-35、CL-36、CL-40 | EX-11 | 快照額度 0 / 已用滿 | 前端擋下；422 `CARE_LOG_MEDIA_QUOTA_EXCEEDED` |
| CL-13、CL-51、CL-59 | EX-12 | 宣告格式不支援 | 422 `CARE_LOG_MEDIA_TYPE_UNSUPPORTED` |
| CL-52、CL-59 | EX-13 | 宣告過大 | 422 `CARE_LOG_MEDIA_TOO_LARGE` |
| CL-59、CL-60a | EX-14 | 宣告時長不符 | 前端擋下「影片長度需為 15–30 秒」；422 `CARE_LOG_VIDEO_DURATION_INVALID` |
| CL-19、CL-81、CL-83 | EX-15 | 同 key 同內容 / 已確認上傳再確認 | 回放，不新增媒體、不重發通知 |
| CL-19、CL-82 | EX-16 | 同 key 不同內容 | 422 `IDEMPOTENCY_KEY_REUSED`（處理中 409 見 §4） |
| CL-45 | EX-17 | 額度剩 1 兩筆同時確認 | 一筆 201、一筆 422 |
| CL-66、CL-68 | EX-18 | 送出 vs O15 / O11 | 依先後結果（真正併發見 §4） |
| — | EX-19 | 鎖逾時 / `@Version` | §4 |
| — | EX-20 | 樂觀鎖覆蓋 | 不適用（不接受客戶端 version，同人多裝置不處理） |
| CL-61 | EX-21 | (1) 取傳檔位置 503：§4；(2) 瀏覽器傳檔失敗 | (2) 前端提示重試，同 key 重試成功 |
| CL-45、CL-66 | EX-22 | 確認時鎖後重驗失敗（額度 / O15） | 回對應錯誤、槽位 `FAILED`、無媒體紀錄（物件刪除見 §4） |
| — | EX-23 | 刪除媒體時 GCS 刪除失敗 | §4 |
| — | EX-24 | 簽名網址產生失敗 | §4 |
| CL-25 | EX-25 | Resend 失敗：§4；站內通知不受 Email 影響由 CL-25 佐證 | — |
| CL-64、CL-70 | EX-26 | `DISPUTED`、訂閱鎖定 | 照常允許 |
| CL-30、CL-31 | EX-27 | 系統逾時轉 `DONE` | 以系統 `completed_at` 起算 24h |
| CL-53、CL-57 | EX-28 | 實際格式 / 時長與宣告不符 | 同組 422、槽位 `FAILED`、無媒體紀錄（實際大小見 §4） |
| — | EX-29 | 申請後 ≥ 30 分鐘才確認 | §4 |
| CL-54、CL-55、CL-56 | EX-30 | 檔案不存在 / 雜湊不符 | 400 `field=file`；不存在者槽位仍 `PENDING` 可補傳、雜湊不符者 `FAILED` |
| — | EX-31 | 確認時讀檔 503 | §4 |
| CL-45、CL-53、CL-55、CL-66 | EX-32 | 已作廢上傳再確認 | 回放第一次的錯誤 |

---

## 4. 不在 E2E 覆蓋的項目

> 後端測試類別依 SD-FULFILL §10.1。

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.6 情境 1、2 的秒級邊界（`02:59:59Z` 成功 / `03:00:00Z` 拒絕）、情境 5 的秒級邊界 | 正式站時鐘不可注入；E2E 以 CL-25 / CL-26 / CL-31 驗期限前後 | `VisitCareLogDeadlineIntegrationTest` |
| AC-1.6 情境 3 精確時刻（申請 `02:59:59Z`、確認 `03:29:58Z`） | 同上；E2E 以 CL-29（申請在期限前、確認在期限後）近似 | `VisitCareLogDeadlineIntegrationTest` |
| AC-1.6 情境 6、EX-29（確認時間 = 申請 + 30 分鐘 → 409 `CARE_LOG_UPLOAD_EXPIRED`，再以新 key 申請 → `DEADLINE_PASSED`） | 槽位 `expires_at` 無測試平移端點，等 30 分鐘不可行 | `VisitCareLogDeadlineIntegrationTest`；FC2 轉 `EXPIRED` 與寬限期由 `ServiceReportUploadOrphanCronIntegrationTest` |
| AC-2.3 情境 6 後半（確認時實際 1,048,576 bytes → 422 `CARE_LOG_MEDIA_TOO_LARGE`） | 正式環境 GCS 在 PUT 階段即依簽名綁定的宣告大小拒絕（PRD-022 AC-2.3 註）；E2E 改斷言 CL-54 | `VisitCareLogMediaUploadIntegrationTest`（fake 直接放置物件） |
| AC-2.3 情境 6、AC-2.4 情境 6、AC-2.6 情境 1、EX-22、EX-28 的「GCS 物件最終被刪除」 | 物件存在與否無對外 API；`INSPECT.uploads.objectDeletedAt` 只在 `expires_at + 15 分寬限` 後由 FC2 最終清除才寫，E2E 不等 45 分鐘；E2E 以槽位 `FAILED` / `DISCARDED` 佐證 | `VisitCareLogMediaUploadIntegrationTest`、`VisitCareLogMediaDeleteIntegrationTest`、`ServiceReportUploadOrphanCronIntegrationTest` |
| AC-2.6 情境 4、EX-23（GCS 刪除失敗仍 200、額度已釋放、FC2 最終清除） | 正式站不能讓 GCS 刪除失敗 | `VisitCareLogMediaDeleteIntegrationTest` |
| AC-2.8 情境 1、2、EX-21 (1)（F17 簽名失敗 503、無媒體、同 key 重試成功） | 正式站無法讓 GCS 簽名故障 | `VisitCareLogMediaUploadIntegrationTest`（F17 注入簽名失敗） |
| EX-31（F18 讀取物件 503，同槽位重打成功） | 同上 | `VisitCareLogMediaUploadIntegrationTest` |
| AC-3.1 情境 4、EX-25（Resend 5xx 送出仍成功）；真實 Email 內容 | 正式站不能讓 Resend 故障；Resend 測試模式只寄專案擁有者；E2E 只驗 `emailStatus`（CL-18、CL-25） | `VisitCareLogSubmitIntegrationTest`；真實 Email 手動驗證 |
| AC-3.2 情境 4（簽名網址過期後重取、15 分鐘有效） | E2E 不等待 15 分鐘（PRD-022 AC-3.2 已指定後端驗）；E2E 只在 CL-63 / CL-75 斷言網址含 `X-Goog-Expires=900` | `VisitCareLogReadIntegrationTest` |
| AC-3.2 情境 5 的「檔案已逾期移除」、EX-24（簽名失敗 503） | 媒體保留刪除（PRD-013）無法在正式站即時造出；GCS 簽名故障無法注入 | `VisitCareLogReadIntegrationTest`（`purged`）；PRD-013 由 TS-013 |
| AC-3.5 情境 3、EX-16 後半（同 key 處理中 → 409 `IDEMPOTENCY_IN_PROGRESS`） | 第一次請求處理窗極短，無法穩定重現 | `VisitCareLogIdempotencyIntegrationTest` |
| AC-3.6 情境 3 的「距申請已超過 30 分鐘」部分 | 同 AC-1.6 情境 6 | `VisitCareLogIdempotencyIntegrationTest` |
| AC-3.7 情境 1、2 的真正同時送達；情境 3、4、EX-19（鎖占用 > 3 秒 → `ORDER_BUSY` + `Retry-After: 1`；F18 `ORDER_BUSY` 保留物件、同槽位重打成功） | 正式站無法穩定造出併發與持鎖；E2E 以 CL-66 / CL-68 依序驗兩種先後的結果 | `VisitCareLogConcurrencyIntegrationTest` |
| 影片時長判定（half-up：14.4 / 14.5 / 30.4 / 30.5 秒、`moov` 在前 / 在後、`mvhd` v0 / v1）、magic bytes；前端縮圖至 2048 px 與品質序列 0.9 → 0.5 | 需大量特製檔案，屬檢驗器 / 純函式單元行為 | `MediaInspectorTest`；前端壓縮純函式單元測試（SD-FULFILL §9.3） |

---

## 5. 已知限制 / 待補

| # | 項目 | 影響 | 建議 |
|---|------|------|------|
| 1 | `INSPECT.notifications[].emailStatus` 的列舉值未在 SD-ORDER §10.4 或 SD-NOTIFY 定義 | CL-18、CL-25 只能以「有 Email / 不寄」語意描述 | SD-NOTIFY 重建時定義列舉值，e2e-author 依實際值對應 |
| 2 | 大量真 GCS 上傳（CL-46 12 支、CL-47 30 支，含 30 MB）拉長 CI 時間並產生測試物件 | CI 時間、GCS 成本 | 由 cleanup 刪除；e2e-author 可平行上傳 |
| — | 已解決（2026-11-22）：ADMIN 帳號（`provision-account roles[]`）；`sitter-setup` upsert 改 `tier` / 訂閱；FT1 `PENDING → DONE / CLOSED_BY_SYSTEM / CANCELLED`、FT2 接受 `DISPUTED`；影片文案統一「影片長度需為 15–30 秒」；照片壓縮下限（SD-FULFILL §9.3：長邊 2048 px、品質 0.9 → 0.5）；排程搶先（`?orderId=`、自動觸發跳過測試訂單）；通知查詢改 `INSPECT`；雙角色帳號（F13 已知邊界改 E2E，CL-79） | — | — |

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 新建，取代 `TS-022-visit-report.md`：依 Approved PRD-022（直傳 GCS、CQ-19~22、BR-28、EX-28~32）與 SD-FULFILL 撰寫，旅程型，步驟 ID `CL-` | 全面重建 2026-11 |
| 2026-11-22 | 依 commit 477c75d 核准後修訂更新：`order_logs` / 通知 / `emailStatus` / 日誌、媒體、上傳槽位狀態改以 `INSPECT` 斷言（CL-01、03、04、06、07、10~12、16~19、22、25、26、28~31、36、38、42、44、45、53~57、61、62、66、78、81、83）；`CANCELLED` 行程改 E2E（CL-04，FT1 支援）；Email 偏好關閉改 E2E（CL-25，AC-3.1 情境 2）；FC1 / C8 帶 `?orderId=` 並斷言精確計數（CL-30、31、69）；401 先於冪等回放（CL-78）；雙角色帳號驗 F13 已知邊界（新增 CL-79，原 CL-79~83 順延為 CL-80~84）；AC-3.8 情境 5 移到 V5 仍可編輯時（新 CL-13，原 CL-84 改為原 CL-83 內容）；全形空白（CL-17）；影片文案與 `noise-big` fixture 依 SD-FULFILL §9.3；帳號改 `roles[]` / upsert（§1.1）；§4、§5 移除已解決項目 | 主流程交辦（核准後修訂） |
