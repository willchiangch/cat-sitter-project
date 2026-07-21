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

### 1.3 爭議申報狀態機防呆
`disputeOrder()` 僅允許 `order.status ∈ {CONFIRMED, IN_PROGRESS}` 的訂單被申報爭議（其餘狀態如 `PENDING`/`COMPLETED`/已在 `DISPUTED` 中，一律拒絕並拋 `IllegalStateException`），同時比對 `order.owner.id == 呼叫者`，防止非本人飼主誤操作或重複申報。前端「申報爭議」按鈕的顯示條件（`OwnerOrderDetail.tsx`）須與此狀態集合保持一致，避免飼主在非法狀態下看到可點擊的按鈕才在送出後被後端拒絕。

### 1.4 保母帳務總覽 (Sitter Ledger)
保母可依月份查詢自己名下已結案訂單的營收總覽（PRD-009 主流程 C）。查詢範圍為 `orders.completed_at` 落在指定月份區間（`[月初, 次月月初)`），僅撈取 `COMPLETED` 狀態的訂單，回傳每筆訂單的金額與三個財務時間戳記（`paidAt`/`completedAt`/`payoutAt`）以及該月總營收。

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
- **Endpoint**: `POST /api/orders/{orderId}/admin-resolve`
- **Auth**: `ROLE_ADMIN`（`@PreAuthorize("hasRole('ADMIN')")`，Controller 層即攔截，非僅依賴 Service 層邏輯判斷）
```json
{
  "finalAmount": 900,
  "receiptUrl": "gs://cat-sitter-bucket/disputes/uuid_1.jpg",
  "reason": "雙方同意減收 100 元",
  "adminPassword": "當前登入管理員的原始密碼（非雜湊值，用於二次驗證）"
}
```
- **二次驗證邏輯**：後端以 `SecurityContextHolder` 取得目前登入管理員的 email，呼叫 `AuthService.verifyPassword(adminEmail, req.adminPassword())` 重新比對密碼雜湊；不符時拋 `AccessDeniedException` → **403**（而非 401）。前端 `axiosClient` 對所有 401 回應皆會觸發全域 refresh-token 靜默重試，若此處回 401 會讓「密碼錯誤」被誤判為 session 過期，導致使用者看到的錯誤訊息失真、甚至連正確密碼的後續請求都被連帶影響。

### 2.4 保母帳務總覽 (Sitter Ledger)
- **Endpoint**: `GET /api/orders/sitter/ledger?month=2026-07`
- **Auth**: `ROLE_SITTER`
- **Query Param**: `month`（`YYYY-MM`，省略則預設當月）
```json
{
  "yearMonth": "2026-07",
  "totalRevenue": 4500,
  "entries": [
    {
      "orderId": "uuid",
      "ownerName": "王小明",
      "totalAmount": 1500,
      "paidAt": "2026-07-02T10:00:00Z",
      "completedAt": "2026-07-05T10:00:00Z",
      "payoutAt": "2026-07-08T10:00:00Z"
    }
  ]
}
```

### 2.5 內部觸發端點 (Internal Cron Trigger)
- **Endpoint**: `POST /api/internal/cron/orders/auto-complete`
- **Auth**: `INTERNAL_ONLY` (由 SecurityConfig 控制)
- **說明**: 供外部排程器（如 GCP Cloud Scheduler）呼叫，觸發自動結案邏輯。

---

## 3. 詳細邏輯與序列圖 (Sequence Diagram)

### 3.1 自動結案排程 (CronJob Logic)

```mermaid
sequenceDiagram
    participant Scheduler as GCP Cloud Scheduler
    participant API as InternalCronController
    participant SVC as CompletionService
    participant DB as PostgreSQL

    Scheduler->>API: POST /api/internal/cron/orders/auto-complete (with Secret Header)
    API->>SVC: triggerAutoCompletion()
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
> 1. **獨立事務**: 在實作 `triggerAutoCompletion` 時，`loop For each Order` 內部的處理邏輯必須標註為 `@Transactional(propagation = Propagation.REQUIRES_NEW)`。
> 2. **Cloud Run 相容性**: 由於 `min-instances: 0`，內部 `@Scheduled` 在生產環境無效。必須確保上述 API 可被外部觸發。
> 3. **本地開發模擬**: 可在 `src/main/java/com/petsitter/infrastructure/cron` 建立一個僅在 `local` profile 啟動的 `@Scheduled` 元件，每小時呼叫此 Internal API 以模擬生產環境行為。
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
