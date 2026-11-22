# TS-027: 保母婉拒、評估逾期與付款前保母取消

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-027（Approved 2026-11-22）；前置資料另用 PRD-005（送單）、PRD-006（報價）、PRD-007（上傳 / 駁回 / 核對憑證、O8）、PRD-026（飼主撤回）、PRD-010（轉介入口後的選單，僅入口） |
| 對應 SD | SD-ORDER（Approved 2026-11-22）：E2、E4、E6、E7、E8、E9、E10、E11、E12、E14、E15、C1、C3、§6.3 排程端點契約、§6.5 錯誤碼、§7.2 通知、§9.1 testid、§10.4 測試支援端點 |
| 類型 | 旅程（打真後端，§2.1–§2.6、§3）＋ 模組（mock，只有 §2.7 的 QRM 列：轉介失敗、`ORDER_BUSY` 重試等真後端造不出的回應） |
| 自動化路徑 | 旅程 `frontend/e2e/journeys/order-decline-expiry-cancel.spec.ts`；模組 `frontend/e2e/order-decline-cancel.spec.ts`（須 `assertNoUnmockedApi`） |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> 三個出口都是狀態轉移 + 權限 + 冪等 + 跨角色 / 排程競爭，主體用旅程型。
> PRD-027 AC 內「情境 n」逐列對應為 `AC-x.y-n`。

---

## 0. 測試邏輯（Given / When / Then）

- **Given**：每個 `test.describe` 自己 provision 飼主與保母（`@e2e-journey.test`），以 E1 建立 `PENDING` 訂單，需要 `PENDING_PAYMENT` 者再以 E6 原價報價；排程情境以 `shift-time` 把 `review_expires_at` / `due_at` 平移到過去。
- **When**：保母在評估頁婉拒（E7）、在待付款訂單詳情取消（E9）；或以 `X-Internal-Secret` 觸發 C1（評估逾期）/ C3（付款逾期，PRD-007）。
- **Then**：訂單 `CANCELLED`，`cancellation.cancelledBy` / `reasonCode` / `reason` 正確，剩餘行程 `CANCELLED`、未結束 payment `VOID`，雙方各一則站內通知；被拒時回 PRD-027 BR-14 判斷順序中第一個不符的錯誤碼且不改資料、不發通知。

## 1. 前置資料

> 不寫死 UUID：訂單、payment、submission、毛孩、方案 id 一律取自建立時的回應。每個 describe 可獨立重跑。

**記號**

| 記號 | 意義 |
|------|------|
| `D(n)` | 執行當下 Asia/Taipei 的今天 + n 日 |
| `ownerToken(X)` / `sitterToken(X)` | 帳號 X 以 `currentRole=OWNER` / `SITTER` 取得的 token |
| **不變** | 飼主 E2 的 `status`、`cancellation`、`visits[].status` 與保母 E10 的 payment `status` 與操作前相同，且雙方該訂單通知數與操作前相同 |
| 200 字 | 以 Unicode code point 計（PRD-027 BR-9）；測試字串 = 199 個中文字 +「🐱」（UTF-16 為 201 個單位，用來抓以 UTF-16 長度計數的錯誤） |

