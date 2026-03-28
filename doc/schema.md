# 寵物到府保母系統 (SaaS) - 核心資料庫 Schema 規格書 (V8)

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

---

## 🐾 領域二：業務設定與毛孩檔案 (Business & Subjects)

### 3. `services` (服務方案表)
| 欄位名稱 | 型態 | 說明 |
| :--- | :--- | :--- |
| `id` | UUID (PK) | 方案 ID |
| `sitter_profile_id` | UUID (FK) | 屬於哪位保母 |
| `base_price` | DECIMAL | 基礎定價 |
| `is_active` | BOOLEAN | 是否上架 |

### 4. `sitter_questions` (保母自訂問卷表)
### 5. `pets` (服務注意事項表)

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
| `discount_amount`| DECIMAL | 折扣金額 |
| `max_uses` | INT | 最大使用次數 |
| `used_count` | INT | 已使用次數 |

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
| `file_size` | BIGINT | 檔案大小 |
| `is_deleted` | BOOLEAN | 是否已依保留政策刪除檔案 |