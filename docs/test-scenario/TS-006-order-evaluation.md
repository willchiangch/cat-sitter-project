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

## Scenario 4: 不做容量控管——同日已有接單，另一張同日單報價仍成功
* **Given**: 同日兩張 `PENDING` 單 A、B；保母先原價接單 A（`PENDING_PAYMENT`）。
* **When**: 保母對 B 送出原價報價（adjustment 0）。
* **Then**: 報價成功，A、B 皆為 `PENDING_PAYMENT`，B 有 1 筆快照（PRD-005 / PRD-006：系統不做容量控管，方案沒有 `dailyCapacity`）。
* **自動化對應**: `OrderEvaluationTest.ts006_04_should_AllowQuote_When_SameDayAlreadyHasConfirmedOrder()`

## Scenario 5: 同一張單原價接單與報價同時起跑
* **Given**: 一張 `PENDING` 單，兩個請求拿同一個 version。
* **When**: 一個執行緒打 `ConfirmOrderService.confirmOrder()`、另一個打 `EvaluationService.sendQuote()`，對象是**同一張**單，`CyclicBarrier` 同時起跑；每輪一張新單共 5 輪。
* **Then**: 每輪恰好 1 成功、另一個經 `GlobalExceptionHandler` 換算為 **409**；訂單為 `PENDING_PAYMENT`、快照 1 筆。
* **自動化對應**: `OrderEvaluationTest.ts006_05_should_TransitionOnce_When_ConfirmAndQuoteRaceOnSameOrder()`

## Scenario 6: 同一張單兩次報價同時起跑
* **Given / When**: 同上，但兩個執行緒都打 `sendQuote`（不同 Idempotency-Key、同一 version，模擬保母兩個分頁同時送出）。
* **Then**: 每輪只成功 1 個、另一個 409，快照 1 筆。
* **自動化對應**: `OrderEvaluationTest.ts006_06_should_QuoteOnce_When_ConcurrentQuotesOnSameOrder()`

> 保護機制：`ConfirmOrderService` 與 `sendQuote` 都以 `OrderRepository.findByIdForUpdate`（`SELECT ... FOR UPDATE`）讀訂單、再比對保母身分（fix-batch11 I1 前是 `findByIdAndSitterIdForUpdate`），見 SD-006 §2.1。

## Scenario 7: 飼主付款前撤單（fix-batch10 H5，PRD-006 AC-7）
* **Given**: 飼主自己的訂單，狀態分別為 `PENDING`、`PENDING_PAYMENT`（未上傳憑證）、`PENDING_PAYMENT`（已有憑證）、`PENDING_PAYMENT` 補款階段（最新變更 `M_DONE`、差額 > 0、未核帳）、`PAID` / `CONFIRMED` / `IN_PROGRESS` / `MODIFYING` / `REFUND_VERIFY` / `DISPUTED` / `COMPLETED` / `CANCELLED`。
* **When**: 飼主 `POST /api/orders/{id}/cancel`（帶畫面上的 version）；另測別的飼主、該訂單保母、SITTER 角色、版本過期、缺 version。
* **Then**: 前兩者成功：訂單與行程 `CANCELLED`、`cancelledBy=OWNER`、`order_logs` 有 `ORDER_CANCELLED_BY_OWNER`、保母收到 `ORDER_AFFAIR`「飼主已取消預約」通知；其餘狀態 409 且訂單不變（補款階段訊息含「補款」，已付款類訊息含「PRD-016」）；非本人 403、版本過期 409、缺 version 400。
* **自動化對應**: `OwnerCancellationServiceTest`（各狀態、補款排除、BOLA、版本、通知）、`OrderControllerTest.should_CancelOrder_When_OwnerCancelsOwnPendingOrder` / `should_RejectCancel_When_NotOwnerOrNotCancellable`（HTTP 層 + 保母端 `cancelledBy`）、`NotificationListenerLinkTest.orderCancelledByOwnerNotifiesSitter`；前端 `frontend/e2e/owner-cancel-booking.spec.ts`（保母端先看歷史訂單、再切評估中斷言空狀態 `sitter-orders-empty`，避免列表未載入就通過，驗收 A4）；真後端 Journey B「保母評估中飼主取消預約」（需 CI 驗證）。

