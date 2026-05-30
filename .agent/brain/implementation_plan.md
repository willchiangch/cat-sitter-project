# SD-007: 線下付款憑證上傳與確認 (MVP 階段) 實作計劃

本計劃旨在實作 PRD-007 線下付款凭证流程，打通 `PENDING_PAYMENT → PAID → CONFIRMED` (以及拒絕退回 `PAID → PENDING_PAYMENT`) 的完整閉環，支援飼主上傳轉帳資訊與免責勾選、保母進行憑證核對及入帳確認，並同步寫入多租戶審計與 SLF4J 動作日誌。

## User Review Required

> [!IMPORTANT]
> **1. 資料庫欄位變更 (Schema Migrations)**：
> - `orders` 表需新增 `payment_proof_url`、`payment_proof_last_five` (嚴格長度為 5)、`disclaimer_agreed`、`disclaimer_agreed_at` 欄位以儲存轉帳憑證與法務免責聲明簽署記錄。
> - `profiles` 表 (SITTER 側表) 需新增 `bank_code`、`bank_branch`、`bank_account`、`bank_payee_name` 以儲存保母的銀行匯款帳戶資訊。
>
> **2. 銀行帳戶安全暴露範圍限制**：
> - 為了避免隱私洩漏，只有在訂單狀態為 `PENDING_PAYMENT` 或 `PAID` 時，`GET /api/orders/{orderId}` DTO 中才會帶出保母的銀行帳號資訊，其餘任何狀態（如 `CONFIRMED`, `COMPLETED`, `CANCELLED`）皆一律回傳 `null`。
>
> **3. 駁回時的 GCS 處理與免責聲明重設 (Known Tech Debt & Audit Trail)**：
> - 當保母駁回憑證後，系統將狀態退回 `PENDING_PAYMENT`，清空資料庫中的憑證圖片關聯（重設為 null），同時強制將 `disclaimer_agreed` 重設為 `false`，`disclaimer_agreed_at` 重設為 `null`。這能確保飼主在重新上傳新憑證時必須再度勾選免責聲明，以便記錄正確、準確的法務/財務審計時間戳。GCS 實體圖片在此階段暫不主動刪除，標註為已知技術債。

## Open Questions

> [!NOTE]
> **Q1：保母的銀行帳號資訊是否應在 E2E 測試與本地開發時提供預設種子資料？**
> - **建議**：是的，我們將在 Flyway 遷移中為現有的保母種子資料預填銀行帳號，以便測試與開發時能直接在飼主待付款畫面中渲染出保母轉帳帳戶。
> 
> **Q2：GCS 付款憑證圖片儲存命名規則？**
> - **建議**：圖片檔將統一由 `MediaStorageService` 上傳，儲存路徑為 `payment-proofs/owner-{ownerId}/order-{orderId}-{random}.jpg`，其生命週期為永久保存（不受方案媒體保留限制，因為屬於財務凭證）。

## Proposed Changes

### Database (Flyway Migration)

#### [NEW] [V20260530_01__add_payment_proof_fields_to_orders.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V20260530_01__add_payment_proof_fields_to_orders.sql)
- 於 `orders` 新增轉帳憑證與免責聲明欄位。
- 於 `profiles` 新增 `bank_code`, `bank_branch`, `bank_account`, `bank_payee_name` 欄位。
- 更新預設保母 seed 資料的銀行帳戶，以供開發測試。

```sql
-- 1. orders 擴充
ALTER TABLE orders ADD COLUMN payment_proof_url VARCHAR(512);
ALTER TABLE orders ADD COLUMN payment_proof_last_five VARCHAR(5); -- 嚴格限制 5 碼
ALTER TABLE orders ADD COLUMN disclaimer_agreed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN disclaimer_agreed_at TIMESTAMPTZ;

-- 2. profiles 擴充
ALTER TABLE profiles ADD COLUMN bank_code VARCHAR(20);
ALTER TABLE profiles ADD COLUMN bank_branch VARCHAR(100);
ALTER TABLE profiles ADD COLUMN bank_account VARCHAR(100);
ALTER TABLE profiles ADD COLUMN bank_payee_name VARCHAR(100);
```

---

### Backend Core & Application

#### [MODIFY] [Order.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/Order.java)
- 新增對應之 JPA 屬性：
  - `paymentProofUrl`
  - `paymentProofLastFive`
  - `disclaimerAgreed`
  - `disclaimerAgreedAt`

#### [MODIFY] [Profile.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/Profile.java)
- 新增對應之 JPA 屬性：
  - `bankCode`
  - `bankBranch`
  - `bankAccount`
  - `bankPayeeName`

