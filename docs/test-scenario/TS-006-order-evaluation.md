# TS-006: 保母報價審核與快照 (Order Evaluation)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-006 / PRD-012 / SD-006 |
| **測試類型** | ✅ 功能測試 / ✅ 權限測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | 🟢 已實作 (6/6 Scenarios) |

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

## Scenario 4: 報價時該日容量已被原價接單佔滿
* **Given**: 方案 `dailyCapacity = 1`，同日兩張 `PENDING` 單 A、B；保母先原價接單 A（`PENDING_PAYMENT`）。
* **When**: 保母對 B 送出原價報價（adjustment 0）。
* **Then**: 拋 `CapacityFullException`（409 `CAPACITY_FULL`），B 維持 `PENDING`、沒有快照；該日已佔用行程數仍為 1。把容量調成 2 後用**同一顆** Idempotency-Key 重送報價成功（證明失敗時冪等鍵一起 rollback）。
* **自動化對應**: `OrderEvaluationTest.ts006_04_should_RejectQuote_When_DayAlreadyFullByConfirm()`

## Scenario 5: 原價接單與報價同時搶最後一格
* **Given**: 方案 `dailyCapacity = 1`，同日兩張 `PENDING` 單。
* **When**: 一個執行緒打 `ConfirmOrderService.confirmOrder(A)`、另一個打 `EvaluationService.sendQuote(B)`，`CyclicBarrier` 同時起跑；每輪換一天共 5 輪。
* **Then**: 每輪恰好 1 成功、1 `CapacityFullException`，1 張 `PENDING_PAYMENT`、1 張 `PENDING`，該日已佔用行程數 = 1。
* **自動化對應**: `OrderEvaluationTest.ts006_05_should_PreventOverselling_When_ConfirmAndQuoteRace()`

## Scenario 6: 兩張單同時報價
* **Given / When**: 同上，但兩個執行緒都打 `sendQuote`。
* **Then**: 每輪只成功 1 張、只建立 1 筆快照，該日已佔用行程數 = 1。
* **自動化對應**: `OrderEvaluationTest.ts006_06_should_PreventOverselling_When_ConcurrentQuotes()`

> 容量口徑（不含本單自身趟次、多方案以第一方案為準）沿用接單現行實作，待產品決策，見 SD-006 §5。

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 保母送出報價 | 狀態轉為 `PENDING_PAYMENT` | **Snapshot**: 存入 `unit_price`, `plan_title` 與媒體保留天數。 |
| 2 | 修改原始方案價 | 訂單總額不變 | DB `order_snapshots.snapshot_unit_price` 依然保持報價當時數值。 |
| 3 | 樂觀鎖測試 | 保母報價時資料已更新 | 拋出 `OptimisticLockingFailureException` (409) |
| 4 | 權限攔截 | FREE 方案調價 | 拋出 `AUTH_PLAN_LIMIT` (403) |
| 4.5 | 報價時容量已滿 / 與原價接單同時搶 | 409 `CAPACITY_FULL`，訂單維持 `PENDING` | 與 `ConfirmOrderService` 共用 `CapacityGuard`（advisory lock + 鎖內 count），只成功一張 |
| 5 | 從列表點入評估頁原價接單，導回列表（不重新整理） | 該訂單不在「評估中」分頁，「進行中」分頁顯示「待付款」 | 前端 `OrderEvalView` 成功後 invalidate `['orders']` 查詢（迴歸，見下方「本測試發現並修復的真實 bug」） |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [OrderEvaluationTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/OrderEvaluationTest.java), [OrderControllerTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/OrderControllerTest.java)
- **執行指令**: `mvn test -Dtest=OrderControllerTest,OrderEvaluationTest`
- **最後驗證日期**: 2026-10-24（TS-006-04~06 新增）
- **前端迴歸**: `frontend/e2e/order-eval.spec.ts`「從列表進入原價接單後回到列表，訂單不再停留在「評估中」」（mock 訂單 API；2026-10-23 對正式站舊版前端執行為紅燈、修正後本機為綠燈）

---

## 本測試發現並修復的真實 bug

- **保母接單後列表仍顯示「待評估」（2026-10-23，Journey B 證據稽核發現）**：`OrderEvalView.tsx` 接單 / 送報價 / 婉拒成功後直接 `navigate('/sitter/orders')`，沒有 invalidate React Query；`useSitterOrdersQuery` 的 `staleTime: 60s` 讓列表 60 秒內一直沿用舊快取，訂單仍在「評估中」分頁、徽章「待評估」，保母會以為接單沒成功。trace 證實 `POST /confirm` 200 後完全沒有重抓 `GET /api/orders/sitter`。修法：三個成功分支改為先 `await queryClient.invalidateQueries({ queryKey: ['orders'] })` 再導頁。迴歸：`order-eval.spec.ts` 新增一支 SPA 內導覽（不 reload）的測試，Journey B `B-05` 也改成不 reload 直接切「進行中」斷言「待付款」。
- **評估頁品項價格欄被擠壓，「$ 600」看起來像「$ 1600」（2026-10，驗收 P5 / Backlog 第 3 批 U4）**：`OrderEvalView.tsx` 品項列用 `justify-content: space-between` 放兩個 span，沒有間距、價格也可被壓縮；390px 寬度下方案名稱稍長就佔滿整列，說明結尾「每天 1 次」的「1」直接貼著「$ 600」。修法見 SD-006 §5「品項明細排版」。迴歸：`order-eval.spec.ts`「品項明細：說明很長時價格欄不被擠壓」（長方案名稱 mock；斷言價格 `toHaveText('$ 600')`、與說明間距 ≥ 12px、單行 `nowrap`、不超出列寬；修前間距量到 0px）。
- **議價報價路徑沒有容量保護 → 真實超賣（2026-10-24，backlog G1）**：`EvaluationService.sendQuote()` 直接把訂單改成 `PENDING_PAYMENT`，沒有 advisory lock 也沒有 `dailyCapacity` 檢查；而 `countBookedVisitsBySitterIdAndDate` 把 `PENDING_PAYMENT` 算成已佔用。保母原價接滿某天後，仍可用「報價」再接同日的單；兩條路徑同時跑時也互相看不到。拿掉保護跑新測試：TS-006-04（循序：先接單再報價沒拋例外）、TS-006-05（接單 vs 報價併發，第 1 輪 2 張都成功）、TS-006-06（雙報價併發）全紅。修法：把 `ConfirmOrderService` 的「排序取鎖 + 鎖內 count」抽成 `CapacityGuard.lockAndCheck()`，兩條路徑都在改狀態前呼叫（SD-006 §2.1）。只拿掉 `CapacityGuard` 裡的 `acquireLocks`：TS-006-05/06 與 TS-005-02/02b 紅，證明鎖本身必要。
