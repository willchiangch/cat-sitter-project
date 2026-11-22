# TS-016: 訂單變更：發起、報價與協商期限

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 對應 PRD | PRD-016（Approved 2026-11-22）；旅程中順帶使用 PRD-023（同意 / 拒絕，只作前置或後續動作）、PRD-007（付款憑證，只作前置）、PRD-009（結案，只作前置） |
| 對應 SD | SD-ORDER（§3.3、§6.3 E2、E17–E23、C1–C10、§6.5、§7.1、§7.2、§8.1 C5 / C6、§9.1、§9.3、§10.4）；SD-FULFILL（§6.3 F1、FT1 `force-status`、FT2 `shift-time`） |
| 類型 | 旅程（打真後端）：變更子流程（申請 / 報價 / 拒絕 / 撤回 / 逾時）、跨角色互動、權限、冪等全部走真後端 |
| 自動化路徑 | `frontend/e2e/journeys/order-modification-initiate.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> 本檔重寫自舊版 TS-016（舊版對應已拆卡前的 PRD-016 / SD-016，含舊狀態 `MODIFYING`、`REFUND_VERIFY`、`previous_status` 復原等已廢止流程）。檔名與 PRD-016 slug 相同，直接覆寫；舊版內容中補款 / 退款 / 結清移至 TS-023，款項爭議移至 TS-024。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 自己建資料、可獨立重跑。所有帳號為 `@e2e-journey.test`，測試資料端點（`/api/internal/test-data/**`）與排程端點一律帶 `X-Internal-Secret`（值取自 CI 環境變數，不得寫入檔案或證據）。
> 日期記號：`D` = 執行當下 Asia/Taipei 的日期；`D+n` = D 之後第 n 天。所有日期由 spec 在執行時計算。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `POST /api/internal/test-data/provision-account`（`roles=[OWNER]`、`displayName`）；`POST /api/internal/test-data/owner-setup`：`pets` = key M1、M2、M3（CAT，`deleted=false`），`emailPreferences.ORDER_AFFAIR=開`；回應以 key 取 `petId` | — |
| `ownerB` | OWNER | `provision-account`（`roles=[OWNER]`）；`owner-setup` 一隻毛孩 | 與 `order` 無關的第三人 |
| `sitter` | OWNER + SITTER | `provision-account`（`roles=[OWNER, SITTER]`，雙角色，登入後以既有角色切換取得 `currentRole=OWNER` 或 `SITTER` 的 token）；`POST /api/internal/test-data/sitter-setup`：`tier=PRO`（建單期間用，避免 FREE 每月 3 單上限）、`subscriptionExpiresAt = now + 30 天`、`suspended=false`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄、`plans` = key P（單價 500、CAT、無可預約區間、`active=true`）與 key Q（單價 800、CAT、`active=true`）、`questions=[]`、`emailPreferences.ORDER_AFFAIR=開`；回應以 key 取 `planId`。建單完成後以 `sitter-setup` upsert 只送 `tier` 改成該情境要的等級（預設 BASIC） | — |
| `sitterB` | SITTER | 同 `sitter`（`roles=[SITTER]`），與 `order` 無關 | 第三人 |
| `admin` | ADMIN | `provision-account`（`roles=[ADMIN]`） | — |
| `order` | — | `owner` 以 E1 `POST /api/orders` 預約 `sitter`：`groups=[{planId: P, dates: [D+3, D+4, D+5], dailyCount: 1, petIds: [M1, M2]}]`、`expectedAmount=1500` → `sitter` E6 原價報價（`expectedAmount=1500`）→ `owner` E11 上傳首次付款憑證（`disclaimerAgreed=true`、`lastFive=12345`、PNG 檔）→ `sitter` E14 確認 → 以 F1 `GET /api/orders/{order}/visits` 依 `serviceDate` 取 V1（D+3）、V2（D+4）、V3（D+5） | `CONFIRMED`；E2 `quote.totalAmount=1500`、`netPaid=1500` |
| `orderIP` | — | `order` 建好後以 FT1 `POST /api/internal/test-data/visits/{V1}/force-status`（`status=DONE`、`careLogSubmitted=true`） | `IN_PROGRESS`（O9）；V1 `DONE`，V2、V3 `PENDING` |
| `order2` | — | 同 `order`，同一組 `owner` / `sitter` 的另一張單 | `CONFIRMED`（只用來取「不屬本訂單」的行程 id） |

- 建單 helper（建議名 `bootstrapConfirmedOrder({ tier, visitStates })`）由 e2e-author 實作；每個 test 自建一組 `owner` / `sitter` / `order`，同一 test 需要多張單時在 `tier=PRO` 下建立後再以 upsert 降級。
- **斷言來源**：
  - 目前總額：E2 `quote.totalAmount`（SD-ORDER E2：為目前生效的 `orders.total_amount`），並與 `GET /api/internal/test-data/orders/{order}/inspect` 的 `totalAmount` 互相核對；已收淨額：E2 `netPaid`。
  - 變更列數：E18 `GET /api/orders/{order}/modifications` 陣列長度；變更內容：E19。
  - `order_logs`：`inspect.orderLogs`（`actionType`、`operatorRole`、`payload`）；同一步驟只斷言本步驟新增的列（以步驟前的列數為基準）。
  - 通知：`inspect.notifications`（`type`、`recipientRole`、`mandatory`、`emailStatus`）斷言種類、收件者與筆數；雙方 `ORDER_AFFAIR` 偏好皆開，測試帳號應寄 Email 者 `emailStatus=SUPPRESSED_TEST`（Email 斷言一律收斂成此值）；畫面文案以收件者 token 呼叫 `GET /api/notifications`（SD-014）篩同一 `type` 斷言。
  - 報價明細：E2 `quote.original`（原報價）、`quote.adjustments[]`（`modificationId`、`kind`、`totalBefore`、`totalAfter`、`amountChange`）、`quote.totalAmount`；畫面 `owner-order-detail-quote-breakdown` 內 `order-detail-quote-original-total`、`order-detail-quote-modification-{modificationId}`（無調整時整段不顯示）、`order-detail-quote-current-total`，雙方頁面相同。
  - 變更精靈資料：`groups[].bookableFrom` / `bookableTo` 雙方呼叫 E2 都回傳（保母當下 PRO / ULTIMATE 未鎖定時才有值，否則不輸出，與 E31 一致）；`sitterTier`、`modificationPricingMode` 只在 SITTER 呼叫時回傳。
  - 報價明細只列 `amountChange ≠ 0` 的事件；差額 0 的 `SETTLED` 或補款逾時不列，全部為 0 時整段變更列不顯示。
- **進入協商頁**：一律從訂單詳情點入——未結束變更點 `owner-order-detail-btn-open-modification` / `sitter-order-detail-btn-open-modification`（文案「查看進行中變更」），已結束變更點變更歷史 `modification-history-item-{modificationId}`；不直接輸入網址。步驟中「開協商頁」即指此路徑，落地頁為 `/orders/{order}/modifications/{mod}`。
- **狀態文字**：變更狀態在畫面上的中文依 SD-FRONTEND-SPEC §6.4（`REQUESTED` 待保母報價、`QUOTED` 待飼主回應、`AWAITING_SUPPLEMENT` 待補款、`AWAITING_REFUND` 待退款、`SETTLED` 已生效、`REJECTED` 已拒絕、`WITHDRAWN` 已撤回、`EXPIRED` 已逾期；該表尚待使用者確認，確認前以 SD-FRONTEND-SPEC 當下內容為準）。
- **排程**：C5 / C6 一律帶 `?orderId={order}`，只處理該測試訂單，回應計數只算該單，斷言精確值（`processed` / `skipped` / `failed`）。自動排程不會處理測試帳號訂單。
- **時間**：`expiresAt` 等時間以「送出前時間 t0、收到回應時間 t1」夾住，斷言 `t0 + 72h ≤ expiresAt ≤ t1 + 72h`；精確到固定時鐘的斷言（例 `2026-11-24T02:00:00Z`）交後端整合測試（§4）。
- **金額顯示**：畫面金額一律依 SD-FRONTEND-SPEC §6.4.1 格式 `$ 1,800`（`$` 後為 U+00A0）。PRD 文字「應補款 NT$X」在畫面上呈現為「應補款 $ X」。

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4 Cleanup 擴充與跨模組範圍：訂單全套含 `initiated_by=SYSTEM` 變更、`payments`、`admin_tasks`、`order_logs`、`idempotency_records`，再清通知、方案、毛孩、帳號）。

## 2. 測試步驟

> 步驟 ID 用 `{前綴}-NN`，e2e spec 的 `step()` / `apiStep()` 標籤必須以同一 ID 開頭。前綴：`MI` = 子模組 1 發起；`MQ` = 子模組 2 報價與拒絕；`MW` = 子模組 3 撤回、逾時、協商頁與冪等；`ME` = §3 權限與例外。
> 「API / 資料斷言」寫具體值；E17–E23 一律帶 `Idempotency-Key`（UUID），除非該步驟另寫。

### 2.1 子模組 1：發起變更

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| MI-01 | AC-1.1 | owner | 以 `orderIP` 開啟 `/owner/orders/{order}`，點 `owner-order-detail-btn-modify` | 導向 `/orders/{order}/modify`；有 `order-modify-type-CHANGE`、`order-modify-type-CANCEL_ALL`；`order-modify-visit-checkbox-{V2}`、`order-modify-visit-checkbox-{V3}` 可勾選；V1（`DONE`）沒有可勾選的 checkbox（不存在或 disabled） | E2 `allowedActions` 含 `REQUEST_MODIFICATION` | 截圖 + API JSON |
| MI-02 | AC-1.1 | owner | 選 `order-modify-type-CHANGE`；勾 `order-modify-visit-checkbox-{V3}`；點 `order-modify-btn-add-item`（出現 `order-modify-add-item-0`）；在 `order-modify-add-item-0-date` 填 D+7、`order-modify-add-item-0-plan` 選 P、`order-modify-add-item-0-daily-count` 填 1，毛孩維持預設；`order-modify-input-reason` 填「提早回國」；點 `order-modify-btn-submit` | 送出前 `order-modify-add-item-0-pet-checkbox-{M1}`、`-{M2}` 皆勾選，`-{M3}` 不存在（截圖）；送出後導向 `/orders/{order}/modifications/{mod}`，狀態文字「待保母報價」（依 SD-FRONTEND-SPEC §6.4）；`modification-detail-expires-at` 顯示 `expiresAt` 換算的 Asia/Taipei `YYYY-MM-DD HH:mm`；飼主只看到 `modification-btn-withdraw` | 頁面發出的 E17 恰一次，201：`status=REQUESTED`、`initiatedBy=OWNER`、`systemAmount` / `newTotalAmount` / `diff` 不輸出；E18 該列 `createdAt` 與 E19 `expiresAt` 相差 72h（±2 秒）；E19 `requestedContent.removeVisitIds=[V3]`、`addItems[0]` = D+7 / P / 1 / `petIds={M1,M2}` | 截圖 + API JSON |
| MI-03 | AC-1.1 | — | 查訂單、行程、金額、付款、稽核 | — | E2 `status=IN_PROGRESS`、`openModification.status=REQUESTED`、`quote.totalAmount=1500`、`quote.adjustments=[]`；F1 V3 `PENDING`、無 D+7 行程；E10 只有 1 筆 `purpose=INITIAL`；`inspect.orderLogs` 本步新增恰 1 筆 `actionType=MODIFICATION_REQUESTED`、`operatorRole=OWNER`、`payload.modificationId`=mod、`payload.reason=提早回國`、`payload.expiresAt` = E19 `expiresAt` | API JSON |
| MI-04 | AC-1.1 | sitter | 查 `inspect.notifications`；sitter `GET /api/notifications` | — | `inspect` 恰 1 筆 `type=MODIFICATION_REQUESTED`、`recipientRole=SITTER`、`mandatory=false`、`emailStatus=SUPPRESSED_TEST`；sitter 通知列表該筆文案含「飼主提出訂單變更」、連結 `/orders/{order}/modifications/{mod}` | API JSON |
| MI-20 | AC-1.1 | owner、sitter | 雙方各開訂單詳情（`/owner/orders/{order}`、`/sitter/orders/{order}`），點 `owner-order-detail-btn-open-modification` / `sitter-order-detail-btn-open-modification` | 兩個按鈕文案皆「查看進行中變更」，點擊後落地 `/orders/{order}/modifications/{mod}`；報價明細只有 `order-detail-quote-original-total`「$ 1,500」與 `order-detail-quote-current-total`「$ 1,500」，`order-detail-quote-modification-*` 數量 0；`owner-order-detail-btn-modify` 不存在（已有未結束變更） | E2 `openModification.modificationId`=mod | 截圖 + API JSON |
| MI-05 | AC-1.2 | owner | 以 `order`（`CONFIRMED`）點 `owner-order-detail-btn-modify` → 選 `order-modify-type-CANCEL_ALL`、原因「行程取消」、送出 | 導向協商頁 | E17 201：`status=REQUESTED`；E19 `type=CANCEL_ALL`；F1 V1、V2、V3 皆 `PENDING` | 截圖 + API JSON |
| MI-06 | AC-1.2 | owner | 另一張 `CONFIRMED` 單，E17 `CANCEL_ALL` 依序送：`reason="   "`、`reason`=201 字、`reason`=200 字（每次換 key） | — | 前兩次 400 `VALIDATION_FAILED`（`payload.field=reason`），E18 長度 0、`inspect.orderLogs` 無 `MODIFICATION_*`；第三次 201、`REQUESTED` | API JSON |
| MI-07 | AC-1.3 | sitter | `sitter` 降為 BASIC；以 `order` 開 `/sitter/orders/{order}` 點 `sitter-order-detail-btn-modify` → 選 CHANGE、勾 `order-modify-visit-checkbox-{V3}`、原因「臨時住院」、送出 | 送出前 `modification-input-surcharge`、`modification-input-discount`、`modification-input-adjustment-reason` 為停用（BASIC 不可自訂；sitter E2 `sitterTier=BASIC`、`modificationPricingMode=SYSTEM_ONLY`）；送出後導向協商頁，`modification-detail-diff` 顯示「應退款 $ 500」；保母只看到 `modification-btn-withdraw` | E17 201：`status=QUOTED`、`initiatedBy=SITTER`、`systemAmount=1000`、`newTotalAmount=1000`、`netPaid=1500`、`diff=-500`；`expiresAt` 在 `[t0+72h, t1+72h]`；`inspect.orderLogs` 新增 1 筆 `MODIFICATION_PROPOSED`（`operatorRole=SITTER`、`payload.newTotalAmount=1000`、`payload.diff=-500`） | 截圖 + API JSON |
| MI-08 | AC-1.3 | owner | 收通知後開協商頁 | `modification-btn-accept`、`modification-btn-reject` 可見；`modification-btn-withdraw` 不存在；`modification-detail-diff`「應退款 $ 500」 | `inspect.notifications` 1 筆 `type=MODIFICATION_PROPOSED`、`recipientRole=OWNER`；E19 `allowedActions=[ACCEPT, REJECT]` | 截圖 + API JSON |
| MI-09 | AC-1.4 | owner、sitter | 建 `orderPending`（只做 E1）與 `orderPP`（E1 + E6）；雙方各以 E17（`CHANGE`、新增 D+7、原因「測試」）呼叫 | — | 皆 409 `ORDER_STATE_CONFLICT`，`payload.currentStatus` 分別為 `PENDING`、`PENDING_PAYMENT`；兩單 E18 長度 0 | API JSON |
| MI-10 | AC-1.4 | owner、sitter | 建 `orderDone`：FT1 V1、V2、V3 皆 `DONE`（`careLogSubmitted=true`）→ owner E25 結案（`COMPLETED`）；建 `orderCxl`：E1 後 owner E8 撤回（`expectedStatus=PENDING`，`CANCELLED`）；雙方各 E17 | — | 皆 409 `ORDER_STATE_CONFLICT`，`currentStatus` = `COMPLETED` / `CANCELLED`；E18 長度 0 | API JSON |
| MI-11 | AC-1.4 | owner、sitter | 以 `order`：sitter E17 減少 V3（`QUOTED` 1000）→ owner E23 同意（`AWAITING_REFUND`）→ owner E24 申報爭議（說明「測試」，訂單 `DISPUTED`）；雙方各 E17 | — | 皆 409 `ORDER_STATE_CONFLICT`（`currentStatus=DISPUTED`），不是 `MODIFICATION_IN_PROGRESS`；E18 長度仍 1 | API JSON |
| MI-12 | AC-1.4 | owner | 以 `order`：FT1 V1 `DONE`、V3 `DONE`、V2 `CLOSED_BY_SYSTEM`；owner E17 `CANCEL_ALL`；再 E17 `CHANGE` 只新增 D+7；開 `/owner/orders/{order}` | 訂單詳情沒有 `owner-order-detail-btn-modify` | 兩次皆 409 `MODIFICATION_NOT_ALLOWED`；E18 長度 0；E2 `allowedActions` 不含 `REQUEST_MODIFICATION` | 截圖 + API JSON |
| MI-13 | AC-1.5 | owner、sitter | 以 `order`：owner E17 減少 V3（`REQUESTED`）→ 雙方各 E17；sitter E20 報價（`QUOTED`）→ 雙方各 E17 | — | 四次皆 409 `MODIFICATION_IN_PROGRESS`；E18 長度 1 | API JSON |
| MI-14 | AC-1.5 | owner、sitter | `orderSup`：owner E17 新增 D+7 → sitter E20（2000、差額 +500）→ owner E23（`AWAITING_SUPPLEMENT`）；`orderRef`：sitter E17 減少 V3 → owner E23（`AWAITING_REFUND`）；兩單雙方各 E17 | — | 皆 409 `MODIFICATION_IN_PROGRESS`；兩單 E18 長度各 1 | API JSON |
| MI-15 | AC-1.5 | owner、sitter | 以 `order` 依序：owner E17 → sitter E21 拒絕（原因「不便」）→ owner E17 → owner E22 撤回 → owner E17 → `POST /api/internal/test-data/orders/{order}/shift-time`（`target=MODIFICATION_EXPIRES_AT`、`modificationId`、`minutes=-(72*60+1)`）→ C5 `POST /api/internal/cron/modifications/expiry?orderId={order}` → sitter E17（減少 V3、新增 D+7，新總額 1500、差額 0）→ owner E23（`SETTLED`）→ owner E17 `CANCEL_ALL` | — | C5 回 `processed=1`、`skipped=0`、`failed=0`；每次在前一筆進入 `REJECTED` / `WITHDRAWN` / `EXPIRED` / `SETTLED` 後的發起皆 201；E18 依 `createdAt` 倒序最後為 5 列，狀態依序 `REQUESTED`、`SETTLED`、`EXPIRED`、`WITHDRAWN`、`REJECTED` | API JSON |
| MI-21 | AC-1.5 | owner、sitter | 接 MI-15：雙方各開訂單詳情看報價明細 | `order-detail-quote-original-total`「$ 1,500」、`order-detail-quote-current-total`「$ 1,500」；`order-detail-quote-modification-*` 數量 0（SETTLED 那筆差額 0 不列，REJECTED / WITHDRAWN / EXPIRED 也不列）；雙方內容相同。有差額的變更列見 TS-023 SS-02、SS-11 | E2 `quote.adjustments=[]`；`quote.totalAmount=1500` | 截圖 + API JSON |
| MI-16 | AC-1.6 | owner | 以 `order`：FT1 V1 `DONE`、V2 `IN_PROGRESS`；owner E17 `CHANGE` 減少 [V1]；再減少 [V2] | — | 409 `VISIT_ALREADY_STARTED`，`payload.visitIds` 分別為 [V1]、[V2]；E18 長度 0 | API JSON |
| MI-17 | AC-1.6 | owner | 同上訂單：E17 減少 `order2` 的 V1；E17 `CHANGE` 不帶 `removeVisitIds` 也不帶 `addItems` | — | 前者 400 `VALIDATION_FAILED`（`field` 指向 `removeVisitIds`）；後者 422 `MODIFICATION_EMPTY`；E18 長度 0 | API JSON |
| MI-18 | AC-1.8 | owner | 以 `order`，E17 `CHANGE` 只新增一筆，依序：`petIds=[]`；`petIds=[M3]`；`dailyCount=4`；V2 日期 / P / `dailyCount=3`；58 筆不同新日期（D+10 起連續）各 1 次（變更後 61 個日期）；方案 Q | — | 前五次 400 `VALIDATION_FAILED`（前兩次 `field` 指向 `addItems[0].petIds`）；方案 Q → 422 `PLAN_NOT_IN_ORDER`（`payload.planId`=Q）；E18 長度 0 | API JSON |
| MI-19 | AC-1.8 | owner | UI：從 `owner-order-detail-btn-modify` 進精靈，選 CHANGE，點 `order-modify-btn-add-item`，`order-modify-add-item-0-date` 填 V2 日期、`-plan` 選 P、`-daily-count` 填 2，取消勾選 `order-modify-add-item-0-pet-checkbox-{M2}`，原因「多一次」，送出 | 導向協商頁 | E17 201；E19 `requestedContent.addItems[0]` = V2 日期 / P / 2 / `petIds=[M1]` | 截圖 + API JSON |

### 2.2 子模組 2：保母報價與拒絕

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| MQ-01 | AC-2.1 | sitter | `sitter` BASIC；`orderIP` 上 owner E17（減少 V3、新增 D+7 一次）；保母開協商頁 | 顯示飼主期望內容、原因「提早回國」、`modification-detail-diff`「無差額」；`modification-btn-quote`、`modification-btn-reject` 可見；`modification-input-surcharge` / `-discount` / `-adjustment-reason` 停用 | E19 `status=REQUESTED`、`allowedActions=[QUOTE, REJECT]` | 截圖 + API JSON |
| MQ-02 | AC-2.1 | sitter | 記下 E19 `expiresAt` 為 e0；點 `modification-btn-quote`（內容維持預填） | 報價後兩顆按鈕消失，顯示唯讀報價與到期時間；狀態文字「待飼主回應」（依 SD-FRONTEND-SPEC §6.4） | E20 200：`status=QUOTED`、`systemAmount=1500`、`newTotalAmount=1500`、`diff=0`；`expiresAt` 在 `[t0+72h, t1+72h]` 且 > e0；E19 `quotedContent.addItems[0].petIds={M1,M2}`；`inspect.orderLogs` 新增 1 筆 `MODIFICATION_QUOTED`（`operatorRole=SITTER`、`payload.pricingMode` 有值）；`inspect.notifications` 1 筆 `MODIFICATION_QUOTED` → OWNER，通知列表文案「保母已回覆變更報價」 | 截圖 + API JSON |
| MQ-03 | AC-2.2 | sitter | `sitter` BASIC；`order` 上 owner E17 減少 V3；sitter E20 依序：`surchargeAmount=200`+`adjustmentReason`；`discountAmount=100`+原因 | — | 皆 403 `PLAN_REQUIRED`；E19 仍 `REQUESTED`、`expiresAt` 不變 | API JSON |
| MQ-04 | AC-2.2 | sitter | `sitter-setup` upsert `tier=FREE`；E20 `surchargeAmount=200`+原因；再 E20 `surchargeAmount=200`+原因+`addItems=[{date: D, planId: P, dailyCount: 1, petIds: [M1]}]` | — | 前者 403 `PLAN_REQUIRED`；後者 422 `INVALID_VISIT_DATE`（先於 `PLAN_REQUIRED`，BR-28）；仍 `REQUESTED` | API JSON |
| MQ-05 | AC-2.2 | sitter | E20 `surchargeAmount=0`、`discountAmount=0` | — | 200、`QUOTED`、`newTotalAmount=1000`（= `systemAmount`） | API JSON |
| MQ-06 | AC-2.3 | sitter | `sitter` PRO；`order` 上 owner E17 減少 V3、新增 D+7 兩次；sitter E20 依序：`surchargeAmount=300` 無原因；`discountAmount=2001`+原因 | — | 前者 400 `VALIDATION_FAILED`（`field=adjustmentReason`）；後者 422 `INVALID_MODIFICATION_AMOUNT`；皆仍 `REQUESTED` | API JSON |
| MQ-07 | AC-2.3 | sitter | UI：保母協商頁 `modification-input-surcharge` 填 300、`modification-input-adjustment-reason` 填「節日加成」，點 `modification-btn-quote` | 保母頁差額「應補款 $ 800」；飼主頁 `modification-detail-diff`「應補款 $ 800」 | E20 200：`systemAmount=2000`、`surchargeAmount=300`、`newTotalAmount=2300`、`diff=800` | 截圖 + API JSON |
| MQ-08 | AC-2.3 | owner、sitter | owner E22 撤回 → owner E17 同內容 → sitter E20 `discountAmount=2000`+原因 | 飼主頁 `modification-detail-diff`「應退款 $ 1,500」 | 200：`newTotalAmount=0`、`diff=-1500` | 截圖 + API JSON |
| MQ-09 | AC-2.4 | sitter | `sitter` BASIC；`order` 上 FT1 V1 `DONE`、V2 `CLOSED_BY_SYSTEM`；owner E17 `CANCEL_ALL`；sitter UI 點 `modification-btn-quote` | 差額「應退款 $ 500」 | E20 200：`quotedContent.removeVisitIds=[V3]`、`newTotalAmount=1000`、`diff=-500` | 截圖 + API JSON |
| MQ-10 | AC-2.4 | sitter | 另一張 `CONFIRMED` 單，owner E17 `CANCEL_ALL` → sitter E20 | — | 200：`removeVisitIds` = {V1, V2, V3}、`newTotalAmount=0`、`diff=-1500` | API JSON |
| MQ-11 | AC-2.4 | sitter | 另一張單：FT1 V1 `DONE`、V2 `CLOSED_BY_SYSTEM`；owner E17 `CANCEL_ALL`（`REQUESTED`）→ FT1 V3 `IN_PROGRESS` → sitter UI 點 `modification-btn-quote` | 顯示「此訂單已無可取消的行程，請拒絕此變更」；`modification-btn-reject` 仍可用 | E20 422 `MODIFICATION_EMPTY`；E19 仍 `REQUESTED` | 截圖 + API JSON |
| MQ-12 | AC-2.5 | owner | `sitter` BASIC；`order` 上 owner E17 新增日期 D；再新增 D+1 | — | 前者 422 `INVALID_VISIT_DATE`（`payload.dates` 含 D），E18 長度 0；後者 201 | API JSON |
| MQ-13 | AC-2.5 | sitter | 另一張單 owner E17 新增 D+2（`REQUESTED`）；sitter E20 `addItems` 改為 D；再改為 D+1 | — | 前者 422 `INVALID_VISIT_DATE`、仍 `REQUESTED`；後者 200、`QUOTED` | API JSON |
| MQ-14 | AC-2.5 | owner、sitter | 以 BASIC 建 `order`（D+3~D+5）；`sitter-setup` upsert `tier=PRO` 並將 `plans` key P 的可預約區間設為 D+10 ~ D+40；保母從 `sitter-order-detail-btn-modify` 進精靈；飼主從 `owner-order-detail-btn-modify` 進精靈；之後 upsert `tier=BASIC` 再由雙方重開精靈，最後 upsert 回 `tier=PRO` | PRO 時雙方精靈都顯示方案 P 可預約區間（D+10 ~ D+40）；保母精靈加價 / 折扣欄可用；飼主精靈不顯示保母等級或計價模式資訊；BASIC 時雙方精靈都不顯示區間（常態開放） | PRO 時 sitter 與 owner E2 皆 `groups[P].bookableFrom=D+10`、`bookableTo=D+40`；BASIC 時雙方皆不輸出 `bookableFrom` / `bookableTo`。sitter E2：PRO 時 `sitterTier=PRO`、`modificationPricingMode=CUSTOM_ALLOWED`，BASIC 時 `modificationPricingMode=SYSTEM_ONLY`；owner E2 任何時候都不輸出 `sitterTier`、`modificationPricingMode` | 截圖 + API JSON |
| MQ-15 | AC-2.5 | owner、sitter | owner E17 新增 D+12（`REQUESTED`）；sitter E20 `addItems` 改為 D+8；再改回 D+12 | — | D+8 → 422 `INVALID_VISIT_DATE`、`payload.reason=PLAN_NOT_IN_RANGE`、仍 `REQUESTED`；D+12 → 200（原 D+3~D+5 不檢查） | API JSON |
| MQ-16 | AC-2.5 | owner、sitter | owner E22 撤回；upsert `tier=BASIC`（區間保留）；owner E17 新增 D+12 → sitter E20 改 D+8 → 200。owner E22 撤回；upsert `tier=PRO`、`subscriptionExpiresAt = now − 2 天`（鎖定）；owner E17 新增 D+12 → sitter E20 改 D+8 | — | 兩次報價皆 200、`QUOTED`、`quotedContent.addItems[0].date=D+8` | API JSON |
| MQ-17 | AC-2.6 | sitter | `order` 上 owner E17（`REQUESTED`）；保母 UI 點 `modification-btn-reject`，`modification-input-reject-reason` 留空送出 | 欄位下方錯誤提示 | 若有送出請求：E21 400 `VALIDATION_FAILED`（`field=reason`）；E19 仍 `REQUESTED` | 截圖 + API JSON |
| MQ-18 | AC-2.6 | sitter、owner | `modification-input-reject-reason` 填「當週無法服務」送出 | 協商頁顯示唯讀結果：已拒絕、結束時間、拒絕原因 | E21 200：`status=REJECTED`、`endedAt` 在 `[t0, t1]`；E19 `rejectReason=當週無法服務`；F1、E2 `quote.totalAmount=1500`、`status=CONFIRMED` 不變；`inspect.orderLogs` 新增 1 筆 `MODIFICATION_REJECTED`（`operatorRole=SITTER`、`payload.reason=當週無法服務`）；`inspect.notifications` 1 筆 `MODIFICATION_REJECTED_BY_SITTER` → OWNER，通知列表文案含「當週無法服務」；owner 再 E17 → 201 | 截圖 + API JSON |
| MQ-19 | AC-2.6 | sitter | 另一張單：owner E17 → sitter E20（`QUOTED`）→ sitter E21 帶 `reason=不想做` | — | 409 `MODIFICATION_STATE_CONFLICT`（`currentStatus=QUOTED`）；仍 `QUOTED` | API JSON |
| MQ-20 | AC-2.6 | owner、sitter | owner E21 拒絕該 `QUOTED`（PRD-023）→ sitter E17（減少 V2、新增 D+8） | — | owner 拒絕 200 `REJECTED`；sitter 發起 201：`status=QUOTED`、`initiatedBy=SITTER` | API JSON |
| MQ-21 | AC-2.7 | sitter | `sitter` PRO；owner E17 減少 V3；sitter E20 依序：`surchargeAmount=1000000`+原因；`surchargeAmount=999000`+原因；`surchargeAmount=100`+原因 201 字 | — | 三次皆 400 `VALIDATION_FAILED`（分別指向 `surchargeAmount`、新總額上限 `max=999999`、`adjustmentReason`）；仍 `REQUESTED` | API JSON |
| MQ-22 | AC-2.7 | sitter | E20 `surchargeAmount=998999`+原因 | 飼主頁 `modification-detail-diff`「應補款 $ 998,499」 | 200、`newTotalAmount=999999` | 截圖 + API JSON |
| MQ-23 | AC-2.7 | owner、sitter | owner E22 撤回 → owner E17 減少 V3 → sitter E20 `surchargeAmount=100`+原因 200 字 | — | 200、`newTotalAmount=1100` | API JSON |
| MQ-24 | AC-2.8 | sitter | `sitter` PRO 時 owner E17 減少 V3；upsert `tier=BASIC`；sitter E20 `surchargeAmount=200`+原因；再 E20 不帶加減 | — | 前者 403 `PLAN_REQUIRED`；後者 200、`newTotalAmount=1000` | API JSON |
| MQ-25 | AC-2.8 | sitter | 另一張單：`sitter` BASIC 時 owner E17 減少 V3；upsert `tier=PRO`；sitter E20 `discountAmount=100`+原因 | — | 200、`newTotalAmount=900` | API JSON |
| MQ-26 | AC-2.8 | sitter | 另一張單：owner E17 減少 V3；upsert `tier=PRO`、`subscriptionExpiresAt = now − 2 天`；保母開協商頁 | `modification-input-surcharge` 等停用並顯示「訂閱已逾期，變更金額依系統計算」 | sitter E2 `sitterTier=PRO`、`modificationPricingMode=SYSTEM_ONLY`、`groups[P].bookableFrom` 不輸出；直接以 API 送 E20 `surchargeAmount=200`+原因 → 403 `PLAN_REQUIRED`；仍 `REQUESTED` | 截圖 + API JSON |
| MQ-27 | AC-2.8 | sitter | 同上，E20 不帶加減 | — | 200、`QUOTED`、`newTotalAmount=1000`（不回 403 `SUBSCRIPTION_LOCKED`） | API JSON |
| MQ-28 | AC-2.8 | sitter | 另一張單：upsert `tier=PRO`、`suspended=true`；owner E17 減少 V3；sitter E20 `surchargeAmount=200`+原因；再 E20 不帶加減 | — | 403 `PLAN_REQUIRED`；200、`newTotalAmount=1000` | API JSON |

### 2.3 子模組 3：撤回、逾時、協商頁與冪等

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| MW-01 | AC-3.1 | owner | `order` 上 owner E17（`REQUESTED`）；飼主從 `owner-order-detail-btn-open-modification` 進協商頁，點 `modification-btn-withdraw`；回訂單詳情 | 顯示唯讀結果：狀態文字「已撤回」（依 SD-FRONTEND-SPEC §6.4）、結束時間（Asia/Taipei）；回訂單詳情後 `owner-order-detail-btn-open-modification` 消失、`owner-order-detail-btn-modify` 重新出現 | E22 200：`status=WITHDRAWN`、`endedAt` 在 `[t0, t1]`；F1、E2 `quote.totalAmount=1500`、`status` 不變；`inspect.orderLogs` 新增 1 筆 `MODIFICATION_WITHDRAWN`（`operatorRole=OWNER`、`payload.fromStatus=REQUESTED`）；`inspect.notifications` 1 筆 `MODIFICATION_WITHDRAWN` → SITTER，通知列表文案「飼主已撤回變更請求」 | 截圖 + API JSON |
| MW-02 | AC-3.1 | owner | 另一筆：owner E17 → sitter E20（`QUOTED`）→ owner E22 | — | 200、`WITHDRAWN`、`endedAt` 有值；`payload.fromStatus=QUOTED`；sitter 收到通知 | API JSON |
| MW-03 | AC-3.2 | owner、sitter | sitter E17 減少 V3（`QUOTED`）；owner E22；保母協商頁點 `modification-btn-withdraw` | 保母頁撤回後顯示唯讀結果 | owner → 403 `FORBIDDEN`、仍 `QUOTED`；保母 → 200 `WITHDRAWN`、`endedAt` 有值；`inspect.notifications` 1 筆 `MODIFICATION_WITHDRAWN` → OWNER | 截圖 + API JSON |
| MW-04 | AC-3.2 | owner、sitter | `orderSup`（owner 發起，`AWAITING_SUPPLEMENT`）：owner E22、sitter E22；`orderRef`（sitter 發起，`AWAITING_REFUND`）：sitter E22、owner E22 | — | 發起者 → 409 `MODIFICATION_STATE_CONFLICT`（`currentStatus` 為該狀態）；另一方 → 403 `FORBIDDEN`；狀態不變 | API JSON |
| MW-05 | AC-3.2 | owner、sitter | 建 SYSTEM 退款變更：owner E17 減少 V3、新增 D+7 兩次 → sitter E20（2000）→ owner E23（補款 500）→ shift-time `PAYMENT_DUE_AT`（`paymentId`=補款、`minutes=-(72*60+1)`）→ C6 `POST /api/internal/cron/modifications/supplement-expiry?orderId={order}`；取 E18 `initiatedBy=SYSTEM` 那列；owner、sitter 各 E22 | 協商頁顯示「此退款由系統建立，無法撤回」且無撤回按鈕 | C6 `processed=1`；兩次 E22 皆 403 `FORBIDDEN`；仍 `AWAITING_REFUND` | 截圖 + API JSON |
| MW-06 | AC-3.2 | owner、sitter | 依 MI-15 的同一流程產生 `REJECTED`、`WITHDRAWN`、`EXPIRED`、`SETTLED` 四筆；各由其發起者 E22 | — | 皆 409 `MODIFICATION_STATE_CONFLICT`；`endedAt` 不變 | API JSON |
| MW-07 | AC-3.3 | — | `order` 上 owner E17（`REQUESTED`）；shift-time `MODIFICATION_EXPIRES_AT` `minutes=-(72*60+1)`；先查 E19；再呼叫 C5 `?orderId={order}` | — | shift 後 E19 仍 `REQUESTED`；C5 200：`processed=1`、`skipped=0`、`failed=0`；E19 `EXPIRED`、`endedAt ≥` C5 呼叫時間；E2 `status`、F1 不變；`inspect.orderLogs` 新增 1 筆 `MODIFICATION_EXPIRED`（`operatorRole=SYSTEM`、`payload.fromStatus=REQUESTED`）；`inspect.notifications` `MODIFICATION_EXPIRED` 恰 2 筆（OWNER、SITTER 各一），通知列表文案「變更請求已逾期」 | API JSON |
| MW-08 | AC-3.3 | — | 另一筆：owner E17 → sitter E20（`QUOTED`）→ shift 同上 → C5 `?orderId` | — | 同 MW-07（`payload.fromStatus=QUOTED`） | API JSON |
| MW-09 | AC-3.3 | — | 再呼叫一次 C5 `?orderId={order}` | — | `processed=0`、`skipped=0`、`failed=0`（終態不再被查出）；E19 `endedAt` 與 MW-07 相同；`MODIFICATION_EXPIRED` 的 `order_logs` 仍 1 筆、通知仍 2 筆 | API JSON |
| MW-10 | AC-3.4 | owner、sitter | 另一筆 `REQUESTED`；shift `minutes=-(72*60+1)`（不跑 C5）；讀 E19 `expiresAt` 為 e1；雙方開協商頁；sitter E20、sitter E21（附原因）、owner E22 | 雙方頁面的報價 / 拒絕 / 撤回按鈕都不存在，顯示「此變更請求已逾期」 | 三次皆 409 `DEADLINE_PASSED`，`payload.deadline=MODIFICATION_EXPIRES_AT`、`deadlineAt=e1`；E19 仍 `REQUESTED`、`allowedActions=[]` | 截圖 + API JSON |
| MW-11 | AC-3.4 | owner | 另一筆 owner 發起的 `QUOTED`；shift 同上；owner E22 | 飼主頁無按鈕、顯示「此變更請求已逾期」 | 409 `DEADLINE_PASSED`（同 payload）；仍 `QUOTED` | 截圖 + API JSON |
| MW-12 | AC-3.5 | owner、sitter | owner 發起的 `REQUESTED`：雙方開協商頁 | 飼主：只有 `modification-btn-withdraw`；保母：只有 `modification-btn-quote`、`modification-btn-reject` | E19（各自 token）`allowedActions`：owner `[WITHDRAW]`、sitter `[QUOTE, REJECT]` | 截圖 + API JSON |
| MW-13 | AC-3.5 | owner、sitter | 報價後（owner 發起的 `QUOTED`）雙方開協商頁 | 飼主：`modification-btn-accept`、`modification-btn-reject`、`modification-btn-withdraw`；保母：沒有 `modification-btn-quote` / `modification-btn-reject` / `modification-btn-withdraw`，顯示 `modification-detail-diff` 與 `modification-detail-expires-at` | owner `[ACCEPT, REJECT, WITHDRAW]`、sitter `[]` | 截圖 + API JSON |
| MW-14 | AC-3.5 | owner、sitter | sitter 發起的 `QUOTED`：雙方開協商頁 | 飼主：`modification-btn-accept`、`modification-btn-reject`（無撤回）；保母：只有 `modification-btn-withdraw` | owner `[ACCEPT, REJECT]`、sitter `[WITHDRAW]` | 截圖 + API JSON |
| MW-15 | AC-3.5 | owner、sitter | 對 MW-06 的四筆終態變更，雙方各開協商頁 | 無任何動作按鈕；顯示狀態、結束時間、金額（有報價者，`$ 1,500` 格式）、原因 | 皆 `allowedActions=[]` | 截圖 + API JSON |
| MW-16 | AC-3.5 | owner | 以 MI-15 流程的訂單（5 筆變更）開 `/owner/orders/{order}` 變更歷史；另開一張無變更的 `CONFIRMED` 單 | 前者 `modification-history-item-{id}` 共 5 個，DOM 順序與 E18 一致（`createdAt` 倒序）；後者 `modification-history-empty`「尚無變更紀錄」；點前者的 `EXPIRED` 那列 → 落地 `/orders/{order}/modifications/{該 id}` 唯讀明細，狀態文字「已逾期」、無任何動作按鈕 | E18 前者 5 列倒序、後者 `[]` | 截圖 + API JSON |
| MW-17 | AC-3.6 | owner、sitter | owner E17（`REQUESTED`）；記下 `inspect.orderLogs` 列數 n；以 `Promise.all` 同時送 owner E22 與 sitter E20（不帶加減） | — | 恰一個 200；另一個 409 `MODIFICATION_STATE_CONFLICT`；E19 最終狀態 = 成功者的結果（`WITHDRAWN` 或 `QUOTED`）；`inspect.orderLogs` 恰新增 1 筆，`actionType` 為成功者那種（`MODIFICATION_WITHDRAWN` 或 `MODIFICATION_QUOTED`）；`inspect.notifications` 只有成功那一種 | API JSON |
| MW-18 | AC-3.7 | owner | 另一張單：owner E17 帶 `Idempotency-Key=K1`；同 key 同 body 重送；同 key 改 `reason`；換新 key 同 body | — | 201；回放 201 且 `modificationId` 相同、E18 長度 1、`MODIFICATION_REQUESTED` 的 `order_logs` 與通知各仍 1 筆；422 `IDEMPOTENCY_KEY_REUSED`；409 `MODIFICATION_IN_PROGRESS` | API JSON |
| MW-19 | AC-3.7 | sitter | sitter E20 帶 `K2`；同 key 同 body 重送；換新 key 同 body | — | 200；回放 200、body 與第一次相同；409 `MODIFICATION_STATE_CONFLICT`，E19 `newTotalAmount` 不變 | API JSON |
| MW-20 | AC-3.7 | owner | 另一張單 E17 不帶 `Idempotency-Key` header | — | 400 `VALIDATION_FAILED`、`payload.field=Idempotency-Key`；E18 長度 0 | API JSON |

## 3. 權限與例外

> 每個 PRD EX-n 至少一列；越權一律用另一個真實帳號打 API。已在 §2 覆蓋的 EX 列出對應步驟。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| ME-01 | EX-1、AC-1.7 | `order` 有一筆 `REQUESTED`；`ownerB` 呼叫 E17、E18、E19、E21、E22；`sitterB` 呼叫 E20 | 皆 403 `FORBIDDEN`；E18（本人）長度不變、E19 仍 `REQUESTED` |
| ME-02 | EX-2、AC-1.7 | owner 以隨機 UUID 當 `orderId` 呼叫 E17；以隨機 UUID 當 `modificationId` 呼叫 E19、E20（sitter）、E21、E22 | 皆 404 `NOT_FOUND` |
| ME-03 | EX-3、AC-1.7 | `sitter`（雙角色）以角色切換取得 `currentRole=OWNER` 的 token 呼叫 E17（G4a）、E20（G3）；`owner` 呼叫 E20；`admin` 呼叫 E17、E20、E21、E22 | 皆 403 `ROLE_MISMATCH`；無新增變更、既有 `REQUESTED` 不變 |
| ME-04 | EX-4 | 見 MW-03、MW-04、MW-05 | 403 `FORBIDDEN` |
| ME-05 | EX-5 | 見 MQ-03、MQ-04、MQ-24、MQ-26、MQ-28 | 403 `PLAN_REQUIRED`，變更維持原狀態 |
| ME-06 | EX-6 | 見 MI-09~MI-11；另：MI-11 的 `DISPUTED` 訂單上 sitter E21（附原因）、sitter E22 對其發起的 `AWAITING_REFUND` | E21 → 409 `ORDER_STATE_CONFLICT`；E22 → 409 `ORDER_STATE_CONFLICT`（發起者本人，凍結先於變更狀態） |
| ME-07 | EX-7 | 見 MI-13、MI-14 | 409 `MODIFICATION_IN_PROGRESS` |
| ME-08 | EX-8 | 見 MQ-19、MW-04、MW-06、MW-19 | 409 `MODIFICATION_STATE_CONFLICT` |
| ME-09 | EX-9 | 見 MW-10、MW-11 | 409 `DEADLINE_PASSED` + payload |
| ME-10 | EX-10 | owner E17 減少 V3（`REQUESTED`）→ FT1 V3 `IN_PROGRESS` → sitter E20（`removeVisitIds=[V3]`）；另見 MI-16 | 409 `VISIT_ALREADY_STARTED`，`payload.visitIds=[V3]`；仍 `REQUESTED` |
| ME-11 | EX-11 | 見 MQ-12、MQ-13、MQ-15 | 422 `INVALID_VISIT_DATE`（區間外帶 `reason=PLAN_NOT_IN_RANGE`） |
| ME-12 | EX-12 | 見 MQ-06 | 422 `INVALID_MODIFICATION_AMOUNT` |
| ME-13 | EX-13 | 見 MW-17（撤回 vs 報價）；「開始行程 vs 送出含該行程的變更」見 §4 | 後到者 409 |
| ME-14 | EX-15、EX-16 | 見 MW-18、MW-19 | 回放 / 422 `IDEMPOTENCY_KEY_REUSED` |
| ME-15 | EX-17 | 見 MI-04：`inspect.notifications` 該筆站內通知已同交易寫入，`emailStatus=SUPPRESSED_TEST`；Resend 失敗情境見 §4 | 站內通知存在 |
| ME-16 | EX-19 | 見 MI-06、MI-17、MI-18、MQ-06、MQ-17、MQ-21、MW-20；另：E17 `addItems[0].date="2026/13/01"` | 400 `VALIDATION_FAILED`（`field` 指向 `addItems[0].date`），不建立變更 |
| ME-17 | EX-21 | 見 MI-17、MQ-11 | 422 `MODIFICATION_EMPTY` |
| ME-18 | EX-22 | 見 MI-18 | 422 `PLAN_NOT_IN_ORDER` |
| ME-19 | EX-23 | 見 MI-12 | 409 `MODIFICATION_NOT_ALLOWED` |
| ME-20 | EX-24、BR-28 | (a) MI-11 的 `DISPUTED` 訂單上 owner E17 帶 `reason=""`；(b) owner E17 減少 V3 → FT1 V3 `IN_PROGRESS` → shift `MODIFICATION_EXPIRES_AT` 過期 → sitter E20（`removeVisitIds=[V3]`）；(c) 見 MI-11、MQ-04 | (a) 400 `VALIDATION_FAILED`（先於 `ORDER_STATE_CONFLICT`）；(b) 409 `DEADLINE_PASSED`（先於 `VISIT_ALREADY_STARTED`）；(c) `ORDER_STATE_CONFLICT` 先於 `MODIFICATION_IN_PROGRESS`、422 先於 `PLAN_REQUIRED` |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.1、AC-2.1（`expires_at` 精確值 `2026-11-24T02:00:00Z`） | 正式站無法固定時鐘；E2E 以 `[t0+72h, t1+72h]` 夾值 | `ModificationInitiateIntegrationTest`、`ModificationQuoteIntegrationTest`（注入 `Clock`） |
| AC-2.5（2026-11-21 23:30 跨日邊界） | 需固定時鐘；E2E 只驗 D / D+1 | `ModificationQuoteIntegrationTest`（注入 `Clock`，剛好 00:00 前後） |
| AC-3.1（結束時間 = `2026-11-22T01:00:00Z`） | 需固定時鐘 | `ModificationLifecycleIntegrationTest` |
| AC-3.3（正式排程於到期後 ≤ 15 分鐘內執行）；訂單 `DISPUTED` 時略過 | 頻率由 Cloud Scheduler 設定決定，且自動排程跳過測試帳號訂單；E2E 直接帶 `orderId` 呼叫 C5 | 手動驗證 Cloud Scheduler job `modifications/expiry` 為每 15 分鐘（SD-ORDER §8.3）；`ModificationCronIntegrationTest` |
| AC-3.6（鎖等待 > 3 秒 → 409 `ORDER_BUSY` + `Retry-After: 1`；指定先後順序） | 正式站無法持有訂單鎖 3 秒；`Promise.all` 無法控制先後 | `ModificationConcurrencyIntegrationTest`、`OrderLockServiceTest` |
| AC-3.7（`IDEMPOTENCY_IN_PROGRESS`、接手） | 無法在正式站製造處理中狀態 | `ModificationIdempotencyIntegrationTest`（SD-ORDER §10.2） |
| AC-3.8、EX-17（Resend 5xx 不影響轉移、重試 5 次） | 正式站無法注入 Resend 失敗；Resend 測試模式只寄專案擁有者信箱 | `ModificationInitiateIntegrationTest`（Resend mock）；真實寄信手動驗證 |
| EX-13（SITTER 開始行程 vs 送出含該行程的變更同時） | 需控制先後 | `ModificationConcurrencyIntegrationTest`（SD-ORDER §10.3 已列：開始先 → 409 `VISIT_ALREADY_STARTED`；變更先 → 開始照常） |
| EX-14（鎖等待逾時） | 同 AC-3.6 | `OrderLockServiceTest`、`ModificationConcurrencyIntegrationTest` |
| EX-18（GCS） | 不適用：本卡不上傳檔案 | — |
| EX-20（排程單筆失敗回滾、其他筆照常） | 正式站無法注入單筆例外 | `ModificationCronIntegrationTest`（SD-ORDER §10.3 已列：同批 3 筆其中 1 筆拋例外） |

## 5. 已知限制 / 待補

1. **時間敏感**（未解決）：MQ-12、MQ-13 以 D / D+1 判斷，避免在 Asia/Taipei 23:55–00:05 執行（spec 偵測到時 `test.skip` 並註記）。這是日期跨日問題，與排程無關。
2. 已解決（2026-11-22 SD-ORDER 核准後修訂）：ADMIN 帳號（`provision-account` `roles[]`）；角色切換（雙角色帳號）；缺 testid（SD-ORDER §9.3）；目前總額讀取來源（E2 `quote.totalAmount`）；`sitter-setup` upsert；`order_logs` / 通知可讀（`inspect`）；後端測試缺口 EX-13、EX-20（§10.3 已補）；排程搶先處理測試資料（C1–C10 `orderId`、自動觸發跳過測試帳號）。
3. 已解決（第 2 批核准後修訂）：進協商頁入口（`*-btn-open-modification`、可點的 `modification-history-item-*`）；精靈新增一列按鈕（`order-modify-btn-add-item`）；精靈方案區間資料來源（E2 `groups[].bookableFrom` / `bookableTo` 雙方都回，`sitterTier` / `modificationPricingMode` 只回 SITTER）；報價明細三段（E2 `quote.adjustments[]`）；Email 斷言（`emailStatus=SUPPRESSED_TEST`）。
4. 待確認（不阻擋）：SD-FRONTEND-SPEC §6.4 子流程狀態文字尚待使用者確認，步驟中的狀態中文以確認後版本為準。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依新範本全文重寫：對應拆卡後的 PRD-016（發起、報價、拒絕、撤回、逾時）與 SD-ORDER；補款 / 退款 / 結清移至 TS-023、款項爭議移至 TS-024；舊版 Scenario（`MODIFYING`、`REFUND_VERIFY`、`previous_status`）全部廢止 | PRD-016、SD-ORDER Approved 2026-11-22 |
| 2026-11-22 | 依 SD 核准後修訂（commit 477c75d）更新：`order_logs` / 通知改以 `inspect` 在 E2E 斷言（MI-03、MI-04、MI-07、MI-08、MQ-02、MQ-18、MW-01、MW-03、MW-07~MW-09、MW-17、MW-18；§4 移除 `order_logs` 列）；目前總額改用 E2 `quote.totalAmount`；C5 / C6 帶 `orderId` 並斷言精確計數（MI-15、MW-05、MW-07~MW-09）；帳號改用 `roles[]`（`sitter` 雙角色、`admin`）與 setup upsert / key 對應；補 SD-ORDER §9.3 testid（`owner-order-detail-btn-modify`、`sitter-order-detail-btn-modify`、`modification-input-*`、`order-modify-add-item-{index}-*`）；畫面金額改 `$ 1,800` 格式；新增 ME-15（EX-17 站內通知）；§4 EX-13、EX-20 標明後端已列；§5 已解決項移除 | SD-ORDER / SD-FULFILL 核准後修訂（commit 477c75d） |
| 2026-11-22 | 使用者核准：狀態 Draft → Approved | 使用者 2026-11-22 |
| 2026-11-22 | 依核准後修訂同步（使用者 2026-11-22 同意）：§1 補報價明細三段（`amountChange=0` 不列）、精靈資料回傳對象、協商頁一律由「查看進行中變更」/ 歷史列點入、`emailStatus=SUPPRESSED_TEST`、狀態文字依 SD-FRONTEND-SPEC §6.4；MI-02、MI-19 先點 `order-modify-btn-add-item`；新增 MI-20（`*-btn-open-modification` 入口與無調整時明細兩段）、MI-21（SETTLED 後報價明細）；MI-03 `quote.adjustments=[]`；MI-07、MQ-26 斷言 `sitterTier` / `modificationPricingMode`；MQ-14 改驗雙方精靈都顯示區間（E2 `bookableFrom` / `bookableTo` 雙方都回）、飼主 E2 不輸出 `sitterTier` / `modificationPricingMode`；MI-21 改為差額 0 的 SETTLED 不列入報價明細（`amountChange=0` 不列）；MQ-02、MW-01 狀態文字；MW-01 入口與按鈕切換；MW-16 歷史列可點進唯讀明細；MI-04、ME-15 `emailStatus`；狀態維持 Approved | 第 2 批核准後修訂 |
