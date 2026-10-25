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
| 5 | UI：飼主 `/owner/orders/{orderId}` 點「查看並確認訂單變更」 | 導向確認頁，差額欄位顯示 `$600`（正值，不可為 `-600`） | 按鈕為本次新增；差額斷言整段錨定 `^\$\s?600\s*\(負值為退款\)$`（舊版 `toContainText('600')` 遇到 `$ -600` 也會通過，分不出補款/退款方向） |
| 6 | UI：確認變更 | 顯示「變更已確認」；同意表單消失、只剩「前往補款」，**沒有退款區塊** | `POST .../modification/confirm` 200，`Order=PENDING_PAYMENT`；`confirm-submit-btn`、`refund-confirm-section`、`refund-confirm-btn` 數量為 0（C2） |
| 6b | UI：保母直接打網址重新進入報價頁 | 唯讀摘要「應補款 $ 600」，無報價、拒絕、退款憑證區塊 | `quote-submit-btn`、`reject-mod-btn`、`refund-proof-section` 數量為 0（C2） |
| 6c | API：飼主在補款階段查詢訂單並直接打撤單 `POST /api/orders/{id}/cancel {version}`（驗收 A3，fix-batch12） | 補款階段不可撤單，需走 PRD-016 協商 | `GET /api/orders/{id}` 的 `cancellableByOwner=false`；撤單回 **409**，`message` 含「補款階段」與「PRD-016」；訂單仍 `PENDING_PAYMENT`（`OwnerCancellationService` 的 `SupplementPaymentResolver` 排除，過去只有 JUnit） |
| 7 | UI：飼主上傳補款憑證（沿用 SD-007 既有表單） | 付款頁顯示「本次應補差額 $600」（`payment-amount-due`），總額列顯示「變更後訂單總額 $1,200」；**沒有「取消預約」鈕**（`owner-cancel-booking-btn` 數量 0）；憑證送出成功 | `GET /api/orders/{id}` 的 `amountDue=600`、`totalAmount=1200`；`POST .../payment-proof` 200，`Order=PAID` |
| 8 | UI：保母核對入帳 | 核對面板顯示「本次應收補款差額 $600」（差額 `verify-amount-due-value` 完全比對 `$ 600`，整段 `verify-amount-due` 以 `/^本次應收補款差額：\s*\$ 600\s*（變更後總額 \$ 1,200）$/` 錨定；2026-10 前用 `toContainText('$ 600')`，`$ 6000` 也會過，驗收 S2）、**補款**憑證後五碼 `54321`（`verify-last-five`，不是首次付款的 `12345`）與補款憑證預覽（簽名網址，SD-007 §4.7）；訂單回到發起變更前的狀態 | `GET /api/orders/sitter` 的 `amountDue=600`；`payment-proof-preview` 的 `src` 帶 `X-Goog-Signature` 且 `naturalWidth>0`；`POST .../verify-payment` 200，`Order=CONFIRMED`（變更是在 CONFIRMED 時發起，SD-016 §4.3）；核帳後 `paymentProofLastFive` 回到首次付款 `12345`、`paidAt` 與前置步驟記下的首次核帳時間相同、`amountDue=1200`（這組值以 `apiStep`「核帳後首次付款資料未被補款覆寫」留 JSON，驗收 S2）；補款憑證預覽 `apiStep` 留遮簽名的網址 JSON，且不帶簽名 GET 該物件斷言 403（驗收 S1/S3） |
| 9 | UI：結清後雙方直接打網址重新進入兩個協商頁 | 兩頁都以中性樣式顯示「目前沒有進行中的變更協商」，沒有任何可操作按鈕 | `GET .../modification` 204；`mod-quote-empty`/`mod-confirm-empty` 可見、`mod-*-error` 數量 0（2026-10-24 驗收 N8 前為紅字 error + 409）；`quote-submit-btn`、`reject-mod-btn`、`refund-proof-submit-btn`、`confirm-submit-btn`、`refund-confirm-btn` 數量為 0（C2） |

