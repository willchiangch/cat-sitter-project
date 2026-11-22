# SD-FULFILL 審查紀錄（API 與追溯面）

> 範圍：§1 AC 對應、§2.3 / §2.4 介面（與 SD-ORDER 的一致性）、§6 API 契約與判斷順序、§7 通知、§8.4 同發布改寫清單、§9 前端、§10 測試設計、§12 CQ-1~9。資料模型、遷移、鎖與併發內部由另一位審查員負責，本文件不深入。
> 基準：PRD-008 / PRD-009 / PRD-022 / PRD-028（Approved 2026-11-22）、DOMAIN-BASELINE 第 3 版（§1.2、§3.6、§3.7）、SD-ORDER（Approved）、SD-FRONTEND-SPEC、SD-GLOBAL-SPEC。
> 唯讀查證：`backend/src/main/java`（`OrderRepository`、`VisitRepository`、`CareLog` / `CareLogRepository`、`SecurityConfig`、`InternalSecretFilter`）、`backend/src/test/java`、`frontend/src`（`axiosClient.ts`、`hooks/`、`routes.tsx`）、`frontend/e2e/**`、`firebase.json`、`docs/api/openapi.json`。

---

## 第 1 輪（2026-11-22）

### 結論

**Needs Clarification**：1 項阻擋、9 項重要、14 項建議；新增 CQ-10～CQ-12 待使用者回答（另 CQ-5、CQ-7 原本就需使用者決策）。

- AC 點數：以 `^- \[.\] \*\*AC-` 逐張點數，PRD-008 21、PRD-028 10、PRD-022 24、PRD-009 24，**合計 79**，與 SD 宣稱一致。§1 四個子表逐條比對 **79 / 79 皆有列**，無缺號、重號或 PRD 不存在的 AC。§10.1「對應 AC」欄反向展開同樣涵蓋 79 條（PRD-009 AC-1.7 只由 TS 驗證，§10.1 末段已註明）。
- 錯誤碼：四張 PRD 例外表的錯誤碼在 §6.5 或 SD-ORDER §6.5 全部找得到；`VISIT_STATE_CONFLICT` 在 `currentVisitStatus=DONE` 時帶 `completionSource`，§3.1 第 232 行、§6.3 第 662 行、§6.5 第 830 行三處一致（PRD-008 CQ-11）。本 SD 自創 1 個碼 `CARE_LOG_UPLOAD_EXPIRED`（§6.5 第 832 行），源自直傳設計，見 #B1。
- 判斷順序：F3 / F4 / F5 / F9–F12 / F14 / F15 / F16 / F19 的錯誤鏈逐一對照 PRD-008 §4.1 步驟 3、§4.7 步驟 2、BR-8，PRD-028 BR-8，PRD-022 §3 第 1–11 步，皆一致；混合角色 GET（F1 / F2 / F8 / F13）採 SD-ORDER G4a，位置（G4 → G4a → G5）與 SD-ORDER §6.1 相同。PRD-022 §3「唯一結果」六例逐一代入 F14 / F16 皆得 PRD 指定結果。
- 404 歸屬：`visit.order_id ≠ 路徑 orderId`、項目 / 媒體 / 槽位不屬路徑行程 → 404（§2.5 第 196 行、§6.1 第 623 行），符合 PRD-022 §3、PRD-028 §3；F10 / F19 鎖後重讀不存在仍 404，PRD-028 AC-1.3 情境 1、PRD-022 AC-2.6 情境 2 / 3 成立。
- testid：PRD-008 §9（29 個）、PRD-028 §9（9 個）、PRD-022 §9（15 個，另引用行程列）、PRD-009 §9（24 個）在 §9.1 / §9.2 全部出現，名稱一致；`owner-order-detail-visit-row-{visitId}` 已統一（PRD-008 CQ-14），無殘留 `-visit-item-`。
- 通知：§7.2 四個類型、收件者、文案與 PRD-008 §4.3 步驟 3、§4.4 步驟 3、PRD-022 BR-17 字面一致；`mandatory=false`、站內必寫 Email 依偏好；PRD-009 的 `ORDER_COMPLETED_BY_OWNER` / `ORDER_AUTO_COMPLETED` / `SERVICE_DISPUTE_OPENED` 由 SD-ORDER §7.2 擁有，§1.4 引用正確。
- 與 SD-ORDER 介面：§2.3.1 六個方法簽名與 SD-ORDER §2.4.2 相同；`VisitView` 欄位與 SD-ORDER E2 `visits[]` 所需一致；PRD-009 的 API（E2、E3、E25、E26、E30、C8）未重複定義。

主要問題：(1) CQ-5 說直傳「可觀察結果不變」不成立，使用者無法據此決策（阻擋）；(2) 直傳三段式的前後端重試契約有兩個缺口（F17 已完成 vs 已逾時的先後、F18 `ORDER_BUSY` 後的重試路徑），PRD-022 AC-3.6 情境 3、AC-3.7 情境 4 會失敗；(3)「以 HTTP `Date` 標頭取伺服器時間」在帳務總覽沒有可用的前一個請求，也無法配合注入的 `Clock`；(4) 同發布改寫清單漏了會讓後端啟動失敗與排程靜默失敗的兩個查詢、一個類別名稱衝突，以及 5 支 E2E spec 與 2 個 hook。

