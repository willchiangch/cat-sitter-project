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
* **Then**: 新增日期不檢查名額（系統不做容量控管，2026-10 移除 Sorted Advisory Locks 與名額檢查）。訂單狀態轉回 `PENDING_PAYMENT`；訂單詳情/清單的 `amountDue` 為差額（非新總額），飼主付款頁顯示「本次應補差額」。

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

## Scenario 1c: 補款核帳通知文案 (2026-10，C5 迴歸)
* **Given**: 同 Scenario 1b，補款憑證已上傳（訂單 `PAID`）。
* **When**: 保母核對入帳。
* **Then**: 飼主收到一筆 `ORDER_AFFAIR` 通知，標題「補款已確認」、內容含「補款」且**不含**「交易正式成立」；首次付款核帳仍是「付款已確認／交易正式成立」。
* **自動化對應**: `PaymentControllerTest.should_SendSupplementWording_When_SupplementVerified`、`should_VerifyPayment_Successfully`（首次付款文案）。

## Scenario 2: 線下退款憑證核銷 (退款路徑)
* **Given**: 狀態為 `MODIFYING`。保母報價退款 200 元，飼主點擊 `confirm`。
* **When**: 訂單進入 `REFUND_VERIFY`。保母上傳退款轉帳截圖（私有物件），飼主檢視簽名網址預覽後點擊【確認收到款項】。
* **Then**: 訂單狀態回歸發起變更前的狀態（`CONFIRMED` 發起者回 `CONFIRMED`）。

## Scenario 2b: 退款確認 / 無差額變更依快照復原 (2026-10，C3 迴歸)
* **Given**: 訂單在 `IN_PROGRESS` 時發起變更（`previous_status = IN_PROGRESS`）。
* **When**: (a) 減量變更 → `REFUND_VERIFY` → 飼主確認收到退款；(b) 保母報價使差額為 0 → 飼主同意。
* **Then**: 兩者訂單都回到 `IN_PROGRESS`（不得退回 `CONFIRMED`）；`CONFIRMED` 發起者回 `CONFIRMED`；查無 `previous_status`（舊資料）fallback `CONFIRMED`；變更後無預約項目者退款確認後為 `CANCELLED`。
* **自動化對應**: `ModificationServiceTest.should_RestoreInProgress_When_RefundConfirmed`、`should_RestoreConfirmed_When_RefundConfirmedFromConfirmedOrLegacy`、`should_Cancel_When_RefundConfirmedForWholeCancellation`、`should_RestoreInProgress_When_NoDiffModificationConfirmed`、`should_FallbackToConfirmed_When_NoDiffAndPreviousStatusMissing`。

## Scenario 2e: 無差額的整筆取消轉 CANCELLED (2026-10，Backlog 第 3 批 D2 迴歸)
* **Given**: 飼主「申請完全取消此預約」（送出空的 items/dates），保母報價時同意取消但不退款（新總額 = 原總額，差額 0），`previous_status = IN_PROGRESS`。
* **When**: 飼主同意變更（`confirmModification`，`agreedDiffAmount = 0`）。
* **Then**: 訂單轉 `CANCELLED`（PRD-016 AC-4），不得回到 `previous_status`；變更請求為 `M_DONE`、`totalAmount` 不變。整筆取消的判斷與退款確認共用 `ModificationService.isWholeCancellation()`（變更後 `Order.items` 為空）。
* **自動化對應**: `ModificationServiceTest.should_Cancel_When_NoDiffModificationIsWholeCancellation`、`should_DetectWholeCancellation_OnlyWhenNoItemsLeft`。

## Scenario 2c: 協商頁依狀態收斂可執行動作、端點重送 409 (2026-10，C2 迴歸 / 驗收 P7)
* **Given**: 變更請求分別處於 `PENDING_REVIEW`、`QUOTED`、`M_DONE`+`REFUND_VERIFY`、`M_DONE`+`PENDING_PAYMENT`，以及已結清（查無進行中變更，GET 204；2026-10-24 前為 409）。
* **When**: 保母進入報價頁、飼主進入確認頁；以及直接對後端重送已完成的動作。
* **Then**:
  - 前端：報價後不再有報價表單（只剩拒絕）；同意後不再有同意表單；補款分支兩頁都沒有退款區塊；退款分支憑證未上傳前飼主沒有「確認已收到退款」；結清後重新進頁兩頁都沒有任何按鈕。
  - 後端：已報價後重送報價、已同意後重送同意、已確認收到退款後重送確認 → 409，資料不變；補款分支或已結清後上傳退款憑證 → 409，既有憑證不被改寫；`GET .../modification` 回傳 `orderStatus`。
