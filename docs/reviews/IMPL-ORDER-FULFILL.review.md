# 實作審查：SD-ORDER + SD-FULFILL（2026-11-22，BASE main 9420611 → HEAD 0c0fc6b，分支 feature/sd-order-fulfill-rebuild）

結論：需修正（無阻擋項；3 項重要、10 項建議）

## 審查範圍與方法

- 對照文件：`docs/sd/SD-ORDER.md`、`docs/sd/SD-FULFILL.md`、`docs/sd/SD-GLOBAL-SPEC.md`、`docs/sd/SD-FRONTEND-SPEC.md`、`docs/sa/nfr/03-SECURITY.md`，以及 PRD-005 / 006 / 007 / 008 / 009 / 016 / 022 / 023 / 024 / 026 / 027 / 028（共 235 條 AC：SD-ORDER 156、SD-FULFILL 79）。
- 變更範圍：`git diff main...HEAD`（83 commits、668 檔）；工作區乾淨，無未 commit 變更。
- 方法：讀實際程式碼（controller 註解、Service 判斷順序、鎖 / 冪等 / 稽核 / 通知寫入點、例外對映、設定檔、前端 testid / 錯誤處理）；AC 對測試以 `@DisplayName` 前綴比對後，抽讀測試內容確認斷言。
- 後端測試實跑：JDK 21 下 `mvn -q test` 全套 105 個測試類別（含 @Nested）、626 個測試，0 失敗 / 0 錯誤 / 1 略過（surefire 報告 21:41–21:44）。注意：系統預設 JDK 17 跑會因 class file 65 失敗，需 `JAVA_HOME` 指向 21。前端 vitest / tsc 未執行（審查限制只允許 mvn test）。
- 未重提的已知項：SD-GLOBAL §4.3 已知偏離（`care_logs` REQUIRES_NEW、`AdminSubscriptionController` 交易）、§2.6 `LegacyBusinessException`、§2.8 `anonymous-enforce=false` 與 form-urlencoded 缺口、SD-FULFILL §6.3 F13 已知邊界、PRD-016 EX-13 併發測試缺漏、E2E spec 尚未重寫。
- 路徑縮寫：實作位置相對 `backend/src/main/java/com/petsitter/application/service/{order|fulfill}/`（前端檔案相對 `frontend/src/`）；測試位置為 `backend/src/test/java/com/petsitter/{order|fulfill}/` 下的類別名:行號（多數以 @Nested 收在合併後的測試類別內）。

## 整體觀察（已確認符合的全域規則）

| 項目 | 結論 | 位置 |
|------|------|------|
| 訂單鎖入口 | `SET LOCAL lock_timeout 3s` + `FOR NO KEY UPDATE`、`MANDATORY`、鎖後 refresh、`LockedOrder` 綁交易（`requireHeld`）符合 §3.6.1 | `order/OrderLockService.java:46-121`、`order/LockedOrder.java:13-52` |
| 同交易稽核 | `OrderLogWriter.write` 為 `MANDATORY` 且要求持鎖；稽核 Repository 為 append-only（`AuditAppendOnlyArchTest`）；回滾測試 `OrderLockAndLogIntegrationTest:116` | `order/OrderLogWriter.java:34-53` |
| 冪等三段式 | claim `NEVER` 短交易、complete `MANDATORY` 比對 `claim_token`、fail 依 409/422 記錄 / 其餘刪除；§10.2 全部情境（含接手後原請求完成、commit 後出錯、並行同 key、路徑變數入 hash）有測試 | `application/common/idempotency/IdempotencyService.java:81-217`；`IdempotencyIntegrationTest:129-451` |
| Outbox | `NotificationService.enqueue` 為 `MANDATORY`；訂單 / 履約無 AFTER_COMMIT / @Async / REQUIRES_NEW；通知類型代碼與 SD-ORDER §7.2、SD-FULFILL §7.2 一致 | `application/service/NotificationService.java:48`、`order/OrderNotifier.java` |
| 身分與授權 | 33 支 E 端點、19 支 F 端點的 `@PreAuthorize` 與 §6.2 / F §6.2 一致；身分一律 `TokenContext`；G4 → G4a → G5 由 `OrderAccess.partyRole` / `FulfillGuard` 統一；`OrderIdentitySecurityTest`、`VisitAuthorizationIntegrationTest` 走真 JWT filter chain | `interfaces/controller/order/*`、`interfaces/controller/fulfill/*`、`order/OrderAccess.java:17-43` |
| 時間來源 | 新程式無 `LocalDateTime.now()` / 無參數 `now()`；一律注入 `Clock`（`OrderClock`） | grep 結果為 0 |
| 例外對映 | `AbstractModuleExceptionHandler` 只有 23505 對映 409，其餘 500；55P03 / 40P01 / `@Version` → `ORDER_BUSY` + `Retry-After: 1` | `application/exception/AbstractModuleExceptionHandler.java`、`interfaces/advice/OrderExceptionHandler.java` |
| 遷移 | 只新增 `V20261122_01`、`V20261122_02`，未改舊遷移 | `db/migration` |
| Secret | `INTERNAL_CRON_SECRET`、`APP_SECURITY_JWT_SECRET`、`APP_CRYPTO_KEY` 已移除預設值（D1 / D2）；log 無收款帳號 / token / 簽名 query | `backend/src/main/resources/application.yml` |
| 前端 | SD-ORDER §9.1 / §9.3、SD-FULFILL §9 的 testid 全數存在（含動態組成者）；冪等 key 於送出時產生、`ORDER_BUSY` 依 `Retry-After` 重試一次、`IDEMPOTENCY_IN_PROGRESS` 自動重試；§6.5 / F §6.5 所有錯誤碼在 `api/errorMessages.ts` 有文案；E32 / E33 試算 400 ms 防抖且只取最後回應、CQ-50 文案 | `api/idempotency.ts`、`hooks/useIdempotentAction.ts`、`components/orders/ModificationPreviewAmounts.tsx:39-43` |

## AC 對照（235 條：符合 228、符合（畫面，E2E 範圍外）2、測試不足 5、偏離 0、未實作 0）

