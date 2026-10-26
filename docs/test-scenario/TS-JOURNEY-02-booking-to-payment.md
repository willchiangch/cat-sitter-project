# TS-JOURNEY-02: 飼主下單到付款全流程 (Booking to Payment)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-000/SD-000（帳號+redirect 導流）、PRD-002/SD-002（毛孩管理）、PRD-005/SD-005（預約送單）、PRD-006/SD-006（報價評估）、PRD-007/SD-007（線下付款）、PRD-021/SD-021（照護記事本） |
| **測試類型** | ✅ 跨模組整合測試（真後端、真資料庫，非 mock） |
| **優先級** | P0（需求端下單到成交是平台核心交易鏈路） |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | `frontend/e2e/journeys/booking-to-payment.spec.ts`（主流程：原價接單；議價分支：保母調整報價，見 §二-B） |

---

## 一、測試邏輯定義 (Given / When / Then)

* **Given**：一個 Journey A 驗證過的 VERIFIED+isOpen+有方案的保母（本測試用 `bootstrapVerifiedSitter` 純 API 重建，不重複走 UI），與一個從未存在過的飼主帳號。
* **When**：未登入訪客在保母公開頁點「立即預約」→ 走 redirect 登入 → 建寵物 → 送出預約 → 保母接單 → 飼主付款 → 保母核對入帳 → 雙方在照護記事本互動。
* **Then**：訂單狀態依序正確轉移 `PENDING → PENDING_PAYMENT → PAID → CONFIRMED`，且「未登入點立即預約→登入→自動導回下單頁」這個本次新增的 redirect 機制要真的生效。

---

## 二、測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 1 | UI：未登入打開保母公開頁 `/sitter/{sitterId}/profile`，點「立即預約」 | 導向 `/register?redirect=/booking/{sitterId}` | URL 帶正確 redirect 參數 |
| 2 | API 背景建飼主帳號（略過 OTP）→ UI 改走「已有帳號？返回登入」→ `/login` 真實登入 | 登入成功後**自動導回** `/booking/{sitterId}`，不是預設的 `/demo` | 驗證 `LoginPage.tsx`/`RegisterPage.tsx` 新增的 redirect 白名單邏輯真的生效 |
| 3 | UI：`/pets` 建立寵物（名稱必填，物種預設 CAT）+ 上傳頭像 | 寵物卡片顯示新建資料與頭像 | `POST /api/pets` 201；頭像是建檔後另外 `POST /api/pets/{id}/avatar`，要等寵物卡片的 `<img>` 出現且已載入（`naturalWidth>0`）才算完成，只等卡片出現會截到名字首字佔位 |
| 4 | UI：`/booking/{sitterId}` 選方案卡片 → 選一個未來日期 → 確認日期 → 下一步 → 送出 | 訂單建立成功，送出成功後頁面會自動導向 `/owner/orders/{orderId}` | `POST /api/orders/booking` 201，訂單狀態 `PENDING`；**注意**：只 `await` 點擊送出按鈕不代表送單完成，click() 只保證事件派發，要等這個導向真的發生才代表非同步送單流程跑完 |
| 4b | API 證據（fix-batch13 K3） | 頁面實際發出的 `POST /api/orders/booking` 恰好一次、201；**query 與 body 都不帶 `ownerId`**（飼主身分取自 token，fix-batch11 I2），`body.sitterId` 為下單對象 | `trackBookingCalls` + `expectSingleTokenOnlyBooking`（`journeys/helpers.ts`），`apiStep` 留 `bodyKeys` |
| 5 | UI：保母 `/sitter/orders`「評估中」分頁找到訂單 → 進入評估頁 → 原價直接接受 | 訂單狀態轉為待付款；導回 `/sitter/orders` 後（不重新整理）該訂單不在「評估中」，「進行中」分頁顯示「待付款」 | `POST /api/orders/{orderId}/confirm`（快速接單路徑）後 `Order=PENDING_PAYMENT`；**注意**：此按鈕背後有 `window.confirm()` 對話框，Playwright 預設會自動 dismiss（回傳 false）導致函式提早 return 什麼都不會發生，測試需要主動註冊 `page.on('dialog', d => d.accept())` |
| 5b | API 證據（fix-batch13 K3） | 頁面實際發出的 `POST /api/orders/{id}/confirm` 恰好一次、2xx、**query 不帶 `sitterId` / `ownerId`**（fix-batch11 I1） | `trackOrderActionCalls` + `expectSingleTokenOnlyCall` |
| 6 | UI：飼主 `/owner/orders/{orderId}` 上傳付款憑證（圖片+末五碼+同意勾選） | 訂單狀態轉為已付款待核對；reload 後看得到自己的憑證預覽 | `POST /api/orders/{orderId}/payment-proof` 200，`Order=PAID`；憑證 `<img>` 的 `src` 是 `storage.googleapis.com/<bucket>/payment-proofs/...?...X-Goog-Signature=...` 簽名網址且 `naturalWidth>0` (SD-007 §4.7)；`apiStep` 留 JSON（簽名 / Credential 遮蔽，只留 path 與 `X-Goog-Expires`），並以同一物件**不帶簽名**直接 GET 斷言 403（驗收 S1/S3） |
| 7 | UI：保母 `/sitter/orders`「進行中」分頁 → 核對入帳面板（後五碼 `12345`、憑證預覽）→ 確認入帳 | 訂單正式成立 | 核對面板憑證預覽為簽名網址且已載入；「駁回憑證」按鈕 `background-image: none`、文字為錯誤色 `#b02500`（U8）；`POST /api/orders/{orderId}/verify-payment` 200，`Order=CONFIRMED`；核對面板預覽同步驟 6 留 JSON 並斷言不帶簽名 403 |
| 8 | UI：保母在 `/care-notes/manage/{sitterId}/{ownerId}` 新增一則「其他說明」項目並儲存；飼主在 `/care-notes/view/{sitterId}/{ownerId}` 確認看得到 | 雙方都能看到同一份記事本內容 | `PUT` 記事本 API 200，飼主端查詢包含剛寫入的內容 |

