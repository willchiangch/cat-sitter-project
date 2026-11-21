# 審查：BASELINE（設計與實作可行性，第 1 輪，2026-11-21）

審查者：sd-reviewer（獨立，未參與撰寫）。範圍：`docs/sa/00-DOMAIN-BASELINE.md` §3–§5 能否乾淨落地到 SD-ORDER；CQ-1~8 的決定本身不質疑。
證據來源：2026-11-21 `docs/domain-baseline` 分支程式碼（唯讀）、`application*.yml`、`.github/workflows/deploy.yml`、`db/migration`（最大號 `V20261025_01`）。

結論：**需釐清**（6 項阻擋，SD-ORDER 開工前須先補進 baseline）

---

## 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 阻擋 | §3.3、§3.1、§5 AUTO_COMPLETE | **子流程沒有出口，會讓訂單永遠不結案**。`REQUESTED` / `QUOTED` 沒有撤回或逾時；`AWAITING_SUPPLEMENT` 只能等補款 `VERIFIED`、`AWAITING_REFUND` 只能等 OWNER 確認——飼主不付補款或不按「已收到退款」時，變更永不 `SETTLED`，§3.3 又規定「有未結束變更 → 自動結案暫停」，保母收入永遠不入帳。PRD-016 L125 寫「飼主長時間未確認，可發起爭議處理」，但 §3.1 的 `DISPUTED` 只能由 OWNER 從 `IN_PROGRESS` 進入，保母沒有任何手段。舊設計靠 `MODIFYING` 狀態卡住，CQ-1 拆開後這條死路變成隱性的 | 補 CQ-30（及 CQ-15）：(a) 發起方可撤回 `REQUESTED`/`QUOTED`（終態併入 `REJECTED`，記 `rejectedBy`）；(b) `AWAITING_SUPPLEMENT` 逾時 → 變更作廢、原內容不變、補款 payment `VOID`；(c) `AWAITING_REFUND` 任一方可申報爭議 → 訂單 `DISPUTED`；§3.1 圖補 `CONFIRMED/IN_PROGRESS --> DISPUTED`（觸發者含 SITTER，限有未結變更時）與逾時參數進 §5 |
| 2 | 阻擋 | §3.2 | **payments 狀態機自相矛盾且不完整**。(a) 圖上駁回是 `SUBMITTED --> UNPAID`，文字卻說「保留狀態 REJECTED 的歷史列」——同一列不可能既回 UNPAID 又是 REJECTED，表示其實需要「每次上傳一列」的子表；(b) `VOID` 只能從 `UNPAID` 進入，但付款逾期時 payment 可能是 `SUBMITTED`（飼主已匯款、保母未核對），ADMIN 調解結案時也可能有未結 payment，這些情況沒有轉移；(c) 退款沒有實體：§4「保母收入 = 已付總額 − 已退總額」要算「已退」，但 §3.2 只描述入帳，退款憑證現況塞在 `modification_requests.refund_proof_url` | (a) 拆成 `payments`（一筆應收/應退款）+ `payment_submissions`（每次上傳憑證一列，`PENDING/ACCEPTED/REJECTED`）；payment 狀態只剩 `UNPAID → SUBMITTED → VERIFIED`、`UNPAID/SUBMITTED → VOID`；(b) 明定 `SUBMITTED` 時逾期**不取消**（見 CQ-31），VOID 允許從 `SUBMITTED` 進入（僅限 ADMIN 調解 / 整筆取消）；(c) 退款也是一筆 payment，`direction = REFUND`（付款人 SITTER、收款人 OWNER），走同一狀態機：保母上傳 = `SUBMITTED`、飼主確認收到 = `VERIFIED`。§3.3 `AWAITING_REFUND → SETTLED` 改寫為「退款 payment VERIFIED」。欄位見下方〈資料模型建議〉 |
| 3 | 阻擋 | §3.1 | **`CONFIRMED` 是死路**。圖上 `CONFIRMED` 只能 → `IN_PROGRESS`（首個行程開始）或 → `CANCELLED`。保母從未開始任何行程時，72h 殭屍清理把全部行程關成 `CLOSED_BY_SYSTEM`，訂單永遠停在 `CONFIRMED`（不會自動結案、不能申報爭議）。PRD-009 A/B/D 與現況程式都允許 `CONFIRMED → COMPLETED`、`CONFIRMED → DISPUTED`（`CompletionService.MANUAL_COMPLETABLE_STATUSES`、`disputeOrder`、`OrderRepository.findOrdersReadyForAutoComplete`） | CQ-28（prd-reviewer）：§3.1 補 `CONFIRMED --> COMPLETED`（全部行程 DONE/CLOSED_BY_SYSTEM 後，手動或自動）與 `CONFIRMED --> DISPUTED`；或明定殭屍清理後「全部行程 CLOSED_BY_SYSTEM 且無 DONE」的處置（建議：照樣自動結案，`completedAt` 照寫，保母收入照算，飼主在 DISPUTE 視窗內申報） |
| 4 | 阻擋 | §5 `PAYMENT_TIMEOUT_HOURS` / `PAYMENT_REMINDER_HOURS` | **起算點無法計算**。「首個行程開始時間」在資料上不存在：預約只有日期，`visits.scheduled_at` 由 `date.atStartOfDay().atOffset(UTC)` 產生（`ModificationService.updateVisitsSafely`），等於台北 08:00，且違反 §5「日的邊界一律 Asia/Taipei」。另外，首日距報價不足 24h 的訂單（當天或隔天的預約）一報價就已逾期，下一輪排程立刻取消——CQ-2 決定要做，就必須定義這個邊界 | CQ-31（及 CQ-9~11）：`payments.due_at` 在建立 payment 時算好存起來（排程只比 `due_at <= now()`，不在排程內重算），公式建議 `due_at = max(首個行程日 00:00 Asia/Taipei − 24h, 報價時間 + 3h)`；提醒 = `due_at − 24h`，若早於報價時間則不提醒；`SUBMITTED` 不因逾期取消 |
| 5 | 阻擋 | §3.2 與 `docs/sd/00-SD-PLAN.md` §0 | **Table 歸屬衝突**：00-SD-PLAN §0 把 `payments` 劃給 SD-BILLING（順序 8、延後），SD-015 已有一份 `payments` DDL（狀態 `PENDING/SUCCESS/FAILED/REFUNDED`、`payment_type=BOOKING/SUBSCRIPTION`），與 §3.2 的 `UNPAID/SUBMITTED/VERIFIED/VOID` 不相容。SD-ORDER 不擁有 payments 就無法實作線下付款與補款 | baseline 明定 `payments`、`payment_submissions` 歸 SD-ORDER；SD-BILLING 只擁有 `payout_records`、訂閱與金流 adapter，線上付款回呼呼叫 SD-ORDER 的 payment service（§3.2 已寫「同一實體的另一個 method」）。訂閱扣款不放 `payments`（另開 `subscription_charges`），避免 `order_id` nullable 的多用途表。同步修 00-SD-PLAN §0 與 SD-015 |
| 6 | 阻擋 | §3.4 冪等、§5 `IDEMPOTENCY_KEY_TTL_HOURS` | **「回放第一次的結果」缺必要定義，且 TTL「已實作」在正式站不成立**。(a) 現況 `idempotency_keys(idempotency_key, user_id, response_body)` 有 `response_body` 欄位但從未寫入；冪等實際分散在 3 處：`orders.idempotency_key`（下單）、`orders.payment_idempotency_key` + `modification_requests.supplement_idempotency_key`（付款，先 `existsBy` 再寫，有競態）、`IdempotencyService.checkAndConsume`（報價 / 婉拒 / 開始行程，重複回 409）；變更協商 4 支端點（modify、modification/quote、reject、confirm）收 `Idempotency-Key` header 卻完全沒用。(b) 未定義：key 的範圍（同 key 打不同端點？）、同 key 不同 body、第一個請求還在處理中時第二個請求怎麼回。(c) `CleanIdempotencyKeysJob` 是 `@Scheduled`，但 `@EnableScheduling` 只掛在 `@Profile("local")` 的 `LocalCronSimulator` 上——prod 從未清除過，且 Cloud Run `min-instances 0` 本來就不能靠 `@Scheduled`。(d) 這是前後端契約（錯誤碼、重試行為），不能留給 SD 各自決定 | baseline §3.4 補：key 範圍 = `(user_id, method + 路由樣板, key)`；存 `request_hash`（JSON body 的 SHA-256；multipart 用欄位 + 檔案 SHA-256）、`status`（`PROCESSING/COMPLETED`）、`response_status`、`response_body`（JSONB，只存業務 DTO，不得含 Signed URL，回放時 Signed URL 重簽）、`expires_at`；同 key 不同 hash → `422 IDEMPOTENCY_KEY_REUSED`；處理中 → `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`；查詢條件一律帶 `expires_at > now()`，不靠刪除生效；清除改為 `/api/internal/cron/idempotency/cleanup`。流程見下方〈§3.4 落地方式〉。24h 對網路重試足夠，不需要更長 |
| 7 | 重要 | §3.4 通知、營運 | **AFTER_COMMIT + `@Async` 在現行 Cloud Run 設定下不可靠**。`deploy.yml` 沒有 `--no-cpu-throttling`（預設 request-based CPU），回應送出後背景執行緒幾乎拿不到 CPU，`NotificationListener` 的 10 個 `@Async @TransactionalEventListener` 會延遲或在縮容時遺失；它們各自還要再拿一條 DB 連線（prod pool 3）。baseline 又寫「通知失敗不重試，只記 log」——付款逾期取消、補款駁回這類通知遺失，使用者完全不知道 | CQ-33：站內通知（`notifications` 列）與狀態變更**同交易寫入**（transactional outbox，`notifications` 表本身就是 outbox），Email 才走 AFTER_COMMIT 非同步，失敗由 `/api/internal/cron/notifications/email-retry` 補送（Resend 測試模式只能寄單一信箱，失敗是常態，更需要可補送）。替代方案是加 `--no-cpu-throttling`（instance-based 計費，成本上升，不建議） |
| 8 | 重要 | §3.4 鎖 | **鎖規則的範圍與細節不足**：(a) 「任何改變訂單主狀態」包含 FULFILL 的 `VisitReportService.startVisit`（`CONFIRMED → IN_PROGRESS`），現況用 `findById` 不加鎖、冪等回 409，SD-FULFILL 必須照改；(b) 沒定義鎖順序——一律先鎖 `orders` 列，再碰 `payments` / `modification_requests` / `visits`，否則跨表會死鎖；(c) `FOR UPDATE` 與子表 FK 插入的 `FOR KEY SHARE` 互斥：`OwnerCancellationService` 的註解已記錄「REQUIRES_NEW 插 order_logs 的 FK 檢查等 FOR UPDATE 列鎖到逾時」，表示 `@Lock(PESSIMISTIC_WRITE)` 產生的是 `FOR UPDATE`；改用 `FOR NO KEY UPDATE` 可讓其他交易照常插入子表；(d) 沒有鎖等待上限，pool 只有 3 條，一個卡住的鎖就能吃掉 1/3 容量 | §3.4 補：鎖 SQL 用 `SELECT ... FOR NO KEY UPDATE`（native query，不依賴 Hibernate 方言對 PESSIMISTIC_WRITE 的翻譯，實作時以 SQL log 確認）；交易開頭 `SET LOCAL lock_timeout = '3s'`、`SET LOCAL statement_timeout = '10s'`（Transaction Pooler 下 `SET LOCAL` 有效，`SET` 無效），逾時 → `409 ORDER_STATE_CONFLICT`（或 `503 ORDER_BUSY`，擇一寫進錯誤碼列）；鎖順序 orders → payments → modification_requests → visits；排程用 `FOR NO KEY UPDATE SKIP LOCKED`。SD-ORDER 對外提供唯一的轉移入口（例 `OrderLifecycleService`），FULFILL 不自己鎖 orders、不自己寫 order_logs |
| 9 | 重要 | §3.4「不另用 `@Version`」 | **與現況與 GLOBAL 規範衝突、語意不清**。`BaseEntity` 對所有實體都有 `@Version`（含 `orders`），SD-GLOBAL-SPEC §2.3 寫「實體更新使用 `@Version` 樂觀鎖」；現況還有兩種「客戶端帶 version」：`cancelByOwner(expectedVersion)`、`quoteModification(version)`。「不另用」是指刪欄位、還是保留但不當前置條件、還是禁止客戶端帶 version？payments / modification_requests 要不要自己鎖？ | CQ-35：保留 `@Version` 欄位（作為漏鎖路徑的最後防線，`ObjectOptimisticLockingFailureException` → `409 ORDER_STATE_CONFLICT`）；狀態轉移的前置條件只看「鎖住後的狀態」；移除客戶端 `expectedVersion`，過期畫面的防護改為**回傳關鍵值比對**（例：飼主同意變更帶 `agreedDiffAmount`，不符 → `409 PRICE_CHANGED`，現況 `PricingMismatchException` 已是這個做法）；子實體不另鎖，一律透過鎖父訂單序列化。同步改 SD-GLOBAL-SPEC §2.3 |
| 10 | 重要 | §3.3、CQ-1 | **拆掉 `MODIFYING` 後，原本靠狀態「順便擋掉」的動作要逐一改成顯式檢查**，baseline 只寫了「自動結案暫停」與「行程照常」。現況隱性依賴：`CompletionService.manualComplete`（`MODIFYING` 不在白名單）、`disputeOrder`、`OwnerCancellationService.rejectReason`、`proposeModification`（狀態必須 CONFIRMED/IN_PROGRESS，否則會疊第二筆）、**`triggerAutoCompletion` 的 `findByStatus("IN_PROGRESS")`**——CQ-1 落地後協商中的訂單仍是 `IN_PROGRESS`，若查詢沒加 `NOT EXISTS 未結變更`，48h 後會在協商中被自動結案。另現有 `uk_active_modification` 只涵蓋 `PENDING_REVIEW`，`QUOTED` 時已可疊第二筆 | §3.3 補一張「有未結變更時」動作表：自動結案 ✗、手動結案 ✗（409 `MODIFICATION_IN_PROGRESS`）、申報爭議 ✓（見 #1）、開始/完成行程 ✓、再發起變更 ✗（409 `MODIFICATION_IN_PROGRESS`）。唯一性由 partial unique index 保證（見〈資料模型建議〉），不能只靠應用層檢查 |
| 11 | 重要 | §3.3「SETTLED 時套用新內容」 | **套用時點會讓「協商中行程照常執行」出現錯誤行程**。減量變更合意後到飼主確認收到退款之間，被刪掉的行程仍在排程上，保母可能照做、或被殭屍清理關成 `CLOSED_BY_SYSTEM` 後才套用；增量變更若在合意時就建立行程（現況 `confirmModification` 的做法），保母可能在補款前就執行未付款的行程。整筆取消 + 退款時，訂單在等退款期間仍是 `CONFIRMED/IN_PROGRESS` 且有可執行行程 | CQ-32（或 CQ-16）：拆開「行程」與「金額」的套用時點——減少的行程於 OWNER 同意時立即取消；新增的行程與 `orders.total_amount` 於 `SETTLED` 時才建立/更新；整筆取消在 OWNER 同意時取消全部未執行行程，`SETTLED` 時訂單才轉 `CANCELLED` |
| 12 | 重要 | §3.1 `DISPUTED --> COMPLETED`、§4 保母收入 | **ADMIN 調解的金額沒有落點**。現況 `resolveDisputedOrder` 直接 `order.setTotalAmount(finalAmount)`，但 §4 保母收入改為「已付 − 已退」（從 payments 加總），改 total 不會影響收入；調解時若還有未結變更 / 未結 payment，也沒有規則；現況 `is_disputed` 欄位與 `DISPUTED` 狀態重複 | CQ-34（及 CQ-18）：調解結果以一筆 payment（`direction=REFUND`、`method=ADMIN_ADJUSTMENT`、直接 `VERIFIED`）表示退還差額；調解結案時未結變更 → `REJECTED`、未結 payment → `VOID`，同交易寫 order_logs；刪 `orders.is_disputed` |
| 13 | 重要 | §2.1 與 §4「快照」 | **快照時點三處不一致**：§2.1「訂單成立時」（=CONFIRMED？）、§4「報價時」、SD-GLOBAL-SPEC §4.2「進入 CONFIRMED 時」；現況在報價/原價接單時寫（`ConfirmOrderService`、`EvaluationService`）。快照內容的媒體額度現況取 `plan.getMaxPhotos()`，CQ-4 已決改取訂閱等級，但沒寫「取報價當下還是 CONFIRMED 當下的訂閱等級」 | 統一為「報價時（`PENDING → PENDING_PAYMENT`）」寫快照，媒體額度與保留期限取**報價當下**的訂閱等級；§2.1 改字；SD-GLOBAL-SPEC §4.2、SD-ERD §2.2 同步改。理由：飼主付款時看到的條件就是報價時的條件 |
| 14 | 重要 | §3.4 外部 I/O | **「先上傳 GCS 再開交易」的孤兒檔與重試重複上傳沒有處理規則**。現況 `PaymentService.submitPaymentProof` 上傳在 `@Transactional` 內（#4），物件名 `payment-proofs/{date}/{uuid}` 每次隨機，重試會產生多份 | §3.4 補：(1) 先在無鎖讀取交易做所有前置檢查（身分、狀態、檔案格式），減少注定失敗的上傳；(2) 物件名由冪等 key 決定（`payment-proofs/{orderId}/{paymentId}/{sha256(userId:key)}`），上傳加 `doesNotExist` precondition，重試不重複；(3) 鎖定交易失敗時 best-effort 刪檔（失敗只記 log）；(4) 週期性孤兒清除 `/api/internal/cron/storage/orphan-sweep`：列出 `payment-proofs/`、`refund-proofs/` 下 > 24h 且 DB 無引用的物件刪除。不要用 GCS lifecycle 處理孤兒——它分不出有沒有被引用 |
| 15 | 重要 | §5 排程 | **「每筆訂單獨立交易」缺實作約束**，現況 `CompletionService.triggerAutoCompletion` 是單一 `@Transactional`：殭屍行程 + 全部 `IN_PROGRESS` 訂單一起提交，一筆失敗全部回滾；不鎖訂單（與飼主同時申報爭議會互相覆寫）；`findByStatus` 全表載入再在記憶體 N+1 查行程；`manualComplete` 不寫 order_logs；`OrderRepository.findOrdersReadyForAutoComplete` 是沒人呼叫的死碼 | §5 補排程約束：(1) controller 不開交易，先用唯讀查詢取候選 ID（SQL 內完成「全部行程結束 + 最後行程 + 48h + 無未結變更 + 非 DISPUTED」條件），`LIMIT 200`；(2) 每筆呼叫**另一個 bean** 的 `@Transactional(REQUIRES_NEW)` 方法（同類別自呼叫不會生效），內部 `FOR NO KEY UPDATE SKIP LOCKED` 後重驗條件（防 TOCTOU），不符就跳過；(3) 單筆例外 catch 後記 log 繼續；(4) 回應帶 `processed/skipped/failed` 計數，Cloud Scheduler attempt deadline ≥ 執行上限，超量由下一輪接續；(5) 不可用 session-level advisory lock 當 job 互斥（Transaction Pooler 下無效），重疊執行靠 SKIP LOCKED + 重驗；(6) 付款提醒用 `payments.reminded_at` 去重；(7) 殭屍行程、付款逾期、自動結案、提醒分成 4 支端點，各自排程 |
| 16 | 重要 | §3.1 狀態名 | **`AWAITING_PAYMENT` 是未聲明的改名**。全 repo（程式、PRD、SD、TS、e2e）只用 `PENDING_PAYMENT`，`AWAITING_PAYMENT` 只出現在 baseline。§7 沒有這條決定 | CQ-29 |
| 17 | 重要 | §3.1 主狀態表、API 契約 | **前端列表 / 分頁拿不到子流程狀態**。現況列表、Badge、`OwnerOrders` 分頁全依 `order.status` 判斷「待付款 / 已付款 / 變更協商中 / 待確認退款」；拆開後 `IN_PROGRESS` 訂單上的補款、退款待辦在列表上看不到。保母收款帳號的可讀條件（§1.2「待付款 / 付款核對中」）現況也寫死 `status IN (PENDING_PAYMENT, PAID)` | §3.1 補「衍生顯示狀態」：訂單摘要 DTO 必帶 `paymentStatus`（目前未結 payment 的 status 與 direction，無則 null）、`modificationStatus`（未結變更的 status，無則 null）、`actionRequiredBy`（OWNER/SITTER/null）；§1.2 收款帳號可讀條件改為「該訂單有 `direction=PAYMENT` 且 `UNPAID/SUBMITTED` 的 payment」 |
| 18 | 重要 | 營運 / 遷移 | **破壞性 DDL 與 Cloud Run 滾動部署的空窗**。Flyway 在新 revision 啟動時跑，舊 revision 在新 revision 健康前仍接流量，DROP `orders.payment_proof_*`、`modification_requests.previous_status` 後舊程式會 500 數分鐘。另外正式 / UAT DB 仍有 `PAID`、`MODIFYING`、`REFUND_VERIFY` 的資料列 | 未上線可接受空窗，但 SD-ORDER 要寫明：新增表與資料搬移放 `V20261121_01`（新號，接續 `V20261025_01`），DROP 舊欄位放下一支 migration、隨下一次部署執行；既有資料處置見 CQ-36 |
| 19 | 建議 | §3.4 稽核 | `AuditLogService.writeOrderLog` 是 `REQUIRES_NEW` 且 try/catch 吞例外（#2、#3）。改同交易後**不能**保留 try/catch：同交易內的持久化例外會把交易標成 rollback-only，吞掉只會變成 commit 時的 `UnexpectedRollbackException` | §3.4 補一句「order_logs 寫入失敗即主交易失敗，不吞例外」；`order_logs` 加 `(order_id, created_at)` 索引；`action` 改 enum 白名單（現況有 `DISPUTE_提出` 這種值） |
| 20 | 建議 | §4 每月接單上限、CQ-5 | CQ-5「已在流程中的訂單不受影響」代表 `VERIFIED` 時不擋，計數可超過上限，額度只是公開頁的軟閘門——那就不需要併發鎖，但要寫明「允許超額」，否則 SD 會誤加 advisory lock | §2.1 補「計數只用於公開頁是否可預約；核對付款不檢查額度，可超額」 |
| 21 | 建議 | §3.1 `CANCELLED` | `cancelledBy` 現況值 `OWNER` / `SITTER` / NULL；baseline 新增 `SYSTEM`（付款逾期）與「變更整筆取消」（現況 NULL） | 列舉 `cancelledBy ∈ {OWNER, SITTER, SYSTEM, MODIFICATION}` 與 `cancel_reason`，`orders.status` 與各子表 status 加 CHECK constraint |
| 22 | 建議 | §3.3 發起方 | SITTER 發起的變更：圖上 `REQUESTED --> QUOTED: SITTER 報價`，等於保母自己報自己的價；現況 `rejectModification` 也允許保母在 `QUOTED` 拒絕 | 明定 SITTER 發起時一步建立為 `QUOTED`（發起即報價），`QUOTED` 只有 OWNER 能拒絕 / 同意，發起方只能撤回 |
| 23 | 建議 | 測試支援 | `E2eJourneyDataCleanupService` 依 FK 順序手寫 DELETE，未含 `payments` / `payment_submissions` / `modification_requests`；新表若沒有 `ON DELETE CASCADE`，journey 清理會失敗 | 新表 `order_id` FK 設 `ON DELETE CASCADE`（僅測試清理會硬刪），或 SD-ORDER 列出清理順序 |

