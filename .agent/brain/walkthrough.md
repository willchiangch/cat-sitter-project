# SD-007: 線下付款憑證與收款帳戶 實作成果報告

本成果報告總結了 SD-007「線下付款憑證上傳與確認」功能的開發與驗證成果。我們完成了資料庫擴充、JPA 安全加解密基礎設施、非同步事件通知機制、媒體服務整合、零信任 BOLA 身分與狀態機防禦，並撰寫了 17 個核心整合測試情境，已全數通過驗證。

---

## 1. 實作功能與規格摘要

1. **資料庫遷移與 Seed 預填 (Flyway)**：
   - 於 `orders` 表新增憑證路徑、轉帳後五碼、免責聲明記錄欄位，並加上 `payment_idempotency_key` 唯一約束索引。
   - 於 `profiles` 表新增加密儲存的 `bank_account_info` 欄位。
   - 預填測試保母的收款帳戶（預先在本地以同密鑰 AES 加密好）與測試飼主的 profiles 記錄。
2. **敏感財務帳戶加密 (JPA Converter & AES-256-GCM)**：
   - 實作 `BankAccountInfoCryptoConverter`（使用 Spring 建構子注入防範 NPE 且強制 UTF-8 編碼）。
   - 保母的收款帳戶資訊於寫入時由 GCM 模式自動加密、載入時自動解密，資料庫中僅留 Base64 密文，保護財務資料隱私。
   - 藉由 `@JdbcTypeCode(SqlTypes.JSON)` 解決 PostgreSQL `JSONB` 欄位型態綁定衝突。
3. **事件驅動通知 (Transactional Event Listener & Thread Pool)**：
   - 實作三項訂單付款事件及其異步監聽器 `NotificationListener.java`，掛載於 `AFTER_COMMIT` 階段非同步執行。
   - 於 `application.yml` 配置具備上限的 `ThreadPoolTaskExecutor` 執行緒池，保障高併發的線程安全性。
4. **BOLA 安全性與隱私過濾 (Zero-Trust)**：
   - API 身分全由 JWT Token 解析，拒絕透過 RequestParam 傳遞操作者 ID，防範越權漏洞。
   - 新建 `OrderQueryService` 進行 BOLA 身分查驗與欄位過濾：僅在待付款/待核對狀態且為關係人查詢時解密回傳 `sitterPaymentInfo`，其餘狀態均過濾為 `null`，且該欄位保證永遠輸出。
5. **上傳防呆與冪等性 (Idempotency)**：
   - 付款憑證上傳 API 限定檔案大小（<=5MB）、MIME 類型（jpg/png/webp）以及後五碼格式。
   - 上傳時強制要求 `Idempotency-Key`，由資料庫局部唯一索引防範併發重複提交。

---

## 2. 測試驗證與結果

我們於 `PaymentControllerTest.java` 實作了 17 個核心整合測試情境。藉由 Testcontainers 啟動真實的 PostgreSQL 容器執行，以保證與生產環境資料庫型態、索引之相容性。

### 測試結果輸出