### 二-B、議價分支（保母調整報價，2026-10-24 驗收 R7 新增）

走 `EvaluationService.sendQuote`（第 7 批加了 `findByIdAndSitterIdForUpdate` 列鎖）。過去真後端沒有任何 E2E 走到這條路徑，只有 JUnit（TS-006-04~06）。前段上架、下單的 UI 主流程已驗過，這裡用 `bootstrapPendingOrder` 純 API 兜出 PENDING 訂單（$600），從報價開始走 UI。

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 前置 | API：`bootstrapPendingOrder`；管理員 `POST /api/admin/subscriptions/{sitterId}` 設 `PRO` | 訂單 `PENDING`、`totalAmount 600`；保母 `planTier PRO` | 有調整金額時 `PlanGatingAspect` 要求 PRO 以上，新保母沒有 `subscriptions` 資料列視為 FREE 會 403 `AUTH_PLAN_LIMIT`。兩者皆 `apiStep` 留 JSON |
| B2-01 | UI：保母 `/sitter/orders` →「查看並處理訂單」 | 導到 `/sitter/eval/{orderId}`，報價總計 `$ 600`，只有「原價直接接受」 | `sitter-order-eval-btn-confirm` 數量 0 |
| B2-02 | UI：加價 300、折扣 100、填調整原因 | 報價總計 `$ 800`；原價按鈕消失，改顯示「發送調整後報價」 | 有調整時才出現原因欄（`sitter-order-eval-input-reason`）；截圖前把總計捲進畫面 |
| B2-03 | UI：發送調整後報價 | alert「報價已成功送出」；導回 `/sitter/orders`，不 reload 在「進行中」看到「待付款」與 `$ 800` | `POST /api/orders/{id}/quote` 200，`Order=PENDING_PAYMENT`；`apiStep` 斷言 `totalAmount 800`、`adjustmentAmount 200`、`adjustmentReason` 與輸入一致、`amountDue 800` |
| B2-03b | API 證據（fix-batch13 K3） | 頁面實際發出的 `POST /api/orders/{id}/quote` 恰好一次、2xx、**query 不帶 `sitterId` / `ownerId`** | `trackOrderActionCalls` + `expectSingleTokenOnlyCall` |
| B2-04 | UI：飼主 `/owner/orders/{id}` | 徽章「待付款」、訂單金額 `$ 800`，不顯示補款差額提示；顯示「加價 $ 200」與保母填的調整原因（fix-batch12 U6） | `order-total-amount`、`payment-top-up-notice` 數量 0、`order-adjustment-amount`、`order-adjustment-reason` |
| B2-05 | UI：飼主上傳憑證（後五碼 `24680`） | 「付款憑證提交成功」；reload 後徽章「已付款」、金額仍 `$ 800` | `Order=PAID` |
| B2-06 | UI：保母「進行中」核對入帳 | 後五碼 `24680`、卡片 `$ 800`、首次付款不顯示「補款差額」列；確認後成立 | `POST /verify-payment` 200，`Order=CONFIRMED`；最後 `apiStep` 斷言 `totalAmount 800`、`adjustmentAmount 200`、`paymentProofLastFive 24680` |

