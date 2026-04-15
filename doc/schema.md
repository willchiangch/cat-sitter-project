# 寵物到府保母系統 (SaaS) - 核心資料庫 Schema 規格書 (V30)

## 全域稽核欄位 (Audit Columns) 約定
所有資料表皆包含以下標準稽核欄位：
* `created_by` (VARCHAR)：建立者 ID 或系統標識
* `created_at` (TIMESTAMPTZ)：資料建立時間
* `updated_by` (VARCHAR)：最後修改者 ID
* `updated_at` (TIMESTAMPTZ)：資料最後修改時間

---

## 🏛️ 領域一：帳號與身分核心 (Identity & Access)

### 1. `accounts` (系統登入帳號表)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 系統底層唯一識別碼 |
| `email` | VARCHAR (UNIQUE) | 登入與主要聯絡信箱 |
| `oauth_provider` | VARCHAR | `LOCAL`, `GOOGLE`, `LINE`, `APPLE` |
| `last_active_role` | VARCHAR | **[V9]** `SITTER`, `CLIENT` (Role Switching) |
| `status` | VARCHAR | `ACTIVE`, `SUSPENDED` |

### 2. `profiles` (角色檔案表)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 身分唯一識別碼 |
| `account_id` | UUID (FK) | 對應至 `accounts` |
| `role_type` | VARCHAR | `SITTER`, `CLIENT` |
| `name` | VARCHAR | 姓名 / 品牌名 |
| `booking_open_start` | DATE | **[SITTER]** 開放預約起始日 |
| `booking_open_end` | DATE | **[SITTER]** 開放預約結束日 |
| `avatar_url` | VARCHAR | 頭像路徑 |
| `is_verified` | BOOLEAN | **[V30]** 是否通過身分驗證 |
| `id_card_front_url` | VARCHAR | **[V30]** 身分證件正面照片 |
| `face_photo_url` | VARCHAR | **[V30]** 人臉辨識自拍照 |
| `bank_code` | VARCHAR | **[V30]** 銀行代碼 (3碼) |
| `bank_account` | VARCHAR | **[V30]** 銀行帳號 |
| `bio_summary` | TEXT | **[V30]** 保母自我介紹 |
| `professional_labels`| JSONB | **[V15]** 專業標籤列表 |

---

## 🐾 領域二：業務設定與毛孩檔案 (Business & Subjects)

### 3. `services` (服務方案表)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 方案 ID |
| `sitter_profile_id` | UUID (FK) | 屬於哪位保母 |
| `name` | VARCHAR | 方案名稱 |
| `base_price` | DECIMAL | 基礎定價 |
| `duration_minutes` | INT | 服務時長 (分鐘) |
| `supported_pet_types`| JSONB | 支援的寵物類型 (如 ["CAT", "DOG"]) |
| `bookable_start_date`| DATE | 可預約開始日期 |
| `bookable_end_date` | DATE | 可預約結束日期 |
| `effective_start_date`| DATE | 方案生效開始日期 |
| `effective_end_date` | DATE | 方案生效結束日期 |
| `description` | TEXT | **[NEW]** 服務詳細描述 (含顯示更多按鈕) |
| `sort_order` | INT | 顯示排序 |
| `is_active` | BOOLEAN | 是否上架 |
| `is_whitelist_only` | BOOLEAN | **[NEW]** 是否僅限白名單可見 (PREMIUM) |

### 4. `sitter_questions` (保母自訂問卷表)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 題目 ID |
| `sitter_profile_id` | UUID (FK) | 屬於哪位保母 |
| `target_pet_type` | VARCHAR | `DOG`, `CAT`, `HAMSTER`, `RABBIT`, `BIRD`, `OTHER`, `ALL` |
| `question_text` | TEXT | 題目內容 |
| `type` | VARCHAR | `TEXT`, `SINGLE_CHOICE`, `MULTIPLE_CHOICE` |
| `required` | BOOLEAN | 是否必填 |
| `sort_order` | INT | 排序權重 |
| `is_active` | BOOLEAN | 是否啟用 |

#### `sitter_question_options` (問卷題目選項)
*存儲單選/多選題的預設選項*
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `question_id` | UUID (FK) | 對應至 `sitter_questions` |
| `option_text` | VARCHAR | 選項內容 |

### 5. `pets` (毛孩檔案表)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 毛孩專屬 ID |
| `client_profile_id` | UUID (FK) | 屬於哪位飼主 |
| `name` | VARCHAR | 名字 |
| `species` | VARCHAR | `DOG`, `CAT`, `HAMSTER`, `RABBIT`, `BIRD`, `OTHER` |
| `gender` | VARCHAR | `MALE`, `FEMALE`, `UNKNOWN` (NOT NULL) |
| `neutered_status` | VARCHAR | **[V26]** `YES`, `NO`, `NOT_REQUIRED` |
| `vaccination_status`| VARCHAR | **[V26]** `YES`, `NO`, `NOT_REQUIRED` |
| `deworming_status` | VARCHAR | **[V26]** `YES`, `NO`, `NOT_REQUIRED` |
| `birth_date` | DATE | **[V22]** 出生年月日 |
| `weight_kg` | DECIMAL | 體重 (kg) |
| `avatar_url` | VARCHAR | 照片路徑 |
| `medical_notes` | TEXT | 醫療狀況註記 |
| `dietary_notes` | TEXT | 飲食習慣註記 |
| `personality_notes`| TEXT | 性格與行為註記 |
| `other_notes` | TEXT | 其他補充事項 |

