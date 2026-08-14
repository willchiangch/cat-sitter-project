# TS-JOURNEY-03: 服務執行到結案 + 通知驗證 (Service Execution to Completion)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-008/SD-008（服務執行）、PRD-021/022（照護日誌媒體）、PRD-014/SD-014（通知中心）、PRD-009/SD-009（訂單結案） |
| **測試類型** | ✅ 跨模組整合測試（真後端、真資料庫，非 mock） |
| **優先級** | P0（服務執行是訂單生命週期最核心的一段，且本次規劃時發現真實 UI 入口缺失） |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | `frontend/e2e/journeys/service-execution-and-completion.spec.ts` |

---

## 一、測試邏輯定義 (Given / When / Then)

* **Given**：一筆 `CONFIRMED` 訂單（`bootstrapConfirmedOrder` 純 API 重跑 Journey A+B 驗證過的機械部分兜出）。
* **When**：保母從訂單列表真實點入行程 → Check-in → 撰寫日誌+上傳照片 → Check-out → 送出報告；飼主從訂單詳情頁真實點入查看日誌；飼主手動確認結案。
* **Then**：訂單狀態依序 `CONFIRMED → IN_PROGRESS → COMPLETED`，過程中飼主收到正確分類的通知，且雙方都是透過**本次新補上的真實列表連結**（`GET /api/orders/{orderId}/visits`）點進行程頁面，不是繞過去直接打 URL。

**架構備註**：規劃本 journey 時發現 `SitterOrders.tsx`/`OwnerOrderDetail.tsx` 對 CONFIRMED 之後的訂單完全沒有連到行程頁面的入口（詳見 `docs/sd/SD-008-service-execution.md` 4.3 節），已在同一批一併補上後端端點與前端連結。本測試因此同時扮演這個新增功能的驗收測試。

---

## 二、測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 1 | UI：保母 `/sitter/orders` 切到「進行中」分頁 → 點行程清單裡的「打卡 / 回報」連結 | 導向 `/visit-reports/manage/{visitId}` | 連結來自 `GET /api/orders/{orderId}/visits`，不是寫死 URL |
| 2 | UI：點擊「開始服務 (Check-in)」 | 頁面切換到日誌編輯面板 | `POST /api/visits/{visitId}/start` 200，`Visit=IN_PROGRESS`、`Order=IN_PROGRESS` |
| 3 | API 輪詢：飼主通知列表 | 收到 `SERVICE_RECORD` 分類通知 | `VisitNotificationEvent` 為 `@Async`+`AFTER_COMMIT`，需要 poll 不能立即斷言（見 `awaitNotification`） |
| 4 | UI：填寫日誌文字 + 上傳一張照片（含說明文字） | 草稿與媒體都成功送出 | `PUT /visits/{visitId}/report` 200、`POST /visits/{visitId}/media` 200 |
| 5 | UI：點擊「結束服務」 | 行程完工 | `POST /api/visits/{visitId}/end` 200，`Visit=DONE`；**注意**：此按鈕背後有 `window.confirm()`，需要主動 accept 對話框，不然點了跟沒點一樣 |
| 6 | UI：點擊「送出報告」 | 報告正式送出 | `POST /visits/{visitId}/report/submit` 200，`report.status=SUBMITTED` |
| 7 | UI：飼主 `/owner/orders/{orderId}` 點行程清單裡的「查看日誌」連結 | 導向 `/visit-reports/view/{visitId}`，看得到文字與照片 | 連結同樣來自 `GET /api/orders/{orderId}/visits` |
| 8 | UI：飼主點擊「確認結案」 | 訂單正式結案 | `POST /api/orders/{orderId}/complete` 200，`Order=COMPLETED`；**注意**：此按鈕同樣有 `window.confirm()` |

---

## 三、邊界條件 / 例外場景

* **不驗證的點（刻意）**：自動結案排程 `CompletionService.triggerAutoCompletion()`（48 小時無異議自動結案）不發送任何通知事件，本 journey 走的是飼主主動結案路徑（`POST /api/orders/{orderId}/complete`），不會斷言「結案後收到通知」，避免寫出恆假的斷言。自動結案路徑已有專門的 `CompletionServiceTest` 覆蓋。
* **`window.confirm` 陷阱**：本頁面群（結束服務、確認結案）沿用 Journey B 已經踩過的坑，Playwright 預設自動 dismiss 對話框會讓 `if (!window.confirm(...)) return;` 提早結束、動作完全沒發生，測試前段就要註冊 `page.on('dialog', d => d.accept())`。
* **通知種類侷限**：本 journey 只驗證 Check-in 觸發的 `SERVICE_RECORD` 通知，沒有驗證 Check-out、送出報告、結案三個動作是否也各自觸發通知——這些若也需要驗證，屬於未來擴充範圍，目前只確認至少一條非同步通知鏈路真的打通。

---

## 四、附註 / 復原步驟

* **資料清理**：本測試建立的帳號、訂單、行程、日誌媒體皆落在 `@e2e-journey.test` 網域，由 CI 跑完後呼叫 `cleanup-e2e-journeys` 統一硬刪除（含 GCS 上的日誌照片物件）。
* **前置依賴**：`bootstrapConfirmedOrder` helper 重跑 Journey A（`bootstrapVerifiedSitter`）+ Journey B（建寵物、下單、快速接單、付款、核對入帳）的機械部分，若這兩條路徑本身失敗，這裡也會連帶失敗。
