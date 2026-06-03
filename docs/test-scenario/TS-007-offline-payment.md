# TS-007: 線下付款憑證與收款帳戶流程 測試情境定義

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | [PRD-007-offline-payment.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-007-offline-payment.md) / [SD-007-offline-payment.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-007-offline-payment.md) |
| **測試類型** | ✅ 功能 (Functional) / ✅ 非功能 (Security/Resilience) |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | [PaymentControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/PaymentControllerTest.java) |

---

## 測試環境與種子資料準備

為了在測試環境中執行驗證，需準備以下預填種子資料：
* **保母使用者**:
  - `id`: `3d498178-14c0-4376-b81e-7fb02e615dda` (測試用 `requesterId`)
  - `profiles.bank_account_info`: 資料庫中為加密之 `JSONB` 密文，在應用層解密後應為：銀行代碼 `822`、分行 `忠孝分行`、帳號 `123456789012`、戶名 `本地測試保母`。
* **飼主使用者**:
  - `id`: `1031efbc-583a-4062-9a35-15706a3384c6` (測試用 `requesterId`)
* **測試用訂單**:
  - `id`: `7031efbc-583a-4062-9a35-15706a3384c9`，關聯飼主與保母，初始狀態為 `PENDING_PAYMENT`。

---

## TS-007-1: 飼主上傳付款憑證與格式校驗 (正常/異常流)

### 一、 測試邏輯定義 (Given / When / Then)
* **Given (前置背景)**：
  - 訂單狀態處於 `PENDING_PAYMENT`。
  - 查詢者為該訂單的合法 `owner`（飼主）或 `sitter`（保母）。
* **When (觸發事件)**：
  - 飼主或保母查詢訂單詳情。
  - 飼主提交轉帳後五碼、免責勾選及轉帳憑證圖片。
* **Then (預期行為)**：
  - 飼主與保母皆可於詳情中看到解密後的明文收款帳戶。
  - 格式不合規時（未勾免責或後五碼非5位數字）阻擋並回傳 400。
  - 參數合規時，上傳成功且狀態轉為 `PAID`，寫入日誌並異步觸發通知事件。

### 二、 測試步驟與多維度驗證
| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1.1 | 飼主呼叫 `GET /api/orders/{orderId}` 查詢詳情 | 畫面應正確顯示保母收款帳戶 | API Status: 200 OK<br/>JSON 包含解密的 `sitterPaymentInfo` |
| 1.2 | 保母呼叫 `GET /api/orders/{orderId}` 查詢詳情 | 畫面應正確顯示保母個人收款帳戶（驗證對稱查詢） | API Status: 200 OK<br/>JSON 包含解密的 `sitterPaymentInfo` |
| 2 | 飼主提交憑證，但免責勾選 `disclaimerAgreed = false` | 提示「必須同意線下交易免責聲明」 | API Status: 400 Bad Request<br/>DB 狀態仍為 `PENDING_PAYMENT` |
| 3 | 飼主提交憑證，但後五碼 `lastFive = "123a"` | 提示「後五碼必須為 5 位數字」 | API Status: 400 Bad Request<br/>正則驗證 `^\d{5}$` 阻擋成功 |
| 4 | 飼主提交合規參數與憑證圖片，傳送 `Idempotency-Key` | 提示「憑證已上傳，待保母確認」 | API Status: 200 OK<br/>DB `status` 轉為 `PAID`<br/>`orders` 寫入相關憑證與免責資訊<br/>`order_logs` 寫入 `PAYMENT_PROOF_SUBMITTED`<br/>**事件監聽驗證**：測試碼中以 Mockito verify 驗證 `NotificationListener.onPaymentProofSubmitted` 有被異步觸發呼叫 |

### 三、 邊界條件 / 例外場景 (Edge Cases)
* **檔案規格限制**：上傳之 MockMultipartFile 若大於 5MB，或 Content-Type 不為 `image/jpeg`、`image/png`、`image/webp` 時，API 應直接拒絕，回傳 400。

---

