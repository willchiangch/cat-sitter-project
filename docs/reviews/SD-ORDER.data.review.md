# 審查：SD-ORDER 資料與機制面（第 1 輪，2026-11-22）

範圍：§2.4 跨模組介面、§3.6 共用機制、§5 遷移 `V20261122_01__order_rebuild.sql`、§8 排程與部署清單、§11.1 抽樣缺陷避開方式。API 契約與 AC 追溯由另一份審查負責。
比對來源：現有遷移 V1–V20261025_01（逐檔讀過）、`backend/src/main/java/com/petsitter/**`、`application*.yml`、`.github/workflows/deploy.yml`、`reports/rereview/00-sample-SD-007.md`、`docs/reviews/00-DOMAIN-BASELINE.sd.review.md` 第 2 輪 §3。

## 結論

**Needs Clarification**。遷移 DDL 本身大致正確：DROP 的欄位、索引都真的存在，TRUNCATE 清單已涵蓋所有 FK 參照表（visits、order_snapshots、order_logs、modification_requests、referral_logs，以及經 visits 連到的 visit_service_reports、service_report_media），不加 CASCADE 也不會失敗；partial unique index 正確表達 baseline 規則。問題集中在兩處：

1. 冪等「接手」後的寫入保護不足，E11 的失敗路徑可能刪掉已提交 submission 所引用的 GCS 物件。
2. 發布範圍沒有定義。這支遷移改了 `orders`、`visits`、`order_logs`、`order_snapshots`、`modification_requests`，但尚未重建的模組（照護日誌、毛孩、照護記事、轉介、媒體保存、通知 listener）還在直接讀寫這些表，部署後會編譯失敗或在執行期出錯。另外，SD-FULFILL 必須先提供的 `VisitCommandService` / `VisitQueryService` 目前不存在。

阻擋 2、重要 7、建議 10；新增待回答 4 項（CQ-20–23）。

