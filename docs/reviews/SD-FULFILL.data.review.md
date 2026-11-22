# 審查：SD-FULFILL 資料與機制面（第 1 輪，2026-11-22）

範圍：§2.3 / §2.5 交易與鎖、§3.3 上傳槽位、§4.4–§4.8 流程、§5 遷移（`V20261122_01` 第 13–16 段）、§6.1 例外對映、§7 副作用、§8 排程 / 外部服務 / 部署清單、§10.2 cleanup、§12 CQ-1~9。API 契約細節與 AC 文字追溯由另一份審查負責。
比對來源：baseline 第 3 版 §3.6、§3.7、§5、§6；PRD-008 / 009 / 022 / 028（Approved）；SD-ORDER（Approved）§2.3.1、§2.4、§3.6、§5.1.1、§6.4、§8.1–§8.3；SD-GLOBAL-SPEC §1、§2.3、§4.1；SD-ERD。現況證據：遷移 V1、V6、V20260523_03、V20260614_01、V20260703_01；`BaseEntity.java`、`JpaAuditingConfig.java`、`Visit.java`、`InternalSecretFilter.java`、`GcsMediaStorageServiceImpl.java`、`application*.yml`、`pom.xml`。

> 依呼叫方指示，本輪沒有修改 SD 任何內容（含表頭狀態）。建議狀態：**Needs Clarification**。

## 結論

**Needs Clarification**。

先說沒問題的部分：
- **遷移 DDL**：DROP / RENAME 的欄位都真的存在（V1 的 `scheduled_at`、`finished_at`、`is_deleted`，V6 的 `snapshot_plan_title`，V20260523_03 的兩個索引與 `media_url`、`version`）。`idx_visits_status_scheduled` 會隨 `scheduled_at` 一起被刪。第 0 段 TRUNCATE 已涵蓋所有參照 `visits` 的表（只有 `visit_service_reports`），所以加 NOT NULL 都安全。Testcontainers 從空 DB 跑，任何種子遷移都沒有插入 `visits`，既有測試資料不會擋遷移。
- **鎖方向**：所有寫入都是「先鎖訂單、後動子列」。FC2 只鎖單一槽位列，不碰訂單；F18 / F19 也只改自己那一列槽位。沒有形成死結環。
- **額度**：在訂單鎖內重驗，可以保證不超賣。
- **同交易副作用**：`order_logs` 與 `enqueue()` 都在同交易內。
- **外部 I/O**：都在交易外。
- **secret**：沒有新增帶預設值的 secret。

問題集中在三處：

1. **三段式上傳的失敗路徑**。F18 失敗時一律刪除物件，但物件是「槽位共用」的，可能刪掉已經 COMPLETED 媒體的檔案。簽名 PUT 網址的有效期又比槽位長，FC2 清完之後物件還能被寫進來，變成永久孤兒。
2. **實體基底類別與新 DDL 不相容**。`BaseEntity` 固定映射 `is_deleted`、`version`、`@CreatedBy`，其中 `@CreatedBy` 會讓 O1 帶入檢核項時撞上 `ck_checklist_source`，導致建單失敗。
3. **連線與排程**。pool 只有 3 條，卻有 11 支排程在同一分鐘觸發；`createVisits` 的 3,600 列 batch 也沒有對應設定。

AC 覆蓋：§1 列 79 / 79（PRD-008 21、PRD-028 10、PRD-022 24、PRD-009 24），本面向未發現缺列。
統計：阻擋 3、重要 9、建議 12；新增待回答 2 項。

## 問題清單

