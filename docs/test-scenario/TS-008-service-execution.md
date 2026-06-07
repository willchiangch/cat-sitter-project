# TS-008: 服務執行與 Check-in 流程 測試情境定義

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | [PRD-008-service-execution.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-008-service-execution.md) / [SD-008-service-execution.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-008-service-execution.md) |
| **測試類型** | ✅ 功能 (Functional) / ✅ 非功能 (Security/Resilience) |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | [VisitReportControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/VisitReportControllerTest.java) / `frontend/e2e/service-execution.spec.ts` |

---

## 測試環境與種子資料準備

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
