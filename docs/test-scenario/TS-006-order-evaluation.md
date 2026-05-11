# TS-006: 保母報價審核與快照 (Order Evaluation)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-006 / PRD-012 / SD-006 |
| **測試類型** | ✅ 功能測試 / ✅ 權限測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | 🟢 已實作 (3/3 Scenarios) |

---

## 0. 前置條件 (Prerequisites)
- **身分驗證**：保母與飼主皆須已登入。
- **認證 Header**：API 請求須包含 `Authorization: Bearer <Token>`。
- **角色權限**：
    - 送出報價僅限 `ROLE_SITTER`。
    - 方案調價限制檢查依據保母的 `subscriptions` 等級。

## Scenario 1: 報價金額快照與不回溯性
* **Given**: 訂單處於 `PENDING` 狀態。
* **When**: 保母送出報價，調增 200 元。隨後保母至後台修改其「專業餵食」方案的原始單價。
* **Then**: 該訂單的 `expectedTotalAmount` 必須保持不變（依據快照計算），不受方案改價影響。
* **自動化對應**: `OrderEvaluationTest.ts006_01_should_KeepPriceConsistent_When_PlanPriceChangesAfterQuote()`

## Scenario 2: SaaS 方案等級調價限制
* **Given**: 保母當前訂閱方案為 `FREE`。
* **When**: 保母嘗試在報價時輸入 `adjustmentAmount` = 100 元。
* **Then**: 系統拋出 `AUTH_PLAN_LIMIT` 錯誤，不允許非 PRO 以上保母調價。
* **自動化對應**: `OrderEvaluationTest.ts006_02_should_ThrowAuthPlanLimitException_When_FreePlanTriesToAdjustPrice()`

## Scenario 3: 樂觀鎖攔截 (併發衝突)
* **Given**: 保母 A 開啟報價頁面（取得 Version 1）。
* **When**: 在保母送出前，飼主或系統更新了該訂單（Version 變為 2），隨後保母嘗試以 Version 1 送出報價。
* **Then**: 系統拋出 `OptimisticLockingFailureException`，攔截過期操作。
* **自動化對應**: `OrderEvaluationTest.ts006_03_should_ThrowOptimisticLockException_When_ConcurrentModification()`

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 保母送出報價 | 狀態轉為 `PENDING_PAYMENT` | **Snapshot**: 存入 `unit_price`, `plan_title` 與媒體保留天數。 |
| 2 | 修改原始方案價 | 訂單總額不變 | DB `order_snapshots.snapshot_unit_price` 依然保持報價當時數值。 |
| 3 | 樂觀鎖測試 | 保母報價時資料已更新 | 拋出 `OptimisticLockingFailureException` (409) |
| 4 | 權限攔截 | FREE 方案調價 | 拋出 `AUTH_PLAN_LIMIT` (403) |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [OrderEvaluationTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/OrderEvaluationTest.java), [OrderControllerTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/OrderControllerTest.java)
- **執行指令**: `mvn test -Dtest=OrderControllerTest,OrderEvaluationTest`
- **最後驗證日期**: 2026-05-11
