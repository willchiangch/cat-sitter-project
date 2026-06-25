# TS-014: 訊息中心與推播通知測試情境設計

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | [PRD-014-notification-center.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-014-notification-center.md) / [SD-014-notification-center.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-014-notification-center.md) |
| **測試類型** | ✅ 功能 (Functional) / ✅ 非功能 (Performance/Security/Resilience) |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | 後端 Service 測試：`NotificationServiceTest.java` <br> 後端 Controller 測試：`NotificationControllerTest.java`, `InternalCronControllerTest.java` |

---

## 測試情境一：標示已讀與 IDOR 安全防禦 (404 模糊化與時序攻擊防禦)

### 一、 測試邏輯定義 (Given / When / Then)

* **Given (前置背景)：** 
  * 系統資料庫中存在一筆屬於 `User A` 的通知紀錄 `noti_A`（ID：`uuid_A`），其讀取狀態為未讀 (`is_read = false`)。
  * `User B` 為合法的登入使用者。
* **When (觸發事件)：** 
  * `User B`（非擁有者）向 `POST /api/notifications/uuid_A/read` 發送標示已讀請求。
* **Then (預期行為)：** 
  * **模糊化防禦安全驗證**：系統必須回傳 `404 Not Found` (錯誤回應代碼：`MSG_DATA_F11`)，隱藏資料存在性，藉此防範攻擊者進行 ID 暴力枚舉與時序分析攻擊。
  * 資料庫中 `noti_A` 的 `is_read` 依然為 `false`，未讀數不變。

### 二、 測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | `User A` 標示自己的通知 `uuid_A` 為已讀 | 系統提示已讀成功，小鈴鐺未讀數減少 1 | API Status: 200 OK / DB: `notifications.is_read` 更新為 true，且 `read_at` 填入目前 UTC 時間 |
| 2 | `User B` 嘗試標示 `uuid_A` 為已讀 | 系統提示找不到該通知紀錄 (404) | API Status: 404 Not Found / 錯誤碼: `MSG_DATA_F11` / **IDOR防禦**：DB中 `is_read` 依然為 false |
| 3 | 傳入隨機不存在的 `uuid_random` 標示已讀 | 系統提示找不到該通知紀錄 (404) | API Status: 404 Not Found / 錯誤碼: `MSG_DATA_F11` (回應與步驟 2 完全一致，無法區分越權與不存在) |

---

## 測試情境二：新用戶查詢通知偏好的動態補齊

### 一、 測試邏輯定義 (Given / When / Then)

* **Given (前置背景)：** 
  * `User A` 為全新註冊用戶，資料庫 `notification_preferences` 表中尚無任何關於該使用者的偏好紀錄。
* **When (觸發事件)：** 
  * `User A` 發送 `GET /api/notifications/preferences` 查詢自己的通知偏好。
* **Then (預期行為)：** 
  * API 應回傳 200 OK，資料結構應自動取得 `NotificationPreferenceDefaults` 中定義之預設 Map（四類通知偏好均回傳），且 `SUBSCRIPTION_MAINTENANCE` 的 `enableEmail` 為 `false`，其餘皆為 `true`，資料庫本身無資料污染。

### 二、 測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | `User A` 發送 preferences 查詢 | 獲得所有 4 類通知偏好的預設開關配置 | API Status: 200 OK / 回傳 PreferenceDto 陣列長度為 4 / 預設值 `SUBSCRIPTION_MAINTENANCE.enableEmail` 必須為 false |
| 2 | 查詢資料庫偏好記錄 | 使用者在資料庫中此時依然無記錄 (動態補齊不應主動 insert 寫入資料庫造成資料污染) | DB: `SELECT COUNT(*) FROM notification_preferences WHERE user_id = userA` 結果為 0 |

---

## 測試情境三：偏好更新安全防護鎖 (ACCOUNT_AUTH 強制開啟防呆)

### 一、 測試邏輯定義 (Given / When / Then)

* **Given (前置背景)：** 
  * 使用者已登入，並在個人通知偏好設定頁。
* **When (觸發事件)：** 
  * 使用者嘗試發送 `PUT /api/notifications/preferences` 更新 `ACCOUNT_AUTH` 類別，將 `enableEmail` 或 `enableInApp` 改為 `false`。
