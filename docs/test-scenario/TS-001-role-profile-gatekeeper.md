# TS-001: 角色切換與預約門禁測試情境

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-011 (角色雙向切換) / PRD-012 (預約門禁 SaaS 鎖定) / NFR-006 (多租戶審計日誌) |
| **測試類型** | ✅ 功能 (Functional) / ✅ 非功能 (Security & Concurrency) |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | `frontend/e2e/gatekeeper-flow.spec.ts` |

---

## 一、 測試邏輯定義 (Given / When / Then)

* **Given (前置背景)：** 
    * 系統已使用 Flyway 初始化 schema，存在 `profiles`, `gatekeeper_rules` 與 `log_user_action` 實體表。
    * 資料庫存在預設使用者 `User` (email: `sitter@test.com`, role: `SITTER`)。
* **When (觸發事件)：** 
    * 使用者要求雙向角色切換，或於保母端設定/刪除全域與方案級預約門禁規則（黑/白名單及問卷免填）。
* **Then (預期行為)：** 
    * 角色切換時，自動懶載入（Lazy Initialization）建立對應的角色 Profile，並返回全新 JWT (claims 帶 activeRole 與 userId)。
    * 門禁規則遵循 SaaS Gating 鎖定（Free 鎖定，Pro 僅限黑名單，Ultimate 無限制），且同範圍內同對象黑白名單互斥防禦，關鍵操作均寫入 SLF4J 與多租戶審計日誌。

---

## 二、 測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 驗證層 | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :---: | :--- |
| 1 | POST `/api/auth/switch-role` 傳入目標角色 `CLIENT` | 從預設 SITTER 角色切換為 CLIENT，UI 更新為「愛貓飼主」介面。 | E2E <br><hr> 後端整合測試 | API 200 OK / 簽發全新 JWT 且 payload decode 驗證 `activeRole` 為 `CLIENT`<br><hr> **DB 驗證**：自動於 `profiles` 寫入一筆 `type='CLIENT'` 紀錄，且無重複產生。 |
| 2 | 切換回 `SITTER` 角色 | 切換回 SITTER 角色，UI 更新為「貓咪保母」介面。 | E2E <br><hr> 後端整合測試 | API 200 OK / 新 JWT payload decode 驗證 `activeRole` 為 `SITTER`<br><hr> **DB 驗證**：已存在的 profile 唯讀不重複寫入，`profiles` 數量維持 1 筆。 |
| 3 | 進入門禁設定頁面 (Sitter 方案為 FREE) | UI 顯示鎖定畫面：「解鎖預約門禁系統 (Gatekeeper)」，拒絕進入設定面板。 | E2E <br><hr> 後端整合測試 | 前端依據 `/subscription` 之 `planTier='FREE'` 卡控 UI 鎖定。<br><hr> 後端 Controller 攔截器作為防線，仍應回傳 403 Forbidden 錯誤。 |
| 4 | 模擬升級為專業版 (PRO) | 鎖定畫面消失，解鎖呈現「預約門禁管理系統」面板，方案顯示 PRO。 | E2E | API 200 OK / `/subscription` 回傳 planTier='PRO' / 門禁類型中「白名單」與「免填問卷」選項呈現 disabled 禁用。 |
| 5 | 模擬升級為頂級版 (ULTIMATE) | 面板全部解鎖，門禁類型「白名單」與「免填問卷」選項變為可用啟用狀態。 | E2E | API 200 OK / `/subscription` 回傳 planTier='ULTIMATE' / 所有規則種類均可選取。 |
| 6 | 對同一對象 `owner@test.com` 於 GLOBAL 範圍設定 `WHITE` 規則 (此時已有 `BLACK` 規則) | UI 彈出錯誤提示：「同一對象在同範圍內不能同時並存於黑名單與白名單中」，防止設定。 | E2E <br><hr> 後端整合測試 | API 400 Bad Request / 頁面渲染後端返回的互斥提示。<br><hr> **SLF4J Log**：後端拋出異常前印出 `[GatekeeperService] Mutual exclusion conflict: targetUserId ...` 警告。 |
| 7 | 設定 `GLOBAL` 或 `PLAN` (綁定特定方案) 規則 | 成功新增，規則清單多出一筆，Email 呈現遮蔽格式 (`ow***@test.com`)。 | E2E <br><hr> 後端整合測試 | API 201 Created / 透過對應的 GET `/api/sitter/gatekeeper` endpoint 反查清單確有此規則。<br><hr> **DB 驗證**：`gatekeeper_rules` 新增紀錄 / `log_user_action` 寫入 `CREATE` 審計 / **SLF4J Log**：印出 `Rule created successfully...`。 |
| 8 | 點擊刪除剛建立的門禁規則 | 成功刪除，清單數量減 1。 | E2E <br><hr> 後端整合測試 | API 204 No Content (或 200 OK) / 透過 GET endpoint 反查此規則已不存在於清單。<br><hr> **DB 驗證**：`gatekeeper_rules` 刪除該筆 / `log_user_action` 寫入 `DELETE` 審計 / **SLF4J Log**：印出 `Rule deleted successfully...`。 |
| 9 | 方案降級為 FREE | 頁面即刻重新被鎖定畫面遮罩，限制門禁功能使用。 | E2E <br><hr> 後端整合測試 | API 403 Forbidden / 前端重播 FREE 鎖定畫面。<br><hr> 驗證後端 API 返回 403 Forbidden，且 response body 含有代表方案受限之明確錯誤訊息與 code。 |

---

## 三、 邊界條件 / 例外場景 (Edge Cases)

* **高併發雙向切換 (Race Condition)**：
    * **情境**：兩個並發的 `switch-role` `SITTER` 請求同時抵達。
    * **防禦**：資料庫 `profiles` 表設有 `uidx_profiles_user_type` 唯一索引。其中一個請求會因重複寫入拋出 `DataIntegrityViolationException`。
    * **驗證**：`AuthService` 捕獲該 Exception 後，轉為重新查詢已建立的 Profile，達成冪等處理，確保兩個請求皆能成功返回 200 OK。*(註：E2E mock 無法測試真實併發 Race Condition，此情境由後端整合測試以 `CompletableFuture.allOf(...)` 多執行緒並發發送 switch-role 請求進行覆蓋驗證。)*
* **降級規則失效防禦**：
    * **情境**：保母在 ULTIMATE 方案時設定了白名單，隨後因未繳費降級為 FREE，此時白名單規則是否仍會阻擋飼主預約？
    * **驗證**：降級後，雖然規則仍存在 DB 中，但 `GatekeeperService.isBlocked(...)` 會先核對 Sitter 當前的訂閱方案等級。若非 PRO/ULTIMATE 或訂閱已過期，所有門禁規則自動失效並預設放行，避免造成合規保母正常預約被靜默卡死。

---

## 四、 復原步驟

* **資料清理**：
    * 每項測試執行前，利用全域 `DatabaseCleanupListener` 執行 `TRUNCATE ... CASCADE` 重設所有關聯表（`profiles`, `gatekeeper_rules`, `users`, `log_user_action`），確保無前案殘留。