### 二-C、飼主撤單分支（保母評估中取消預約，2026-10-25 fix-batch10 H5 新增，需 CI 驗證）

PRD-006 例外處理「飼主在保母審核中嘗試取消」。用 `bootstrapPendingOrder` 純 API 兜出 PENDING 訂單（$600），從飼主取消開始走 UI。本機不跑真後端（:8080 不重啟、跑的是舊版），這條只在 CI 對正式站驗證。

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 前置 | API：`bootstrapPendingOrder('bc')` | 訂單 `PENDING`、`cancellableByOwner true` | `apiStep` 留 JSON |
| BC-01 | UI：飼主 `/owner/orders/{id}` | 徽章「待評估」，顯示「取消預約」 | `owner-cancel-booking-btn` |
| BC-02 | UI：點「取消預約」→ confirm | alert「已取消預約」；徽章「已取消」、`cancelled-status-banner`「您已取消此預約」、取消按鈕消失 | `POST /api/orders/{id}/cancel` 200；`apiStep` 斷言 `CANCELLED`、`cancelledBy OWNER`、`cancellableByOwner false` |
| 通知 | API：保母 `GET /api/notifications?role=SITTER` | 「飼主已取消預約」 | `awaitNotification` 輪詢（AFTER_COMMIT + @Async）；`ORDER_AFFAIR`、`linkUrl /sitter/orders` |
| 接單 | API：保母 `POST /api/orders/{id}/confirm` | 409，訂單仍 `CANCELLED` | 跨角色：撤單後保母無法再接 |
| BC-03 | UI：保母 `/sitter/orders`，先切「歷史訂單」再切回「評估中」 | 「歷史訂單」顯示「飼主已取消預約」；「評估中」沒有這張單（空狀態，另拍一張評估中分頁） | `sitter-order-owner-cancelled-{id}`；切回評估中後 `sitter-orders-loading` 0、`sitter-orders-empty` 可見、`sitter-orders-count`「共 0 筆」、`btn-go-order-eval` 0。先在歷史訂單看到這張單是為了證明列表已載入——過去 `goto` 後立刻斷言 `btn-go-order-eval` 0，列表未載入也會通過（驗收 A4） |
| BC-04 | UI：保母 `/sitter/eval/{id}` | 「飼主已取消預約，無法在此頁面操作。」，沒有原價接受 / 報價按鈕；有「返回訂單列表」（fix-batch12 U3） | `sitter-order-eval-not-pending`、`sitter-order-eval-back-to-list` |

### 二-D、保母婉拒分支（2026-10-25 fix-batch12 J6 新增；2026-10-26 fix-batch13 K1 補通知與婉拒原因，需 CI 驗證）

第 11 批把 `POST /api/orders/{id}/reject` 的保母身分改取自 token（BOLA），但真後端 journey 從未呼叫這支（CI run 40177738227 trace：reject 0 次）。

