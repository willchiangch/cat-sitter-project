# PRD-007: 線下付款憑證上傳與核對

| 項目 | 內容 |
|------|------|
| 狀態 | In Review |
| 建立日期 | 2026-05-09（2026-11-21 依 DOMAIN-BASELINE 重寫） |
| 歸屬 SD | SD-ORDER（`docs/sd/00-SD-PLAN.md` §0） |
| 測試情境 | TS-007 |
| 相依 PRD | PRD-006（O2 報價時建立首次收款 payment 並寫 `due_at` / `escalate_at` / 收款帳號快照；O7 保母取消）；PRD-005（O6 飼主撤回）；PRD-016（補款 / 退款 payment 的建立時機、VERIFIED 後的變更結清、O14 爭議）；PRD-025（保母收款帳號設定，快照來源）；PRD-014（通知範本與投遞）；PRD-020（ADMIN 後台入口畫面） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1（角色、`ROLE_MISMATCH`）、§1.2（收款帳號 / 憑證可讀範圍）、§2.1–§2.2（線下付款四級皆可、訂閱鎖定不擋核款、月額度計數）、§3.1（`PENDING_PAYMENT` / `DISPUTED` 凍結）、§3.2（O5、O6、O7、O8、O16、期限以時間判斷）、§3.3（payments / submissions 狀態機、方向表、待核對升級）、§3.6（鎖、版本、稽核、通知 outbox、冪等、外部 I/O、排程、`admin_tasks`）、§3.7（錯誤碼）、§4（TWD 整數）、§5（首日、`due_at`、`escalate_at`、付款提醒、`URGENT_WINDOW_HOURS`、排程頻率、憑證永久保存） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念（角色、SaaS 額度、訂單狀態、時間起算點）只能**引用** DOMAIN-BASELINE，不得在這裡重新定義；要改就改 baseline。

---

## 1. 背景與目的

MVP 只支援線下轉帳（BR §3.2、§5.6.1）。每一筆應收或應退的款項是一筆 payment，每次上傳憑證是一筆 submission（baseline §3.3）。本卡定義**所有方向**（首次付款、變更補款、變更退款）共通的「付款方上傳憑證 → 收款方確認 / 駁回」行為、憑證檢視權限，以及首次付款特有的付款頁、期限、提醒、逾期取消、待核對升級與 ADMIN 作廢。補款 / 退款**何時建立、確認後變更如何結清**在 PRD-016；保母收款帳號的設定在 PRD-025。

## 2. 使用者故事

- **US-1** 身為飼主，我希望付款頁顯示本次應付金額與保母在報價當下的收款帳號，以便匯到正確帳戶且日後能舉證。
- **US-2** 身為付款方（首次 / 補款為飼主、退款為保母），我希望上傳轉帳截圖與後五碼，讓對方核對。
- **US-3** 身為收款方，我希望檢視憑證後確認或駁回（附原因），確認後訂單或變更才往下走。
- **US-4** 身為飼主，我希望在付款期限前收到提醒，並清楚知道逾期會被取消。
- **US-5** 身為 ADMIN，我希望在首次付款長時間待核對時，能作廢該筆付款並取消訂單，讓雙方自行處理款項；但我不能代替保母確認收款。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 檢視 CHARGE 付款頁（`amount`、收款帳號快照、`due_at`） | 本人訂單（付款方） | 本人訂單（收款方，看自己的快照） | 可讀訂單範圍內（§1.2；快照是否可讀見 CQ-12） | ✗ 401 | 無 |
| 檢視 REFUND 付款頁（`amount`，無帳號） | 本人訂單（收款方） | 本人訂單（付款方） | 可讀訂單範圍內 | ✗ 401 | 無 |
| 上傳 CHARGE 憑證 | 本人訂單 | ✗ 403 `FORBIDDEN` | ✗ 403 `ROLE_MISMATCH` | ✗ 401 | 無（線下付款四級皆可，SAAS「金流與對帳」） |
| 上傳 `MODIFICATION_REFUND` 憑證 | ✗ 403 `FORBIDDEN` | 本人訂單 | ✗ 403 `ROLE_MISMATCH` | ✗ 401 | 無 |
| 確認 / 駁回 CHARGE | ✗ 403 `FORBIDDEN` | 本人訂單 | ✗ 403 `ROLE_MISMATCH`（不能代為確認，CQ-43） | ✗ 401 | 無；訂閱鎖定 / 停權不擋（§2.2） |
| 確認 `MODIFICATION_REFUND` | 本人訂單 | ✗ 403 `FORBIDDEN` | ✗ 403 `ROLE_MISMATCH` | ✗ 401 | 無 |
| 駁回 `MODIFICATION_REFUND` | ✗ 403 `FORBIDDEN`（只能確認，異議走 O14） | ✗ 403 `FORBIDDEN` | ✗ | ✗ 401 | 無 |
| 檢視憑證與 submission 歷史 | 本人訂單 | 本人訂單 | 可讀訂單範圍內（`DISPUTED` 或有 OPEN 待辦） | ✗ 401 | 無 |
| 作廢首次付款並取消訂單（O16） | ✗ | ✗ | 僅限該訂單有 OPEN 的首次付款待核對待辦 | ✗ 401 | 無 |

- 「本人訂單」以 token 身分判斷；存在但不屬於呼叫者 → 403 `FORBIDDEN`；不存在 → 404 `NOT_FOUND`；token `currentRole` 與端點要求不符 → 403 `ROLE_MISMATCH`（§1.1、§1.2）。
- 是訂單當事人但不是該 payment 的付款方（或收款方）而呼叫對應動作 → 403 `FORBIDDEN`（依 §3.3 方向表判斷，見 CQ-9）。

## 4. 主流程

### 4.1 付款頁與上傳憑證（所有方向共通）

1. 付款方進入訂單詳情的付款區塊。系統依 payment 顯示：
   - 共通：`amount`（只顯示這一個數字，不顯示訂單總額或差額公式，§3.3）、payment 狀態、submission 歷史。
   - CHARGE（`INITIAL` / `SUPPLEMENT`）：收款帳號快照（銀行代碼、分行、帳號、戶名）、`due_at`（Asia/Taipei `YYYY-MM-DD HH:mm`）。
   - REFUND（`MODIFICATION_REFUND`）：不顯示任何帳號，顯示固定文案「退款帳號請與飼主自行聯繫」（CQ-54）。
   - `method=ADMIN_ADJUSTMENT`：建立即 `VERIFIED`，不顯示上傳區（§3.3）。