---

## §3.4 落地方式（Cloud Run + Supabase Transaction Pooler，prod pool 3）

組合本身可行，前提是每個請求同時間只佔 1 條連線，且外部 I/O 不在交易內。建議 SD-ORDER 採以下 3 段式（以上傳付款憑證為例）：

| 段 | 交易 | 內容 | 佔連線 |
|----|------|------|-------|
| 1 認領 | 短交易 A | `INSERT INTO idempotency_keys (...status='PROCESSING', request_hash, expires_at) ON CONFLICT DO NOTHING RETURNING`；衝突時讀既有列：`COMPLETED` 且 hash 相同 → 直接回放；hash 不同 → 422；`PROCESSING` 且 < 60s → 409 `IDEMPOTENCY_IN_PROGRESS`；`PROCESSING` 且 ≥ 60s（前一次崩潰）→ 接手。同交易做無鎖前置檢查（身分 403 / 404、狀態、檔案） | 1，毫秒級 |
| 2 外部 I/O | 無交易 | GCS 上傳（物件名由 key 決定、`doesNotExist` precondition） | 0 |
| 3 轉移 | 交易 B | `SET LOCAL lock_timeout`；`SELECT ... FOR NO KEY UPDATE` 鎖訂單；重驗狀態；寫 payment_submissions / payments / orders；寫 order_logs；寫 notifications（CQ-33）；`UPDATE idempotency_keys SET status='COMPLETED', response_status, response_body`；commit | 1 |
| 失敗 | 交易 B 回滾後 | 刪除 key（讓客戶端可重試）或記為 `COMPLETED` + 錯誤回應（4xx 業務錯誤應回放相同錯誤）；best-effort 刪 GCS 物件 | 1，毫秒級 |

