# 貓咪保母 PWA — 業務需求規格書 (Business Requirements)

本文件依**前端需求規格**與既有 **Schema**（`doc/schema.md`）對齊，定義系統的業務目標、角色、用例、狀態與規則，供前後端實作與 API 設計對照。

---

## 1. 專案目標與角色

### 1.1 業務目標

- 為**專職貓咪保母**提供接單、排程、任務執行與轉單工具。
- 為**飼主**提供預約保母、填寫問卷、追蹤訂單與管理毛孩護照的介面。
- 雙角色共用同一 PWA，以**角色切換**區分功能，資料依 `profiles.role_type` 隔離。

### 1.2 業務角色 (Actor)


| 角色              | 說明                      | 對應資料                            |
| --------------- | ----------------------- | ------------------------------- |
| **保母 (Sitter)** | 提供到府照護服務，管理方案、問卷、訂單、信任圈 | `profiles.role_type = 'SITTER'` |
| **飼主 (Client)** | 發起預約、填寫問卷、管理寵物、追蹤訂單     | `profiles.role_type = 'CLIENT'` |


- 同一帳號可同時擁有保母與飼主兩種 Profile，前端以 `RoleContext` 切換 `currentRole`（'sitter' | 'client'）。
- 視覺區隔：保母端主色 **Amber**，飼主端主色 **Blue**。

---

## 2. 業務領域與前端對照

以下依**前端底部導覽 + 共用視圖**列出對應的**業務需求**與**主要資料實體**。

### 2.1 保母端 (Sitter Mode)


| 導覽項                       | 業務用途                         | 主要實體 / 狀態                                                          |
| ------------------------- | ---------------------------- | ------------------------------------------------------------------ |
| **行程 (Dashboard)**        | 今日任務與捷徑、事前問卷回收提示、跳轉護照 / 任務面板 | `visits`, `orders`, `order_answers`, `questionnaire_status`        |
| **訂單 (Orders)**           | 評估中報價與拒絕、進行中編輯任務、歷史篩選與總收入    | `orders`, `order_answers`, `visits`, `visit_tasks`, `order_status` |
| **互助 (Trust Circle)**     | 滿單時轉介給白名單保母、發送轉介邀請           | `sitter_trust_circles`, `order_transfers`                          |
| **通知 (Notifications)**    | 依飼主維度的訂單與狀態通知                | `order_action_logs`, `orders`（依 client）                            |
| **我的 (Profile Settings)** | 接單網址、開放預約期間、簡介、方案、問卷設定       | `profiles`, `services`, `sitter_questions`                         |


### 2.2 飼主端 (Client Mode)


| 導覽項                    | 業務用途                       | 主要實體 / 狀態                                         |
| ---------------------- | -------------------------- | ------------------------------------------------- |
| **行程 (Dashboard)**     | 今日保母到府排程、進入訂單執行狀況          | `visits`, `orders`                                |
| **訂單 (Orders)**        | 評估中送單明細/取消、進行中執行狀況/結案、歷史篩選 | `orders`, `visits`, `visit_tasks`, `order_status` |
| **保母 (Sitters)**       | 以代碼綁定保母、前往保母預約網頁           | `client_favorite_sitters`, `profiles`             |
| **通知 (Notifications)** | 依保母維度的報價與任務狀態              | `order_action_logs`, `orders`（依 sitter）           |
| **我的 (Profile)**       | 飼主資料、毛孩護照新增/編輯、登出          | `profiles`, `pets`                                |


### 2.3 共用視圖（無底部導覽）


| 視圖               | 業務用途                          | 主要實體                                                                |
| ---------------- | ----------------------------- | ------------------------------------------------------------------- |
| **毛孩數位護照**       | 檢視/編輯醫療與個性備註、環境備註；分享協作、檢視編輯紀錄 | `pets`, `order_action_logs`（或護照專用編輯紀錄）                              |
| **任務執行面板** (僅保母) | 任務檢核表打勾、多媒體日誌上傳、產出並發送報告       | `visit_tasks`, `visits`                                             |
| **保母對外預約網頁**     | 選日期與方案、填問卷、價格試算、送出預約申請        | `services`, `sitter_questions`, `orders`, `order_answers`, `visits` |


---

## 3. 訂單生命週期與狀態對照

前端 Tab「評估中 / 進行中 / 歷史」與後端狀態對應關係如下。

### 3.1 訂單狀態 (order_status)


| 前端 Tab    | 業務含義       | 對應 `order_status` (建議)   |
| --------- | ---------- | ------------------------ |
| 評估中       | 待保母報價或飼主取消 | `PENDING`                |
| 進行中       | 已成立，排程執行中  | `CONFIRMED`              |
| 歷史訂單/歷史紀錄 | 已結案或已取消    | `COMPLETED`, `CANCELLED` |