2. payment 為 `UNPAID` 時顯示上傳表單：免責聲明核取方塊、後五碼、單一圖片檔。未勾選免責時送出按鈕停用。
3. 前端送出前檢查格式與大小，不符直接顯示錯誤、不送出、**不做壓縮**（BR-5）。
4. 付款方送出（帶 `Idempotency-Key`）。系統：
   1. 驗證欄位與檔案（交易外）。
   2. 上傳檔案至 GCS 私有物件（交易外，§3.6 外部 I/O）。
   3. 開交易、鎖訂單列（§3.6），重驗：呼叫者為付款方、訂單非 `DISPUTED`、payment 為 `UNPAID`、`now < due_at`（有 `due_at` 時）。
   4. 建立 submission（`SUBMITTED`，含後五碼、免責同意時間、檔案物件鍵），payment `UNPAID → SUBMITTED`，寫 `order_logs`，`enqueue` 通知收款方，提交。
   5. 交易失敗 → 刪除步驟 2 的物件（孤兒檔由清除排程兜底）。
5. 訂單主狀態不變（首次付款時仍為 `PENDING_PAYMENT`，CQ-29）。

### 4.2 收款方確認 / 駁回（所有方向共通）

1. 收款方在訂單詳情檢視目前 `SUBMITTED` 的 submission（後五碼、憑證圖片、上傳時間）。
2. **確認**（帶 `submissionId`、`Idempotency-Key`）：鎖訂單列，重驗 payment 為 `SUBMITTED` 且 `submissionId` 為目前那筆 → submission `ACCEPTED`、payment `VERIFIED`、寫 `order_logs`、通知付款方；依 `purpose` 連動：
   - `INITIAL`：O5，訂單 `PENDING_PAYMENT → CONFIRMED`、月額度 +1、相關 OPEN ADMIN 待辦 → RESOLVED、通知雙方「訂單啟動成功」。不檢查日期（O5）。
   - `SUPPLEMENT` / `MODIFICATION_REFUND`：訂單主狀態不變；變更請求結清見 PRD-016。
3. **駁回**（僅 CHARGE；帶 `submissionId`、原因、`Idempotency-Key`）：鎖訂單列，重驗同上 → submission `REJECTED`（保存原因）、payment `SUBMITTED → UNPAID`、寫 `order_logs`、通知付款方（含原因）。付款方可再上傳，產生新 submission；舊 submission 不清空（§3.3）。
4. `MODIFICATION_REFUND` 的收款方（OWNER）只有「確認收到退款」，畫面不提供駁回；有異議時顯示「如有異議，請申報款項爭議」入口（O14，見 PRD-016）。

### 4.3 憑證檢視

1. 當事人或可讀範圍內的 ADMIN 讀取 submission 列表：每筆含狀態、後五碼、上傳時間、駁回原因（若有）、免責同意時間。
2. 檢視圖片時，後端每次授權後產生**短效簽名網址**（有效期見 CQ-5）；API 回應與冪等回放 body 都不保存簽名網址（§3.6）。過期後前端重新請求。
3. 憑證永久保存，不在媒體保留排程範圍（§5）。

### 4.4 首次付款期限、提醒、逾期、升級（`purpose=INITIAL`）

1. `due_at`、`escalate_at` 在 O2 報價建立 payment 時寫入（PRD-006），公式見 baseline §5，本卡只消費。
2. **付款提醒**：排程（每 15 分鐘）找出 `now ≥ 首日 − 48h` 且 payment 為 `UNPAID`、尚未提醒者 → 通知 OWNER（一次）。報價當下已過提醒點者，由報價通知一併提醒（O2，PRD-006），排程不再補發。
3. **逾期取消（O8）**：排程找出 `now ≥ due_at` 且 payment 為 `UNPAID` → 鎖訂單、重驗 → payment `VOID`、訂單 `PENDING_PAYMENT → CANCELLED`（`cancelledBy=SYSTEM`）、寫 `order_logs`、通知雙方。
4. 期限後、排程未跑前，飼主上傳一律 409 `DEADLINE_PASSED`（payload `deadline=due_at`），以時間判斷（§3.2，CQ-46）。
5. `SUBMITTED` 不因 `due_at` 取消（§3.3）。
6. **待核對升級**：排程找出 `now ≥ escalate_at` 且 payment 仍 `SUBMITTED` → 建立 OPEN 的「首次付款待核對」ADMIN 待辦（`(type, order_id)` 唯一），通知雙方。之後保母仍可確認（O5，待辦自動 RESOLVED）。
7. **ADMIN 作廢（O16）**：ADMIN 在後台待辦列表（入口畫面屬 PRD-020）開啟訂單，檢視憑證，填原因後「作廢並取消訂單」→ 鎖訂單、重驗 payment `SUBMITTED` 且待辦 OPEN → payment `VOID`、訂單 `CANCELLED`（`cancelledBy=ADMIN`，記原因）、待辦 RESOLVED、寫 `order_logs`、通知雙方「平台已作廢此筆付款，款項請由雙方自行處理」。ADMIN 沒有確認收款的動作。

## 5. 狀態轉移

> 完整狀態機在 DOMAIN-BASELINE §3.2–§3.3；本表只列本功能造成的轉移。