## 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議 |
|---|--------|------|------|------|
| 1 | 阻擋 | §3.6.2 段 1 / 段 3 / 失敗、§3.6.6、§7.3 | **接手後的寫入保護不足，失敗路徑會刪錯物件。** (a) 交易 B 最後的 `UPDATE idempotency_records ... WHERE id=:id AND status='PROCESSING'` 偵測不到「已被接手」：接手只改 `locked_until`，狀態仍是 `PROCESSING`，所以原請求與接手請求都能通過這道防線。(b) E11 的物件鍵由認領紀錄 id 決定，接手者會沿用同一把鍵（§3.6.2 段 2 刻意這樣設計）。情境：原請求 B 已 commit 並建好 submission，接手者重驗時得到 409 `PAYMENT_STATE_CONFLICT`，走失敗路徑「best-effort 刪除段 2 的物件」，刪掉的正是已提交 submission 引用的憑證，造成資料遺失。(c) 失敗分支「可記錄的業務錯誤（409 / 422）→ 改 `COMPLETED`」沒有排除 409 `IDEMPOTENCY_IN_PROGRESS`（被接手）。(d) 「5xx → 刪除紀錄」沒有條件：commit 結果不明（連線在 commit 時中斷，但其實已提交）或 commit 之後才出錯時，若紀錄已是 `COMPLETED` 也會被刪掉，同 key 重試會重做一次。E1 可能因此建出第二張訂單 | DDL：`idempotency_records` 加 `claim_token UUID NOT NULL`（每次認領或接手都換新值）。交易 B 的完成 UPDATE、交易 C 的 UPDATE / DELETE 一律加 `WHERE id=:id AND status='PROCESSING' AND claim_token=:mine`；影響 0 列就不動任何東西。409 `IDEMPOTENCY_IN_PROGRESS` 明列為「不記錄、不刪除」。E11 失敗路徑只在交易 C 確認「本請求仍持有 token，且 `NOT EXISTS payment_submissions WHERE proof_object_key = :key`」後才刪物件，否則交給 C10。§10.2 補「接手後原請求完成」「commit 後出錯」兩個案例 |
| 2 | 阻擋 | 表頭「依賴的 SD」、§2.3.1、§5.1、§8.3、§12 | **發布範圍沒有定義，未重建模組會壞。** 現況證據：`VisitReportService.startVisit` 第 518–524 行直接 `visit.setStatus("IN_PROGRESS")`、`order.setStatus("IN_PROGRESS")`，不經訂單鎖；同檔有 8 處經 `AuditLogService.writeOrderLog`（`REQUIRES_NEW`）寫 `operator_id`。遷移後 `operator_id` 被刪、新增的 `operator_role` 為 `NOT NULL`，每次開始 / 結束行程、寫照護日誌都會失敗。`PetService` 第 124、250 行讀 `order.getItems()`，對應的 `orders.items` 已刪。`CompletionService`、`NotificationListener`、`MediaRetentionServiceImpl`、`AuthService`、`CareRelationshipGuard`、`ReferralService`、`TestDataCleanupService` 都依賴舊 `Order` entity。SD-FULFILL（重建順序 2，未開始）要提供的 `createVisits` / `cancelPendingVisits` / `closeZombieVisits` / `findOrderIdsWithZombieVisits` 目前不存在。§11.2 #3 只處理了 `visits` 欄位，沒處理程式碼 | 新增 §8.4「同發布過渡改寫清單」，逐項列出本次發布必須一併修改的非本 SD 程式，以及各自改走哪個介面（清單見下方「重建時必須移除的舊呼叫點」）。另外說明 SD-FULFILL 兩個 Service 由誰先實作（見 CQ-21） |
| 3 | 重要 | §2.3.1、§2.4.1、§3.6.1 | **鎖的新鮮度與鎖順序沒有規範。** SD-FULFILL 開始行程時只知道 `visitId`，必須先讀 visit 才能拿到 orderId；`Visit.order` 是 `@ManyToOne`，預設 EAGER，所以呼叫 `lockForTransition` 之前 `Order` 已經在 persistence context 裡。之後原生 `FOR NO KEY UPDATE` 查詢不會覆寫已受管的 entity，重驗時讀到的是鎖前的舊值。若 SD-FULFILL 先以 `FOR UPDATE` 鎖 visit 列再鎖訂單，與 E23（先鎖訂單再改 visits）方向相反，會產生死結（40P01）。此外，55P03 只在 `OrderLockService` 內被轉換；同交易後續語句（`lock_timeout` 對整個交易有效）或死結拋出的 `CannotAcquireLockException` / `PessimisticLockingFailureException` 會變成 500，冪等紀錄也不會被刪 | `lockForTransition` 規範：鎖成功後，若該 `Order` 已受管則 `refresh`，回傳的 `LockedOrder` 一律取鎖後的值。§3.6.1 加鎖順序規則：任何交易先鎖 `orders` 列，之後才能讀寫 visits / payments / 變更列；SD-FULFILL 只能用不鎖的查詢取得 orderId，且鎖後必須重讀 visit。`GlobalExceptionHandler` 與冪等失敗分支把 55P03、40P01（含 Spring 轉換後的例外）統一對映為 409 `ORDER_BUSY` 並刪除冪等紀錄 |
| 4 | 重要 | §2.4.2 `findOrderIdsWithZombieVisits`、§8.1 C7 | **C7 候選介面缺訂單狀態條件。** 簽名只有 `(maxServiceDate, limit)`，SD-FULFILL 又不擁有 `orders`，無法排除 `PENDING_PAYMENT`。INITIAL 已 `SUBMITTED`、保母一直不核對的訂單會長期留在 `PENDING_PAYMENT`（C3 只處理 `UNPAID`，O16 要等 ADMIN 處理），它的過期 `PENDING` 行程每輪都會被選進 `LIMIT 200`，之後才被 C7 過濾掉。累積夠多時，真正的殭屍單永遠排不到。baseline 審查 §3.3 的原始寫法有 `JOIN orders ... status IN (...)`，搬到介面後遺失了 | 見 CQ-22 |
| 5 | 重要 | §5.1.1 第 13 段、§2.4.2 `createVisits` | **`visits` 舊的 NOT NULL 欄位沒有交代。** `visits.scheduled_at` 在 V1 是 `NOT NULL`，本遷移沒放寬；`Visit` entity 的 `plan_id`、`snapshot_plan_title` 標 `nullable=false`。`VisitSpec` 只有 `orderItemId`、`planId`、`serviceDate`、`sequenceInDay`，`createVisits` 不知道 `scheduled_at` / `snapshot_plan_title` 該寫什麼。另外 `VisitView.completedAt` 在 DB 裡實際叫 `finished_at` | 見 CQ-23。`VisitView` 註明 `completedAt` 對應 `visits.finished_at` |
| 6 | 重要 | §8.3 D1、D2 | **移除預設值後 CI 會失敗。** `application-local.yml` 被 `.gitignore`（`.gitignore:47`）；`application.yml` 第 30–32 行的註解已說明 CI checkout 後沒有這個檔，拿掉預設值後所有 `@SpringBootTest` 都會因 placeholder 無法解析而失敗。deploy.yml 的 `mvn test` 步驟也沒有注入這兩個值 | D1 / D2 補一句：測試用值放 `backend/src/test/resources/application.yml`（或測試 profile 檔），不放 main。驗證欄加「CI `mvn test` 綠燈」 |
| 7 | 重要 | §8.3 D1、§11.1 #1 | **既有密文是用公開預設金鑰加密的。** prod 從未注入 `APP_CRYPTO_KEY`（deploy.yml 的 `--set-secrets` 沒有這一項），所以 `profiles.bank_account_info` 全部是以 repo 內預設金鑰加密（V20260530_01 的種子密文同樣如此）。D1 換成新金鑰後這些密文都解不開，O2 的 `getSnapshot` 會 500。§11.1 #1 宣稱已避開，但只處理了「以後」，沒處理「已經存在的」 | 見 CQ-20 |
| 8 | 重要 | §10.4 Cleanup 擴充 | **刪除順序違反 FK。** `admin_tasks.payment_id REFERENCES payments(id)`，但清單把 `payments` 排在 `admin_tasks` 之前，第一次清理就會 FK 失敗。現有 `E2eJourneyDataCleanupService` 在刪 orders 之後才刪 `service_plans`、`pets`，而 `order_items` / `order_plan_snapshots` / `order_item_pets` 以 FK 參照這兩張表，所以這三張必須在它們之前刪 | 改為 `payment_submissions` → `admin_tasks` → `payments`（先刪帶 `source_payment_id` 的列）→ …；註明 `order_items`、`order_item_pets`、`order_plan_snapshots` 必須早於現有的 `service_plans`、`pets` 刪除步驟。建議加一個整合測試：建好全套訂單資料後跑 cleanup，斷言成功 |
| 9 | 重要 | §3.3 T9、§4.4、§5.1.1 `ck_modreq_quoted` / `reason` / `type` | **SYSTEM 變更列沒寫要填哪些欄位，C6 可能每輪都失敗。** `ck_modreq_quoted` 要求非 `REQUESTED`、非 OWNER 終態的列 `new_total_amount IS NOT NULL`；`reason` 為 `NOT NULL`；`type` 只允許 `CHANGE` / `CANCEL_ALL`。T9 只寫了 `AWAITING_REFUND`、`source_modification_id`，沒寫 `type`、`reason`、`new_total_amount`、`quoted_*`。實作者漏填任一欄就會撞 CHECK，C6 整筆回滾、每輪計 `failed`，補款逾時永遠處理不掉 | T9 明列 SYSTEM 列的欄位值，例如 `type=CHANGE`、`reason` 固定文案、`new_total_amount` = 重算後的 `total_amount`、`agreed_net_paid` / `agreed_diff`、`initiated_by_user_id=NULL`。`SupplementExpiryIntegrationTest` 斷言這些欄位 |
| 10 | 建議 | §8.1、§8.3 D5、§11.2 #4 | D5 只列 C1–C10 共 10 支 job。§11.2 #4 決定 outbox 與 SD-ORDER 同一次發布，但 C11（email-dispatch）的 job 與 SD-NOTIFY 的 `notifications` 投遞欄位遷移沒列進部署清單；少了它們，訂單 Email 一封都不會寄 | D5 加 C11，或註明「SD-NOTIFY 同發布，其遷移版號早於 `V20261122_01` 或與之同批」 |
| 11 | 建議 | §2.3.5 | **給其他模組的查詢介面有缺口**（第 2 點改寫時會用到）：`PetService` 刪除毛孩前要問「此毛孩是否在未結案訂單中」，`isAuthorizedSitter` 要問「這對保母與飼主之間是否有未結案訂單」；`MediaRetentionServiceImpl.upgrade` 用 `findActiveSnapshotsForUpgrade(sitterId)` 列出保母的已完成訂單。目前只有 `hasActiveOrder(userId)`、`hasAnyOrderBetween`（任何狀態）、`extendMediaRetention(orderId)` | 補 `hasActiveOrderWithPet(petId)`（查 `order_item_pets` join `order_items` join `orders`）、`hasActiveOrderBetween(sitterId, ownerId)`、`extendMediaRetentionForSitter(sitterId, newDays)`（或回傳訂單 id 清單） |
| 12 | 建議 | §5.1.1 `ck_orders_quote_parts`、`ck_payments_snapshot` | `surcharge_amount BETWEEN 0 AND 999999` 遇到 NULL 時結果是 NULL，CHECK 視為通過，所以報價後兩欄為 NULL 擋不住。`payee_account_key_version` 也沒有和快照綁在一起 | 改成 `quoted_at IS NULL OR (surcharge_amount IS NOT NULL AND discount_amount IS NOT NULL AND ...)`；`ck_payments_snapshot` 加 `(payee_account_snapshot IS NULL) = (payee_account_key_version IS NULL)` |
| 13 | 建議 | §8.1 C8 | 候選條件 `completion_basis_at + AUTO_COMPLETE_HOURS <= :now` 是對欄位做運算，`ix_orders_auto_complete` 無法用範圍掃描 | 改為 `completion_basis_at <= :threshold`（threshold = now − 48h，在 Java 端計算）；C7 已經是這種寫法 |
| 14 | 建議 | §11.3 Hikari | baseline 審查第 1 輪建議 prod 明寫 `minimum-idle: 1`、`connection-timeout: 5000`，SD 沒有採用，也沒說明理由。現況 prod 繼承 base 的 `minimum-idle: 2`、timeout 30s；Cloud Run 預設 concurrency 80，pool 只有 3，尖峰時請求會排隊 30 秒。拿不到訂單鎖時最多等 3 秒，這段時間仍佔著連線 | §8.3 加 D7：`application-prod.yml` 設 `minimum-idle: 1`、`connection-timeout: 5000`；或寫明不採用的理由 |
| 15 | 建議 | §5.1、§8.3 D6 | Flyway 在新 revision 啟動時執行；舊 revision 仍持有交易時，`TRUNCATE` / `ALTER` 要等 ACCESS EXCLUSIVE 鎖，而且會無限等待，可能讓啟動逾時。這支遷移是破壞性的，無法回滾 | 遷移第一行加 `SET lock_timeout = '10s';`（Flyway 在同一交易內，等同 LOCAL）。D6 補一句「失敗時只能修正後重新部署（forward fix），舊 revision 不可再接流量」 |
| 16 | 建議 | §5.1.1 第 1 段、§5.3 | `orders.is_deleted`（以及 `version DEFAULT 1`、`created_by`、`updated_by`）沒處理，語意不明；現有查詢都有過濾 `is_deleted = false`。§10.4 已改為硬刪除 | DROP `orders.is_deleted`，或在 §5.3 寫明「保留、恆為 false、新查詢不過濾」 |
| 17 | 建議 | §8.1 舊排程處置 | `CleanIdempotencyKeysJob` 在 prod **從未執行**：`@EnableScheduling` 只標在 `@Profile("local")` 的 `LocalCronSimulator` 上，不只是「縮到 0 時不可靠」。舊的 `idempotency_keys` 會一直成長 | C9 同時清除舊表 `idempotency_keys WHERE created_at < now − 24h`，直到該表移除為止；或另外列一支 job |
| 18 | 建議 | §3.6.1、§10.3 | §11.1 #6 的「單一入口」只靠約定維持：Java 套件不會阻止其他模組注入 `OrderRepository` 或 `PaymentRepository` | 加 ArchUnit 測試：只有 `application/service/order/**` 可以依賴訂單聚合的 Repository；`VisitCommandService` 的寫入方法只能由 order 套件呼叫 |
| 19 | 建議 | §8.3 | E31 是匿名可呼叫的端點，但 `SecurityConfig` 目前只對 `/api/sitters/*/plans`、`/questions` 開放匿名；C10 需要 runner SA 有 `storage.objects.list` 權限 | 部署清單補：`SecurityConfig` 加 `GET /api/sitters/*/booking-context` permitAll；驗證 `cat-sitter-runner` SA 可以 list `payment-proofs/` |