* **Then (預期行為)：** 
  * 後端 API 攔截請求，拒絕寫入，並回傳 `400 Bad Request` (錯誤回應代碼：`MSG_DATA_INVALID_INPUT`)。
  * 同時資料庫層級的 CHECK 約束起作用，防止髒資料經由其他管道寫入資料庫（Defense-in-depth）。

### 二、 測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 更新 `SUBSCRIPTION_MAINTENANCE` 偏好為 `true/true` | 系統提示更新成功 | API Status: 200 OK / DB: 成功寫入/更新偏好設定紀錄 |
| 2 | 更新 `ACCOUNT_AUTH` 偏好為 `true/false` | 系統阻擋並顯示「安全與認證通知為系統核心功能，無法關閉」 | API Status: 400 Bad Request / 錯誤代碼: `MSG_DATA_INVALID_INPUT` |
| 3 | 繞過 API，手動以 SQL INSERT `ACCOUNT_AUTH` 且開關為 `false` | 資料庫拒絕寫入 | DB Error: 觸發 check constraint `chk_pref_account_auth_locked` 阻擋 |

---

## 測試情境四：90 天物理清理排程 (AOP 自我呼叫與 REQUIRES_NEW 驗證)

### 一、 測試邏輯定義 (Given / When / Then)

* **Given (前置背景)：** 
  * 資料庫中存在 10 筆建立時間超過 90 天的歷史通知，且存在 5 筆今天建立的新通知。
* **When (觸發事件)：** 
  * GCP Cloud Scheduler 攜帶安全密鑰呼叫 `POST /api/internal/cron/notifications/cleanup` 觸發物理清理任務。
* **Then (預期行為)：** 
  * 物理清理服務將大於 90 天的通知刪除，不使用邏輯刪除。
  * **AOP 事務驗證**：大入口方法不帶事務，內部的 batch delete 以獨立 Bean 注入呼叫且標註為 `REQUIRES_NEW`。這使得每一個 Batch (LIMIT 1000) 皆能**單獨 Commit 釋放行級鎖與資源**，允許其他交易能在 batch 之間插針執行。
  * 清理完成後，資料庫僅剩 5 筆新通知。

### 二、 測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 未帶 Secret Header 呼叫 `/cleanup` | 系統拒絕存取 | API Status: 401 Unauthorized |
| 2 | 攜帶正確 Secret Header 呼叫 `/cleanup` | 系統提示 Cleanup successfully completed，並回傳已刪除的筆數為 10 | API Status: 200 OK / 回傳 `deletedCount`: 10 / **物理刪除**：DB 總通知數從 15 降為 5 |
| 3 | 檢查剩餘的通知紀錄 | 剩餘的 5 筆通知均為 90 天內之新資料 | DB: `SELECT COUNT(*) FROM notifications` 結果為 5 / 所有資料的 `created_at` 均在 90 天內 |

---

## 測試情境五：訊息查詢之角色隔離 (Role Target)

### 一、 測試邏輯定義 (Given / When / Then)

* **Given (前置背景)：** 
  * 為同一個 `User A` 建立了 1 筆 `roleTarget = OWNER` 的通知、1 筆 `roleTarget = SITTER` 的通知、與 1 筆 `roleTarget = ALL` 的通知。
* **When (觸發事件)：** 
  * `User A` 切換至 `OWNER`（飼主）角色，向 `/api/notifications?role=OWNER` 發送分頁查詢。
* **Then (預期行為)：** 
  * API 僅回傳 2 筆通知（`OWNER` 與 `ALL`），絕對不能包含 `SITTER` 的通知，實現前端畫面的角色訊息隔離。

### 二、 測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 以 `role = OWNER` 查詢 notifications | 回傳 2 筆通知，內容包含 Owner 與 All 類別的通知 | API Status: 200 OK / `data.content` 長度為 2，且不包含 `roleTarget == 'SITTER'` |
| 2 | 以 `role = SITTER` 查詢 notifications | 回傳 2 筆通知，內容包含 Sitter 與 All 類別的通知 | API Status: 200 OK / `data.content` 長度為 2，且不包含 `roleTarget == 'OWNER'` |
| 3 | 不帶入 `role` 查詢 | 回傳所有屬於該使用者的通知，長度為 3 | API Status: 200 OK / `data.content` 長度為 3 |