| # | 實體 | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|------|----|----|-------|---------|-------------|
| P1 | payment | `UNPAID` | `SUBMITTED` | 付款方（§3.3 方向表） | 訂單非 `DISPUTED`；有 `due_at` 時 `now < due_at`；無其他 `SUBMITTED` submission；欄位與檔案有效 | 409 `PAYMENT_STATE_CONFLICT` / `ORDER_STATE_CONFLICT` / `DEADLINE_PASSED`；400 `VALIDATION_FAILED`；422 `PROOF_FILE_TYPE_UNSUPPORTED` / `PROOF_FILE_TOO_LARGE` |
| P2 | payment | `SUBMITTED` | `VERIFIED` | 收款方 | `submissionId` 為目前 `SUBMITTED` 那筆；訂單非 `DISPUTED`；不檢查日期 | 409 `PAYMENT_STATE_CONFLICT` / `ORDER_STATE_CONFLICT` |
| P3 | payment | `SUBMITTED` | `UNPAID` | 收款方（僅 CHARGE） | 同 P2；附原因 | 409 同 P2；400 `VALIDATION_FAILED`；403 `FORBIDDEN`（REFUND） |
| P4 | payment | `UNPAID` | `VOID` | SYSTEM（O8） | `purpose=INITIAL`；`now ≥ due_at` | —（排程跳過） |
| P5 | payment | `SUBMITTED` | `VOID` | ADMIN（O16） | `purpose=INITIAL`；有 OPEN 首次付款待核對待辦 | 409 `PAYMENT_STATE_CONFLICT` |
| S1 | submission | — | `SUBMITTED` | 付款方 | 同 P1 | 同 P1 |
| S2 | submission | `SUBMITTED` | `ACCEPTED` | 收款方 | 同 P2 | 同 P2 |
| S3 | submission | `SUBMITTED` | `REJECTED` | 收款方 | 同 P3 | 同 P3 |
| O5 | order | `PENDING_PAYMENT` | `CONFIRMED` | SITTER | P2 且 `purpose=INITIAL` | 409 `PAYMENT_STATE_CONFLICT` |
| O8 | order | `PENDING_PAYMENT` | `CANCELLED` | SYSTEM | P4 | — |
| O16 | order | `PENDING_PAYMENT` | `CANCELLED` | ADMIN | P5 | 409 `PAYMENT_STATE_CONFLICT` |
| A1 | admin_task | — | `OPEN` | SYSTEM | `now ≥ escalate_at` 且 INITIAL payment `SUBMITTED`；同訂單無 OPEN 同型待辦 | —（唯一約束，重跑不重建） |
| A2 | admin_task | `OPEN` | `RESOLVED` | SITTER（O5）/ ADMIN（O16）/ SITTER 駁回（CQ-8） | 與觸發轉移同交易 | — |

