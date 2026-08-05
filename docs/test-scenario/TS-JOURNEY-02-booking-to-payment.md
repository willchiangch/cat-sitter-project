# TS-JOURNEY-02: 飼主下單到付款全流程 (Booking to Payment)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-000/SD-000（帳號+redirect 導流）、PRD-002/SD-002（毛孩管理）、PRD-005/SD-005（預約送單）、PRD-006/SD-006（報價評估）、PRD-007/SD-007（線下付款）、PRD-021/SD-021（照護記事本） |
| **測試類型** | ✅ 跨模組整合測試（真後端、真資料庫，非 mock） |
| **優先級** | P0（需求端下單到成交是平台核心交易鏈路） |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | `frontend/e2e/journeys/booking-to-payment.spec.ts` |

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
| 3 | UI：`/pets` 建立寵物（名稱必填，物種預設 CAT）+ 上傳頭像 | 寵物卡片顯示新建資料與頭像 | `POST /api/pets` 201 |
| 4 | UI：`/booking/{sitterId}` 選方案卡片 → 選一個未來日期 → 確認日期 → 下一步 → 送出 | 訂單建立成功，送出成功後頁面會自動導向 `/owner/orders/{orderId}` | `POST /api/orders/booking` 201，訂單狀態 `PENDING`；**注意**：只 `await` 點擊送出按鈕不代表送單完成，click() 只保證事件派發，要等這個導向真的發生才代表非同步送單流程跑完 |
| 5 | UI：保母 `/sitter/orders`「評估中」分頁找到訂單 → 進入評估頁 → 原價直接接受 | 訂單狀態轉為待付款 | `POST /api/orders/{orderId}/confirm`（快速接單路徑）後 `Order=PENDING_PAYMENT`；**注意**：此按鈕背後有 `window.confirm()` 對話框，Playwright 預設會自動 dismiss（回傳 false）導致函式提早 return 什麼都不會發生，測試需要主動註冊 `page.on('dialog', d => d.accept())` |
| 6 | UI：飼主 `/owner/orders/{orderId}` 上傳付款憑證（圖片+末五碼+同意勾選） | 訂單狀態轉為已付款待核對 | `POST /api/orders/{orderId}/payment-proof` 200，`Order=PAID` |
| 7 | UI：保母 `/sitter/orders`「進行中」分頁 → 核對入帳面板 → 確認入帳 | 訂單正式成立 | `POST /api/orders/{orderId}/verify-payment` 200，`Order=CONFIRMED` |
| 8 | UI：保母在 `/care-notes/manage/{sitterId}/{ownerId}` 新增一則「其他說明」項目並儲存；飼主在 `/care-notes/view/{sitterId}/{ownerId}` 確認看得到 | 雙方都能看到同一份記事本內容 | `PUT` 記事本 API 200，飼主端查詢包含剛寫入的內容 |

---

## 三、邊界條件 / 例外場景

* **開放重導向防護**：`redirect` 參數只接受同源相對路徑（`/^\/(?!\/)/`），本測試只驗證合法 redirect 生效這一半，惡意 `//evil.com` 或絕對網址的防護屬於程式碼審查範圍，未來若要專門測這塊建議另開一條純前端單元測試，不需要動用真後端 journey。
* **保母接單方式選擇**：本測試選「原價直接接受」（`ConfirmOrderService.confirmOrder` 快速接單路徑），不是保母議價路徑（`EvaluationService.sendQuote`）。議價路徑的零信任金額校驗已經有 `OrderControllerTest`/`OrderEvaluationTest` 覆蓋，這裡不重複測。
* **`window.confirm`/`alert` 對話框**：本頁面群（訂單評估、訂單結案、記事本儲存等）大量使用瀏覽器原生 `alert()` 顯示操作結果，Playwright 預設會自動關閉不阻塞；但 `OrderEvalView.tsx` 的「原價直接接受」與 `OwnerOrderDetail.tsx` 的「訂單結案」用 `window.confirm()` **閘控真實邏輯**，沒有主動 accept 對話框會讓整個動作變成無效點擊，是最容易寫出「測試通過但其實什麼都沒發生」假陽性的地方。
* **讀寫時序**：實測發現正式站極短時間內（約 1 秒內）用不同身份查詢剛寫入的訂單有機率撲空（`POST /api/orders/booking` 201 之後，緊接著用保母帳號查 `GET /api/orders/sitter` 查到空陣列），改用 `awaitOrderStatus` helper 在每個狀態轉移後 poll 過一輪再繼續操作，不能假設「動作 API 回應成功後，下一個查詢就一定看得到」。

---

## 五、本測試發現並修復的真實 bug

跑這條 journey 時，步驟 8（記事本互動）踩到一個跟本次下單流程本身無關、但會擋住所有新保母的既有 bug：`CareNoteController` 整支 class 掛 `@RequirePlan(PlanTier.FREE)`，但 `PlanGatingAspect.doCheckPlan()` 原本是 `subscriptionRepository.findBySitterId(sitterId).orElseThrow(...)`——**保母完全沒有 `subscriptions` 資料列時直接丟例外（403），不是預設當作 FREE tier**。而 `subscriptions` 這張表只有 admin 手動開通、或保母自己呼叫 `/api/sitter/gatekeeper/subscription/mock` 才會寫入，一般註冊/KYC 流程完全不會建立，等於**任何一個真實新保母，只要沒被 admin 手動開通過訂閱，永遠無法使用 Care Notes 及其他 `@RequirePlan` 端點**。這正是 mock-based 單元測試測不到、只有真後端跨模組 journey 才會踩到的那種 bug（既有測試的 `setUp()` 都會手動先塞一筆 `Subscription(planTier=FREE)`，繞過了這個現實中不存在的前提）。

修法：`PlanGatingAspect` 查不到訂閱資料列時不再 `throw`，改為視為 `PlanTier.FREE` 繼續往下判斷（FREE 本來就該是所有人預設可用的最低層級）。已補上 `CareNoteControllerTest.should_Return200_When_SitterHasNoSubscriptionRow` 迴歸測試，全量 `mvn test` 208 個測試綠燈。

---

## 四、附註 / 復原步驟

* **資料清理**：本測試建立的保母/飼主帳號、寵物、訂單、記事本，皆落在 `@e2e-journey.test` 網域，由 CI 跑完後呼叫 `cleanup-e2e-journeys` 統一硬刪除。
* **前置依賴**：`bootstrapVerifiedSitter` helper 重跑一次 Journey A 驗證過的機械部分（純 API），若 Journey A 那條路徑本身失敗，這裡也會連帶失敗——兩者共用同一段邏輯是刻意的，減少重複維護成本。
