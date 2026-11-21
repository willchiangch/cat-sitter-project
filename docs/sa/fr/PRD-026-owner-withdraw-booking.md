# PRD-026: 飼主撤回預約（付款前）

| 項目 | 內容 |
|------|------|
| 狀態 | In Review |
| 建立日期 | 2026-11-21 |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0；本卡自 PRD-005 拆出，§0 對應表待主流程補上） |
| 測試情境 | TS-026 |
| 相依 PRD | PRD-005（訂單建立 O1、`review_expires_at`）；PRD-006（報價 O2、與撤回的併發）；PRD-007（首次收款 payment 狀態、`due_at`、付款逾期 O8）；PRD-016（`CONFIRMED` 後的整筆取消）；PRD-027（保母婉拒、評估逾期 O4 排程，與撤回的併發）；PRD-014（通知投遞） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1 角色（`ROLE_MISMATCH`）、§1.2 資源歸屬、§2.2 訂閱逾期只擋新生意、§3.1 主狀態與 `cancelledBy`、§3.2 O3（OWNER 撤回）、O6、取消類操作的期限例外（CQ-61）、取消的共同附帶動作（CQ-65）、付款後不提供撤回（CQ-7）、付款前改內容撤回重下（CQ-3）、§3.3 payment 狀態、§3.6 轉移規則（鎖、版本 / 關鍵值比對、稽核、通知、冪等）、§3.7 錯誤碼、§5 `review_expires_at`、`payments.due_at` |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念（角色、SaaS 額度、訂單狀態、時間起算點）只能**引用** DOMAIN-BASELINE，不得在這裡重新定義；要改就改 baseline。
> 本卡只含 OWNER 的撤回（O3 OWNER、O6）。保母婉拒（O3 SITTER）、評估逾期（O4）、付款前保母取消（O7）見 PRD-027；付款逾期（O8）見 PRD-007；`CONFIRMED` 後的取消見 PRD-016。

---

## 1. 背景與目的

飼主送出預約後、首次付款憑證上傳前，可能因行程改變而不再需要服務，或想改日期（付款前改內容一律撤回重下，baseline CQ-3）。本卡定義飼主在 `PENDING` 與 `PENDING_PAYMENT` 撤回訂單的條件與結果，並明確擋下「已上傳憑證後撤回」與「付款後撤回」，避免飼主已匯款或訂單已成立後被單方取消。

## 2. 使用者故事

- **US-1** 身為飼主，我希望在保母報價前撤回我的預約，以便行程改變時不佔用保母時間。
- **US-2** 身為飼主，我希望在看到報價、尚未付款前仍可撤回，以便價格不符預期時退出。
- **US-3** 身為保母，我希望飼主撤回時收到通知，且飼主上傳付款憑證後不能再撤回，以便不會在已收到錢時訂單被取消。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 撤回預約（O3 OWNER、O6） | 本人訂單，`currentRole=OWNER` | ✗ 403 `ROLE_MISMATCH`（保母婉拒 / 取消見 PRD-027） | ✗ 403 `ROLE_MISMATCH` | ✗ 401 `UNAUTHENTICATED` | 無；保母訂閱鎖定不擋（屬既有訂單） |

- 「本人訂單」以 token 身分判斷；他人訂單 → 403 `FORBIDDEN`，不存在 → 404 `NOT_FOUND`（baseline §1.2）。

## 4. 主流程

### 4.1 撤回

1. 飼主在訂單詳情（評估中分頁）按「撤回預約」，確認視窗可填撤回原因（選填，最多 200 字，BR-11），按確認。前端送出 `expectedStatus`（畫面上的訂單主狀態）並帶 `Idempotency-Key`。
2. 系統以訂單列鎖序列化（baseline §3.6，`lock_timeout 3s`），鎖住後依序檢查：
   1. 呼叫者為該訂單 owner 且 `currentRole=OWNER`。
   2. 原因超過 200 字 → 400 `VALIDATION_FAILED`。
   3. 訂單主狀態為 `PENDING` 或 `PENDING_PAYMENT`，否則 409 `ORDER_STATE_CONFLICT`（含已被 O4 / O8 排程取消的訂單）。
   4. 主狀態等於 `expectedStatus`，否則 409 `ORDER_STATE_CONFLICT`（畫面過期，例如撤回送出前保母已報價）。
   5. `PENDING_PAYMENT` 時首次收款 payment 為 `UNPAID`；`SUBMITTED` → 409 `PAYMENT_ALREADY_SUBMITTED`。
   - **不檢查期限**：`review_expires_at`、`due_at` 已過但訂單尚未進入終態（排程未跑）時仍允許撤回，`cancelledBy=OWNER`（baseline §3.2 期限例外、CQ-61）。