O6（飼主撤回）見 PRD-005、O7（保母取消）見 PRD-006，兩者把 `UNPAID` payment 改 `VOID`，`SUBMITTED` 時回 409 `PAYMENT_ALREADY_SUBMITTED`。

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 付款頁與核對畫面只顯示 payment 的 `amount`（正整數 TWD），不顯示訂單總額、差額公式 | baseline §3.3、§4 |
| BR-2 | CHARGE 付款頁顯示的帳號一律是該 payment 建立時的收款帳號快照；保母之後修改收款帳號不影響既有 `UNPAID` / `SUBMITTED` payment；REFUND 不存、不顯示飼主帳號，畫面提示雙方自行聯繫 | baseline §1.2、§3.3、CQ-23、CQ-54 |
| BR-3 | 每次上傳必填：免責聲明勾選（記錄同意時間於 submission）、後五碼（`^[0-9]{5}$`）、恰好 1 個圖片檔。免責文案：「我瞭解平台不代管此筆線下款項，此筆交易風險由飼主與保母雙方自行承擔」 | 沿用原 PRD-007（免責與後五碼）；`00-PRD-PLAN.md` PRD-007「強制線下交易免責聲明」；退款方向是否同樣適用見 CQ-3、CQ-4 |
| BR-4 | 檔案格式限 JPEG / PNG / WebP | CQ-1 |
| BR-5 | 單檔上限 5 MB；超過一律拒絕，前端送出前先擋並顯示「憑證圖片需小於 5MB」，**前端不壓縮**（取代原 PRD-007「前端預先壓縮」） | 沿用原 PRD-007 數值；處理方式見 CQ-2 |
| BR-6 | 同一 payment 同時最多一筆 `SUBMITTED` submission；駁回不清空，submission 歷史完整保留並對可讀者可見 | baseline §3.3 |
| BR-7 | 確認 / 駁回須帶目前 `SUBMITTED` 的 `submissionId`，不符 → 409 `PAYMENT_STATE_CONFLICT`（畫面過期以關鍵值比對） | baseline §3.6「版本」 |
| BR-8 | 駁回必填原因（長度見 CQ-6）；只有 CHARGE 可駁回；`MODIFICATION_REFUND` 的 OWNER 只能確認，異議走 O14 | baseline §3.3、O14 |
| BR-9 | 首次付款確認（O5）：不檢查日期；月額度 +1（即使當下已達上限，在流程中的訂單照常）；關閉相關 OPEN 待辦；通知雙方「訂單啟動成功」 | baseline O5、§2.2、BR §3.6 |
| BR-10 | 訂閱逾期 / 停權不擋上傳、確認、駁回（屬既有訂單） | baseline §2.2、CQ-19 |
| BR-11 | 訂單 `DISPUTED` 期間，付款相關所有使用者操作 → 409 `ORDER_STATE_CONFLICT` | baseline §3.2、CQ-48 |
| BR-12 | 有 `due_at` 的 payment（`INITIAL`、`SUPPLEMENT`）在 `now ≥ due_at` 時上傳 → 409 `DEADLINE_PASSED`，即使排程尚未把狀態寫成終態；REFUND 無 `due_at`，不受此限 | baseline §3.2、§3.3、CQ-46 |
| BR-13 | 付款提醒：首日 − 48h，對 `UNPAID` 的 INITIAL payment 通知 OWNER 一次；報價時已過提醒點則由報價通知一併提醒，不另發 | baseline §5、BR §3.2；條件與管道見 CQ-10 |
| BR-14 | 逾期取消 O8：`now ≥ due_at` 且 `UNPAID`；排程每 15 分鐘，以 `now ≥ due_at` 判斷；`SUBMITTED` 不取消 | baseline O8、§3.3、§5 |
| BR-15 | 待核對升級：`now ≥ escalate_at` 且仍 `SUBMITTED` → 建 ADMIN 待辦並通知雙方；建立後保母仍可確認 | baseline §3.3、§5、CQ-43、CQ-44 |
| BR-16 | ADMIN 對首次付款只能作廢並取消（O16），不能確認收款；作廢需原因 | baseline O16、§3.1（取消記錄原因）、CQ-43 |
| BR-17 | 憑證檔為私有物件，只透過授權後產生的短效簽名網址檢視；簽名網址不寫入冪等回放 body；憑證永久保存 | baseline §1.2、§3.6、§5；有效期見 CQ-5 |
| BR-18 | 上傳、確認、駁回、作廢必帶 `Idempotency-Key`，行為依 baseline §3.6（multipart hash 含檔案 SHA-256）；缺少 → 400 `VALIDATION_FAILED` | baseline §3.6 |
| BR-19 | GCS 上傳在交易外、先於交易；交易失敗刪除已上傳物件；Email 透過 `notifications` outbox，寄送失敗不回滾狀態轉移 | baseline §3.6 |
| BR-20 | 服務執行（PRD-008）在訂單 `CONFIRMED` 或 `IN_PROGRESS` 時開放（取代原 PRD-007「只有 CONFIRMED」） | baseline §3.1 |
| BR-21 | 時間 UTC 儲存、ISO-8601 傳輸；`due_at` 等期限在畫面以 Asia/Taipei `YYYY-MM-DD HH:mm` 顯示 | baseline §5 |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 非訂單當事人讀付款頁 / 憑證，或對他人訂單操作（BOLA） | 拒絕，不改資料 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | 訂單 / payment / submission 不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此訂單」 |
| EX-3 | 當事人但非該 payment 付款方而上傳；非收款方而確認 / 駁回；OWNER 駁回退款 | 拒絕 | 403 `FORBIDDEN` | 「此操作不屬於您的角色」 |
| EX-4 | token `currentRole` 與端點不符（含 ADMIN 呼叫確認收款） | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換至正確角色」 |
| EX-5 | 未登入 | 拒絕 | 401 `UNAUTHENTICATED` | 導向登入 |
| EX-6 | 未勾免責、後五碼不是 5 位數字、缺檔案、多於 1 檔、缺 `Idempotency-Key`、駁回 / 作廢缺原因 | 拒絕，payment 不變 | 400 `VALIDATION_FAILED` | 欄位下方錯誤訊息 |
| EX-7 | 檔案格式不支援 | 拒絕，不上傳 GCS | 422 `PROOF_FILE_TYPE_UNSUPPORTED`（模組專屬） | 「僅支援 JPG、PNG、WebP」 |
| EX-8 | 檔案 > 5 MB | 前端送出前擋；後端仍驗證並拒絕，不上傳 GCS | 422 `PROOF_FILE_TOO_LARGE`（模組專屬） | 「憑證圖片需小於 5MB」 |
| EX-9 | payment 不是 `UNPAID` 時上傳（已 `SUBMITTED` / `VERIFIED` / `VOID`，含訂單已取消） | 拒絕 | 409 `PAYMENT_STATE_CONFLICT` | 「付款狀態已變更，請重新整理」 |
| EX-10 | payment 不是 `SUBMITTED`，或 `submissionId` 不是目前那筆時確認 / 駁回 | 拒絕 | 409 `PAYMENT_STATE_CONFLICT` | 同上 |
| EX-11 | 訂單 `DISPUTED` 時任何付款操作 | 拒絕 | 409 `ORDER_STATE_CONFLICT` | 「訂單爭議處理中，暫停付款操作」 |
| EX-12 | `now ≥ due_at` 後上傳（排程尚未取消） | 拒絕 | 409 `DEADLINE_PASSED`（payload `deadline=due_at`） | 「已超過付款期限」 |
| EX-13 | 重複送出：同 key 同內容 | 回放第一次的狀態碼與 body（不含簽名網址），不新增 submission、不重複上傳 GCS | 同第一次（例 201） | 同第一次 |
| EX-14 | 同 key 不同內容 | 拒絕 | 422 `IDEMPOTENCY_KEY_REUSED` | 「請重新送出」 |
| EX-15 | 同 key 第一次仍處理中 | 拒絕 | 409 `IDEMPOTENCY_IN_PROGRESS` | 「處理中，請稍候」 |
| EX-16 | 不同 key 連續上傳（第一筆已成功） | 第二筆依 EX-9 拒絕 | 409 `PAYMENT_STATE_CONFLICT` | 「付款狀態已變更，請重新整理」 |
| EX-17 | 訂單鎖等待 > 3 秒 | 拒絕，不記冪等結果，可重試 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「系統忙碌，請稍後再試」 |
| EX-18 | 跨角色競爭：飼主上傳 vs 保母取消（O7）/ 飼主撤回 vs 保母確認不適用（撤回需 UNPAID）；保母確認 vs ADMIN 作廢 | 訂單列鎖序列化，只有先取得鎖者成功；後到者重驗失敗 | 先上傳後取消：409 `PAYMENT_ALREADY_SUBMITTED`（PRD-006）；先取消後上傳：409 `PAYMENT_STATE_CONFLICT`；確認 / 作廢後到者：409 `PAYMENT_STATE_CONFLICT` | 「訂單狀態已變更，請重新整理」 |
| EX-19 | GCS 上傳失敗 | 不開交易，payment 維持 `UNPAID`，無 submission；不記冪等結果 | 503 `STORAGE_UNAVAILABLE`（模組專屬） | 「上傳失敗，請重試」；同 key 重試可成功 |
| EX-20 | GCS 上傳成功但交易失敗 | 回滾並刪除剛上傳的物件；刪除失敗由孤兒檔排程清除 | 依失敗原因（409 / 5xx） | 依錯誤碼 |
| EX-21 | 產生簽名網址失敗 | 不影響任何狀態 | 503 `STORAGE_UNAVAILABLE` | 「圖片暫時無法載入」 |
| EX-22 | Resend 寄送失敗 | 狀態轉移已提交；站內通知已寫入；Email 由 outbox 排程重試最多 5 次，4xx 直接判失敗 | 主操作照常 2xx | 站內通知可見 |
| EX-23 | SaaS 方案不足 | 不適用：線下付款四級皆可（SAAS）；訂閱鎖定 / 停權不擋核款（§2.2） | — | — |
| EX-24 | 樂觀鎖衝突 | 不接受客戶端 version；以列鎖 + `submissionId` 比對處理（EX-10、EX-17）；`@Version` 為最後防線，觸發時視同 EX-17 | 409 `ORDER_BUSY` | 同 EX-17 |
| EX-25 | ADMIN 作廢通過授權檢查後，取得訂單鎖重驗時 payment 已非 `SUBMITTED` 或待辦已非 OPEN（與保母確認競爭） | 拒絕 | 409 `PAYMENT_STATE_CONFLICT` | 「此訂單目前不可作廢」 |
| EX-26 | ADMIN 讀取不在可讀範圍的訂單（非 `DISPUTED`、無 OPEN 待辦） | 拒絕 | 403 `FORBIDDEN` | 「無權限檢視此訂單」 |

