# TS-016: 訂單雙向變更與退款 (Modification & Cancellation)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-016 / SD-016 |
| **測試類型** | ✅ 複雜狀態機測試 / ✅ 財務試算測試 |
| **優先級** | P0 (Critical) |

---

## Scenario 1: 雙向變更與快照重算 (補款路徑)
* **Given**: 訂單已支付，狀態 `CONFIRMED`。
* **When**: 飼主申請「增加兩天」，保母報價後，飼主點擊 `confirm` 同意支付差額。
* **Then**: 系統重新獲取 **Sorted Advisory Locks**。訂單狀態轉回 `PENDING_PAYMENT`；訂單詳情/清單的 `amountDue` 為差額（非新總額），飼主付款頁顯示「本次應補差額」。

## Scenario 1b: 補款核帳後狀態復原與首次付款紀錄保留 (2026-10，B2 迴歸)
* **Given**: 首次付款已核帳（憑證後五碼 `11111`、`paid_at` 已填）；訂單在 `IN_PROGRESS`（或 `CONFIRMED`）時發起變更，飼主確認差額 +600，訂單 `PENDING_PAYMENT`。
* **When**: 飼主上傳補款憑證（後五碼 `54321`）→ 保母核對入帳；另一路徑為保母駁回補款憑證。
* **Then**:
  - 上傳後 `orders` 的首次付款憑證/後五碼/冪等鍵/免責同意時間/`paid_at` 全部不變；補款憑證寫在 `modification_requests.supplement_*`；保母清單看到 `amountDue=600`、後五碼 `54321`，簽名網址簽的是補款憑證。
  - 核帳後訂單回到 `previous_status`（`IN_PROGRESS` 中發起的回 `IN_PROGRESS`，不再一律 `CONFIRMED`），`supplement_verified_at` 有值，`paid_at` 不變，詳情回到首次付款後五碼 `11111`。
  - 駁回只清 `supplement_*`，首次付款資料不變，`PENDING_PAYMENT` 期間 `hasPaymentProof=false`（不把首次憑證當成補款憑證）。
  - 補款沿用首次付款的 `Idempotency-Key` → 409，不上傳檔案。
* **自動化對應**: `PaymentControllerTest.should_RestoreInProgress_When_SupplementVerified`、`should_RestoreConfirmed_When_SupplementVerified`、`should_KeepInitialPayment_And_ExposeSupplementProof_When_SupplementUploaded`、`should_ClearOnlySupplementFields_When_SupplementRejected`、`should_Return409_When_SupplementReusesInitialIdempotencyKey`。
* **E2E 覆蓋範圍**：Journey D 補款分支只覆蓋 `CONFIRMED` 發起的路徑（核帳後回 `CONFIRMED`、首次付款後五碼 `12345` 與 `paidAt` 不變、核對面板顯示補款後五碼 `54321`）。`IN_PROGRESS` 中發起變更需要先讓訂單進入服務中（當日打卡），journey 的訂單日期是明天、無法在測試內打卡，成本過高，**刻意只以上述 JUnit 覆蓋**。

## Scenario 2: 線下退款憑證核銷 (退款路徑)
* **Given**: 狀態為 `MODIFYING`。保母報價退款 200 元，飼主點擊 `confirm`。
* **When**: 訂單進入 `REFUND_VERIFY`。保母上傳 `gs://` 憑證，飼主點擊【確認收到款項】。
* **Then**: 訂單狀態回歸發起變更前的狀態（`CONFIRMED` 發起者回 `CONFIRMED`）。

