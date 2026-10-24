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

---

## 五、 本測試發現並修復的真實 bug

**匿名公開頁在背景以種子帳號自動登入（2026-10，驗收 X2 / Backlog 第 2 批 C1）**：`RoleContext` 掛載時只要不是 `authMode=manual`、也不在 `/login`/`/register`/`/forgot-password`/`/reset-password`，就會以 `sitter@test.com` 等種子帳號背景登入。匿名訪客開 `/sitter/{id}/profile`（真實對外分享的公開網址）因此被登入成種子保母，公開頁 API 帶著種子保母 token 打出；匿名開 `/booking/{id}` 被登入成種子保母後又被 `RequireRole` 踢回 `/demo`；登出後點通知信深連結也一樣。修法：自動登入只在明確的 Demo 模式發生（`seedAutoLogin.ts`，規則表見 SD-001「UX 設計」）；`RequireAuth` 導去登入時帶 `redirect` 回原頁。

| 驗證 | 預期 | 自動化 |
| :--- | :--- | :--- |
| 匿名開保母公開頁、點立即預約 | 0 次 `POST /api/auth/login`，公開頁 API 不帶 `Authorization`，`localStorage` 無 token | `e2e/seed-auto-login-scope.spec.ts` 測試 1；Journey A 步驟 8、Journey E 測試 1/2 的匿名步驟 |
| 匿名開 `/login`、`/register`、`/forgot-password` | 0 次登入請求 | 同上測試 2 |
| 匿名開 `/booking/{id}`、`/owner/orders/{id}` | 0 次登入請求，導到 `/login?redirect=<原路徑>` | 同上測試 3 |
| 開 `/demo`；之後在 Demo 模式深連結 `/sitter/ledger`；再開公開頁 | `/demo` 送出種子登入、`authMode=seed`；深連結沿用（再登入），帳務內容以種子 session 渲染、`accessToken` 仍在；公開頁 0 次 | 同上測試 4（2026-10-24 驗收 R3：以 `mockSessionApis` mock header API 與帳務 API，過去截圖是「載入失敗」且 token 已被 refresh 401 清掉） |

`order-eval.spec.ts`（SD-006 區塊）、`sitter-ledger.spec.ts` 每個測試都直接深連結進受保護頁面，改在 `beforeEach` 以 `addInitScript` 標記 `authMode=seed`（模擬「已從 Demo 入口進來過」）。

**Demo 種子登入撞 409 被導去 /login（2026-10，Backlog 第 3 批 D4 / 第 2 批另外發現 #1）**：種子帳號是所有 Demo 訪客共用的，後端每次登入都會刪掉並重建該帳號唯一一組 refresh token，同時有兩個登入請求（dev `<StrictMode>` effect 雙呼叫、別的分頁、mock E2E 的平行 worker）就會撞樂觀鎖回 409 `MSG_DATA_CONCURRENCY_CONFLICT`。前端拿不到 token 仍結束 loading，`RequireAuth` 把人導去 `/login`。實測 trace：失敗那次瀏覽器只送出 1 個登入請求、回 409，衝突來自其他 worker，所以光靠瀏覽器內去重不夠。修法：`loginAsRole()` 模組層級 in-flight 去重 + 409 重送（SD-001「UX 設計」）。本機 vite 下 `dispute-and-completion.spec.ts` + `sitter-profile.spec.ts` `--repeat-each=5`（3 workers）：修前 4 輪每輪 2~3 支因被導去 `/login` 失敗，修後 4 輪 0 支；剩下的失敗都是 `sitter-profile.spec.ts` 自己的平行副本互改同一筆種子保母公開檔案（409「內容已被更新」、gated 名稱），與登入無關（`--workers=1` 15/15 通過）。

| 驗證 | 預期 | 自動化 |
| :--- | :--- | :--- |
| 開 `/demo`，第一次登入回 409 | 重送後取得 token、停在 `/demo`，登入請求恰 2 次（無 StrictMode 並發） | `e2e/seed-auto-login-scope.spec.ts` D4 測試 |

**正式登入使用者在 `/demo` 切換角色被換成種子帳號（2026-10，Backlog 第 3 批 D3 / 第 2 批另外發現 #7）**：`RoleContext.setRole()` 不看 `authMode`，一律以 `sitter@test.com`/`owner@test.com`/`admin@test.com` 重新登入。`DemoHome` 在 `RequireAuth` 之內，真實使用者/管理員也看得到切換按鈕，點下去自己的 session 就被共用種子帳號取代，之後的操作都記在種子帳號名下。修法：`authMode=manual` 時對目前帳號呼叫 `POST /api/auth/switch-role`；管理員與保母/飼主之間不可互切（提示原因、維持原角色）；Demo 模式行為不變（SD-001 D3 說明）。

| 驗證 | 預期 | 自動化 |
| :--- | :--- | :--- |
| manual 飼主點「切換為保母」 | `switch-role` 1 次、body `{targetRole:"SITTER"}`、帶原 token；`POST /api/auth/login` 0 次；token 換成新 token、`authMode=manual`；畫面「貓咪保母」 | `e2e/manual-role-switch.spec.ts` 測試 1 |
| 接著點「切換為管理員」 | alert「此帳號沒有管理員權限」，角色、token 不變，無任何 API | 同上 |
| manual 管理員點「切換為保母」 | alert「管理員帳號沒有保母/飼主身分」，`switch-role`/`login` 皆 0 次 | 同上測試 2 |