### 退款分支

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 1-2 | 同上，改申請「減少天數」 | `Order=MODIFYING` | 原始訂單 2 天，改為 1 天 |
| 3-4 | 保母接受預填總額（換算後較低） | `ModificationRequest=QUOTED`，`diffAmount=-600`；報價表單消失，飼主同意前沒有退款憑證區塊 | `quote-submit-btn`、`refund-proof-section` 數量為 0（C2） |
| 5-6 | 飼主確認，差額欄位顯示 `-$600` | `Order=REFUND_VERIFY`；同意表單消失；保母尚未上傳退款憑證，顯示等待且沒有「確認已收到退款」 | `confirm-submit-btn`、`refund-confirm-btn` 數量為 0，`refund-awaiting-proof` 可見（C2） |
| 7 | UI：保母**重新**點「處理訂單變更協商」進入同一頁 | 頁面正常顯示（不 404），報價表單與拒絕已不存在；選擇退款截圖上傳（不再有手填網址欄位），送出後表單消失並顯示簽名網址預覽（`apiStep` 留遮簽名網址 JSON、不帶簽名 GET 斷言 403，驗收 S1/S3） | **迴歸測試重點**：`getActiveModificationRequest` 過去只認 `PENDING_REVIEW`/`QUOTED`，飼主確認後 `ModificationRequest` 已轉終態 `M_DONE`，這裡若沒修好會顯示 `mod-quote-error` 進不去表單。`POST .../modification/refund-proof`（multipart）200；`refund-proof-preview` 的 `src` 為 `refund-proofs/` 簽名網址且 `naturalWidth>0`（C4） |
| 8 | UI：飼主**重新**點「查看並確認訂單變更」→ 檢視退款憑證預覽 → 點「確認已收到退款」 | 退款憑證以圖片顯示（簽名網址、無外部連結；同步驟 7 留 JSON 並斷言不帶簽名 403）；顯示「已確認收到退款」，按鈕消失 | `POST .../modification/refund-confirm` 200，`Order=CONFIRMED`（本測試訂單非整筆取消，items 非空；變更在 CONFIRMED 發起，依 `previous_status` 復原，SD-016 §2.7） |
| 9 | 同補款分支步驟 9 | 兩頁皆無任何可操作按鈕 | 同上 |

---

## 三、邊界條件 / 例外場景

* **前端方案有效期間為寫死 mock 常數**：`OrderModificationWizard.tsx` 的 `planStart`/`planEnd` 目前是寫死字串 `2026-05-01`/`2026-05-31`，跟訂單實際日期或方案真實有效期無關，純粹是前端輸入防呆。測試因此故意把變更後日期填在這個區間內（跟現有 mock 版單模組測試 `order-modification.spec.ts`同慣例），不代表這是有意義的業務日期——若未來要把這個常數換成串接真實方案資料，屬於獨立的產品待辦，非本次修復範圍。
* **Zero-Trust 對帳**：`confirmModification` 後端會重新核對 `agreedDiffAmount` 與 `ModificationRequest.diffAmount` 是否一致；`OwnerModificationConfirm.tsx` 的輸入框預設值就是後端算好的最新差額，本測試直接接受預填值送出，沒有特別測「篡改差額」這個防線——該防線已有 `ModificationServiceTest.should_RejectConfirm_When_AgreedAmountMismatchesQuote` 單元測試覆蓋。
* **不驗證的點（刻意）**：`ModificationService` 全程沒有發送任何通知事件（非 Check-in/付款那類有 `eventPublisher` 的模組），本 journey 不斷言任何通知內容。
* **既有技術債（非本次修復範圍）**：SD-016 文件本身已註記 `propose/quote/reject/confirm` 四支端點的 `Idempotency-Key` 只在 Controller 層強制必填，`ModificationService` 內部沒有真的做去重（見 SD-016 §5 WARNING）；另外 `OrderModificationWizard.tsx` 呼叫 `modifyOrder()` 時 `requestedBy` 參數寫死 `'OWNER'`，若保母透過同一個頁面發起變更會被後端拒絕（`callerIsOwner=false` 但 `requestedBy='OWNER'`），本 journey 只測飼主發起的路徑，這個保母發起路徑的斷路屬已知缺陷，留給後續處理。

---

## 四、附註 / 復原步驟