## Scenario 2b: 退款確認 / 無差額變更依快照復原 (2026-10，C3 迴歸)
* **Given**: 訂單在 `IN_PROGRESS` 時發起變更（`previous_status = IN_PROGRESS`）。
* **When**: (a) 減量變更 → `REFUND_VERIFY` → 飼主確認收到退款；(b) 保母報價使差額為 0 → 飼主同意。
* **Then**: 兩者訂單都回到 `IN_PROGRESS`（不得退回 `CONFIRMED`）；`CONFIRMED` 發起者回 `CONFIRMED`；查無 `previous_status`（舊資料）fallback `CONFIRMED`；變更後無預約項目者退款確認後為 `CANCELLED`。
* **自動化對應**: `ModificationServiceTest.should_RestoreInProgress_When_RefundConfirmed`、`should_RestoreConfirmed_When_RefundConfirmedFromConfirmedOrLegacy`、`should_Cancel_When_RefundConfirmedForWholeCancellation`、`should_RestoreInProgress_When_NoDiffModificationConfirmed`、`should_FallbackToConfirmed_When_NoDiffAndPreviousStatusMissing`。

## Scenario 3: 非本人發起變更應被拒絕
* **Given**: 訂單 `CONFIRMED`，僅飼主與保母為訂單雙方。
* **When**: 第三方（非該訂單的飼主或保母）呼叫發起變更 API。
* **Then**: 系統拒絕，不建立 `ModificationRequest`。
* **自動化對應**: `ModificationServiceTest.should_RejectProposeModification_When_RequesterIsNotOrderParty()`

## Scenario 4: 保母提供差額報價成功
* **Given**: 存在一筆 `PENDING_REVIEW` 的變更請求。
* **When**: 保母呼叫 `quoteModification`，帶入新總額與正確的 `Order.version`。
* **Then**: `ModificationRequest.status` 轉為 `QUOTED`，`diffAmount` 正確計算。
* **自動化對應**: `ModificationServiceTest.should_QuoteModification_Successfully()`

## Scenario 5: 保母拒絕變更後訂單狀態依快照復原
* **Given**: 訂單原狀態為 `IN_PROGRESS`，飼主發起變更後訂單轉為 `MODIFYING`（`previousStatus` 快照為 `IN_PROGRESS`）。
* **When**: 保母呼叫 `rejectModification`。
* **Then**: `ModificationRequest.status → REJECTED`，訂單狀態回到 `IN_PROGRESS`（而非寫死回 `CONFIRMED`）。
* **自動化對應**: `ModificationServiceTest.should_RejectModification_And_RestorePreviousStatus()`

## Scenario 6: Zero-Trust — 同意金額與保母最新報價不符應拒絕
* **Given**: 保母報價差額為 -200 元。
* **When**: 飼主呼叫 `confirmModification`，但帶入的 `agreedDiffAmount` 為 -150（例如頁面快取了舊報價）。
* **Then**: 系統拋出 `PricingMismatchException`，不執行變更。
* **自動化對應**: `ModificationServiceTest.should_RejectConfirm_When_AgreedAmountMismatchesQuote()`

## Scenario 7: 尚未報價即確認應被拒絕
* **Given**: `ModificationRequest.status` 仍為 `PENDING_REVIEW`（保母尚未報價）。
* **When**: 飼主呼叫 `confirmModification`。
* **Then**: 系統拒絕，提示「尚未經保母報價，無法確認」。
* **自動化對應**: `ModificationServiceTest.should_RejectConfirm_When_NotYetQuoted()`

