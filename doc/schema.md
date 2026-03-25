# 寵物到府保母系統 (SaaS) - 核心資料庫 Schema 規格書 (V6)

## 全域稽核欄位 (Audit Columns) 約定
為保持表格簡潔，以下 13 張資料表**皆預設包含**以下 4 個標準稽核欄位（實作時由 Spring Data JPA 框架自動生成與維護）：

* `created_by` (VARCHAR)：建立者 ID 或系統標識
* `created_at` (TIMESTAMPTZ)：資料建立之精準時間
* `updated_by` (VARCHAR)：最後修改者 ID 或系統標識
* `updated_at` (TIMESTAMPTZ)：資料最後修改之精準時間

---

## 🏛️ 領域一：帳號與身分核心 (Identity & Access)

### 1. `accounts` (系統登入帳號表)
處理底層認證，支援未來擴充第三方登入。

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 系統底層唯一識別碼 |
| `email` | VARCHAR (UNIQUE) | 登入與主要聯絡信箱 |
| `password_hash` | VARCHAR | 密碼 (若走 OAuth 第三方登入則允許為空) |
| `oauth_provider` | VARCHAR | **[狀態]** 登入來源：<br>`LOCAL` (本地密碼), `GOOGLE`, `LINE`, `APPLE` |
| `oauth_id` | VARCHAR | 第三方平台回傳的唯一 ID (勾稽用) |
| `status` | VARCHAR | **[狀態]** 帳號總狀態：<br>`ACTIVE` (正常啟用), `SUSPENDED` (停權/封鎖) |

> **Constraints**：`UNIQUE (oauth_provider, oauth_id)` — 防止同一第三方帳號重複綁定

### 2. `profiles` (角色檔案表)
同一個帳號可切換不同身分，資料互不干擾。訂單與寵物皆綁定此表。

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 身分唯一識別碼 |
| `account_id` | UUID (FK) | 對應至 `accounts` |
| `role_type` | VARCHAR | **[狀態]** 角色定義：<br>`SITTER` (保母), `CLIENT` (飼主) |
| `name` | VARCHAR | 顯示名稱 / 品牌名稱 |
| `avatar_url` | VARCHAR | 存放於 GCS 的頭像或 Logo 網址 |
| `phone` | VARCHAR | 聯絡電話 |
| `address` | VARCHAR | 實際居住或通訊地址 |
| `service_areas` | JSONB | **[SITTER 專用]** 服務區域 (如 `["新莊區", "板橋區"]`) |
| `bio_summary` | TEXT | **[SITTER 專用]** 專業履歷與自介 |
| `refusal_criteria`| TEXT | **[SITTER 專用]** 拒接條件聲明 |
| `booking_open_start` | DATE | **[SITTER 專用]** 開放預約起始日 |
| `booking_open_end` | DATE | **[SITTER 專用]** 開放預約結束日 |

> **Constraints**：`UNIQUE (account_id, role_type)` — 同帳號同角色只能有一個 profile

---

## 🐾 領域二：業務設定與毛孩檔案 (Business & Subjects)

### 3. `services` (服務方案表)
保母的生財工具，支援動態調價與檔期限制。

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 方案 ID |
| `sitter_profile_id` | UUID (FK) | 屬於哪位保母 |
| `name` | VARCHAR | 方案名稱 (如：單次到府 45 分鐘) |
| `base_price` | DECIMAL | 基礎定價 |
| `duration_minutes`| INT | 預計服務時長 |
| `supported_pet_types`| JSONB | 適用物種標籤 (如 `["CAT", "DOG"]`) |
| `bookable_start_date`| DATE | 方案有效起日 (常態方案為空) |
| `bookable_end_date`| DATE | 方案有效迄日 (常態方案為空) |
| `advance_booking_days`| INT | 需提前預訂天數限制 (防堵急單) |
| `sort_order` | INT | 保母端方案顯示排序 (預設 0) |
| `is_active` | BOOLEAN | 是否上架開放預約 |