**帳號**

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `POST /api/internal/test-data/provision-account`（`@e2e-journey.test`，顯示名稱「王小明」）＋ `owner-setup`：`pets` = `M`（CAT）、`emailPreferences.ORDER_AFFAIR = 開` | — |
| `ownerMuted` | OWNER | 同上，`ORDER_AFFAIR = 關`，`pets` = `K` | — |
| `sitterA` | SITTER | provision ＋ `sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄測試值、`plans` = `P`（單價 600，`active`）、`questions = []` | — |
| `sitterMuted` | SITTER | 同 `sitterA`；保母端 `ORDER_AFFAIR = 關`（設定方式見 §5 #3） | — |
| `sitterLock` | SITTER | 同 `sitterA`；建好訂單（必要時已報價）後以 `sitter-setup` 改 `subscriptionExpiresAt = D(-2)` | 先可接單，後鎖定 |
| `sitterOther` | SITTER | 同 `sitterA` | 越權用 |
| `admin` | ADMIN | provision-account（ADMIN 角色，見 §5 #1） | — |

**共用 helper（業務名稱，e2e-author 實作）**

| helper | 內容 |
|--------|------|
| `createPendingOrder(owner, sitter, dates)` | E31 取方案 → E1 `POST /api/orders`（`groups=[{planId:P, dates, dailyCount:1, petIds:[M]}]`、新 key）→ 201、`status=PENDING` |
| `createQuotedOrder(owner, sitter, dates)` | `createPendingOrder` → E6 `{expectedAmount}` → 200、`status=PENDING_PAYMENT`；回傳 INITIAL `paymentId` |
| `uploadProof(owner, order, paymentId)` | E11 multipart（`disclaimerAgreed=true`、`lastFive=12345`、1×1 PNG）→ 201、`paymentStatus=SUBMITTED`；回傳 `submissionId` |
| `shiftTime(order, target, 分鐘, paymentId?)` | `POST /api/internal/test-data/orders/{orderId}/shift-time`，`target=REVIEW_EXPIRES_AT` 或 `PAYMENT_DUE_AT`（後者帶 `paymentId`）；分鐘 = −(距到期分鐘數 + 5) |
| `runCron(path, headers?)` | `POST /api/internal/cron/{path}`，預設帶 `X-Internal-Secret`（CI secret，不寫入證據）；C1 = `orders/review-expiry`、C3 = `payments/initial-expiry` |
| `notificationsOf(user, orderId)` | 以該使用者 token 讀通知清單（SD-NOTIFY 通知查詢 API，路徑由 e2e-author 依 SD-NOTIFY 對應），篩 `orderId` |

**標準日期**：`[D(10), D(11), D(12)]`（3 個行程、`baseAmount=1800`）；§2.6 用 `[D(10), D(11)]`（2 個行程）。

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4：含 `payment_submissions` 的 GCS 物件、`referral_logs`、`idempotency_records`）。

## 2. 測試步驟

> 步驟 ID 前綴 `QR-`（旅程）、`QRM-`（模組），spec 的 `step()` / `apiStep()` 標籤以同一 ID 開頭。

### 2.1 婉拒成功、確認框與轉介入口（describe「婉拒」：`owner` × `sitterA`）

前置：`ordD1 = createPendingOrder(owner, sitterA, 標準)`、`ordD2 = createPendingOrder(owner, sitterA, 標準)`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-01 | AC-1.2-1 | sitterA | 開 `/sitter/orders/{ordD1}`，點 `sitter-order-eval-btn-decline` | 對話框含必填的 `sitter-order-decline-input-reason`；`sitter-order-decline-btn-quick-reason-1`–`4` 文字依序「檔期已滿」「超出服務範圍」「無法照顧此類毛孩」「其他」；文字「婉拒後飼主將收到通知，含您填寫的原因」 | — | 截圖 |
| QR-02 | AC-1.2-3（EX-5） | sitterA | 原因欄空白；改填 3 個半形空白；改填 201 字 | 空白與純空白時 `sitter-order-decline-btn-confirm` disabled；201 字時顯示「原因最多 200 字」且確認鈕 disabled | 期間未發出任何 E7 | 截圖 |
| QR-03 | AC-1.2-2 | sitterA | 清空後點 `sitter-order-decline-btn-quick-reason-1`，再在結尾輸入「（12/10–12/12）」 | 原因欄值 =「檔期已滿（12/10–12/12）」（帶入後可編輯） | — | 截圖 |
| QR-04 | AC-1.1-1 | sitterA | 清空原因改填「該週檔期已滿」，點 `sitter-order-decline-btn-confirm` | 對話框改顯示 `sitter-order-decline-btn-referral`「轉介信任圈夥伴」 | 頁面發出的 E7 恰 1 次、帶 `Idempotency-Key`、body `{reason:"該週檔期已滿"}` → 200 `{orderId, status:"CANCELLED", cancelledBy:"SITTER"}` | 截圖 + API JSON |
| QR-05 | AC-1.5-1 | sitterA | 點 `sitter-order-decline-btn-referral` | 進入轉介選單（PRD-010；元素依 PRD-010 定義對應） | 訂單仍 `CANCELLED` | 截圖 |
| QR-06 | AC-1.1-1 | owner、sitterA | 讀 E2、E10 | — | 飼主 E2：`status=CANCELLED`、`cancellation.cancelledBy=SITTER`、`cancellation.reasonCode=SITTER_DECLINED`、`cancellation.reason=該週檔期已滿`、`visits` 3 筆皆 `CANCELLED`、`payments=[]`；保母 E10 → `[]` | API JSON |
| QR-07 | AC-1.1-1（BR-2） | owner、sitterA | `notificationsOf(owner, ordD1)`、`notificationsOf(sitterA, ordD1)` | — | 飼主恰 1 則婉拒通知：標題「預約已婉拒」、內文含「該週檔期已滿」、連結 `/owner/orders/{ordD1}`；保母恰 1 則：「您已婉拒 王小明 的預約」、連結 `/sitter/orders/{ordD1}` | API JSON |
| QR-08 | AC-1.1-1 | owner | 開 `/owner/orders/{ordD1}` | `order-status-badge`「已取消」；`owner-order-detail-cancel-result` 含「保母已婉拒此預約」與「該週檔期已滿」；無 `owner-order-detail-btn-withdraw`、無付款區塊 | — | 截圖 |
| QR-09 | AC-1.1-1 | sitterA | 開 `/sitter/orders` 切「歷史」分頁，再開該訂單 | 歷史分頁有 `ordD1`；評估中分頁沒有；訂單頁無 `sitter-order-eval-btn-quote-original`、`-btn-quote-adjust`、`-btn-decline` | E4 `tab=HISTORY` 含 `ordD1`、`tab=EVALUATING` 不含；E2 `allowedActions` 不含 `DECLINE`、`QUOTE` | 截圖 + API JSON |
| QR-10 | AC-1.5-3 | sitterA | `ordD2` 婉拒（快速帶入「超出服務範圍」）成功後直接關閉對話框；重開 `/sitter/orders/{ordD2}`（歷史） | 歷史訂單頁無 `sitter-order-decline-btn-referral` | `notificationsOf(owner, ordD2)` 只有 1 則婉拒通知、無任何轉介類通知 | 截圖 + API JSON |
| QR-11 | AC-1.1-2 | ownerMuted、sitterMuted | `ordM = createPendingOrder(ownerMuted, sitterMuted, 標準)`；E7 `{reason:"該週檔期已滿"}` | — | 200；雙方各恰 1 則站內通知（Email 未寄見 §4） | API JSON |

### 2.2 原因驗證與婉拒被拒（describe「婉拒被拒」：`sitterA`）

> 每次呼叫用新 key；除另註外每列結尾斷言 **不變**（訂單仍 `PENDING`、飼主無婉拒通知）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-12 | AC-1.2-4（EX-5） | sitterA | `ordV`；E7 依序 `reason:""`、`reason:"  \t  "`、`reason:` 前後空白 + 201 字 | — | 皆 400 `VALIDATION_FAILED`、`payload.field=reason` | API JSON |
| QR-13 | AC-1.2-4（BR-9 邊界） | sitterA | `ordV` E7 `reason:` 前後各 2 個空白 + 200 字（含「🐱」） | — | 200、`status=CANCELLED`；E2 `cancellation.reason` 為去除前後空白後的 200 字（見 §5 #6） | API JSON |
| QR-14 | AC-1.3-1 | sitterA | `ordQ = createQuotedOrder(…)`；E7 | — | 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=PENDING_PAYMENT`；E10 仍 1 筆 `UNPAID` | API JSON |
| QR-15 | AC-1.3-1 | owner、sitterA | `ordW`：飼主 E8 `{expectedStatus:"PENDING"}` 撤回；保母 E7 | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E2 `cancellation.cancelledBy=OWNER` 不變 | API JSON |
| QR-16 | AC-1.3-1 | sitterA | `ordX`：`shiftTime(REVIEW_EXPIRES_AT)` → `runCron(C1)` → E7 | — | C1 200；E2 `cancelledBy=SYSTEM`；E7 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED` | API JSON |
| QR-17 | AC-1.3-2（EX-1） | sitterOther | `ordR`；E7 `{reason:"x"}` | — | 403 `FORBIDDEN` | API JSON |
| QR-18 | AC-1.3-3（EX-2） | sitterA（OWNER 角色） | 以 `ownerToken(sitterA)` E7 | — | 403 `ROLE_MISMATCH` | API JSON |
| QR-19 | AC-1.3-4（EX-3） | admin | E7 | — | 403 `ROLE_MISMATCH` | API JSON |
| QR-20 | AC-1.3-5（EX-4） | sitterA | E7 執行時產生的隨機 UUID | — | 404 `NOT_FOUND` | API JSON |
| QR-21 | AC-1.3-6（EX-5） | sitterA | `ordR` E7 不帶 `Idempotency-Key` | — | 400 `VALIDATION_FAILED`、`payload.field=Idempotency-Key`；QR-17–QR-21 後 `ordR` **不變** | API JSON |

### 2.3 婉拒重送、併發與鎖定（describe「婉拒冪等併發」）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-22 | AC-1.4-2（EX-14） | sitterA | `ordI`；E7 key `K` `{reason:"檔期已滿"}` → 回應 `B1`；同 key 同 body 再送 | — | 第二次 200、body 與 `B1` 相同；雙方站內通知各仍 1 則 | API JSON |
| QR-23 | （EX-14） | sitterA | key `K`、`{reason:"其他"}` | — | 422 `IDEMPOTENCY_KEY_REUSED` | API JSON |
| QR-24 | AC-1.4-1（EX-11） | owner、sitterA | 3 輪，每輪新訂單：同時送出飼主 E8 `{expectedStatus:"PENDING"}` 與保母 E7 `{reason:"檔期已滿"}` | — | 每輪恰一方 200、另一方 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E2 `cancellation.cancelledBy` = 勝方（`OWNER` 或 `SITTER`）；通知只有勝方那組（飼主勝：保母有撤回通知、飼主無婉拒通知；保母勝：反之）。誰勝不固定，確定性覆蓋見 §4 | API JSON（每輪） |
| QR-25 | AC-1.6-1（EX-15） | sitterLock | `ordL = createPendingOrder(owner, sitterLock, 標準)` → 鎖定 → 開評估頁婉拒（原因「暫停接案」） | `sitter-order-eval-precheck-banner` 顯示鎖定文案，`sitter-order-eval-btn-decline` 仍 enabled；婉拒後出現轉介入口 | E7 200；E2 `cancelledBy=SITTER`、行程皆 `CANCELLED`；雙方各 1 則通知 | 截圖 + API JSON |

### 2.4 期限後婉拒（describe「期限後婉拒」）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-26 | AC-1.7-1（EX-9） | sitterA | `ordL1`；`shiftTime(REVIEW_EXPIRES_AT)` 後立即 E7 `{reason:"檔期已滿"}` | — | E2（平移後）`reviewExpiresAt` < now 且仍 `PENDING`；E7 200；E2 `cancelledBy=SITTER`、`reasonCode=SITTER_DECLINED`、行程皆 `CANCELLED`（不回 `DEADLINE_PASSED`） | API JSON |
| QR-27 | AC-1.7-1（EX-12） | 排程 | `runCron(C1)` | — | 200；E2 `cancellation` 與 QR-26 後完全相同（含 `cancelledAt`）；雙方無「保母未於期限內回覆，預約已自動取消」/「預約已逾期自動取消」通知 | API JSON |
| QR-28 | AC-1.7-2（EX-12） | sitterA | `ordL2`；`shiftTime` → `runCron(C1)` → E7 | — | C1 後 E2 `cancelledBy=SYSTEM`；E7 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E2 `cancelledBy` 仍 `SYSTEM` | API JSON |

### 2.5 評估逾期 O4（describe「評估逾期」：`ordE = createPendingOrder(owner, sitterA, [D(10), D(11)])`）

> 步驟依序執行：先驗未到期不處理、非排程身分被拒，再平移後正式觸發。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-30 | AC-2.2-1 | 排程 | 未平移，`runCron(C1)` | — | 200；`ordE` E2 `status=PENDING`、2 個行程 `PENDING`；雙方無逾期通知 | API JSON |
| QR-31 | AC-2.4-2（EX-20） | 一般使用者 | `shiftTime(ordE, REVIEW_EXPIRES_AT)`；立即依序以 `sitterToken(sitterA)`、`ownerToken(owner)`、不帶任何標頭、`X-Internal-Secret: wrong` 呼叫 C1 | — | 四次皆 403 `FORBIDDEN`（§6.1 錯誤格式）；之後 E2 `status=PENDING` | API JSON |
| QR-32 | AC-2.1 | 排程 | `runCron(C1)` | — | 200，body 含 `job`、`processed`（≥ 1）、`skipped`、`failed`（皆整數）、`startedAt`、`finishedAt`；`ordE` E2：`status=CANCELLED`、`cancellation.cancelledBy=SYSTEM`、`reasonCode=REVIEW_EXPIRED`、`reason=保母未於期限內回覆`、2 個行程 `CANCELLED`、`payments=[]` | API JSON |
| QR-33 | AC-2.1（BR-2） | owner、sitterA | `notificationsOf` 雙方 | — | 飼主恰 1 則「保母未於期限內回覆，預約已自動取消」；保母恰 1 則「預約已逾期自動取消」 | API JSON |
| QR-34 | AC-2.4-1 | 排程 | 再 `runCron(C1)` | — | 200；`ordE` E2 `cancellation.cancelledAt` 與 QR-32 後相同；雙方逾期通知各仍 1 則 | API JSON |

### 2.6 付款前保母取消 O7（describe「付款前取消」：`sitterA`）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QR-40 | AC-3.1 | sitterA | `ordC = createQuotedOrder(owner, sitterA, 標準)`；開 `/sitter/orders/{ordC}`，點 `sitter-order-detail-btn-cancel` | 對話框含 `sitter-order-cancel-input-reason`、`sitter-order-cancel-btn-quick-reason-1`–`4`（文字同 QR-01）、文字「取消後飼主將收到通知，含您填寫的原因」 | — | 截圖 |
| QR-41 | AC-3.1 | sitterA | 填「臨時生病無法服務」，點 `sitter-order-cancel-btn-confirm` | 成功畫面無 `sitter-order-decline-btn-referral`（BR-13） | 頁面發出的 E9 恰 1 次、帶 key → 200 `{status:"CANCELLED", cancelledBy:"SITTER"}` | 截圖 + API JSON |
| QR-42 | AC-3.1 | owner、sitterA | 讀 E2、E10 | — | E10 INITIAL `status=VOID`；E2 `cancellation.cancelledBy=SITTER`、`reasonCode=SITTER_CANCELLED`、`reason=臨時生病無法服務`、3 個行程 `CANCELLED` | API JSON |
| QR-43 | AC-3.1（BR-2） | owner、sitterA | `notificationsOf` 雙方 | — | 飼主恰 1 則「保母已取消訂單」、內文含「臨時生病無法服務」；保母恰 1 則「您已取消訂單」 | API JSON |
| QR-44 | AC-3.1 | owner | 開 `/owner/orders/{ordC}` | 無 `proof-file-input`、`proof-submit-btn`（付款入口消失）；`owner-order-detail-cancel-result` 含「臨時生病無法服務」 | — | 截圖 |
| QR-45 | AC-3.2-1（EX-8） | owner → sitterA | `ordC2 = createQuotedOrder(…)`；`uploadProof`；保母開 `/sitter/orders/{ordC2}`，嘗試取消 | `sitter-order-cancel-blocked-message`「飼主已上傳付款憑證，請先核對或駁回」，含前往核對區塊（`btn-verify-payment` / `btn-reject-payment` 所在處）的連結（出現時機見 §5 #5） | E9 `{reason:"臨時有事"}` → 409 `PAYMENT_ALREADY_SUBMITTED`；E10 `status=SUBMITTED`；E2 `status=PENDING_PAYMENT`、行程 `PENDING` | 截圖 + API JSON |
| QR-46 | AC-3.2-2 | sitterA | E15 駁回 `{submissionId, reason:"金額不符"}` → E9 `{reason:"臨時有事"}`（新 key） | — | E15 200、`paymentStatus=UNPAID`；E9 200；E10 `status=VOID`；E12 該 submission `status=REJECTED` | API JSON |
| QR-47 | AC-3.3-1（EX-7） | owner、sitterA | `ordC3 = createQuotedOrder(…)`；保母先開 `/sitter/orders/{ordC3}` 不重新整理；另以 API `uploadProof` + E14 確認 → `CONFIRMED`；回頁面取消並送出 | 「訂單已成立，如需取消請申請訂單變更」 | 頁面發出的 E9 → 409 `ORDER_STATE_CONFLICT`、`currentStatus=CONFIRMED`；E2 `status=CONFIRMED` | 截圖 + API JSON |
| QR-48 | AC-3.3-2（EX-7） | sitterA | `ordC4 = createPendingOrder(…)`；E9 | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=PENDING`；**不變** | API JSON |
| QR-49 | AC-3.3-2（EX-7） | owner、sitterA | `ordC5 = createQuotedOrder(…)`；保母先開詳情頁；飼主 E8 `{expectedStatus:"PENDING_PAYMENT"}` 撤回；保母在頁面取消並送出 | 「訂單狀態已變更，請重新整理」 | 頁面發出的 E9 → 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E2 `cancelledBy=OWNER` | 截圖 + API JSON |
| QR-50 | AC-3.4-1（EX-1） | sitterOther | `ordC6 = createQuotedOrder(…)`；E9 | — | 403 `FORBIDDEN` | API JSON |
| QR-51 | AC-3.4-2（EX-2） | sitterA（OWNER 角色） | `ownerToken(sitterA)` E9 | — | 403 `ROLE_MISMATCH` | API JSON |
| QR-52 | AC-3.4-3（EX-3） | admin | E9 | — | 403 `ROLE_MISMATCH` | API JSON |
| QR-53 | AC-3.4-4（EX-5、BR-9） | sitterA | E9 依序 `reason:""`、`reason:"   "`、前後空白 + 201 字 | — | 皆 400 `VALIDATION_FAILED`、`payload.field=reason` | API JSON |
| QR-54 | AC-3.4-5（EX-5） | sitterA | E9 不帶 `Idempotency-Key` | — | 400 `VALIDATION_FAILED`、`payload.field=Idempotency-Key`；QR-50–QR-54 後 `ordC6` **不變**（`PENDING_PAYMENT`、`UNPAID`） | API JSON |
| QR-55 | AC-3.5（EX-10） | owner、sitterA | 3 輪，每輪新 `createQuotedOrder`：同時送出飼主 E11 上傳與保母 E9 | — | 每輪恰一個成立。上傳勝：E9 409 `PAYMENT_ALREADY_SUBMITTED`、E10 `SUBMITTED`、E2 `PENDING_PAYMENT`。取消勝：E11 409 `PAYMENT_STATE_CONFLICT`、E10 `VOID`、E2 `CANCELLED`、E12 `[]`。任何一輪 E12 都不得出現 `voided=true`（payment `VOID` 但 submission 仍 `SUBMITTED`）。GCS 物件刪除見 §4 | API JSON（每輪） |
| QR-56 | AC-3.6-1（EX-14） | sitterA | `ordC7`；E9 key `K` → `B1`；同 key 同 body 再送；key `K` 改原因再送 | — | 第二次 200、body = `B1`、E10 仍 1 筆 `VOID`、雙方通知各仍 1 則；第三次 422 `IDEMPOTENCY_KEY_REUSED` | API JSON |
| QR-57 | AC-3.6-2（EX-15） | sitterLock | `ordC8 = createQuotedOrder(owner, sitterLock, 標準)` → 鎖定 → E9 | — | 200；E10 `VOID`；E2 `cancelledBy=SITTER` | API JSON |
| QR-58 | AC-3.7-1（EX-9、EX-12） | sitterA、排程 | `ordC9 = createQuotedOrder(…)`；`shiftTime(PAYMENT_DUE_AT, paymentId)` 後立即 E9；接著 `runCron(C3)` | — | 平移後 E10 `dueAt` < now；E9 200、`cancelledBy=SITTER`、`reasonCode=SITTER_CANCELLED`；C3 200 後 E2 `cancellation` 不變；雙方無付款逾期通知 | API JSON |
| QR-59 | AC-3.7-2（EX-12） | sitterA、排程 | `ordC10 = createQuotedOrder(…)`；`shiftTime(PAYMENT_DUE_AT)` → `runCron(C3)` → E9 | — | C3 後 E2 `cancelledBy=SYSTEM`、`reasonCode=PAYMENT_EXPIRED`；E9 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；`cancelledBy` 仍 `SYSTEM` | API JSON |