* **資料清理**：本測試建立的帳號、訂單、變更請求皆落在 `@e2e-journey.test` 網域，由 CI 跑完後呼叫 `cleanup-e2e-journeys` 統一硬刪除。
* **對話框斷言**：協商三頁的成功/失敗都用 `alert()` 呈現（例如「變更申請已成功提交！」「送出報價失敗，請檢查權限或參數。」），退款分支每個送出動作都以 `expectDialog` 斷言成功字樣，避免 `alert('…失敗')` 被默默 accept 仍判定通過。
* **前置依賴**：`bootstrapConfirmedOrder` helper 重跑 Journey A+B 的機械部分；退款分支額外傳入 `datesOverride` 參數兜出 2 天的原始訂單。
* **Cloud Run 冷啟動（環境因素，非產品 bug）**：正式站沒設 min-instances，縮到 0 後 Spring Boot 冷啟要 33~40 秒。本機多 worker 同時打第一發 `provision-account` 時，超出可用 instance 的 request 會被 Google Frontend 直接 abort 成 `429 Rate exceeded.`（2026-10-23 本機 5 workers 實測，退款分支因此在前置步驟失敗）。處理方式：`helpers.ts` 的 `provisionAndLogin`/`loginAsSeedAdmin` 只對 **body 不是 JSON** 的 429/503（GFE 的 `Rate exceeded.` 純文字/HTML，request 沒進 container，重送安全）指數退避重試；後端自己回的 JSON 429（`ACCOUNT_LOCKED`、`OTP_LOCKED` 等真實業務結果）一律原樣回傳不重試，避免多等 21 秒還把鎖定原因藏起來；本機 `playwright.config.ts` workers 上限 3；`e2e-runner` 本機模式先暖機 `/actuator/health` 再以 `--workers=2` 執行。

---

## 五、本測試發現並修復的真實 bug

規劃本 journey、盤查三個協商頁面（`OrderModificationWizard`/`SitterModificationQuote`/`OwnerModificationConfirm`）時，發現這整個模組雖然功能本身可用（API 都通、單元測試都綠），但**真實使用者事實上完全到不了這些頁面**，額外還挖到一個安全性問題：

1. **三個頁面完全沒有真實入口**：`SitterOrders.tsx`/`OwnerOrders.tsx`/`OwnerOrderDetail.tsx` 過去沒有任何按鈕連到 `/orders/:id/modify`、`/sitter/orders/:id/quote`、`/owner/orders/:id/modification-confirm`，只有 `DemoHome.tsx` 用寫死的假 orderId 展示連結。真實訂單一旦要走變更協商，飼主/保母完全無法從列表或詳情頁點進去——這跟 Journey C 抓到的行程入口缺口是同一類問題。已在 `OwnerOrderDetail.tsx`（CONFIRMED/IN_PROGRESS 顯示「申請訂單變更」、MODIFYING/REFUND_VERIFY 顯示「查看並確認訂單變更」）與 `SitterOrders.tsx`（CONFIRMED/IN_PROGRESS 顯示「申請訂單變更」、MODIFYING/REFUND_VERIFY 顯示「處理訂單變更協商」）補上對應按鈕。

2. **退款憑證上傳頁面在真正需要時會 404**：`getActiveModificationRequest()` 過去只認 `ModificationRequest.status ∈ {PENDING_REVIEW, QUOTED}`。但退款流程恰好是「飼主先確認、保母才需要上傳退款憑證」，飼主確認後 `ModificationRequest.status` 已轉終態 `M_DONE`，保母端頁面只要重新整理或重新導航就查無資料——實務上保母永遠上傳不了退款憑證。詳見 SD-016 §2.2、`docs/sd/SD-016-order-modification-cancellation.md`。已修法：訂單處於 `MODIFYING`/`PENDING_PAYMENT`/`REFUND_VERIFY` 這三個協商相關狀態時，改撈該訂單最新一筆變更請求，不限 `status`。

3. **BOLA（Broken Object Level Authorization）**：`POST .../modification/refund-proof`（保母上傳退款憑證）與 `POST .../modification/refund-confirm`（飼主確認收到退款）這兩支端點——SD-016 文件標注的「[新增]」端點——過去完全沒有 `@PreAuthorize`，身份也不是從 JWT（`TokenContext`）取得，而是直接信任前端傳來的 `sitterId`/`ownerId` query param，只驗證「這個 ID 是不是該訂單的當事人」，沒驗證「呼叫者是不是這個 ID 本人」。實際影響：該訂單的保母本來就合法看得到自己訂單的 `ownerId`，等於保母能自行呼叫 `refund-confirm` 冒充飼主完成退款確認，繞過飼主二次確認的把關意義。同檔案內其餘四支端點（propose/quote/reject/confirm）都正確用 `@PreAuthorize` + `TokenContext.getUserId()`，只有這兩支新增的端點是舊 pattern。已修復並補上 4 支迴歸測試（`OrderControllerTest` 內以「迴歸測試 (BOLA)」為前綴）。