| PRD | AC | 實作位置 | 測試位置 | 結果 |
|-----|----|---------|---------|------|
| PRD-005 | AC-1.1 匿名瀏覽與登入送單 | BookingContextService.java:28；BookingOrchestrator.java:122 | BookingIntegrationTest:402; BookingContextIntegrationTest:23 | 符合 |
| PRD-005 | AC-1.2 KYC 未通過 | BookingContextService.java:28；BookingOrchestrator.java:122 | BookingIntegrationTest:196; BookingContextIntegrationTest:37 | 符合 |
| PRD-005 | AC-1.3 隱藏與全域黑名單同文案 | BookingContextService.java:28；BookingOrchestrator.java:122 | BookingIntegrationTest:206; BookingContextIntegrationTest:46 | 符合 |
| PRD-005 | AC-1.4 方案黑 / 白名單依等級生效 | BookingContextService.java:28；BookingOrchestrator.java:122 | BookingIntegrationTest:234; BookingContextIntegrationTest:67 | 符合 |
| PRD-005 | AC-1.5 全域白名單 | BookingContextService.java:28；BookingOrchestrator.java:122 | BookingIntegrationTest:206; BookingContextIntegrationTest:46 | 符合 |
| PRD-005 | AC-1.6 訂閱鎖定與 ADMIN 停權 | BookingContextService.java:28；BookingOrchestrator.java:122 | BookingIntegrationTest:247; BookingContextIntegrationTest:85 | 符合 |
| PRD-005 | AC-1.7 每月接單上限 | BookingContextService.java:28；BookingOrchestrator.java:122 | BookingIntegrationTest:264; BookingContextIntegrationTest:85 | 符合 |
| PRD-005 | AC-1.8 角色不符與預約自己 | BookingContextService.java:28；BookingOrchestrator.java:122 | OrderIdentitySecurityTest:127; BookingIntegrationTest:386 | 符合 |
| PRD-005 | AC-2.1 日期群組組合與試算 | BookingOrchestrator.java:122 | BookingIntegrationTest:34 | 符合 |
| PRD-005 | AC-2.2 日期上下限 | BookingOrchestrator.java:122 | BookingIntegrationTest:70 | 符合 |
| PRD-005 | AC-2.3 方案可預約區間依等級生效 | BookingOrchestrator.java:122 | BookingIntegrationTest:86; BookingContextIntegrationTest:113 | 符合 |
| PRD-005 | AC-2.4 毛孩預設全選與種類提示 | PublicBookingPage.tsx:370 / :594（前端） | TS-005 E2E（E2E 待重寫，範圍外） | 符合（畫面） |
| PRD-005 | AC-2.5 毛孩綁定檢核 | BookingOrchestrator.java:122 | BookingIntegrationTest:99 | 符合 |
| PRD-005 | AC-2.6 格式與數量上限 | BookingOrchestrator.java:122 | BookingIntegrationTest:126 | 符合 |
| PRD-005 | AC-2.7 同日不阻擋與待評估訂單上限 | BookingOrchestrator.java:122 | BookingIntegrationTest:156; BookingIntegrationTest:304 | 符合 |
| PRD-005 | AC-2.8 方案不可預約 | BookingOrchestrator.java:122 | BookingIntegrationTest:165 | 符合 |
| PRD-005 | AC-3.1 建立訂單、行程與毛孩綁定 | BookingOrchestrator.java:122 | BookingIntegrationTest:34 | 符合 |
| PRD-005 | AC-3.2 評估期限：一般單與緊急單 | BookingOrchestrator.java:122 | BookingIntegrationTest:55 | 符合 |
| PRD-005 | AC-3.3 後端重算、畫面過期與金額上限 | BookingOrchestrator.java:122 | BookingIntegrationTest:180 | 符合 |
| PRD-005 | AC-3.4 冪等重送 | BookingOrchestrator.java:122 | BookingIdempotencyIntegrationTest:22; BookingIdempotencyIntegrationTest:38 | 符合 |
| PRD-005 | AC-3.5 新客戶填寫問卷 | BookingOrchestrator.java:122 | BookingQuestionnaireIntegrationTest:56; BookingQuestionnaireIntegrationTest:129 | 符合 |
| PRD-005 | AC-3.6 問卷畫面過期與格式 | BookingOrchestrator.java:122 | BookingQuestionnaireIntegrationTest:80; BookingQuestionnaireIntegrationTest:95 | 符合 |
| PRD-005 | AC-3.7 熟客與無題目免填 | BookingOrchestrator.java:122 | BookingQuestionnaireIntegrationTest:107 | 符合 |
| PRD-005 | AC-3.8 送單附帶：我的最愛與通知 | BookingOrchestrator.java:154（commit 後我的最愛）/ :294（enqueue） | BookingSideEffectIntegrationTest:26; BookingSideEffectIntegrationTest:40 | 符合 |
| PRD-006 | AC-1.1 評估頁內容 | OrderDetailService.java:448（E5） | EvaluationQueryIntegrationTest:24 | 符合 |
| PRD-006 | AC-1.2 問卷答案快照 | OrderDetailService.java:448（E5） | EvaluationQueryIntegrationTest 無任何 questionnaire.answers 斷言（見問題 #3） | 測試不足 |
| PRD-006 | AC-1.3 免填顯示與熟客標記 | OrderDetailService.java:448（E5） | EvaluationQueryIntegrationTest:39 | 符合 |
| PRD-006 | AC-1.4 評估頁越權 | OrderDetailService.java:448（E5） | OrderIdentitySecurityTest:44; EvaluationQueryIntegrationTest:74 | 符合 |
| PRD-006 | AC-1.5 報價預檢提示 | OrderDetailService.java:448（E5） | EvaluationQueryIntegrationTest:50 | 符合 |
| PRD-006 | AC-2.1 原價接單 | QuoteService.java:79（E6） | QuoteIntegrationTest:31 | 符合 |
| PRD-006 | AC-2.2 PRO 手動調價 | QuoteService.java:79（E6） | QuoteIntegrationTest:103 | 符合 |
| PRD-006 | AC-2.3 報價快照 | QuoteService.java:79（E6） | QuoteIntegrationTest:31 | 符合 |
| PRD-006 | AC-2.4 首次收款建立與期限—一般單 | QuoteService.java:79（E6） | QuoteIntegrationTest:31 | 符合 |
| PRD-006 | AC-2.5 首次收款期限—緊急單 | QuoteService.java:79（E6） | QuoteIntegrationTest:91 | 符合 |
| PRD-006 | AC-2.6 重複送出與跨角色併發 | QuoteService.java:79（E6） | QuoteConcurrencyIntegrationTest:22; QuoteConcurrencyIntegrationTest:35 | 符合 |
| PRD-006 | AC-2.7 報價後不可編輯 | QuoteService.java:79（E6） | QuoteIntegrationTest:186 | 符合 |
| PRD-006 | AC-3.1 報價越權 | QuoteService.java:79（E6） | OrderIdentitySecurityTest:44; QuoteIntegrationTest:196 | 符合 |
| PRD-006 | AC-3.2 方案不足調價 | QuoteService.java:79（E6） | QuoteIntegrationTest:103 | 符合 |
| PRD-006 | AC-3.3 金額與欄位驗證 | QuoteService.java:79（E6） | QuoteIntegrationTest:116 | 符合 |
| PRD-006 | AC-3.4 評估期限與首個行程日 | QuoteService.java:79（E6） | QuoteIntegrationTest:133 | 符合 |
| PRD-006 | AC-3.5 月接單額度 | QuoteService.java:79（E6） | QuoteIntegrationTest:148 | 符合 |
| PRD-006 | AC-3.6 訂閱鎖定 | QuoteService.java:79（E6） | QuoteIntegrationTest:164 | 符合 |
| PRD-006 | AC-3.7 畫面金額過期 | QuoteService.java:79（E6） | QuoteIntegrationTest:174 | 符合 |
| PRD-006 | AC-3.8 未設定收款帳號 | QuoteService.java:79（E6） | QuoteIntegrationTest:148 | 符合 |
| PRD-007 | AC-1.1 CHARGE 付款頁與帳號快照 | PaymentQueryService.java:44 | PaymentIntegrationTest:63 | 符合 |
| PRD-007 | AC-1.2 REFUND 付款頁與 ADMIN_ADJUSTMENT | PaymentSubmissionService.java:68 | PaymentIntegrationTest:184; ModificationIntegrationTest:373 | 符合 |
| PRD-007 | AC-1.3 讀取與上傳越權 | PaymentSubmissionService.java:68 | OrderIdentitySecurityTest:64; PaymentIntegrationTest:104 | 符合 |
| PRD-007 | AC-1.4 上傳成功 | PaymentSubmissionService.java:68 | PaymentIntegrationTest:125 | 符合 |
| PRD-007 | AC-1.5 欄位與檔案驗證 | PaymentSubmissionService.java:68 | PaymentIntegrationTest:140 | 符合 |
| PRD-007 | AC-1.6 冪等 | PaymentSubmissionService.java:68 | PaymentIntegrationTest:214 | 符合 |
| PRD-007 | AC-1.7 狀態與期限不允許 | PaymentSubmissionService.java:68 | PaymentIntegrationTest:169 | 符合 |
| PRD-007 | AC-1.8 GCS 失敗與跨角色競爭 | PaymentSubmissionService.java:68 | PaymentIntegrationTest:233; PaymentIntegrationTest:254 | 符合 |
| PRD-007 | AC-2.1 首次付款確認 → 訂單成立 | PaymentReviewService.java:51 | PaymentIntegrationTest:303 | 符合 |
| PRD-007 | AC-2.2 補款 / 退款確認 | PaymentReviewService.java:51 | ModificationIntegrationTest:354 / :373（以 PRD-023 標註，未標 PRD-007） | 符合 |
| PRD-007 | AC-2.3 駁回、重新上傳與 SUPPLEMENT 逾期確認框 | PaymentReviewService.java:126 | PaymentIntegrationTest:324 | 符合 |
| PRD-007 | AC-2.4 退款不可駁回 | PaymentReviewService.java:126 | PaymentIntegrationTest:337 | 符合 |
| PRD-007 | AC-2.5 確認 / 駁回權限 | PaymentReviewService.java:51 | OrderIdentitySecurityTest:64; PaymentIntegrationTest:337 | 符合 |
| PRD-007 | AC-2.6 狀態不允許、過期畫面與冪等 | PaymentReviewService.java:51 | PaymentIntegrationTest:353 | 符合 |
| PRD-007 | AC-2.7 憑證檢視與簽名網址 | PaymentQueryService.java:53 / :69 | PaymentIntegrationTest:80 | 符合 |
| PRD-007 | AC-2.8 通知失敗不回滾 | PaymentReviewService.java:51 | PaymentIntegrationTest:370 | 符合 |
| PRD-007 | AC-3.1 期限顯示與緊急單 | QuoteService.java:79 | QuoteIntegrationTest:31 | 符合 |
| PRD-007 | AC-3.2 付款提醒 | OrderCronItemProcessor.java:92 | OrderCronIntegrationTest:151 | 符合 |
| PRD-007 | AC-3.3 逾期取消 O8 | OrderCronItemProcessor.java:111 | OrderCronIntegrationTest:168 | 符合 |
| PRD-007 | AC-3.4 SUBMITTED 不逾期；INITIAL 逾期後駁回 | OrderCronItemProcessor.java:111；PaymentReviewService.java:126 | OrderCronIntegrationTest:168 | 符合 |
| PRD-007 | AC-3.5 待核對升級 | OrderCronItemProcessor.java:131 | OrderCronIntegrationTest:192 | 符合 |
| PRD-007 | AC-3.6 ADMIN 作廢並取消 O16 | AdminOrderService.java:79 | DisputeAndAdminIntegrationTest:261 | 符合 |
| PRD-007 | AC-3.7 ADMIN 限制 | AdminOrderService.java:79 | DisputeAndAdminIntegrationTest:273 | 符合 |
| PRD-007 | AC-3.8 保母確認 / 駁回 vs ADMIN 作廢競爭 | AdminOrderService.java:79 | DisputeAndAdminIntegrationTest:285 | 符合 |
| PRD-008 | AC-1.1 第一次開始觸發 O9 | VisitLifecycleService.java:53（F3） | VisitStartIntegrationTest:19 | 符合 |
| PRD-008 | AC-1.2 進行中 / 爭議中訂單開始不改主狀態 | VisitLifecycleService.java:53（F3） | VisitStartIntegrationTest:44 | 符合 |
| PRD-008 | AC-1.3 開始的日期與殭屍期限 | VisitLifecycleService.java:53（F3） | VisitStartIntegrationTest:71 | 符合 |
| PRD-008 | AC-1.4 狀態不允許 | VisitLifecycleService.java:53 / :91 | VisitStartIntegrationTest:94; VisitCompleteIntegrationTest:43 | 符合 |
| PRD-008 | AC-1.5 結束行程不看打勾與日誌 | VisitLifecycleService.java:91（F4） | VisitCompleteIntegrationTest:19 | 符合 |
| PRD-008 | AC-1.6 越權、角色與缺 key | VisitLifecycleService.java:53（F3） | VisitAuthorizationIntegrationTest:18 | 符合 |
| PRD-008 | AC-1.7 重送與訂閱鎖定 | VisitLifecycleService.java:53（F3） | VisitIdempotencyIntegrationTest:19; VisitIdempotencyIntegrationTest:49 | 符合 |
| PRD-008 | AC-1.8 跨角色併發 | VisitLifecycleService.java:53（F3） | VisitConcurrencyIntegrationTest:32; VisitConcurrencyIntegrationTest:52 | 符合 |
| PRD-008 | AC-2.1 殭屍關閉觸發 O9 | VisitCommandServiceImpl.java:167（closeZombieVisits） | CloseZombieVisitsIntegrationTest:23 | 符合 |
| PRD-008 | AC-2.2 殭屍範圍與邊界 | VisitCommandServiceImpl.java:167；VisitRepository（候選） | CloseZombieVisitsIntegrationTest:47 | 符合 |
| PRD-008 | AC-2.3 IN_PROGRESS 逾時自動結束 | VisitTimeoutCronService.java:52（FC1） | InProgressTimeoutCronIntegrationTest:30 | 符合 |
| PRD-008 | AC-2.4 逾時邊界與手動結束競爭 | VisitTimeoutCronService.java:52；VisitLifecycleService.java:91 | VisitConcurrencyIntegrationTest:114; InProgressTimeoutCronIntegrationTest:53 | 符合 |
| PRD-008 | AC-2.5 排程與他方操作的併發 | VisitLifecycleService.java:53；OrderCronItemProcessor.java:266 | VisitConcurrencyIntegrationTest:70; VisitConcurrencyIntegrationTest:89 | 符合 |
| PRD-008 | AC-2.6 排程重驗、失敗、重跑與身分 | VisitTimeoutCronService.java:52；OrderCronItemProcessor.java:266 | CloseZombieVisitsIntegrationTest:79; InProgressTimeoutCronIntegrationTest:79 | 符合 |
| PRD-008 | AC-3.1 保母今日行程內容與排序 | VisitReadService.java:135（F6） | VisitDashboardIntegrationTest:38 | 符合 |
| PRD-008 | AC-3.2 待處理區 | VisitReadService.java:135 | VisitDashboardIntegrationTest:72 | 符合 |
| PRD-008 | AC-3.3 飼主訂單詳情行程狀態 | VisitReadService.java:58（F1） | OrderVisitListIntegrationTest:20 | 符合 |
| PRD-008 | AC-3.4 飼主行程頁 | VisitReadService.java:148（F7） | VisitDashboardIntegrationTest:101 | 符合 |
| PRD-008 | AC-3.5 讀取越權 | VisitReadService.java:58 | VisitDashboardIntegrationTest:38; OrderVisitListIntegrationTest:49 | 符合 |
| PRD-008 | AC-3.6 行程命名與預設名稱 | VisitLifecycleService.java:123（F5） | VisitRenameIntegrationTest:30; VisitRenameIntegrationTest:64 | 符合 |
| PRD-008 | AC-3.7 命名被拒 | VisitLifecycleService.java:123 | VisitRenameIntegrationTest:72 | 符合 |
| PRD-009 | AC-1.1 確認結案成功與通知 | OrderCompletionService.java:121（E25）；OrderDetailService.java:103（completionHint） | DisputeAndAdminIntegrationTest:301 | 符合 |
| PRD-009 | AC-1.2 行程或日誌未完成 | OrderCompletionService.java:121（E25）；OrderDetailService.java:103（completionHint） | DisputeAndAdminIntegrationTest:311; DisputeAndAdminIntegrationTest:394 | 符合 |
| PRD-009 | AC-1.3 變更進行中，錯誤先後 | OrderCompletionService.java:121（E25）；OrderDetailService.java:103（completionHint） | DisputeAndAdminIntegrationTest:311; DisputeAndAdminIntegrationTest:394 | 符合 |
| PRD-009 | AC-1.4 狀態不允許與終態不可改 | OrderCompletionService.java:121（E25）；OrderDetailService.java:103（completionHint） | DisputeAndAdminIntegrationTest:301 | 符合 |
| PRD-009 | AC-1.5 越權與缺 key | OrderCompletionService.java:121（E25）；OrderDetailService.java:103（completionHint） | DisputeAndAdminIntegrationTest:351 | 符合 |
| PRD-009 | AC-1.6 冪等與併發 | OrderCompletionService.java:121（E25）；OrderDetailService.java:103（completionHint） | DisputeAndAdminIntegrationTest:351 | 符合 |
| PRD-009 | AC-1.7 結案後畫面 | OwnerOrderDetail.tsx:313；SitterOrderDetail.tsx:447（前端） | TS-009 E2E（E2E 待重寫，範圍外）；後端欄位 DisputeAndAdminIntegrationTest:394 | 符合（畫面） |
| PRD-009 | AC-1.8 行程清單顯示範圍 | OrderDetailService.java:103（E2 visits） | OrderVisitListIntegrationTest:71 | 符合 |
| PRD-009 | AC-2.1 自動結案時間點與通知 | OrderCronItemProcessor.java:289（C8） | OrderCronIntegrationTest:358 | 符合 |
| PRD-009 | AC-2.2 自動結案不看日誌，逾期提示 | OrderCronItemProcessor.java:289（C8） | OrderCronIntegrationTest:376; DisputeAndAdminIntegrationTest:394 | 符合 |
| PRD-009 | AC-2.3 自動結案略過的訂單 | OrderCronItemProcessor.java:289（C8） | OrderCronIntegrationTest:376 | 符合 |
| PRD-009 | AC-2.4 排程計數、失敗隔離、重跑與身分 | OrderCronItemProcessor.java:289（C8） | C8 計數 / 單筆失敗隔離無測試；403 由 OrderCronIntegrationTest:71 涵蓋（問題 #7） | 測試不足 |
| PRD-009 | AC-2.5 回報問題成功與通知 | OrderCompletionService.java:190（E26） | DisputeAndAdminIntegrationTest:323 | 符合 |
| PRD-009 | AC-2.6 爭議視窗邊界與併發 | OrderCompletionService.java:190（E26） | DisputeAndAdminIntegrationTest:323 | 符合 |
| PRD-009 | AC-2.7 回報問題前置條件不符 | OrderCompletionService.java:190（E26） | DisputeAndAdminIntegrationTest:339 | 符合 |
| PRD-009 | AC-2.8 說明驗證、越權與冪等 | OrderCompletionService.java:190（E26） | DisputeAndAdminIntegrationTest:339 | 符合 |
| PRD-009 | AC-3.1 收入 = 已收淨額 | SitterLedgerService.java:41（E30） | DisputeAndAdminIntegrationTest:446 | 符合 |
| PRD-009 | AC-3.2 月份歸屬邊界 | SitterLedgerService.java:41（E30） | DisputeAndAdminIntegrationTest:475 | 符合 |
| PRD-009 | AC-3.3 非終態不計，終態後才計入 | SitterLedgerService.java:41（E30） | DisputeAndAdminIntegrationTest:446 | 符合 |
| PRD-009 | AC-3.4 位置、預設月份與空狀態 | SitterLedgerService.java:41（E30） | DisputeAndAdminIntegrationTest:446 | 符合 |
| PRD-009 | AC-3.5 權限、方案與鎖定 | SitterLedgerService.java:41（E30） | DisputeAndAdminIntegrationTest:446 | 符合 |
| PRD-009 | AC-3.6 飼主訂單分頁歸屬與載入更多 | OrderDetailService.java:343（E3） | OrderHistoryIntegrationTest:23 | 符合 |
| PRD-009 | AC-3.7 飼主歷史卡片、篩選與空狀態 | OrderDetailService.java:343（E3） | OrderHistoryIntegrationTest:50 | 符合 |
| PRD-009 | AC-3.8 修改單、淨額 0、排序與訂單編號 | SitterLedgerService.java:41（E30） | DisputeAndAdminIntegrationTest:446 | 符合 |
| PRD-016 | AC-1.1 飼主發起 CHANGE | ModificationService.java:63（E17）；ModificationPreviewService.java:39 | ModificationPreviewIntegrationTest:259; ModificationIntegrationTest:76 | 符合 |
| PRD-016 | AC-1.2 飼主發起 CANCEL_ALL 與原因 | ModificationService.java:63（E17）；ModificationPreviewService.java:39 | ModificationIntegrationTest:94 | 符合 |
| PRD-016 | AC-1.3 保母發起即報價 | ModificationService.java:63（E17）；ModificationPreviewService.java:39 | ModificationPreviewIntegrationTest:143; ModificationIntegrationTest:94 | 符合 |
| PRD-016 | AC-1.4 不可發起 | ModificationService.java:63（E17）；ModificationPreviewService.java:39 | ModificationPreviewIntegrationTest:350; ModificationIntegrationTest:116 | 符合 |
| PRD-016 | AC-1.5 同時只能一筆未結束變更 | ModificationService.java:63（E17）；ModificationPreviewService.java:39 | ModificationIntegrationTest:129 | 符合 |
| PRD-016 | AC-1.6 減少目標與空內容 | ModificationService.java:63（E17）；ModificationPreviewService.java:39 | ModificationIntegrationTest:138 | 符合 |
| PRD-016 | AC-1.7 越權與角色 | ModificationService.java:63（E17）；ModificationPreviewService.java:39 | OrderIdentitySecurityTest:89; ModificationIntegrationTest:168 | 符合 |
| PRD-016 | AC-1.8 新增行程的毛孩、數量與方案 | ModificationService.java:63（E17）；ModificationPreviewService.java:39 | ModificationPreviewIntegrationTest:350; ModificationIntegrationTest:152 | 符合 |
| PRD-016 | AC-2.1 FREE / BASIC 系統計價 | ModificationService.java:152（E20）；ModificationPreviewService.java:59 | ModificationPreviewIntegrationTest:224; ModificationPreviewIntegrationTest:243 | 符合 |
| PRD-016 | AC-2.2 FREE / BASIC 不可自訂 | ModificationService.java:152（E20）；ModificationPreviewService.java:59 | ModificationPreviewIntegrationTest:350; ModificationPreviewIntegrationTest:414 | 符合 |
| PRD-016 | AC-2.3 PRO 自訂並附原因 | ModificationService.java:152（E20）；ModificationPreviewService.java:59 | ModificationPreviewIntegrationTest:205; ModificationIntegrationTest:208 | 符合 |
| PRD-016 | AC-2.4 計價範圍含 DONE 與 CLOSED_BY_SYSTEM | ModificationService.java:152（E20）；ModificationPreviewService.java:59 | ModificationPreviewIntegrationTest:143; ModificationPreviewIntegrationTest:224 | 符合 |
| PRD-016 | AC-2.5 新增日期下限與方案區間 | ModificationService.java:152（E20）；ModificationPreviewService.java:59 | ModificationPreviewIntegrationTest:414; ModificationIntegrationTest:235 | 符合 |
| PRD-016 | AC-2.6 保母拒絕 | ModificationService.java:210 | ModificationIntegrationTest:266 | 符合 |
| PRD-016 | AC-2.7 金額與原因上限 | ModificationService.java:152（E20）；ModificationPreviewService.java:59 | ModificationPreviewIntegrationTest:350; ModificationPreviewIntegrationTest:414 | 符合 |
| PRD-016 | AC-2.8 方案以報價當下判斷；鎖定依 FREE 計價 | ModificationService.java:152（E20）；ModificationPreviewService.java:59 | ModificationPreviewIntegrationTest:466; ModificationIntegrationTest:190 | 符合 |
| PRD-016 | AC-3.1 飼主撤回 | ModificationService.java:251 | ModificationIntegrationTest:283 | 符合 |
| PRD-016 | AC-3.2 撤回權限與不可撤回狀態 | ModificationService.java:251 | ModificationIntegrationTest:283 | 符合 |
| PRD-016 | AC-3.3 逾時排程 | OrderCronItemProcessor.java:156 | OrderCronIntegrationTest:216 | 符合 |
| PRD-016 | AC-3.4 期限以時間判斷 | ModificationService.java:152 / :210 / :251 | ModificationPreviewIntegrationTest:414; ModificationIntegrationTest:294 | 符合 |
| PRD-016 | AC-3.5 協商頁可執行動作與歷史 | ModificationQueryService.java:35 / :51 | ModificationIntegrationTest:310 | 符合 |
| PRD-016 | AC-3.6 跨角色併發 | ModificationService.java:152 / :251 | ModificationIntegrationTest:491 | 符合 |
| PRD-016 | AC-3.7 發起與報價冪等 | ModificationService.java:63 / :152 | ModificationIntegrationTest:537 | 符合 |
| PRD-016 | AC-3.8 寄信失敗不影響轉移 | OrderNotifier.java（enqueue 同交易） | 無任何測試（見問題 #4） | 測試不足 |
| PRD-022 | AC-1.1 開放條件 | VisitCareLogService.java:122（F14） | VisitCareLogDraftIntegrationTest:19 | 符合 |
| PRD-022 | AC-1.2 首次暫存建立草稿與唯一性 | VisitCareLogService.java:122；VisitCareLogMediaService.java:261 | VisitCareLogDraftIntegrationTest:38; VisitCareLogDraftIntegrationTest:54 | 符合 |
| PRD-022 | AC-1.3 文字欄位與未暫存保護 | VisitCareLogService.java:122 | VisitCareLogDraftIntegrationTest:68 | 符合 |
| PRD-022 | AC-1.4 送出限制與成功 | VisitCareLogService.java:143（F15） | VisitCareLogSubmitIntegrationTest:20; VisitCareLogSubmitIntegrationTest:123 | 符合 |
| PRD-022 | AC-1.5 送出後唯讀 | VisitCareLogService.java:143 | VisitCareLogSubmitIntegrationTest:48 | 符合 |
| PRD-022 | AC-1.6 24h 期限邊界，含系統逾時轉 DONE | VisitCareLogMediaService.java:116 / :261；VisitCareLogService.java:122 | VisitCareLogDeadlineIntegrationTest:20; VisitCareLogDeadlineIntegrationTest:48 | 符合 |
| PRD-022 | AC-1.7 逾期草稿 | VisitCareLogService.java:67（F13） | VisitCareLogReadIntegrationTest:19 | 符合 |
| PRD-022 | AC-1.8 訂單狀態與訂閱鎖定 | VisitCareLogService.java:143 | VisitCareLogSubmitIntegrationTest:69 | 符合 |
| PRD-022 | AC-2.1 FREE 快照僅文字 | VisitCareLogMediaService.java:116（F16）/ :226（F17）/ :261（F18） | VisitCareLogMediaUploadIntegrationTest:22 | 符合 |
| PRD-022 | AC-2.2 額度依快照逐級 | VisitCareLogMediaService.java:116（F16）/ :226（F17）/ :261（F18） | VisitCareLogMediaUploadIntegrationTest:39 | 符合 |
| PRD-022 | AC-2.3 照片格式與大小 | VisitCareLogMediaService.java:116（F16）/ :226（F17）/ :261（F18） | MediaInspectorTest:25; VisitCareLogMediaUploadIntegrationTest:63 | 符合 |
| PRD-022 | AC-2.4 影片格式、長度、大小 | VisitCareLogMediaService.java:116（F16）/ :226（F17）/ :261（F18） | MediaInspectorTest:40; MediaInspectorTest:57 | 符合 |
| PRD-022 | AC-2.5 上傳成功與紀錄 | VisitCareLogMediaService.java:116（F16）/ :226（F17）/ :261（F18） | VisitCareLogMediaUploadIntegrationTest:135 | 符合 |
| PRD-022 | AC-2.6 刪除與額度釋放 | VisitCareLogMediaService.java:478（F19） | VisitCareLogMediaDeleteIntegrationTest:21; VisitCareLogMediaDeleteIntegrationTest:49 | 符合 |
| PRD-022 | AC-2.7 額度併發 | VisitCareLogMediaService.java:116（F16）/ :226（F17）/ :261（F18） | VisitCareLogConcurrencyIntegrationTest:22; VisitCareLogConcurrencyIntegrationTest:43 | 符合 |
| PRD-022 | AC-2.8 儲存服務失敗與確認時狀態改變 | VisitCareLogMediaService.java:116（F16）/ :226（F17）/ :261（F18） | VisitCareLogMediaUploadIntegrationTest:163; VisitCareLogMediaUploadIntegrationTest:212 | 符合 |
| PRD-022 | AC-3.1 送出通知 | VisitCareLogService.java:143 | VisitCareLogSubmitIntegrationTest:97 | 符合 |
| PRD-022 | AC-3.2 飼主檢視 | VisitCareLogService.java:67 | VisitCareLogReadIntegrationTest:38 | 符合 |
| PRD-022 | AC-3.3 越權 | FulfillGuard.java | VisitCareLogAuthorizationIntegrationTest:20 | 符合 |
| PRD-022 | AC-3.4 ADMIN 唯讀 | VisitCareLogService.java:67 | VisitCareLogReadIntegrationTest:79 | 符合 |
| PRD-022 | AC-3.5 送出冪等 | VisitCareLogService.java:143 | VisitCareLogIdempotencyIntegrationTest:20; VisitCareLogIdempotencyIntegrationTest:38 | 符合 |
| PRD-022 | AC-3.6 上傳冪等 | VisitCareLogMediaService.java:116 / :261 | VisitCareLogIdempotencyIntegrationTest:38; VisitCareLogIdempotencyIntegrationTest:83 | 符合 |
| PRD-022 | AC-3.7 跨角色競爭與鎖逾時 | VisitCareLogService.java:143；VisitCareLogMediaService.java:261 | VisitCareLogConcurrencyIntegrationTest:67; VisitCareLogConcurrencyIntegrationTest:90 | 符合 |
| PRD-022 | AC-3.8 錯誤判斷順序 | VisitCareLogService.java:122；VisitCareLogMediaService.java:116 | VisitCareLogErrorOrderIntegrationTest:20 | 符合 |
| PRD-023 | AC-1.1 無差額立即生效 | ModificationService.java:289（E23） | ModificationIntegrationTest:337 | 符合 |
| PRD-023 | AC-1.2 差額 > 0 建立補款；同意不重驗方案 | ModificationService.java:289（E23） | ModificationIntegrationTest:354 | 符合 |
| PRD-023 | AC-1.3 差額 < 0 建立退款 | ModificationService.java:289（E23） | ModificationIntegrationTest:373 | 符合 |
| PRD-023 | AC-1.4 減少目標已開始 | ModificationService.java:289（E23） | ModificationIntegrationTest:397 | 符合 |
| PRD-023 | AC-1.5 同意的跨角色併發、O9 不觸發 | ModificationService.java:289（E23） | ModificationIntegrationTest:517 | 符合 |
| PRD-023 | AC-1.6 飼主拒絕 | ModificationService.java:210 | ModificationIntegrationTest:266 | 符合 |
| PRD-023 | AC-1.7 越權、狀態與期限 | ModificationService.java:289（E23） | ModificationIntegrationTest:467 | 符合 |
| PRD-023 | AC-1.8 同意冪等 | ModificationService.java:289（E23） | ModificationIntegrationTest:537 | 符合 |
| PRD-023 | AC-2.1 補款確認即生效 | PaymentReviewService.java:51（E14 T7/T10） | ModificationIntegrationTest:354 | 符合 |
| PRD-023 | AC-2.2 首次付款不被覆寫 | PaymentReviewService.java:51（E14 T7/T10） | ModificationIntegrationTest:354 | 符合 |
| PRD-023 | AC-2.3 新增日期已過不擋同意、仍建立 | PaymentReviewService.java:51（E14 T7/T10） | ModificationIntegrationTest:452 | 符合 |
| PRD-023 | AC-2.4 補款逾時重算並建系統退款 | OrderCronItemProcessor.java:189（C6） | OrderCronIntegrationTest:258 | 符合 |
| PRD-023 | AC-2.5 純新增逾時不退款 | OrderCronItemProcessor.java:189（C6） | OrderCronIntegrationTest:328 | 符合 |
| PRD-023 | AC-2.6 重算下限 0 | OrderCronItemProcessor.java:189（C6） | OrderCronIntegrationTest:342 | 符合 |
| PRD-023 | AC-2.7 SUBMITTED 不逾時；駁回與逾期上傳 | OrderCronItemProcessor.java:189（C6） | OrderCronIntegrationTest:328 | 符合 |
| PRD-023 | AC-2.8 先前已有 SETTLED 變更時只扣本次取消 | OrderCronItemProcessor.java:189（C6） | OrderCronIntegrationTest:288 | 符合 |
| PRD-023 | AC-3.1 退款確認即結清 | PaymentReviewService.java:51（E14 T7/T10） | ModificationIntegrationTest:373 | 符合 |
| PRD-023 | AC-3.2 退款前不可確認、退款無逾時 | PaymentReviewService.java:51（E14 T7/T10） | 無任何測試（見問題 #5） | 測試不足 |
| PRD-023 | AC-3.3 CONFIRMED 整筆取消結清 → O10 | PaymentReviewService.java:51（E14 T7/T10） | ModificationIntegrationTest:409 | 符合 |
| PRD-023 | AC-3.4 無差額的整筆取消立即 O10 | ModificationService.java:289 | ModificationIntegrationTest:425 | 符合 |
| PRD-023 | AC-3.5 IN_PROGRESS 整筆取消照常結案 | PaymentReviewService.java:51（E14 T7/T10） | ModificationIntegrationTest:437 | 符合 |
| PRD-023 | AC-3.6 系統退款變更 | OrderCronItemProcessor.java:189；ModificationService.java:251 | OrderCronIntegrationTest:258 | 符合 |
| PRD-024 | AC-1.1 飼主於等待退款時申報 | OrderCompletionService.java:60（E24） | DisputeAndAdminIntegrationTest:89 | 符合 |
| PRD-024 | AC-1.2 保母於 CONFIRMED 訂單申報 | OrderCompletionService.java:60（E24） | DisputeAndAdminIntegrationTest:103 | 符合 |
| PRD-024 | AC-1.3 補款待核對滿 72h，自目前 submission 起算 | OrderCompletionService.java:60（E24） | DisputeAndAdminIntegrationTest:103 | 符合 |
| PRD-024 | AC-1.4 不符申報條件 | OrderCompletionService.java:60（E24） | DisputeAndAdminIntegrationTest:115 | 符合 |
| PRD-024 | AC-1.5 越權 | OrderCompletionService.java:60（E24） | DisputeAndAdminIntegrationTest:127 | 符合 |
| PRD-024 | AC-1.6 併發與冪等 | OrderCompletionService.java:60（E24） | DisputeAndAdminIntegrationTest:127 | 符合 |
| PRD-024 | AC-1.7 申報與確認款項併發 | OrderCompletionService.java:60（E24） | DisputeAndAdminIntegrationTest:137 | 符合 |
| PRD-024 | AC-1.8 爭議說明 | OrderCompletionService.java:60（E24） | DisputeAndAdminIntegrationTest:115 | 符合 |
| PRD-024 | AC-2.1 DISPUTED 凍結 | PaymentSubmissionService.java:106；PaymentReviewService.java:63；ModificationEvaluator | PaymentIntegrationTest:199; DisputeAndAdminIntegrationTest:151 | 符合 |
| PRD-024 | AC-2.2 爭議期間行程照常 | VisitLifecycleService.java:53 → OrderLifecycleService.java:29 | DisputeAndAdminIntegrationTest:151 | 符合 |
| PRD-024 | AC-2.3 調解結果：UNPAID 退款 | AdminOrderService.java:185（E29） | DisputeAndAdminIntegrationTest:169; DisputeAndAdminIntegrationTest:205 | 符合 |
| PRD-024 | AC-2.4 調解結果：SUBMITTED 退款與補款 | AdminOrderService.java:185（E29） | DisputeAndAdminIntegrationTest:183 | 符合 |
| PRD-024 | AC-2.5 ADMIN 唯讀範圍 | AdminOrderService.java:123（E27）；AdminOrderAccessPolicy.java:25 | DisputeAndAdminIntegrationTest:220 | 符合 |
| PRD-024 | AC-2.6 協商期間不可結案、不自動結案 | OrderCompletionService.java:121 / :190；OrderCronItemProcessor.java:289 | OrderCronIntegrationTest:376; DisputeAndAdminIntegrationTest:311 | 符合 |
| PRD-024 | AC-2.7 結案基準只看 SETTLED 變更 | CompletionBasisCalculator.java；OrderLifecycleService.java:46 | OrderCronIntegrationTest:358 | 符合 |
| PRD-024 | AC-2.8 訂閱鎖定 / 停權不擋 | 各寫入端點不呼叫 PlanPolicy.requireNotLocked | ModificationPreviewIntegrationTest:466; ModificationIntegrationTest:249 | 符合 |
| PRD-026 | AC-1.1 評估中撤回 | OrderCancelService.java:74（E8） | OrderCancelIntegrationTest:95 | 符合 |
| PRD-026 | AC-1.2 報價後付款前撤回 | OrderCancelService.java:74（E8） | OrderCancelIntegrationTest:108 | 符合 |
| PRD-026 | AC-1.3 已上傳憑證不可撤回 | OrderCancelService.java:74（E8） | OrderCancelIntegrationTest:118 | 符合 |
| PRD-026 | AC-1.4 付款後與終態不可撤回 | OrderCancelService.java:74（E8） | OrderCancelIntegrationTest:118 | 符合 |
| PRD-026 | AC-1.5 期限已過仍可撤回 | OrderCancelService.java:74（E8） | OrderCancelIntegrationTest:131; OrderCronIntegrationTest:136 | 符合 |
| PRD-026 | AC-1.6 越權與角色 | OrderCancelService.java:74（E8） | OrderIdentitySecurityTest:44; OrderCancelIntegrationTest:142 | 符合 |
| PRD-026 | AC-1.7 畫面過期與跨角色併發 | OrderCancelService.java:74（E8） | OrderCancelIntegrationTest:131; OrderCancelIntegrationTest:157 | 符合 |
| PRD-026 | AC-1.8 重複送出與格式 | OrderCancelService.java:74（E8） | OrderCancelIntegrationTest:131 | 符合 |
| PRD-027 | AC-1.1 婉拒成功 | OrderCancelService.java:48（E7） | OrderCancelIntegrationTest:42 | 符合 |
| PRD-027 | AC-1.2 婉拒確認框與原因 | OrderCancelService.java:48（E7） | OrderCancelIntegrationTest:56 | 符合 |
| PRD-027 | AC-1.3 婉拒被拒 | OrderCancelService.java:48（E7） | OrderIdentitySecurityTest:44; OrderCancelIntegrationTest:56 | 符合 |
| PRD-027 | AC-1.4 婉拒併發與重送 | OrderCancelService.java:48（E7） | 併發由 OrderCancelIntegrationTest:157（PRD-026 AC-1.7）涵蓋；婉拒同 key 回放無測試（問題 #6） | 測試不足 |
| PRD-027 | AC-1.5 轉介入口 | OrderQueryService.java:160；SitterOrderDetail.tsx:70 | OrderCancelIntegrationTest:42; ReferralOrderPreconditionIntegrationTest:50 | 符合 |
| PRD-027 | AC-1.6 鎖定或額度已滿仍可婉拒 | OrderCancelService.java:48（E7） | OrderCancelIntegrationTest:69 | 符合 |
| PRD-027 | AC-1.7 期限後仍可婉拒 | OrderCancelService.java:48（E7） | OrderCancelIntegrationTest:69; OrderCronIntegrationTest:136 | 符合 |
| PRD-027 | AC-2.1 逾期取消 | OrderCronItemProcessor.java:71（C1） | OrderCronIntegrationTest:99 | 符合 |
| PRD-027 | AC-2.2 到期邊界 | OrderCronItemProcessor.java:71（C1） | OrderCronIntegrationTest:99 | 符合 |
| PRD-027 | AC-2.3 重驗與略過 | OrderCronItemProcessor.java:71（C1） | OrderCronIntegrationTest:118 | 符合 |
| PRD-027 | AC-2.4 重跑冪等與端點身分 | OrderCronItemProcessor.java:71（C1） | OrderCronIntegrationTest:71 | 符合 |
| PRD-027 | AC-3.1 取消成功 | OrderCancelService.java:111（E9） | OrderCancelIntegrationTest:183 | 符合 |
| PRD-027 | AC-3.2 已上傳憑證不可取消 | OrderCancelService.java:111（E9） | OrderCancelIntegrationTest:195 | 符合 |
| PRD-027 | AC-3.3 狀態不允許 | OrderCancelService.java:111（E9） | OrderCancelIntegrationTest:195 | 符合 |
| PRD-027 | AC-3.4 越權與原因 | OrderCancelService.java:111（E9） | OrderIdentitySecurityTest:44; OrderCancelIntegrationTest:208 | 符合 |
| PRD-027 | AC-3.5 跨角色併發 | OrderCancelService.java:111（E9） | PaymentIntegrationTest:254 | 符合 |
| PRD-027 | AC-3.6 重送與鎖定 | OrderCancelService.java:111（E9） | OrderCancelIntegrationTest:208 | 符合 |
| PRD-027 | AC-3.7 付款期限後仍可取消 | OrderCancelService.java:111（E9） | OrderCancelIntegrationTest:208 | 符合 |
| PRD-028 | AC-1.1 行程建立時從方案帶入 | VisitCommandServiceImpl.java:49 | ChecklistSeedIntegrationTest:28; ChecklistSeedIntegrationTest:69 | 符合 |
| PRD-028 | AC-1.2 新增 | VisitChecklistService.java:50 | ChecklistEditIntegrationTest:21; ChecklistEditIntegrationTest:137 | 符合 |
| PRD-028 | AC-1.3 刪除與排序 | VisitChecklistService.java:77 / :96 | ChecklistEditIntegrationTest:43; ChecklistEditIntegrationTest:158 | 符合 |
| PRD-028 | AC-1.4 可編輯時機 | VisitChecklistService.java:50 | ChecklistEditIntegrationTest:73 | 符合 |
| PRD-028 | AC-1.5 上限與格式 | VisitChecklistService.java:50 | ChecklistEditIntegrationTest:109 | 符合 |
| PRD-028 | AC-1.6 越權 | VisitChecklistService.java:50 | ChecklistAuthorizationIntegrationTest:20 | 符合 |
| PRD-028 | AC-2.1 打勾不強制 | VisitChecklistService.java:118 | ChecklistCheckIntegrationTest:19 | 符合 |
| PRD-028 | AC-2.2 打勾時機與併發 | VisitChecklistService.java:118 | ChecklistCheckIntegrationTest:46; VisitConcurrencyIntegrationTest:114 | 符合 |
| PRD-028 | AC-2.3 飼主唯讀檢視 | VisitReadService.java:58 | ChecklistReadIntegrationTest:20 | 符合 |
| PRD-028 | AC-2.4 讀取越權 | VisitReadService.java:58 / :201 | ChecklistReadIntegrationTest:43 | 符合 |
## 問題清單