### 3.2 問卷狀態 (questionnaire_status)

- `NOT_REQUIRED`：熟客免填
- `PENDING_CLIENT`：待飼主填寫（前端：事前問卷未回收）
- `COMPLETED`：已填寫（前端：事前問卷已回收，可顯示藍色提示）

### 3.3 行程狀態 (visits.status)

- `SCHEDULED` → `IN_PROGRESS`（保母打卡）→ `DONE`（任務完成、發送報告）
- 前端「排程完成狀態列表」打勾/時鐘 icon 可依 `visit_tasks.is_completed` 與 `visits.status` 計算。

### 3.4 業務流程摘要

1. **飼主送出預約**：建立 `orders`（PENDING）、可建立多個 `visits`（依多日期群組）；若需問卷則 `questionnaire_status = PENDING_CLIENT`，並建立 `order_answers` 佔位或待填。
2. **保母評估**：檢視事前問卷、輸入加價/減價 → 試算 `base_amount` + `surcharge_amount` - `discount_amount` = `total_amount`；可「確認發送報價」或「拒絕接單」。
3. **確認成立**：保母發送報價後，飼主確認（或依產品設計自動成立）→ `order_status = CONFIRMED`。
4. **進行中**：保母可編輯任務內容（`visit_tasks` 拖曳排序、增刪）；每次到府有「任務執行面板」→ 打勾、上傳照片、完成後產出報告，`visit.status = DONE`。
5. **結案**：當次訂單所有 `visits` 均完成，飼主端「確認任務全部完成 (結案)」→ `order_status = COMPLETED`。
6. **取消**：飼主「取消送單」或保母「拒絕接單」→ `order_status = CANCELLED`。

---

## 4. 共用業務模組規格

以下對應前端「共用彈出層」與「共用視圖」的**業務邏輯**，不含 UI 實作細節。

### 4.1 服務方案選擇 (PlanSelectionModal)

- **使用情境**：保母對外預約網頁上，使用者更換方案時。
- **業務規則**：
  - 方案列表來自 `services`（`sitter_profile_id` = 當前保母，`is_active = true`）。
  - 若方案有 `bookable_start_date` / `bookable_end_date`，與使用者已選的**預約日期**比對：日期不在區間內則該方案**不可選**，並顯示紅色提示（前端 Disabled + 提示字）。
  - 需顯示方案名稱、價格、標籤（如貓、到府）、長描述（前端需「顯示更多」收合）。

### 4.2 協作編輯紀錄 (EditHistoryModal)

- **使用情境**：毛孩護照頁面，檢視誰在何時改了哪些欄位。
- **業務規則**：
  - 資料來源可為 `order_action_logs`（若護照異動有寫入）或未來擴充的「護照編輯紀錄」表。
  - 時間軸需包含：時間、編輯者（保母/飼主）、修改欄位說明（如：更新了環境備註）。

### 4.3 編輯毛孩資料 (PetFormModal)

- **使用情境**：飼主端新增/編輯寵物。
- **業務規則**：
  - 對應實體 `pets`，欄位：名字、物種 (species)、性別、結紮狀況、體重、備註等（若 schema 有擴充欄位則一併對齊）。
  - 大頭貼為選用，存於 GCS，對應 `pets` 頭像欄位（若 schema 有）。
  - 表單驗證與送出後由後端寫入/更新 `pets`，`client_profile_id` = 當前飼主。

### 4.4 毛孩數位護照 (Pet Profile 視圖)

- **業務規則**：
  - 保母與飼主皆可進入；區分「純檢視」與「編輯」權限（依角色或協作權限）。
  - 內容含醫療/個性/環境備註（對應 `pets` 或訂單/家訪相關備註欄位）。
  - 「分享協作」：複製連結，供對方開啟護照（權限與連結設計由產品決定）。
  - 「檢視紀錄」：開啟 EditHistoryModal。

### 4.5 保母對外預約網頁 (Public Booking Page)

- **Step 1 日期與方案**
  - **業務規則**：支援多個「日期群組」，每群組可選多日並綁定一個方案；可獨立刪除群組。
  - 方案選擇若更換則開啟 PlanSelectionModal；需檢查方案之 `bookable_start_date` / `bookable_end_date` 與 `advance_booking_days`，不符合的方案在 Modal 內 Disabled。
- **Step 2 填寫問卷**
  - 依保母設定的 `sitter_questions`（依寵物類型等）動態產生題目；答案對應 `order_answers`。
- **Step 3 價格估算**
  - 依 Step 1 各群組「天數 × 方案單價」試算；可再加總顯示總金額，對應 `orders.base_amount`，後續保母可再改 `surcharge_amount` / `discount_amount`。