## Scenario 8: 飼主撤單與保母接單 / 報價同時起跑
* **Given**: 一張 `PENDING` 單，飼主與保母拿同一個 version。
* **When**: 一個執行緒 `OwnerCancellationService.cancelByOwner`、另一個 `ConfirmOrderService.confirmOrder`（或 `EvaluationService.sendQuote`），`CyclicBarrier` 同時起跑，每輪新單共 6 輪。
* **Then**: 每輪恰好 1 成功、另一個 409；撤單成功 → `CANCELLED`、無快照、有撤單日誌；保母成功 → `PENDING_PAYMENT`、快照 1 筆、無撤單日誌。
* **自動化對應**: `OwnerCancellationServiceTest.should_SucceedOnlyOnce_When_OwnerCancelRacesSitterConfirm` / `_RacesSitterQuote`

## Scenario 9: 接單 / 報價 / 婉拒身分取自 token（fix-batch11 I1，安全 / BOLA）
* **Given**: 飼主 A 向保母 A 下的 `PENDING` 單；另有保母 B、飼主 A 本人。請求一律帶真的 JWT，走完整 `SecurityConfig` filter chain。
* **When**: 對 `POST /api/orders/{id}/confirm`、`/quote`、`/reject` 各送三種請求：(1) 保母 B 的 token + 舊攻擊手法 `?sitterId=<保母 A>`；(2) 錯角色：飼主 A 的 token；(3) 保母 A 本人 + 亂填的 `?sitterId=<random>`。另測未登入、FREE 保母調價。
* **Then**: (1)(2) 403，訂單仍 `PENDING`；未登入 401；(3) 200 並轉 `PENDING_PAYMENT` / `CANCELLED`（舊身分參數被忽略，舊前端在部署空窗期不壞）；FREE 保母調價仍 403 `AUTH_PLAN_LIMIT`（`PlanGatingAspect` 改以 token 身分判斷方案）。前端請求不再帶任何 query param（`order-eval.spec.ts` 斷言 `searchParams` 為空）。
* **自動化對應**: `OrderIdentityFromTokenSecurityTest.confirm_*` / `quote_*` / `reject_*`；前端 `frontend/e2e/order-eval.spec.ts`（原價接單 / 報價 / 婉拒三支）。

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 保母送出報價 | 狀態轉為 `PENDING_PAYMENT` | **Snapshot**: 存入 `unit_price`, `plan_title` 與媒體保留天數。 |
| 2 | 修改原始方案價 | 訂單總額不變 | DB `order_snapshots.snapshot_unit_price` 依然保持報價當時數值。 |
| 3 | 樂觀鎖測試 | 保母報價時資料已更新 | 拋出 `OptimisticLockingFailureException` (409) |
| 4 | 權限攔截 | FREE 方案調價 | 拋出 `AUTH_PLAN_LIMIT` (403) |
| 4.5 | 同一張單同時原價接單 + 報價 / 兩次報價 | 只成功一個，另一個 409 | 訂單列 `SELECT ... FOR UPDATE`，快照 1 筆；同日其他單照常可接（不做容量控管） |
| 4.6 | 飼主付款前撤單 / 與保母接單、報價併發 | 可撤狀態成功、其他 409；併發只成功一方 | `SELECT ... FOR UPDATE` 同一把列鎖 + version；`cancelled_by='OWNER'`（Scenario 7 / 8） |
| 4.7 | 他人 / 錯角色呼叫接單、報價、婉拒 | 403，訂單不變；本人 200 | 身分取自 JWT（`TokenContext`）+ `@PreAuthorize("hasRole('SITTER')")`；舊 `?sitterId=` 被忽略（Scenario 9） |
| 5 | 從列表點入評估頁原價接單，導回列表（不重新整理） | 該訂單不在「評估中」分頁，「進行中」分頁顯示「待付款」 | 前端 `OrderEvalView` 成功後 invalidate `['orders']` 查詢（迴歸，見下方「本測試發現並修復的真實 bug」） |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [OrderEvaluationTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/OrderEvaluationTest.java), [OrderControllerTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/OrderControllerTest.java)
- **執行指令**: `mvn test -Dtest=OrderControllerTest,OrderEvaluationTest,OwnerCancellationServiceTest,OrderIdentityFromTokenSecurityTest`
- **最後驗證日期**: 2026-10-24（TS-006-04~06 依「不做容量控管」決策改寫，backlog 第 7 批）
- **真後端 E2E**：`frontend/e2e/journeys/booking-to-payment.spec.ts`「保母調整報價（加價 + 折扣 + 原因）…」（TS-JOURNEY-02 §二-B，2026-10-24 驗收 R7）：PRO 保母在評估頁加價 300、折扣 100 送出報價 → `PENDING_PAYMENT`、`totalAmount 800`、`adjustmentAmount 200`、`adjustmentReason` 一致 → 飼主依 $800 付款 → 保母核對入帳 → `CONFIRMED`。快照與併發 409 仍只有 JUnit。
- **前端迴歸**: `frontend/e2e/order-eval.spec.ts`「從列表進入原價接單後回到列表，訂單不再停留在「評估中」」（mock 訂單 API；2026-10-23 對正式站舊版前端執行為紅燈、修正後本機為綠燈）