### §11.1 抽樣缺陷避開方式核對

| 缺陷 | 是否成立 | 說明 |
|------|---------|------|
| #1 金鑰預設值 | 部分成立 | 移除預設值與 Secret 注入的方向正確；缺既有密文的處理（#7）與 CI 測試設定（#6） |
| #2 `REQUIRES_NEW` 稽核 | SD-ORDER 內成立 | 但 `AuditLogService.writeOrderLog`（`REQUIRES_NEW`）仍被 `VisitReportService` 用來寫 `order_logs`，需納入 #2 的過渡改寫 |
| #3 同請求 2 條連線 | 成立 | 已確認 `spring.jpa.open-in-view: false`，各段是依序的短交易；排程協調者不開交易；我的最愛在 commit 後依序呼叫。沒有找到同時佔兩條連線的路徑 |
| #4 GCS 在交易內 | 成立 | 上傳在段 2、交易外 |
| #5 冪等語意與競態 | 部分成立 | 回放與 `ON CONFLICT` 認領正確；接手後的寫入保護不足（#1） |
| #6 鎖策略不一 | 部分成立 | 入口統一；缺鎖後新鮮度與鎖順序規範（#3）、缺強制機制（#18）；現有 `VisitReportService` 仍繞過鎖（#2） |
| #12 錯誤碼 / AC 表 | 成立 | — |
| #13 關注點混雜 | 成立 | 收款帳號設定已移到 SD-SUPPLY |