- 為什麼不把認領放進交易 B：同 key 的第二個請求的 INSERT 會等第一個交易的唯一索引鎖，等待期間佔第 2 條連線——pool 3 下 2 個重送就能把 pool 吃光，正是 #3 的問題換個形式。
- 不需 GCS 上傳的轉移（核對、駁回、報價）可把段 1 與段 3 合成一個交易：先鎖訂單、再 INSERT key——鎖訂單已序列化同訂單請求，不會發生上述等待。
- Transaction Pooler 限制：`pg_advisory_xact_lock`、`SET LOCAL`、`FOR UPDATE` 在交易內都可用；`pg_advisory_lock`（session）、`SET`、`LISTEN` 不可用。`prepareThreshold=0` 已設定。
- 連線預算：請求 1 + 非同步 Email listener 1（若 CQ-25 採 outbox，listener 不再寫 DB，只讀）+ 排程 1 = 3，剛好；`minimum-idle: 2` 由 base yml 繼承，建議 prod 明寫 `minimum-idle: 1`、`connection-timeout: 5000`，讓 pool 滿時快速失敗而不是排隊 30 秒。

---

## 資料模型建議（給 SD-ORDER，新 migration 從 `V20261121_01` 起）

**payments**（一筆應收 / 應退款）

| 欄位 | 型別 | 說明 |
|------|------|------|
| `id`、`version`、`created_at`、`updated_at` | — | BaseEntity |
| `order_id` | UUID NOT NULL FK `ON DELETE CASCADE` | |
| `modification_request_id` | UUID NULL FK | 首次付款為 NULL |
| `purpose` | VARCHAR(16) | `INITIAL` / `SUPPLEMENT` / `REFUND` / `ADJUSTMENT` |
| `direction` | VARCHAR(8) | `PAYMENT`（OWNER → SITTER）/ `REFUND`（SITTER → OWNER） |
| `method` | VARCHAR(24) | `OFFLINE_TRANSFER` / `ONLINE`（SD-BILLING）/ `ADMIN_ADJUSTMENT` |
| `amount` | INT NOT NULL CHECK (`amount` > 0) | 永遠正數，方向看 `direction` |
| `status` | VARCHAR(16) CHECK IN (`UNPAID`,`SUBMITTED`,`VERIFIED`,`VOID`) | |
| `due_at` | TIMESTAMPTZ NULL | 只有 `INITIAL` 必填（CQ-31） |
| `reminded_at`、`verified_at`、`verified_by`、`voided_at`、`void_reason` | | 排程去重與稽核 |