#### [NEW] [PaymentService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/PaymentService.java)
- **實作以下業務邏輯 (均加掛 `@Transactional` 與 `@Slf4j`)**：
  - `updateSitterPaymentInfo(UUID sitterId, ...)`: 維護保母轉帳資訊 (寫入/更新 SITTER Profile)。
  - `submitPaymentProof(UUID ownerId, UUID orderId, String lastFive, MultipartFile file, boolean disclaimerAgreed)`: 
    - 驗證 Order 存在、狀態為 `PENDING_PAYMENT`、屬於該飼主且 `disclaimerAgreed` 為 true。
    - **後五碼正規驗證**：驗證 `lastFive` 必須為 5 碼且全為數字：`lastFive != null && lastFive.length() == 5 && lastFive.matches("\\d{5}")`，不符則拋出 `IllegalArgumentException`（API 回傳 400）。
    - 呼叫 `mediaStorageService` 上傳憑證。
    - 更新狀態為 `PAID`，寫入憑證資料，新增 `PAYMENT_PROOF_SUBMITTED` 訂單日誌，發送通知。
  - `verifyPayment(UUID sitterId, UUID orderId)`:
    - 驗證狀態為 `PAID` 且屬於該保母。
    - 更新狀態為 `CONFIRMED`，填入 `paidAt = OffsetDateTime.now()`。
    - 寫入 `PAYMENT_VERIFIED` 訂單日誌，發送通知。
  - `rejectPayment(UUID sitterId, UUID orderId, String rejectReason)`:
    - 驗證狀態為 `PAID` 且屬於該保母。
    - 更新狀態退回 `PENDING_PAYMENT`，清空 `paymentProofUrl` 與 `paymentProofLastFive` 為 null，並重設 `disclaimerAgreed` 為 false，`disclaimerAgreedAt` 為 null。
    - *(Known Tech Debt)*：駁回後暫不主動刪除 GCS 實體圖片，僅清空 DB 關聯。
    - 寫入 `PAYMENT_REJECTED` 訂單日誌 (payload 帶 `rejectReason`)，發送通知。

#### [MODIFY] [OrderController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/OrderController.java)
- 提供以下 Controller 端點與權限卡控 (依賴 `TokenContext.getUserId()` 進行 BOLA 防禦)：
  - `POST /api/orders/{orderId}/payment-proof` (飼主權限，傳送 `file`, `lastFive`, `disclaimerAgreed`)
  - `POST /api/orders/{orderId}/verify-payment` (保母權限，無參數)
  - `POST /api/orders/{orderId}/reject-payment` (保母權限，傳送 `rejectReason`)
  - `PUT /api/sitter/payment-info` (保母權限，更新轉帳帳戶)
  - `GET /api/sitter/payment-info` (保母權限，取得轉帳帳戶)
- 修改 `GET /api/orders/{orderId}` 傳回 DTO：
  - **安全暴露控制**：只有在訂單狀態為 `PENDING_PAYMENT` 或 `PAID` 時，才會查表注入保母的轉帳帳戶資訊，其餘狀態均強制塞入 `null`。

---

### Frontend UI & testid Specifications

為了利於 Playwright 進行精確定位，以下指定各前端元素之 `data-testid` 屬性：

| 元素名稱 | `data-testid` 值 | 說明 |
| :--- | :--- | :--- |
| 匯款資訊區塊容器 | `bank-info-container` | 包含銀行代碼、帳號與戶名之區塊 |
| 銀行代碼文字 | `bank-code-text` | 展示銀行名稱或代碼之 label/span |
| 銀行帳號文字 | `bank-account-text` | 展示保母匯款帳號之 label/span |
| 戶名文字 | `bank-payee-text` | 展示保母戶名之 label/span |
| 免責聲明勾選框 | `checkbox-disclaimer-agreed` | 飼主必須勾選之免責 checkbox |
| 帳號後五碼輸入框 | `input-payment-last-five` | 飼主輸入轉帳後五碼之 input |
| 圖片上傳 input 元件 | `input-payment-file` | 上傳轉帳憑證圖片之 input[type="file"] |
| 憑證提交按鈕 | `btn-submit-payment-proof` | 提交憑證之按鈕 |
| 保母憑證核對面板 | `payment-verification-panel` | 保母檢視憑證照片與後五碼之區塊 |
| 確認入帳按鈕 | `btn-verify-payment` | 保母點擊確認入帳之按鈕 |
| 憑證駁回退回按鈕 | `btn-reject-payment` | 保母點擊駁回憑證之按鈕 |
| 退回原因輸入框 | `input-reject-reason` | 保母填寫退回原因之 textarea |
| 確定退回提交按鈕 | `btn-submit-reject` | 確定退回之按鈕 |

---

## Verification Plan

### Automated Tests (Java & Playwright)
- **後端單元與整合測試**：
  - 新增 `PaymentControllerTest.java`：
    - 測試飼主越權上傳拒絕 (403)
    - 測試未勾選免責聲明拒絕 (400)
    - 測試後五碼不符合正則（非5位數字）拒絕 (400)
    - 測試正常上傳流程與狀態變更 (200, PAID)
    - 測試保母確認入帳 (200, CONFIRMED)
    - 測試保母駁回憑證 (200, PENDING_PAYMENT)
  - 執行 `mvn clean test` 確保 100% 通過。
- **E2E 測試 (Playwright)**：
  - 新增 `docs/test-scenario/TS-007-offline-payment.md` 情境定義。
  - 新增 `frontend/e2e/offline-payment.spec.ts` 測試案例：
    - **Scenario 1**：飼主檢視轉帳帳號、免責勾選防呆、成功上傳憑證變更為 PAID。
    - **Scenario 2**：保母端檢視憑證、核對確認入帳變更為 CONFIRMED。
    - **Scenario 3**：保母駁回憑證、填寫原因、狀態退回 PENDING_PAYMENT。
  - 執行 `npx playwright test` 確保全部通過。

### Manual Verification
- 保母在個人設定頁面更新匯款資訊，飼主送出預約並在報價後，能正常在付款頁面看到該保母之匯款資訊。