### 4. `sitter_questions` (保母自訂題庫表)
支援依據寵物種類發送不同客製化問卷。

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 題目 ID |
| `sitter_profile_id` | UUID (FK) | 出題的保母 |
| `target_pet_type` | VARCHAR | **[狀態]** 針對哪種寵物發問：<br>`CAT`, `DOG`, `BIRD`, `REPTILE`, `ALL` (通用) |
| `question_text` | VARCHAR | 開放式問題內容 |
| `sort_order` | INT | 表單顯示排序 |
| `is_active` | BOOLEAN | 是否啟用此問題 |

### 5. `pets` (服務注意事項表)

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 毛孩 ID |
| `client_profile_id` | UUID (FK) | 屬於哪位飼主 |
| `name` | VARCHAR | 寵物名字 |
| `species` | VARCHAR | **[狀態]** 物種：<br>`CAT`, `DOG`, `BIRD`, `REPTILE`, `OTHER` |
| `gender` | VARCHAR | 性別：`MALE`, `FEMALE`, `UNKNOWN` |
| `is_neutered` | BOOLEAN | 是否已結紮 |
| `weight_kg` | NUMERIC(5,2) | 體重 (公斤) |
| `avatar_url` | VARCHAR | 存放於 GCS 的大頭貼網址 |
| `medical_notes` | TEXT | 醫療史與過敏藥物 |
| `dietary_notes` | TEXT | 飲食偏好與餵食方式 |
| `personality_notes`| TEXT | 個性雷達與行為特徵 |
| `other_notes` | TEXT | 其他奇特雜項備註 |

---

## 🤝 領域三：社交與信任圈 (Social & Trust Network)

### 6. `client_favorite_sitters` (飼主最愛保母表)

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 關聯 ID |
| `client_profile_id` | UUID (FK) | 收藏者 (飼主) |
| `sitter_profile_id` | UUID (FK) | 被收藏者 (保母) |
| `is_favorite` | BOOLEAN | `true` (顯示於最愛), `false` (隱藏/不再合作) |

> **Constraints**：`UNIQUE (client_profile_id, sitter_profile_id)`

### 7. `sitter_trust_circles` (保母信任圈表)

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 關聯 ID |
| `owner_sitter_id` | UUID (FK) | 發起信任的保母 |
| `trusted_sitter_id` | UUID (FK) | 被加入信任圈的同行 |
| `status` | VARCHAR | **[狀態]** 合作關係：<br>`ACTIVE` (正常合作), `BLOCKED` (封鎖/終止合作) |

> **Constraints**：`UNIQUE (owner_sitter_id, trusted_sitter_id)`

---

## 📅 領域四：交易、排程與執行 (Orders & Execution)

### 8. `orders` (訂單主表)
財務結帳與交易狀態的唯一真理來源。

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 訂單 ID |
| `client_profile_id` | UUID (FK) | 買方 (飼主) |
| `current_sitter_id` | UUID (FK) | 實際承接保母 (若轉單成功會更新為接手者) |
| `service_id` | UUID (FK) | 購買的方案 (歷史查詢用，勿作為方案資訊來源) |
| `service_name` | VARCHAR | **[快照]** 訂單建立時的方案名稱 |
| `service_unit_price`| NUMERIC(10,2) | **[快照]** 訂單建立時的方案單價 |
| `base_amount` | DECIMAL | 方案原始總價 (次數 x 方案單價) |
| `surcharge_amount`| DECIMAL | 加給總額 (如跨區車馬費) |
| `discount_amount` | DECIMAL | 折扣總額 |
| `total_amount` | DECIMAL | 最終結帳總金額 |
| `pricing_notes` | TEXT | 價格計算備註 (如：車馬費+300) |
| `order_status` | VARCHAR | **[狀態]** 訂單進度：<br>`PENDING` (待確認), `CONFIRMED` (已成立), `COMPLETED` (已結案), `CANCELLED` (已取消) |
| `payment_status` | VARCHAR | **[狀態]** 財務狀態：<br>`UNPAID` (未付), `PAID` (已付), `REFUNDED` (已退款) |
| `questionnaire_status`| VARCHAR | **[狀態]** 問卷進度：<br>`NOT_REQUIRED` (熟客免填), `PENDING_CLIENT` (待飼主填寫), `COMPLETED` (已填寫完成) |