### 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議 |
|---|-------|------|------|------|
| B1 | 阻擋 | §12 CQ-5（第 1153 行）、§11 決策 1、§4.4 | CQ-5 寫「錯誤碼、判斷順序、期限以首個請求時間判斷、額度不超賣、失敗刪物件等可觀察結果不變」「PRD-022 AC 不需改寫」，但直傳實際改變了以下 PRD-022 規格：(a) PRD-022 §4.3-4 步驟 6–7「檔案預檢在 GCS 上傳前完成，不通過即不寫 GCS」：直傳只能預檢**宣告值**，實際內容（magic bytes、SHA-256、`mvhd` 時長）在物件已進 GCS 後才於 F18 檢驗，偽造宣告的 GIF / 14 秒影片會先寫入 GCS 再 best-effort 刪除，AC-2.3 情境 2、AC-2.4 情境 2–4「GCS 無新物件」變成「最終無物件」；(b) 新增錯誤碼 409 `CARE_LOG_UPLOAD_EXPIRED` 與 30 分鐘槽位期限，PRD-022 BR-5「上傳耗時不影響」在 F16 → F18 超過 30 分鐘時不成立；(c) 新增 400 原因（SHA-256 不符、物件不存在、`VIDEO` 缺 `durationSeconds`、`contentType` / `sizeBytes` / `sha256` 格式），PRD-022 BR-14 / EX-5 只列 4 種；(d) AC-2.8 情境 1「GCS 回 5xx → 上傳請求得 503」：瀏覽器 PUT 失敗時後端不會回 503，只有 F17 簽名失敗或 F18 讀取失敗才有；(e) AC-2.5 情境 1 / AC-3.6 情境 1 的「上傳回應 body」拆成 F16（`uploadId`）與 F18（媒體 + 已用量）兩個，回放只對 F16 | CQ-5 改列上述 (a)–(e) 讓使用者決策；採用直傳時由主流程在 PRD-022 加註（不改 AC 條數）：§4.3-4 步驟 6–8 改為「宣告值預檢 → 簽名直傳 → 實際內容檢驗，不符刪物件」、BR-5 加槽位上限、EX-5 補 400 原因、EX-21 補「瀏覽器上傳失敗不經後端」、AC-2.8 情境 1 的 503 指 F17 / F18；PRD-022 依 doc-conventions 退回 In Review。CQ-5 理由中「Firebase Hosting 代理層限制未經驗證（推測同樣有限制）」需標明為推測（Cloud Run HTTP/1 32 MiB 有官方出處，Firebase Hosting rewrite 的上限需查證） |
| I1 | 重要 | §6.3 F17（第 783 行）、§4.4 第 378 行 | F17 的錯誤只寫「409 `CARE_LOG_UPLOAD_EXPIRED`（`EXPIRED` 或 `now ≥ expires_at`）」，沒寫「槽位 `COMPLETED` → `alreadyCompleted=true`」排在逾時檢查之前。PRD-022 AC-3.6 情境 3（第一次成功後已過期限，同 key 重送仍 201）：F16 回放同一 `uploadId` → F17 時槽位已 `COMPLETED` 且必然 `now ≥ expires_at`（30 分鐘）→ 若先判逾時就回 409，前端流程中斷，§4.4 第 378 行「完整流程結果與第一次相同」不成立 | F17 錯誤鏈改為：G4 → G5 → 槽位 `COMPLETED` → 200 `alreadyCompleted=true`（不看 `expires_at`）→ 槽位 `FAILED` → 回存下的錯誤（同 F18，不要套 `CARE_LOG_UPLOAD_EXPIRED`）→ `EXPIRED` 或 `now ≥ expires_at` → 409 → 503。`CareLogIdempotencyIntegrationTest` 必測「完成後 31 分鐘以上同 key 走完 F16→F17→F18」 |
| I2 | 重要 | §9.3 第 1057、1061–1062 行、§4.4 第 374 行、§7.3 第 894 行 | F18 遇 `ORDER_BUSY` 時後端刪除物件、槽位維持 `PENDING`（符合 PRD-022 AC-3.7 情境 4），但 §9.3 共通規則「`ORDER_BUSY` 以同一 key 自動重試一次」會讓前端直接重打 F18，此時物件已刪 → 400 `VALIDATION_FAILED`（`field=file`），AC-3.7 情境 4「鎖釋放後以 K 同內容重試 → 201」失敗。步驟 4 的「從步驟 3 重跑」只涵蓋網路 / GCS 失敗 | §9.3 上傳流程明定：F18 回 `ORDER_BUSY` / 5xx → 從 F17 重跑（重新取網址、重新 PUT、再 F18），不可只重打 F18；F18 回 `FAILED` 類業務錯誤 → 顯示錯誤，使用者重選檔案時產生新 key。TS-022 AC-3.7 情境 4 照此寫步驟 |
| I3 | 重要 | §9.2 第 1045、1049 行、§1.4 AC-3.4（第 118 行） | 「伺服器時間取 API 回應的 HTTP `Date` 標頭」：(1) 帳務總覽 E30 的 `month` 必填（PRD-009 AC-3.4 缺 `month` → 400），進頁前沒有任何請求可取 `Date`，SD 未說明預設月份從哪個回應取得；(2) `Date` 由 Tomcat / Google 前端依系統時間產生，與後端注入的 `Clock` 無關，整合測試與 `shift-time` 造出的時間點無法反映在畫面判斷上（PRD-009 AC-2.2「時鐘 2026-11-22 12:00」、AC-3.4「時鐘 `2026-11-30T16:30:00Z`」只能靠 mock E2E 偽造標頭）；(3) 結案提示 iii 由前端用 F1 `deadlineAt` + `Date` 比較，按鈕卻依 E2 `allowedActions`，兩支請求、兩個時間點，邊界附近可能「沒有按鈕也沒有提示」或兩者並存。同源（Firebase rewrite `/api/**`，`firebase.json`）下 `Date` 標頭可讀，這點沒問題 | 改由後端以注入 `Clock` 計算：E2 增加 `serverTime`，或直接回 `completionHint`（`MODIFICATION_IN_PROGRESS` / `VISITS_IN_PROGRESS` / `CARE_LOG_EXPIRED` / `CARE_LOG_PENDING` / null，與 `allowedActions` 同一套前置條件同次計算），i–v 只讀 E2（E2 已有 `openModification`、`visits[].status / completedAt / careLogSubmitted`、`disputeWindowEndsAt`）；帳務預設月份另定來源（例：E30 回應帶 `serverMonth`，前端先以瀏覽器月份呼叫一次再校正；或 SD-GLOBAL-SPEC 新增 `GET /api/server-time`）。屬 SD-ORDER E2 / E30 契約變更，列 CQ-10 |
| I4 | 重要 | §8.4.2 F-M6、F-M11；SD-ORDER M26 | 兩個查詢不在任何改寫清單：(1) `OrderRepository.findOrdersReadyForAutoComplete`（`OrderRepository.java:32–35`）JPQL 用 `v.order = o` 與 `v2.scheduledAt`，F-M4 移除 `@ManyToOne order`、§5.1 刪除 `scheduled_at` 後，Spring Data 啟動時驗證 JPQL 失敗，**整個後端無法啟動**；SD-ORDER M6 刪除 `CompletionService` 但 M26 未列此方法。(2) `OrderRepository.findOrdersPendingMediaExpiryWarning`（`:69–85`，native SQL）讀 `srm2.is_deleted`，§5.1 第 553 行刪除該欄後，PRD-013 媒體到期提醒排程**每次執行都 SQL 失敗**（native query 不在啟動時驗證，只會在排程 log 出錯），且它是 SD-ORDER 的 Repository 直讀本 SD 的表 | F-M 新增兩列：(1) 刪除 `findOrdersReadyForAutoComplete`（與 SD-ORDER M6 同發布）；(2) `findOrdersPendingMediaExpiryWarning` 的 `EXISTS (... service_report_media ...)` 改呼叫本 SD `CareLogMediaRetentionPort` 新方法（例 `hasUnpurgedMedia(Set<UUID> orderIds)`），或至少拿掉 `is_deleted` 條件並在 §2.3.2 登記為排程候選唯讀例外。`FulfillAggregateArchTest` 會擋到 (2)，須一起處理 |
| I5 | 重要 | §2.3.1 第 165 行、§8.4.2 F-M2 | 現有 `domain/model/CareLog.java`（`care_logs` 表，`V20260523_01`，SD-CARE 的照護操作稽核）與 `domain/repository/CareLogRepository.java` 已存在，`CareMediaServiceTest`、`CareNoteServiceTest`、`KycTrustScoreTest` 依賴它。SD 規劃的 `CareLogRepository`（日誌）與 ArchUnit「`CareLogRepository` 只能被 fulfill 套件依賴」會與之撞名，照字面實作會讓 SD-CARE 違反 ArchUnit 或覆蓋既有類別 | 本 SD 的 Repository / Service / Controller 改用不衝突的名稱（例 `VisitCareLogRepository`、`VisitCareLogService`、`VisitCareLogController`，或沿用表名 `ServiceReportRepository`）；§2.3.1 ArchUnit 清單與 F-M2 同步；§11 決策 7 註記 `care_logs` 已被 SD-CARE 使用（這也是不改表名的另一個理由） |
| I6 | 重要 | §8.4.2 F-M15、F-M16、F-M20 | 前端與 E2E 呼叫點漏列（以 grep `visits` / `visit-report` / `/api/visits/` 查得）：`frontend/src/hooks/useOrders.ts`（`useOrderVisitsQuery`，型別 `VisitSummaryDto[]`）、`frontend/src/hooks/useVisitReport.ts`（舊日誌 API 與 `version`）；`e2e/journeys/order-modification-negotiation.spec.ts:140–146`（讀 F1 為陣列與 `scheduledAt`）、`e2e/journeys/trust-gating-boundary.spec.ts:191–200`（`visits[0].id`、`POST /api/visits/{id}/start`）、`e2e/offline-payment.spec.ts:23`、`e2e/owner-cancel-booking.spec.ts:135、199`（mock F1 回陣列）、`e2e/ui-layout-contrast.spec.ts:473–495、837`（`/visit-reports/manage/`、`/api/visits/.../report` mock；N18 對比度迴歸以舊日誌頁為對象，SD-FRONTEND-SPEC §2.3 有引用）。後端測試另有 `NotificationListenerLinkTest`（`VisitNotificationEvent`）、`OrderIdentityFromTokenSecurityTest`（`Visit.builder` 舊欄位）。F1 回應由陣列改為 `{ orderId, orderStatus, visits }`，所有讀陣列的地方都會壞 | F-M15 補兩個 hook；F-M20 補上列 5 支 spec 與 2 個後端測試（`ui-layout-contrast` N18 改以新服務面板為對象，SD-FRONTEND-SPEC §2.3 同步）；兩支 journey 屬 SD-ORDER 範圍的也要在 SD-ORDER M30 或本表擇一登記，避免兩邊都以為對方改 |
| I7 | 重要 | §9.1 第 1021 行（`/sitter/orders/:orderId` 行程區塊「空：同上」） | 「同上」= 訂單 `PENDING` / `PENDING_PAYMENT` / `CANCELLED` 不顯示行程區塊，這是 PRD-009 BR-24 對**飼主**詳情的規則（AC-1.8 只驗飼主）。PRD-008 CQ-15 明寫報價前的保母審閱頁會顯示 `sitter-order-visit-label`（以 `booking_plan_title` 組成名稱），`booking_plan_title` 欄位（§5.1 第 450 行）的存在目的就是這個畫面；照 §9.1 實作，該欄位在畫面上永遠用不到 | 保母詳情的行程區塊顯示範圍另寫：`PENDING` / `PENDING_PAYMENT` 顯示（唯讀，名稱用 `booking_plan_title`），`CANCELLED` 是否顯示列 CQ-11（建議：顯示，與飼主不同，保母需要看到被取消的行程）；不要寫「同上」 |
| I8 | 重要 | §8.2 第 919 行（`LocalMediaStorageServiceImpl` 簽名網址指向 `PUT /api/internal/local-storage/**`） | `SecurityConfig.java:64` 對 `/api/internal/**` 要求 `ROLE_INTERNAL`，`InternalSecretFilter.java:35` 只認 `X-Internal-Secret`。local profile 下瀏覽器 PUT 不帶 secret（前端也不能持有 secret，硬性規則 6）→ 403，本機開發與以 local 後端跑的 E2E 都無法上傳 | 改用 `/api/local-storage/**`（只在 local profile 註冊、`permitAll`），以網址內的簽名參數（HMAC + 到期時間）驗證；或 local 直接回 GCS emulator 網址。§10.2 註明 mock E2E 以 `page.route('https://storage.googleapis.com/**')` 攔 PUT |
| I9 | 重要 | 表頭第 14 行「不修改 SD-ORDER」、§12 CQ-1~4 | CQ-1（新增 `getOrderContexts`）、CQ-2（`IdempotencyService` 對外、`@Idempotent`、ArchUnit 例外、M28 改寫）、CQ-3（§5.1.1 第 13 段、`createVisits` 契約）、CQ-4（§10.4 契約）都是改 SD-ORDER 的介面 / 資料表 / 測試契約，依 doc-conventions「修改已 Approved 文件的規格內容 → 退回 In Review」，SD-ORDER 會回到 In Review，與「本 SD 不修改 SD-ORDER」的敘述不符。另 SD-ORDER 內部自相矛盾、本 SD 依賴的點：§8.1 C7 寫 `closeZombieVisits(orderId, now)`，§2.4.2 是 `(LockedOrder, now, Duration)`；§9.1 `/owner/orders` 空狀態「各分頁尚無訂單」與 PRD-009 BR-23（本 SD §9.2 第 1037 行）不同 | 表頭改寫為「需 SD-ORDER 配合修訂（§12 CQ-1~4），SD-ORDER 隨之退回 In Review，兩份同批核准」；CQ-3 一併請 SD-ORDER 修正 C7 呼叫簽名、§9.1 `/owner/orders` 歷史分頁空狀態改引本 SD §9.2，並在 M26 補 `findOrdersReadyForAutoComplete`（#I4） |
| S1 | 建議 | §9.1 第 1018 行錯誤欄、§9.3 | 服務面板同時承載 PRD-008 / 028 / 022 的操作，同一錯誤碼各 PRD 文案不同：`VISIT_STATE_CONFLICT` 在打勾時 `PENDING` →「開始行程後才能打勾」、編輯時 `DONE` →「此行程已結束，無法修改」（PRD-028 EX-8）；`ORDER_STATE_CONFLICT` 日誌操作 →「訂單已結束，無法編輯日誌」（PRD-022 EX-6）vs 行程操作「訂單狀態已變更，請重新整理」（PRD-008 EX-6）；`ROLE_MISMATCH`「請切換至保母身分」vs「請切換至正確角色」。§9.1 只寫「依 PRD-008 EX-7」 | §9.1 或 §9.3 加「操作 × 錯誤碼 → 文案」表（行程 / 檢核項 / 日誌三組），`CARE_LOG_UPLOAD_EXPIRED` 也給文案（例「上傳逾時，請重新選擇檔案」） |
| S2 | 建議 | §6.3 F11（第 732 行）、F5（第 699 行）、F12（第 740 行）、F16（第 774 行） | (1) F11 `itemIds` 宣告為 `UUID[]`：值不是 UUID 時 Jackson 在進方法前回 400，早於 404 / 403，違反 SD-ORDER §6.1「body 內的 UUID 欄宣告為 `String`」規則（PRD-028 AC-1.6 情境 4 的 404 先於 400 精神）；(2) F5「非字串也是 400」：Jackson 預設會把數字 `123` 轉成字串 `"123"`，宣告 `String` 擋不到；F12 `checked` 宣告 `boolean` 時字串 `"true"` 也會被接受；(3) F16 `durationSeconds` 為 number，未寫預檢前是否四捨五入（BR-13「四捨五入至整數秒」，AC-2.4 14.5 秒） | §6.3 註明 DTO 型別：`itemIds` 為 `List<String>`；`name` / `checked` 以 `JsonNode` 接收由 `FulfillRequestValidator` 判型別（或全域關閉 scalar coercion，影響其他模組需列出）；`durationSeconds` 預檢以與 `MediaInspector` 相同的 half-up 取整數秒 |
| S3 | 建議 | §6.3 F17（第 783 行）、§8.2 第 947 行 | 簽名上傳網址 TTL 固定 15 分鐘，未與槽位 `expires_at` 取小：槽位剩 1 分鐘時簽出的網址仍可用 15 分鐘，F18 一定回 409，且 FC2 已把槽位轉 `EXPIRED` 並刪過物件後，遲到的 PUT 會留下沒有任何列記錄 `object_deleted_at = NULL` 的物件 | `urlExpiresAt = min(now + 15m, expires_at)`；或 FC2 以 `expires_at + upload-url-ttl` 判逾時（給在途上傳留寬限），F18 以「槽位 `PENDING` 且物件存在」為準。與資料面審查員對齊 |
| S4 | 建議 | §9.2 結案提示表（第 1041–1047 行） | (1) 表格未限定訂單 `IN_PROGRESS`：`CONFIRMED` 訂單會命中 ii、`DISPUTED` 會命中 ii–iv，與 PRD-009 §4.1「飼主在訂單詳情頁（`IN_PROGRESS`）」及 §4.4 `DISPUTED` 不顯示結案動作不符；(2) iii 的 `{disputeWindowEndsAt}` 已過但仍 `IN_PROGRESS` 時，提示文字是否同樣改「即將自動結案」未定（PRD-009 BR-21 只講兩個 testid） | 表頭加「只在 E2 `status=IN_PROGRESS` 時判斷」；iii 已過時沿用 BR-21 的「即將自動結案」措辭（與兩個 testid 一致） |
| S5 | 建議 | §6.3 FT1（第 797–807 行）、FT2（第 809 行） | (1) FT1 未限制訂單狀態：對 `PENDING_PAYMENT` 訂單強制 `IN_PROGRESS` 會造出正式路徑不可能的狀態（PRD-008 T1 前置條件）；(2) FT1 沒有 `closedAt`、FT2 沒有 `CLOSED_AT` target，PRD-009 AC-2.1 / AC-2.6 需要指定 `CLOSED_BY_SYSTEM` 的關閉時間以推算 `completion_basis_at`；(3) FT2 `COMPLETED_AT` 會呼叫 `onVisitFinished` 重算基準，會覆蓋先前以 SD-ORDER `shift-time COMPLETION_BASIS_AT` 設定的值，TS 呼叫順序需說明 | FT1：訂單非 `CONFIRMED` / `IN_PROGRESS` / `DISPUTED` → 409 `ORDER_STATE_CONFLICT`；加 `closedAt`；FT2 加 `CLOSED_AT`；§10.2 註明「先 FT1 / FT2，再 SD-ORDER `COMPLETION_BASIS_AT`」 |
| S6 | 建議 | §9.1 第 1020 行 | 飼主行程列展開檢核項的空狀態「保母未設定檢核項」（PRD-028 §4.4、AC-1.1 情境 1、AC-2.3）未列；PRD-028 未給項目 testid，SD 以 `data-checked` 標示，TS 需要定位單項時無 testid | 空欄補文案；項目 testid 若需要，由主流程補進 PRD-028 §9（例 `owner-order-detail-visit-checklist-item-{itemId}`），SD 不自取 |
| S7 | 建議 | §8.4.2 F-M18 | 移除行程 `CONFIRMED` 狀態文字，但 SD-FRONTEND-SPEC §6.4 第 196 行仍寫「`PENDING`/`CONFIRMED` 待執行」 | 同發布修 SD-FRONTEND-SPEC §6.4，並補「完成來源 `SYSTEM` 加註『系統自動結束』」（PRD-008 BR-17） |
| S8 | 建議 | §10.1 `CloseZombieVisitsIntegrationTest`、PRD-009 五個測試類別 | (1) C7 與 SD-ORDER `ZombieVisitCronIntegrationTest` 都打 C7 端點，必測重點部分重疊；(2) `OwnerCompleteIntegrationTest` 等五個類別測的是 SD-ORDER 的 E25 / E26 / C8 / E30 / E3，與 SD-ORDER `CompletionIntegrationTest` / `CompletionBasisIntegrationTest` 重疊，未寫放在哪個測試套件、由誰維護 | 寫明分工：C7 端點計數與 O9 由 SD-ORDER 測，本 SD 測 `closeZombieVisits` 的行程寫入與通知；PRD-009 類別放 `application/service/order` 測試套件、以 `@DisplayName("PRD-009 AC-x.y …")` 命名，SD-ORDER §10.3 對應列註明「PRD-009 AC 由 SD-FULFILL §10.1 承接」 |
| S9 | 建議 | §6.3 F13（第 751 行） | F13 的 G4 含「OWNER / ADMIN 讀取時日誌非 `SUBMITTED` → 404」，判斷依據是 token 角色；該單保母以 OWNER token 讀自己的草稿會先得 404 而不是 G4a `ROLE_MISMATCH`。PRD-022 §3 第 3 步 `ROLE_MISMATCH` 在 404 之前，但 G4a 本來就只能放在 404 之後，屬已知取捨 | 在 F13 錯誤欄註明此邊界與理由，`CareLogAuthorizationIntegrationTest` 以實際行為斷言，避免 TS-022 照 PRD 字面寫 403 |
| S10 | 建議 | §4.4 第 378 行、§1.3 AC-3.6 | `idempotency_records.expires_at = now + 24h`（SD-ORDER §3.6.2）。PRD-022 AC-3.6 情境 3「第一次成功後已過期限，同 key 重送仍回放」：若第一次上傳在行程 `IN_PROGRESS` 早期，期限可能在 24h 之後，屆時紀錄已過期，同 key 會重新處理並得 409 `DEADLINE_PASSED` | §10.1 `CareLogIdempotencyIntegrationTest` 情境限定在 TTL 內；TS-022 同步註明。是否需調整 TTL 不在本 SD 範圍 |
| S11 | 建議 | §9.3 第 1064 行 | 「字數計數器以 code point 計」：`<textarea maxLength>` 以 UTF-16 code unit 計，emoji 會被提早截斷 | 註明不得使用 `maxLength`，以 `[...value].length` 計數並在 `onChange` 截斷 |
| S12 | 建議 | §6.3 F16（第 772–774 行） | 宣告的 `contentType` 被簽進 PUT 的 `Content-Type`，GCS 物件 metadata 因此等於宣告值；之後 F13 簽 GET 網址時瀏覽器依此 `Content-Type` 解析。偽造宣告的檔案在 F18 被拒就刪除，不影響；但成功的檔案 metadata 與實際格式一致性依賴 F18 檢驗結果 | F18 檢驗通過後若 magic bytes 判定格式與宣告 `contentType` 不同（例宣告 `image/jpeg` 實為 PNG，兩者都允許），寫明是拒絕（422）還是改寫 metadata；建議拒絕，規則最單純 |
| S13 | 建議 | §7.2 第 879 行 | FC1 通知「每行程一則」；同一訂單同批逾時多個行程時保母收多則，與殭屍關閉「同批合併 1 則」做法不同。PRD-008 §4.4 步驟 3 文案是單一行程，SD 的選擇有出處 | 維持；在 §7.2 註一句「PRD-008 §4.4 未要求合併，故逐行程」，避免實作時被當成遺漏 |
| S14 | 建議 | §6.2 FT1 / FT2、§8.4.3 | `docs/api/openapi.json` 現有 `/api/visits/{visitId}/report`、`/report/submit`、`/media`、`/start`、`/end`、`/api/visits/media/{mediaId}`、`/api/orders/{orderId}/visits`，§8.4.3 已列刪除 / 改語意，與新端點無路徑衝突（`/api/visits/sitter/dashboard`、`/api/visits/owner/today` 在舊路徑刪除後不會與 `/api/visits/{visitId}` 撞樣板）。另 `/api/internal/cron/media/cleanup`（PRD-013）與 FC2 職責相近 | §8.1 FC2 註明與 `/api/internal/cron/media/cleanup` 的分工（前者清未成媒體的槽位物件，後者清保留期滿的媒體） |