---

## 📅 領域三：交易、排程與執行 (Orders & Execution)

### 8. `orders` (訂單主表)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `order_status` | VARCHAR | `PENDING`, `CONFIRMED`, `COMPLETED`, `CANCELLED` |
| `payment_status` | VARCHAR | `UNPAID`, `PAID`, `REFUNDED` |

### 11. `visits` (出勤行程表)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 行程 ID |
| `calendar_event_id` | VARCHAR | **[NEW]** 外部日曆事件 ID (如 Google Calendar ID) |
| `status` | VARCHAR | `SCHEDULED`, `IN_PROGRESS`, `DONE`, `CANCELLED` |

---

## 💰 領域四：財務與訂閱 (Finance & Subscriptions) - [NEW]

### 14. `subscription_plans` (保母訂閱方案定義)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 方案 ID |
| `name` | VARCHAR | 方案名稱 (Standard, Pro, Premium) |
| `order_limit` | INT | 每月接單上限 |
| `plan_code` | VARCHAR | **[V16]** 內部方案代碼 (FREE, PRO...) |
| `monthly_price` | DECIMAL | 月繳金額 |
| `yearly_price` | DECIMAL | 年繳金額 |

### 15. `sitter_subscriptions` (保母訂閱紀錄)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 紀錄 ID |
| `sitter_profile_id` | UUID (FK) | 訂閱的保母 |
| `plan_id` | UUID (FK) | 訂閱的方案 |
| `start_date` | DATE | 起始日 |
| `end_date` | DATE | 到期日 |
| `status` | VARCHAR | `ACTIVE`, `EXPIRED`, `CANCELLED` |

### 16. `promo_codes` (促銷折扣碼)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 折扣碼 ID |
| `code` | VARCHAR | 折扣代碼 (UNIQUE) |
| `discount_amount`| DECIMAL | 固定折扣金額 |
| `discount_type` | VARCHAR | **[V27]** `FIXED`（固定金額）/ `PERCENT`（百分比） |
| `discount_percent` | DECIMAL | **[V27]** 百分比折扣值（0–100），discount_type=PERCENT 時使用 |
| `max_uses` | INT | 最大使用次數 |
| `used_count` | INT | 已使用次數 |
| `expiry_date` | TIMESTAMPTZ | 過期時間（NULL = 永不過期）|

### 16-A. `subscription_change_logs` (訂閱方案變更歷程) - **[V27 NEW]**
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 紀錄 ID |
| `sitter_profile_id` | UUID (FK) | 操作保母 |
| `from_plan_code` | VARCHAR | 變更前方案代碼（NULL = 全新訂閱）|
| `to_plan_code` | VARCHAR | 變更後方案代碼 |
| `change_type` | VARCHAR | `SUBSCRIBE` / `UPGRADE` / `DOWNGRADE` / `CANCEL` / `FREE_REDEMPTION` |
| `promo_code_id` | UUID (FK) | 使用的折扣碼（nullable）|
| `promo_code_used` | VARCHAR | 折扣碼文字冗余備份（防止碼被刪除後歷史丟失）|
| `original_amount` | DECIMAL | 原始金額 |
| `discount_amount` | DECIMAL | 實際折扣金額 |
| `final_amount` | DECIMAL | 最終實付金額（0 = 免費啟用）|
| `note` | TEXT | 備註（如「折扣碼使金額歸零，直接免費啟用」）|

### 17. `payment_transactions` (支付交易紀錄)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 交易 ID |
| `mer_trade_no` | VARCHAR | 系統內部唯一案號 |
| `trade_no` | VARCHAR | PayUni 外部交易序號 |
| `amount` | DECIMAL | 交易金額 |
| `status` | VARCHAR | `PENDING`, `SUCCESS`, `FAILED` |

---

## 🔗 領域五：整合與資源 (Integration & Assets) - [NEW]

### 18. `sitter_calendar_configs` (行事曆同步配置)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 配置 ID |
| `sitter_profile_id` | UUID (FK) | 屬於哪位保母 |
| `provider` | VARCHAR | `GOOGLE`, `ICLOUD` (WebCal) |
| `ical_token` | VARCHAR | **[V7]** 專屬 iCal 訂閱 Token (唯一) |
| `access_token` | TEXT | OAuth2 存取憑證 |
| `refresh_token` | TEXT | OAuth2 刷新憑證 |

### 19. `visit_media` (行程多媒體附件)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 媒體 ID |
| `visit_id` | UUID (FK) | 屬於哪次行程 |
| `media_url` | VARCHAR | 儲存路徑 (本地或 GCS) |
| `media_type` | VARCHAR | `PHOTO`, `VIDEO` |
| `caption` | VARCHAR | **[NEW]** 媒體說明文字 |
| `file_size` | BIGINT | 檔案大小 |
| `is_deleted` | BOOLEAN | 是否已依保留政策刪除檔案 |