## Scenario 8: 確認變更時僅刪除 PENDING 行程
* **Given**: 訂單同時存在 `PENDING` 與 `DONE` 狀態的 Visit。
* **When**: 飼主確認變更生效。
* **Then**: 僅 `PENDING` 的行程被刪除重建，已完成 (`DONE`) 的行程不受影響。
* **自動化對應**: `ModificationServiceTest.should_OnlyDeletePendingVisits_WhenConfirming()`

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 飼主點擊 Confirm | 執行實質變更與鎖定 | **Snapshot Recalc**: 確認按原合約價計算，非現行價。 |
| 2 | 保母上傳憑證 | 二次驗證通過 | **GCP Storage**: 校驗 `evidence_url` 格式與權限。 |
| 3 | 飼主最終確認 | 解除退款凍結 | **State Cleanup**: `waiting_for_owner_action` 轉為 `false`。 |
| 4 | 保母拒絕變更 | 訂單狀態依快照復原 | `modification_requests.previous_status` 讀取，非寫死值 |
| 5 | 確認金額與報價不符 | 拒絕確認 | `diffAmount` 與 `agreedDiffAmount` 比對 |
| 6 | 補款確認後查詢訂單 | 應付金額為差額 | `amountDue = diffAmount`、`totalAmount = 新總額`（`OrderQueryServiceListTest.amountDue_IsDiffAmount_When_PendingTopUpAfterModification`） |
| 7 | 補款核帳 | 訂單回到發起變更前狀態 | `Order.status = previous_status`、`supplement_verified_at` 非 null、`paid_at` 不變（Scenario 1b） |
| 9 | 確認收到退款 / 無差額同意 | 訂單回到發起變更前狀態 | `Order.status = previous_status`，整筆取消為 `CANCELLED`（Scenario 2b） |
| 8 | 補款上傳/駁回 | 首次付款紀錄保留 | `orders.payment_proof_url/last_five/payment_idempotency_key/disclaimer_agreed_at/paid_at` 不變（Scenario 1b） |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [ModificationServiceTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/ModificationServiceTest.java), [ModificationServiceIntegrationTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/ModificationServiceIntegrationTest.java)
- **E2E 對應**: [order-modification.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/order-modification.spec.ts)
- **補款應付金額 (P8 迴歸)**: [OrderQueryServiceListTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/OrderQueryServiceListTest.java)；E2E `frontend/e2e/journeys/order-modification-negotiation.spec.ts` 補款分支步驟 7/8
- **補款核帳狀態復原 / 首次付款不覆寫 (B2 迴歸)**: [PaymentControllerTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/PaymentControllerTest.java) Scenario 1b 五支；E2E Journey D 補款分支步驟 8
- **執行指令**: `mvn test -Dtest=ModificationServiceTest,ModificationServiceIntegrationTest,OrderQueryServiceListTest,PaymentControllerTest`
- **最後驗證日期**: 2026-07-18

---

## 本測試發現並修復的真實 bug

**補款核帳後狀態一律回 `CONFIRMED`、補款覆寫首次付款紀錄（2026-10，E2E 修復回合「另外發現」）**：`PaymentService.verifyPayment()` 不分首次付款或補款，一律 `status = CONFIRMED` 並覆寫 `paid_at`；`submitPaymentProof()` 把補款憑證直接寫進 `orders.payment_proof_url/last_five/payment_idempotency_key/disclaimer_agreed_at`，`rejectPayment()` 駁回補款時更會把這些欄位清成 null。結果：(1) `IN_PROGRESS` 中發起變更的訂單補款後被退回「已確認未開始」，跟拒絕變更依 `previous_status` 復原的設計不一致（PRD-016 C.3 退款分支已明訂恢復 `CONFIRMED` 或 `IN_PROGRESS`，補款分支原本沒寫，本次補上同一規則）；(2) 首次付款的憑證、後五碼、免責同意時間、付款確認時間在訂單上就消失了，只剩 order log。修法見 SD-016 §4.3：補款紀錄改存 `modification_requests.supplement_*`（Flyway `V20261024_01`），核帳回 `previous_status`。迴歸見 Scenario 1b。

**退款確認、無差額變更也寫死 `CONFIRMED`（2026-10，Backlog 第 2 批 C3）**：B2 修完補款後，同類寫法還留在 `ModificationService`：`confirmRefund()` 只要變更後仍有預約項目就一律 `CONFIRMED`，`confirmModification()` 差額為 0 的分支也直接 `CONFIRMED`。PRD-016 C.3 退款分支明文「恢復 `CONFIRMED` 或 `IN_PROGRESS`」、無差額為「直接恢復」，`IN_PROGRESS` 中發起的變更走這兩條路都會被退回「已確認未開始」（自動結案排程、打卡入口都依狀態判斷）。修法：三條結清路徑（拒絕、退款確認、無差額）共用 `ModificationService.restoreStatus()`，讀發起變更時快照的 `previous_status`，舊資料 fallback `CONFIRMED`；整筆取消仍為 `CANCELLED`。迴歸見 Scenario 2b。