* **自動化對應**: `OrderControllerTest.should_Return409_When_QuoteResubmittedAfterQuoted`、`should_Return409_When_ConfirmResubmittedAfterDone`、`should_Return409_When_RefundProofUploadedAfterRefundConfirmed`、`should_Return409_When_RefundProofUploadedOnSupplementBranch`、`should_Return409_When_RefundConfirmResubmitted`、`should_ReturnOrderStatus_When_QueryingModificationRequest`；mock E2E `order-modification.spec.ts`（8 支，含補款/退款/結清各階段）；Journey D 兩分支（TS-JOURNEY-04 步驟 5-6、7、9）。

## Scenario 2d: 退款憑證改為上傳 + 私有 + 簽名網址 (2026-10，C4 迴歸)
* **Given**: 訂單 `REFUND_VERIFY`，最新變更請求 `M_DONE`、差額 -500。
* **When**: 保母以 multipart 上傳退款憑證；飼主/保母/第三方取簽名網址；保母改用舊的 JSON 網址字串上傳。
* **Then**: 非圖片或 > 5MB → 400 不上傳；JSON 網址字串 → 400、DB 不寫入；上傳成功存 `refund-proofs/` Object Key；飼主與保母取得 10 分鐘簽名網址，第三方 403 且不簽名；舊資料的外部網址或未上傳 → 409 且回應不含該網址；`GET .../modification` 只回 `hasRefundProof`。前端飼主端以 `<img>` 顯示簽名網址，退款區塊內沒有任何 `<a>` 連結。
* **自動化對應**: `OrderControllerTest` 中 `C4` 前綴的 7 支與 `should_UploadRefundProof_Successfully_UsingTokenContextIdentity`；mock E2E `order-modification.spec.ts`（multipart content-type、預覽 `src` 為簽名網址、無 `<a>`）；Journey D 退款分支步驟 7、8（`expectSignedRefundProof`：`refund-proofs/` + `X-Goog-Signature` + 圖片載入）。

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

## Scenario C2: 同一變更請求同時確認兩次只結轉一次 (2026-10，backlog 第 7 批)
* **Given**: 訂單 `MODIFYING`、變更請求 `QUOTED`、`diffAmount = -200`。
* **When**: 兩個執行緒同時以相同金額呼叫 `confirmModification`（`CyclicBarrier` 對齊）。
* **Then**: 恰好一個成功、另一個經 `GlobalExceptionHandler` 換算為 409；訂單 `REFUND_VERIFY`、`totalAmount` 只扣一次（1000 → 800）、變更請求 `M_DONE`。保護來源是 `orders` / `modification_requests` 的 `@Version`（容量用的 advisory lock 已移除）。
* **自動化對應**: `ModificationServiceIntegrationTest.should_ApplyOnce_When_SameModificationConfirmedConcurrently()`

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 飼主點擊 Confirm | 執行實質變更（不檢查名額） | **Snapshot Recalc**: 確認按原合約價計算，非現行價。 |
| 2 | 保母上傳憑證 | 圖片上傳成功，雙方可見預覽 | **GCP Storage**: 私有物件 `refund-proofs/...`，DTO 只有 `hasRefundProof`，簽名網址僅訂單雙方可取（Scenario 2d） |
| 3 | 飼主最終確認 | 解除退款凍結 | **State Cleanup**: `waiting_for_owner_action` 轉為 `false`。 |
| 4 | 保母拒絕變更 | 訂單狀態依快照復原 | `modification_requests.previous_status` 讀取，非寫死值 |
| 5 | 確認金額與報價不符 | 拒絕確認 | `diffAmount` 與 `agreedDiffAmount` 比對 |
| 6 | 補款確認後查詢訂單 | 應付金額為差額 | `amountDue = diffAmount`、`totalAmount = 新總額`（`OrderQueryServiceListTest.amountDue_IsDiffAmount_When_PendingTopUpAfterModification`） |
| 7 | 補款核帳 | 訂單回到發起變更前狀態 | `Order.status = previous_status`、`supplement_verified_at` 非 null、`paid_at` 不變（Scenario 1b） |
| 9 | 確認收到退款 / 無差額同意 | 訂單回到發起變更前狀態 | `Order.status = previous_status`，整筆取消（含無差額的整筆取消）為 `CANCELLED`（Scenario 2b / 2e） |
| 10 | 重送已完成的協商動作 / 非退款階段上傳退款憑證 | 409、資料不變；協商頁不再顯示該動作 | `ModificationRequest.status`、`Order.status` 不變（Scenario 2c） |
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

