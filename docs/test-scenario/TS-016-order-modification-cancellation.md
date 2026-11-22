# TS-016: 訂單變更：發起、報價與協商期限

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-016（Approved 2026-11-22）；旅程中順帶使用 PRD-023（同意 / 拒絕，只作前置或後續動作）、PRD-007（付款憑證，只作前置） |
| 對應 SD | SD-ORDER（§3.3、§6.3 E17–E23、§6.5、§7.2、§8.1 C5、§9.1、§10.4）；SD-FULFILL（§6.3 FT1 `force-status`、FT2 `shift-time`、F1 行程清單） |
| 類型 | 旅程（打真後端）：變更子流程（申請 / 報價 / 拒絕 / 撤回 / 逾時）、跨角色互動、權限、冪等全部走真後端 |
| 自動化路徑 | `frontend/e2e/journeys/order-modification-initiate.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> 本檔重寫自舊版 TS-016（舊版對應已拆卡前的 PRD-016 / SD-016，含舊狀態 `MODIFYING`、`REFUND_VERIFY`、`previous_status` 復原等已廢止流程）。檔名與 PRD-016 slug 相同，直接覆寫；舊版內容中補款 / 退款 / 結清移至 TS-023，款項爭議移至 TS-024。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 自己建資料、可獨立重跑。所有帳號為 `@e2e-journey.test`，由 `POST /api/internal/test-data/provision-account` 建立，測試資料端點一律帶 `X-Internal-Secret`（值取自 CI 環境變數，不得寫入檔案或證據）。
> 日期記號：`D` = 執行當下 Asia/Taipei 的日期；`D+n` = D 之後第 n 天。所有日期由 spec 在執行時計算。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `provision-account`；`POST /api/internal/test-data/owner-setup`：`pets` = M1、M2、M3（CAT，`deleted=false`），`emailPreferences.ORDER_AFFAIR=開` | — |
| `ownerB` | OWNER | `provision-account`；`owner-setup` 一隻毛孩 | 與 `order` 無關的第三人 |
| `sitter` | SITTER | `provision-account`；`POST /api/internal/test-data/sitter-setup`：`tier=PRO`（建單期間用，避免 FREE 每月 3 單上限）、`subscriptionExpiresAt = now + 30 天`、`suspended=false`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄、`plans` = P（單價 500、CAT、無可預約區間、`active=true`）與 Q（單價 800、CAT、`active=true`）、`questions=[]`。建單完成後再呼叫 `sitter-setup` 改成該情境要的等級（預設 BASIC） | — |
| `sitterB` | SITTER | 同 `sitter`，與 `order` 無關 | 第三人 |
| `admin` | ADMIN | `provision-account`（ADMIN 角色；見 §5 待補 1） | — |
| `order` | — | `owner` 以 E1 `POST /api/orders` 預約 `sitter`：`groups=[{planId: P, dates: [D+3, D+4, D+5], dailyCount: 1, petIds: [M1, M2]}]`、`expectedAmount=1500` → `sitter` E6 原價報價（`expectedAmount=1500`）→ `owner` E11 上傳首次付款憑證（`disclaimerAgreed=true`、`lastFive=12345`、PNG 檔）→ `sitter` E14 確認 → 以 F1 `GET /api/orders/{order}/visits` 依 `serviceDate` 取 V1（D+3）、V2（D+4）、V3（D+5） | `CONFIRMED`；`total_amount=1500`；E2 `netPaid=1500` |
| `orderIP` | — | `order` 建好後以 FT1 `POST /api/internal/test-data/visits/{V1}/force-status`（`status=DONE`、`careLogSubmitted=true`） | `IN_PROGRESS`（O9）；V1 `DONE`，V2、V3 `PENDING` |
| `order2` | — | 同 `order`，同一組 `owner` / `sitter` 的另一張單 | `CONFIRMED`（只用來取「不屬本訂單」的行程 id） |

- 建單 helper（建議名 `bootstrapConfirmedOrder({ tier, visitStates })`）由 e2e-author 實作；每個 test 自建一組 `owner` / `sitter` / `order`，同一 test 需要多張單時在 `tier=PRO` 下建立後再降級。
- 「目前總額」以 E4 `GET /api/orders/sitter?tab=ACTIVE` 該單 `content[].totalAmount` 斷言（E2 只有報價時的 `quote.totalAmount`，見 §5 待補 4）；「已收淨額」以 E2 `netPaid` 斷言。
- 「變更列數」以 E18 `GET /api/orders/{order}/modifications` 的陣列長度斷言；「變更內容」以 E19 斷言。
- 「通知」以收件者 token 呼叫 `GET /api/notifications`（SD-014），篩 `type`（SD-ORDER §7.2）與連結含 `modificationId`。
- 時間斷言：`expiresAt` 等時間以「送出前時間 t0、收到回應時間 t1」夾住，斷言 `t0 + 72h ≤ expiresAt ≤ t1 + 72h`；精確到固定時鐘的斷言（例 `2026-11-24T02:00:00Z`）交後端整合測試（§4）。

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4 Cleanup 擴充已涵蓋 `modification_requests` 含 `initiated_by=SYSTEM` 列、`payments`、`admin_tasks`、`order_logs`、`idempotency_records`）。

## 2. 測試步驟

> 步驟 ID 用 `{前綴}-NN`，e2e spec 的 `step()` / `apiStep()` 標籤必須以同一 ID 開頭。前綴：`MI` = 子模組 1 發起；`MQ` = 子模組 2 報價與拒絕；`MW` = 子模組 3 撤回、逾時、協商頁與冪等；`ME` = §3 權限與例外。
> 「API / 資料斷言」寫具體值；E17–E23 一律帶 `Idempotency-Key`（UUID），除非該步驟另寫。

### 2.1 子模組 1：發起變更

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| MI-01 | AC-1.1 | owner | 以 `orderIP` 開啟 `/owner/orders/{order}`，點訂單詳情的「申請變更」入口（testid 未定，見 §5 待補 3） | 導向 `/orders/{order}/modify`；有 `order-modify-type-CHANGE`、`order-modify-type-CANCEL_ALL`；`order-modify-visit-checkbox-{V2}`、`order-modify-visit-checkbox-{V3}` 可勾選；V1（`DONE`）沒有可勾選的 checkbox（不存在或 disabled） | E2 `allowedActions` 含 `REQUEST_MODIFICATION` | 截圖 + API JSON |
| MI-02 | AC-1.1 | owner | 選 `order-modify-type-CHANGE`；勾 `order-modify-visit-checkbox-{V3}`；在 `order-modify-add-item-0` 填日期 D+7、方案 P、每日次數 1，毛孩維持預設；`order-modify-input-reason` 填「提早回國」；點 `order-modify-btn-submit` | 新增列的毛孩預設 M1、M2 皆勾選（送出前截圖）；送出後導向 `/orders/{order}/modifications/{mod}`；`modification-detail-expires-at` 顯示 `expiresAt` 換算的 Asia/Taipei `YYYY-MM-DD HH:mm`；飼主只看到 `modification-btn-withdraw` | 頁面發出的 E17 恰一次，201：`status=REQUESTED`、`initiatedBy=OWNER`、`systemAmount` / `newTotalAmount` / `diff` 不輸出；E18 該列 `createdAt` 與 E19 `expiresAt` 相差 72h（±2 秒）；E19 `requestedContent.removeVisitIds=[V3]`、`addItems[0]` = D+7 / P / 1 / `petIds={M1,M2}` | 截圖 + API JSON |
| MI-03 | AC-1.1 | — | 查訂單、行程、金額、付款 | — | E2 `status=IN_PROGRESS`、`openModification.status=REQUESTED`；F1 V3 `PENDING`、無 D+7 行程；E4 `totalAmount=1500`；E10 只有 1 筆 `purpose=INITIAL` | API JSON |
| MI-04 | AC-1.1 | sitter | `GET /api/notifications`（SITTER） | — | 恰 1 筆 `type=MODIFICATION_REQUESTED`、文案含「飼主提出訂單變更」、連結 `/orders/{order}/modifications/{mod}`（`order_logs` 一筆的斷言見 §4） | API JSON |
| MI-05 | AC-1.2 | owner | 以 `order`（`CONFIRMED`）經 UI 選 `order-modify-type-CANCEL_ALL`、原因「行程取消」、送出 | 導向協商頁 | E17 201：`status=REQUESTED`；E19 `type=CANCEL_ALL`；F1 V1、V2、V3 皆 `PENDING` | 截圖 + API JSON |
| MI-06 | AC-1.2 | owner | 另一張 `CONFIRMED` 單，E17 `CANCEL_ALL` 依序送：`reason="   "`、`reason`=201 字、`reason`=200 字（每次換 key） | — | 前兩次 400 `VALIDATION_FAILED`（`payload.field=reason`），E18 長度 0；第三次 201、`REQUESTED` | API JSON |
| MI-07 | AC-1.3 | sitter | `sitter` 降為 BASIC；以 `order` 開 `/sitter/orders/{order}` 點「提出變更」入口（testid 未定）→ `/orders/{order}/modify`；選 CHANGE、勾 `order-modify-visit-checkbox-{V3}`、原因「臨時住院」、送出 | 送出前畫面不顯示加價 / 折扣欄位（BASIC 不可自訂）；送出後導向協商頁，`modification-detail-diff` 顯示「應退款 NT$500」；保母只看到 `modification-btn-withdraw` | E17 201：`status=QUOTED`、`initiatedBy=SITTER`、`systemAmount=1000`、`newTotalAmount=1000`、`netPaid=1500`、`diff=-500`；`expiresAt` 在 `[t0+72h, t1+72h]` | 截圖 + API JSON |
| MI-08 | AC-1.3 | owner | 收通知後開 `/orders/{order}/modifications/{mod}` | `modification-btn-accept`、`modification-btn-reject` 可見；`modification-btn-withdraw` 不存在；`modification-detail-diff`「應退款 NT$500」 | 通知 `type=MODIFICATION_PROPOSED` 1 筆；E19 `allowedActions=[ACCEPT, REJECT]` | 截圖 + API JSON |
| MI-09 | AC-1.4 | owner、sitter | 建 `orderPending`（只做 E1）與 `orderPP`（E1 + E6）；雙方各以 E17（`CHANGE`、新增 D+7、原因「測試」）呼叫 | — | 皆 409 `ORDER_STATE_CONFLICT`，`payload.currentStatus` 分別為 `PENDING`、`PENDING_PAYMENT`；兩單 E18 長度 0 | API JSON |
| MI-10 | AC-1.4 | owner、sitter | 建 `orderDone`：FT1 V1、V2、V3 皆 `DONE`（`careLogSubmitted=true`）→ owner E25 結案（`COMPLETED`）；建 `orderCxl`：E1 後 owner E8 撤回（`expectedStatus=PENDING`，`CANCELLED`）；雙方各 E17 | — | 皆 409 `ORDER_STATE_CONFLICT`，`currentStatus` = `COMPLETED` / `CANCELLED`；E18 長度 0 | API JSON |
| MI-11 | AC-1.4 | owner、sitter | 以 `order`：sitter E17 減少 V3（`QUOTED` 1000）→ owner E23 同意（`AWAITING_REFUND`）→ owner E24 申報爭議（說明「測試」，訂單 `DISPUTED`）；雙方各 E17 | — | 皆 409 `ORDER_STATE_CONFLICT`（`currentStatus=DISPUTED`），不是 `MODIFICATION_IN_PROGRESS`；E18 長度仍 1 | API JSON |
| MI-12 | AC-1.4 | owner | 以 `order`：FT1 V1 `DONE`、V3 `DONE`、V2 `CLOSED_BY_SYSTEM`；owner E17 `CANCEL_ALL`；再 E17 `CHANGE` 只新增 D+7；開 `/owner/orders/{order}` | 訂單詳情沒有「申請變更」入口 | 兩次皆 409 `MODIFICATION_NOT_ALLOWED`；E18 長度 0；E2 `allowedActions` 不含 `REQUEST_MODIFICATION` | 截圖 + API JSON |
| MI-13 | AC-1.5 | owner、sitter | 以 `order`：owner E17 減少 V3（`REQUESTED`）→ 雙方各 E17；sitter E20 報價（`QUOTED`）→ 雙方各 E17 | — | 四次皆 409 `MODIFICATION_IN_PROGRESS`；E18 長度 1 | API JSON |
| MI-14 | AC-1.5 | owner、sitter | `orderSup`：owner E17 新增 D+7 → sitter E20（2000、差額 +500）→ owner E23（`AWAITING_SUPPLEMENT`）；`orderRef`：sitter E17 減少 V3 → owner E23（`AWAITING_REFUND`）；兩單雙方各 E17 | — | 皆 409 `MODIFICATION_IN_PROGRESS`；兩單 E18 長度各 1 | API JSON |
| MI-15 | AC-1.5 | owner、sitter | 以 `order` 依序：owner E17 → sitter E21 拒絕（原因「不便」）→ owner E17 → owner E22 撤回 → owner E17 → SD-ORDER `POST /api/internal/test-data/orders/{order}/shift-time`（`target=MODIFICATION_EXPIRES_AT`、`modificationId`、`minutes=-(72*60+1)`）→ 呼叫 C5 `POST /api/internal/cron/modifications/expiry` → sitter E17（減少 V3、新增 D+7，新總額 1500、差額 0）→ owner E23（`SETTLED`）→ owner E17 | — | 每次在前一筆進入 `REJECTED` / `WITHDRAWN` / `EXPIRED` / `SETTLED` 後的發起皆 201；E18 依 `createdAt` 倒序最後為 5 列，狀態依序 `REQUESTED`、`SETTLED`、`EXPIRED`、`WITHDRAWN`、`REJECTED` | API JSON |
| MI-16 | AC-1.6 | owner | 以 `order`：FT1 V1 `DONE`、V2 `IN_PROGRESS`；owner E17 `CHANGE` 減少 [V1]；再減少 [V2] | — | 409 `VISIT_ALREADY_STARTED`，`payload.visitIds` 分別為 [V1]、[V2]；E18 長度 0 | API JSON |
| MI-17 | AC-1.6 | owner | 同上訂單：E17 減少 `order2` 的 V1；E17 `CHANGE` 不帶 `removeVisitIds` 也不帶 `addItems` | — | 前者 400 `VALIDATION_FAILED`（`field` 指向 `removeVisitIds`）；後者 422 `MODIFICATION_EMPTY`；E18 長度 0 | API JSON |
| MI-18 | AC-1.8 | owner | 以 `order`，E17 `CHANGE` 只新增一筆，依序：`petIds=[]`；`petIds=[M3]`；`dailyCount=4`；V2 日期 / P / `dailyCount=3`；58 筆不同新日期（D+10 起連續）各 1 次（變更後 61 個日期）；方案 Q | — | 前五次 400 `VALIDATION_FAILED`（前兩次 `field` 指向 `addItems[0].petIds`）；方案 Q → 422 `PLAN_NOT_IN_ORDER`（`payload.planId`=Q）；E18 長度 0 | API JSON |
| MI-19 | AC-1.8 | owner | UI：`/orders/{order}/modify` 選 CHANGE，在 `order-modify-add-item-0` 填 V2 日期、方案 P、次數 2，取消勾選 M2，原因「多一次」，送出 | 導向協商頁 | E17 201；E19 `requestedContent.addItems[0]` = V2 日期 / P / 2 / `petIds=[M1]` | 截圖 + API JSON |

### 2.2 子模組 2：保母報價與拒絕

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| MQ-01 | AC-2.1 | sitter | `sitter` BASIC；`orderIP` 上 owner E17（減少 V3、新增 D+7 一次）；保母開 `/orders/{order}/modifications/{mod}` | 顯示飼主期望內容、原因「提早回國」、`modification-detail-diff`「無差額」；`modification-btn-quote`、`modification-btn-reject` 可見；無加價 / 折扣欄位 | E19 `status=REQUESTED`、`allowedActions=[QUOTE, REJECT]` | 截圖 + API JSON |
| MQ-02 | AC-2.1 | sitter | 記下 E19 `expiresAt` 為 e0；點 `modification-btn-quote`（內容維持預填） | 報價後兩顆按鈕消失，顯示唯讀報價與到期時間 | E20 200：`status=QUOTED`、`systemAmount=1500`、`newTotalAmount=1500`、`diff=0`；`expiresAt` 在 `[t0+72h, t1+72h]` 且 > e0；E19 `quotedContent.addItems[0].petIds={M1,M2}`；owner 通知 `type=MODIFICATION_QUOTED`「保母已回覆變更報價」1 筆 | 截圖 + API JSON |
| MQ-03 | AC-2.2 | sitter | `sitter` BASIC；`order` 上 owner E17 減少 V3；sitter E20 依序：`surchargeAmount=200`+`adjustmentReason`；`discountAmount=100`+原因 | — | 皆 403 `PLAN_REQUIRED`；E19 仍 `REQUESTED`、`expiresAt` 不變 | API JSON |
| MQ-04 | AC-2.2 | sitter | `sitter-setup` 改 FREE；E20 `surchargeAmount=200`+原因；再 E20 `surchargeAmount=200`+原因+`addItems=[{date: D, planId: P, dailyCount: 1, petIds: [M1]}]` | — | 前者 403 `PLAN_REQUIRED`；後者 422 `INVALID_VISIT_DATE`（先於 `PLAN_REQUIRED`，BR-28）；仍 `REQUESTED` | API JSON |
| MQ-05 | AC-2.2 | sitter | E20 `surchargeAmount=0`、`discountAmount=0` | — | 200、`QUOTED`、`newTotalAmount=1000`（= `systemAmount`） | API JSON |
| MQ-06 | AC-2.3 | sitter | `sitter` PRO；`order` 上 owner E17 減少 V3、新增 D+7 兩次；sitter E20 依序：`surchargeAmount=300` 無原因；`discountAmount=2001`+原因 | — | 前者 400 `VALIDATION_FAILED`（`field=adjustmentReason`）；後者 422 `INVALID_MODIFICATION_AMOUNT`；皆仍 `REQUESTED` | API JSON |
| MQ-07 | AC-2.3 | sitter | UI：保母協商頁填加價 300、調整原因「節日加成」（輸入欄 testid 未定），點 `modification-btn-quote` | 保母頁差額「應補款 NT$800」；飼主頁 `modification-detail-diff`「應補款 NT$800」 | E20 200：`systemAmount=2000`、`surchargeAmount=300`、`newTotalAmount=2300`、`diff=800` | 截圖 + API JSON |
| MQ-08 | AC-2.3 | owner、sitter | owner E22 撤回 → owner E17 同內容 → sitter E20 `discountAmount=2000`+原因 | 飼主頁 `modification-detail-diff`「應退款 NT$1500」 | 200：`newTotalAmount=0`、`diff=-1500` | 截圖 + API JSON |
| MQ-09 | AC-2.4 | sitter | `sitter` BASIC；`order` 上 FT1 V1 `DONE`、V2 `CLOSED_BY_SYSTEM`；owner E17 `CANCEL_ALL`；sitter UI 點 `modification-btn-quote` | 差額「應退款 NT$500」 | E20 200：`quotedContent.removeVisitIds=[V3]`、`newTotalAmount=1000`、`diff=-500` | 截圖 + API JSON |
| MQ-10 | AC-2.4 | sitter | 另一張 `CONFIRMED` 單，owner E17 `CANCEL_ALL` → sitter E20 | — | 200：`removeVisitIds` = {V1, V2, V3}、`newTotalAmount=0`、`diff=-1500` | API JSON |
| MQ-11 | AC-2.4 | sitter | 另一張單：FT1 V1 `DONE`、V2 `CLOSED_BY_SYSTEM`；owner E17 `CANCEL_ALL`（`REQUESTED`）→ FT1 V3 `IN_PROGRESS` → sitter UI 點 `modification-btn-quote` | 顯示「此訂單已無可取消的行程，請拒絕此變更」；`modification-btn-reject` 仍可用 | E20 422 `MODIFICATION_EMPTY`；E19 仍 `REQUESTED` | 截圖 + API JSON |
| MQ-12 | AC-2.5 | owner | `sitter` BASIC；`order` 上 owner E17 新增日期 D；再新增 D+1 | — | 前者 422 `INVALID_VISIT_DATE`（`payload.dates` 含 D），E18 長度 0；後者 201 | API JSON |
| MQ-13 | AC-2.5 | sitter | 另一張單 owner E17 新增 D+2（`REQUESTED`）；sitter E20 `addItems` 改為 D；再改為 D+1 | — | 前者 422 `INVALID_VISIT_DATE`、仍 `REQUESTED`；後者 200、`QUOTED` | API JSON |
| MQ-14 | AC-2.5 | owner、sitter | 以 BASIC 建 `order`（D+3~D+5）；`sitter-setup` 改 PRO 並將方案 P 可預約區間設為 D+10 ~ D+40；owner 開 `/orders/{order}/modify` | 變更精靈顯示方案 P 可預約區間（D+10 ~ D+40） | — | 截圖 |
| MQ-15 | AC-2.5 | owner、sitter | owner E17 新增 D+12（`REQUESTED`）；sitter E20 `addItems` 改為 D+8；再改回 D+12 | — | D+8 → 422 `INVALID_VISIT_DATE`、`payload.reason=PLAN_NOT_IN_RANGE`、仍 `REQUESTED`；D+12 → 200（原 D+3~D+5 不檢查） | API JSON |
| MQ-16 | AC-2.5 | owner、sitter | owner E22 撤回；`sitter-setup` 改 BASIC（區間保留）；owner E17 新增 D+12 → sitter E20 改 D+8 → 200。owner E22 撤回；`sitter-setup` 改 PRO 且 `subscriptionExpiresAt = now − 2 天`（鎖定）；owner E17 新增 D+12 → sitter E20 改 D+8 | — | 兩次報價皆 200、`QUOTED`、`quotedContent.addItems[0].date=D+8` | API JSON |
| MQ-17 | AC-2.6 | sitter | `order` 上 owner E17（`REQUESTED`）；保母 UI 點 `modification-btn-reject`，`modification-input-reject-reason` 留空送出 | 欄位下方錯誤提示 | 若有送出請求：E21 400 `VALIDATION_FAILED`（`field=reason`）；E19 仍 `REQUESTED` | 截圖 + API JSON |
| MQ-18 | AC-2.6 | sitter、owner | `modification-input-reject-reason` 填「當週無法服務」送出 | 協商頁顯示唯讀結果：已拒絕、結束時間、拒絕原因 | E21 200：`status=REJECTED`、`endedAt` 在 `[t0, t1]`；E19 `rejectReason=當週無法服務`；F1、E4 `totalAmount=1500`、E2 `status=CONFIRMED` 不變；owner 通知 `type=MODIFICATION_REJECTED_BY_SITTER` 含「當週無法服務」；owner 再 E17 → 201 | 截圖 + API JSON |
| MQ-19 | AC-2.6 | sitter | 另一張單：owner E17 → sitter E20（`QUOTED`）→ sitter E21 帶 `reason=不想做` | — | 409 `MODIFICATION_STATE_CONFLICT`（`currentStatus=QUOTED`）；仍 `QUOTED` | API JSON |
| MQ-20 | AC-2.6 | owner、sitter | owner E21 拒絕該 `QUOTED`（PRD-023）→ sitter E17（減少 V2、新增 D+8） | — | owner 拒絕 200 `REJECTED`；sitter 發起 201：`status=QUOTED`、`initiatedBy=SITTER` | API JSON |
| MQ-21 | AC-2.7 | sitter | `sitter` PRO；owner E17 減少 V3；sitter E20 依序：`surchargeAmount=1000000`+原因；`surchargeAmount=999000`+原因；`surchargeAmount=100`+原因 201 字 | — | 三次皆 400 `VALIDATION_FAILED`（分別指向 `surchargeAmount`、新總額上限 `max=999999`、`adjustmentReason`）；仍 `REQUESTED` | API JSON |
| MQ-22 | AC-2.7 | sitter | E20 `surchargeAmount=998999`+原因 | — | 200、`newTotalAmount=999999` | API JSON |
| MQ-23 | AC-2.7 | owner、sitter | owner E22 撤回 → owner E17 減少 V3 → sitter E20 `surchargeAmount=100`+原因 200 字 | — | 200、`newTotalAmount=1100` | API JSON |
| MQ-24 | AC-2.8 | sitter | `sitter` PRO 時 owner E17 減少 V3；`sitter-setup` 改 BASIC；sitter E20 `surchargeAmount=200`+原因；再 E20 不帶加減 | — | 前者 403 `PLAN_REQUIRED`；後者 200、`newTotalAmount=1000` | API JSON |
| MQ-25 | AC-2.8 | sitter | 另一張單：`sitter` BASIC 時 owner E17 減少 V3；`sitter-setup` 改 PRO；sitter E20 `discountAmount=100`+原因 | — | 200、`newTotalAmount=900` | API JSON |
| MQ-26 | AC-2.8 | sitter | 另一張單：owner E17 減少 V3；`sitter-setup` PRO + `subscriptionExpiresAt = now − 2 天`；保母 UI 填加價 200 送出報價 | 顯示「自訂變更金額需專業版以上」與「訂閱已逾期，變更金額依系統計算」 | E20 403 `PLAN_REQUIRED`；仍 `REQUESTED` | 截圖 + API JSON |
| MQ-27 | AC-2.8 | sitter | 同上，E20 不帶加減 | — | 200、`QUOTED`、`newTotalAmount=1000`（不回 403 `SUBSCRIPTION_LOCKED`） | API JSON |
| MQ-28 | AC-2.8 | sitter | 另一張單：`sitter-setup` PRO + `suspended=true`；owner E17 減少 V3；sitter E20 `surchargeAmount=200`+原因；再 E20 不帶加減 | — | 403 `PLAN_REQUIRED`；200、`newTotalAmount=1000` | API JSON |

### 2.3 子模組 3：撤回、逾時、協商頁與冪等

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| MW-01 | AC-3.1 | owner | `order` 上 owner E17（`REQUESTED`）；飼主協商頁點 `modification-btn-withdraw` | 顯示唯讀結果：已撤回、結束時間（Asia/Taipei） | E22 200：`status=WITHDRAWN`、`endedAt` 在 `[t0, t1]`；F1、E4 `totalAmount=1500`、E2 `status` 不變；sitter 通知 `type=MODIFICATION_WITHDRAWN`「飼主已撤回變更請求」 | 截圖 + API JSON |
| MW-02 | AC-3.1 | owner | 另一筆：owner E17 → sitter E20（`QUOTED`）→ owner E22 | — | 200、`WITHDRAWN`、`endedAt` 有值；sitter 收到通知 | API JSON |
| MW-03 | AC-3.2 | owner、sitter | sitter E17 減少 V3（`QUOTED`）；owner E22；保母協商頁點 `modification-btn-withdraw` | 保母頁撤回後顯示唯讀結果 | owner → 403 `FORBIDDEN`、仍 `QUOTED`；保母 → 200 `WITHDRAWN`、`endedAt` 有值；owner 通知 `type=MODIFICATION_WITHDRAWN` | 截圖 + API JSON |
| MW-04 | AC-3.2 | owner、sitter | `orderSup`（owner 發起，`AWAITING_SUPPLEMENT`）：owner E22、sitter E22；`orderRef`（sitter 發起，`AWAITING_REFUND`）：sitter E22、owner E22 | — | 發起者 → 409 `MODIFICATION_STATE_CONFLICT`（`currentStatus` 為該狀態）；另一方 → 403 `FORBIDDEN`；狀態不變 | API JSON |
| MW-05 | AC-3.2 | owner、sitter | 建 SYSTEM 退款變更：owner E17 減少 V3、新增 D+7 兩次 → sitter E20（2000）→ owner E23（補款 500）→ shift-time `PAYMENT_DUE_AT`（`paymentId`=補款、`minutes=-(72*60+1)`）→ C6 `POST /api/internal/cron/modifications/supplement-expiry`；取 E18 `initiatedBy=SYSTEM` 那列；owner、sitter 各 E22 | 協商頁顯示「此退款由系統建立，無法撤回」且無撤回按鈕 | 皆 403 `FORBIDDEN`；仍 `AWAITING_REFUND` | 截圖 + API JSON |
| MW-06 | AC-3.2 | owner、sitter | 依 MI-15 的同一流程產生 `REJECTED`、`WITHDRAWN`、`EXPIRED`、`SETTLED` 四筆；各由其發起者 E22 | — | 皆 409 `MODIFICATION_STATE_CONFLICT`；`endedAt` 不變 | API JSON |
| MW-07 | AC-3.3 | — | `order` 上 owner E17（`REQUESTED`）；shift-time `MODIFICATION_EXPIRES_AT` `minutes=-(72*60+1)`；先查 E19；再呼叫 C5 | — | shift 後 E19 仍 `REQUESTED`；C5 200（`processed ≥ 1`）；E19 `EXPIRED`、`endedAt ≥` C5 呼叫時間；E2 `status`、F1 不變；雙方各 1 筆 `type=MODIFICATION_EXPIRED`「變更請求已逾期」 | API JSON |
| MW-08 | AC-3.3 | — | 另一筆：owner E17 → sitter E20（`QUOTED`）→ shift 同上 → C5 | — | 同 MW-07 | API JSON |
| MW-09 | AC-3.3 | — | 再呼叫一次 C5 | — | E19 `endedAt` 與 MW-07 相同；雙方 `MODIFICATION_EXPIRED` 通知仍各 1 筆 | API JSON |
| MW-10 | AC-3.4 | owner、sitter | 另一筆 `REQUESTED`；shift `minutes=-(72*60+1)`（不跑 C5）；讀 E19 `expiresAt` 為 e1；雙方開協商頁；sitter E20、sitter E21（附原因）、owner E22 | 雙方頁面的報價 / 拒絕 / 撤回按鈕都不存在，顯示「此變更請求已逾期」 | 三次皆 409 `DEADLINE_PASSED`，`payload.deadline=MODIFICATION_EXPIRES_AT`、`deadlineAt=e1`；E19 仍 `REQUESTED`、`allowedActions=[]` | 截圖 + API JSON |
| MW-11 | AC-3.4 | owner | 另一筆 owner 發起的 `QUOTED`；shift 同上；owner E22 | 飼主頁無按鈕、顯示「此變更請求已逾期」 | 409 `DEADLINE_PASSED`（同 payload）；仍 `QUOTED` | 截圖 + API JSON |
| MW-12 | AC-3.5 | owner、sitter | owner 發起的 `REQUESTED`：雙方開協商頁 | 飼主：只有 `modification-btn-withdraw`；保母：只有 `modification-btn-quote`、`modification-btn-reject` | E19（各自 token）`allowedActions`：owner `[WITHDRAW]`、sitter `[QUOTE, REJECT]` | 截圖 + API JSON |
| MW-13 | AC-3.5 | owner、sitter | 報價後（owner 發起的 `QUOTED`）雙方開協商頁 | 飼主：`modification-btn-accept`、`modification-btn-reject`、`modification-btn-withdraw`；保母：沒有 `modification-btn-quote` / `modification-btn-reject` / `modification-btn-withdraw`，顯示 `modification-detail-diff` 與 `modification-detail-expires-at` | owner `[ACCEPT, REJECT, WITHDRAW]`、sitter `[]` | 截圖 + API JSON |
| MW-14 | AC-3.5 | owner、sitter | sitter 發起的 `QUOTED`：雙方開協商頁 | 飼主：`modification-btn-accept`、`modification-btn-reject`（無撤回）；保母：只有 `modification-btn-withdraw` | owner `[ACCEPT, REJECT]`、sitter `[WITHDRAW]` | 截圖 + API JSON |
| MW-15 | AC-3.5 | owner、sitter | 對 MW-06 的四筆終態變更，雙方各開協商頁 | 無任何動作按鈕；顯示狀態、結束時間、金額（有報價者）、原因 | 皆 `allowedActions=[]` | 截圖 + API JSON |
| MW-16 | AC-3.5 | owner | 以 MI-15 流程的訂單（5 筆變更）開 `/owner/orders/{order}` 變更歷史；另開一張無變更的 `CONFIRMED` 單 | 前者 `modification-history-item-{id}` 共 5 個，DOM 順序與 E18 一致（`createdAt` 倒序）；後者 `modification-history-empty`「尚無變更紀錄」 | E18 前者 5 列倒序、後者 `[]` | 截圖 + API JSON |
| MW-17 | AC-3.6 | owner、sitter | owner E17（`REQUESTED`）；以 `Promise.all` 同時送 owner E22 與 sitter E20（不帶加減） | — | 恰一個 200；另一個 409 `MODIFICATION_STATE_CONFLICT`；E19 最終狀態 = 成功者的結果（`WITHDRAWN` 或 `QUOTED`）；對方通知只有成功那一種（`ORDER_BUSY` 與 `order_logs` 筆數見 §4） | API JSON |
| MW-18 | AC-3.7 | owner | 另一張單：owner E17 帶 `Idempotency-Key=K1`；同 key 同 body 重送；同 key 改 `reason`；換新 key 同 body | — | 201；回放 201 且 `modificationId` 相同、E18 長度 1；422 `IDEMPOTENCY_KEY_REUSED`；409 `MODIFICATION_IN_PROGRESS` | API JSON |
| MW-19 | AC-3.7 | sitter | sitter E20 帶 `K2`；同 key 同 body 重送；換新 key 同 body | — | 200；回放 200、body 與第一次相同；409 `MODIFICATION_STATE_CONFLICT`，E19 `newTotalAmount` 不變 | API JSON |
| MW-20 | AC-3.7 | owner | 另一張單 E17 不帶 `Idempotency-Key` header | — | 400 `VALIDATION_FAILED`、`payload.field=Idempotency-Key`；E18 長度 0 | API JSON |

## 3. 權限與例外

> 每個 PRD EX-n 至少一列；越權一律用另一個真實帳號打 API。已在 §2 覆蓋的 EX 列出對應步驟。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| ME-01 | EX-1、AC-1.7 | `order` 有一筆 `REQUESTED`；`ownerB` 呼叫 E17、E18、E19、E21、E22；`sitterB` 呼叫 E20 | 皆 403 `FORBIDDEN`；E18（本人）長度不變、E19 仍 `REQUESTED` |
| ME-02 | EX-2、AC-1.7 | owner 以隨機 UUID 當 `orderId` 呼叫 E17；以隨機 UUID 當 `modificationId` 呼叫 E19、E20（sitter）、E21、E22 | 皆 404 `NOT_FOUND` |
| ME-03 | EX-3、AC-1.7 | `sitter` 帳號以角色切換取得 `currentRole=OWNER` 的 token（見 §5 待補 2）呼叫 E17、E20；`owner` 呼叫 E20；`admin` 呼叫 E17、E20、E21、E22 | 皆 403 `ROLE_MISMATCH`；無新增變更、既有 `REQUESTED` 不變 |
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
| ME-15 | EX-19 | 見 MI-06、MI-17、MI-18、MQ-06、MQ-17、MQ-21、MW-20；另：E17 `addItems[0].date="2026/13/01"` | 400 `VALIDATION_FAILED`（`field` 指向 `addItems[0].date`），不建立變更 |
| ME-16 | EX-21 | 見 MI-17、MQ-11 | 422 `MODIFICATION_EMPTY` |
| ME-17 | EX-22 | 見 MI-18 | 422 `PLAN_NOT_IN_ORDER` |
| ME-18 | EX-23 | 見 MI-12 | 409 `MODIFICATION_NOT_ALLOWED` |
| ME-19 | EX-24、BR-28 | (a) MI-11 的 `DISPUTED` 訂單上 owner E17 帶 `reason=""`；(b) owner E17 減少 V3 → FT1 V3 `IN_PROGRESS` → shift `MODIFICATION_EXPIRES_AT` 過期 → sitter E20（`removeVisitIds=[V3]`）；(c) 見 MI-11、MQ-04 | (a) 400 `VALIDATION_FAILED`（先於 `ORDER_STATE_CONFLICT`）；(b) 409 `DEADLINE_PASSED`（先於 `VISIT_ALREADY_STARTED`）；(c) `ORDER_STATE_CONFLICT` 先於 `MODIFICATION_IN_PROGRESS`、422 先於 `PLAN_REQUIRED` |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.1（`order_logs` 新增一筆）；所有轉移的 `order_logs` 斷言 | 當事人無讀取 `order_logs` 的端點；E27 只對 ADMIN 可讀範圍（`DISPUTED` 或有 OPEN 待辦）開放 | 後端整合測試 `ModificationInitiateIntegrationTest`、`ModificationQuoteIntegrationTest`、`ModificationLifecycleIntegrationTest`、`ModificationCronIntegrationTest`（`order_logs` action / operator / payload 依 SD-ORDER §7.1） |
| AC-1.1、AC-2.1（`expires_at` 精確值 `2026-11-24T02:00:00Z`） | 正式站無法固定時鐘；E2E 以 `[t0+72h, t1+72h]` 夾值 | `ModificationInitiateIntegrationTest`、`ModificationQuoteIntegrationTest`（注入 `Clock`） |
| AC-2.5（2026-11-21 23:30 跨日邊界） | 需固定時鐘；E2E 只驗 D / D+1 | `ModificationQuoteIntegrationTest`（注入 `Clock`，剛好 00:00 前後） |
| AC-3.1（結束時間 = `2026-11-22T01:00:00Z`） | 需固定時鐘 | `ModificationLifecycleIntegrationTest` |
| AC-3.3（排程於到期後 ≤ 15 分鐘內執行）；訂單 `DISPUTED` 時略過；`order_logs` 操作者 SYSTEM | 頻率由 Cloud Scheduler 設定決定，E2E 直接呼叫 C5 | 手動驗證 Cloud Scheduler job `modifications/expiry` 為每 15 分鐘（SD-ORDER §8.3）；`ModificationCronIntegrationTest` |
| AC-3.6（鎖等待 > 3 秒 → 409 `ORDER_BUSY` + `Retry-After: 1`；`order_logs` 只有成功那筆；指定先後順序） | 正式站無法持有訂單鎖 3 秒；`Promise.all` 無法控制先後 | `ModificationConcurrencyIntegrationTest`、`OrderLockServiceTest` |
| AC-3.7（`IDEMPOTENCY_IN_PROGRESS`、接手） | 無法在正式站製造處理中狀態 | `ModificationIdempotencyIntegrationTest`（SD-ORDER §10.2） |
| AC-3.8、EX-17（Resend 5xx 不影響轉移、重試 5 次） | 正式站無法注入 Resend 失敗；Resend 測試模式只寄專案擁有者信箱 | `ModificationInitiateIntegrationTest`（Resend mock）；真實寄信手動驗證 |
| EX-13（SITTER 開始行程 vs 送出含該行程的變更同時） | 需控制先後；F3 需行程日期已到 | `ModificationConcurrencyIntegrationTest`（SD-ORDER §10.3 未列此情境，見 §5 待補 6） |
| EX-14（鎖等待逾時） | 同 AC-3.6 | `OrderLockServiceTest`、`ModificationConcurrencyIntegrationTest` |
| EX-18（GCS） | 不適用：本卡不上傳檔案 | — |
| EX-20（排程單筆失敗回滾、其他筆照常） | 正式站無法注入單筆例外 | `ModificationCronIntegrationTest`（SD-ORDER §10.3 該類別未列此情境，見 §5 待補 6） |

## 5. 已知限制 / 待補

1. **ADMIN 測試帳號**：SD-ORDER §10.4 未列 `provision-account` 能否建立 ADMIN 角色的 `@e2e-journey.test` 帳號；ME-03 需要。需後端確認或擴充。
2. **角色切換 token**：ME-03 需要同一帳號以 `currentRole=OWNER` 呼叫保母端點（G4a）。§10.4 未列取得方式；若既有角色切換 API 可用則沿用，否則需新增測試端點。
3. **缺 testid**：訂單詳情的「申請變更 / 提出變更」入口；保母報價的加價 / 折扣 / 調整原因輸入；`order-modify-add-item-{index}` 內的日期、方案、次數、毛孩勾選子元件。spec 先用畫面業務名稱，e2e-author 與 frontend-dev 補定。
4. **目前總額的讀取來源**：E2 只有 `quote.totalAmount`（報價時值），SD 未寫明變更 `SETTLED` 後 E2 何處反映新的 `total_amount`；本 TS 以 E4 `content[].totalAmount` 斷言。
5. **`sitter-setup` 重複呼叫**：MQ-14~MQ-16、MQ-24~MQ-28 需要對既有保母改等級、`subscriptionExpiresAt`、`suspended`、方案區間；§10.4 未寫明可重複呼叫並覆寫。需後端確認。
6. **後端測試缺口**：SD-ORDER §10.3 `ModificationConcurrencyIntegrationTest` 未列 EX-13 第二情境；`ModificationCronIntegrationTest` 未列 EX-20 單筆失敗。
7. **時間敏感**：MQ-12、MQ-13 以 D / D+1 判斷，避免在 Asia/Taipei 23:55–00:05 執行（spec 偵測到時 `test.skip` 並註記）。
8. **正式站排程**：直接呼叫 C5 / C6 會一併處理其他到期資料，斷言只看本測試的變更列，不斷言 `processed` 精確值。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依新範本全文重寫：對應拆卡後的 PRD-016（發起、報價、拒絕、撤回、逾時）與 SD-ORDER；補款 / 退款 / 結清移至 TS-023、款項爭議移至 TS-024；舊版 Scenario（`MODIFYING`、`REFUND_VERIFY`、`previous_status`）全部廢止 | PRD-016、SD-ORDER Approved 2026-11-22 |