---

## 本測試發現並修復的真實 bug

- **保母接單後列表仍顯示「待評估」（2026-10-23，Journey B 證據稽核發現）**：`OrderEvalView.tsx` 接單 / 送報價 / 婉拒成功後直接 `navigate('/sitter/orders')`，沒有 invalidate React Query；`useSitterOrdersQuery` 的 `staleTime: 60s` 讓列表 60 秒內一直沿用舊快取，訂單仍在「評估中」分頁、徽章「待評估」，保母會以為接單沒成功。trace 證實 `POST /confirm` 200 後完全沒有重抓 `GET /api/orders/sitter`。修法：三個成功分支改為先 `await queryClient.invalidateQueries({ queryKey: ['orders'] })` 再導頁。迴歸：`order-eval.spec.ts` 新增一支 SPA 內導覽（不 reload）的測試，Journey B `B-05` 也改成不 reload 直接切「進行中」斷言「待付款」。
- **評估頁品項價格欄被擠壓，「$ 600」看起來像「$ 1600」（2026-10，驗收 P5 / Backlog 第 3 批 U4）**：`OrderEvalView.tsx` 品項列用 `justify-content: space-between` 放兩個 span，沒有間距、價格也可被壓縮；390px 寬度下方案名稱稍長就佔滿整列，說明結尾「每天 1 次」的「1」直接貼著「$ 600」。修法見 SD-006 §5「品項明細排版」。迴歸：`order-eval.spec.ts`「品項明細：說明很長時價格欄不被擠壓」（長方案名稱 mock；斷言價格 `toHaveText('$ 600')`、與說明間距 ≥ 12px、單行 `nowrap`、不超出列寬；修前間距量到 0px）。
- **議價報價路徑沒有容量保護 → 真實超賣（2026-10-24，backlog G1）**：`EvaluationService.sendQuote()` 直接把訂單改成 `PENDING_PAYMENT`，沒有 advisory lock 也沒有 `dailyCapacity` 檢查；而 `countBookedVisitsBySitterIdAndDate` 把 `PENDING_PAYMENT` 算成已佔用。保母原價接滿某天後，仍可用「報價」再接同日的單；兩條路徑同時跑時也互相看不到。拿掉保護跑新測試：TS-006-04（循序：先接單再報價沒拋例外）、TS-006-05（接單 vs 報價併發，第 1 輪 2 張都成功）、TS-006-06（雙報價併發）全紅。修法：把 `ConfirmOrderService` 的「排序取鎖 + 鎖內 count」抽成 `CapacityGuard.lockAndCheck()`，兩條路徑都在改狀態前呼叫（SD-006 §2.1）。只拿掉 `CapacityGuard` 裡的 `acquireLocks`：TS-006-05/06 與 TS-005-02/02b 紅，證明鎖本身必要。（**2026-10-24 backlog 第 7 批**：產品決策「系統不做接單容量控管」，`CapacityGuard` 與 `dailyCapacity` 整個移除，TS-006-04~06 改寫為「同日多張可接」與「同一張單只轉出一次」，見下一條。）
- **移除容量鎖後，同一張單同時原價接單 + 報價的後到者回 400 而非 409（2026-10-24，backlog 第 7 批）**：拿掉 `CapacityGuard` 後，改寫的 TS-006-05（同一張單 confirm + quote 併發）、TS-006-06（同一張單兩次 quote）第 1 輪就紅：兩邊都讀到 `PENDING`，後到者撞 `uk_order_snapshot_order_id` → 400 `DATA_ERROR`。資料仍一致（只成功一個、快照一份），但保母看到的是「資料處理異常」。修法：接單與報價都改用 `OrderRepository.findByIdAndSitterIdForUpdate`（`PESSIMISTIC_WRITE`），後到者讀到 `PENDING_PAYMENT` → 409 `STATE_CONFLICT`（SD-006 §2.1）。修正後連跑 3 次皆綠。
- **飼主撤單未實作（2026-10-25，fix-batch10 H5）**：PRD-006 例外處理寫「飼主在保母審核中嘗試取消：允許取消，保母端顯示『飼主已取消預約』」，但後端沒有任何飼主撤單 API，前端也沒有入口；飼主送出預約後只能等保母婉拒，或付款後走 PRD-016 變更協商。補 `POST /api/orders/{id}/cancel`（SD-006 §2.5）：限付款前、排除補款階段、身分取自 token、與保母接單 / 報價同一把列鎖 + version 防跨角色競爭；保母端以新欄位 `cancelled_by` 區分飼主撤單與保母婉拒。迴歸：Scenario 7 / 8。
- **接單 / 報價 / 婉拒可被任何已登入者冒充保母（2026-10-25，fix-batch11 I1，安全 / BOLA）**：`OrderController` 的 `/confirm`、`/quote`、`/reject` 以 `@RequestParam sitterId` 當呼叫者身分，且沒有 `@PreAuthorize`；service 拿「參數裡的 sitterId」去比對訂單。保母 UUID 會出現在訂單詳情 `sitterId`、公開頁網址，飼主或其他保母帶上它就能代替保母接單、報價、婉拒。修法：三支改為 `@PreAuthorize("hasRole('SITTER')")` + `TokenContext.getUserId()`，不再宣告 `sitterId` 參數（舊前端多帶的會被 Spring 忽略）；service 改 `findByIdForUpdate` / `findById` 後比對保母，越權拋 `AccessDeniedException` → 403（過去查不到一律 400）。拿掉參數後 controller 層的 `@RequirePlan` 找不到 `sitterId` 會直接跳過，`PlanGatingAspect` 補「找不到就用 token 身分」，否則 FREE 保母調價的 PRO 門檻在 HTTP 層失效（service 層另有一道仍會擋）。迴歸：Scenario 9（把 controller 換回舊版，16 支全紅）。