### 同發布改寫清單補漏（§8.4.2）

| 補列 | 檔案:行 | 現況 | 建議處置 |
|------|--------|------|---------|
| F-M21 | `OrderRepository.java:32–35` `findOrdersReadyForAutoComplete` | JPQL `v.order`、`scheduledAt`；刪欄後啟動失敗 | 刪除（併 SD-ORDER M6 / M26），#I4 |
| F-M22 | `OrderRepository.java:69–85` `findOrdersPendingMediaExpiryWarning` | native SQL 讀 `service_report_media.is_deleted` | 改經 `CareLogMediaRetentionPort`，#I4 |
| F-M23 | `domain/model/CareLog.java`、`domain/repository/CareLogRepository.java`（SD-CARE 既有） | 與本 SD 規劃類別撞名 | 本 SD 改名，#I5 |
| F-M24 | `frontend/src/hooks/useOrders.ts:36–39`、`frontend/src/hooks/useVisitReport.ts` | 舊型別 / 舊 API | 依 F1 / F13–F19 重寫，#I6 |
| F-M25 | `e2e/journeys/order-modification-negotiation.spec.ts`、`e2e/journeys/trust-gating-boundary.spec.ts`、`e2e/offline-payment.spec.ts`、`e2e/owner-cancel-booking.spec.ts`、`e2e/ui-layout-contrast.spec.ts` | 讀 F1 陣列、`scheduledAt`、`/api/visits/{id}/start`、舊日誌頁 mock | 依新契約改寫，#I6 |
| F-M26 | `NotificationListenerLinkTest`、`OrderIdentityFromTokenSecurityTest` | `VisitNotificationEvent`、`Visit.builder` 舊欄位 | 刪除 / 改寫 |
| F-M27 | `SD-FRONTEND-SPEC.md` §6.4、§2.3（N18 引用舊日誌頁） | 文件 | 同發布修，#S7、#I6 |

