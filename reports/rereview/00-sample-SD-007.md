# 抽樣深審：PRD-007 / SD-007 / TS-007 / 程式 / E2E（2026-11-21）

範圍：`docs/sa/fr/PRD-007-offline-payment.md`、`docs/sd/SD-007-offline-payment.md`、`docs/test-scenario/TS-007-offline-payment.md`、`PaymentService.java`、`OrderController.java`、`PaymentControllerTest.java`、`frontend/e2e/offline-payment.spec.ts`、`application.yml`、Cloud Run 環境變數（只讀名稱）。

## 已證實的缺陷

| # | 層 | 嚴重度 | 缺陷 | 證據 |
|---|----|--------|------|------|
| 1 | 部署/程式 | 阻擋 | 保母銀行帳號的 AES 金鑰在正式站用的是 repo 內寫死的預設值；Cloud Run 沒有 `APP_CRYPTO_KEY`，deploy.yml 也沒注入。拿到 repo 就能解密所有帳號 | `application.yml:34` `${APP_CRYPTO_KEY:MTIz...}`；`gcloud run services describe` env 清單無 APP_CRYPTO_KEY |
| 2 | 程式 | 重要 | `order_logs` 用 `REQUIRES_NEW` 寫：主交易若在 commit 時失敗（例如冪等唯一鍵衝突在 flush 才觸發），日誌已獨立提交，留下沒發生過的 `PAYMENT_PROOF_SUBMITTED` | `AuditLogService.java:44`；`PaymentService.java:146-154`（save 未 flush，log 先提交） |
| 3 | 程式/營運 | 重要 | 同一請求佔 2 條連線（主交易 + REQUIRES_NEW），prod pool 只有 3：3 個並行寫入請求可互相等待到 Hikari timeout | `application-prod.yml:9` `maximum-pool-size: 3` |
| 4 | 程式 | 重要 | GCS 上傳在 DB 交易內進行，上傳期間佔住連線（同 #3） | `PaymentService.java:126` 位於 `@Transactional` 方法內 |
| 5 | SD/程式 | 重要 | 「冪等」實作成重複 key 回 409，不是重播成功結果；網路逾時重試的客戶端會看到錯誤。`existsBy` 先查再寫有競態，兩個並行請求都會上傳 GCS | `PaymentService.java:118-123`；SD §6.2 |
| 6 | 程式 | 重要 | verify / reject 用 `findById` 不加鎖，Order 沒有 `@Version`；其他狀態轉移（ConfirmOrder、OwnerCancellation、Evaluation）都用 `findByIdForUpdate`，訂單狀態機的鎖策略不一致 | `PaymentService.java:168,226`；`OrderRepository.java:28-30` |
| 7 | 程式 | 建議 | 錯誤一律用 `IllegalArgumentException` / `IllegalStateException`，沒有錯誤碼；訂單不存在回 400 而非 404 | `PaymentService.java:76,86` |
| 8 | 程式 | 建議 | `updateSitterPaymentInfo` 找不到 profile 時自建一份（trustScore 100、UNVERIFIED），重複了 profile 建立邏輯 | `PaymentService.java:47-53` |
| 9 | PRD | 重要 | AC 只有 4 條且不可觀察（「順利」「正確變更」）；沒有錯誤碼、越權、檔案格式、免責、冪等、通知失敗；沒寫訂單變更補款（SD 從 PRD-016 帶進來，PRD-007 不知道）| PRD-007 §驗收標準 |
| 10 | PRD | 建議 | 「前端預先壓縮」與 SD「超過 5MB 拒絕」矛盾；「逾期未付（未來擴充）」與 README 的 72hr 殭屍清理是否涵蓋 PENDING_PAYMENT 不一致 | PRD-007 例外表；README SD-009 |
| 11 | PRD | 待決 | 保母可在飼主匯款前後隨時改收款帳號，訂單沒有快照；帳號被盜改時飼主會匯到錯的帳戶 | PRD-007 無規範；SD §1.2 即時解密 |
| 12 | SD | 重要 | 沒有錯誤碼表、沒有 AC 對應實作表；把 100 行 Converter 實作原始碼貼進 SD（文件即程式，必然過時） | SD-007 §3.3 |
| 13 | SD | 重要 | 一份 SD 混了三個關注點：付款憑證、保母收款帳號設定、新建 `GET /api/orders/{id}` 詳情端點（屬於訂單核心，不屬於付款） | SD-007 §2.4、§4.4–4.6 |
| 14 | TS | 重要 | 自動化路徑指向後端 Controller 測試而不是 E2E；用寫死的 UUID 種子資料；E2E spec 存在但 TS 不知道 | TS-007 表頭 |
| 15 | 後端測試 | 重要 | 沒有測試：冪等重送、並發 verify/reject、交易失敗時 order_logs 不應存在、補款路徑 | `PaymentControllerTest.java` 測試清單 |
| 16 | E2E | 重要 | 整支 spec 全 mock 後端（含 verify/reject），不用 `step()` 證據；只驗證前端對手寫 mock 的反應，mock 與真實 API 已有落差也不會發現 | `offline-payment.spec.ts:116-164` |

## 缺陷模式（回寫 reviewer 檢查清單用）

| 層 | 模式 |
|----|------|
| PRD | P1 AC 不可觀察；P2 例外表沒錯誤碼；P3 跨模組的流程（補款）只寫在下游 SD；P4 前後矛盾的處理方式並存；P5 沒有資料快照 / 竄改情境 |
| SD | S1 沒錯誤碼表與 AC 對應表；S2 貼實作原始碼；S3 一份 SD 混多個聚合；S4 同一實體的鎖策略各 SD 各寫各的；S5 「冪等」語意錯誤（回 409）；S6 外部 I/O 放在交易內 |
| TS | T1 自動化路徑不是 E2E；T2 寫死 UUID；T3 不知道既有 spec |
| 程式 | C1 REQUIRES_NEW 稽核；C2 狀態轉移未鎖；C3 泛用例外無錯誤碼；C4 secret 有預設值 |
| 測試 | X1 只測 happy path 與欄位驗證；X2 E2E 全 mock，不驗證契約 |

## 結論

PRD-007 的 AC 只有 4 條。依「> 50% AC 不符就重寫」的門檻判不出結果（AC 本身就太空），改以 SD 判斷：SD-007 的核心流程大致可用，但 #1–#6 屬於訂單核心的共通問題（稽核、鎖、冪等、交易邊界），應該在新的「訂單交易 SD」一次解決，不在 SD-007 局部修補。