| # | 嚴重度 | 位置（SD 行號） | 問題 | 建議 |
|---|--------|----------------|------|------|
| 1 | 阻擋 | §3.3 L266；§4.4 L365–375；§7.3 L893–894；§6.3 F18 L789 | **F18 失敗路徑會刪掉已完成媒體的物件。** 與 PRD 字面的單一請求不同，本 SD 的物件鍵綁定 `uploadId`，同一槽位的重試共用同一個物件。以下三種情況都會刪錯：(a) 同一 `uploadId` 的兩個 F18 並行（雙擊、前端 fetch 逾時後重送而後端仍在串流 50 MB），#2 先 commit 成 `COMPLETED`，#1 在交易 B 重驗「槽位 PENDING」失敗，走「業務失敗 → FAILED → best-effort 刪除物件」；`FAILED` 的條件式 UPDATE 影響 0 列，但刪物件這一步沒有條件，結果 `service_report_media` 指向一個已刪除的物件。(b) #1 等鎖逾時得到 `ORDER_BUSY`，走「刪物件、槽位維持 PENDING」，同樣沒先確認 #2 是否已完成。(c) L365 沒定義「鎖後槽位已 `COMPLETED`」要回什麼。這屬於「會造成資料錯誤的競爭」，不在 doc-conventions 豁免範圍。上游：baseline §3.6「外部 I/O」只要求刪除「本次」上傳的物件；PRD-022 AC-3.6 情境 1「GCS 物件仍 1 個」、EX-22 | (1) 交易 B 鎖後若槽位已 `COMPLETED`：回 201 與既有媒體（與 L789 的回放行為一致），不走失敗路徑。(2) 刪物件前一律先跑短交易：業務失敗時 `UPDATE ... SET status='FAILED' WHERE id=:id AND status='PENDING'` 影響 1 列才刪；`ORDER_BUSY` / 5xx 時重讀槽位，確認仍為 `PENDING` 且 `NOT EXISTS service_report_media WHERE upload_id=:id` 才刪。兩者任一不成立就不刪、交給 FC2。(3) `CareLogConcurrencyIntegrationTest` 加「同 uploadId 兩條執行緒同時 F18」案例：恰 1 筆媒體、物件仍存在 |
| 2 | 阻擋 | §4.4 L348、L351、L379；§6.3 F17 L783；§8.1 FC2 L908；§8.2 L947 | **簽名 PUT 網址比槽位活得久，FC2 會留下永久孤兒。** 槽位 30 分鐘，F17 每次簽 15 分鐘。若 F17 在第 29 分鐘呼叫，網址可用到第 44 分鐘。FC2 在第 31 分鐘之後把槽位轉 `EXPIRED`，刪除時「物件不存在視為成功」，接著寫入 `object_deleted_at`。之後 PUT 仍會成功（`if-generation-match=0` 只要求物件目前不存在），這個物件就再也不在任何候選條件內（FC2 看 `object_deleted_at IS NULL`，C10 只列 `payment-proofs/`）。`FAILED` 槽位也一樣：F18 失敗後刪了物件，網址仍有效。另外，F18 若在交易 B 以「鎖時的 now」判斷是否逾期，在第 29:50 開始、串流 20 秒的合法上傳會被判逾期並刪檔。上游：baseline §3.6「另有孤兒檔清除排程」；PRD-022 BR-15、EX-22 | (1) F17 簽名 TTL 改為 `min(upload-url-ttl, expires_at − now)`；剩餘時間小於下限（例如 2 分鐘）時直接回 409 `CARE_LOG_UPLOAD_EXPIRED`。(2) FC2 對 `PENDING` / `FAILED` 槽位只在 `now ≥ expires_at + upload-url-ttl`（寬限期，涵蓋簽名前已開始的長上傳）之後才刪物件並寫 `object_deleted_at`；或改為兩階段：先轉 `EXPIRED`，下一輪過了寬限期才刪。(3) F18 的槽位逾期判斷改用 F18 自己的 `receivedAt`（Controller 進入時間），交易 B 不再拿鎖後的 now 重比。§3.3 狀態圖與 §5.2 `ix_uploads_cleanup` 理由同步修改 |
| 3 | 阻擋 | §5.1 L448、L494–501、L511、L521–542、L553；§8.4.2 F-M4 / F-M5 | **`BaseEntity` 與新 DDL 不相容，會讓 O1 建單失敗。** 現況 `BaseEntity` 固定映射 `version`（`@Version`）、`is_deleted`、`created_by`（`@CreatedBy`）、`updated_by`，`Visit`、`VisitServiceReport`、`ServiceReportMedia` 都繼承它。本 SD 的變更：(a) 刪除三張表的 `is_deleted`，以及 `service_report_media.version`。(b) 新表 `visit_checklist_items` 沒有 `version` / `is_deleted`，`care_log_media_uploads` 連 `created_by` / `updated_by` 都沒有。沿用 `BaseEntity` 的話，local（`ddl-auto: validate`）啟動會失敗，prod 則在執行期 SQL 錯誤。(c) 最嚴重的是：`RequestAuditorAware` 回傳 token 使用者，O1 / E23 由 OWNER 觸發，`createVisits` 帶入檢核項時 `@CreatedBy` 會把 `created_by` 填成 OWNER 的 id，撞上 `ck_checklist_source`（SYSTEM 必須 `created_by IS NULL`）。整個建單交易因此失敗，再經 §6.1 L628 對映成可重試的 409 `ORDER_BUSY`（見 #7），客戶端只會無限重試。SD-ORDER 刪除 `orders.is_deleted` 也有同樣的實體問題（跨 SD） | §2.5 或 §8.4.2 明定實體基底：新增不含 `is_deleted` 的 `AuditedEntity`（id、created_at、updated_at）。`version` 只放在需要的實體上（`Visit`、`VisitServiceReport`，對應 L201）。`VisitChecklistItem` 的 `created_by` 不掛 `@CreatedBy`，由 Service 明確寫入（SITTER 時寫 token、SYSTEM 時寫 NULL）。`care_log_media_uploads` 不用 auditing。`ChecklistSeedIntegrationTest` 斷言「以 OWNER token 走 E1，檢核項的 `created_by IS NULL`」 |
| 4 | 重要 | §2.5 L196–198；§6.3 F5 / F10–F12 / F14 / F19 | **鎖前讀進來的 `Visit` 實體在鎖後是舊值。** SD-ORDER §3.6.1「鎖後新鮮度」只 refresh `Order`。不帶 key 的端點（F5、F10–F12、F14、F19）沒有交易 A，實作很可能把 G4 讀取與交易 B 包在同一個 `@Transactional` 裡。此時 L198 的「重讀行程」若用 JPQL / `findById`，Hibernate 會回傳一級快取裡鎖前的那個實體，不會用新資料覆寫。後果：F12 打勾時若行程剛被 FC1 轉成 `DONE`，會拿舊狀態通過檢查，最後被 `@Version` 擋下，回的是 `ORDER_BUSY`，而不是 PRD-008 AC-2.4 要求的 `VISIT_STATE_CONFLICT`（帶 `completionSource`）。F9 新增檢核項時行程剛被 C7 關閉，因為沒有更新 `visits` 列，`@Version` 擋不到，項目就寫進了已關閉的行程。上游：SD-ORDER §2.3.1「鎖後必須重讀 visit」；PRD-008 AC-2.4、PRD-028 AC-1.4 | §2.5 第 1 點改寫：鎖前一律只用投影查詢（例如 `VisitRef(visitId, orderId, sitterId)`），不載入 `Visit` / 日誌 / 檢核項實體；或規定鎖後以 `EntityManager.refresh` / native 查詢重讀。`VisitConcurrencyIntegrationTest` 的「打勾 vs FC1」斷言 409 `VISIT_STATE_CONFLICT`（不是 `ORDER_BUSY`） |
| 5 | 重要 | §3.3 L256、L266；§4.4 L371–380；§6.3 F17 L783、F18 L789；§7.3 L891；§9.3 L1061–1062 | **F18 各類失敗後的槽位狀態、刪不刪物件、前端怎麼重試，都沒有完整定義。** (a) 鎖前就能偵測的錯誤（預檢 409、SHA-256 不符 400、magic bytes / 大小 / 時長 422）槽位要轉 `FAILED` 還是維持 `PENDING`，物件刪不刪：L256 寫「檔案不符 → FAILED」，L371 的序列圖卻只畫出交易 B 的分支。(b) 「物件不存在」與「SHA-256 不符」都回 400 `field=file`，前端無法分辨要重新 PUT 還是放棄。(c) `DISCARDED` 槽位在 F17 / F18 的行為未定義。F16 同 key 回放後拿到已刪媒體的 `uploadId`，結果不明。(d) L891 / L1062 寫「重試沿用同一 key 從 F16 重跑」，但槽位一旦 `FAILED` / `EXPIRED`，同一把 key 永遠回放同一個死槽位（24h 內），使用者卡住。(e) 依 PRD-022 AC-3.7 情境 4，`ORDER_BUSY` 時物件會被刪除，§9.3「`ORDER_BUSY` 同 key 自動重試一次」對 F18 而言必須退回 F17 + PUT，否則會拿到 400。上游：PRD-022 EX-21、EX-22、AC-2.8 情境 2、AC-3.7 情境 4 | §3.3 加一張「F18 失敗類別 × 槽位狀態 × 物件處置 × 前端動作」表。400 加 `payload.reason`（`OBJECT_MISSING` / `SHA256_MISMATCH`）。F17 / F18 對 `DISCARDED` 回 404（或 409 加 `failureCode=DISCARDED`）。§9.3 規定：收到 `CARE_LOG_UPLOAD_EXPIRED`、`FAILED` 回放、`SHA256_MISMATCH` 時前端**換新 key** 從 F16 重來；收到 `OBJECT_MISSING` 時同槽位重做 F17 + PUT |
| 6 | 重要 | §8.1 L907–911；§8.3 DF1 L954 | **排程同分鐘疊加，pool 3 條會被吃光。** SD-ORDER D5 的 C1–C8 都是 `*/15`，C11 是 `*/5`，本 SD 又加 FC1 `*/15`、FC2 `0 * * * *`。每到整點有 11 支排程同時打進來，Cloud Run 很可能全派給同一個實例。每支排程逐筆處理時佔 1 條連線、最長 50 秒；只要 3 支同時有候選，使用者請求就會在 D8 的 `connection-timeout: 5000` 後失敗（`CannotCreateTransactionException` → 500）。上游：SD-ORDER §8.3 D5 / D8、技術決策「Hikari ≤ 3」 | 本 SD：FC1 改 `7-59/15 * * * *`、FC2 改 `37 * * * *`，避開 :00 / :15 / :30 / :45。跨 SD：建議 SD-ORDER 把 C1–C8 錯開（例如每支差 1 分鐘），或排程協調者在「目前可用連線 ≤ 1」時提早結束本輪（屬推測性方案，需驗證 Hikari MXBean 讀值）。DF1 驗證欄加「整點並行觸發 FC1 + C7 + 一般請求，一般請求不逾時」 |
| 7 | 重要 | §6.1 例外 L628 | **`DataIntegrityViolationException` 一律對映 409 `ORDER_BUSY`（可重試），會把程式錯誤藏起來。** CHECK（23514）、NOT NULL（23502）、FK（23503）違反都是確定性錯誤，重試只會再撞一次。#3 的 `ck_checklist_source` 就會讓客戶端無限重試。延遲唯一約束（`ux_checklist_sort`）的違反發生在 commit 時，也需要確認會被轉成同一種例外。上游：baseline §3.7 `ORDER_BUSY` 的語意是「鎖 / 版本衝突、可重試」；SD-ORDER §6.4 有同樣寫法（跨 SD） | 依 SQLSTATE 分流：23505 且約束名屬已知防線（`visit_service_reports_visit_id_key`、`ux_report_media_upload`、`ux_checklist_sort`、`ux_visits_slot`）→ 409 `ORDER_BUSY`；其餘一律 500 `INTERNAL_ERROR` 並記錄 ERROR log。整合測試補「F11 commit 時延遲約束違反 → 409、冪等紀錄被刪」 |
| 8 | 重要 | §5.1 L439、L447–449、L476–477；§12 CQ-3 | **與 SD-ORDER 第 13 段的重疊必須「只做一次」。** SD-ORDER §5.1.1 L1264–1275 已寫 `RENAME finished_at TO completed_at`、`ADD service_date / order_item_id / cancelled_at / closed_at`，並建了同名索引 `ix_visits_order_status`、`ix_visits_zombie`。如果兩段都照寫進同一支 SQL，Flyway 會因 `column "finished_at" does not exist` 或 `relation already exists` 失敗，部署在啟動時中斷。SD-ORDER §2.4.2 也還寫著 `createVisits` 要寫 `scheduled_at`（本 SD 把它刪了）。SD-ORDER 已是 Approved，改它的 §5.1.1 / §2.4.2 屬規格內容變更（doc-conventions：退回 In Review）。另外，V1 的 `idx_visits_order (order_id)` 被 `ix_visits_order_status` 的前導欄涵蓋，屬冗餘 | CQ-3 的處理要明確：SD-ORDER 第 13 段整段刪除，改成「見 SD-FULFILL §5.1」；由使用者決定這是否讓 SD-ORDER 退回 In Review。本 SD 第 13 段加 `DROP INDEX IF EXISTS idx_visits_order;`。實作驗收：`V20261122_01` 內 `RENAME COLUMN finished_at` 恰出現 1 次 |
| 9 | 重要 | §8.2 L919 | **local 簽名上傳端點會被 `InternalSecretFilter` 擋掉。** `InternalSecretFilter.java:35` 對所有 `/api/internal/` 開頭的路徑要求 `X-Internal-Secret`，瀏覽器直傳 `PUT /api/internal/local-storage/**` 不可能帶這個標頭，local 開發的上傳流程整條斷掉 | 改用不在 `/api/internal/` 底下的路徑（例如 `/local-storage/**`，只在 `@Profile("local")` 註冊），以 HMAC 簽名 query 驗證，並模擬 `Content-Type`、大小範圍、`if-generation-match` 的行為（含 412），讓 local 的語意與 GCS 一致 |
| 10 | 重要 | §8.2 L927、L932；§8.3 DF2 L955、DF4 | **bucket 層設定要確認的不只 CORS。** (a) 現況 bucket 是 fine-grained ACL（`GcsMediaStorageServiceImpl.java:67` 以 `Acl.User.ofAllUsers()` 設成公開），簽名 PUT 沒帶 `x-goog-acl` 時，物件會套用 bucket 的 **default object ACL**。若預設值含 allUsers，新上傳的日誌媒體會是公開的，違反 PRD-022 BR-18 的私有物件要求。(b) DF2 寫的是「Firebase Hosting 正式網域」，但 GLOBAL §1.2 說前端由 Spring Boot 內嵌、跟 Cloud Run 同一個 service。實際的 Origin 可能是 `*.web.app`、`*.firebaseapp.com`、自訂網域、`*.run.app` 其中幾個，必須逐一列出。CORS 設定作用於整個 bucket（含 `payment-proofs/`），但只開 `PUT` 加簽名，風險可接受。(c) 前端若用 `fetch` 讀媒體（不是 `<img>` / `<video>`），CORS 還要開 `GET` | DF 加一項：唯讀確認 bucket 的 `defaultObjectAcl` 不含 allUsers / allAuthenticatedUsers；不符時要使用者同意後修改。F18 檢驗時順便呼叫既有的 `isPubliclyReadable`，若為 true 就 `revokePublicRead` 並記 WARN log。DF2 列出確切 origin 清單；註明 CORS 只開 `PUT`（讀取以元素直接載入、不經 fetch） |
| 11 | 重要 | §4.8 L431 | **3,600 列的 JDBC batch 沒有對應設定。** repo 裡沒有 `hibernate.jdbc.batch_size`、`order_inserts`、`reWriteBatchedInserts`（grep 無結果）。沒有這些設定時，`saveAll` 會逐列送出：O1 在持有訂單鎖的交易內，經 Supabase Transaction Pooler 往返 3,600 次，以每次 5–10 ms 推估要 18–36 秒，期間佔住 1 條連線，`statement` 層也沒有逾時保護（推測值，未實測） | §8.2 明列設定：`spring.jpa.properties.hibernate.jdbc.batch_size=100`、`order_inserts=true`，JDBC URL 加 `reWriteBatchedInserts=true`（與 `prepareThreshold=0` 相容）。實體 id 維持應用端產生 UUID（`BaseEntity` 已是 `GenerationType.UUID`，不會被 IDENTITY 關掉 batch）。`ChecklistSeedIntegrationTest` 加「60 日 × 3 次 × 20 項」案例，斷言語句數量級（例如用 datasource-proxy 計數 ≤ 50） |
| 12 | 重要 | §1 L73；§4.4 L348、L379；§6.1 L626 | **`received_at` 的定義前後不一。** L73 寫「上傳以槽位建立時間」，L626 寫「F18 的期限基準為槽位的 `received_at`」。若實作用 `created_at DEFAULT now()` 或 INSERT 當下的時間，就會晚於 Controller 進入時間（中間還有交易 A 與預檢）。期限邊界測試（PRD-022 AC-1.6，02:59:59 / 03:00:00）會因此變得不穩定 | 統一寫成：`received_at` = F16 Controller 進入時以注入 `Clock` 取得的 `receivedAt`，由 Service 明確寫入，不用 DB 預設值。L73 同步修改 |
| 13 | 建議 | §5.1 L551 | `ix_uploads_cleanup` 的 partial 條件是 `object_deleted_at IS NULL`，但 `COMPLETED` 槽位的 `object_deleted_at` 永遠是 NULL，所以這個索引最後會涵蓋所有已完成上傳，越長越大 | 條件改為 `WHERE object_deleted_at IS NULL AND status <> 'COMPLETED'` |
| 14 | 建議 | §5.1 L521–566 | CHECK 有缺口：槽位缺 `(status IN ('COMPLETED','DISCARDED')) = (completed_at IS NOT NULL)`、`expires_at > received_at`、`declared_sha256 ~ '^[0-9a-f]{64}$'`、`content_type IN (5 種)`、`(media_type='VIDEO') = (declared_duration_seconds IS NOT NULL)`；媒體缺 `size_bytes > 0`；檢核項的 `created_by` 沒有 FK 到 `users` | 補上；理由寫進 §5.2 |
| 15 | 建議 | §8.2 L927 | `x-goog-content-length-range` 目前只限制在類型上限（`1,{max−1}`）。F16 已經有宣告大小，可以綁成 `{declared_size},{declared_size}`，大小不符時 GCS 在 PUT 階段就拒絕，不必等 F18 串流後才發現 | 綁定宣告大小；F18 仍然要驗實際大小（不信任宣告） |
| 16 | 建議 | §4.4 L359–366 | F18 檢驗與交易 B 之間，物件版本沒有固定下來。若 #1 修好，只有後端會刪物件，風險很低；但仍建議把 `generation` 記下 | 檢驗時記下物件 `generation`，寫入 `service_report_media`（或槽位）；之後的讀取 / 刪除帶 `generation` 前置條件 |
| 17 | 建議 | §8.2 L936；§6.3 MediaItem L750；§11 決策 11 | 簽名 GET 網址快取：撤權風險分析下來**沒有比不快取更大**。每次 F13 都先做授權才交付網址，已交付網址的最長曝露時間在簽名時就固定為 15 分鐘，快取不會延長；F19 / PRD-013 刪除後網址只會 404，`purged` 由 DB 判斷。但有三點要補：(a) `urlExpiresAt` 必須是快取項目的原始到期時間，不能寫成 now + 15m。(b) 交付時剩餘 ≥ 5 分鐘，與 PRD-022 BR-18「有效期 15 分鐘」字面有落差（AC-3.2 只驗 `X-Goog-Expires=900`，仍會通過）。(c) `pom.xml` 沒有 Caffeine，屬於新增依賴 | 寫明 (a)；(b) 列入 CQ-5 一併讓使用者確認，或把快取縮到 5 分鐘（交付時剩餘 ≥ 10 分鐘）。F13 的簽名須在 `@Transactional(readOnly)` 範圍外執行（L199 已有原則，F13 契約再寫一次） |
| 18 | 建議 | §6.3 FT2 L809 | `SERVICE_DATE` 平移單一行程可能撞 `ux_visits_slot`（同群組同日序號），結果會被 #7 對映成 `ORDER_BUSY`，測試人員看不出原因 | FT2 `SERVICE_DATE` 改為「整群組（同 `order_item_id`）一起平移」，或衝突時回 409 並在 payload 說明 |
| 19 | 建議 | `JpaAuditingConfig.dateTimeProvider` | 稽核時間用 `OffsetDateTime.now()`，不是注入的 `Clock`；`uploaded_at`、`checked_at`、`started_at` 若依賴 auditing，FT2 與邊界測試無法控制時間 | §2.5 明定業務時間欄一律由 Service 以注入 `Clock` 寫入；auditing 只負責 `created_at` / `updated_at` |
| 20 | 建議 | §8.1 FC2 L908；§2.3.2 `markPurged` L172 | FC2 對長期刪不掉的物件（權限錯誤）每小時重試，沒有上限，也沒有告警。PRD-013 `markPurged` 刪了物件之後，`COMPLETED` 槽位的 `object_deleted_at` 仍是 NULL，兩邊紀錄不一致 | 槽位加 `delete_attempts SMALLINT`，超過 N 次記 ERROR log（觸發 Error Reporting）。`markPurged` 同交易寫入槽位的 `object_deleted_at`（同模組表，不違反擁有權） |
| 21 | 建議 | §10.2 L1121 | cleanup「先刪 GCS 物件再刪列」沒有寫交易邊界。若整個 `E2eJourneyDataCleanupService` 包在 `@Transactional` 裡，就會在持有連線時逐一呼叫 GCS | 先以唯讀查詢收集物件鍵 → 在交易外刪 GCS → 短交易刪資料列 |
| 22 | 建議 | §4.4 F16 L346–348 | F16 的「短交易 INSERT 槽位 + 冪等 COMPLETED」沒寫要帶 `claim_token` 條件（SD-ORDER §3.6.2 段 3 的規則）。回放 body 固定為 `status: PENDING`，但槽位實際可能已是 `COMPLETED` | 寫明完成 UPDATE 帶 `WHERE claim_token=:mine AND status='PROCESSING'`，影響 0 列就回滾。回放 body 拿掉 `status`，或註明「回放值為建立當下狀態」 |
| 23 | 建議 | §8.4.1 L970；§4.8 | `service_plans.default_tasks` 是任意 JSONB，可能混入非字串元素或 null | `findDefaultTasks` 遇到非字串元素就略過（與「去空白後為空即略過」同一處理），不拋例外，否則 O1 會整筆 500 |
| 24 | 建議 | §2.5 L197；§11 決策 4 L1133 | 理由寫「`@ManyToOne` 預設 EAGER」，但現況 `Visit.java:18` 是 `FetchType.LAZY`。結論（不建關聯）仍然成立，只是理由要修正 | 改寫理由：關聯讓本模組能經由行程修改訂單，而且 LAZY 代理在鎖前觸發時，同樣有鎖後新鮮度問題 |

