# TS-008: 服務執行與 Check-in 流程 測試情境定義

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | [PRD-008-service-execution.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-008-service-execution.md) / [SD-008-service-execution.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-008-service-execution.md) |
| **測試類型** | ✅ 功能 (Functional) / ✅ 非功能 (Security/Resilience) |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | [VisitReportControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/VisitReportControllerTest.java) / `frontend/e2e/service-execution.spec.ts` |

---

## 測試環境與種子資料準備

- **mock E2E 前置（2026-10，Backlog 第 4 批 E1）**：`frontend/e2e/service-execution.spec.ts` 以假 token 進 Demo，必須先呼叫 `e2e/support/mockSession.ts` 的 `mockDemoSession()` 攔 AppHeader 每頁都打的通知 / KYC 狀態 API。漏攔的 API 打到真後端會 401 → axios refresh 也 401 → 清掉 accessToken，下一次換頁被 `RequireAuth`（C1 起訂閱 location）導去 `/login?redirect=...`。這是測試 mock 不完整，不是產品 bug：真實使用者只有 refresh token 真的失效時才會被清 token 並導去登入。

為了在測試環境中執行驗證，需準備以下預填種子資料：
* **保母使用者**:
  - `id`: `3d498178-14c0-4376-b81e-7fb02e615dda`
  - `role`: `SITTER`
* **飼主使用者**:
  - `id`: `1031efbc-583a-4062-9a35-15706a3384c6`
  - `role`: `OWNER`
* **測試用訂單與行程**:
  - 訂單 `orderId`: `a1023000-0000-0000-0000-000000000000`，初始狀態為 `CONFIRMED`。
  - 行程 `visitId`: `2624511e-3f10-4376-b81e-7fb02e615dda`，初始狀態為 `PENDING`。

---

## TS-008-1: 保母 Check-in 與 Check-out 流程 (正常流)

### 一、 測試邏輯定義 (Given / When / Then)
* **Given (前置背景)**：
  - 訂單狀態為 `CONFIRMED`。
  - 行程狀態為 `PENDING`。
  - 操作者為訂單關聯的 `sitter` (保母)。
* **When (觸發事件)**：
  - 保母進入行程面板，點擊「開始服務 (Check-in)」。
  - 服務進行完畢後，點擊「完成服務 (Check-out)」。
* **Then (預期行為)**：
  - Check-in 後，行程狀態轉為 `IN_PROGRESS`，訂單狀態流轉為 `IN_PROGRESS`，寫入日誌並異步通知飼主。
  - Check-in 後，編輯日誌與多媒體上傳區解鎖可用。
  - Check-out 後，行程狀態轉為 `DONE`，寫入日誌並記錄完工時間。此時編輯區變為唯讀，只保留「正式送出日誌」按鈕。

### 二、 測試步驟與多維度驗證
| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1.1 | 載入行程面板，當前狀態為 `PENDING` | 僅顯示「🟢 開始服務 (Check-in)」大按鈕，日誌與媒體上傳區不顯示 | `visitStatus == 'PENDING'`<br/>`isEditable == false` |
| 1.2 | 保母點擊「🟢 開始服務 (Check-in)」 | 提示「已成功 Check-in」，面板切換為執行中模式 | API Status: 200 OK<br/>DB `visit.status` 轉為 `IN_PROGRESS`<br/>DB `order.status` 轉為 `IN_PROGRESS` |
| 2.1 | 進入執行中模式，編輯草稿與上傳照片 | 支援輸入文字日誌，並可成功暫存草稿與上傳照片 | `visitStatus == 'IN_PROGRESS'`<br/>`isEditable == true` |
| 2.2 | 保母點擊「🔴 完成服務 (Check-out)」 | 提示「已成功 Check-out」，行程狀態轉為 DONE，顯示確認彈窗並寫入 finishedAt | API Status: 200 OK<br/>DB `visit.status` 轉為 `DONE`<br/>`finished_at` 不為 null |
| 3.1 | 完工後，送出正式報告 | 日誌草稿與媒體區進入唯讀狀態，保母點擊「🚀 正式送出日誌」 | API Status: 200 OK<br/>DB 日誌狀態轉為 `SUBMITTED`<br/>內容轉為唯讀不可修改 |