### 與 baseline 審查第 2 輪 §3 的比對

- **已採用**：§3.1 全部時間 / 去重欄位（`parent_request_id` 改名為 `source_modification_id`、`requested_by` 改名為 `initiated_by`）；§3.2 的 partial unique index 與 CHECK 全部採用，並補了 `ck_payments_snapshot` / `_escalate` / `_remind` 等；`uq_payment_adjustment` 拆成 `_manual` / `_source` 兩條，理由（與 PRD-024 BR-14 的自動補登衝突）成立。§3.3 排程：`payments/expiry` 拆成 C3 / C6，C2 多加 `due_at > :now`，C8 部分索引多加 `completion_basis_at IS NOT NULL`，逐筆處理由 `REQUIRES_NEW` 改為「協調者不開交易 + 逐筆 `REQUIRED`」，這幾處都比原建議好。
- **未採用但合理**：不需上傳的端點沒有把段 1 與段 3 合併成一個交易，維持一致的三段式。代價是多一次往返，可以接受。不過 §3.6.2 寫的理由「合併會佔第 2 條連線」不精確：等待者只佔它自己的那一條。真正的理由應該是「各端點流程一致，`ORDER_BUSY` 時可以刪紀錄讓客戶端重試」，建議改寫。
- **未採用且未說明**：Hikari `minimum-idle` / `connection-timeout`（#14）。殭屍候選查詢的訂單狀態 JOIN 在搬到 SD-FULFILL 介面時遺失（#4）。

