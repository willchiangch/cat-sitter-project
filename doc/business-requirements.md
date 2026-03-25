# 貓咪保母 PWA — 業務需求規格書 (Business Requirements)

本文件依**前端需求規格** (`doc/frontend-spec.md`)與既有 **Schema**（`doc/schema.md`）對齊，定義系統的業務目標、角色、用例、狀態與規則，供前後端實作與 API 設計對照。

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
| **收款 (Finances)**         | 帳務管理、線上待撥款佇列、線下確認與對帳         | `payments`, `payouts`, `payment_status`                            |
| **通知 (Notifications)**    | 依飼主維度的訂單、付款與轉介狀態通知             | `order_action_logs`, `orders`, `notifications`                     |
| **我的 (Profile Settings)** | 接單網址、服務方案、問卷設定、**信任圈管理**、金流 KYC | `profiles`, `services`, `sitter_questions`, `sitter_trust_circles` |


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


| 前端 Tab    | 業務含義                     | 對應 `order_status` / `payment_status` |
| --------- | ------------------------ | ----------------------------------- |
| 評估中       | 待保母報價 / 待飼主付款        | `PENDING`, `PENDING_PAYMENT`        |
| 進行中       | 已付款成立，排程執行中          | `CONFIRMED`                         |
| 歷史訂單     | 已結案或已取消 (含退款)        | `COMPLETED`, `CANCELLED`            |

### 3.2 訂單付款與啟動規則

**訂單必須達到 `PAID` (已付款) 狀態，訂單才算正式啟動 (`order_status = CONFIRMED`)。**

- **第一階段 (MVP)**：僅實作「線下付款」。飼主上傳憑證 → 保母點擊確認收款 → 轉為 `PAID`。
- **第二階段 (企業化)**：實作「線上付款」。由藍新金流 API 自動回寫狀態，支援實時分帳。
- **逾期未付**：若報價後 `PENDING_PAYMENT_TIMEOUT` 小時內未付，訂單自動失效。

### 3.3 訂單修改與撤回 (Client)

飼主在訂單為 `PENDING` 或 `PENDING_PAYMENT` 狀態時，可執行下列操作：

- **撤回訂單**：飼主主動取消。若已付款則觸發退款流程。
- **修改訂單日期**：
  - 僅限 `PENDING` 狀態（保母報價前）。
  - 若已報價但未付，需聯繫保母重新評估並發送新報價。

### 3.4 問卷狀態 (questionnaire_status)

- `NOT_REQUIRED`：熟客免填
- `PENDING_CLIENT`：待飼主填寫（保母已要求問卷）
- `COMPLETED`：已填寫（保母可開始審核答案並決定是否接單）

### 3.5 行程狀態 (visits.status)

- `SCHEDULED` → `IN_PROGRESS`（保母打卡）→ `DONE`（任務完成、發送報告）
- 前端「排程完成狀態列表」打勾/時鐘 icon 可依 `visit_tasks.is_completed` 與 `visits.status` 計算。

### 3.6 業務流程摘要

1. **飼主送出預約**：建立 `orders`（PENDING），此時不填問卷。
2. **保母要求問卷 (選用)**：保母決定是否需要問卷。若需要，`questionnaire_status = PENDING_CLIENT`。
3. **飼主回填問卷**：飼主填寫完畢後 `questionnaire_status = COMPLETED`，系統通知保母審核。
4. **保母審核與決定接單**：
   - 保母查看問卷答案與預約行程。
   - **決定接單**：此時保母才輸入加減價（報價），訂單進入 `PENDING_PAYMENT`。
   - **拒絕接單**：`order_status = CANCELLED`。
5. **飼主付款**：
   - **實作階段**：第一階段採「線下付款（憑證上傳）」，第二階段才啟用「線上金流」。
6. **確認成立**：
   - 保母確認線下收款（或第二階段線上付款成功）後，`payment_status = PAID` 且 `order_status = CONFIRMED`。
   - 系統發送 **「訂單啟動成功」** 通知給雙方。
5. **進行中**：保母執行任務並上傳日誌。
6. **結案**：任務完成，飼主確認結案，系統列入保母可對帳收入。

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

### 5.3 轉介通知系統 (Referral System)

信任圈是保母的**私人白名單**。當保母無法接單時，可發起主動轉介通知。

#### 信任圈管理
- 移至 **「我的 (Profile)」** 下的「信任圈管理」子頁面。
- 功能：搜尋並加入保母、設定移除、檢視對方的預約網頁。