---

## TS-008-2: 狀態機例外校驗 (異常流)

> [!NOTE]
> **Accepted Risk / 測試分層決策**：部分底層防禦（如 BOLA 403 越權與並發冪等控制）維持在後端整合測試層 ([VisitReportControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/VisitReportControllerTest.java)) 驗證，E2E 測試層不重複實作。這是一項刻意的測試分層設計。

### 一、 測試邏輯定義 (Given / When / Then)
* **Given (前置背景)**：
  - 行程狀態非 `PENDING` 時嘗試開始服務。
  - 行程狀態非 `IN_PROGRESS` 時嘗試結束服務。
* **When (觸發事件)**：
  - 保母發送 `POST /api/visits/{visitId}/start` 或 `end` 請求。
* **Then (預期行為)**：
  - 系統拒絕請求，回傳 422 Unprocessable Entity，並提示對應之錯誤訊息。

### 二、 測試步驟與多維度驗證
| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 對已處於 `IN_PROGRESS` 狀態之行程重複呼叫 `/start` | 提示「行程非待執行狀態，無法開始」 | API Status: 422 Unprocessable Entity<br/>Code: `MSG_DATA_INVALID_STATUS` |
| 2 | 對處於 `PENDING` 狀態之行程直接呼叫 `/end` | 提示「行程非執行中狀態，無法結束」 | API Status: 422 Unprocessable Entity<br/>Code: `MSG_DATA_INVALID_STATUS` |

---

## 本測試發現並修復的真實 bug

**行程清單不分狀態一律顯示「查看日誌」（2026-10-24，驗收 N11）**：飼主訂單詳情對「待執行」行程也顯示「查看日誌」，點進去只會看到「尚未回報」空狀態；保母訂單卡除了 `PENDING` 以外全部是「查看日誌」，執行中的行程看不出要點進去 Check-out，`CONFIRMED`（也是待執行）甚至不會顯示「打卡」。改依行程狀態給文案或不給入口（對照表見 SD-008 §4.3）。迴歸：`frontend/e2e/order-visit-gating.spec.ts` N11（飼主 / 保母各一支，五種狀態逐列比對入口文字；舊版兩支皆紅）。

**飼主點已完工但未送出的行程「查看日誌」落到空頁（2026-10-25，驗收 N11 殘留 / fix-batch12 U5）**：N11 後飼主端以行程 `DONE` 顯示「查看日誌」，但 N13 起 Check-out 後保母仍可繼續編輯草稿，日誌可能還沒送出；逾期的行程也一樣，點進去是「尚無照護回報」（t070-02）。`GET /api/orders/{id}/visits` 新增 `reportSubmitted`，飼主端只在 true 時給入口，否則顯示「日誌尚未送出」/「未送出日誌」。迴歸：`OrderControllerTest.should_FlagReportSubmitted_OnlyForSubmittedReports`（草稿 / 已刪除皆 false）、`order-visit-gating.spec.ts` N11 飼主詳情（舊版紅）。

**日誌回報頁成功訊息綠字配淺綠底對比不足（2026-10-25，驗收截圖新問題 8 / fix-batch12 U7）**：「已成功 Check-in」等訊息 `#10b981` 疊 `rgba(16,185,129,0.1)`，約 2.3:1。改 `#047857` / `#ecfdf5`（5.2:1），加 `data-testid="visit-report-success-msg"`。迴歸：`service-execution.spec.ts` Check-in 步驟量測（舊版紅）。

**日誌回報頁狀態徽章低對比（2026-10-26，fix-batch12 另外發現 #3 / fix-batch13 K10）**：`VisitReportManager`「執行中」`#d97706` 對 `#fef3c7`（2.86）、「待送出日誌」`#ea580c` 對 `#ffedd5`（3.11），影像到期提醒同為 2.86。改 `#92400e`（6.37）、`#9a3412`（6.38）。迴歸：`ui-layout-contrast.spec.ts` K10「執行中」徽章（舊版紅）。