**payment_submissions**（每次上傳憑證一列，駁回歷史保留）：`id`、`payment_id` FK CASCADE、`submitted_by`、`proof_object_key`、`last_five`（PAYMENT 方向必填）、`disclaimer_agreed_at`、`status`（`PENDING`/`ACCEPTED`/`REJECTED`）、`reviewed_by`、`reviewed_at`、`reject_reason`、`created_at`。

**modification_requests**：保留 `id`、`order_id`、`requested_by`、`payload`（新 items）、`dates`；新增 `requested_by_user_id`、`base_paid_total`（發起時已付淨額快照，算差額用）、`new_total_amount`、`diff_amount`、`quoted_at`、`decided_at`、`decided_by`、`reject_reason`、`settled_at`；status 改 `REQUESTED/QUOTED/AWAITING_SUPPLEMENT/AWAITING_REFUND/SETTLED/REJECTED`；**刪除** `previous_status`、`refund_proof_url`、`supplement_*` 五欄。

**orders**：**刪除** `payment_proof_url`、`payment_proof_last_five`、`payment_idempotency_key`、`disclaimer_agreed`、`disclaimer_agreed_at`、`idempotency_key`（移到 idempotency_keys）、`is_disputed`；`paid_at` 改名 `confirmed_at`（首次付款 VERIFIED 時間）；`total_amount` 定義為「目前生效的合約總額」，只在報價與變更 SETTLED 時改。

**唯一約束（partial unique index）**

```sql
-- 同訂單最多一筆未結束變更（取代只涵蓋 PENDING_REVIEW 的 uk_active_modification）
CREATE UNIQUE INDEX uq_modreq_open_per_order ON modification_requests (order_id)
  WHERE status IN ('REQUESTED','QUOTED','AWAITING_SUPPLEMENT','AWAITING_REFUND') AND is_deleted = false;

-- 同訂單同一時間最多一筆未結 payment（首次付款與補款 / 退款不可能同時未結：
-- 變更只能在 CONFIRMED 後發起，且一次只有一筆變更）。這條比「每筆變更最多一筆」更強，也涵蓋它
CREATE UNIQUE INDEX uq_payment_open_per_order ON payments (order_id)
  WHERE status IN ('UNPAID','SUBMITTED');

-- 每張訂單最多一筆有效的首次付款
CREATE UNIQUE INDEX uq_payment_initial ON payments (order_id)
  WHERE purpose = 'INITIAL' AND status <> 'VOID';

-- 每筆變更最多一筆有效的差額 payment
CREATE UNIQUE INDEX uq_payment_per_modreq ON payments (modification_request_id)
  WHERE modification_request_id IS NOT NULL AND status <> 'VOID';

ALTER TABLE payments ADD CONSTRAINT ck_payment_modreq
  CHECK ((purpose = 'INITIAL') = (modification_request_id IS NULL) OR purpose = 'ADJUSTMENT');

-- 每筆 payment 同時最多一張待核對憑證
CREATE UNIQUE INDEX uq_submission_pending ON payment_submissions (payment_id) WHERE status = 'PENDING';

-- 排程用索引
CREATE INDEX ix_payment_due ON payments (due_at) WHERE status = 'UNPAID' AND purpose = 'INITIAL';
CREATE INDEX ix_order_logs_order ON order_logs (order_id, created_at);
```

idempotency_keys：PK 改 `(user_id, scope, idempotency_key)`，新增 `request_hash CHAR(64)`、`status`、`response_status SMALLINT`、`response_body JSONB`、`expires_at TIMESTAMPTZ`，索引 `(expires_at)`。

保母收入（§4）可直接查：`Σ amount WHERE direction='PAYMENT' AND status='VERIFIED' − Σ amount WHERE direction='REFUND' AND status='VERIFIED'`，限 `orders.status='COMPLETED'`，歸屬月份依 `completed_at`（Asia/Taipei）。現況 `getSitterLedger` 加總 `orders.total_amount`，要改。

---

## CQ-1 影響面（grep：`PENDING_PAYMENT|PAID|MODIFYING|REFUND_VERIFY|previousStatus|paymentProof|payment_proof|paymentIdempotencyKey|disclaimerAgreed|supplement|refundProof|M_DONE|PENDING_REVIEW`，已排除 KYC 的 `PENDING_REVIEW` 誤判）

| 範圍 | 檔案數 | 主要檔案（命中數） | 改動性質 |
|------|-------|------------------|---------|
| 後端 main | 27 | `PaymentService`(40)、`SupplementPaymentResolver`(17，整支刪除)、`ModificationService`(17)、`ModificationRequest`(15)、`OrderQueryServiceImpl`(14)、`OwnerCancellationService`(9)、`OrderController`(8)、`Order`(8)、`NotificationListener`(8)、`PaymentProofAclMigrationService`(5)、`OrderDetailResponseDto`(5)、`InternalMaintenanceController`(4)、`PaymentProofAccessService`(3)、`GlobalExceptionHandler`(3，冪等約束名對映)、`EvaluationService`、`ConfirmOrderService`、`CompletionService`、`OrderSummaryDto`、`ModificationRequestDetailDto`、`PaymentVerifiedEvent`、`PaymentProofSubmittedEvent`、`OrderCancelledByOwnerEvent`、`OrderRepository`、`ModificationRequestRepository`、`MediaStorageService`、`Gcs/LocalMediaStorageServiceImpl` | PaymentService、ModificationService、SupplementPaymentResolver、PaymentProofAccessService 實質重寫（約 850 行）；新增 Payment / PaymentSubmission 實體與 repository、`OrderLifecycleService`、`IdempotencyService` 重寫 |
| 後端 main（無字面命中但語意受牽動） | 5 | `VisitReportService.startVisit`（鎖、冪等）、`CompletionService.triggerAutoCompletion`（未結變更條件、逐筆交易）、`CleanIdempotencyKeysJob`（改 cron 端點）、`E2eJourneyDataCleanupService`、`AuditLogService` | 見 #8、#10、#15、#6、#23 |
| 後端測試 | 11 | `PaymentControllerTest`(103)、`ModificationServiceTest`(38)、`OrderControllerTest`(29)、`OwnerCancellationServiceTest`(20)、`OrderQueryServiceListTest`(11)、`ModificationServiceIntegrationTest`(6)、`OrderEvaluationTest`(5)、`OrderIdentityFromTokenSecurityTest`(4)、`CompletionServiceTest`、`BookingServiceTest`、`NotificationListenerLinkTest` | PaymentControllerTest 與 ModificationServiceTest 近乎重寫 |
| 前端 src | 11 | `OwnerOrderDetail.tsx`(29)、`orderApi.ts`(15)、`SitterOrders.tsx`(12)、`modificationPhase.ts`(7，階段判斷改看子流程 status)、`useOrders.ts`(6)、`SitterModificationQuote.tsx`(5)、`statusLabels.ts`(5)、`PaymentProofImage.tsx`(5)、`OwnerOrders.tsx`(4，分頁篩選)、`StatusBadge.tsx`(4)、`OwnerModificationConfirm.tsx` | 型別、Badge、分頁條件、詳情頁付款區塊；需要 #17 的衍生欄位 |
| E2E | 14 | `offline-payment.spec.ts`(28)、`journeys/order-modification-negotiation.spec.ts`(13，真後端)、`journeys/booking-to-payment.spec.ts`(11，真後端)、`order-modification.spec.ts`(10)、`owner-cancel-booking.spec.ts`(9)、`ui-layout-contrast.spec.ts`(7)、`order-status-labels.spec.ts`(6)、`order-eval.spec.ts`、`journeys/helpers.ts`、`dispute-and-completion.spec.ts`、`owner-quote-adjustment.spec.ts`、`order-visit-gating.spec.ts`、`support/mockSession.ts`、`payment-reject-button-contrast.spec.ts` | mock 回應的 status 值與 DTO 形狀全改；2 支 journey 需重跑真後端 |
| Migration | 新增 2 支 | `V20261121_01`（新表、資料搬移、partial index）、`V20261121_02` 或下一版（DROP 舊欄位） | 既有 5 支歷史 migration 不動 |

合計約 **68 個檔案**（後端 32、測試 11、前端 11、E2E 14），另加 2 支 migration。

**被牽動的其他模組**