### 排程互動核對（無問題的項目，供後續參考）

- C3（`UNPAID`）與 C4（`SUBMITTED`）的候選集合互斥。P3 駁回後重新上傳不會漏升級：`escalate_at ≥ due_at + 2h`，所以升級之後才駁回時，重新上傳一定已經超過 `due_at`，會被 409 擋下，之後由 C3 走 O8。
- C5（`REQUESTED` / `QUOTED`）與 C6（`AWAITING_SUPPLEMENT`）互斥。C6 的 T9 會建立 `AWAITING_REFUND` 的 SYSTEM 列，有 flush 規範，不會撞 `ux_modreq_open_per_order`。
- C7 與 C8 同一輪執行不會互相影響：C7 寫入的基準是當下的關閉時間，C8 要基準 + 48h 才會選到；C8 只看 `IN_PROGRESS`，`DISPUTED` 由 C7 處理但 C8 不碰。
- `DISPUTED` 訂單不會有 `REQUESTED` / `QUOTED` 變更或 `UNPAID` 補款（O13 要求沒有未結束變更；O14 補款分支要求 `SUBMITTED`），所以 C5 / C6 的重驗防線實際上不會被觸發。
- 訂單鎖 `FOR NO KEY UPDATE` 與子表 INSERT 時 FK 檢查取的 `FOR KEY SHARE` 相容。`orders` 刪掉 `idempotency_key UNIQUE` 之後只剩 PK 一個唯一鍵，Hibernate 整列 UPDATE 不會升級成 `FOR UPDATE`。
- `SET LOCAL` 在 Transaction Pooler 下只要在交易內執行就有效；prod JDBC URL 已設 `prepareThreshold=0`；Flyway 走 Session Pooler（5432），與應用程式的連線池分開。