| 步驟 | 操作 | 預期 | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 前置 | API：`bootstrapPendingOrder` | PENDING $600 | `awaitOrderStatus` |
| BR-01 | UI：保母 `/sitter/orders` → 評估頁 → 「拒絕」→ 填原因 | 婉拒面板展開（原因欄有標籤「拒絕原因（選填，飼主看得到）」，fix-batch13 K6），送出鈕可按 | `sitter-order-eval-btn-reject`、`sitter-order-eval-input-reject-reason` |
| BR-02 | UI：送出婉拒 → confirm | confirm「確定要拒絕此訂單嗎？拒絕後飼主將收到通知（含您填寫的原因）…」、alert「已拒絕此訂單」，導回 `/sitter/orders` | `expectDialog` 兩次；`Order=CANCELLED` |
| BR-03 | API 證據 | 頁面實際發出的 `POST /api/orders/{id}/reject` 恰好一次、2xx、**query 不帶 `sitterId` / `ownerId`**；訂單 `CANCELLED`、`cancelledBy=SITTER`、`sitterRejectReason` 與輸入一致、`cancellableByOwner=false`（fix-batch13 K1） | `trackOrderActionCalls` + `expectSingleTokenOnlyCall`（`journeys/helpers.ts`） |
| BR-03b | API：飼主 `GET /api/notifications?role=OWNER` | 「預約已婉拒」，內容「保母已婉拒您的預約（訂單 #前 8 碼），原因：{輸入的原因}」（fix-batch13 K1） | `awaitNotification` 輪詢（AFTER_COMMIT + @Async）；`ORDER_AFFAIR`、`linkUrl /owner/orders/{id}` |
| BR-04 | UI：飼主 `/owner/orders/{id}` | 徽章「已取消」、橫幅「保母已婉拒此預約」、下方「婉拒原因：{輸入的原因}」，沒有取消預約與付款鈕 | `order-status-badge`、`cancelled-status-banner` 整段 `toHaveText('保母已婉拒此預約婉拒原因：' + 原因)`（與 mock `owner-cancel-booking.spec.ts` 一致；fix-batch14 N6 前為 `toContainText('保母已婉拒此預約')`，多出其他文字攔不到）、`cancelled-reject-reason` |

* **未涵蓋**：報價快照（`order_snapshots`）沒有對外 API，快照金額只由 JUnit `OrderEvaluationTest` 驗證；同一張單併發報價只成功一次（409）、飼主撤單 vs 保母接單 / 報價併發只成功一方，真後端無法用 E2E 穩定重現，仍只有 JUnit TS-006-05/06/08。

---

## 三、邊界條件 / 例外場景

* **第 11 批 BOLA 修正的真後端 network 證據（2026-10-26 fix-batch13 K3）**：6 支改為「身分取自 token」的端點都從頁面實際發出的請求斷言恰好一次、成功、不帶身分參數——booking（步驟 4b，另斷言 body 不帶 `ownerId`）、confirm（5b）、quote（B2-03b）、reject（BR-03），以及 TS-JOURNEY-03 的 complete（8b）、dispute（CD-04）。越權 403 / 錯角色 / 401 依規定不對正式站探測，仍只有 JUnit（`OrderIdentityFromTokenSecurityTest` 等）。

* **開放重導向防護**：`redirect` 參數只接受同源相對路徑（`/^\/(?!\/)/`），本測試只驗證合法 redirect 生效這一半，惡意 `//evil.com` 或絕對網址的防護屬於程式碼審查範圍，未來若要專門測這塊建議另開一條純前端單元測試，不需要動用真後端 journey。
* **保母接單方式選擇**：主流程選「原價直接接受」（`ConfirmOrderService.confirmOrder` 快速接單路徑）；保母議價路徑（`EvaluationService.sendQuote`）由 §二-B 議價分支在真後端走一次（2026-10-24 起）。議價的零信任金額校驗（`expectedTotalAmount` 不符 → 400 `PRICING_MISMATCH`）與 FREE 方案調價 403 仍由 `OrderControllerTest`/`OrderEvaluationTest` 覆蓋，journey 不重複測。
* **`window.confirm`/`alert` 對話框**：本頁面群（訂單評估、訂單結案、記事本儲存等）大量使用瀏覽器原生 `alert()` 顯示操作結果，Playwright 預設會自動關閉不阻塞；但 `OrderEvalView.tsx` 的「原價直接接受」與 `OwnerOrderDetail.tsx` 的「訂單結案」用 `window.confirm()` **閘控真實邏輯**，沒有主動 accept 對話框會讓整個動作變成無效點擊，是最容易寫出「測試通過但其實什麼都沒發生」假陽性的地方。
* **讀寫時序**：實測發現正式站極短時間內（約 1 秒內）用不同身份查詢剛寫入的訂單有機率撲空（`POST /api/orders/booking` 201 之後，緊接著用保母帳號查 `GET /api/orders/sitter` 查到空陣列），改用 `awaitOrderStatus` helper 在每個狀態轉移後 poll 過一輪再繼續操作，不能假設「動作 API 回應成功後，下一個查詢就一定看得到」。

---

## 五、本測試發現並修復的真實 bug