## TS-007-2: 保母確認與駁回憑證流程 (狀態機閉環)

### 一、 測試邏輯定義 (Given / When / Then)
* **Given (前置背景)**：
  - 訂單狀態為 `PAID`。
  - 操作者為訂單關聯之 `sitter`（保母）。
* **When (觸發事件)**：
  - 保母點擊確認入帳。
  - 保母駁回憑證，帶入拒絕原因。
* **Then (預期行為)**：
  - 確認入帳後狀態變更為 `CONFIRMED`，隱私過濾將銀行資訊置為 null，並觸發通知。
  - 駁回時若原因格式有誤（空字串或超過 500 字）阻擋並回傳 400。
  - 駁回成功後狀態退回 `PENDING_PAYMENT`，清空憑證欄位、免責勾選狀態及 `payment_idempotency_key` 以防重複提交卡死。

### 二、 測試步驟與多維度驗證
| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 保母呼叫 `POST /api/orders/{orderId}/verify-payment` | 訂單顯示已確認 | API Status: 200 OK<br/>DB `status` 轉為 `CONFIRMED`，`paid_at` 填入時間<br/>`order_logs` 寫入 `PAYMENT_VERIFIED`<br/>Mockito verify 驗證 `NotificationListener.onPaymentVerified` 被異步觸發 |
| 2 | 飼主在 `CONFIRMED` 狀態下呼叫 `GET /api/orders/{orderId}` | 訂單詳情中不再暴露保母收款資訊 | API Status: 200 OK<br/>**隱私安全防禦**：`sitterPaymentInfo` 強制置為 `null` 輸出 |
| 3.1 | 保母嘗試駁回憑證，但傳入 `rejectReason = ""` (空字串) 或長度 > 500 字 | 提示拒絕原因格式不合規 | API Status: 400 Bad Request<br/>驗證 Bean Validation 防呆 |
| 3.2 | 保母傳入合規原因並呼叫 `reject-payment` | 訂單退回待付款，且飼主收到退回通知 | API Status: 200 OK<br/>DB `status` 退回 `PENDING_PAYMENT`<br/>清空憑證欄位、免責狀態及 `payment_idempotency_key` 為 null<br/>`order_logs` 寫入 `PAYMENT_REJECTED`<br/>Mockito verify 驗證 `NotificationListener.onPaymentRejected` 被異步觸發 |

### 三、 邊界條件 / 例外場景 (Edge Cases)
* **GCS 實體圖片生命週期**：駁回時，GCS 實體檔案不受 Lifecycle 影響而不會被主動刪除，但在 DB 中切斷關聯（此部分為已知技術債），以留存財務舉證。

---

## TS-007-3: 零信任安全與 BOLA 越權防禦 (安全性測試)

> [!NOTE]
> **Accepted Risk / 測試分層決策**：本 BOLA 越權防禦安全性測試維持在後端整合測試層 ([PaymentControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/PaymentControllerTest.java)) 驗證，E2E 測試層不重複實作。這是刻意的測試分層設計（Security tests belong to API layer, not UI layer）。

### 一、 測試邏輯定義 (Given / When / Then)
* **Given (前置背景)**：
  - 訂單已建立，包含特定 `ownerId` 與 `sitterId`。
* **When (觸發事件)**：
  - 第三方使用者（飼主 C）嘗試查詢或操作此訂單。
  - 飼主 A 嘗試操作保母專屬 API（確認入帳或駁回憑證）。
* **Then (預期行為)**：
  - 系統嚴格攔截所有越權請求，一律回傳 403 Forbidden。

### 二、 測試步驟與多維度驗證
| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 第三方飼主 C 呼叫 `GET /api/orders/{orderId}` | 提示權限不足 | API Status: 403 Forbidden (BOLA 防護) |
| 2 | 第三方飼主 C 嘗試呼叫 `POST /api/orders/{orderId}/payment-proof` | 提示權限不足 | API Status: 403 Forbidden (BOLA 防護) |
| 3.1 | 訂單飼主 A 嘗試呼叫 `POST /api/orders/{orderId}/verify-payment` | 提示權限不足 | API Status: 403 Forbidden (SITTER 專屬 API) |
| 3.2 | 訂單飼主 A 嘗試呼叫 `POST /api/orders/{orderId}/reject-payment` | 提示權限不足 (對稱越權測試) | API Status: 403 Forbidden (SITTER 專屬 API) |