| # | 嚴重度 | 分類 | 位置 | SD / PRD 寫的 | 程式實際 | 建議 |
|---|--------|------|------|--------------|---------|------|
| 1 | 重要 | 偏離設計 | `backend/src/main/resources/application-prod.yml:8-9`（繼承 `application.yml:33-35`） | SD-ORDER §8.3 D8、§11.4「Hikari」：prod 明寫 `minimum-idle: 1`、`connection-timeout: 5000`（`maximum-pool-size: 3` 不變），pool 滿時 5 秒內失敗；§8.3 D5 連線預算以此為前提 | prod 只有 `maximum-pool-size: 3`；`minimum-idle` 繼承主設定的 2，`connection-timeout` 為 Hikari 預設 30 秒 | 在 `application-prod.yml` 的 `hikari` 下補 `minimum-idle: 1`、`connection-timeout: 5000`。屬 repo 內設定，不是雲端變更 |
| 2 | 重要 | 偏離設計 | `order/PaymentReviewService.java:131-133`（E15） | PRD-007 §3 權限表（PRD 第 41 行）：對 `method=ADMIN_ADJUSTMENT` payment 的上傳 / 確認 / **駁回**，OWNER 與 SITTER 都回 409 `PAYMENT_STATE_CONFLICT`；PRD-007 第 47 行與 SD-ORDER §3.2：「ADMIN_ADJUSTMENT 對任何當事人寫入一律 409，狀態檢查先於方向檢查」 | E15 先以 `direction != CHARGE \|\| role != SITTER` 回 403 `FORBIDDEN`；ADMIN_ADJUSTMENT 建立時 `direction=REFUND`（`order/AdminOrderService.java:274`、`:282`），所以駁回一律回 403，不是 409。E11 / E14 有跳過方向檢查（`PaymentSubmissionService.java:74`、`PaymentReviewService.java:56`），E15 沒有。無測試涵蓋 | 方向檢查前加 `!isAdminAdjustment(payment)` 條件（同 E11 / E14 寫法），讓它落到鎖內 `currentSubmission` 回 409；在 `PaymentIntegrationTest:184` 補 E15 案例。另外 SD-ORDER §6.3 E15 的錯誤列沒寫「ADMIN_ADJUSTMENT 跳過方向檢查」（E11 / E14 有寫），PRD-007 AC-2.4「退款不可駁回 403 先於 409」與第 41 行對 ADMIN_ADJUSTMENT（方向也是 REFUND）互相矛盾。建議 sd-architect 在 E15 補一句，順便釐清 AC-2.4 的範圍 |
| 3 | 重要 | 測試不足 | `EvaluationQueryIntegrationTest:24`（整個類別） | PRD-006 AC-1.2 問卷答案快照；SD-ORDER §10.3 `EvaluationQueryIntegrationTest` 必測「答案快照不受題目修改影響」 | 實作在（`order/OrderDetailService.java:448` 讀 `order_answers`），但測試類別沒有任何 `questionnaire.answers` / `questionText` 的斷言 | 補一個案例：送單後修改 / 停用題目，E5 的 `questionnaire.answers[]` 仍回送單當下的題目文字、題型、答案（含 JSON null） |
| 4 | 建議 | 測試不足 | — | PRD-016 AC-3.8 寄信失敗不影響轉移；SD-ORDER §1.4 指定 `ModificationInitiateIntegrationTest` | 無任何測試標註或斷言此 AC（outbox 設計本身沒問題） | 在 `ModificationIntegrationTest` 的發起案例補：Resend mock 拋例外 / 偏好關閉時 E17 仍 201、`notifications` 有列；或比照 `PaymentIntegrationTest:370` 的寫法 |
| 5 | 建議 | 測試不足 | — | PRD-023 AC-3.2 退款前不可確認、退款無逾時 | 實作靠通用邏輯：`PaymentReviewService.java:176`（非 `SUBMITTED` → 409），C6 只掃 SUPPLEMENT。沒有測試斷言「UNPAID 的 MODIFICATION_REFUND 呼叫 E14 → 409」與「C6 / C3 不處理 MODIFICATION_REFUND」 | 在 `ModificationIntegrationTest:373` 補這兩個斷言 |
| 6 | 建議 | 測試不足 | `OrderCancelIntegrationTest:42-79` | PRD-027 AC-1.4 婉拒併發與重送（E7 回放） | 撤回 vs 婉拒的併發在 `OrderCancelIntegrationTest:157` 有測；E7 同 key 回放沒有測（E9 有，`:208`） | 補 E7 同 key 回放、換 key → 409 |
| 7 | 建議 | 測試不足 | `OrderCronIntegrationTest:358-395` | PRD-009 AC-2.4 C8 排程計數、失敗隔離、重跑與身分；SD-ORDER §10.3 `CompletionBasisIntegrationTest` | 有測重跑不重做與 403（`:71`），C8 的 `processed / skipped / failed` 計數與單筆例外隔離沒有測（只對 C1 `:118`、C5 `:216` 測過） | 比照 C1 / C5 的寫法補 C8 一例 |
| 8 | 建議 | 測試不足 | `QuoteConcurrencyIntegrationTest:22-51` | SD-ORDER §10.3：`@Version` 衝突（測試替身繞過訂單鎖造版本不符）→ 409 `ORDER_BUSY` + `Retry-After: 1`、**冪等紀錄刪除**（PRD-006 EX-22，CQ-2） | 對映本身只在 `SharedExceptionMappingIntegrationTest:120` 用測試 controller 驗；訂單端點上「版本衝突 → 冪等紀錄被刪、同 key 可重試」沒有端到端測試 | 補一例：交易 B 內以 JDBC 先改 `orders.version`，斷言 409 + `Retry-After` + `idempotency_records` 無該列 |
| 9 | 建議 | 全域違反 | `order/OrderDetailService.java:351-358`、`order/AdminOrderService.java:163-170` | SD-GLOBAL §2.8「分頁」：超過上限回 400 `VALIDATION_FAILED`（`field=size`、`max`），由共用 `PageParams` 驗證 | E3 / E4 / E28 自己寫 `sz > 50` 判斷，400 的 payload 只有 `field=size`、沒有 `max`；沒用 `PageParams.of(page, size, 50)` | 改用 `PageParams.of(page, size, 50)`（`application/common/validation/PageParams.java:22`），或補上 `max` |
| 10 | 建議 | 偏離設計 | `frontend/src/pages/shared/OrderListPage.tsx:19-31`、`frontend/src/components/orders/PaymentSection.tsx:274` | SD-ORDER §9.2：「今天」、期限一律以後端 `serverTime` / `serverMonth` 為準，不以瀏覽器時間判斷 | 歷史月份篩選的「近 12 個月」用 `new Date()` 算；逾期駁回確認框在 `serverTime` 缺漏時退回 `new Date()` | 月份清單改以 E3 回應或 E2 的 `serverTime` 為基準（或接受並在 SD 註明月份清單只是 UI 候選）；`PaymentSection` 的 fallback 移除，改要求 `serverTime` 必傳 |
| 11 | 建議 | 全域違反 | `frontend/src/components/orders/BookingSitterCard.tsx:91-92` | SD-FRONTEND-SPEC §2.3：元件內不得寫死顏色，用 CSS variables | 收藏愛心寫死 `#dc2626` | 改用 `var(--color-error)` 或新增語義變數 |
| 12 | 建議 | 文件落後 | SD-ORDER §1 測試欄、§10.3（50 個類別） | §10.3 列 50 個獨立測試類別，§1 各 AC 指向這些類別名 | 實作合併成 19 個類別，以 @Nested 沿用 SD 類別名（例：`PaymentIntegrationTest` 內的 `PaymentReviewIntegrationTest`、`DisputeAndAdminIntegrationTest` 內的 `CompletionIntegrationTest`），依檔名找不到 SD 所列類別；行為涵蓋大致相同 | SD §10.3 補一欄「實作所在類別（@Nested）」，或在 §11.5 記錄「測試以 @Nested 合併」的決定 |
| 13 | 建議 | 設計問題 | SD-FULFILL §6.3 F13；`fulfill/VisitCareLogService.java:70-77` | F13 判斷順序把「OWNER / ADMIN 讀取時日誌不存在或非 `SUBMITTED` → 404」放在 G4，先於 G4a / G5 | 程式照 SD 寫：非當事人 OWNER 讀已送出日誌得 403、讀未送出得 404，可以分辨某行程日誌是否已送出（需要知道 orderId + visitId，UUID 不可猜，風險低） | 交給使用者與 sd-architect 決定是否把「非 SUBMITTED → 404」移到 G5 之後（當事人才判斷），不要求程式遷就 |

