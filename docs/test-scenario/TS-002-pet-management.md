# TS-002: 毛孩資料與注意事項管理測試情境

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-002 (毛孩注意事項) / PRD-010 (毛孩基本資料管理與 8 大物種) / NFR-006 (異動紀錄) |
| **測試類型** | ✅ 功能 (Functional) / ✅ 非功能 (Concurrency & Security) |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | `frontend/e2e/pet-management.spec.ts` |

---

## 一、 測試邏輯定義 (Given / When / Then)

* **Given (前置背景)：**
    * 資料庫已使用 Flyway 初始化 schema，存在 `pets` 和 `pet_edit_logs` 表，且 `pets` 表設有 partial index `idx_pets_owner`（過濾已刪除資料）。
    * 登入之飼主已存在，且名下已有一隻毛孩 `咪咪`（version = 1）。
    * 測試資料中已預埋一筆由保母 (SITTER) 修改該毛孩注意事項所產生的 `pet_edit_logs` 紀錄。
* **When (觸發事件)：**
    * 飼主編輯毛孩基本資料、大頭照上傳、新增毛孩、修改備註，或嘗試在樂觀鎖衝突與服務中刪除卡控等例外邊界情境下進行操作。
* **Then (預期行為)：**
    * 系統提供包含 8 個物種（貓咪、狗狗、鳥類、鼠類、兔子、爬蟲、昆蟲、其他）的選單，編輯成功時保存至資料庫並記錄 `pet_edit_logs`。
    * 當遭遇樂觀鎖衝突時彈出紅字覆蓋提示且保留使用者當前輸入；當毛孩有進行中服務時，拒絕刪除並彈出 Toast 警告。

---

## 二、 測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 驗證層 | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :---: | :--- |
| 1 | 進入毛孩資料管理頁面，選取毛孩「咪咪」，將體重填寫為 `4.85` 並儲存 | 頁面呈現「成功更新基本資料」Toast，列表中咪咪的體重更新。 | E2E <br><hr> 後端整合測試 | API 200 OK / 頁面更新為 4.85<br><hr> **DB 驗證**：`pets` 中該毛孩之 `weight` 改為 4.85，且 `version` 欄位遞增。 |
| 2 | 點擊頭像上傳，選取 `avatar.jpg` 檔案 | 頁面呈現「大頭照上傳成功」Toast，頭像即時更新為新照片。 | E2E <br><hr> 後端整合測試 | API 200 OK / 返回新 `photoUrl` / 頁面 `<img>` 的 `src` 更新成功。<br><hr> **GCS 驗證**：大頭照上傳至非訂單媒體的永久個人頭像目錄，由後端整合測試實作驗證。 |
| 3 | 點擊「新增毛孩」，輸入姓名 `哈利`、選取物種 `DOG`，體重 `12.0` 並儲存 | 頁面呈現「成功新增毛孩資料」Toast，清單中多出哈利。 | E2E <br><hr> 後端整合測試 | API 200 OK (或 201) / 頁面卡片增加一筆。<br><hr> **DB 驗證**：`pets` 寫入新記錄，`neutered` 預設為 false，`is_deleted` 為 false。 |
| 4 | 點擊咪咪的「編輯紀錄」歷史紀錄按鈕 | 彈出 Modal 展示異動紀錄，顯示異動時間、編輯者角色（如「保母」）及修改前後快照。 | E2E <br><hr> 後端整合測試 | API 200 OK / 返回 `pet_edit_logs` 列表 / UI 正確渲染歷史快照與角色識別。 |
| 5 | **模擬樂觀鎖衝突**：在備註區修改內容時，發送 version 錯誤之 PUT 請求 | UI 彈出紅字覆蓋提示，**且不關閉編輯表單**以防飼主辛苦填寫的文字丟失。 | E2E <br><hr> 後端整合測試 | **Mock 方式**：E2E 使用 `page.route` 攔截 `PUT **/api/pets/*` 並回傳 409 Conflict 錯誤。<br><hr> 後端拋出 `ObjectOptimisticLockingFailureException`，資料庫無任何寫入。 |
| 5b | **樂觀鎖衝突解決 (強制覆蓋)**：點擊彈出提示中的「強制覆蓋」按鈕 | UI 關閉提示，並帶上最新 version (version=2) 重新提交成功，表單關閉。 | E2E <br><hr> 後端整合測試 | **Mock 方式**：E2E 採用雙重 Mock：① 攔截強制覆蓋所觸發的 `GET **/api/pets/*` 並回傳 `version=2` 的最新毛孩資料；② 攔截後續隨之重新發送的 `PUT **/api/pets/*` 請求並回傳 200 OK。驗證 UI 提示關閉且呈現儲存成功 Toast。<br><hr> **DB 驗證**：強制寫入資料庫，`version` 遞增至 3，更新內容覆蓋舊資料。 |
| 6 | **模擬進行中服務刪除卡控**：嘗試刪除有進行中訂單的毛孩 | 頁面彈出警告 Toast：「此毛孩尚有進行中的服務，無法刪除」，刪除被阻擋。 | E2E <br><hr> 後端整合測試 | **Mock 方式**：E2E 使用 `page.route` 攔截 `DELETE **/api/pets/*` 並回傳 400 Bad Request。<br><hr> 後端 `PetService` 卡控並拋出違規 Exception / **DB 驗證**：毛孩 `is_deleted` 仍為 false。 |

---

## 三、 邊界條件 / 例外場景 (Edge Cases)

* **JPA 樂觀鎖 @Version 衝突**：
    * **情境**：飼主 A 與保母 B 同時打開咪咪的「注意事項」編輯介面。保母 B 先存檔，此時資料庫中咪咪的 `version` 由 1 變為 2。飼主 A 隨後存檔（攜帶的 version 仍為 1）。
    * **防禦**：後端 Hibernate 檢測到 version mismatch，自動回滾並拋出樂觀鎖異常，返回 409 錯誤代碼。
    * **前端保護**：前端捕獲 409 後，不在 UI 上直接清空或關閉表單，而是提供「強制覆蓋」按鈕。點擊強制覆蓋按鈕後，前端會先透過 GET 取得最新資料的 version 值，再以該 version 重新提交，達成強制覆蓋。
* **刪除安全卡控 (進行中訂單)**：
    * **情境**：飼主因毛孩調皮而賭氣嘗試刪除毛孩，但該毛孩在未來 3 天內有一筆已確認 (`CONFIRMED`) 或進行中 (`IN_PROGRESS`) 的照護訂單。
    * **防禦**：後端 `PetService.deletePet` 執行前，會查詢 `orderRepository` 是否存在該 `petId` 且狀態不為 `COMPLETED` 或 `CANCELLED` 的訂單。
    * **驗證**：若存在進行中服務，拒絕軟刪除，保持資料庫一致性，防堵訂單關聯損壞。

---

## 四、 復原步驟

* **資料清理**：
    * 每次測試前執行全域 `DatabaseCleanupListener`，清空 `pets`, `pet_edit_logs`, `orders` 等表，重新初始化乾淨測試資料。
