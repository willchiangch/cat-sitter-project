# TS-JOURNEY-04: 訂單變更協商 (Order Modification Negotiation)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-016/SD-016（訂單雙向變更與退款） |
| **測試類型** | ✅ 跨模組整合測試（真後端、真資料庫，非 mock） |
| **優先級** | P0（規劃時發現整個協商流程在真實 UI 上完全沒有入口，屬於「功能存在但使用者到不了」等級的落差） |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | `frontend/e2e/journeys/order-modification-negotiation.spec.ts` |

---

## 一、測試邏輯定義 (Given / When / Then)

* **Given**：一筆 `CONFIRMED` 訂單（`bootstrapConfirmedOrder` 純 API 重跑 Journey A+B 驗證過的機械部分兜出；退款分支需要原始訂單天數 > 修改後天數才有負差額可測，`bootstrapConfirmedOrder` 因此新增了可選的 `datesOverride` 參數）。
* **When**：兩條分支各自跑一次完整協商——**補款**：飼主申請增加天數 → 保母報價 → 飼主確認（差額為正，轉 `PENDING_PAYMENT`）→ 飼主補款上傳憑證 → 保母核對入帳。**退款**：飼主申請減少天數 → 保母報價 → 飼主確認（差額為負，轉 `REFUND_VERIFY`）→ 保母上傳退款憑證 → 飼主確認收到退款。
* **Then**：訂單狀態依 SD-016 §4.1 狀態結轉表正確轉移，且雙方全程透過**本次新補上的三個真實按鈕**（申請訂單變更 / 處理訂單變更協商 / 查看並確認訂單變更）點入，不是繞過去直接打 URL。

**架構備註**：規劃本 journey 時發現三個結構性缺口，皆已一併修復，詳見下方「五、本測試發現並修復的真實 bug」。

---

## 二、測試步驟與多維度驗證

### 補款分支

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 1 | UI：飼主 `/owner/orders/{orderId}` 點「申請訂單變更」 | 導向 `/orders/{orderId}/modify` | 按鈕為本次新增，`OwnerOrderDetail.tsx` CONFIRMED 狀態下才顯示 |
| 2 | UI：填新日期（2 天）送出 | 顯示「變更請求已送出」 | `POST /api/orders/{orderId}/modify` 200，`Order=MODIFYING` |
| 3 | UI：保母 `/sitter/orders` 進行中分頁點「處理訂單變更協商」 | 導向 `/sitter/orders/{orderId}/quote`，表單已預填換算後總額 | 連結為本次新增；`GET /api/orders/{orderId}/modification` 200 |
| 4 | UI：接受預填總額，送出報價 | 顯示「報價已送出」 | `POST .../modification/quote` 200，`ModificationRequest=QUOTED`，`diffAmount=+600` |
| 5 | UI：飼主 `/owner/orders/{orderId}` 點「查看並確認訂單變更」 | 導向確認頁，差額欄位顯示 `$600` | 按鈕為本次新增 |
| 6 | UI：確認變更 | 顯示「變更已確認」 | `POST .../modification/confirm` 200，`Order=PENDING_PAYMENT` |
| 7 | UI：飼主上傳補款憑證（沿用 SD-007 既有表單） | 憑證送出成功 | `POST .../payment-proof` 200，`Order=PAID` |
| 8 | UI：保母核對入帳 | 訂單正式回到成立狀態 | `POST .../verify-payment` 200，`Order=CONFIRMED` |

### 退款分支

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 1-2 | 同上，改申請「減少天數」 | `Order=MODIFYING` | 原始訂單 2 天，改為 1 天 |
| 3-4 | 保母接受預填總額（換算後較低） | `ModificationRequest=QUOTED`，`diffAmount=-600` | |
| 5-6 | 飼主確認，差額欄位顯示 `-$600` | `Order=REFUND_VERIFY` | |
| 7 | UI：保母**重新**點「處理訂單變更協商」進入同一頁 | 頁面正常顯示（不 404），填憑證網址送出 | **迴歸測試重點**：`getActiveModificationRequest` 過去只認 `PENDING_REVIEW`/`QUOTED`，飼主確認後 `ModificationRequest` 已轉終態 `M_DONE`，這裡若沒修好會顯示 `mod-quote-error` 進不去表單。`POST .../modification/refund-proof` 200 |
| 8 | UI：飼主**重新**點「查看並確認訂單變更」→ 點「確認已收到退款」 | 顯示「已確認收到退款」 | `POST .../modification/refund-confirm` 200，`Order=CONFIRMED`（本測試訂單非整筆取消，items 非空） |

---

## 三、邊界條件 / 例外場景