| 模組 | 牽動點 |
|------|-------|
| SD-FULFILL | `startVisit` 改經 SD-ORDER 轉移入口（鎖 + order_logs + 冪等回放）；自動結案 / 殭屍行程改逐筆交易並加「無未結變更」條件；`CONFIRMED` 結案與爭議路徑（#3）；變更 SETTLED / 取消時 SD-ORDER 要呼叫 FULFILL 的行程重排 / 取消介面（visits 歸 FULFILL，不能由 ORDER 直接寫）；爭議（`DISPUTED`）狀態歸 ORDER、爭議內容歸 FULFILL，邊界要在兩份 SD 對齊 |
| 帳務總覽（`GET /api/orders/sitter/ledger`，`SitterLedger.tsx`） | 收入改從 payments 加總（#12）；此端點目前在 OrderController，SD-PLAN 未指定歸屬（PRD-009 帳務歸屬 → FULFILL？），建議歸 SD-ORDER 並由 ORDER 提供 `netPaidAmount(orderId)` |
| SD-NOTIFY | 事件改名 / 新增（付款逾期取消、付款提醒、補款 / 退款各階段、變更撤回 / 逾時）；若採 CQ-25 outbox，`NotificationService.createNotification` 要能在呼叫端交易內執行；`NotificationListenerLinkTest` |
| 管理後台 | `AdminResolvePanel` / `admin-resolve` 端點改寫金額語意（#12）；`InternalMaintenanceController` 的 ACL 遷移改掃 `payment_submissions.proof_object_key` |
| SD-SUPPLY | 收款帳號可讀條件改依 payments（#17） |
| SD-CARE / SD-FULFILL 媒體額度 | 快照額度來源改訂閱等級（CQ-4 + #13），`ConfirmOrderService` / `EvaluationService` 寫快照處要改 |
| SD-BILLING | `payments` 歸屬移交 SD-ORDER，SD-015 的 payments DDL 作廢（#5） |
| SD-IDENTITY / 毛孩 | `existsActiveOrderForParty`、`PetService` 用 `NOT IN (COMPLETED, CANCELLED)`，不受影響 |
| 測試基礎設施 | `E2eJourneyDataCleanupService`、`TestDataCleanupService`（#23） |

---

## 待使用者回答

> prd-reviewer 同時在 baseline §7 新增了 CQ-9~28，與本審查多項重疊。本審查只新增不重疊或建議值不同的項目，編號改為 CQ-29~36（原預定 CQ-20 起與 prd-reviewer 撞號）。下表「對應」欄標出與 prd-reviewer CQ 的關係；問題清單中的 CQ 引用以本表為準。

| CQ | 問題 | 建議預設值 | 對應 |
|----|------|-----------|------|
| CQ-29 | 付款前主狀態名：baseline 寫 `AWAITING_PAYMENT`，全 repo 現況 `PENDING_PAYMENT`（102 處），§7 沒有改名決定（#16） | 沿用 `PENDING_PAYMENT`，baseline 改字 | 新增 |
| CQ-30 | `AWAITING_REFUND` 的出口（#1） | CQ-15 其餘照建議；`AWAITING_REFUND` 不採「72h 視為確認」，改為任一方可申報爭議 → `DISPUTED`（CQ-17 互斥對此狀態開例外），由 CQ-18 調解收尾 | 補充 CQ-15 / CQ-17，建議值不同 |
| CQ-31 | 付款期限實作方式與首日仍 `SUBMITTED` 的處置（#4） | 期限於建立 payment 時寫入 `payments.due_at`，排程只比對 `due_at`；首個行程日 00:00 仍 `SUBMITTED` 不自動取消，轉 ADMIN 待辦並通知雙方 | 補充 CQ-9 / CQ-11，CQ-11 的自動取消建議值不同；短前置期採 CQ-10（422 `BOOKING_TOO_SOON`），取代本審查 #4 原寫的 `報價時間 + 3h` |
| CQ-32 | 變更新內容的套用時點（#11） | 與 CQ-16 擇一：減少的行程於 OWNER 同意時取消；新增行程與 `total_amount` 於 `SETTLED` 生效；整筆取消於同意時取消未執行行程 | CQ-16 的替代方案 |
| CQ-33 | 通知交付保證（#7） | 站內通知與狀態變更同交易寫入（outbox），Email AFTER_COMMIT 非同步送出，失敗由排程補送；不開 `--no-cpu-throttling` | 新增 |
| CQ-34 | 退款的資料表示（#2、#12） | 變更退款與 ADMIN 調解退款都是 `payments` 一筆 `direction=REFUND`；調解用 `method=ADMIN_ADJUSTMENT, status=VERIFIED`；調解結案時未結變更 → `REJECTED`、未結 payment → `VOID` | 補充 CQ-18（同方向，補資料表示） |
| CQ-35 | `@Version` 與客戶端 `expectedVersion` 的去留（#9） | 保留 `@Version` 欄位當防線；移除客戶端 `expectedVersion`；過期畫面以回傳關鍵值比對 → `409 PRICE_CHANGED`；子實體透過鎖父訂單序列化 | 新增 |
| CQ-36 | 既有 `PAID` / `MODIFYING` / `REFUND_VERIFY` 資料列的處置（#18） | 未上線、僅測試資料，重建 migration 清空交易相關表，不寫狀態對映 | 新增 |

已由 prd-reviewer CQ 涵蓋、本審查不另開：#3 `CONFIRMED` 死路 → CQ-28（行程被系統關閉也把訂單推進 `IN_PROGRESS`，可解）；#13 快照時點 → CQ-20（建議值與本審查一致）；#10 變更與爭議 / 結案互斥 → CQ-17；收款帳號快照（抽樣 #11）→ CQ-23。

## 跨文件衝突（需同步修改，不在本次審查可改範圍）

| 文件 | 衝突 |
|------|------|
| `docs/sd/00-SD-PLAN.md` §0 | `payments` 歸 SD-BILLING；SD-ORDER 擁有的 Table 未列 `payments`、`payment_submissions`；帳務總覽端點無歸屬；§3、§4 舊狀態機（`MODIFYING`、`REFUND_VERIFY`、`PAID`）仍在 |
| `docs/sd/SD-015-payment-integration.md` | `payments` DDL 與 §3.2 狀態集合不相容 |
| `docs/sd/SD-GLOBAL-SPEC.md` §2.3、§4.2 | `@Version` 為實體更新規範（vs baseline §3.4）；快照時點「進入 CONFIRMED」（vs baseline §4「報價時」）；§2.3 舉例仍寫 `PENDING → PENDING_PAYMENT` |
| `docs/sd/SD-ERD.md` §2.2 | `settlement_status (PENDING_PAYMENT, PAID, COMPLETED)`、`is_disputed`、`snapshot_plan_tier` 值 `PREMIUM`（vs baseline `ULTIMATE`） |
| `docs/sa/fr/PRD-009-order-completion.md` | A/B/D 允許 `CONFIRMED → COMPLETED/DISPUTED`、L41/L89 引用 `MODIFYING`/`REFUND_VERIFY`/`PAID`，與 baseline §3.1 不一致（prd-reviewer 另審） |
| `docs/sa/fr/PRD-016-order-modification-cancellation.md` L125 | 「飼主長時間未確認可發起爭議」，baseline 無此轉移 |


---

# 第 2 輪（2026-11-21，審 commit 5ac0fd8 改寫後正文）

審查者：sd-reviewer。範圍：(1) 第 1 輪 23 項是否已被正文解決；(2) §3.3 / §3.4 / §3.6 / §3.7 / §5 能否直接交給 sd-architect 寫 SD-ORDER；(3) 跨文件同步。CQ-1~36 決定本身不質疑。
證據：`docs/domain-baseline` 分支、`db/migration`（最大號仍 `V20261025_01`）、`V20260614_01`（`notifications` 無 Email 投遞欄位）、全 repo grep 無飼主退款帳號欄位。
同輪 prd-reviewer 已寫 CQ-40~49；本輪與其重疊者不另開 CQ，只在下表標出 sd 觀點（同意 / 補實作條件 / 替代預設）。

結論：**需釐清**（4 項阻擋，皆為「流程走不出去 / 公式不存在」，均已有 CQ 與預設值；使用者採預設即可開工）

## 1. 第 1 輪項目驗證