### 2.7 模組（mock）：真後端造不出的回應

> 檔案 `frontend/e2e/order-decline-cancel.spec.ts`；`mockDemoSession(page, 'SITTER')`，E2 / E5 回 `PENDING` 或 `PENDING_PAYMENT` 訂單；`afterEach` 呼叫 `assertNoUnmockedApi`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| QRM-01 | AC-1.5-2（EX-16） | sitter | mock E7 200；mock 轉介送出 API（PRD-010）回 500；婉拒 → 點 `sitter-order-decline-btn-referral` → 選一位夥伴送出 | 「轉介未送出，請稍後再試」，可再按送出；訂單狀態顯示「已取消」 | 重試時再次送出轉介請求；E7 只發出 1 次（婉拒不回滾、不重送） | 截圖 + 請求紀錄 |
| QRM-02 | （EX-13） | sitter | mock E7 連續兩次 409 `ORDER_BUSY` + `Retry-After: 1`；送出婉拒 | 「系統忙碌，請稍後再試」 | 恰 2 次 E7，`Idempotency-Key` 相同 | 截圖 + 請求紀錄 |
| QRM-03 | （EX-7） | sitter | mock E9 回 409 `ORDER_STATE_CONFLICT`、`currentStatus=IN_PROGRESS`；送出取消 | 「訂單已成立，如需取消請申請訂單變更」 | — | 截圖 |
| QRM-04 | （EX-14） | sitter | mock E7 回 422 `IDEMPOTENCY_KEY_REUSED` | 「請重新操作」 | — | 截圖 |
| QRM-05 | （EX-1、EX-2、EX-4） | sitter | mock E7 分別回 403 `FORBIDDEN`、403 `ROLE_MISMATCH`、404 `NOT_FOUND` | 依序「無權限操作此訂單」「請切換至保母身分」「找不到此訂單」 | — | 截圖 |