### 抽查紀錄（每張 PRD ≥ 4 條，含例外與併發）

| PRD AC | 走查結果 |
|--------|---------|
| PRD-008 AC-1.1 | F3：鎖後訂單 `CONFIRMED` → `onVisitLeftPending` 寫 `ORDER_STARTED`、本 SD 不寫 `VISIT_STARTED`（§4.1 第 321 行），恰 1 列，通過 |
| PRD-008 AC-1.6 情境 6 | 他人保母 + 不存在行程 + 無 key：G4（404）先於 G6（400），DTO 不掛 `@Valid`、key `required=false`，通過 |
| PRD-008 AC-1.7 情境 3 | 同 key 換行程 id：hash 含路徑變數 → G2 不回放 → G7 422，通過 |
| PRD-008 AC-2.4 情境 3 | FC1 先 → F4 得 409 `VISIT_STATE_CONFLICT`（`DONE` + `completionSource=SYSTEM`），前端依 payload 選文案，通過 |
| PRD-008 AC-3.7 情境 6 | `CLOSED_BY_SYSTEM` + 21 字：G6 先於 L，400；`COMPLETED` + 逾期：`ORDER_STATE_CONFLICT` 先於 `DEADLINE_PASSED`，通過 |
| PRD-028 AC-1.5 情境 4 | `DONE` + 已 20 項：`VISIT_STATE_CONFLICT`（第 9 步）先於上限 400（第 12 步），通過 |
| PRD-028 AC-1.6 情境 3 | 以 V1 路徑刪 V2 項目：G4 404，通過 |
| PRD-028 AC-2.2 情境 1 | `CONFIRMED` 訂單打勾：F12 訂單須 `IN_PROGRESS` / `DISPUTED` → `ORDER_STATE_CONFLICT`，通過 |
| PRD-028 AC-1.3 情境 3 | F11 `[I3, I2, I2]`：G6 集合比對 400；若 id 字串非 UUID 則進方法前 400（#S2），結果同為 400 但位置不同 |
| PRD-022 AC-1.5 情境 2 | 已送出且逾期暫存：`CARE_LOG_STATE_CONFLICT` 先於 `DEADLINE_PASSED`，通過 |
| PRD-022 AC-1.6 情境 3 | F16 02:59:59 建槽位、F18 03:00 後完成：期限以槽位 `received_at`，201，通過（前提：30 分鐘內完成，#B1 (b)） |
| PRD-022 AC-2.7 | 額度剩 1、兩把 key：F16 皆通過、F18 鎖序列化，後到者 422 並刪物件，通過 |
| PRD-022 AC-3.6 情境 3 | F16 回放 → F17：`COMPLETED` 與逾時先後未定（#I1），可能 409 |
| PRD-022 AC-3.7 情境 4 | F18 `ORDER_BUSY` → 前端依 §9.3 只重打 F18 → 物件已刪 → 400（#I2），失敗 |
| PRD-022 AC-3.8 情境 3 | 逾期 + 101 字說明：F16 G6 先於預檢，400、無槽位無物件，通過 |
| PRD-009 AC-1.2 | 提示 ii / iv 由 F1；按鈕由 E2 `allowedActions`；兩來源（#I3 (3)） |
| PRD-009 AC-2.2 | 提示 iii 依 `Date` 標頭與 F1 `deadlineAt`；TS 只能在 mock E2E 偽造時間（#I3 (2)） |
| PRD-009 AC-3.4 | 預設月份無請求可取 `Date`（#I3 (1)） |
| PRD-009 AC-1.8 | 飼主 `PENDING` / `PENDING_PAYMENT` / `CANCELLED` 不顯示行程列，通過；保母端誤用同規則（#I7） |

