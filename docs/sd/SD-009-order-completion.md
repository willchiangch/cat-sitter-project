# SD-009: 訂單結案與爭議結轉 (Completion & Dispute)

| 項目 | 內容 |
|------|------|
| 模組編號 | SD-009 |
| 對應 PRD | PRD-009, PRD-020, PRD-013 |
| 核心技術 | Scheduled Tasks (Cron), Financial Timestamps (Payout_at), GCP Storage |
| 狀態 | **Approved with Consultant Feedback** |

---

## 1. 業務邏輯與流程設計

### 1.1 結案路徑與財務觸發
1. **自動結案 (Auto-Completion)**：系統每小時執行。
   - **殭屍清理**：先強制關閉 72 小時未打卡的行程。
   - **狀態結轉**：確認所有行程皆為 `DONE` 或 `CLOSED_BY_SYSTEM` 且過 48 小時。
2. **手動結案 (Manual)**：飼主點擊【確認結案】。
3. **強制結案 (Admin-Override)**：管理員處理爭議後結案。

### 1.2 財務時間戳記 (Financial Benchmarks)
結案時必須寫入：
- **`completed_at`**: 結案當下時間。
- **`payout_at`**: 
  - 線上支付：`completed_at + 3 days` (金流撥款基準)。
  - 線下支付：同 `paid_at` (僅作帳務紀錄)。

---

## 2. API 定義

### 2.1 飼主手動結案
- **Endpoint**: `POST /api/orders/{orderId}/complete`
- **Auth**: `ROLE_OWNER`
- **Headers**: `Idempotency-Key: UUID` (必填)
- **邏輯**: 檢核成功後寫入 `completed_at` 與 `payout_at`。

### 2.2 飼主回報爭議 (Dispute)
- **Endpoint**: `POST /api/orders/{orderId}/dispute`
- **Auth**: `ROLE_OWNER`
- **Headers**: `Idempotency-Key: UUID` (必填)
- **Request Body**: `{ "category": "ENUM", "description": "...", "version": 1 }`

### 2.3 管理員強制結案 (Admin Resolve)
- **Endpoint**: `POST /api/admin/orders/{orderId}/resolve`
- **Auth**: `ROLE_ADMIN`
- **Headers**: `Idempotency-Key: UUID` (必填，[NFR-003] 交易去重)
- **Request Body**: 
```json
{
  "finalTotalAmount": 900,
  "evidenceUrls": ["gs://cat-sitter-bucket/disputes/uuid_1.jpg"],
  "resolutionNote": "雙方同意減收 100 元",
  "confirmPassword": "hashed_password",
  "version": 1
}
```

---

## 3. 詳細邏輯與序列圖 (Sequence Diagram)

### 3.1 自動結案排程 (CronJob Logic)

```mermaid
sequenceDiagram
    participant Cron as CronJob (1hr)
    participant SVC as CompletionService
    participant DB as PostgreSQL

    Cron->>SVC: triggerAutoCompletion()
    activate SVC
    
    Note over SVC, DB: [Pre-step] 1. 清理過期未打卡的殭屍行程
    SVC->>DB: UPDATE visits SET status='CLOSED_BY_SYSTEM' <br/>WHERE status='PENDING' AND scheduled_at < (NOW - 72h)
    
    Note over SVC, DB: 2. 篩選符合結案條件的訂單
    SVC->>DB: SELECT id, payment_type, paid_at FROM orders <br/>WHERE status IN ('IN_PROGRESS', 'CONFIRMED') <br/>AND (所有 visits 為 DONE 或 CLOSED_BY_SYSTEM) <br/>AND last_visit_time < (NOW - 48h)
    
    loop For each Order
        Note over SVC: 3. 計算 payout_at (線上 D+3, 線下同 paid_at)
        SVC->>DB: UPDATE orders SET status='COMPLETED', <br/>completed_at=NOW(), payout_at=:calculatedDate, version=version+1<br/>WHERE id=:id AND version=:v AND status!='DISPUTED'
        
        alt Success
            SVC->>DB: INSERT INTO order_logs (type=AUTO_COMPLETED)
        end
    end
    
    deactivate SVC

> [!IMPORTANT]
> **開發實作提醒 (Transaction Boundary)**:
> 在實作 `triggerAutoCompletion` 時，`loop For each Order` 內部的處理邏輯必須標註為 `@Transactional(propagation = Propagation.REQUIRES_NEW)`。這能確保：
> 1. 每筆訂單的結案為獨立事務，互不干擾。
> 2. 若單一訂單結案失敗 (如 OptimisticLockException)，系統能繼續處理下一筆，不會導致全域 Rollback。
```

---

## 4. 資料庫異動與限制 (DB Constraint)

### 4.1 財務紀錄與差額 (Ledger)
管理員結案時紀錄：
- `evidence_snapshot`: JSONB (存放 `resolutionNote` 與 `gs://` 路徑)

---

## 5. NFR 規格對齊 (NFR Alignment)

| NFR 編號 | 設計實作 |
|----------|----------|
| **NFR-003 (Security)** | 管理員執行 `resolve` 時必須通過**二次驗證**，且所有 POST API 皆具備 **Idempotency-Key**。 |
| **NFR-006 (Audit)** | 紀錄狀態變遷 (`IN_PROGRESS` -> `COMPLETED`) 以及結案時的財務基準日。 |
| **NFR-008 (Compliance)** | 財務資料 (payout_at, completed_at) 設為永不移除。 |
| **NFR-001 (Performance)** | CronJob 清理殭屍行程使用索引欄位 (`status`, `scheduled_at`)，確保不造成資料庫鎖定過久。 |
