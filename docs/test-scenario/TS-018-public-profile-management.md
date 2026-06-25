# TS-018: 保母公開檔案與標籤管理測試情境

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-018 (保母公開檔案) / PRD-017 (KYC) / NFR-005 (樂觀鎖併發) |
| **測試類型** | ✅ 功能 (Functional) / ✅ 非功能 (Concurrency/Security/Cache-Busting/Audit-Log) |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | 後端：[SitterPublicProfileControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/SitterPublicProfileControllerTest.java)<br>前端 E2E：[sitter-profile.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/sitter-profile.spec.ts) |

---

## 一、 測試邏輯定義 (Given / When / Then)

### 1. 編輯公開檔案與大頭貼 Cache-Busting
* **Given (前置背景)：** 保母 A 帳號已啟用，且資料庫中已有一筆預設公開檔案。
* **When (觸發事件)：** 
  1. 上傳一個 1.5MB 的 PNG 大頭貼。
  2. 修改暱稱為「愛貓保母阿香」、簡介為「專業貓咪照顧」，並新增服務區域與特點標籤。
  3. 送出儲存。
* **Then (預期行為)：** 
  * 儲存成功，後端回傳 200 OK。
  * `profiles` 表中 `display_name`、`bio` 已被正確更新，且 `avatar_url` 後綴帶有 `?v={timestamp}` 版本號。
  * `sitter_tags` 與 `sitter_service_areas` 已完成全量替換。

### 2. 敏感詞過濾防禦 (Security)
* **Given (前置背景)：** 系統管理員已將「皮下」設定為敏感詞庫 (`forbidden_keywords`)。
* **When (觸發事件)：** 保母填寫自我介紹為「提供皮下注射服務」並嘗試儲存。
* **Then (預期行為)：** 
  * 後端在進入 `@Transactional` 寫入事務前即刻攔截。
  * API 拒絕儲存並返回 `400 Bad Request`，錯誤識別碼為 `MSG_DATA_INVALID_INPUT`，業務提示「內容包含敏感詞彙」。

### 3. Gating 隱私卡控與黑名單模糊防禦 (Security)
* **Given (前置背景)：** 
  * 保母 A 將 `isVisible` 設定為 `false`，或是保母 A 的 KYC 狀態為 `SUSPENDED`，或訪客 B 在保母 A 的 `sitter_blacklists` 內。
* **When (觸發事件)：** 訪客 B 或匿名訪客嘗試存取該保母的公開預約頁面。
* **Then (預期行為)：** 
  * 後端不回傳 `403` 或 `404`，而是返回 `200 OK` 且 DTO 內 `status` 為 `HIDDEN`、`message` 為 `MSG_SITTER_RESTING`。
  * 前端渲染「保母休息中」溫馨提示頁，達到隱私卡控與模糊防禦目的。

### 4. 保母本人免 Gating 讀取與樂觀鎖 (Concurrency)
* **Given (前置背景)：** 
  * 保母 A 將自己的公開檔案設為 `isVisible = false`。
* **When (觸發事件)：** 保母 A 登入自己帳號，存取「公開檔案設定編輯」頁面。
* **Then (預期行為)：** 
  * 後端判定為保母本人，跳過 Gating 卡控，回傳完整的 profile 資料，包含正確的 `version`。
  * 保母再次編輯資料並儲存時，能正確提交 `version`，不會觸發樂觀鎖 409 衝突。

### 5. Admin 敏感詞管理與審計日誌 (Security & Audit)
* **Given (前置背景)：** 管理員已登入系統。
* **When (觸發事件)：** 
  1. 新增一個敏感詞「皮下」。
  2. 嘗試再次新增相同的敏感詞「皮下」。
  3. 查詢敏感詞清單，傳入篩選條件（page/size/q）。
  4. 刪除不存在的敏感詞 ID。