## 重建時必須移除 / 改寫的舊呼叫點（第 2 點的清單草稿）

| 檔案:行 | 現況 | 處置 |
|--------|------|------|
| `application/service/VisitReportService.java:518`、`:522`、`:524` | 開始行程時直接把 visit 與 order 設為 `IN_PROGRESS` 並 save | 改為 `OrderLockService.lockForTransition` → 重讀 visit → `onVisitLeftPending(STARTED)` |
| `VisitReportService.java:563`、`:565` | 結束行程時直接把 visit 設為 `DONE` | 改為取鎖 → `onVisitFinished` |
| `VisitReportService.java:138、243、266、279、332、378、526、567` | `auditLogService.writeOrderLog`（`REQUIRES_NEW`，寫 `operator_id`） | 改用 `OrderLogWriter`（`MANDATORY`，`operator_role` / `operator_user_id`） |
| `application/service/CompletionService.java:44`、`:47` | 殭屍行程 → `CLOSED_BY_SYSTEM` | 刪除，由 C7 取代 |
| `CompletionService.java:78、113、170`（`COMPLETED`）、`:219`（`DISPUTED`） | 自動結案 / 結案 / 爭議 | 刪除，由 C8 / E25 / E26 取代；`/api/internal/cron/orders/auto-complete` 改接 C8 |
| `application/service/ConfirmOrderService.java:46`、`:47`、`:62` | → `PENDING_PAYMENT` | 刪除（E6） |
| `application/service/EvaluationService.java:97、146、153`（save 在 95、104、150、154） | 報價 / 婉拒、行程取消 | 刪除（E6 / E7） |
| `application/service/OwnerCancellationService.java:98、104`（save 在 101、105） | 撤回、行程取消 | 刪除（E8） |
| `application/service/PaymentService.java:130`（`PAID`）、`:197`、`:242`（save 在 137、146、192、198、250、259），以及 3 處 `writeOrderLog` | 舊付款流程 | 刪除（E11 / E14 / E15）；收款帳號設定部分移交 SD-SUPPLY（`SitterProfileController` 依賴） |
| `application/service/ModificationService.java:75、177、198、201、236、238、243、247、250、378、385`（save 在 76、202、251、328、387） | 舊變更狀態機（`MODIFYING` / `REFUND_VERIFY` / `M_DONE`） | 刪除（E17–E23 / C5 / C6） |
| `application/service/SupplementPaymentResolver.java`、`PaymentProofAccessService.java` | 舊補款 / 憑證網址 | 刪除（E13） |
| `application/service/BookingService.java:131`、`:139`（`Visit.builder`）、`:150` | 建訂單與行程 | 刪除（E1，行程經 `createVisits`） |
| `application/service/MediaExpiryWarningBatchProcessor.java:36` | `orderRepository.saveAndFlush(order)` 寫 `media_expiry_warned`（整列 UPDATE、不鎖） | 過渡期改呼叫本 SD 的 `markMediaExpiryWarned(orderId)`（新方法），或明訂只能以單欄 UPDATE 寫入 |
| `application/service/MediaRetentionServiceImpl.java:89`、`:100` | `findActiveSnapshotsForUpgrade` 加上 `orderSnapshotRepository.save` | 改用 `extendMediaRetentionForSitter`（#11） |
| `application/service/PetService.java:124`、`:250` | `findByOwnerIdAndStatusNotIn` 再讀 `order.getItems()` | 改用 `hasActiveOrderWithPet` / `hasActiveOrderBetween`（#11） |
| `application/service/AuthService.java:379` | `existsActiveOrderForParty` | 改用 `OrderQueryService.hasActiveOrder` |
| `application/service/CareRelationshipGuard.java:43` | `existsOrderBetween`（過濾 `is_deleted`） | 改用 `hasAnyOrderBetween` |
| `application/service/ReferralService.java:70` | `orderRepository.findById` | 改用 `getParticipants` / `isDeclinedBySitter` |
| `application/listener/NotificationListener.java`（`@Async` + `AFTER_COMMIT`，第 46、68、90、117、141 行讀 `orderRepository`） | 訂單事件通知 | 刪除訂單相關的 handler，改用 `enqueue()` |
| `application/service/TestDataCleanupService.java`、`OrderRepository.softDeleteByPartyEmails` | 軟刪除訂單 | 依 §10.4 改為硬刪除 |
| `application/service/E2eJourneyDataCleanupService.java:54–60` | 只刪 order_logs / order_snapshots / visits / orders | 依 §10.4 擴充（注意 #8 的順序） |
| `domain/repository/OrderRepository.java:28–30` `findByIdForUpdate`（`PESSIMISTIC_WRITE`）、`:51` `existsByPaymentIdempotencyKey`；`ModificationRequestRepository.java:14` `existsBySupplementIdempotencyKey`；`VisitRepository.java:16` `findByStatusAndScheduledAtBefore` | 舊鎖與舊冪等 | 刪除 |
| `infrastructure/cron/LocalCronSimulator.java:28` | 呼叫舊的 `completionService.triggerAutoCompletion` | 改為依序呼叫 C1–C10 |

