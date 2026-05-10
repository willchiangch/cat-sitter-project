# TS-XXX: [情境名稱]

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-XXX / NFR-XXX |
| **測試類型** | ⬜ 功能 (Functional) / ✅ 非功能 (Performance/Security/Resilience) |
| **自動化狀態** | ⬜ 未自動化 / ✅ 已自動化 |
| **自動化路徑** | `tests/e2e/xxx.spec.ts` |

---

## 一、 測試邏輯定義 (Given / When / Then)

**思維：定義測試發生的背景與預期契約。**

* **Given (前置背景)：** * [例如：系統處於 2,000 VUs 的 Constant Load 壓力下]
    * [例如：DB 已存在該租戶的 1,000 萬筆檢索紀錄]
* **When (觸發事件)：** * [例如：使用者在 1 秒內連續點擊 3 次「送出檢索」]
* **Then (預期行為)：** * [例如：系統僅執行 1 次檢索，其餘請求回傳「處理中」，且 API 延遲 < 500ms]

---

## 二、 測試步驟與多維度驗證

**思維：除了 UI，更要驗證 DB 狀態、Log 軌跡與性能指標。**

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | [動作描述] | [UI 呈現或業務流程結果] | [API Latency / Status Code / DB State] |
| 2 | [重複/併發觸發] | [防重機制提示或特定 Error] | [**冪等性驗證**：DB 僅產生單筆變動] |
| 3 | [模擬相依服務中斷] | [觸發優雅降級或 Fallback 介面] | [Circuit Breaker 紀錄 / Correlation-ID 追蹤] |

---

## 三、 邊界條件 / 例外場景 (Edge Cases)

**思維：針對極端狀況與安全性進行破壞性測試。**

* **併發陷阱：** 模擬不同使用者同時更新同一筆資料，驗證樂觀鎖 (Optimistic Locking) 機制。
* **資料污染：** 傳入包含 SQL Injection 或 XSS 標籤的 Payload，驗證防禦攔截。
* **資源極限：** 當 GCP Cloud Run 到達 Auto-scaling 上限時，驗證系統是否仍能回傳正確的 429 或 503 錯誤而非直接掛掉。

---

## 四、 附註 / 復原步驟

* **資料清理：** 測試完成後需清理 Redis/Valkey 緩存或刪除測試資料夾中的 Dummy Data。
* **環境相依：** 確保 GCP IAM 權限與 Production 環境一致，避免環境差異造成的無效測試。