3. 通過後同一交易內：
   1. 訂單 → `CANCELLED`，`cancelledBy=OWNER`，記錄原因（若有）。
   2. 取消的共同附帶動作（baseline §3.2、CQ-65）：未結束 payment → `VOID`（`PENDING_PAYMENT` 時即首次收款 payment `UNPAID → VOID`，O6；`PENDING` 時尚無 payment）；訂單的 `PENDING` 行程 → `CANCELLED`（行程歸 SD-FULFILL，透過 SD-ORDER 鎖入口處理）。
   3. 寫 `order_logs`：`ORDER_WITHDRAWN`，操作者 = 飼主，payload 含撤回前狀態與原因。
   4. `NotificationService.enqueue()`：通知保母與飼主（站內 + Email）；有填原因時，保母通知內容含原因。
4. 回 200，body 含 `status=CANCELLED`、`cancelledBy=OWNER`。訂單移到雙方「歷史」分頁。

## 5. 狀態轉移

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1（baseline O3 OWNER） | `PENDING` | `CANCELLED` | OWNER | 本人訂單；`expectedStatus=PENDING`；不論是否已過 `review_expires_at`（CQ-61） | 403 `FORBIDDEN` / `ROLE_MISMATCH`；409 `ORDER_STATE_CONFLICT` |
| T2（baseline O6） | `PENDING_PAYMENT` | `CANCELLED` | OWNER | 本人訂單；`expectedStatus=PENDING_PAYMENT`；首次收款 payment `UNPAID`；不論是否已過 `due_at`（CQ-61） | 403 `FORBIDDEN` / `ROLE_MISMATCH`；409 `ORDER_STATE_CONFLICT` / `PAYMENT_ALREADY_SUBMITTED` |
| T3（附帶，O6、CQ-65） | payment `UNPAID` | payment `VOID` | OWNER（隨 T2） | 同 T2 | 同 T2 |
| T4（附帶，CQ-65） | 行程 `PENDING` | 行程 `CANCELLED` | OWNER（隨 T1 / T2） | 同 T1 / T2 | 同 T1 / T2 |

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 飼主可在 `PENDING`（O3）與 `PENDING_PAYMENT` 且首次收款 payment 為 `UNPAID`（O6）時撤回；`SUBMITTED` 後不可撤回 → 409 `PAYMENT_ALREADY_SUBMITTED` | baseline O3、O6、§3.7 |
| BR-2 | `CONFIRMED` 之後不提供撤回，一律走變更協商整筆取消（PRD-016）→ 409 `ORDER_STATE_CONFLICT` | baseline §3.2、CQ-7 |
| BR-3 | 付款前要改日期或內容 → 撤回後重新預約（PRD-005），不提供修改 | baseline §3.2、CQ-3（取代 BR §3.3「修改訂單日期」） |
| BR-4 | 撤回不受期限限制：訂單尚未進入終態前，即使已過 `review_expires_at`（`PENDING`）或 `due_at`（`PENDING_PAYMENT`）、排程尚未寫入終態，仍允許撤回，`cancelledBy=OWNER`；排程已先寫入終態（`cancelledBy=SYSTEM`）→ 409 `ORDER_STATE_CONFLICT`。O4 / O8 排程鎖住後重驗，訂單已 `CANCELLED` 則跳過 | baseline §3.2 期限例外、§3.6 排程、CQ-61（使用者 2026-11-21；取代原依 CQ-46 的 `DEADLINE_PASSED`） |
| BR-5 | 撤回時記 `cancelledBy=OWNER`；同一交易把未結束 payment 轉 `VOID`（`PENDING_PAYMENT` 的首次收款 payment）、剩餘 `PENDING` 行程轉 `CANCELLED` | baseline §3.1、O6、§3.2 取消的共同附帶動作、CQ-65（使用者 2026-11-21） |
| BR-6 | 取消需通知雙方（站內 + Email） | BR §3.6 步驟 4「取消接單…需信件通知雙方」；baseline §3.6 |
| BR-7 | 畫面過期以關鍵值比對：客戶端帶 `expectedStatus`，與目前主狀態不符 → 409 `ORDER_STATE_CONFLICT`；不接受客戶端 version | baseline §3.6、CQ-35 |
| BR-8 | 撤回不影響保母每月接單上限（`PENDING` / `PENDING_PAYMENT` 從未計入） | baseline §2.2、CQ-5 |
| BR-9 | 保母訂閱鎖定 / 停權不擋飼主撤回 | baseline §2.2、CQ-19（只擋新生意） |
| BR-10 | 撤回帶 `Idempotency-Key`，同 key 同內容回放第一次結果；`order_logs` 與通知 enqueue 與狀態變更同一交易 | baseline §3.6 |
| BR-11 | 撤回原因選填，最多 200 字（超過 → 400 `VALIDATION_FAILED`）；有填則寫入訂單取消原因並顯示在保母通知中 | CQ-1（使用者 2026-11-21） |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 非本人訂單（其他飼主） | 拒絕，不改資料 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | `currentRole=SITTER`（含該訂單的保母本人）或 ADMIN | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換為飼主身分」 |
| EX-3 | 訂單不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此訂單」 |
| EX-4 | 已上傳付款憑證（payment `SUBMITTED`） | 拒絕，不改資料 | 409 `PAYMENT_ALREADY_SUBMITTED` | 「已上傳付款憑證，無法撤回；如需取消請與保母聯繫」，撤回按鈕隱藏 |
| EX-5 | 狀態不允許（`CONFIRMED` / `IN_PROGRESS` / `DISPUTED` / `COMPLETED` / `CANCELLED`） | 拒絕 | 409 `ORDER_STATE_CONFLICT` | 「訂單狀態已變更，請重新整理」；`CONFIRMED` / `IN_PROGRESS` 顯示 PRD-016 變更入口 |
| EX-6 | 已過評估期限 / 付款期限、排程未跑 | 允許撤回（BR-4）；之後排程重驗發現已 `CANCELLED` 即跳過，不重寫 log、不重複通知 | 200（`cancelledBy=OWNER`） | 同一般撤回 |
| EX-7 | 畫面過期（`expectedStatus` 與目前狀態不符，例：保母已報價） | 拒絕 | 409 `ORDER_STATE_CONFLICT` | 「訂單狀態已變更，請重新整理」，重新整理後可看到報價再決定 |
| EX-8 | 跨角色併發：撤回與保母報價 / 婉拒 / 取消同時送出 | 列鎖序列化，先到者生效；後到者重驗條件失敗 | 409 `ORDER_STATE_CONFLICT` | 同 EX-7 |
| EX-9 | 訂單鎖等待逾時（> 3s） | 不改資料，可重試 | 409 `ORDER_BUSY` + `Retry-After: 1` | 前端自動重試一次，仍失敗顯示「系統忙碌，請稍後再試」 |
| EX-10 | 重複送出（同 key 同內容） | 回放第一次結果，不重複寫 log、不重複通知 | 200（回放） | 同第一次 |
| EX-11 | 同 key 不同內容 / 處理中 | 拒絕 | 422 `IDEMPOTENCY_KEY_REUSED` / 409 `IDEMPOTENCY_IN_PROGRESS` | 「請重新操作」/ 前端自動重試 |
| EX-12 | 不同 key 再撤回已撤回的訂單 | 拒絕 | 409 `ORDER_STATE_CONFLICT` | 同 EX-5 |
| EX-13 | Email 寄送失敗（Resend） | 撤回照常完成；站內通知已寫入；Email 由排程重試最多 5 次 | 200 | 無感 |
| EX-14 | 方案不足 / 訂閱鎖定 | 不適用：撤回無方案限制，保母鎖定也不擋（BR-9） | — | — |
| EX-15 | GCS 失敗 | 不適用：撤回不上傳檔案 | — | — |
| EX-16 | 同一飼主在另一裝置同時上傳付款憑證 | 不處理同一使用者多裝置自我併發（既有產品決策）；列鎖仍保證資料一致：先撤回則上傳因 payment `VOID` 被 PRD-007 拒絕，先上傳則撤回得 EX-4 | 依先後 | 依先後 |
| EX-17 | 撤回與 O4 / O8 排程同時處理同一已過期限的訂單 | 列鎖序列化，先到者生效：排程先 → 訂單 `cancelledBy=SYSTEM`，撤回重驗失敗；撤回先 → `cancelledBy=OWNER`，排程重驗後跳過 | 409 `ORDER_STATE_CONFLICT`（排程先時）/ 200（撤回先時） | 「訂單狀態已變更，請重新整理」/ 同一般撤回 |
| EX-18 | 撤回原因超過 200 字 | 拒絕，不改資料 | 400 `VALIDATION_FAILED` | 原因欄旁錯誤訊息「最多 200 字」 |