**撤單後橫幅像停用按鈕、評估頁沒有出口（2026-10-25，驗收截圖新問題 4 / fix-batch12 U3）**：飼主詳情的 `cancelled-status-banner` 與「取消預約」鈕同樣是灰底（`--color-surface-high`）、置中、粗體，撤單後看起來像一顆停用的按鈕；保母評估頁對非 PENDING 訂單只顯示一行字，沒有返回入口。橫幅改資訊樣式（白底、左側 4px 色條、靠左、`role="status"`），評估頁補「返回訂單列表」（`sitter-order-eval-back-to-list`）。迴歸：`owner-cancel-booking.spec.ts`（比對橫幅與按鈕的 computed style、對比 ≥ 4.5、點返回回到列表；舊版紅）、Journey B BC-04。

**飼主看不到保母的報價調整原因（2026-10-25，驗收 B2-04 / 第 9 批另外發現 #4 / fix-batch12 U6）**：保母評估頁的原因欄寫「飼主會看到」，後端也回 `adjustmentReason`，但飼主訂單詳情只顯示調整後總額。補「保母報價調整：加價 / 折扣 $X」與調整原因。迴歸：`owner-quote-adjustment.spec.ts`（加價 / 折扣 / 原價三案）、Journey B B2-04（需 CI 真後端）。