## 3. 權限與例外（判斷順序與破壞性測試）

> 驗證 PRD-027 BR-14：同時違反兩條規則時回應必須是順序在前者。每列用新訂單、新 key。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| QR-E1 | （§3 未登入） | 不帶 token 呼叫 E7、E9 | 皆 401 `UNAUTHENTICATED`；訂單 **不變** |
| QR-E2 | EX-6 | 保母開 `ordS` 評估頁不重新整理；飼主 E8 撤回；保母在對話框填原因送出婉拒 | E7 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；畫面「訂單狀態已變更，請重新整理」 |
| QR-E3 | BR-14（400 先於 409） | 已 `CANCELLED` 的訂單，E7 `reason:""` | 400 `VALIDATION_FAILED`、`field=reason` |
| QR-E4 | BR-14（`ROLE_MISMATCH` 先於 404） | `ownerToken(sitterA)` 對隨機 UUID 呼叫 E7 | 403 `ROLE_MISMATCH` |
| QR-E5 | BR-14（`FORBIDDEN` 先於 400） | `sitterOther` 對 `sitterA` 的訂單呼叫 E7，`reason:""` | 403 `FORBIDDEN` |
| QR-E6 | BR-14（`ORDER_STATE_CONFLICT` 先於 `PAYMENT_ALREADY_SUBMITTED`） | E9 打 `PENDING` 訂單（無 payment）與 `CONFIRMED` 訂單（payment `VERIFIED`） | 皆 409 `ORDER_STATE_CONFLICT`（`currentStatus` 分別 `PENDING`、`CONFIRMED`），不是 `PAYMENT_ALREADY_SUBMITTED` |
| QR-E7 | （資料污染） | 婉拒原因填 `<script>alert(1)</script>` | 200；飼主 `owner-order-detail-cancel-result` 原樣顯示該字串為文字、未觸發 dialog；通知內文為原字串 |
| QR-E8 | EX-12（排程不覆寫） | QR-26 / QR-58 已涵蓋保母先、排程後；本列再驗 C1 對已報價訂單：`createQuotedOrder` 後 `shiftTime(REVIEW_EXPIRES_AT)` → `runCron(C1)` | 訂單仍 `PENDING_PAYMENT`、payment `UNPAID`（C1 候選只取 `PENDING`） |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.1-1、AC-2.1、AC-3.1 的 `order_logs`（`ORDER_REJECTED` 含原因、`ORDER_REVIEW_EXPIRED`、`ORDER_CANCELLED_BY_SITTER`）與雙方 Email 投遞紀錄；AC-1.1-2 偏好關閉無 Email | 無對外 API 讀 `order_logs` / Email 投遞紀錄；Resend 為測試模式只寄專案擁有者 | `SitterDeclineIntegrationTest`、`ReviewExpiryCronIntegrationTest`、`SitterCancelIntegrationTest`；真實寄信手動驗證 |
| AC-1.4-1 確定性與「`order_logs` 只有一筆取消紀錄」 | 正式站無法控制交錯、無法讀 log | `SitterDeclineIntegrationTest`、`OrderWithdrawConcurrencyIntegrationTest` |
| AC-1.6-2（FREE 本月已 3 筆 `CONFIRMED` 仍可婉拒） | 需造 3 筆 `CONFIRMED`（各含 GCS 上傳）；同樣前置已在 TS-006 Q-80–Q-82 驗證額度計數，本卡只差「婉拒不檢查額度」 | `SitterDeclineIntegrationTest` |
| AC-1.7、AC-3.7 字面時刻（`2026-12-10T16:03:00Z` 等）與「無 `ORDER_REVIEW_EXPIRED` log」 | 正式站無法注入 `Clock`、無法讀 log | `SitterDeclineIntegrationTest`、`SitterCancelIntegrationTest` |
| AC-2.1「`processed=1`」精確值、AC-2.4-1「`processed=0`」精確值 | 正式站排程同時處理其他到期資料，只能斷言 ≥ 1 與本訂單狀態 | `ReviewExpiryCronIntegrationTest` |
| AC-2.2-1 字面時刻（15:59）、AC-2.2-2（`now = review_expires_at` 整點即處理） | 需精確時間 | `ReviewExpiryCronIntegrationTest` |
| AC-2.3-1、AC-2.3-2（候選查出後狀態改變而略過、單筆例外 `failed=1` 其他照常、下輪重試） | 需控制候選查詢與處理之間的時間窗、注入例外 | `ReviewExpiryCronIntegrationTest` |
| AC-2.4-2 log 不含 secret | 無法讀伺服器 log | `InternalCronSecurityTest` |
| AC-3.5 取消先到時「該次上傳的 GCS 物件已刪除」 | 需列 GCS bucket，E2E 無權限 | `PaymentSubmissionConcurrencyIntegrationTest`；孤兒兜底 `OrphanProofSweepIntegrationTest` |
| EX-12 排程與保母操作「同時」 | 無法與正式 Cloud Scheduler 精準交錯 | `SitterDeclineIntegrationTest`、`SitterCancelIntegrationTest`（循序版本由 QR-26–QR-28、QR-58、QR-59 覆蓋） |
| EX-13 鎖等待逾時 409 `ORDER_BUSY`（後端）；排程鎖不到計 `skipped` | 正式站無法持有列鎖 | 後端類別未指名（見 §5 #7）；畫面由 QRM-02 |
| EX-14 同 key 處理中 409 `IDEMPOTENCY_IN_PROGRESS` | 需控制處理中時間窗 | SD-ORDER §10.2 冪等共通必測 |
| EX-16 轉介 API 本身失敗的後端行為 | 屬 PRD-010 | TS-010（PRD-010 改寫後）；本卡畫面由 QRM-01 |
| EX-17 Resend 失敗 | 正式站無法注入故障 | SD-NOTIFY 排程測試 |
| EX-18 GCS | 本卡自身無上傳；併發刪物件見 AC-3.5 列 | — |
| EX-19 排程單筆失敗 | 需注入例外 | `ReviewExpiryCronIntegrationTest` |