三項修復後，全量後端測試 `mvn test` 219 個測試綠燈。

4. **補款頁顯示新總額而不是應補差額（2026-10，驗收 P8）**：`confirmModification()` 會把 `Order.totalAmount` 直接改成變更後新總額（例：$600 → $1,200）再轉 `PENDING_PAYMENT`，而 `GET /api/orders/{id}` / `GET /api/orders/sitter` 過去只回 `totalAmount`，系統裡根本沒有「這次要付多少」的欄位。結果 `OwnerOrderDetail` 補款頁顯示「訂單金額 $1,200」，飼主照著匯就會多匯一倍；保母核對入帳時看到的也是 $1,200，收到 $600 反而會以為短付而駁回。PRD-016 主流程 C.3 寫明「應補款：等待飼主支付**差額**」，所以這是後端缺欄位 + 前端顯示錯欄位兩層問題。修法：兩個 DTO 新增 `amountDue`（`OrderQueryServiceImpl.resolveAmountDue`：訂單處於 `PENDING_PAYMENT`/`PAID` 且最新一筆變更請求為 `M_DONE`、`diffAmount > 0` 時回差額，其餘回 `totalAmount`）；飼主付款頁顯示「本次應補差額」區塊、總額列改標「變更後訂單總額」，保母核對面板顯示「本次應收補款差額」。迴歸測試：`OrderQueryServiceListTest` 三支（補款回差額、首次付款回總額、退款/已結清不誤判），以及本 journey 步驟 7、8 的 UI 斷言。

5. **付款憑證公開可讀（2026-10，安全/個資）**：步驟 8 的補款憑證（與首次付款憑證同一機制）過去以 `allUsers READER` 上傳、DTO 直接回 `storage.googleapis.com` 永久公開網址。改為私有物件 + 10 分鐘簽名網址，只有訂單飼主/保母能取得（SD-007 §4.7）。迴歸：步驟 8 斷言預覽為簽名網址且真的載入；後端 `PaymentControllerTest`。退款分支的「退款憑證」當時是保母手填的外部網址字串（不經本系統上傳），已於第 8 點修正。

6. **補款核帳後狀態寫死 `CONFIRMED`、補款覆寫首次付款紀錄（2026-10）**：`verifyPayment()` 不分首次或補款一律回 `CONFIRMED` 並覆寫 `paidAt`；補款上傳直接覆寫 `orders` 上首次付款的憑證/後五碼/冪等鍵/免責時間。改為補款紀錄存 `modification_requests.supplement_*`、核帳回 `previous_status`（SD-016 §4.3）。本 journey 步驟 8 斷言核對面板顯示補款後五碼、核帳後首次付款後五碼與 `paidAt` 未變；`IN_PROGRESS` 發起的分支因 journey 訂單日期在明天無法打卡，只由 `PaymentControllerTest` 覆蓋（見 TS-016 Scenario 1b）。

7. **協商頁沒有依狀態收斂按鈕（2026-10，驗收 P7 / Backlog 第 2 批 C2）**：前一輪驗收的退款分支截圖 13 看到「退款憑證已上傳」時報價表單仍可操作。兩個協商頁原本不看變更請求狀態，所有表單永遠同時渲染；補款分支也出現退款區塊；後端退款憑證端點沒有狀態防護。修法見 SD-016 §2.2（`orderStatus` + 階段表）、§2.5。本 journey 補款步驟 6/6b/9、退款步驟 3-4/5-6/7/8/9 都斷言「已完成的動作按鈕數量為 0」；後端由 `OrderControllerTest` 六支 C2 測試覆蓋。

8. **退款憑證是保母手填的任意外部網址（2026-10，Backlog 第 2 批 C4，安全）**：本 journey 退款步驟 7 過去填的是 `https://storage.googleapis.com/e2e-journey-test/refund-proof.jpg`——任何字串都會被接受、原樣回給飼主端。改為上傳圖片到私有物件 + 簽名網址（SD-016 §2.5/§2.5.1）；步驟 7 改用 `fixtures/report-photo.jpg` 上傳，步驟 7、8 以 `expectSignedRefundProof` 斷言雙方預覽都是 `refund-proofs/` 簽名網址且真的載入，飼主退款區塊內沒有 `<a>`。