```
[INFO] Running com.petsitter.interfaces.controller.PaymentControllerTest
...
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.664 s -- in TS-007: 線下付款憑證與收款帳戶測試
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

### 17 個整合測試情境說明
1. **保母更新收款帳戶成功**：測試 `PUT /api/sitter/payment-info` 能成功以明文接收、JPA 自動加密寫入 DB 並可正確讀回。
2. **保母更新收款帳戶欄位校驗失敗**：測試不符正規表示式（如 3 碼以外）的 `bankCode` 被 Bean Validation 自動拒絕並回傳 400。
3. **飼主正常上傳憑證成功**：測試飼主上傳 MultipartFile 憑證、帶入合規參數，狀態機成功轉變為 `PAID`。
4. **憑證上傳未勾免責聲明失敗**：測試 `disclaimerAgreed = false` 欄位被業務邏輯層攔截，回傳 400 並阻擋狀態變更。
5. **憑證上傳後五碼格式錯誤失敗**：測試後五碼不為 5 位純數字時被阻擋，回傳 400。
6. **非訂單飼主越權上傳攔截**：測試 BOLA 橫向越權防護，非訂單 owner 操作時拋出 `AccessDeniedException`，回應 403。
7. **保母確認入帳成功**：測試 `POST /api/orders/{orderId}/verify-payment` 成功將訂單變更為 `CONFIRMED`。
8. **保母駁回憑證重設測試**：測試駁回憑證後，狀態退回 `PENDING_PAYMENT`，清空 `paymentProofUrl` 等欄位，且 **`payment_idempotency_key` 重設為 null**、`disclaimer_agreed` 重設為 false，確認流程閉環無瑕疵。
9. **詳情查詢解密與 BOLA 測試**：測試在待付款狀態下，合法的訂單飼主查詢訂單詳情可看到解密後的明文 `sitterPaymentInfo`。
10. **銀行帳戶資訊狀態過濾測試**：測試當訂單已變更為已確認（`CONFIRMED`）時，即便保母有設定收款資訊，查詢詳情也無法取得，欄位值被強制過濾為 `null`，保證隱私安全。
11. **保母查詢收款帳戶成功**：測試 `GET /api/sitter/payment-info` 保母身分呼叫能讀取自己解密明文。
12. **飼主查詢保母個人收款帳戶阻擋**：測試 `GET /api/sitter/payment-info` 飼主身分呼叫時回傳 `403 Forbidden`（防護 BOLA）。
13. **飼主更新保母個人收款帳戶阻擋**：測試 `PUT /api/sitter/payment-info` 飼主身分呼叫時回傳 `403 Forbidden`（防護 BOLA）。
14. **飼主嘗試駁回憑證阻擋**：測試 `POST /api/orders/{orderId}/reject-payment` 飼主身分呼叫時回傳 `403 Forbidden`。
15. **保母駁回時原因格式校驗**：測試 `rejectReason` 為空字串或超過 500 字元時，Bean Validation 阻擋成功，回傳 `400 Bad Request`。
16. **保母查詢訂單亦可看到收款帳戶**：測試 `GET /api/orders/{orderId}` 當查詢者為訂單保母時，同樣能在合規狀態下讀取收款資訊（對稱檢索）。
17. **Idempotency-Key 長度超限攔截**：測試當 `Idempotency-Key` 超過 100 字元時，API 能主動拒絕並回傳 400（防範 DB 欄位截斷異常）。

---

## 3. 驗收標準對齊 (AC Alignment)

- [x] **AC-1 (線下上傳)**：已透過 `POST /api/orders/{orderId}/payment-proof` API 實作，且測試驗證上傳成功後狀態正確流轉為 `PAID`。
- [x] **AC-2 (保母確認)**：已透過 `POST /api/orders/{orderId}/verify-payment` API 實作，核對入帳後狀態正確變更為 `CONFIRMED`，且非同步發布事件發送通知。
- [x] **AC-3 (憑證無效退回)**：已透過 `POST /api/orders/{orderId}/reject-payment` API 實作，退回後狀態恢復為 `PENDING_PAYMENT`，清空相關憑證關聯與冪等 Key，且重設免責聲明狀態，容許再次提交。
- [x] **AC-4 (編輯保護)**：於 `submitPaymentProof` 等 API 內，實作了前置狀態校驗，僅在 `PENDING_PAYMENT` 狀態才允許憑證提交，已確認 (`CONFIRMED` 等) 之訂單將拋出異常拒絕變更。

---

## 4. 前端 E2E 測試與防死鎖修復

我們在 `frontend/e2e/offline-payment.spec.ts` 中實作並驗證了 4 個關鍵的 E2E 測試案例。在此過程中，我們發現 Playwright 測試框架在測試 React 同步彈出的 `alert`（如「請填寫退回原因」）時，會因為 JavaScript 同步阻塞事件循環，導致 `Promise.all([page.waitForEvent('dialog'), page.click(...)])` 產生死鎖並超時。

### 修復方案
我們將 E2E 測試中的 dialog 攔截寫法優化為符合 Playwright 官方最佳實踐的**非同步前置監聽**：
```typescript
page.once('dialog', async (dialog) => {
  expect(dialog.message()).toContain('請填寫退回原因');
  await dialog.accept();
});
await page.getByTestId('btn-submit-reject').click();
```
這項變更確保了 Dialog 處理常式在 `click()` 執行前就已註冊完成，避免了 click 阻塞超時，讓 E2E 測試能在 2.6 秒內高速且 100% 通過。

### 4 個前端 E2E 測試案例說明
1. **保母端收款帳戶設定流程 (`should manage sitter payment info settings successfully`)**
   - 進入收款設定頁面，驗證初始值加載。
   - 測試不合規防呆（銀行代碼非3碼），成功攔截並顯示錯訊。
   - 測試合規保存，驗證 API 更新與頁面成功提示。
2. **飼主端付款憑證上傳與防呆 (`should display bank info and allow owner to upload proof with validations`)**
   - 進入訂單詳情，確認正確讀取並顯示保母收款資訊。
   - 測試「未勾選免責聲明」與「轉帳後五碼非5位數」的前端表單驗證攔截。
   - 測試合規上傳圖片，驗證狀態正確流轉為 `PAID` 且顯示已付款憑證與後五碼。
3. **保母端憑證審核與駁回流轉 (`should allow sitter to verify and reject payment proof`)**
   - 進入保母訂單管理「進行中」頁籤，驗證憑證核對面板與圖片預覽。
   - 測試異常駁回（未寫理由）攔截與同步 alert。
   - 測試正常駁回理由提交，驗證訂單狀態退回 `PENDING_PAYMENT` 且清空憑證資訊。
   - 測試正常確認入帳，驗證狀態流轉為 `CONFIRMED`。
4. **安全隱私過濾驗證 (`should hide bank info to owner when status is CONFIRMED`)**
   - 驗證當訂單為 `CONFIRMED`（已確認入帳）後，飼主端詳情頁面將不再暴露保母的敏感銀行帳戶資訊（`bank-info-container` 不可見）。