## 8. 驗收標準（AC）

> 範例日期：今天 2026-11-21；一般單首個行程 2026-11-25，首日 = 2026-11-25 00:00 Asia/Taipei（= 2026-11-24T16:00:00Z），2026-11-21 10:00 報價 → `due_at` = 2026-11-23T16:00:00Z（台北 11/24 00:00）、`escalate_at` = 2026-11-24T18:00:00Z（台北 11/25 02:00）、提醒點 = 台北 11/23 00:00。

### 子模組 1：付款頁與上傳憑證（付款方，所有方向）

- [ ] **AC-1.1 (CHARGE 付款頁與帳號快照)**：Given 訂單 `PENDING_PAYMENT`、INITIAL payment `UNPAID`、`amount=3000`、快照帳號 `812-0001-12345678901-王小明`，且保母其後已把收款帳號改為另一組，When 該訂單飼主開啟付款頁，Then `payment-amount` 顯示「3,000」、`payment-account-snapshot` 顯示快照帳號（非新帳號）、`payment-due-at` 顯示「2026-11-24 00:00」；畫面不出現訂單總額或差額公式。對應：BR-1、BR-2、BR-21
- [ ] **AC-1.2 (REFUND 付款頁)**：Given `MODIFICATION_REFUND` payment `UNPAID`、`amount=1200`，When 保母開啟退款頁，Then 顯示「1,200」與文案「退款帳號請與飼主自行聯繫」，不出現任何帳號欄位、不出現 `payment-due-at`；Given `method=ADMIN_ADJUSTMENT` 的退款，Then 狀態顯示 `VERIFIED` 且無上傳表單。對應：BR-1、BR-2
- [ ] **AC-1.3 (讀取與上傳越權)**：Given 訂單 A 屬於飼主 O1 / 保母 S1，When 飼主 O2 讀 A 的付款頁或上傳憑證，Then 403 `FORBIDDEN`；When S1 對 A 的 CHARGE payment 上傳，Then 403 `FORBIDDEN`；When O1 對 A 的 REFUND payment 上傳，Then 403 `FORBIDDEN`；When 不存在的訂單 ID，Then 404 `NOT_FOUND`；以上 payment 狀態皆不變。對應：EX-1、EX-2、EX-3
- [ ] **AC-1.4 (上傳成功)**：Given INITIAL payment `UNPAID` 且 `now < due_at`，When 飼主勾選 `proof-disclaimer-checkbox`、輸入後五碼「12345」、選 2 MB PNG 並送出，Then 201；payment `UNPAID → SUBMITTED`；新增 1 筆 submission `SUBMITTED`（後五碼 12345、免責同意時間 = 送出時間）；訂單仍為 `PENDING_PAYMENT`；`order_logs` 新增 1 筆上傳紀錄；保母收到站內通知 1 則並排入 Email 1 封。對應：P1、S1、BR-3、BR-6
- [ ] **AC-1.5 (欄位與檔案驗證)**：Given payment `UNPAID`，When 未勾免責，Then `proof-submit-btn` 為停用，直接呼叫 API 回 400 `VALIDATION_FAILED`；When 後五碼「1234」或「12a45」，Then 400 `VALIDATION_FAILED`；When 上傳 GIF，Then 422 `PROOF_FILE_TYPE_UNSUPPORTED`；When 選 6 MB JPEG，Then 前端顯示「憑證圖片需小於 5MB」且不發出請求，直接呼叫 API 回 422 `PROOF_FILE_TOO_LARGE`；以上 payment 皆仍 `UNPAID`、GCS 無新物件。對應：BR-3、BR-4、BR-5、EX-6–EX-8
- [ ] **AC-1.6 (冪等)**：Given 飼主以 key K 上傳成功（201），When 以 K 與相同欄位、相同檔案重送，Then 回 201 與第一次相同的 body（不含簽名網址），submission 仍只有 1 筆、GCS 物件仍只有 1 個；When 以 K 改傳不同檔案，Then 422 `IDEMPOTENCY_KEY_REUSED`；When 第一次仍處理中又以 K 送出，Then 409 `IDEMPOTENCY_IN_PROGRESS`；When 以新 key K2 再送，Then 409 `PAYMENT_STATE_CONFLICT`。對應：BR-18、EX-13–EX-16
- [ ] **AC-1.7 (狀態與期限不允許)**：Given payment 為 `SUBMITTED` / `VERIFIED` / `VOID`，When 付款方上傳，Then 409 `PAYMENT_STATE_CONFLICT`；Given 訂單 `DISPUTED` 且 payment `UNPAID`，Then 409 `ORDER_STATE_CONFLICT`；Given INITIAL payment `UNPAID`、`now = due_at + 1 分鐘` 且 O8 排程尚未執行，Then 409 `DEADLINE_PASSED`、payload `deadline` = `due_at`；Given 訂單 `CONFIRMED`，Then 付款區塊不顯示上傳表單。對應：BR-11、BR-12、EX-9、EX-11、EX-12
- [ ] **AC-1.8 (GCS 失敗與跨角色競爭)**：Given GCS 回 5xx，When 飼主上傳，Then 503 `STORAGE_UNAVAILABLE`、payment 仍 `UNPAID`、無 submission，且以同 key 重試在 GCS 恢復後回 201；Given INITIAL payment `UNPAID`，When 飼主上傳與保母取消（O7）同時送出，Then 恰好一方成功：上傳先成功時取消回 409 `PAYMENT_ALREADY_SUBMITTED`，取消先成功時上傳回 409 `PAYMENT_STATE_CONFLICT` 且 GCS 物件被刪除。對應：BR-19、EX-18–EX-20