## 8. 驗收標準（AC）

### 子模組 1：飼主撤回

- [ ] **AC-1.1 (評估中撤回)**：Given 飼主 O 的訂單 `PENDING`（問卷 `NOT_REQUIRED` 或 `COMPLETED` 皆可）、`now < review_expires_at`，When O 以 `expectedStatus=PENDING` 撤回並填原因「行程取消」，Then 200、`status=CANCELLED`、`cancelledBy=OWNER`、取消原因 = 「行程取消」；該訂單行程全部 `CANCELLED`；`order_logs` 有 1 筆 `ORDER_WITHDRAWN`（操作者 = O、payload 含 `fromStatus=PENDING` 與原因）；保母與 O 各有 1 則站內通知與 1 筆 Email 投遞紀錄，保母通知內容含「行程取消」；訂單出現在雙方「歷史」分頁。Given 不填原因，Then 同樣 200；Given 原因 201 字，Then 400 `VALIDATION_FAILED`、資料不變。Given 保母訂閱已鎖定，結果相同。對應：T1、T4、BR-5、BR-6、BR-9、BR-11、EX-18
- [ ] **AC-1.2 (報價後付款前撤回)**：Given 訂單 `PENDING_PAYMENT`、首次收款 payment `UNPAID`、`now < due_at`，When O 以 `expectedStatus=PENDING_PAYMENT` 撤回，Then 200、訂單 `CANCELLED`、`cancelledBy=OWNER`、payment `VOID`、行程全部 `CANCELLED`（同一交易）、`order_logs` `ORDER_WITHDRAWN`（`fromStatus=PENDING_PAYMENT`）、雙方收到通知；保母當月接單計數不變。對應：T2、T3、BR-1、BR-5、BR-8
- [ ] **AC-1.3 (已上傳憑證不可撤回)**：Given 訂單 `PENDING_PAYMENT`、首次收款 payment `SUBMITTED`，When O 呼叫撤回，Then 409 `PAYMENT_ALREADY_SUBMITTED`，訂單、payment、submission、行程皆不變，無新 log、無通知；訂單詳情不顯示「撤回預約」按鈕，改顯示「已上傳付款憑證，無法撤回；如需取消請與保母聯繫」。對應：BR-1、EX-4
- [ ] **AC-1.4 (付款後與終態不可撤回)**：Given 訂單分別為 `CONFIRMED`、`IN_PROGRESS`、`DISPUTED`、`COMPLETED`、`CANCELLED`，When O 呼叫撤回，Then 皆 409 `ORDER_STATE_CONFLICT`、資料不變；`CONFIRMED` / `IN_PROGRESS` 的訂單詳情不顯示「撤回預約」，改顯示 PRD-016 的變更 / 取消入口。對應：BR-2、EX-5、EX-12
- [ ] **AC-1.5 (期限已過仍可撤回)**：Given 訂單 `PENDING`、`review_expires_at = 2026-11-24T16:00:00Z`（= 11/25 00:00 Asia/Taipei）、O4 排程尚未執行，When O 於 2026-11-25 00:00:01（Asia/Taipei）撤回，Then 200、`status=CANCELLED`、`cancelledBy=OWNER`、行程全部 `CANCELLED`；之後 O4 排程執行，訂單仍為 `cancelledBy=OWNER`，`order_logs` 只有 1 筆取消類紀錄、雙方通知各仍 1 則。Given `PENDING_PAYMENT`、payment `UNPAID`、`now ≥ due_at`、O8 未執行，When O 撤回，Then 200、`cancelledBy=OWNER`、payment `VOID`。Given O4 排程已先將訂單轉 `CANCELLED`（`cancelledBy=SYSTEM`），When O 撤回，Then 409 `ORDER_STATE_CONFLICT`、`cancelledBy` 維持 `SYSTEM`。任何情況都不回 `DEADLINE_PASSED`。對應：BR-4、BR-5、EX-6、EX-17
- [ ] **AC-1.6 (越權與角色)**：Given 訂單屬於飼主 O，When 飼主 P 呼叫撤回 → 403 `FORBIDDEN`；When 該訂單的保母以 `currentRole=SITTER` 呼叫 → 403 `ROLE_MISMATCH`；When ADMIN 呼叫 → 403 `ROLE_MISMATCH`；When 撤回不存在的訂單 id → 404 `NOT_FOUND`；以上訂單資料皆不變。對應：EX-1~EX-3
- [ ] **AC-1.7 (畫面過期與跨角色併發)**：Given O 的畫面顯示 `PENDING`，保母已先報價使訂單變 `PENDING_PAYMENT`，When O 以 `expectedStatus=PENDING` 撤回，Then 409 `ORDER_STATE_CONFLICT`，訂單維持 `PENDING_PAYMENT`、payment 維持 `UNPAID`。Given O 撤回與保母婉拒同時送出，Then 只有一方成功，訂單為 `CANCELLED` 且 `cancelledBy` 為成功的一方，另一方 409 `ORDER_STATE_CONFLICT`，`order_logs` 只有 1 筆取消類紀錄。鎖等待逾時 → 409 `ORDER_BUSY`、`Retry-After: 1`。對應：BR-7、EX-7~EX-9
- [ ] **AC-1.8 (重複送出)**：Given O 以 key K 撤回成功，When 以 K 與相同內容重送，Then 回放 200 與相同 body，`ORDER_WITHDRAWN` log 仍 1 筆、通知仍各 1 則；When 以 K 但不同原因重送 → 422 `IDEMPOTENCY_KEY_REUSED`；When 以新 key 再撤回 → 409 `ORDER_STATE_CONFLICT`。Given Resend 回 5xx，Then 撤回仍為 200、訂單 `CANCELLED`，Email 由排程重試。對應：BR-10、EX-10~EX-13

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | `orders`：`status`、`cancelled_by`、取消原因、取消時間；`payments`：首次收款 `UNPAID → VOID`（實體定義見 PRD-007）；行程（`visits`，歸 SD-FULFILL）`PENDING → CANCELLED`；`order_logs`：`ORDER_WITHDRAWN`；`idempotency_keys` |
| 畫面 | 飼主訂單詳情（評估中分頁）：「撤回預約」按鈕與確認視窗（原因選填，CQ-1）；`SUBMITTED` 時改顯示無法撤回提示；保母訂單列表：訂單移到歷史分頁 |
| 通知 | 飼主撤回 → SITTER、OWNER → 站內 + Email |
| 排程 | 本卡無新排程。期限到期的終態由 PRD-027 O4、PRD-007 O8 排程寫入；排程鎖住訂單後重驗，訂單已因撤回而 `CANCELLED` 時跳過（BR-4） |
| 現況差異（2026-11-21 程式碼，只作證據） | 現況撤回條件依舊狀態機（`PAID` 等），無 `expectedStatus` 比對；依 baseline CQ-36 既有交易資料清空 |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 / 決定 | 狀態 |
|----|------|-----------|------|
| CQ-1 | 飼主撤回是否必填原因：baseline O3 只要求保母婉拒附原因，OWNER 沒寫 | 選填，最多 200 字（超過 → 400 `VALIDATION_FAILED`）；有填則寫入訂單取消原因並顯示在保母通知中（寫入 BR-11、EX-18、AC-1.1） | 已決（使用者 2026-11-21） |
| CQ-2 | 訂單取消時行程是否一併轉 `CANCELLED` | 由 baseline CQ-65 決定：任何轉為 `CANCELLED` 的轉移，同一交易把剩餘 `PENDING` 行程轉 `CANCELLED`、未結束 payment 轉 `VOID`（baseline §3.2「取消的共同附帶動作」；寫入 §4.1 步驟 3.2、BR-5、T3、T4、AC-1.1、AC-1.2、AC-1.5） | 已決（使用者 2026-11-21） |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-21 | 依使用者決定改寫：撤回不受 `review_expires_at` / `due_at` 限制（訂單未進終態前允許，`cancelledBy=OWNER`），刪除所有 `DEADLINE_PASSED`（§4.1、T1、T2、BR-4、EX-6、AC-1.5），新增排程併發 EX-17；取消附帶動作改引用 baseline CQ-65（§4.1、BR-5、T3、T4、AC-1.2）；CQ-1 採建議預設（新增 BR-11、EX-18，AC-1.1 補原因長度與通知內容）；AC-1.1 問卷子狀態改為 `NOT_REQUIRED` / `COMPLETED`（`PENDING_OWNER` 已不存在）；引用基準改列 CQ-61、CQ-65，相依加 PRD-027；仍 1 個子模組 8 條 AC | 使用者決定 2026-11-21；baseline 第 2 版 CQ-58、CQ-61、CQ-65（CQ-61 取代原依 CQ-46 的寫法） |
| 2026-11-21 | 建立：自 PRD-005 拆出飼主撤回（O3 OWNER、O6），1 個子模組 8 條 AC；狀態 In Review | PRD-005 重寫後子模組 4 個 > 3，依 doc-conventions 拆卡；依據 baseline O3、O6、CQ-3、CQ-7、CQ-46 |