### §12 CQ-1~9 意見

| CQ | 意見 |
|----|------|
| CQ-1 | 同意新增批次唯讀方法，避免 N+1 與越界 JOIN；屬 SD-ORDER 介面新增（#I9）。補兩點：(1) `planTitles` 以 `planId` 為鍵的前提是 `order_plan_snapshots` 每張訂單每個方案只有一列；若變更新增的同方案另有快照列，鍵需改 `orderItemId`；(2) `adminReadable` 的計算與 SD-ORDER E27 的可讀範圍必須共用同一個 policy 物件，SD-ORDER 文件寫明。是否最小必要：可接受，但 `pets`、`ownerDisplayName` / `sitterDisplayName` 只有 F2 / F6 / F7 用到，可考慮拆成兩個方法（輕量的 `getOrderContexts` 給 F1 / F13 權限判斷，`getDashboardCards` 給儀表板），讓權限路徑的查詢較少 |
| CQ-2 | 同意，且是必要的（PRD-008 BR-10、PRD-022 BR-21 都要求回放而非 409，舊 `checkAndConsume` 做不到）。最小改法：把 `IdempotencyService`、`IdempotencyInterceptor`、`IdempotencyBodyCachingFilter` 搬到共用套件（例 `application/service/common/idempotency`），以 `@Idempotent` 標註取代 SD-ORDER §3.6.2 寫死的端點清單；`IdempotencyRecordRepository` 仍只許共用套件依賴。注意 SD-ORDER §3.6.2 段 2「交易外 I/O 只有 E11」的敘述要改成通用（F16 也有交易外步驟，雖然 F16 本身不碰 GCS） |
| CQ-3 | 同意。補：SD-ORDER M26 一併刪除 `findOrdersReadyForAutoComplete`（#I4）、修正 §8.1 C7 的 `closeZombieVisits` 呼叫簽名（#I9）。屬資料表與介面契約變更，SD-ORDER 需退回 In Review |
| CQ-4 | 同意。補：cleanup 清單加 `visit_checklist_items`、`care_log_media_uploads` 與其物件鍵（§10.2 已寫，SD-ORDER §10.4 要逐字同步）；`force-status` 契約改為「見 SD-FULFILL FT1」以免兩份文件各寫一版；`plans[].defaultTasks` 的上限不需在 provision 擋（測試要能造超過 20 項驗 PRD-028 CQ-7 截斷） |
| CQ-5 | 技術上推薦直傳的理由成立（50 MB 超過 Cloud Run HTTP/1 32 MiB），但 CQ 敘述需依 #B1 改寫後再請使用者決定；採用時 PRD-022 需加註並退回 In Review。另需使用者同意雲端資源變更 DF2（CORS）、DF5（刪舊公開物件），依硬性規則 5 當次分開同意 |
| CQ-6 | 同意 code point。補前端不得用 `maxLength`（#S11）；PostgreSQL `char_length` 在 UTF8 資料庫以字元計，與 Java `codePointCount` 一致（組合字 / ZWJ emoji 會計多個，三端一致即可） |
| CQ-7 | 建議縮小範圍：本 SD 只需要「行程」入口（PRD-008 §4.5、§4.6 引 BR §2.1 / §2.2），全站 5 項底部導覽牽動訂單、收款、通知、設定等其他模組，屬全域前端決策，應寫進 SD-FRONTEND-SPEC（§3 目錄已預留 `BottomNav`），不在 SD-FULFILL 定義。testid `nav-bottom-{item}` 沒有 PRD 出處，若採用需在 SD-FRONTEND-SPEC §4 登記為全域元件例外（同 `btn-role-toggle`）。列 CQ-12 |
| CQ-8 | 同意，無需動作；狀態維持「待 PRD-003」 |
| CQ-9 | 同意四個代碼與 `ORDER_AFFAIR`（與 SD-ORDER §10.4 `emailPreferences` 的值一致）。`VISIT_AUTO_COMPLETED`、`CARE_LOG_SUBMITTED` 與 `order_logs` action 同名，命名空間不同可接受；F-M17 前端連結解析需涵蓋四者 |