## §12 CQ-1~9 意見

| CQ | 意見 |
|----|------|
| CQ-1 | 同意新增批次唯讀 `getOrderContexts`。補兩點：(1) `adminReadable` 在讀取當下計算（不取鎖）即可，因為只用於 GET；(2) `ownerDisplayName` / `sitterDisplayName` 來自 `users`（SD-IDENTITY），要確認 SD-ORDER 是以 JOIN 取得還是再呼叫 SD-IDENTITY，維持「固定查詢次數」。這是對 Approved SD-ORDER §2.4.1 新增介面，依 doc-conventions 屬規格變更，由使用者決定是否讓 SD-ORDER 退回 In Review |
| CQ-2 | 方向同意，但不建議用「ArchUnit 對 order 套件開例外」的方式。冪等是跨模組機制，建議把 `IdempotencyService`、`IdempotencyRecordRepository`、`IdempotencyInterceptor`、`IdempotencyBodyCachingFilter` 搬到共用套件（例如 `application/service/common/idempotency`），表的擁有權寫進 SD-GLOBAL-SPEC（SD-ORDER §11.3 本來就寫了「供其他模組沿用」）。另外兩點：(1) `@Idempotent` 標註只有 HandlerInterceptor 看得到；`IdempotencyBodyCachingFilter` 在 handler mapping 之前執行，看不到標註，要改用 `HandlerMappingIntrospector` 判斷，或改成「所有 `/api/**` 的 JSON POST / PUT 都快取 ≤ 64 KB」。(2) F16 的完成步驟不取訂單鎖，`complete()` 只能要求 `MANDATORY` 交易，不能要求持有訂單鎖。M28 依建議改寫 |
| CQ-3 | 同意，並補問題 #8：SD-ORDER 第 13 段整段刪除（不只改註記），否則 Flyway 會失敗 |
| CQ-4 | 同意。cleanup 順序（媒體 → 槽位 → 日誌 → 檢核項 → 行程）符合 FK。補兩點：GCS 刪除在交易外（#21）；FT2 的 `SERVICE_DATE` 平移規則（#18） |
| CQ-5 | 推薦採用直傳。Cloud Run HTTP/1 單一請求 32 MiB 上限是事實；Firebase Hosting 轉發層的限制，SD 自己標了「未驗證」，我同意這個標示。採用前必須先修好阻擋 #1、#2 與重要 #5，否則「失敗刪物件」「孤兒清除」這兩個可觀察結果其實不成立。另外請使用者一併確認兩件事：(a) 槽位 30 分鐘等於「期限前 1 秒建立槽位，可在期限後最多 30 分鐘完成上傳」，SD 把這解讀為 BR-5「上傳耗時不影響」，屬 PRD 解讀而非純技術選擇；(b) PRD-022 AC-3.7 情境 4 規定 `ORDER_BUSY` 時要刪物件。在槽位設計下，保留物件（槽位維持 `PENDING`、交給 FC2）可以省掉 50 MB 重傳，但要改 PRD 字面。建議預設：(a) 接受；(b) 維持 PRD 現狀（刪除），不改 PRD |
| CQ-6 | 同意 code point。PostgreSQL `char_length` 與 `VARCHAR(n)` 在 UTF8 DB 都以字元（code point）計，前後一致。前端必須用 `[...str].length`（或 `Intl.Segmenter` 以外的 code point 計數），不能用 `str.length`（UTF-16 單位）。emoji 旗幟、膚色組合會算成 2 個以上，屬可接受落差，建議寫進 TS-022 的邊界案例 |
| CQ-7 | 不在本面向範圍；資料面無意見 |
| CQ-8 | 同意截斷、不擋建單。資料面補 #23：`findDefaultTasks` 要能容忍非字串元素 |
| CQ-9 | 同意 `ORDER_AFFAIR`。資料面補充：現行 `chk_notification_category` 還有 `SERVICE_RECORD`（V20260614_01 / V20260719_04），舊版照護日誌通知可能用的是這個類別，而 SD-ORDER 第 0 段已一併刪除這類通知。請在 CQ-9 寫明：本 SD 不使用 `SERVICE_RECORD`；`notification_preferences` 裡既有的 `SERVICE_RECORD` 偏好列是否保留，交由 SD-NOTIFY 決定 |