| # | 結果 | 原因 |
|---|------|------|
| 1 子流程無出口 | 部分 | 撤回、三種逾時、O14 已補；但 O14 只允許 `IN_PROGRESS → DISPUTED`，`CONFIRMED` 訂單的 `AWAITING_REFUND`（服務開始前整筆取消 / 減量，最常見的退款情境）仍無出口 → R2-4（= CQ-41） |
| 2 payments 狀態機 | 已解決 | payments + payment_submissions、退款為 `direction=REFUND`、`SUBMITTED → VOID` 限 ADMIN。衍生矛盾見 R2-3 |
| 3 `CONFIRMED` 死路 | 已解決 | O9 含系統關閉行程（CQ-28）。語意邊界見 R2-6 |
| 4 付款期限起算點 | 已解決 | `payments.due_at` 公式、首日、緊急單。升級時點對緊急單不成立 → CQ-44 |
| 5 payments 歸屬 | 已解決 | baseline §3.3 + 00-SD-PLAN §0 改歸 SD-ORDER；SD-015 待重寫時處理 |
| 6 冪等定義 | 部分 | 範圍、同 key 不同內容、處理中、TTL 已寫；缺崩潰殘留的 `PROCESSING` 如何接手、4xx 是否回放、「端點」指實際路徑或路由樣板、multipart 的 hash、清除端點 → R2-8 |
| 7 通知交付 | 部分 | outbox 原則已寫；Email 投遞欄位、防雙寄、最大重試、outbox table 歸屬未定 → R2-9 |
| 8 鎖規則 | 部分 | `FOR NO KEY UPDATE` + `lock_timeout` 已寫；缺逾時值、逾時錯誤碼、FULFILL 改 visits 時如何取得訂單鎖 → R2-7 |
| 9 `@Version` | 已解決 | CQ-35 落在 §3.6；GLOBAL §2.3 已加註 |
| 10 拆 MODIFYING 後的顯式檢查 | 已解決 | O11 / O13 前置條件、§3.4「可發起時機」「協商期間」；唯一索引交 SD（見 §3.2） |
| 11 套用時點 | 已解決 | CQ-32。部分套用公式缺 → R2-1（= CQ-47） |
| 12 ADMIN 調解金額 | 已解決 | O15 + `ADMIN_ADJUSTMENT` |
| 13 快照時點 | 已解決 | §2.2「報價時」；GLOBAL §4.2 已加註 |
| 14 孤兒檔 | 部分 | 「交易失敗刪檔 + 孤兒清除排程」已寫；物件名未綁冪等 key（重試會重複上傳，由清除排程收尾可接受）、寬限期與引用來源未寫 → SD-ORDER 定，不阻擋 |
| 15 排程約束 | 已解決 | §3.6「先唯讀候選、逐筆獨立交易、`SKIP LOCKED`、重驗、可重跑」。去重欄位見 §3.1 |
| 16 狀態名 | 已解決 | CQ-29 |
| 17 衍生顯示狀態 | 未解決 | §3.1 分頁仍只依主狀態；`IN_PROGRESS` 上的補款 / 退款待辦在列表看不到。屬 API 契約，下放 SD-ORDER（摘要 DTO 帶 `openPayment{purpose,direction,status,dueAt}`、`openModification{status,expiresAt}`、`actionRequiredBy`），不阻擋 baseline |
| 18 遷移空窗 | 已解決 | CQ-36 清空；V 號與兩段式 DROP 交 SD-ORDER（新號 `V{撰寫日}_01`，須大於 `V20261025_01`） |
| 19 稽核不吞例外 | 部分 | 同交易已寫；「寫入失敗即主交易失敗、不得 try/catch」未寫 → §3.6 稽核列補半句即可 |
| 20 月上限允許超額 | 已解決 | §2.2「已在流程中的訂單照常進行」+ O5 不檢查額度。O5「月額度 +1」措辭見 R2-13 |
| 21 cancelledBy 列舉 | 部分 | 列舉已寫；O10 寫哪個值未定；`ADMIN` 目前沒有轉移會產生（CQ-41 / CQ-43 採預設後才有） |
| 22 SITTER 發起即報價 | 已解決 | §3.4 圖 `[*] --> QUOTED` |
| 23 測試清理 CASCADE | 未解決 | SD-ORDER 層級，下放，不阻擋 |

解決率：已解決 13 / 部分 8 / 未解決 2（23 項中 13 項完全解決，57%；未解決 2 項均下放 SD-ORDER，不需改 baseline）。

## 2. 新問題清單

| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| R2-1 | 阻擋 | §3.4「補款」 | **「依已套用的部分重算差額」沒有公式**。變更是「減 A、加 B、新總額 T」；逾時後 B 不生效，但「只減 A」的總額從未被報價過；PRO+ 的 T 含自訂加減無法拆。逾時後 `orders.total_amount` 何時更新也未寫 | = CQ-47。sd 建議改預設（CQ-53）：CQ-47 用「快照單價 × 剩餘行程」會吃掉**原報價**的折扣。例：原報價 6 次 × 200 = 1200、PRO 折 200 → 1000；變更「減 1 加 3」補款逾時：CQ-47 算 5 × 200 = 1000，差額 0，飼主少了一次服務卻拿不到退款；CQ-53 算 1000 − 200 = 800，退 200 |
| R2-2 | 阻擋 | §3.3、§5「付款待核對升級」 | **ADMIN 待辦沒有出口、沒有資料落點**。出口與升級時點 = CQ-43 / CQ-44（sd 同意）。另：待辦存哪張表、誰擁有、去重鍵未定，SD-ORDER 建立待辦時無處可寫；CQ-42 的補款核對逾時、CQ-48 的爭議凍結也需要 ADMIN 工作清單 | CQ-55 |
| R2-3 | 阻擋 | §3.4「補款」vs §3.3 圖 | 補款逾時把 `SUBMITTED` 作廢，與 §3.3「`SUBMITTED → VOID` 限 ADMIN」及 CQ-31 精神矛盾；另有兩個時鐘（§3.4 `payments.due_at`、§5「進入當前狀態的時間」） | = CQ-42，sd 同意（沿用 O14 不需新機制）。實作補充：`AWAITING_SUPPLEMENT` 的唯一時鐘是補款 `payments.due_at`；「核對逾時」以 `payment_submissions.created_at + MODIFICATION_TIMEOUT_HOURS` 判斷，O14 前置條件需在鎖內查最新 submission |
| R2-4 | 阻擋 | O14、O15、§3.1 圖 | **`CONFIRMED` 訂單退款無出口**；即使開放，O15 只能 → `COMPLETED` | = CQ-41，sd 同意方向。但 CQ-41「`CONFIRMED` 來的爭議 → `CANCELLED`」以「進入爭議前的主狀態」判斷不可靠：CQ-41 開放後，減量（仍有 `PENDING` 行程）的 `CONFIRMED` 訂單進爭議後行程可能照常執行 → 見 R2-17 / CQ-57，建議改以「行程實際狀態」判斷終態 |
| R2-5 | 重要 | §3.4 圖 | 「系統自建 `AWAITING_REFUND` 變更」在狀態圖沒有入口邊，且未寫同交易的執行順序。與「同訂單最多一筆未結束變更」**不衝突**，前提是同交易內先把舊列 `UPDATE` 為 `EXPIRED`、補款 payment `VOID`，再 `INSERT` 新列與退款 payment（partial unique index 逐句檢查，順序反了會撞 `uq_modreq_open_per_order` / `uq_payment_open_per_order`） | 圖補 `[*] --> AWAITING_REFUND: 系統（補款逾時，重算差額 < 0）`；§3.4 補「先結束舊列再建新列，同交易」；新列 `parent_request_id` 指回舊列、`requested_by=SYSTEM`、不可撤回（CQ-47 已含）。不建議改成 `AWAITING_SUPPLEMENT --> AWAITING_REFUND` 同列轉移：一筆變更會有兩筆 payment，失去「每筆變更最多一筆 payment」唯一鍵 |
| R2-6 | 重要 | O9、§5 `VISIT_ZOMBIE_HOURS` | 「任一行程離開 `PENDING`」字面上含 `CANCELLED`：若算，`CONFIRMED` 訂單一減量就變 `IN_PROGRESS`，O10 永遠到不了（CQ-41 改寫的 O10 也依賴此點）。殭屍清理未限定訂單狀態：`PENDING_PAYMENT`（待 ADMIN 處理）的行程過期被關會觸發 O9，但 O9 只從 `CONFIRMED` 出發；`DISPUTED` 訂單若還有 `PENDING` 行程（R2-17）則必須被清理 | CQ-56 |
| R2-7 | 重要 | §3.6 鎖、§3.7 | `lock_timeout` 值、逾時（SQLState `55P03`）錯誤碼未定，前端無法判斷可否自動重試。visits 歸 SD-FULFILL，但「改行程狀態先鎖訂單列」——FULFILL 不得直接寫 orders，需要 SD-ORDER 的入口 | CQ-50；§3.6 鎖列補「其他模組改 visits 前呼叫 SD-ORDER 的鎖入口（例 `OrderTransitionGateway.lockForUpdate(orderId)`，`Propagation.MANDATORY`），O9 由同一入口處理」 |
| R2-8 | 重要 | §3.6 冪等 | 第一個請求 `PROCESSING` 時 instance 被回收（Cloud Run 縮容常見）→ 該 key 24h 內永遠 409，客戶端重試無解；4xx 業務錯誤是否回放未定，前後端會各做各的 | CQ-51 |
| R2-9 | 重要 | §3.6 通知、00-SD-PLAN §0 | (a) `notifications` 無 Email 投遞欄位，補送排程無從判斷；(b) commit 後送 + 排程補送會雙寄；(c) Resend 測試模式寄非白名單是永久失敗，無上限重試會每輪重打；(d) 00-SD-PLAN「通知 outbox」列在 SD-ORDER、`notifications` 列在 SD-NOTIFY，若 outbox 就是 `notifications`，同一 table 出現在兩列 | CQ-52 |
| R2-10 | 重要 | §3.3 `REFUND`、§1.2 | **退款匯到哪裡沒有定義**。`CHARGE` 快照保母收款帳號；`REFUND` 保母要匯給飼主，但全 repo 沒有飼主帳號欄位，首次付款憑證只存末五碼。payment 欄位與加密範圍取決於此 | CQ-54 |
| R2-11 | 重要 | §5 結案基準時間 | 「最後行程結束時間」對各終態取哪個欄位未定（CQ-40 處理了 DONE 未送日誌的情形，但沒定 `CLOSED_BY_SYSTEM` 的時間、`CANCELLED` 是否排除）；每次排程用 `max()` 子查詢跨 visits / modification_requests 兩模組計算 | §5 補：`DONE` 依 CQ-40、`CLOSED_BY_SYSTEM` 取關閉時間、`CANCELLED` 排除。建議 `orders.completion_basis_at` 在行程結束 / 變更結束時於訂單鎖內更新，排程只比對此欄（與 CQ-31「排程只比對寫好的欄位」一致） |
| R2-12 | 重要 | §3.7 | CQ-45 `FIRST_VISIT_DATE_PASSED`、CQ-46 `DEADLINE_PASSED`、CQ-49 `INVALID_VISIT_DATE`、CQ-50 鎖逾時碼都是跨模組會用到的碼（FULFILL / CARE 也有期限），應進 §3.7；另缺 `401 UNAUTHENTICATED`（GLOBAL §3.4 已定） | 採用後一併補進 §3.7 |
| R2-13 | 建議 | O5、§2.2 | 「月額度 +1」暗示計數欄位（SD-ERD `SUBSCRIPTION.monthly_order_count`），但 §2.2 定義是「當月進入 CONFIRMED 的訂單數、取消不返還」，用 `orders.confirmed_at` 直接計算即可，沒有計數器漂移 | O5 改「寫 `confirmed_at`」；計數 = `count(*) WHERE sitter_id=? AND confirmed_at 落在當月（Asia/Taipei）`，索引 `(sitter_id, confirmed_at)`；ERD 刪 `monthly_order_count` |
| R2-14 | 建議 | §3.3 方向表 | 方向表把 `ADMIN_ADJUSTMENT` 當一列，與 `direction` 的值（只有 CHARGE / REFUND）混用；`ADMIN_ADJUSTMENT` 同時是 purpose 與 method | 改「purpose=ADMIN_ADJUSTMENT（direction=REFUND）：ADMIN 建立即 VERIFIED」 |
| R2-15 | 建議 | §2.2、§3.7 | `403 SUBSCRIPTION_LOCKED` 回給「預約鎖定保母」的飼主語意不對（飼主沒有權限問題），也洩漏保母訂閱狀態 | 飼主端預約遇保母鎖定回 `422 SITTER_UNAVAILABLE`（與 `MONTHLY_QUOTA_REACHED` 同層）；`403 SUBSCRIPTION_LOCKED` 只用於保母本人操作 |
| R2-16 | 建議 | §1.2、§3.3 | 收款帳號快照是銀行帳號的第二份拷貝，未要求同等加密；憑證 Signed URL TTL、憑證路徑是否落在 GLOBAL §4.1 的 `{plan_tier}` lifecycle 前綴（會被媒體保留期誤刪）未寫 | §1.2 補「快照與原欄位同等加密；API 預設遮蔽，付款方於 payment 未結時可看完整帳號」；憑證放 `payment-proofs/{orderId}/{paymentId}/...`，不走 plan_tier lifecycle；TTL 由 SD-ORDER 定（建議 10 分鐘） |
| R2-17 | 重要 | O14、O15、CQ-41、CQ-48 | **`DISPUTED` 期間仍有 `PENDING` 行程時無規則**。O14（含 CQ-41 擴充）允許「還有未來行程」的訂單進爭議（例：5 次減 2 次 → `AWAITING_REFUND`，剩 3 次未執行）。CQ-48 只凍結 payment / 變更，未說行程能否開始、殭屍清理是否處理；O15 → `COMPLETED` 時若仍有 `PENDING` 行程，訂單結案但行程懸空 | CQ-57 |
| R2-18 | 建議 | CQ-48、O15 | CQ-48「作廢 SUBMITTED 退款時同時登記等額 `ADMIN_ADJUSTMENT`」+ O15「可登記一筆」→ 同訂單可能 2 筆 adjustment，`uq_payment_adjustment` 不能設 | 若採 CQ-48，O15 改「ADMIN 登記一筆 `ADMIN_ADJUSTMENT`，金額 = 作廢的已匯退款 + 調解退款，合計 ≤ 已收淨額」，維持一筆；否則取消此唯一鍵 |