* **前端方案有效期間為寫死 mock 常數**：`OrderModificationWizard.tsx` 的 `planStart`/`planEnd` 目前是寫死字串 `2026-05-01`/`2026-05-31`，跟訂單實際日期或方案真實有效期無關，純粹是前端輸入防呆。測試因此故意把變更後日期填在這個區間內（跟現有 mock 版單模組測試 `order-modification.spec.ts`同慣例），不代表這是有意義的業務日期——若未來要把這個常數換成串接真實方案資料，屬於獨立的產品待辦，非本次修復範圍。
* **Zero-Trust 對帳**：`confirmModification` 後端會重新核對 `agreedDiffAmount` 與 `ModificationRequest.diffAmount` 是否一致；`OwnerModificationConfirm.tsx` 的輸入框預設值就是後端算好的最新差額，本測試直接接受預填值送出，沒有特別測「篡改差額」這個防線——該防線已有 `ModificationServiceTest.should_RejectConfirm_When_AgreedAmountMismatchesQuote` 單元測試覆蓋。
* **不驗證的點（刻意）**：`ModificationService` 全程沒有發送任何通知事件（非 Check-in/付款那類有 `eventPublisher` 的模組），本 journey 不斷言任何通知內容。
* **既有技術債（非本次修復範圍）**：SD-016 文件本身已註記 `propose/quote/reject/confirm` 四支端點的 `Idempotency-Key` 只在 Controller 層強制必填，`ModificationService` 內部沒有真的做去重（見 SD-016 §5 WARNING）；另外 `OrderModificationWizard.tsx` 呼叫 `modifyOrder()` 時 `requestedBy` 參數寫死 `'OWNER'`，若保母透過同一個頁面發起變更會被後端拒絕（`callerIsOwner=false` 但 `requestedBy='OWNER'`），本 journey 只測飼主發起的路徑，這個保母發起路徑的斷路屬已知缺陷，留給後續處理。

---

## 四、附註 / 復原步驟

* **資料清理**：本測試建立的帳號、訂單、變更請求皆落在 `@e2e-journey.test` 網域，由 CI 跑完後呼叫 `cleanup-e2e-journeys` 統一硬刪除。
* **前置依賴**：`bootstrapConfirmedOrder` helper 重跑 Journey A+B 的機械部分；退款分支額外傳入 `datesOverride` 參數兜出 2 天的原始訂單。

---

## 五、本測試發現並修復的真實 bug

規劃本 journey、盤查三個協商頁面（`OrderModificationWizard`/`SitterModificationQuote`/`OwnerModificationConfirm`）時，發現這整個模組雖然功能本身可用（API 都通、單元測試都綠），但**真實使用者事實上完全到不了這些頁面**，額外還挖到一個安全性問題：

1. **三個頁面完全沒有真實入口**：`SitterOrders.tsx`/`OwnerOrders.tsx`/`OwnerOrderDetail.tsx` 過去沒有任何按鈕連到 `/orders/:id/modify`、`/sitter/orders/:id/quote`、`/owner/orders/:id/modification-confirm`，只有 `DemoHome.tsx` 用寫死的假 orderId 展示連結。真實訂單一旦要走變更協商，飼主/保母完全無法從列表或詳情頁點進去——這跟 Journey C 抓到的行程入口缺口是同一類問題。已在 `OwnerOrderDetail.tsx`（CONFIRMED/IN_PROGRESS 顯示「申請訂單變更」、MODIFYING/REFUND_VERIFY 顯示「查看並確認訂單變更」）與 `SitterOrders.tsx`（CONFIRMED/IN_PROGRESS 顯示「申請訂單變更」、MODIFYING/REFUND_VERIFY 顯示「處理訂單變更協商」）補上對應按鈕。

2. **退款憑證上傳頁面在真正需要時會 404**：`getActiveModificationRequest()` 過去只認 `ModificationRequest.status ∈ {PENDING_REVIEW, QUOTED}`。但退款流程恰好是「飼主先確認、保母才需要上傳退款憑證」，飼主確認後 `ModificationRequest.status` 已轉終態 `M_DONE`，保母端頁面只要重新整理或重新導航就查無資料——實務上保母永遠上傳不了退款憑證。詳見 SD-016 §2.2、`docs/sd/SD-016-order-modification-cancellation.md`。已修法：訂單處於 `MODIFYING`/`PENDING_PAYMENT`/`REFUND_VERIFY` 這三個協商相關狀態時，改撈該訂單最新一筆變更請求，不限 `status`。

3. **BOLA（Broken Object Level Authorization）**：`POST .../modification/refund-proof`（保母上傳退款憑證）與 `POST .../modification/refund-confirm`（飼主確認收到退款）這兩支端點——SD-016 文件標注的「[新增]」端點——過去完全沒有 `@PreAuthorize`，身份也不是從 JWT（`TokenContext`）取得，而是直接信任前端傳來的 `sitterId`/`ownerId` query param，只驗證「這個 ID 是不是該訂單的當事人」，沒驗證「呼叫者是不是這個 ID 本人」。實際影響：該訂單的保母本來就合法看得到自己訂單的 `ownerId`，等於保母能自行呼叫 `refund-confirm` 冒充飼主完成退款確認，繞過飼主二次確認的把關意義。同檔案內其餘四支端點（propose/quote/reject/confirm）都正確用 `@PreAuthorize` + `TokenContext.getUserId()`，只有這兩支新增的端點是舊 pattern。已修復並補上 4 支迴歸測試（`OrderControllerTest` 內以「迴歸測試 (BOLA)」為前綴）。

三項修復後，全量後端測試 `mvn test` 219 個測試綠燈。