### 子模組 2：收款方確認 / 駁回與憑證檢視（所有方向）

- [ ] **AC-2.1 (首次付款確認 → 訂單成立)**：Given 訂單 `PENDING_PAYMENT`、INITIAL payment `SUBMITTED`（submission X），保母本月已達接單上限且訂閱已鎖定，When 保母以 `submissionId=X` 按 `btn-verify-payment`，Then 200；X `ACCEPTED`；payment `VERIFIED`；訂單 `PENDING_PAYMENT → CONFIRMED`；保母當月 CONFIRMED 計數 +1；`order_logs` 新增確認紀錄；雙方各收到「訂單啟動成功」站內通知 1 則並排入 Email。`now ≥ due_at` 時結果相同。對應：P2、S2、O5、BR-9、BR-10
- [ ] **AC-2.2 (補款 / 退款確認)**：Given 訂單 `IN_PROGRESS`、`SUPPLEMENT` payment `SUBMITTED`，When 保母確認，Then payment `VERIFIED`、訂單仍為 `IN_PROGRESS`；Given `MODIFICATION_REFUND` payment `SUBMITTED`，When 飼主按「確認收到退款」，Then payment `VERIFIED`、訂單主狀態不變；變更請求後續狀態依 PRD-016。對應：P2、S2
- [ ] **AC-2.3 (駁回與重新上傳)**：Given CHARGE payment `SUBMITTED`（submission X），When 保母按 `btn-reject-payment` 並填原因「金額不符」，Then 200；X `REJECTED` 且保存原因；payment `SUBMITTED → UNPAID`；訂單主狀態不變；飼主收到含「金額不符」的通知；When 飼主再上傳，Then 新 submission Y `SUBMITTED`，`submission-history` 依時間列出 Y 與 X（X 顯示「已駁回：金額不符」）。When 原因空白，Then 400 `VALIDATION_FAILED`。對應：P3、S3、BR-6、BR-8
- [ ] **AC-2.4 (退款不可駁回)**：Given `MODIFICATION_REFUND` payment `SUBMITTED`，When 飼主開啟退款頁，Then 只有「確認收到退款」按鈕與「如有異議，請申報款項爭議」入口，無駁回按鈕；When 飼主直接呼叫駁回 API，Then 403 `FORBIDDEN`、payment 仍 `SUBMITTED`。對應：BR-8、EX-3
- [ ] **AC-2.5 (確認 / 駁回權限)**：Given CHARGE payment `SUBMITTED`，When 飼主呼叫確認，Then 403 `FORBIDDEN`；When 他人訂單的保母呼叫，Then 403 `FORBIDDEN`；When ADMIN 呼叫確認，Then 403 `ROLE_MISMATCH`；When 該保母以 OWNER 角色 token 呼叫，Then 403 `ROLE_MISMATCH`；以上 payment 皆仍 `SUBMITTED`。對應：EX-1、EX-3、EX-4、BR-16
- [ ] **AC-2.6 (狀態不允許、過期畫面與冪等)**：Given payment 已 `VERIFIED`，When 保母以新 key 再確認，Then 409 `PAYMENT_STATE_CONFLICT`；When 以第一次的 key 重送，Then 回放第一次的 200 body；Given 保母畫面停在 submission X、飼主已因駁回後重傳變成 Y，When 保母以 `submissionId=X` 確認，Then 409 `PAYMENT_STATE_CONFLICT`；Given 訂單 `DISPUTED`，Then 確認 / 駁回皆 409 `ORDER_STATE_CONFLICT`；Given 訂單鎖被占用 > 3 秒，Then 409 `ORDER_BUSY` 且回應標頭 `Retry-After: 1`。對應：BR-7、BR-11、EX-10、EX-11、EX-17
- [ ] **AC-2.7 (憑證檢視與簽名網址)**：Given payment 有 submission X（REJECTED）與 Y（SUBMITTED），When 飼主或保母讀 submission 列表，Then 回 2 筆，各含狀態、後五碼、上傳時間、免責同意時間，X 含駁回原因；When 請求 Y 的圖片，Then 回私有物件的簽名網址，於 CQ-5 有效期後存取該網址被儲存服務拒絕，前端重新請求取得新網址；When 非當事人飼主請求，Then 403 `FORBIDDEN`；When ADMIN 對有 OPEN 待辦的訂單請求，Then 200；對無待辦且非 `DISPUTED` 的訂單，Then 403 `FORBIDDEN`。對應：BR-6、BR-17、EX-1、EX-26
- [ ] **AC-2.8 (通知失敗不回滾)**：Given Resend 回 5xx，When 保母確認首次付款，Then 200、訂單 `CONFIRMED`、雙方站內通知已存在；該 Email 由 outbox 排程重試，第 5 次仍失敗後標記失敗，訂單與 payment 狀態不變。對應：BR-19、EX-22

### 子模組 3：首次付款期限、提醒、逾期、升級與 ADMIN 作廢