* **Then (預期行為)：** 
  * 首次新增成功，後端回傳 200 OK，且在 `log_user_action` 內以 `REQUIRES_NEW` 獨立子事務寫入 `ADMIN_FORBIDDEN_KEYWORD_ADD` 審計日誌。
  * 重複新增時，後端觸發唯一約束並回傳 `409 Conflict` (MSG_DATA_CONCURRENCY_CONFLICT)。
  * 分頁查詢成功，正確過濾 `q` 字串。
  * 刪除不存在的敏感詞時，後端回傳 `404 Not Found` (MSG_DATA_F11)。
  * 刪除成功時，寫入 `ADMIN_FORBIDDEN_KEYWORD_DELETE` 審計日誌。

### 6. KYC 停權與 is_open 強一致性連動
* **Given (前置背景)：** 保母 A 的 KYC 狀態為 `VERIFIED`，且公開檔案 `is_open = true` (開放預約)。
* **When (觸發事件)：** 管理員呼叫 KYC 停權 API 將保母 A 停權 (`SUSPENDED`)。
* **Then (預期行為)：** 
  * 在同一個寫入事務中，`profiles.is_open` 欄位立即被更新為 `false`。
  * 訪客存取該保母頁面時，觸發 Gating 條件，回傳 `HIDDEN` 狀態與 `MSG_SITTER_RESTING`。

### 7. 保母暫停營業 (isOpen = false) Gating 分支
* **Given (前置背景)：** 保母 A 將 `is_open` 設為 `false` (暫停接單)，但公開檔案 `isVisible` 仍為 `true`。
* **When (觸發事件)：** 訪客存取該保母的公開預約頁面。
* **Then (預期行為)：** 
  * API 正常返回 200 OK，包含完整 profile 欄位，但 DTO 內 `isOpen` 為 `false`。
  * 前端預約頁面能正常渲染基本資料與標籤，但底部的預約與下一步按鈕呈現置灰 (Disabled) 狀態。

---

## 二、 測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 呼叫 `POST /api/sitter/profile/avatar` 上傳 `1.5MB` 圖片 | 提示「頭像上傳成功」 | `200 OK`<br>GCS 檔案正確覆寫並回傳帶有時間戳的 `avatarUrl`。 |
| 2 | 呼叫 `POST /api/sitter/profile/avatar` 上傳 `3MB` 圖片 | 提示「頭像檔案大小不可超過 2MB」 | `400 Bad Request`<br>拒絕寫入 GCS。 |
| 3 | 保母新增標籤超過 10 個 | 畫面提示「最多僅能設定 10 個標籤」 | 前端提前攔截。<br>若繞過前端，API 回傳 `400 MSG_DATA_INVALID_INPUT`。 |
| 4 | 保母送出含有敏感詞的 profile | 提示「內容包含敏感詞彙」 | `400 Bad Request`<br>錯誤碼 `MSG_DATA_INVALID_INPUT`。<br>**審計軌跡**：無寫入 `profiles`。 |
| 5 | 保母同時點擊兩次「儲存」 | 第二次儲存提示「內容已被更新，請重新整理後再試」 | 第二個請求觸發 `OptimisticLockingFailureException`。<br>API 回傳 `409 Conflict`<br>錯誤碼 `MSG_DATA_CONCURRENCY_CONFLICT`。<br>**冪等性**：資料庫版本號僅增加 1。 |
| 6 | 匿名訪客訪問已關閉公開設定 (`isVisible=false`) 的保母 | 畫面顯示「保母休息中」提示 banner，不顯示預約按鈕 | `200 OK`，DTO 內容為 `status: HIDDEN` 且無敏感欄位與 `version`。 |
| 7 | 訪問被註銷 (Deleted) 的保母公開連結 | 顯示「找不到該保母資料或帳號已刪除」 | `404 Not Found`<br>錯誤碼 `MSG_DATA_F11`。 |
| 8 | 管理員新增敏感詞「皮下」 | 顯示「新增成功」 | `200 OK`<br>`forbidden_keywords` 資料表寫入一筆。<br>**獨立審計**：`log_user_action` 存在 `ADMIN_FORBIDDEN_KEYWORD_ADD` 日誌。 |
| 9 | 管理員重複新增敏感詞「皮下」 | 提示「此關鍵字已存在」 | `409 Conflict`<br>錯誤碼 `MSG_DATA_CONCURRENCY_CONFLICT`。 |
| 10 | 管理員分頁查詢敏感詞，帶入 `q=皮` | 清單正確過濾出「皮下」 | `200 OK`<br>分頁 DTO 內 `content` 包含「皮下」，其餘不符者被過濾。 |
| 11 | 管理員刪除不存在的敏感詞 ID | 提示「找不到該敏感關鍵字」 | `404 Not Found`<br>錯誤碼 `MSG_DATA_F11`。 |
| 12 | 管理員刪除已存在的敏感詞「皮下」 | 顯示「刪除成功」 | `200 OK`<br>`forbidden_keywords` 資料表刪除該筆資料。<br>**獨立審計**：`log_user_action` 存在 `ADMIN_FORBIDDEN_KEYWORD_DELETE` 日誌。 |
| 13 | 管理員停權保母 A (KYC 變更為 `SUSPENDED`) | 保母 A 後台顯示已被停權 | 停權 API 回傳 `200 OK`。<br>`profiles` 資料表 `is_open` 被置為 `false` 且 `kyc_status` 變為 `SUSPENDED`。 |
| 14 | 訪客存取 `is_open = false` 但公開的保母檔案 | 正常呈現保母基本檔案，但預約按鈕置灰 | `200 OK`<br>DTO 欄位完整，且 `isOpen` 欄位為 `false`。 |
| 15 | 已被保母列入黑名單的訪客 B 存取該保母公開連結 | 畫面顯示「保母休息中」提示 banner，不顯示預約按鈕 | `200 OK`<br>DTO 內容為 `status: HIDDEN`，且與 `isVisible=false` 的模糊化回應一致。 |