## 待使用者回答

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-20 | 既有 `profiles.bank_account_info` 是以 repo 內公開的預設金鑰加密的。換成新的 `APP_CRYPTO_KEY` 後，舊密文要怎麼處理？ | 一次性重新加密：新增 `/api/internal/maintenance/payout-accounts/re-encrypt`（`X-Internal-Secret`，預設 dryRun），用環境變數 `APP_CRYPTO_KEY_PREVIOUS` 解密、新金鑰加密，部署後立即執行，完成後移除端點與舊金鑰。替代方案：Beta 期直接清空收款帳號並通知保母重設（較簡單，但保母要重填） |
| CQ-21 | 本 SD 依賴的 SD-FULFILL `VisitCommandService` / `VisitQueryService` 尚不存在，未重建模組也依賴舊 schema。發布方式選哪一種？ | 本 SD 的發布先行實作這兩個 Service 的最小版（照 §2.4.2 簽名，放在 fulfill 套件），並依 #2 的清單改寫未重建模組的呼叫點；SD-FULFILL 撰寫時接手。替代方案：等 SD-FULFILL 核准後兩者同一次發布 |
| CQ-22 | C7 候選要排除 `PENDING_PAYMENT` 等非目標訂單，但 `visits` 與 `orders` 分屬兩個 SD。候選查詢要放在哪裡？ | 允許 SD-FULFILL 的 `findOrderIdsWithZombieVisits(maxServiceDate, Set<OrderStatus> orderStatuses, limit)` 以唯讀 JOIN `orders` 過濾（明訂為「排程候選唯讀 JOIN」的表擁有權例外，寫入仍走 Service）。替代方案：由本 SD 以唯讀 JOIN `visits` 查詢 |
| CQ-23 | `visits.scheduled_at`（NOT NULL）與 `plan_id` / `snapshot_plan_title` 在新設計中怎麼處理？ | 本遷移加 `ALTER TABLE visits ALTER COLUMN scheduled_at DROP NOT NULL`；`createVisits` 過渡期仍寫入 `scheduled_at = service_date 00:00 Asia/Taipei` 與 `snapshot_plan_title = 方案當下名稱`，供舊的照護日誌程式讀取；SD-FULFILL 重建時決定是否刪除欄位 |