#### 轉介流程
1. **發起通知**：保母在訂單評估頁面可選擇「轉介給信任圈夥伴」。
2. **選擇對象**：可選擇單一或「全部」信任圈保母。
3. **系統派送**：
   - 系統自動發送 **APP Push** 與 **Email** 給受訪保母，內含原始訂單的日期、需求摘要。
4. **回報**：原保母可追蹤轉介通知的「讀取狀況」。

### 5.4 保母設定

- **專屬接單網址**：唯讀，格式由產品決定（例如 `/booking/{sitter_profile_id}` 或短網址）。
- **開放預約期間**：全域日期限制，前端送出的預約日期需在該區間內（可存於 profiles 擴充欄位或獨立設定表）。
- **服務方案**：CRUD + 排序（sort_order）、公開/隱藏（is_active）、價格、名稱/描述、標籤、可預約日期區間（bookable_start_date / bookable_end_date）。
- **事前問卷**：題目 CRUD、拖曳排序（sort_order）、啟用/停用（is_active）。

#### 5.4.1 行事曆 API 自動同步 (Calendar Sync)

- **同步方式**：系統透過 OAuth2 與保母的行事曆 API 連動（Google Calendar API / iCloud API），採**背景自動同步**。
- **觸發與動作**：
  - **訂單成立 (CONFIRMED)**：自動在目的地行事曆建立事件，並將回傳的 `calendar_event_id` 寫入 `visits` 表（或專用同步表）。
  - **日期異動 (MODIFIED)**：若訂單在 PENDING 或後續協商中改期，系統自動依據 `calendar_event_id` 更新行事曆日期。
  - **訂單取消 (CANCELLED)**：系統自動刪除對應的行事曆事件。
- **時間粒度**：以**天（date）為單位**，一個 `visit` 對應一筆行事曆事件。預設時間 `09:00–10:00 Asia/Taipei`（保母可於設定頁調整預設時段）。
- **容錯機制**：
  - **重複建立**：建立前檢查該 `visit_id` 是否已有 `calendar_event_id`。
  - **事件不存在**：執行刪除或更新時，若 API 回報 404（例如保母已手動刪除），系統應忽略錯誤、清除資料庫中的 `calendar_event_id` 並記錄為成功，確保狀態不卡住。
  - **授權失效**：若授權過期，系統改發送「排程更新失敗通知」，提示保母重新授權或手動調整。
- **隱私聲明**：系統僅具備建立、異動與刪除**由本系統產生的事件**權限，不得讀取保母其他私人行程。

#### 5.4.2 工作日誌媒體保留政策 (Media Retention)

- **存放位置**：GCS（Google Cloud Storage），由任務執行面板上傳的照片與影片。
- **保留期限**：上傳後保留 **`MEDIA_RETENTION_DAYS`**（參數化，預設 60 天）後自動刪除。
- **上傳限制**（均需參數化）：
  - 照片：單次上傳總容量上限 `PHOTO_UPLOAD_MAX_BYTES`，單次最大檔案數 `PHOTO_UPLOAD_MAX_COUNT`。
  - 影片：單次上傳總容量上限 `VIDEO_UPLOAD_MAX_BYTES`，單次最大檔案數 `VIDEO_UPLOAD_MAX_COUNT`。
- **日誌紀錄**：
  - 上傳時：在 `order_action_logs` 寫入 `MEDIA_UPLOADED`（含 GCS object key、檔案類型、大小）。
  - 系統刪除時：GCS 生命週期事件觸發後，在 `order_action_logs` 補寫 `MEDIA_AUTO_DELETED`（保留 log 紀錄即使媒體已不存在）。

### 5.5 飼主端

- **以代碼綁定保母**：依產品設計（例如保母專屬代碼或連結）建立 `client_favorite_sitters` 關聯。
- **加入保母清單（其他入口）**：
  - 飼主**送出訂單 request 後**，系統自動將該保母加入 `client_favorite_sitters`（若尚未存在）。
  - 飼主瀏覽保母的**專屬預約網頁**時，可直接點擊「加入保母清單」按鈕（不必下單），建立 `client_favorite_sitters` 關聯。
- **移除保母清單**：飼主端保母列表提供「移除」功能，刪除對應 `client_favorite_sitters` 紀錄。
- **前往專屬網頁預約**：導向該保母的對外預約網頁（同上，隱藏底部導覽）。

---

### 5.6 保母與客人間金流：服務金流 (Sitter-Client Finances)

本段落定義保母提供照護服務所產生的帳務與收款流程。

