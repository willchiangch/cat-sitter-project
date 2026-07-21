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
* **Then**: 系統重新獲取 **Sorted Advisory Locks**。訂單狀態轉回 `PENDING_PAYMENT`。

## Scenario 2: 線下退款憑證核銷 (退款路徑)
* **Given**: 狀態為 `MODIFYING`。保母報價退款 200 元，飼主點擊 `confirm`。
* **When**: 訂單進入 `REFUND_VERIFY`。保母上傳 `gs://` 憑證，飼主點擊【確認收到款項】。
* **Then**: 訂單狀態回歸 `CONFIRMED`。

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

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [ModificationServiceTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/ModificationServiceTest.java), [ModificationServiceIntegrationTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/ModificationServiceIntegrationTest.java)
- **E2E 對應**: [order-modification.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/order-modification.spec.ts)
- **執行指令**: `mvn test -Dtest=ModificationServiceTest,ModificationServiceIntegrationTest`
- **最後驗證日期**: 2026-07-18