## 5. 已知限制 / 待補

| # | 項目 | 影響 | 建議預設值 / 處理 |
|---|------|------|------------------|
| 1 | §10.4 `provision-account` 未寫明能否建 ADMIN、指定顯示名稱；越權需「同一帳號切成 OWNER 角色」的 token | QR-07「王小明」、QR-18、QR-19、QR-51、QR-52 | 請後端確認支援 `role=ADMIN`、`displayName`，SITTER 帳號可經角色切換取得 OWNER token |
| 2 | `sitter-setup` 是否可在建單後重複呼叫只改 `subscriptionExpiresAt`（upsert）未寫明 | QR-25、QR-57 | 請後端確認為 upsert；否則需另給改訂閱到期 / 停權的測試端點 |
| 3 | §10.4 只有 `owner-setup` 能設 `emailPreferences`，`sitter-setup` 沒有；AC-1.1、AC-2.1、AC-3.1 的 Given 要求「雙方」偏好 | QR-11 保母端偏好 | 建議 `sitter-setup` 增 `emailPreferences`，或允許 `owner-setup` 對 SITTER 帳號設定通知偏好 |
| 4 | PRD-010 為 Draft，轉介選單與轉介送出 API 未定義 | QR-05、QRM-01 只能以業務名稱描述 | PRD-010 改寫後回填 testid / API |
| 5 | PRD-027 §4.3 步驟 4 未定 `sitter-order-cancel-blocked-message` 的出現時機：開頁時依 payment `SUBMITTED` 直接顯示（E2 `allowedActions` 不含 `SITTER_CANCEL`、隱藏取消鈕），或按取消收到 409 後才顯示 | QR-45 畫面斷言 | 建議開頁即顯示、不出現 `sitter-order-detail-btn-cancel`（與 `allowedActions` 一致）；QR-45 先依此寫，API 409 另以直接呼叫 E9 驗證 |
| 6 | SD-ORDER E9 寫「`reason` 必填 1–200 字」，未寫「去前後空白」（E7 有）；PRD-027 BR-9 兩者皆去空白；另未定原因是否以去空白後的值保存 | QR-13、QR-53 | 依 PRD BR-9：E9 同樣去空白後判斷；保存去空白後的值。建議 SD 補字 |
| 7 | SD-ORDER §10.3 的 `SitterDeclineIntegrationTest`、`SitterCancelIntegrationTest` 都沒列 `ORDER_BUSY`（EX-13）；`ReviewExpiryCronIntegrationTest` 未列「鎖不到計 `skipped`」 | 後端覆蓋缺口 | 建議兩個類別補列鎖逾時情境，C1 類別補 `SKIP LOCKED` 略過 |
| 8 | 正式站 Cloud Scheduler 每 15 分鐘真的會跑 C1 / C3；QR-26、QR-31、QR-58 要求「排程尚未處理」 | 平移與下一步之間若剛好被正式排程處理，會得到 409 而非 200 | 平移後立即執行下一步；若 E2 已是 `cancelledBy=SYSTEM`，重建訂單重跑一次並在證據註記；連續兩次才判失敗 |
| 9 | 「全空白字元」是否含全形空白 U+3000、不換行空白 U+00A0 未定義（Java `trim()` 只去 ASCII 控制字元與空白，`strip()` 才去 Unicode 空白） | QR-12 只用半形空白與 tab | 建議以 Unicode 空白（`strip()`）為準並補一列 U+3000 測試；需使用者確認 |
| 10 | PRD-027 只寫婉拒時飼主詳情顯示「保母已婉拒此預約」；O4、O7 在 `owner-order-detail-cancel-result` 的標題文案未定 | QR-44 只斷言原因；O4 未斷言飼主畫面 | 建議 O7「保母已取消此訂單」、O4「保母未於期限內回覆，預約已自動取消」 |
| 11 | 通知查詢 API 屬 SD-NOTIFY（未撰寫），通知類型代碼是否對外輸出未定 | QR-07、QR-33、QR-43 等 | 以標題 / 內文斷言 |
| 12 | 無對外 API 可讀 `order_logs` | §4 第 1、2 列只能靠後端 | 建議 §10.4 增唯讀 `GET /api/internal/test-data/orders/{orderId}/logs`（限 `@e2e-journey.test`） |
| 13 | AC-1.5-3「不送任何轉介通知」需保母有信任圈夥伴才有意義；§10.4 無建立信任圈關係的測試端點 | QR-10 只能驗飼主端無轉介通知 | 建議 `sitter-setup` 增 `trustCirclePartnerIds`（PRD-010 範圍） |

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 建立，狀態 Draft。原 TS-006 Scenario 10（婉拒）依 PRD 拆卡移入並依新規格重寫（原因必填、通知雙方、兩步驟轉介、期限後仍可婉拒） | PRD-027、SD-ORDER Approved 2026-11-22 |