跑這條 journey 時，步驟 8（記事本互動）踩到一個跟本次下單流程本身無關、但會擋住所有新保母的既有 bug：`CareNoteController` 整支 class 掛 `@RequirePlan(PlanTier.FREE)`，但 `PlanGatingAspect.doCheckPlan()` 原本是 `subscriptionRepository.findBySitterId(sitterId).orElseThrow(...)`——**保母完全沒有 `subscriptions` 資料列時直接丟例外（403），不是預設當作 FREE tier**。而 `subscriptions` 這張表只有 admin 手動開通、或保母自己呼叫 `/api/sitter/gatekeeper/subscription/mock`（2026-10 fix-batch10 H1 起僅非正式環境存在，正式站 404）才會寫入，一般註冊/KYC 流程完全不會建立，等於**任何一個真實新保母，只要沒被 admin 手動開通過訂閱，永遠無法使用 Care Notes 及其他 `@RequirePlan` 端點**。這正是 mock-based 單元測試測不到、只有真後端跨模組 journey 才會踩到的那種 bug（既有測試的 `setUp()` 都會手動先塞一筆 `Subscription(planTier=FREE)`，繞過了這個現實中不存在的前提）。

修法：`PlanGatingAspect` 查不到訂閱資料列時不再 `throw`，改為視為 `PlanTier.FREE` 繼續往下判斷（FREE 本來就該是所有人預設可用的最低層級）。已補上 `CareNoteControllerTest.should_Return200_When_SitterHasNoSubscriptionRow` 迴歸測試，全量 `mvn test` 208 個測試綠燈。

**保母接單後列表仍顯示「待評估」（2026-10-23，證據稽核發現）**：步驟 5 的截圖右上已回顯「已成功接單」，畫面卻仍停在「評估中」分頁、徽章「待評估」；trace 顯示 `POST /confirm` 200 之後完全沒有重抓 `GET /api/orders/sitter`。根因是 `OrderEvalView.tsx` 成功後直接 `navigate`，沒有 invalidate React Query，而 `useSitterOrdersQuery` 的 `staleTime` 是 60 秒。舊版測試只靠 `awaitOrderStatus` 輪詢 API、下一步又重新 `goto`，所以一直沒抓到。修法：接單 / 送報價 / 婉拒成功後先 `invalidateQueries({ queryKey: ['orders'] })` 再導頁（見 SD-006 §5）。迴歸：步驟 5 改成不 reload、直接切「進行中」分頁斷言「待付款」；另在 `frontend/e2e/order-eval.spec.ts` 補一支 mock 版前端迴歸測試。

**付款憑證公開可讀（2026-10，安全/個資）**：步驟 7 的憑證預覽過去是 `storage.googleapis.com` 永久公開網址（物件帶 `allUsers READER`），任何人拿到網址都看得到飼主的轉帳截圖。改為私有物件 + 10 分鐘簽名網址，只有訂單飼主/保母能取得 (SD-007 §4.7)。迴歸：步驟 6/7 斷言預覽 `src` 帶 `X-Goog-Signature` 且圖片真的載入；後端 `PaymentControllerTest` 覆蓋 403/200/DTO 不外洩。

**保母婉拒不通知飼主（2026-10-26，驗收 20261026-0007 截圖新問題 1 / fix-batch13 K1）**：BR-02 的確認框寫「拒絕後飼主將收到通知」，但 BR-04 飼主鈴鐺沒有未讀，詳情只寫「此預約已取消」。`EvaluationService.rejectOrder` 只寫日誌、沒有發事件。修法見 TS-006 Scenario 10；本 journey 補 BR-03b（`awaitNotification` 飼主通知含原因）與 BR-04 婉拒原因斷言，BR-03 的 `cancelledBy` 由 null 改為 `SITTER`（契約變更）。

---

## 四、附註 / 復原步驟

* **資料清理**：本測試建立的保母/飼主帳號、寵物、訂單、記事本，皆落在 `@e2e-journey.test` 網域，由 CI 跑完後呼叫 `cleanup-e2e-journeys` 統一硬刪除。
* **前置依賴**：`bootstrapVerifiedSitter` helper 重跑一次 Journey A 驗證過的機械部分（純 API），若 Journey A 那條路徑本身失敗，這裡也會連帶失敗——兩者共用同一段邏輯是刻意的，減少重複維護成本。