### 各分類問題數

| 分類 | 數量 | 編號 |
|------|------|------|
| 偏離設計 | 3 | #1、#2、#10 |
| 多做 | 0 | — |
| 設計問題 | 1（另 #2 附帶一項 SD / PRD 表述矛盾） | #13 |
| 測試不足 | 6 | #3–#8 |
| 全域違反 | 2 | #9、#11 |
| 文件落後 | 1 | #12 |

### 嚴重度

- 阻擋：0
- 重要：3（#1 prod Hikari 設定未依 D8；#2 E15 對 ADMIN_ADJUSTMENT 回 403 而非 409；#3 PRD-006 AC-1.2 測試無斷言）
- 建議：10

## 備註

- 「多做」檢查：controller 端點與 SD-ORDER §6.2（E1–E33、C1–C11）、SD-FULFILL §6.2（F1–F19、FC1–FC2、FT1–FT2）逐一比對，沒有 SD 未列的使用者端點；`/api/internal/cron/notifications/email-dispatch`（C11）屬 SD-NOTIFY 依賴、SD-ORDER §8.1 有列。
- 文件同步：本分支已同步修改 PRD-016、PRD-023 與多份 TS；SD-ORDER / SD-FULFILL 變更紀錄有記載 B 階段實作決定（§11.5）。除 #12 外沒有發現牽動 AC / API / 資料表卻沒同步改文件的變更。
- 前端 390px 版面與四種狀態只做了靜態檢查（各頁都有載入 / 錯誤 / 空狀態分支），沒有實機截圖驗證；這部分由 E2E 重寫後的 `ui-layout-contrast` 驗證。