- [ ] **AC-3.1 (期限顯示與緊急單)**：Given 範例一般單，When 飼主讀 INITIAL payment，Then `dueAt = 2026-11-23T16:00:00Z`、`escalateAt = 2026-11-24T18:00:00Z`，付款頁顯示「請於 2026-11-24 00:00 前完成付款」；Given 緊急單（首個行程 2026-11-21，2026-11-21 11:00 台北報價），Then `dueAt = 2026-11-21T05:00:00Z`（台北 13:00）、`escalateAt = 2026-11-21T07:00:00Z`（台北 15:00），付款頁顯示「請於 2026-11-21 13:00 前完成付款」。對應：BR-21、baseline §5
- [ ] **AC-3.2 (付款提醒)**：Given 範例一般單 payment `UNPAID`，When 台北 2026-11-23 00:00 後第一次提醒排程執行（≤ 15 分鐘內），Then 飼主收到付款提醒站內通知 1 則並排入 Email；之後排程重跑不再發第 2 則；Given 同條件但 payment 為 `SUBMITTED`，Then 不發提醒；Given 緊急單（報價時已過提醒點），Then 排程不另發提醒。對應：BR-13
- [ ] **AC-3.3 (逾期取消 O8)**：Given 範例一般單 payment `UNPAID`，When `now ≥ 2026-11-23T16:00:00Z` 後第一次排程執行，Then payment `VOID`、訂單 `PENDING_PAYMENT → CANCELLED`、`cancelledBy=SYSTEM`、`order_logs` 新增逾期取消紀錄、雙方各收到逾期取消通知 1 則；排程重跑不重複寫入。對應：P4、O8、BR-14
- [ ] **AC-3.4 (SUBMITTED 不逾期；逾期後駁回)**：Given payment 在 `due_at` 前已 `SUBMITTED`，When `due_at` 後排程執行，Then 訂單仍 `PENDING_PAYMENT`、payment 仍 `SUBMITTED`，保母確認後訂單 `CONFIRMED`；Given `now ≥ due_at` 且 payment `SUBMITTED`，When 保母按駁回，Then 確認框顯示「已超過付款期限，駁回後飼主無法重新上傳，訂單將自動取消」，確認後 payment `UNPAID`，飼主上傳回 409 `DEADLINE_PASSED`，下一次排程執行 O8。對應：BR-12、BR-14、CQ-7
- [ ] **AC-3.5 (待核對升級)**：Given 範例一般單 payment `SUBMITTED`，When `now ≥ 2026-11-24T18:00:00Z` 後第一次排程執行，Then 新增 1 筆 OPEN「首次付款待核對」待辦、雙方各收到升級通知 1 則，ADMIN 後台待辦列表出現該訂單；排程重跑仍只有 1 筆 OPEN 待辦；When 保母之後確認，Then 訂單 `CONFIRMED` 且該待辦 `RESOLVED`。對應：A1、A2、BR-15
- [ ] **AC-3.6 (ADMIN 作廢並取消 O16)**：Given 訂單有 OPEN 首次付款待核對待辦、payment `SUBMITTED`，When ADMIN 在訂單頁按 `btn-admin-void` 並填原因，Then 200；payment `SUBMITTED → VOID`；訂單 `PENDING_PAYMENT → CANCELLED`、`cancelledBy=ADMIN` 且記錄原因；待辦 `RESOLVED`；`order_logs` 新增作廢紀錄；雙方各收到含「平台已作廢此筆付款，款項請由雙方自行處理」的通知；雙方仍可檢視該 submission 與憑證圖片。對應：P5、O16、A2、BR-16
- [ ] **AC-3.7 (ADMIN 限制)**：Given payment `SUBMITTED` 但尚未到 `escalate_at`（無 OPEN 待辦、訂單非 `DISPUTED`），When ADMIN 作廢或讀取該訂單，Then 403 `FORBIDDEN`（不在 §1.2 可讀範圍）；Given 待辦已因保母確認而 `RESOLVED`、訂單 `CONFIRMED`，When ADMIN 以舊畫面作廢，Then 403 `FORBIDDEN`、訂單仍 `CONFIRMED`；When 原因空白，Then 400 `VALIDATION_FAILED`；ADMIN 訂單頁不出現確認收款按鈕。對應：BR-16、EX-4、EX-26
- [ ] **AC-3.8 (保母確認 vs ADMIN 作廢競爭)**：Given OPEN 待辦、payment `SUBMITTED`，When 保母確認與 ADMIN 作廢同時送出，Then 恰好一方成功：保母先成功 → 訂單 `CONFIRMED`、待辦 `RESOLVED`、ADMIN 收到 409 `PAYMENT_STATE_CONFLICT`；ADMIN 先成功 → 訂單 `CANCELLED`、保母收到 409 `PAYMENT_STATE_CONFLICT`；兩者都不會使訂單同時有 `CONFIRMED` 與 `CANCELLED` 的 `order_logs`。對應：EX-18

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | `payments`（本卡使用 `status`、`amount`、`due_at`、`escalate_at`、收款帳號快照；建立在 PRD-006 / PRD-016）；`payment_submissions`（新增：後五碼、免責同意時間、檔案物件鍵、狀態、駁回原因、上傳者、時間）；`admin_tasks`（首次付款待核對）；`order_logs`；`idempotency_keys`。憑證**不放** `orders` / `modification_requests`（取代原 PRD-007「訂單新增憑證欄位」）。付款提醒需「已提醒」標記（欄位設計在 SD-ORDER） |
| 畫面 | 飼主 / 保母訂單詳情付款區塊（付款頁、上傳表單、submission 歷史、確認 / 駁回）；ADMIN 後台首次付款待核對列表與訂單頁作廢按鈕（入口屬 PRD-020）。testid：`payment-amount`、`payment-account-snapshot`、`payment-due-at`、`proof-disclaimer-checkbox`、`proof-last5-input`、`proof-file-input`、`proof-submit-btn`、`submission-history`、`btn-verify-payment`、`btn-reject-payment`、`reject-reason-input`、`btn-admin-void` |
| 通知 | 上傳 → 收款方；確認 → 付款方（INITIAL 為雙方「訂單啟動成功」）；駁回 → 付款方（含原因）；付款提醒 → OWNER；O8 → 雙方；升級 → 雙方；O16 → 雙方。管道：站內 + Email（沿用原 PRD-007；提醒見 CQ-10），範本由 PRD-014 管理（BR §3.2「版型需於資料庫設定」） |
| 排程 | 有。每 15 分鐘：付款提醒（首日 − 48h）、O8（`due_at`）、待核對升級（`escalate_at`）；皆以 `now ≥` 時間判斷、逐筆獨立交易、`SKIP LOCKED`、可重跑（baseline §3.6、§5） |
| 外部服務 | GCS（私有物件、簽名網址）、Resend（經 outbox） |
| 拆卡 | 保母收款帳號設定拆至 PRD-025 |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | 憑證接受哪些檔案格式（需求文件未定義；原 PRD-007 只寫「截圖或照片」） | JPEG / PNG / WebP；不收 HEIC、PDF、GIF（iOS 截圖預設 PNG，相機照片在瀏覽器選檔時會轉 JPEG） | Open |
| CQ-2 | 5 MB 上限沒有需求出處；原 PRD-007「前端預先壓縮」與 SD「超過拒絕」矛盾（抽樣 #10） | 上限 5 MB；前端不壓縮（壓縮會改變檔案 SHA-256 與憑證清晰度），超過直接擋並提示；後端同樣拒絕 422 `PROOF_FILE_TOO_LARGE` | Open |
| CQ-3 | 後五碼指誰的帳號；退款方向也要填嗎 | 一律為「付款方匯出帳戶的後五碼」，所有方向必填（首次 / 補款 = 飼主帳戶，退款 = 保母帳戶） | Open |
| CQ-4 | 免責聲明勾選是否也適用退款方向（保母上傳）；文案是否沿用 | 適用所有方向，文案沿用原 PRD-007，不分方向 | Open |
| CQ-5 | 憑證簽名網址有效期 | 5 分鐘 | Open |
| CQ-6 | 駁回原因、ADMIN 作廢原因長度 | 1–200 字（去除前後空白後） | Open |
| CQ-7 | `now ≥ due_at` 後保母才駁回，飼主已無法重傳，訂單會被 O8 取消（baseline 未處理此情境） | 不給寬限，依 baseline 取消；駁回前確認框警告（AC-3.4 文案）。若要給寬限需改 baseline §5 | Open |
| CQ-8 | 首次付款有 OPEN 待辦時保母駁回，待辦怎麼處理（baseline 只寫 O5 / O16 會關閉） | 同交易 `RESOLVED`（resolution = 保母駁回），因為已不是「待核對」 | Open |
| CQ-9 | 當事人呼叫不屬於自己方向的動作（保母上傳 CHARGE、飼主駁回退款）用哪個錯誤碼（§3.7 未列） | 403 `FORBIDDEN` | Open |
| CQ-10 | 付款提醒的條件與管道（baseline 只寫「提醒 OWNER」） | 只對 `UNPAID` 發；`SUBMITTED` 不發；站內 + Email 各一次 | Open |
| CQ-11 | 補款 payment 是否也要付款提醒（第 2 輪 #20） | 不另發提醒（PRD-016 同意變更時的通知已含 `due_at`） | Open |
| CQ-12 | ADMIN 在可讀訂單範圍內能否看收款帳號快照（§1.2 只列付款方可讀快照，ADMIN 可讀憑證） | 可以唯讀（核對與調解需要比對匯款帳號） | Open |
| CQ-13 | O16 作廢時，原 `SUBMITTED` submission 的狀態（baseline submission 只有 SUBMITTED / ACCEPTED / REJECTED） | 維持 `SUBMITTED`，以 payment `VOID` 為準；畫面標示「已作廢」 | Open |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-21 | 依新範本與 DOMAIN-BASELINE 全文重寫：改為 payment / submission 兩層狀態；三子模組 24 條可觀察 AC；新增期限、提醒、O8、待核對升級、O16；保母收款帳號設定拆至 PRD-025；狀態 In Review | 全面重建；`docs/reviews/00-DOMAIN-BASELINE.prd.review.md` 第 1、2 輪；`reports/rereview/00-sample-SD-007.md` |
| 2026-11-21 | 第 1 輪衝突 #20（`PAID` 主狀態）：移除 `PAID`；上傳 = payment `UNPAID → SUBMITTED`、訂單維持 `PENDING_PAYMENT`；確認 = `VERIFIED` + O5；駁回 = `SUBMITTED → UNPAID` 並保留 `REJECTED` submission。AC-1.4、2.1、2.3 皆同時斷言 payment 與訂單狀態 | baseline §3.1、§3.3、CQ-1、CQ-29 |
| 2026-11-21 | 第 1 輪衝突 #21（逾期未付「未來擴充」）：新增子模組 3，含提醒（AC-3.2）、O8（AC-3.3）、`SUBMITTED` 不取消（AC-3.4）、升級（AC-3.5） | baseline §5、CQ-2、CQ-11 → CQ-31、CQ-44 |
| 2026-11-21 | 第 1 輪衝突 #22（憑證放 orders）：憑證改存 `payment_submissions`；補款 / 退款共用子模組 1、2，觸發與結清引用 PRD-016 | baseline §3.3 |
| 2026-11-21 | 第 1 輪衝突 #23（即時帳號）：付款頁顯示 payment 建立時的快照，保母改帳號不影響（AC-1.1）；越權 403（AC-1.3） | baseline §1.2、CQ-23 |
| 2026-11-21 | 第 1 輪衝突 #24（只有 CONFIRMED 開放 PRD-008）：改為 `CONFIRMED` 或 `IN_PROGRESS`（BR-20） | baseline §3.1 |
| 2026-11-21 | 第 1 輪衝突 #25（無冪等）：上傳 / 確認 / 駁回 / 作廢必帶 `Idempotency-Key`，回放、`IDEMPOTENCY_KEY_REUSED`、`IDEMPOTENCY_IN_PROGRESS`（AC-1.6、2.6） | baseline §3.6、CQ-51 |
| 2026-11-21 | 第 2 輪相關：#5 首次付款待辦出口 → O16（AC-3.6–3.8），ADMIN 不能確認（AC-2.5）；#6 升級時點 → 使用 `escalate_at`（AC-3.1、3.5）；#8 期限後操作 → 409 `DEADLINE_PASSED`（AC-1.7）；#19 方向表 → `ADMIN_ADJUSTMENT` 無上傳（AC-1.2）；#20 補款提醒 → CQ-11；第 1 輪 #36 退款帳號 → 不存飼主帳號、提示自行聯繫（AC-1.2）。第 2 輪「與 PRD 的新增衝突」表無 PRD-007 項目 | baseline CQ-42~46、CQ-54 |
| 2026-11-21 | 抽樣 #9（AC 不可觀察）：全部 AC 改為 Given/When/Then，寫出 HTTP + 錯誤碼、狀態值、文案、testid；補越權、檔案格式、免責、冪等、通知失敗、補款路徑。#10（壓縮 vs 拒絕）：改為前端不壓縮、超過拒絕（BR-5、CQ-2）；「逾期未付」以 O8 取代，殭屍清理不處理 `PENDING_PAYMENT`（baseline CQ-56）。#11（帳號快照）：BR-2、AC-1.1，帳號設定與竄改防護見 PRD-025 | `reports/rereview/00-sample-SD-007.md` |
| 2026-05-09 | 建立 | |