## 3. 給 SD-ORDER 的落地清單（假設 CQ-40~57 採建議預設）

### 3.1 時間 / 去重欄位

| 表 | 欄位 | 寫入時點 |
|----|------|---------|
| `orders` | `review_expires_at` | O1 |
| `orders` | `confirmed_at` | O5（取代 `paid_at`） |
| `orders` | `completion_basis_at` | 行程結束 / 變更結束（R2-11） |
| `payments` | `due_at` | 建立 `INITIAL` / `SUPPLEMENT` 時；`REFUND` 為 null |
| `payments` | `remind_at`、`reminded_at` | `remind_at` = 首日 − 48h；建立時已過則 null（隨報價通知提醒） |
| `payments` | `escalate_at`、`escalated_at` | CQ-44 |
| `payments` | `verified_at`、`verified_by`、`voided_at`、`void_reason` | 轉移時 |
| `modification_requests` | `expires_at` | 進入 `REQUESTED` / `QUOTED` 時 = now + 72h；`AWAITING_SUPPLEMENT` 時 null（改看補款 `due_at`）；`AWAITING_REFUND` 與終態 null |
| `modification_requests` | `parent_request_id`、`requested_by`（含 `SYSTEM`） | R2-5 |
| `idempotency_keys` | `request_hash`、`status`、`locked_until`、`response_status`、`response_body`、`expires_at` | CQ-51 |
| `notifications`（SD-NOTIFY） | `email_status`、`email_attempts`、`email_next_attempt_at`、`email_last_error` | CQ-52 |
| `admin_tasks` | 見 CQ-55 | — |

### 3.2 Partial unique index / CHECK

```sql
CREATE UNIQUE INDEX uq_modreq_open_per_order ON modification_requests (order_id)
  WHERE status IN ('REQUESTED','QUOTED','AWAITING_SUPPLEMENT','AWAITING_REFUND');
CREATE UNIQUE INDEX uq_payment_open_per_order ON payments (order_id)
  WHERE status IN ('UNPAID','SUBMITTED');
-- 訂單取消後不會重開，首次付款 VOID 也不必排除
CREATE UNIQUE INDEX uq_payment_initial ON payments (order_id) WHERE purpose = 'INITIAL';
-- O15「一筆」；CQ-48 採預設時見 R2-18
CREATE UNIQUE INDEX uq_payment_adjustment ON payments (order_id) WHERE purpose = 'ADMIN_ADJUSTMENT';
-- 每筆變更一生最多一筆 payment（R2-5 新列設計的前提）
CREATE UNIQUE INDEX uq_payment_per_modreq ON payments (modification_request_id)
  WHERE modification_request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_submission_open ON payment_submissions (payment_id) WHERE status = 'SUBMITTED';
CREATE UNIQUE INDEX uq_admin_task_open ON admin_tasks (type, order_id) WHERE status = 'OPEN';

ALTER TABLE payments ADD CONSTRAINT ck_payment_amount CHECK (amount > 0);
ALTER TABLE payments ADD CONSTRAINT ck_payment_direction CHECK (
  (direction = 'CHARGE' AND purpose IN ('INITIAL','SUPPLEMENT'))
  OR (direction = 'REFUND' AND purpose IN ('MODIFICATION_REFUND','ADMIN_ADJUSTMENT')));
ALTER TABLE payments ADD CONSTRAINT ck_payment_method CHECK ((purpose = 'ADMIN_ADJUSTMENT') = (method = 'ADMIN_ADJUSTMENT'));
ALTER TABLE payments ADD CONSTRAINT ck_payment_due CHECK ((purpose IN ('INITIAL','SUPPLEMENT')) = (due_at IS NOT NULL));
ALTER TABLE payments ADD CONSTRAINT ck_payment_modreq CHECK (
  (purpose IN ('SUPPLEMENT','MODIFICATION_REFUND')) = (modification_request_id IS NOT NULL));
-- 「退款合計 ≤ 已收淨額」無法用 CHECK 表達，於訂單鎖內檢查
```

### 3.3 排程清單（全部 `/api/internal/cron/*`；候選查詢唯讀、`LIMIT 200`；逐筆另一個 bean 的 `REQUIRES_NEW` + `FOR NO KEY UPDATE SKIP LOCKED` 後重驗）

| 端點 | 頻率 | 候選查詢 | 處理 | 支援索引 |
|------|------|---------|------|---------|
| `orders/review-expiry` | 15 分 | `orders WHERE status='PENDING' AND review_expires_at <= now()` | O4 | `(review_expires_at) WHERE status='PENDING'` |
| `payments/expiry` | 15 分 | `payments WHERE status='UNPAID' AND purpose IN ('INITIAL','SUPPLEMENT') AND due_at <= now()` | INITIAL → O8；SUPPLEMENT → 變更 EXPIRED + 重算（CQ-47 / 53，R2-5 順序） | `(due_at) WHERE status='UNPAID'` |
| `payments/reminder` | 15 分 | `payments WHERE status='UNPAID' AND purpose='INITIAL' AND remind_at <= now() AND reminded_at IS NULL` | 通知 OWNER、寫 `reminded_at` | `(remind_at) WHERE reminded_at IS NULL` |
| `payments/escalation` | 15 分 | `payments WHERE status='SUBMITTED' AND purpose='INITIAL' AND escalate_at <= now() AND escalated_at IS NULL` | 建 admin_task、通知雙方 | `(escalate_at) WHERE status='SUBMITTED'` |
| `modifications/expiry` | 15 分 | `modification_requests WHERE status IN ('REQUESTED','QUOTED') AND expires_at <= now()` | → EXPIRED、通知雙方 | `(expires_at) WHERE status IN ('REQUESTED','QUOTED')` |
| `visits/zombie`（SD-FULFILL，經 ORDER 鎖入口） | 15 分 | `visits JOIN orders WHERE visits.status='PENDING' AND 行程日 00:00(Taipei) + 72h <= now() AND orders.status IN ('CONFIRMED','IN_PROGRESS','DISPUTED')`，依 order 分組 | → CLOSED_BY_SYSTEM，同交易 O9 | FULFILL 定；visits 需要 `service_date DATE`（現況 `scheduled_at` = UTC 午夜，違反 §5） |
| `orders/auto-complete` | 15 分 | `orders WHERE status='IN_PROGRESS' AND completion_basis_at + 48h <= now() AND NOT EXISTS 未結束行程 AND NOT EXISTS 未結束變更` | O12 | `(completion_basis_at) WHERE status='IN_PROGRESS'` |
| `notifications/email-retry`（SD-NOTIFY） | 5 分 | `notifications WHERE email_status='PENDING' AND email_next_attempt_at <= now()` | 搶單後寄送（CQ-52） | `(email_next_attempt_at) WHERE email_status='PENDING'` |
| `idempotency/cleanup` | 每日 | `DELETE FROM idempotency_keys WHERE ctid IN (SELECT ctid ... WHERE expires_at < now() - interval '1 day' LIMIT 5000)` | 分批刪除 | `(expires_at)` |
| `storage/orphan-sweep` | 每日 | 列 `payment-proofs/` 下建立 > 24h 的物件，比對 `payment_submissions.proof_object_key` | 刪除無引用 | — |