---

## 三、 邊界條件 / 例外場景 (Edge Cases)

* **併發編輯與 @Version 樂觀鎖：** 
  * 兩個管理請求使用同一個 `version` 併發發出，資料庫透過鎖機制保護，只有第一個成功 commit 且 `version` 遞增，第二個被攔截拋出樂觀鎖失敗，回傳 409。
* **Hibernate 順序陷阱 (複合唯一索引衝突)：**
  * 在全量覆蓋 Sitter Tags 時，Hibernate 可能預設把 `INSERT` 排在 `DELETE` 之前執行，進而引發 `uk_sitter_tag` 唯一約束衝突。
  * **防禦手段**：必須在 `deleteByProfileId(profile.getId())` 後手動呼叫 `flush()`，強迫 Hibernate 優先送出 DELETE，再送出 INSERT。
* **敏感詞大小寫/空格繞過：**
  * 詞庫有「皮下」，保母輸入「 皮 下 」或「皮-下」或「皮下 」。
  * **防禦手段**：比對前需先對自我介紹與暱稱字串執行 `Trim`、過濾標點符號與特殊空白後，再進行包含比對。
* **GCS 孤立圖片與無回滾補償策略：**
  * **邊界情境**：頭像上傳 GCS 成功，但隨後更新資料庫 `profiles.avatar_url` 時發生 409 衝突或資料庫斷線（回傳 500）。
  * **設計決策**：系統在此時不對 GCS 執行任何刪除或回滾操作。由於保母大頭貼採用固定格式路徑 `avatars/{sitterId}.{ext}` 覆寫且不帶雜湊，殘留於 GCS 的孤立圖片將在下一次保母成功更換頭像時自動被覆蓋更新，此為出於效能與系統簡潔性之刻意設計。

---

## 四、 附註 / 復原步驟

* **資料清理：** 
  * 每次執行自動化測試前後，使用種子腳本重置 `profiles`、`sitter_tags`、`sitter_service_areas` 以及 `forbidden_keywords` 資料表。
  * 清除上傳至模擬/本地 GCS `avatars` 資料夾下的臨時圖檔。