---

## TS-007-4: 併發上傳與冪等性防護 (Resilience)

> [!NOTE]
> **Accepted Risk / 測試分層決策**：本重複冪等防護測試維持在後端整合測試層 ([PaymentControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/PaymentControllerTest.java)) 驗證，E2E 測試層不重複實作。這是刻意的測試分層設計（Security & Integrity validation belongs to API layer, not UI layer）。

### 一、 測試邏輯定義 (Given / When / Then)
* **Given (前置背景)**：
  - 訂單狀態為 `PENDING_PAYMENT`。
* **When (觸發事件)**：
  - 飼主以相同的 `Idempotency-Key` 連續發送兩次上傳憑證請求。
* **Then (預期行為)**：
  - 第一個成功，第二個被資料庫部分唯一索引阻擋，回傳 409 Conflict。

### 二、 測試步驟與多維度驗證
| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 呼叫上傳 API 並傳送 `Idempotency-Key: key-abc` | 提示「憑證已上傳，待保母確認」 | API Status: 200 OK<br/>DB `status` 轉變為 `PAID` |
| 2 | 以相同 `Idempotency-Key` 再次發送上傳 | 提示「請求衝突，重複提交」 | API Status: 409 Conflict<br/>**DB 唯一性約束阻擋**：防止重複更新與日誌生成 |

---

## TS-007-5: 保母管理個人收款帳戶 (GET/PUT /api/sitter/payment-info)

### 一、 測試邏輯定義 (Given / When / Then)
* **Given (前置背景)**：
  - 系統中已有保母帳號，並有加密後的個人 profiles。
* **When (觸發事件)**：
  - 保母更新個人收款帳戶資訊（合規與不合規）。
  - 保母取得個人收款帳戶資訊。
  - 飼主越權嘗試更新或取得保母個人收款帳戶資訊。
* **Then (預期行為)**：
  - 保母可以正常更新與查詢解密明文，合規輸入時確認寫入 JSONB 加密。
  - 飼主嘗試呼叫時會被 403 阻擋。

### 二、 測試步驟與多維度驗證
| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術驗證指標 (Then - NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 保母提交合規帳戶資料至 `PUT /api/sitter/payment-info` | 提示「更新成功」 | API Status: 200 OK<br/>**資料庫加密寫入驗證**：查詢 DB profiles 表，`bank_account_info` 欄位為包含 `"ciphertext"` 的 JSONB 加密格式 |
| 2 | 保母呼叫 `GET /api/sitter/payment-info` | 畫面顯示解密後的明文個人收款帳戶 | API Status: 200 OK<br/>JSON 返回正確的解密欄位 (bankCode, bankBranch, bankAccount, bankPayeeName) |
| 3 | 飼主嘗試呼叫 `PUT /api/sitter/payment-info` 更新保母收款帳戶 | 提示權限不足 | API Status: 403 Forbidden<br/>BOLA 防護：阻擋 OWNER 身分更新保母收款資訊 |
| 4 | 飼主嘗試呼叫 `GET /api/sitter/payment-info` 查詢保母收款帳戶 | 提示權限不足 | API Status: 403 Forbidden<br/>BOLA 防護：阻擋 OWNER 身分讀取保母收款資訊 |

---

## 六、 附註 / 復原步驟

* **資料清理**：
  - 測試案例由 `@BeforeEach` 的 `deleteAll()` 提供完整的清空復原機制，維持本地測試容器的潔淨度。
* **環境相依**：
  - 需透過 PostgreSQL 16 Testcontainer 執行整合測試，確保 AES-256-GCM 資料庫加解密與 `JSONB` 欄位型態映射之正確性。