- **送出預約申請**：建立 `orders`（及關聯 `visits`、`order_answers`），並寫入 `order_action_logs`（如 ORDER_CREATED）。

---

## 5. 關鍵業務規則彙整

### 5.1 訂單與報價

- 保母端「訂單評估與報價」：必須能檢視該訂單的**事前問卷答案**（`order_answers`）。
- 報價試算：`total_amount = base_amount + surcharge_amount - discount_amount`；`pricing_notes` 可記錄加價/減價原因。
- 拒絕接單：將訂單設為 `CANCELLED`（或專用狀態），並記錄於 `order_action_logs`。

### 5.2 任務與行程

- 訂單「進行中」時，保母可編輯**單日專屬 SOP**：對應 `visit_tasks` 的增刪與 `sort_order` 拖曳排序。
- 任務執行面板：`visit_tasks` 逐項打勾、上傳 `photo_url`、填寫 `sitter_notes`；全部完成後「任務完成，產出並發送報告」→ `visit.status = DONE`。
- 飼主端「確認任務全部完成 (結案)」：檢查該訂單所有 `visits.status = 'DONE'` 後，將 `order_status` 更新為 `COMPLETED`。

### 5.3 信任圈與轉單

- 信任圈名單：`sitter_trust_circles`（owner_sitter_id = 當前保母，status = ACTIVE）。
- 「確認發送轉介邀請」：建立 `order_transfers`（from_sitter_id = 當前保母，to_sitter_id = 選擇的保母，transfer_status = PENDING）；前端可 Alert 後跳回首頁。
- 轉單接受/婉拒：更新 `order_transfers.transfer_status`（ACCEPTED/REJECTED）；若接受，可將 `orders.current_sitter_id` 改為接手保母。

### 5.4 保母設定

- **專屬接單網址**：唯讀，格式由產品決定（例如 `/booking/{sitter_profile_id}` 或短網址）。
- **開放預約期間**：全域日期限制，前端送出的預約日期需在該區間內（可存於 profiles 擴充欄位或獨立設定表）。
- **服務方案**：CRUD + 排序（sort_order）、公開/隱藏（is_active）、價格、名稱/描述、標籤、可預約日期區間（bookable_start_date / bookable_end_date）。
- **事前問卷**：題目 CRUD、拖曳排序（sort_order）、啟用/停用（is_active）。

### 5.5 飼主端

- **以代碼綁定保母**：依產品設計（例如保母專屬代碼或連結）建立 `client_favorite_sitters` 關聯。
- **前往專屬網頁預約**：導向該保母的對外預約網頁（同上，隱藏底部導覽）。

### 5.6 通知

- 第一層：保母端依「飼主」、飼主端依「保母」彙整。
- 第二層：該客戶（飼主或保母）下的訂單動態，資料來源為 `order_action_logs`（及關聯 orders/visits）。

---

## 6. 與 Schema / API 對照要點

- **帳號與身分**：`accounts` + `profiles`（role_type）；JWT 需帶 profile_id 或 account_id，後端依此判斷 Sitter/Client。
- **方案與問卷**：`services`, `sitter_questions`；預約網頁需 API：取得保母方案列表、問卷題目、送出訂單（含 visits、order_answers）。
- **訂單與報價**：`orders`（含 order_status, questionnaire_status, payment_status）, `order_answers`；保母報價 API 需支援更新金額欄位與狀態。
- **行程與任務**：`visits`, `visit_tasks`；任務面板需 API：取得/更新 visit_tasks（is_completed, photo_url, completed_at）、更新 visit.status 與 sitter_notes。
- **護照**：`pets`；編輯紀錄若用 `order_action_logs` 需約定 action_type 或專用表。
- **信任圈與轉單**：`sitter_trust_circles`, `order_transfers`；轉介邀請 API 建立 order_transfers，接受/拒絕 API 更新狀態並可更新 orders.current_sitter_id。
- **歷史訂單篩選與總收入**：依月份、order_status 篩選 orders，總收入為篩選區間內 `total_amount` 加總（可限定 COMPLETED + PAID）。

---

## 7. 非功能面（與業務相關）

- **時區**：所有時間以 UTC 儲存（timestamptz），傳輸 ISO-8601；前端顯示可轉為 `Asia/Taipei`。
- **金流**：訂單支付狀態 `payment_status`（UNPAID/PAID/REFUNDED）；金流介面採策略模式，Webhook 需冪等。
- **狀態管理**：前端各 View 狀態需獨立、易於抽換為 API（列表、表單、篩選條件），以利未來接 GCP 後端與 Cloud SQL。

---

本規格書與 `doc/schema.md`、`doc/frontend.md`（底部導覽與視圖/動作）一致，可作為 API 設計與實作驗收的業務依據。