### 待使用者回答

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-5（改寫） | 日誌媒體改瀏覽器直傳 GCS，接受 #B1 (a)–(e) 的行為差異（惡意檔案短暫進 GCS 後刪除、30 分鐘槽位、新錯誤碼 `CARE_LOG_UPLOAD_EXPIRED`、新 400 原因、瀏覽器上傳失敗不經後端 503）？ | 採用直傳；PRD-022 §4.3 / BR-5 / EX-5 / EX-21 加註、退回 In Review 後再核准。不採用則影片上限需 < 32 MB（業務變更） |
| CQ-10（新） | 畫面需要伺服器時間的三處（結案提示 iii、「即將自動結案」、帳務預設月份）改由後端回傳，而非讀 HTTP `Date` 標頭？ | SD-ORDER E2 新增 `serverTime` 與 `completionHint`（以注入 `Clock` 計算，與 `allowedActions` 同次）；E30 回應新增 `serverMonth`，前端首次以瀏覽器月份呼叫、與 `serverMonth` 不同時改用並重查。SD-ORDER 隨 CQ-1~4 一起修訂 |
| CQ-11（新） | 保母訂單詳情的行程區塊在訂單 `PENDING` / `PENDING_PAYMENT` / `CANCELLED` 是否顯示？（PRD-009 BR-24 只規定飼主；PRD-008 CQ-15 預期保母審閱時看得到行程名稱） | `PENDING` / `PENDING_PAYMENT` 顯示（唯讀，名稱用 `booking_plan_title` / O2 快照）；`CANCELLED` 顯示（含已取消標示）。由主流程回寫 PRD-008 §4.6 步驟 3 |
| CQ-12（原 CQ-7 改寫） | 底部導覽是否在本次做成全站 5 項，或只加「行程」入口？ | 只加「行程」入口（保母 `/sitter/visits`、飼主 `/owner/visits`），掛在現有導覽；全站 `BottomNav` 另於 SD-FRONTEND-SPEC 定義後再做 |