CQ-42 的「補款核對逾時」不需排程（申報爭議時於鎖內判斷）。訂閱鎖定通知飼主（CQ-19）屬 SD-BILLING。

### 3.4 可直接交給 sd-architect 的部分

§3.6 鎖 / 版本 / 稽核 / 外部 I/O / 排程、§4、§5 除 R2-11 外可直接照寫。`FOR NO KEY UPDATE` + `SET LOCAL lock_timeout` 在 Transaction Pooler 下可行；子表 INSERT 的 FK 檢查取 `FOR KEY SHARE`，與 `FOR NO KEY UPDATE` 相容，第 1 輪 #8(c) 的等待問題消失。

## 4. 跨文件同步

| 文件 | 第 1 輪列出的衝突 | 本輪狀態 |
|------|-----------------|---------|
| `docs/sd/00-SD-PLAN.md` §0 | payments 歸屬、帳務端點歸屬、§3 / §4 舊狀態機 | 已同步（§1 之後標「已被取代」）。**新衝突**：「通知 outbox」列在 SD-ORDER、`notifications` 列在 SD-NOTIFY（CQ-52）；`admin_tasks` 無歸屬（CQ-55） |
| `docs/sd/SD-GLOBAL-SPEC.md` §2.3、§4.2 | `@Version`、快照時點 | 已加註。**本輪新發現未同步**：§2.1「前端依 Client 時區顯示」vs baseline §5「顯示一律 Asia/Taipei」；§3.4「資源不存在 → 400 / 404」vs baseline §1.2「404」；§5.3「main 合併自動部署」vs commit 9945bbd 改手動觸發；§4.1 lifecycle 前綴未排除付款憑證（R2-16） |
| `docs/sd/SD-ERD.md` §2.2 | `settlement_status`、`is_disputed`、`PREMIUM` | 只改了 `ULTIMATE`。`settlement_status (PENDING_PAYMENT, PAID, COMPLETED)`、`paid_at`、`is_disputed`、`cancelled_by` 說明（「變更取消為 NULL」與 baseline 列舉衝突）、`SUBSCRIPTION.monthly_order_count`（R2-13）仍在；無 PAYMENT / PAYMENT_SUBMISSION / MODIFICATION_REQUEST 實體。建議隨 SD-ORDER 同一 commit 改寫，但 sd-architect 須知道 ERD §2.2 不可引用 |
| `docs/sd/SD-015-payment-integration.md` | payments DDL 不相容 | 註明：SD-BILLING 重寫時作廢，不要求現在改 |
| `docs/sa/fr/PRD-009-order-completion.md` | `CONFIRMED → COMPLETED/DISPUTED`、舊狀態名 | 註明：重寫 PRD 時處理；採 CQ-41 後 PRD-009 需加 `CONFIRMED → DISPUTED`（限退款爭議）與 `DISPUTED → CANCELLED` |
| `docs/sa/fr/PRD-016-order-modification-cancellation.md` L125 | 飼主不確認退款可爭議 | 註明：baseline 已由 O14 承接；PRD 重寫時對齊 CQ-41 / CQ-42 / CQ-54 |

## 5. 待使用者回答（已寫入 baseline §7「待決（第 2 輪）」）

與 prd-reviewer CQ-40~49 的關係：R2-1 → CQ-47（sd 另提替代預設 CQ-53）；R2-2 → CQ-43 / CQ-44（同意，另開 CQ-55 補資料落點）；R2-3 → CQ-42（同意）；R2-4 → CQ-41（同意方向，終態判斷見 CQ-57）；到期後排程前的請求 → CQ-46（同意，碼進 §3.7）；R2-11 → CQ-40（同意，另補欄位）。

| CQ | 問題 | 建議預設值 | 對應 |
|----|------|-----------|------|
| CQ-50 | 訂單鎖等待上限與逾時錯誤碼 | `SET LOCAL lock_timeout = '3s'`；逾時 → `409 ORDER_BUSY` + `Retry-After: 1`，前端可帶同一 `Idempotency-Key` 自動重試一次；§3.7 補此碼與 `401 UNAUTHENTICATED` | R2-7、R2-12 |
| CQ-51 | 冪等：處理中殘留與錯誤回放 | `PROCESSING` 列帶 `locked_until = now + 30s`，過期可被同 key 請求接手；2xx 與業務錯誤（409 / 422）記錄並回放；5xx 與 `ORDER_BUSY` 不記錄（刪列，允許重試）；範圍的「端點」= HTTP method + 路由樣板；multipart 的 hash = 各欄位值 + 檔案 SHA-256 | R2-8 |
| CQ-52 | outbox 的實體與歸屬、Email 補送 | outbox 就是 `notifications`，歸 SD-NOTIFY；SD-ORDER 呼叫 `NotificationService.enqueue()`（`MANDATORY`，在轉移交易內）；`notifications` 加 `email_status`（PENDING / SENDING / SENT / FAILED / SKIPPED）等欄位；寄送前以條件 UPDATE 搶單，Resend 呼叫帶 `Idempotency-Key = notification.id`；最多 5 次指數退避，4xx（含測試模式拒收）直接 FAILED；00-SD-PLAN 刪 SD-ORDER 列的「通知 outbox」 | R2-9 |
| CQ-53 | CQ-47 的替代預設：補款逾時後已套用總額 | `已套用總額 = 原 total_amount − Σ(已取消行程的快照單價)`，下限 0；保留原報價的手動加減，只不沿用本次變更的加減；`orders.total_amount` 於逾時交易內即改為已套用總額（系統退款變更 SETTLED 時不再改）。與 CQ-47 擇一 | R2-1 |
| CQ-54 | 退款匯款目的地 | 飼主同意產生退款的變更時，必填退款帳號（銀行代碼 + 帳號 + 戶名），快照到該 `REFUND` payment（同等加密，只有付款方 SITTER 可看完整值）；系統自建退款變更由飼主於通知連結補填，未填前保母不可上傳退款憑證；`ADMIN_ADJUSTMENT` 由 ADMIN 線下處理不需帳號；不建飼主帳號主檔 | R2-10 |
| CQ-55 | ADMIN 待辦的資料表與歸屬 | 新表 `admin_tasks`（`type` = PAYMENT_UNVERIFIED / DISPUTE、`order_id`、`payment_id`、`status` OPEN / RESOLVED、`resolution`、`resolved_by`、`resolved_at`），歸 SD-ORDER；`(type, order_id) WHERE status='OPEN'` 唯一；O13 / O14 / CQ-43 升級時同交易建立，O5（保母自行核對）/ O15 / O16 時同交易關閉；ADMIN 後台列表只讀此表 | R2-2 |
| CQ-56 | O9「離開 PENDING」與殭屍清理範圍 | 只計 `PENDING → IN_PROGRESS` 與 `PENDING → CLOSED_BY_SYSTEM`；因變更取消（`CANCELLED`）不推進主狀態。殭屍清理處理 `CONFIRMED` / `IN_PROGRESS` / `DISPUTED` 訂單的行程，不處理 `PENDING_PAYMENT`（由 CQ-43 收尾） | R2-6 |
| CQ-57 | `DISPUTED` 期間仍有未執行行程 | 爭議期間行程照常（開始、送日誌、殭屍清理都可），不推進主狀態；O15 前置條件加「無 `IN_PROGRESS` 行程」，結案時剩餘 `PENDING` 行程一律取消；終態依行程實際狀態：有任何 `DONE` / `CLOSED_BY_SYSTEM` → `COMPLETED`，全部 `CANCELLED` → `CANCELLED`（`cancelledBy=ADMIN`）。取代 CQ-41「依爭議來源狀態」的判斷 | R2-4、R2-17 |

## 6. SD-ORDER 可否開始

阻擋項 R2-1~R2-4 對應 CQ-41 / 42 / 43(+55) / 47(或 53)，決定前 SD-ORDER 的付款與變更子流程狀態機寫不完。使用者若採建議預設，baseline 依 CQ-40~57 補字（主狀態表新增 O16 與 `DISPUTED → CANCELLED`、O14 擴起點、§3.4 圖補 3 條邊、§3.7 補 4~5 個碼、§5 補 `completion_basis_at` / `escalate_at` / `expires_at` 定義）後即可開工。資料表、鎖、冪等、排程骨架（本輪 §3）不受這些決定影響，可先行撰寫。