### 9. `order_answers` (訂單問卷回答表)

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 回答 ID |
| `order_id` | UUID (FK) | 綁定哪張訂單 |
| `question_id` | UUID (FK) | 對應哪道題目 |
| `answer_text` | TEXT | 飼主填寫的答案 |

> **Constraints**：`UNIQUE (order_id, question_id)` — 同訂單同題目只能有一個答案

### 10. `order_transfers` (轉單歷程表)

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 歷程 ID |
| `order_id` | UUID (FK) | 哪張訂單發生轉單 |
| `from_sitter_id` | UUID (FK) | 原保母 |
| `to_sitter_id` | UUID (FK) | 接收轉單的同行 |
| `transfer_status` | VARCHAR | **[狀態]** 轉單進度：<br>`PENDING` (待對方確認), `ACCEPTED` (對方已接單), `REJECTED` (對方婉拒) |

### 11. `visits` (出勤行程表)
一張訂單可包含多次出勤，為保母日曆行程的來源。

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 行程 ID |
| `order_id` | UUID (FK) | 隸屬訂單 |
| `visit_start_time`| TIMESTAMPTZ | 預計到府區間 (起) |
| `visit_end_time` | TIMESTAMPTZ | 預計到府區間 (迄) |
| `status` | VARCHAR | **[狀態]** 行程進度：<br>`SCHEDULED` (待出發), `IN_PROGRESS` (抵達/服務中), `DONE` (已完成), `CANCELLED` (取消) |
| `sitter_notes` | TEXT | 服務總結日誌 |

### 12. `visit_services` (服務清單與回報表)
保母 APP 用來逐項打勾、拍照的實體清單。

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 服務 ID |
| `visit_id` | UUID (FK) | 屬於哪次到府行程 |
| `pet_id` | UUID (FK) | 針對哪隻寵物 (若為環境清潔可為空) |
| `service_type` | VARCHAR | **[狀態]** 服務類型：<br>`FEEDING` (餵食), `LITTER` (貓砂), `MEDICAL` (醫療/餵藥), `PLAYING` (陪玩), `CUSTOM` (自訂) |
| `description` | VARCHAR | 服務細節說明 |
| `sort_order` | INT | 服務顯示排序，支援拖曳調整 (預設 0) |
| `is_completed` | BOOLEAN | 是否已完成打勾 |
| `photo_url` | VARCHAR | 服務完成證明照片 (GCS 連結) |
| `completed_at` | TIMESTAMPTZ | 保母實際打勾完成的精準時間 |

### 13. `order_action_logs` (業務軌跡日誌表)
合併訂單與行程日誌，前端繪製 Timeline (時間軸) 的唯一來源。

| 欄位名稱 | 型態 | 說明與狀態機 (State Machine) |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 軌跡 ID |
| `order_id` | UUID (FK) | 關聯訂單 |
| `visit_id` | UUID (FK) | 若為出勤打卡動作則填入，若為訂單異動則留空 |
| `actor_profile_id`| UUID (FK) | 觸發動作的人 (若為系統自動觸發則留空) |
| `action_type` | VARCHAR | **[狀態]** 軌跡類型：<br>`ORDER_CREATED` (訂單建立), `STATUS_CHANGED` (狀態變更), `QUESTIONNAIRE_SENT` (發送問卷), `VISIT_STARTED` (行程開始), `VISIT_COMPLETED` (行程完成), `SERVICE_COMPLETED` (服務打勾), `TRANSFER_REQUESTED` (發起轉單) 等 |
| `previous_status` | VARCHAR | 變更前的狀態值 (可為空) |
| `new_status` | VARCHAR | 變更後的狀態值 **(nullable)**；非狀態轉移類型的 action 可為空 |
| `metadata` | JSONB | 附加資訊，如注意事項欄位異動 `{"field": "medical_notes", "old": "...", "new": "..."}` |