#### 5.6.1 支付方式與階段實作
- **第一階段 (非企業社)**：僅實作「線下付款（憑證上傳）」。
  - 飼主於訂單頁面上傳轉帳或付款憑證。
  - 保母於後端點擊「確認收款」後，訂單方告成立。
- **第二階段 (企業社成立)**：啟用「線上付款與平台分帳」。
  - 合作商：藍新金流 (NewebPay)。
  - 分帳：資金進入信託，系統自動撥款給保母與平台手續費。
  - 保母 KYC：必須完成金流商之身分審核與銀行帳號綁定（註冊時連動）。

#### 5.6.2 收款分頁與對帳 (保母端)
- **待撥款佇列 (Online)**：僅於第二階段出現，顯示平台尚未撥款的款項。
- **收款紀錄 (History)**：顯示所有已確認收訖（線下）或已入帳（線上）的收入。
- **篩選**：按月份、支付方式（線上/線下）篩選。

---

### 5.7 系統與保母間金流：平台訂閱與方案 (System-Sitter Subscription)

本段落定義保母使用本平台服務之權利金與方案限制。

#### 5.7.1 訂閱方案與限制
保母可於 **Profile (我的)** 選擇訂閱方案，方案屬性包括：
- **方案等級**：例如 Standard / Pro / Premium。
- **可承接訂單數量**：每個方案設有「月/年承單上限」（參數化），達到上限後，該保母的預約網頁將不再開放預約。
- **計費週期**：可選「月繳」或「年繳」。
- **年繳折扣**：選擇年繳模式，金額自動折抵 `ANNUAL_DISCOUNT_PERCENT`（參數化）。

#### 5.7.2 續訂與逾期停權
- **付款提醒**：系統於訂閱到期前 `RENEWAL_REMINDER_DAYS` 天（參數化），依付款方式自動發送：
  - **電子郵件與系統通知**：內含虛擬帳號或刷卡連結。
- **付款方式**：銀行帳戶轉帳、信用卡單次付、信用卡每月自動扣款（第二階段開發）。
- **逾期限制**：若於到期日隔天 00:00 仍未完成續訂或扣款失敗：
  - **功能鎖定**：系統自動鎖定保母端所有功能。
  - **唯一權限**：僅保留 **「我的 (Profile)」** 頁面，供保母進行帳號管理與補繳/續訂。
  - **開放條件**：補繳完成後，狀態回寫即刻解除鎖定。

---

### 5.8 通知

- 第一層：保母端依「飼主」、飼主端依「保母」彙整。
- 第二層：該客戶（飼主或保母）下的訂單動態，資料來源為 `order_action_logs`（及關聯 orders/visits）。

---

## 6. 與 Schema / API 對照要點

- **帳號與身分**：`accounts` + `profiles`（role_type）；JWT 需帶 profile_id 或 account_id，後端依此判斷 Sitter/Client。
- **方案與問卷**：`services`, `sitter_questions`；預約網頁需 API：取得保母方案列表、問卷題目、送出訂單（含 visits、order_answers）。
- **訂單與報價**：`orders`（含 order_status, questionnaire_status, payment_status）, `order_answers`；保母報價 API 需支援更新金額欄位與狀態。
- **行程與任務**：`visits`, `visit_tasks`；任務面板需 API：取得/更新 visit_tasks（is_completed, photo_url, completed_at）、更新 visit.status 與 sitter_notes。
- **護照**：`pets`；編輯紀錄若用 `order_action_logs` 需約定 action_type 或專用表。
- **信任圈**：`sitter_trust_circles`；需 API：查詢清單、加入、移除。
- **訂閱與方案**：`subscription_plans`, `sitter_subscriptions`；需 API：取得方案列表、建立訂閱、查詢目前狀態。
- **歷史訂單篩選與總收入**：依月份、order_status 篩選 orders，總收入為篩選區間內 `total_amount` 加總（可限定 COMPLETED + PAID）。

---

## 7. 非功能面（與業務相關）

- **時區**：所有時間以 UTC 儲存（timestamptz），傳輸 ISO-8601；前端顯示可轉為 `Asia/Taipei`。
- **金流**：訂單支付狀態 `payment_status`（UNPAID/PAID/REFUNDED）；金流介面採策略模式，Webhook 需冪等。
- **狀態管理**：前端各 View 狀態需獨立、易於抽換為 API（列表、表單、篩選條件），以利未來接 GCP 後端與 Cloud SQL。

---

本規格書與 `doc/schema.md`、`doc/frontend-spec.md`（底部導覽與視圖/動作）一致，可作為 API 設計與實作驗收的業務依據。