**補款核帳也發「交易正式成立」通知（2026-10，Backlog 第 2 批 C5）**：`NotificationListener.onPaymentVerified()` 不分首次付款或補款，一律通知飼主「您的訂單付款已通過保母確認，交易正式成立。」。補款發生在訂單早已成立之後，這句話會讓飼主誤以為是另一筆新交易。修法：`PaymentVerifiedEvent` 加 `supplement` 旗標（由 `SupplementPaymentResolver.findOpenSupplement()` 判定），補款改發「補款已確認／變更內容正式生效」；category 維持 `ORDER_AFFAIR`，不新增通知類別，因此偏好設定與 SD-014 類別清單不需調整。迴歸見 Scenario 1c。

**協商頁沒有依狀態收斂按鈕、退款憑證端點沒有狀態防護（2026-10，Backlog 第 2 批 C2 / 驗收 P7）**：`SitterModificationQuote`、`OwnerModificationConfirm` 兩頁不看變更請求狀態，報價表單、拒絕、退款憑證上傳（保母）與同意表單、確認收到退款（飼主）永遠全部渲染，只用本地旗標把「剛剛按過的那一個」換成成功字樣。重新進頁後已報價還能再報價、已同意還能再同意（後端 409，使用者只看到「失敗」），補款分支也出現退款區塊。後端 `uploadRefundProof()` 更完全沒有狀態檢查，任何階段都能改寫最新一筆變更請求的退款憑證（含飼主已確認收到退款之後）。修法：`GET .../modification` 加回 `orderStatus`，前端以 `resolveModificationPhase()` 收斂成單一階段、每次動作後重新讀取（SD-016 §2.2 表格）；退款憑證端點限 `REFUND_VERIFY` + `M_DONE` + 差額為負。迴歸見 Scenario 2c。

**退款憑證是保母手填的任意外部網址（2026-10，Backlog 第 2 批 C4，安全）**：`POST .../modification/refund-proof` 收 `{"refundProofUrl": "<任意字串>"}` 直接存進 DB，系統不上傳、不驗證；這個網址原樣回到 `GET .../modification`。飼主端若顯示或點開，等於打開保母提供的任意外部網址（釣魚、追蹤像素、惡意下載）；即使是合法網址，憑證本身也是公開可讀的財務截圖。修法：比照付款憑證（第 1 批 B1）改為 multipart 上傳到私有物件 `refund-proofs/...`、DTO 只回 `hasRefundProof`、檢視走 BOLA 檢查後的 10 分鐘簽名網址，且只簽 `refund-proofs/` 開頭的 key——舊的外部網址永遠不會被簽或回傳。迴歸見 Scenario 2d。

**無差額的整筆取消回到 `previous_status` 而非 `CANCELLED`（2026-10，Backlog 第 3 批 D2）**：C3 把 `confirmModification()` 差額為 0 的分支改成 `restoreStatus()`，但沒有考慮「整筆取消、保母同意不退款」這種差額也是 0 的情境；整筆取消的判斷只寫在 `confirmRefund()`。結果訂單已經沒有任何預約項目（行程也被 `updateVisitsSafely` 刪光），狀態卻回到 `CONFIRMED`/`IN_PROGRESS`，違反 PRD-016 AC-4，也會讓這筆空訂單繼續出現在進行中清單、被自動結案排程撈到。修法：抽出 `ModificationService.isWholeCancellation(order)`（變更後 `Order.items` 為空），退款確認與無差額結清共用，整筆取消一律 `CANCELLED`。迴歸見 Scenario 2e。

**協商結清後的正常終態以紅色錯誤字呈現、API 回 409（2026-10-24，驗收 N8 / backlog 第 6 批 G5）**：`GET /api/orders/{id}/modification` 查無進行中的協商時丟 `IllegalStateException` → 409「此訂單目前沒有進行中的變更請求」，兩個協商頁一律把它當成讀取失敗，以紅字「目前沒有進行中的變更協商（可能已完成、已拒絕或不存在）」顯示。這是正常空狀態，不是衝突：改成 `ModificationService.getActiveModificationRequest` 回 `Optional`、controller 回 204；前端 204 → null 顯示中性空狀態，其他錯誤才紅字。迴歸：`OrderControllerTest.should_Return204_When_NoActiveModificationRequest`（飼主與保母各打一次，204 且 body 為空）、`ModificationServiceTest.should_ReturnEmpty_When_OrderHasNoNegotiationRelatedStatus`；mock `order-modification.spec.ts` 結清步驟改斷言 `mod-confirm-empty` 全文與灰色、`mod-confirm-error` 數量 0；Journey D 步驟 9 改斷言 `mod-*-empty`。