## 待使用者回答

| # | 問題 | 建議預設值 |
|---|------|-----------|
| Q1 | （CQ-5 延伸）槽位 30 分鐘讓「期限前建立槽位、期限後完成上傳」成立。這是否符合 PRD-022 BR-5「上傳耗時不影響」的本意？ | 接受；槽位 TTL 維持 30 分鐘，PRD 不改 |
| Q2 | （CQ-3 延伸）刪除 SD-ORDER §5.1.1 第 13 段、修改 §2.4.2 `createVisits` 註記、新增 `getOrderContexts`、開放冪等機制，都是對 Approved SD-ORDER 的規格變更。要讓 SD-ORDER 退回 In Review，還是以「同發布配套修訂」記錄在其變更紀錄中、維持 Approved？ | 退回 In Review，四項併成一次修訂，與 SD-FULFILL 一起核准 |

## 跨 SD 衝突

1. **SD-ORDER §5.1.1 第 13 段 vs 本 SD §5.1 第 13 段**：rename 與兩個同名索引重複（#8）。
2. **SD-ORDER §2.4.2 `createVisits`** 還寫著要寫 `scheduled_at` / `snapshot_plan_title`，本 SD 把前者刪除、後者改名（CQ-3）。
3. **SD-ORDER §8.1 C7** 呼叫的是 `closeZombieVisits(orderId, now)`，但 §2.4.2 與本 SD L160 的簽名是 `(LockedOrder, now, Duration)`，SD-ORDER 內部前後不一致。
4. **SD-ORDER §6.4 與本 SD §6.1**：`DataIntegrityViolationException` 一律對映 `ORDER_BUSY`，兩份有同一個問題（#7）。
5. **SD-ORDER §8.3 D5 的 */15 排程與本 SD FC1 / FC2** 同分鐘疊加，pool 3 條不夠用（#6）。
6. **`BaseEntity` 的 `is_deleted` / `version` / `@CreatedBy`**：SD-ORDER 的 orders / modification_requests 與本 SD 三張表都刪了 `is_deleted`，兩份 SD 都沒交代實體基底要怎麼改（#3）。
7. **冪等機制擁有權**：SD-ORDER 把 `idempotency_records` 列為自己擁有的表，`OrderAggregateArchTest` 又限制只有 order 套件能依賴其 Repository；本 SD 需要使用（CQ-2），建議改由 GLOBAL 擁有。
