# TS-JOURNEY-04: 訂單變更協商全流程（增加行程補款、減少行程退款、逾時、款項爭議）

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-016（發起、報價、逾時）、PRD-023（同意、補款、退款、結清）、PRD-024（款項爭議、`DISPUTED` 凍結、調解結果）——主線；PRD-007（補款 / 退款憑證上傳與確認）、PRD-008（行程狀態列）、PRD-028（變更新增行程的檢核項快照）——沿途觀察。全部 Approved 2026-11-22 |
| 對應 SD | SD-ORDER（§3.2、§3.3、§4.3、§6.3 E2、E10–E14、E17–E24、E27–E29、C5、§6.5、§7.1、§7.2、§8.1、§9.1、§9.3、§10.4）——訂單、payment、變更、稽核、通知步驟；SD-FULFILL（§2.3.3、§6.3 F1、§10.2 `inspect.fulfill`；`createVisits` / `cancelVisits`）——行程步驟（SD-ORDER M33 分工）；SD-FRONTEND-SPEC §4、§6.4、§6.4.1 |
| 類型 | 旅程（打真後端）：跨 PRD 端到端主線，不重複模組 TS 細項；每步標出 PRD AC 與對應模組 TS 步驟 |
| 自動化路徑 | `frontend/e2e/journeys/order-modification-negotiation.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> 本檔全文重寫。舊版（`MODIFYING` / `REFUND_VERIFY` 訂單狀態、`previous_status` 復原、`/sitter/orders/:id/quote` 與 `/owner/orders/:id/modification-confirm` 兩頁、`POST /api/orders/{id}/modify`）依 baseline §3.4（變更協商期間訂單主狀態不變，CQ-1）全部作廢；細項驗證分別移至 TS-016 / TS-023 / TS-024。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 自己建資料、可獨立重跑。帳號一律 `@e2e-journey.test`，由 `POST /api/internal/test-data/provision-account` 建立；測試資料端點（`/api/internal/test-data/**`）與排程端點（`/api/internal/cron/**`）帶 `X-Internal-Secret`（值取自 CI 環境變數，不得寫入檔案、證據或 log）。
> 日期記號：`D` = 執行當下 Asia/Taipei 日期，`D+n` 由 spec 執行時計算。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `provision-account`（`roles=[OWNER]`）；`owner-setup`：`pets` = key M1、M2（CAT）、`emailPreferences.ORDER_AFFAIR=開`；回應以 key 取 `petId` | — |
| `ownerB` | OWNER | `provision-account`（`roles=[OWNER]`）；`owner-setup` 一隻毛孩 | 與各訂單無關的第三人（越權用） |
| `sitter` | SITTER | `provision-account`（`roles=[SITTER]`）；`sitter-setup`：`tier=PRO`、`subscriptionExpiresAt = now + 30 天`、`suspended=false`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄、`plans` = key P（單價 500、CAT、無可預約區間、`active=true`、`defaultTasks=[餵乾糧, 換貓砂]`）、`questions=[]`；回應以 key 取 `planId` | PRO 全程不降級（主線報價要加價） |
| `admin` | ADMIN | `provision-account`（`roles=[ADMIN]`） | — |
| `order` | — | `owner` E1 `POST /api/orders` 預約 P：`groups=[{planId: P, dates: [D+3, D+4, D+5], dailyCount: 1, petIds: [M1, M2]}]`、`expectedAmount=1500` → `sitter` E6 原價（`expectedAmount=1500`）→ `owner` E11 首次付款憑證（`disclaimerAgreed=true`、`lastFive=12345`、PNG）→ `sitter` E14 確認；F1 `GET /api/orders/{order}/visits` 依 `serviceDate` 取 V1（D+3）、V2（D+4）、V3（D+5） | `CONFIRMED`；E2 `quote.totalAmount=1500`、`netPaid=1500` |

- 三個 test 各自建一組 `owner` / `sitter` / `order`：**T1 主線 + 退款分支**（J4-01–J4-21，同一張單接續，驗證連續兩筆變更）、**T2 逾時分支**（J4-22–J4-26）、**T3 爭議分支**（J4-27–J4-33）。`ownerB`、`admin` 可共用。
- 建單 helper 建議沿用 TS-016 §1 的 `bootstrapConfirmedOrder`。

前置動作縮寫（皆走真 API，每次新 `Idempotency-Key`，同 TS-023 §1）：

| 縮寫 | 內容 |
|------|------|
| `REQ(x)` | owner E17 `POST /api/orders/{order}/modifications`：`type=CHANGE`、內容 x、`reason=測試` → `REQUESTED` |
| `QUOTE()` | sitter E20 `POST /api/orders/{order}/modifications/{mod}/quote`：內容維持預填、不帶加減 → `QUOTED` |
| `PROPOSE(x)` | sitter E17：`type=CHANGE`、內容 x、`reason=測試`、不帶加減 → `QUOTED`（`initiatedBy=SITTER`） |
| `ACCEPT` | owner E23 `POST /api/orders/{order}/modifications/{mod}/accept` |
| `INSPECT` | `GET /api/internal/test-data/orders/{order}/inspect` |
| `SHIFT_MOD(m)` | `POST /api/internal/test-data/orders/{order}/shift-time`：`target=MODIFICATION_EXPIRES_AT`、`modificationId=mod`、`minutes=m` |
| `C5` | `POST /api/internal/cron/modifications/expiry?orderId={order}` |

**斷言來源**

- 訂單：E2 `GET /api/orders/{order}`（`status`、`quote.totalAmount` = 目前生效總額、`netPaid`、`openModification`、`allowedActions`、`cancellation`）；目前總額另與 `INSPECT.totalAmount` 核對。
- 變更：E19 `GET /api/orders/{order}/modifications/{mod}`（`status`、`newTotalAmount`、`netPaid`、`diff`、`expiresAt`、`endedAt`、`allowedActions`、`payment`）；列表 E18（`createdAt` 倒序）。
- payment：E10 `GET /api/orders/{order}/payments`（`purpose`、`direction`、`amount`、`status`、`dueAt`、`accountSnapshot`、`verifiedAt`、`voidedAt`，`created_at` 由舊到新）；submission：E12。
- 行程：F1（`serviceDate`、`planId`、`status`）；檢核項：`INSPECT.fulfill.checklistItems`（`visitId`、`title`、`sortOrder`、`isCompleted`、`createdSource`）。
- 稽核與通知：`INSPECT.orderLogs`（`actionType`、`operatorRole`、`payload`）、`INSPECT.notifications`（`type`、`recipientRole`、`mandatory`、`emailStatus`）；同一步驟只斷言本步新增的列（以步驟前列數為基準）。通知文案以收件者 token 呼叫 `GET /api/notifications` 篩同一 `type`，只斷言關鍵字與金額數字（範本由 SD-NOTIFY 管理）。
- 待辦：`INSPECT.adminTasks`；ADMIN 端 E28 `GET /api/admin/tasks`、E27 `GET /api/admin/orders/{order}`。
- 時間：送出前 t0、回應後 t1 夾值（例 `t0+72h ≤ dueAt ≤ t1+72h`）；固定時鐘精確值交後端整合測試（§4）。
- 金額顯示：SD-FRONTEND-SPEC §6.4.1 `$ 1,800`（`$` 後為 U+00A0）；狀態文字依 §6.4。
- 多筆 payment 並存時以 `payment-block-{paymentId}`（SD-ORDER §9.3）定位，其內再找 PRD-007 的 `payment-amount` 等元件。

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4：訂單全套含 `payments` / `payment_submissions` / GCS 憑證物件、`modification_requests`、`admin_tasks`、`order_logs`、`visits` / `visit_checklist_items`、`idempotency_records`，再清通知、方案、毛孩、帳號）。本旅程沒有 cleanup 清不掉的新資料型態。

## 2. 測試步驟

> 步驟 ID 前綴 `J4-`；spec 的 `step()` / `apiStep()` 標籤必須以同一 ID 開頭。E11 / E14 / E17 / E20–E24 / E29 一律帶 `Idempotency-Key`（UUID），除非該步驟另寫。
> AC 欄格式：`PRD-NNN AC-x.y（模組 TS 步驟）`；模組 TS 步驟 = 該模組 TS 中驗證同一行為的細項步驟，本旅程只驗跨 PRD 串接後的結果。

### 2.1 主線：飼主申請增加行程 → 保母加價報價 → 飼主同意 → 補款 → 保母確認 → SETTLED（T1）

前置：建 `order`；記下 E10 `INITIAL` 那筆與 E12（`INITIAL`）為快照 S0。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| J4-01 | PRD-016 AC-1.1（TS-016 MI-01） | owner | 開啟 `/owner/orders/{order}`，點 `owner-order-detail-btn-modify` | 導向 `/orders/{order}/modify`；`order-modify-type-CHANGE`、`order-modify-type-CANCEL_ALL` 可見；`order-modify-visit-checkbox-{V1}`、`-{V2}`、`-{V3}` 皆可勾選 | E2 `status=CONFIRMED`、`allowedActions` 含 `REQUEST_MODIFICATION`、`openModification` 不輸出 | 截圖 + API JSON |
| J4-02 | PRD-016 AC-1.1、BR-26（TS-016 MI-02） | owner | 選 `order-modify-type-CHANGE`；不勾任何減少行程；在新增行程列 `order-modify-add-item-0` 的 `-date` 填 D+7、`-plan` 選 P、`-daily-count` 填 1，毛孩維持預設；`order-modify-input-reason` 填「多請一天」；點 `order-modify-btn-submit` | 送出前 `order-modify-add-item-0-pet-checkbox-{M1}`、`-{M2}` 皆已勾選；送出後導向 `/orders/{order}/modifications/{mod}`，顯示 `modification-detail-expires-at`（`expiresAt` 換算 Asia/Taipei `YYYY-MM-DD HH:mm`）；飼主只看到 `modification-btn-withdraw` | 頁面發出的 E17 恰一次，201：`status=REQUESTED`、`initiatedBy=OWNER`、`systemAmount` / `newTotalAmount` / `diff` 不輸出、`expiresAt` 在 `[t0+72h, t1+72h]`；E19 `requestedContent` 無減少、`addItems[0]` = D+7 / P / 1 / `petIds={M1, M2}` | 截圖 + API JSON |
| J4-03 | PRD-016 AC-1.1、BR-20（TS-016 MI-03、MI-04） | owner、sitter | 查訂單、行程、payment、`INSPECT`；sitter `GET /api/notifications` | — | E2 `status=CONFIRMED`、`openModification={modificationId: mod, status: REQUESTED, initiatedBy: OWNER}`、`quote.totalAmount=1500`；F1 只有 V1–V3（無 D+7）；E10 只有 `INITIAL`；`INSPECT.orderLogs` 新增恰 1 筆 `MODIFICATION_REQUESTED`（`operatorRole=OWNER`、`payload.modificationId=mod`、`payload.reason=多請一天`）；`INSPECT.notifications` 新增恰 1 筆 `MODIFICATION_REQUESTED` → SITTER（`mandatory=false`）；sitter 通知列表該則文案含「飼主提出訂單變更」、連結 `/orders/{order}/modifications/{mod}` | API JSON |
| J4-04 | PRD-016 AC-2.3、AC-3.5（TS-016 MQ-01、MW-12） | sitter | 開啟 `/sitter/orders/{order}`，從訂單詳情進入進行中變更的協商頁（業務名稱「查看變更」入口；SD-ORDER §9.1 / §9.3 未定 testid，見 §5 第 1 項，由 e2e-author 對應；不得以直接輸入網址取代） | 導向 `/orders/{order}/modifications/{mod}`；顯示飼主期望內容（新增 D+7 / P / 1 次）、原因「多請一天」、`modification-detail-diff`「應補款 $ 500」（系統計算值 2000 − 已收淨額 1500）；`modification-btn-quote`、`modification-btn-reject` 可見；`modification-input-surcharge`、`modification-input-discount`、`modification-input-adjustment-reason` 可輸入（PRO 未鎖定） | E19（SITTER）`status=REQUESTED`、`allowedActions=[QUOTE, REJECT]` | 截圖 + API JSON |
| J4-05 | PRD-016 AC-2.3、AC-2.1（通知）、BR-16（TS-016 MQ-07、MQ-02） | sitter | 記下 E19 `expiresAt` 為 e0；`modification-input-surcharge` 填 300、`modification-input-adjustment-reason` 填「節日加成」；點 `modification-btn-quote` | 報價後保母頁 `modification-btn-quote`、`modification-btn-reject`、`modification-btn-withdraw` 數量皆 0，只顯示唯讀報價、`modification-detail-diff`「應補款 $ 800」與 `modification-detail-expires-at` | 頁面發出的 E20 恰一次，200：`status=QUOTED`、`systemAmount=2000`、`newTotalAmount=2300`、`netPaid=1500`、`diff=800`、`expiresAt` 在 `[t0+72h, t1+72h]` 且 > e0；E19 `surchargeAmount=300`、`adjustmentReason=節日加成`、`allowedActions=[]`（SITTER）；`INSPECT.orderLogs` 新增 1 筆 `MODIFICATION_QUOTED`（`operatorRole=SITTER`、`payload.newTotalAmount=2300`、`payload.diff=800`）；`INSPECT.notifications` 新增 1 筆 `MODIFICATION_QUOTED` → OWNER，通知列表文案「保母已回覆變更報價」 | 截圖 + API JSON |
| J4-06 | PRD-016 AC-3.5、PRD-023 §4.1-1（TS-016 MW-13） | owner | 從通知連結（J4-05 的 `MODIFICATION_QUOTED` 通知）開啟協商頁 | 顯示新內容（新增 D+7）、`modification-detail-diff`「應補款 $ 800」、`modification-detail-expires-at`；`modification-btn-accept`、`modification-btn-reject`、`modification-btn-withdraw` 可見 | E19（OWNER）`allowedActions=[ACCEPT, REJECT, WITHDRAW]`；接著執行 J4-E01、J4-E02、J4-E07 | 截圖 + API JSON |
| J4-07 | PRD-023 AC-1.2（TS-023 SA-03） | owner | 點 `modification-btn-accept`（頁面送出的 key 記為 K_acc） | 同意 / 拒絕 / 撤回按鈕消失；協商頁出現補款區塊 `payment-block-{補款 id}` | 頁面發出的 E23 恰一次，200：`status=AWAITING_SUPPLEMENT`、`netPaid=1500`、`diff=800`、`payment={direction: CHARGE, purpose: SUPPLEMENT, amount: 800}`、`payment.dueAt` 在 `[t0+72h, t1+72h]`、`orderStatus=CONFIRMED`、`totalAmount=1500`；F1 仍只有 V1–V3；E2 `quote.totalAmount=1500`、`openModification.status=AWAITING_SUPPLEMENT`；E10 依序 `INITIAL`、`SUPPLEMENT`（`UNPAID`、`accountSnapshot` 四欄齊全）；`INSPECT.orderLogs` 新增 1 筆 `MODIFICATION_ACCEPTED`（`payload.branch=AWAITING_SUPPLEMENT`、`agreedNetPaid=1500`、`agreedDiff=800`、`cancelledVisitIds=[]`、`paymentId`=補款 id）；`INSPECT.notifications` 新增 `SUPPLEMENT_REQUIRED` → OWNER 1 筆、`MODIFICATION_ACCEPTED_SUPPLEMENT` → SITTER 1 筆；owner 通知列表 `SUPPLEMENT_REQUIRED` 文案含金額 800 與「請於 {dueAt 的 Asia/Taipei YYYY-MM-DD HH:mm} 前完成」 | 截圖 + API JSON |
| J4-08 | PRD-023 AC-1.8（TS-023 SA-15） | owner | 以 API 用 K_acc 同 body 重送 E23；再換新 key 送 E23 | — | 回放：狀態碼 200 與 body 同 J4-07；E10 `SUPPLEMENT` 仍 1 筆；`MODIFICATION_ACCEPTED` 的 `order_logs` 與 `SUPPLEMENT_REQUIRED` 通知皆未增加。新 key → 409 `MODIFICATION_STATE_CONFLICT`；E10 `SUPPLEMENT` 仍 1 筆 | API JSON |
| J4-09 | PRD-023 AC-1.2、BR-7、BR-17；PRD-016 AC-1.5（TS-023 SA-04、TS-016 MI-14） | owner | 檢視協商頁補款區塊；再開 `/owner/orders/{order}` | `payment-block-{補款 id}` 內 `payment-amount`「$ 800」、`payment-due-at`「請於 … 前完成」（與 J4-07 同一時間字串）、`payment-account-snapshot` 可見；頁面沒有以 `$ 2,300` 作為應付金額的元素、沒有 `order-total-amount`、沒有退款區塊（無 `payment-refund-dispute-link`）、飼主看不到 `btn-verify-payment`；訂單詳情沒有 `owner-order-detail-btn-modify` | E2 `allowedActions` 不含 `REQUEST_MODIFICATION`；接著執行 J4-E09 | 截圖 + API JSON |
| J4-10 | PRD-023 §4.3-1；PRD-007 AC-1.4（TS-023 SS-01、TS-007 P-18） | owner | 在 `payment-block-{補款 id}` 勾 `proof-disclaimer-checkbox`、`proof-last5-input` 填 `54321`、`proof-file-input` 選 PNG、點 `proof-submit-btn` | `submission-history` 出現 `submission-history-item-{submissionId}`（後五碼 54321） | 頁面發出的 E11 恰一次，201：`paymentStatus=SUBMITTED`；E10 `SUPPLEMENT` `SUBMITTED`、`INITIAL` 不變；`INSPECT.orderLogs` 新增 1 筆 `PAYMENT_PROOF_SUBMITTED`（`operatorRole=OWNER`、`payload.purpose=SUPPLEMENT`、`payload.lastFive=54321`）；`INSPECT.notifications` 新增 1 筆 `PAYMENT_PROOF_SUBMITTED` → SITTER | 截圖 + API JSON |
| J4-11 | PRD-023 AC-2.1、BR-7；PRD-007 AC-2.2（TS-023 SS-02、TS-007 PV-05） | sitter | 先以 `sitter-setup` upsert 只送 `plans=[{key: P, defaultTasks: [餵乾糧, 換貓砂, 梳毛]}]`（供 J4-13）；接著執行 J4-E03；保母開協商頁補款區塊，點 `btn-verify-payment` | 核對區 `payment-amount`「$ 800」（不是 `$ 2,300`）、`submission-history-image-{submissionId}` 已載入（`naturalWidth>0`）、後五碼 54321；確認後顯示唯讀「變更已生效」結果，無任何動作按鈕 | 頁面發出的 E14 恰一次，200：`paymentStatus=VERIFIED`、`modification={modificationId: mod, status: SETTLED}`、`orderStatus=CONFIRMED` | 截圖 + API JSON |
| J4-12 | PRD-023 AC-2.1、BR-3、BR-15（TS-023 SS-02） | — | 查變更、訂單、行程、payment、`INSPECT` | — | E19 `status=SETTLED`、`endedAt` 在 J4-11 的 `[t0, t1]`；F1 共 4 筆：V1–V3 `PENDING` + 新行程 N1（`serviceDate=D+7`、`planId=P`、`status=PENDING`）；E2 `status=CONFIRMED`、`quote.totalAmount=2300`（= `INSPECT.totalAmount`）、`netPaid=2300`、`openModification` 不輸出、`allowedActions` 再度含 `REQUEST_MODIFICATION`；`INSPECT.completionBasisAt` 不輸出（仍有 `PENDING` 行程，SD-ORDER §3.1.3）；E10 補款 `VERIFIED`、`verifiedAt` 在 `[t0, t1]`；`INSPECT.orderLogs` 新增 1 筆 `PAYMENT_VERIFIED`（`operatorRole=SITTER`、`payload.purpose=SUPPLEMENT`、`payload.modificationId=mod`、`payload.newTotalAmount=2300`），且無 `ORDER_STARTED`；`INSPECT.notifications` 新增 `SUPPLEMENT_VERIFIED` 恰 2 筆（OWNER、SITTER），文案「補款已確認，變更已生效」；本單沒有新的 `ORDER_ACTIVATED` | API JSON |
| J4-13 | PRD-023 AC-2.2；PRD-008 AC-3.3；PRD-028 AC-1.1 情境 2、3（TS-023 SS-03、TS-008 V-54、TS-028 §4 AC-1.1-3） | owner | 開 `/owner/orders/{order}`；比對 S0；查 `INSPECT.fulfill.checklistItems` | 行程清單 4 列 `owner-order-detail-visit-row-{V1、V2、V3、N1}`；`owner-order-detail-visit-status-{N1}`「待執行」；頁面不出現英文狀態碼 | 首次付款不被覆寫：E10 `INITIAL` 的 `status=VERIFIED`、`amount=1500`、`currentSubmissionId`、`verifiedAt` 與 S0 相同；E12（`INITIAL`）筆數、`lastFive=12345`、`submittedAt`、`reviewedAt`、`verifiedAt` 與 S0 相同；E12（`SUPPLEMENT`）自有 1 筆 `lastFive=54321`、`status=ACCEPTED`。檢核項：N1 恰 3 項，依 `sortOrder` 為「餵乾糧」「換貓砂」「梳毛」、皆 `isCompleted=false`、`createdSource=SYSTEM`；V1 仍為 2 項「餵乾糧」「換貓砂」（既有行程不受方案修改影響） | 截圖 + API JSON |

### 2.2 退款分支：保母申請減少行程 → 飼主同意 → 待退款 → 保母上傳退款憑證 → 飼主確認 SETTLED（T1 接續）

接續 J4-13（訂單 `CONFIRMED`、V1–V3 與 N1 皆 `PENDING`、`total_amount = 已收淨額 = 2300`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| J4-14 | PRD-016 AC-1.3、AC-1.5（前一筆 `SETTLED` 後可再發起）、BR-9、BR-12（TS-016 MI-07、MI-15） | sitter | 開 `/sitter/orders/{order}`，點 `sitter-order-detail-btn-modify` → 選 `order-modify-type-CHANGE`、勾 `order-modify-visit-checkbox-{N1}`、`order-modify-input-reason` 填「該日無法服務」、加減欄留空；點 `order-modify-btn-submit` | 精靈中 V1、V2、V3、N1 四個 checkbox 皆可勾選；送出後導向協商頁，`modification-detail-diff`「應退款 $ 800」；保母只看到 `modification-btn-withdraw` | 頁面發出的 E17 恰一次，201：`status=QUOTED`、`initiatedBy=SITTER`、`systemAmount=1500`（V1–V3 各 500，前一筆的加價 300 不延續）、`newTotalAmount=1500`、`netPaid=2300`、`diff=-800`、`expiresAt` 在 `[t0+72h, t1+72h]`；E18 共 2 列（新列在前）；`INSPECT.orderLogs` 新增 1 筆 `MODIFICATION_PROPOSED`（`operatorRole=SITTER`、`payload.diff=-800`）；`INSPECT.notifications` 新增 1 筆 `MODIFICATION_PROPOSED` → OWNER（範本由 SD-NOTIFY 擬定，只斷言 `type` 與收件者） | 截圖 + API JSON |
| J4-15 | PRD-016 AC-1.3、AC-3.5（TS-016 MI-08、MW-14） | owner | 開協商頁 | `modification-detail-diff`「應退款 $ 800」；`modification-btn-accept`、`modification-btn-reject` 可見；`modification-btn-withdraw` 數量 0 | E19（OWNER）`allowedActions=[ACCEPT, REJECT]`；接著執行 J4-E06 | 截圖 + API JSON |
| J4-16 | PRD-023 AC-1.3、BR-3、BR-4（TS-023 SA-06） | owner | 點 `modification-btn-accept` | 動作按鈕消失；出現退款區塊；飼主頁沒有 `btn-verify-payment`、沒有補款區塊 | 頁面發出的 E23 恰一次，200：`status=AWAITING_REFUND`、`netPaid=2300`、`diff=-800`、`payment={direction: REFUND, purpose: MODIFICATION_REFUND, amount: 800}`、`payment.dueAt` 不輸出、`orderStatus=CONFIRMED`、`totalAmount=2300`；F1 N1 `CANCELLED`（同意即取消），V1–V3 `PENDING`；E2 `status=CONFIRMED`（因變更取消不觸發 O9）；E10 新增 `MODIFICATION_REFUND` `UNPAID`、`accountSnapshot` 不輸出；`INSPECT.orderLogs` 新增 1 筆 `MODIFICATION_ACCEPTED`（`payload.branch=AWAITING_REFUND`、`cancelledVisitIds=[N1]`、`agreedDiff=-800`），無 `ORDER_STARTED`；`INSPECT.fulfill.visits` N1 `cancelledAt` 有值；`INSPECT.notifications` 新增 1 筆 `REFUND_REQUIRED` → SITTER，文案含「請退還」、金額 800、「上傳退款憑證」 | 截圖 + API JSON |
| J4-17 | PRD-008 AC-3.3；PRD-023 AC-3.2（TS-008 V-54、TS-023 SR-03） | owner | 開 `/owner/orders/{order}`；接著執行 J4-E04、J4-E05 | `owner-order-detail-visit-row-{N1}` 仍在、`owner-order-detail-visit-status-{N1}`「已取消」、開始 / 結束時間「—」；退款 `payment-block-{退款 id}` 內沒有 `btn-verify-payment`，有 `payment-refund-dispute-link` | F1（OWNER）N1 `status=CANCELLED` | 截圖 + API JSON |
| J4-18 | PRD-007 AC-1.2；PRD-023 BR-11（TS-007 P-05） | sitter | 開協商頁（或 `/sitter/orders/{order}`）定位退款 `payment-block-{退款 id}` | `payment-amount`「$ 800」；文案「退款帳號請與飼主自行聯繫」；容器內 `payment-account-snapshot`、`payment-due-at` 數量 0；`submission-history` 顯示「尚未上傳憑證」 | sitter E10 該筆 `allowedActions=["UPLOAD"]`、無 `dueAt`、無 `accountSnapshot` | 截圖 + API JSON |
| J4-19 | PRD-023 §4.4-1；PRD-007 AC-1.4（TS-023 SR-01） | sitter | 勾 `proof-disclaimer-checkbox`、`proof-last5-input` 填 `22222`、`proof-file-input` 選 PNG、點 `proof-submit-btn` | `submission-history` 出現 `submission-history-item-{submissionId}`（後五碼 22222） | 頁面發出的 E11 恰一次，201：`paymentStatus=SUBMITTED`；`INSPECT.orderLogs` 新增 1 筆 `PAYMENT_PROOF_SUBMITTED`（`operatorRole=SITTER`、`payload.purpose=MODIFICATION_REFUND`）；`INSPECT.notifications` 新增 1 筆 `PAYMENT_PROOF_SUBMITTED` → OWNER | 截圖 + API JSON |
| J4-20 | PRD-023 AC-3.1、BR-17；PRD-007 AC-2.2、AC-2.4（TS-023 SR-02、TS-007 PV-06、PV-15） | owner | 開協商頁退款區塊，點 `btn-verify-payment`（「確認收到退款」） | 上傳後才出現 `btn-verify-payment`；`submission-history-image-{submissionId}` 已載入、後五碼 22222；`btn-reject-payment` 數量 0；`payment-refund-dispute-link` 可見；確認後顯示唯讀「變更已完成」結果 | 頁面發出的 E14 恰一次，200：`paymentStatus=VERIFIED`、`modification.status=SETTLED`、`orderStatus=CONFIRMED` | 截圖 + API JSON |
| J4-21 | PRD-023 AC-3.1、BR-13、BR-15；PRD-016 AC-3.5（歷史）（TS-023 SR-02、TS-016 MW-16） | owner | 查變更、訂單、行程、payment、`INSPECT`；開 `/owner/orders/{order}` 變更歷史 | `modification-history-item-{id}` 共 2 個，DOM 順序與 E18 一致（`createdAt` 倒序）；兩筆皆顯示已結束的唯讀結果 | E19 `SETTLED`、`endedAt` 在 J4-20 的 `[t0, t1]`；E2 `status=CONFIRMED`（V1–V3 仍為有效行程，不觸發 O10）、`quote.totalAmount=1500`（= `INSPECT.totalAmount`）、`netPaid=1500`；F1 V1–V3 `PENDING`、N1 `CANCELLED`；E10 依序 `INITIAL`（1500 `VERIFIED`）、`SUPPLEMENT`（800 `VERIFIED`）、`MODIFICATION_REFUND`（800 `VERIFIED`）；E18 2 列皆 `SETTLED`；`INSPECT.orderLogs` 新增 1 筆 `PAYMENT_VERIFIED`（`operatorRole=OWNER`、`payload.purpose=MODIFICATION_REFUND`、`payload.newTotalAmount=1500`），無 `ORDER_CANCELLED_BY_MODIFICATION`；`INSPECT.notifications` 新增 `REFUND_VERIFIED` 恰 2 筆（雙方），文案「退款已確認，變更已完成」 | 截圖 + API JSON |

### 2.3 逾時分支：已報價的變更過期（shift-time + C5 帶 orderId）（T2）

前置：建 `order`；`REQ(新增 D+7 一次)` → `QUOTE()`（2000、差額 +500，`QUOTED`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| J4-22 | PRD-016 AC-3.4、BR-15（TS-016 MW-11） | — | 讀 E19 `expiresAt` 為 e0；`SHIFT_MOD(-(72*60+1))`；再讀 E19 `expiresAt` 為 e1 | — | shift-time 200；e1 = e0 − 4321 分鐘；E19 仍 `QUOTED`、`allowedActions=[]`（雙方 token 皆然）；`INSPECT.orderLogs` 有 1 筆 `TEST_TIME_SHIFTED`（非業務 action，後續步驟比對列數時排除） | API JSON |
| J4-23 | PRD-016 AC-3.4；PRD-023 AC-1.7（TS-016 MW-10、MW-11、TS-023 SA-14） | owner、sitter | 飼主、保母各開協商頁；owner 以 API 依序呼叫 E23、E21、E22 | 雙方頁面 `modification-btn-accept` / `-reject` / `-withdraw` / `-quote` 數量皆 0，顯示「此變更請求已逾期」 | 三次皆 409 `DEADLINE_PASSED`，`payload.deadline=MODIFICATION_EXPIRES_AT`、`payload.deadlineAt=e1`；E19 仍 `QUOTED`；E10 只有 `INITIAL`；F1 仍 V1–V3 | 截圖 + API JSON |
| J4-24 | PRD-016 AC-3.3、BR-20（TS-016 MW-08） | — | 呼叫 `C5` | 協商頁（重新整理）顯示唯讀「已逾期」結果與結束時間 | C5 200：`processed=1`、`skipped=0`、`failed=0`；E19 `EXPIRED`、`endedAt ≥` C5 呼叫時間；E2 `status=CONFIRMED`、`quote.totalAmount=1500`、`openModification` 不輸出；F1 V1–V3 `PENDING`、無 D+7；E10 只有 `INITIAL`；`INSPECT.orderLogs` 新增 1 筆 `MODIFICATION_EXPIRED`（`operatorRole=SYSTEM`、`payload.fromStatus=QUOTED`、`payload.expiresAt=e1`）；`INSPECT.notifications` 新增 `MODIFICATION_EXPIRED` 恰 2 筆（OWNER、SITTER），通知列表文案「變更請求已逾期」 | 截圖 + API JSON |
| J4-25 | PRD-016 AC-3.3（重跑）（TS-016 MW-09） | — | 再呼叫一次 `C5` | — | `processed=0`、`skipped=0`、`failed=0`；E19 `endedAt` 與 J4-24 相同；`MODIFICATION_EXPIRED` 的 `order_logs` 仍 1 筆、通知仍 2 筆 | API JSON |
| J4-26 | PRD-016 AC-1.5（前一筆 `EXPIRED` 後可再發起）（TS-016 MI-15） | owner | 開 `/owner/orders/{order}`，點 `owner-order-detail-btn-modify` → `order-modify-type-CHANGE`、勾 `order-modify-visit-checkbox-{V3}`、原因「行程縮短」、送出 | 按鈕可見（E2 `allowedActions` 含 `REQUEST_MODIFICATION`）；導向新變更的協商頁 | E17 201：`status=REQUESTED`；E18 共 2 列，依序 `REQUESTED`、`EXPIRED` | 截圖 + API JSON |

### 2.4 爭議分支：待退款時飼主申報款項爭議 → DISPUTED 凍結 → ADMIN 調解（T3）

前置：建 `order`；`PROPOSE(減少 V3)`（1000、差額 −500）→ `ACCEPT` → 變更 `AWAITING_REFUND`、退款 payment R（500）`UNPAID`、V3 `CANCELLED`。保母不上傳退款憑證。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| J4-27 | PRD-024 AC-1.1、BR-1、BR-2；PRD-023 AC-3.2（TS-024 PD-01、TS-023 SR-03） | owner | 開 `/owner/orders/{order}`，在退款 `payment-block-{R}` 點 `payment-refund-dispute-link` | 退款區塊沒有 `btn-verify-payment`；導向 `/orders/{order}/modifications/{mod}`，`modification-btn-payment-dispute` 可見；點擊後出現 `payment-dispute-input-description`、`payment-dispute-btn-submit`，對話框內 `input[type=file]` 數量 0 | E19（OWNER）`status=AWAITING_REFUND`、`allowedActions` 含 `PAYMENT_DISPUTE`；E2 `allowedActions` 含 `PAYMENT_DISPUTE` | 截圖 + API JSON |
| J4-28 | PRD-024 AC-1.1、BR-13（TS-024 PD-02） | owner | `payment-dispute-input-description` 填「保母遲未退款」，點 `payment-dispute-btn-submit`；再開 `/owner/orders/{order}` | 訂單詳情顯示 `owner-order-detail-disputed-banner`「訂單爭議處理中，請等候平台處理」；`owner-order-detail-btn-modify` 數量 0、退款區塊無確認按鈕（PRD-009 §4.4 `DISPUTED` 列） | 頁面發出的 E24 恰一次，200：`status=DISPUTED`、`adminTaskId` 有值；E2 `status=DISPUTED`；E19 仍 `AWAITING_REFUND`；E10 R 仍 `UNPAID` | 截圖 + API JSON |
| J4-29 | PRD-024 AC-1.1、BR-4（TS-024 PD-03） | admin、sitter | `INSPECT`；admin 呼叫 E28 `GET /api/admin/tasks?type=MODIFICATION_PAYMENT_DISPUTE&status=OPEN` 並開 `/admin/tasks`；sitter `GET /api/notifications` | `/admin/tasks` 有 `admin-task-item-{adminTaskId}` | `INSPECT.adminTasks` 恰 1 筆 `type=MODIFICATION_PAYMENT_DISPUTE`、`status=OPEN`、`taskId=adminTaskId`；E28 含該筆、`orderId`=order、`paymentId`=R；`INSPECT.orderLogs` 新增 1 筆 `PAYMENT_DISPUTE_OPENED`（`operatorRole=OWNER`、`payload.description=保母遲未退款`、`payload.modificationId=mod`、`payload.paymentId`=R、`payload.adminTaskId`）；`INSPECT.notifications` 本步只新增 1 筆 `PAYMENT_DISPUTE_OPENED` → SITTER，沒有 `recipientRole=ADMIN` 的列；sitter 通知列表文案「訂單已進入爭議處理」 | 截圖 + API JSON |
| J4-30 | PRD-024 AC-2.1、AC-1.4、BR-5、BR-16（TS-024 PF-01、PD-12） | sitter、owner | 記下 E19、E10、E12（R）為快照；保母開協商頁；以 API：sitter E11 上傳 R（有效欄位）、sitter E22 撤回本變更（保母為發起者）、owner E17（新增 D+8）、owner E24（新 key、有效說明） | 保母頁沒有 `proof-submit-btn`、`modification-btn-withdraw`、`modification-btn-payment-dispute`，顯示「訂單爭議處理中，請等候平台處理」；保母訂單詳情顯示 `sitter-order-detail-disputed-banner` | 四次皆 409 `ORDER_STATE_CONFLICT`、`payload.currentStatus=DISPUTED`（E17 不回 `MODIFICATION_IN_PROGRESS`）；E19、E10、E12 與快照相同；`INSPECT.adminTasks` 仍 1 筆 `OPEN` | 截圖 + API JSON |
| J4-31 | PRD-024 AC-2.5、BR-10（TS-024 PF-09） | admin | admin 呼叫 E27 `GET /api/admin/orders/{order}`；開 `/admin/orders/{order}` | 頁面顯示變更與付款資訊，沒有可編輯變更或付款金額的輸入元件 | E27 200：`status=DISPUTED`、`modifications` 含 mod（`AWAITING_REFUND`）、`payments` 含 R（`UNPAID`）、`adminTasks` 含該待辦 `OPEN`、`logs` 含 `PAYMENT_DISPUTE_OPENED`；`allowedActions` 含 `MEDIATE` | 截圖 + API JSON |
| J4-32 | PRD-024 AC-2.3、AC-2.5、BR-6、§4.5（TS-024 PF-04、PF-11） | admin | 記下 E27 `quote.totalAmount` 為 T0；admin 呼叫 E29 `POST /api/admin/orders/{order}/mediation`（body `{}`；R 為 `UNPAID`，不需 `refundDecisions`）；之後 admin 再呼叫 E27 | — | 200：`status=CANCELLED`（V1、V2 `PENDING`、無 `DONE` / `CLOSED_BY_SYSTEM` → O17）、`adjustmentPaymentIds=[]`；以 owner token 查：E19 `REJECTED`、`endedAt` 在 `[t0, t1]`；E10 R `VOID`、`voidedAt` 有值、`INITIAL` 仍 `VERIFIED`、無 `ADMIN_ADJUSTMENT`；F1 V1、V2、V3 皆 `CANCELLED`（剩餘 `PENDING` 隨取消附帶動作取消）；E2 `quote.totalAmount=T0`（= `INSPECT.totalAmount`，ADMIN 不改總額）、`netPaid=1500`、`cancellation.cancelledBy=ADMIN`；調解後訂單已非 `DISPUTED` 且待辦已 `RESOLVED`，admin E27 → 403 `FORBIDDEN`（可讀範圍結束，SD-ORDER §3.5 `AdminOrderAccessPolicy`）；`INSPECT.adminTasks` 該待辦 `status=RESOLVED`、`resolution=ADMIN_MEDIATED`、`resolvedAt` 在 `[t0, t1]`；E28 `OPEN` 不再含此單；`INSPECT.orderLogs` 新增 1 筆 `ORDER_MEDIATED`（`operatorRole=ADMIN`、`payload.outcome=CANCELLED`、`payload.rejectedModificationIds` 含 mod、`payload.resolvedAdminTaskIds` 含該待辦）；`INSPECT.notifications` 新增 `ORDER_MEDIATED` 2 筆（雙方，範本待 PRD-020，只斷言 `type`） | API JSON |
| J4-33 | PRD-024 AC-2.3（調解後雙方畫面）；PRD-016 AC-1.4（終態不可發起）（TS-024 PF-04、TS-016 MI-10） | owner | 開 `/owner/orders/{order}`；owner 以 API 呼叫 E17（新增 D+8） | 狀態徽章 `order-status-badge`「已取消」；`owner-order-detail-btn-modify`、`owner-order-detail-disputed-banner` 數量 0 | E17 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`；E18 仍 1 列 | 截圖 + API JSON |

## 3. 權限與例外

> 越權一律用另一個真實帳號打 API；每列註明執行時機（插在哪一步之後）。完整的越權矩陣在模組 TS，本旅程只驗主線每個交接點上「錯的人、錯的方向」被擋。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| J4-E01 | PRD-023 EX-1、AC-1.7（TS-023 SE-01） | J4-06 時（`QUOTED`）：`ownerB` 呼叫 E23、E21 | 皆 403 `FORBIDDEN`；E19 仍 `QUOTED`；E10 無 `SUPPLEMENT` |
| J4-E02 | PRD-023 EX-2、AC-1.7（TS-023 SE-02） | J4-06 時：`sitter`（SITTER token）呼叫 E23；`admin` 呼叫 E23 | 皆 403 `ROLE_MISMATCH`；E19 仍 `QUOTED` |
| J4-E03 | PRD-007 AC-1.3、AC-2.5（TS-007 P-12、PV-20） | J4-11 確認前（補款 `SUBMITTED`）：`sitter` 對補款呼叫 E11（有效欄位）；`owner` 對補款呼叫 E14（`submissionId` = 目前那筆） | 皆 403 `FORBIDDEN`（付款 / 收款方向不符）；補款仍 `SUBMITTED`、submission 仍 1 筆 |
| J4-E04 | PRD-007 AC-1.3、AC-2.4、AC-2.5（TS-007 P-14、PV-16、PV-24） | J4-17 時（退款 `UNPAID`）：`owner` 對退款呼叫 E11；J4-19 之後（退款 `SUBMITTED`）：`sitter` 對退款呼叫 E14、`owner` 對退款呼叫 E15（`reason=金額不對`） | 皆 403 `FORBIDDEN`；退款狀態不變 |
| J4-E05 | PRD-023 EX-14、AC-3.2（TS-023 SR-03） | J4-17 時（退款 `UNPAID`）：`owner` 呼叫 E14（`submissionId` = 執行期 `crypto.randomUUID()`） | 409 `PAYMENT_STATE_CONFLICT`；E19 仍 `AWAITING_REFUND` |
| J4-E06 | PRD-016 EX-4、AC-3.2（TS-016 MW-03） | J4-15 時（`initiatedBy=SITTER` 的 `QUOTED`）：`owner` 呼叫 E22 | 403 `FORBIDDEN`；仍 `QUOTED` |
| J4-E07 | PRD-016 EX-8、AC-2.6、BR-8（TS-016 MQ-19） | J4-06 時（`initiatedBy=OWNER` 的 `QUOTED`）：`sitter` 呼叫 E21（`reason=不想做`）、再呼叫 E20（不帶加減） | 皆 409 `MODIFICATION_STATE_CONFLICT`；仍 `QUOTED`、`newTotalAmount=2300` 不變 |
| J4-E08 | PRD-024 EX-1、EX-2、AC-1.5（TS-024 PE-01、PE-02） | J4-27 時（`AWAITING_REFUND`、尚未申報）：`ownerB` 呼叫 E24；`admin` 呼叫 E24 | `ownerB` 403 `FORBIDDEN`；`admin` 403 `ROLE_MISMATCH`；`INSPECT.adminTasks` 為空、E2 仍 `CONFIRMED` |
| J4-E09 | PRD-016 EX-7、AC-1.5（TS-016 MI-14） | J4-09 時（`AWAITING_SUPPLEMENT`）：`owner` 呼叫 E17（`CHANGE` 新增 D+8）；`sitter` 呼叫 E17（減少 V3） | 皆 409 `MODIFICATION_IN_PROGRESS`；E18 仍 1 列 |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| PRD-016 AC-1.1 / AC-2.1、PRD-023 AC-1.2（`expires_at` / `due_at` 精確值 `2026-11-24T02:00:00Z`） | 正式站無法固定時鐘；本旅程以 `[t0+72h, t1+72h]` 夾值 | `ModificationInitiateIntegrationTest`、`ModificationSettlementIntegrationTest`（注入 `Clock`） |
| PRD-016 AC-3.3（正式排程於到期後 ≤ 15 分鐘內執行） | 頻率屬 Cloud Scheduler 設定，且自動排程跳過 `@e2e-journey.test` 訂單；本旅程以 `C5?orderId` 直接觸發 | 手動驗證 Cloud Scheduler job `modifications/expiry` 每 15 分鐘（SD-ORDER §8.3）；`ModificationCronIntegrationTest` |
| PRD-023 AC-2.4–AC-2.8（補款逾時重算、系統退款變更） | 主線補款在期限內完成；補款逾時分支已由模組旅程走真後端 | TS-023 SS-04~SS-11（C6 `?orderId`）；`SupplementExpiryIntegrationTest` |
| PRD-023 AC-1.5、PRD-016 AC-3.6、PRD-024 AC-1.6 / AC-1.7（跨角色併發的兩種先後） | 旅程不做 `Promise.all` 競爭；指定先後需控制交錯 | TS-016 MW-17、TS-023 SA-09 / SA-10、TS-024 PD-13 / PD-15（恰一方成功）；`ModificationConcurrencyIntegrationTest`、`PaymentDisputeIntegrationTest`（`CountDownLatch`） |
| 鎖等待逾時 `ORDER_BUSY`（PRD-016 EX-14、PRD-023 EX-10、PRD-024 EX-9） | 正式站無法持鎖 3 秒 | `OrderLockServiceTest`、`ModificationConcurrencyIntegrationTest` |
| GCS 上傳失敗（PRD-023 EX-16） | 正式站無法注入 GCS 故障 | `PaymentSubmissionConcurrencyIntegrationTest`、TS-007 |
| Resend 寄信失敗與真實 Email 內容（PRD-016 EX-17、PRD-023 EX-17、PRD-024 EX-12） | Resend 帳號為測試模式，只能寄給專案擁有者信箱，無法注入失敗；本旅程只斷言站內通知與 `INSPECT.notifications.emailStatus` 欄位存在 | 後端整合測試（Resend mock）；真實寄信手動驗證：以專案擁有者信箱建立一組非 `@e2e-journey.test` 帳號走一次 J4-02 / J4-07，人工確認收信 |
| PRD-024 AC-2.4（`SUBMITTED` 退款「已匯出 / 未匯出」與補款作廢） | 本旅程爭議發生在退款 `UNPAID`；調解畫面屬 PRD-020（待依 baseline 改寫） | TS-024 PF-05~PF-08；`MediationIntegrationTest` |
| 各 PRD 的欄位格式、上限、方案區間、方案等級計價、冪等 422 / 處理中 | 屬單一端點規則，不是跨模組串接 | TS-016 MI-06、MI-18、MQ-03~MQ-28、MW-18~MW-20；TS-023 SA-15；TS-024 PD-16 |

## 5. 已知限制 / 待補

1. **協商頁入口未定 testid**（未解決，J4-04 依賴）：SD-ORDER §9.1 / §9.3 只定義「申請變更 / 提出變更」精靈入口（`owner-order-detail-btn-modify` / `sitter-order-detail-btn-modify`），沒有定義從訂單詳情進入**進行中變更**協商頁的入口（E2 已回 `openModification`）；`modification-history-item-{modificationId}` 是否可點進協商頁也未定。舊版旅程發現過「三個協商頁沒有真實入口」的缺陷，本旅程 J4-04 刻意要求從訂單詳情點入、不得直接輸入網址。建議預設值：SD-ORDER §9.3 補 `owner-order-detail-btn-open-modification` / `sitter-order-detail-btn-open-modification`（E2 `openModification` 非 null 時顯示，連到 `/orders/{orderId}/modifications/{modificationId}`），歷史列點擊同樣導向該頁。補定前由 e2e-author 以業務名稱對應。
2. **時間敏感**（未解決）：D+n 由執行當下 Asia/Taipei 日期計算，避免在 23:55–00:05 執行（spec 偵測到時 `test.skip` 並註記）。
3. **依賴未重寫的 PRD-020**（未解決）：J4-32 的 E29 契約與副作用取自 SD-ORDER（Approved）與 PRD-024 §4.5；`ORDER_MEDIATED` 通知範本與調解畫面待 PRD-020 改寫，屆時同步修訂。
4. **訂單總額的畫面呈現**（待釐清，本旅程不斷言畫面總額）：E2 `quote` 的 `surchargeAmount` / `discountAmount` 是 O2 報價值，`quote.totalAmount` 卻是變更後的目前總額；`owner-order-detail-quote-breakdown`（PRD-006）在變更 `SETTLED` 後若以 base + 加價 − 折扣呈現，會與目前總額不一致（本旅程 J4-12 後為 1500 + 0 − 0 ≠ 2300）。目前總額只以 E2 / `INSPECT` 斷言。

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依新範本全文重寫：主線改為「增加行程 + 加價 → 補款 → SETTLED」並接續「保母減少行程 → 退款 → SETTLED」，新增逾時分支（shift-time + C5 `orderId`）與款項爭議分支（E24 → `DISPUTED` 凍結 → E29 調解）；斷言改用 E2 / E10 / E19 / F1 與 `inspect`（`order_logs`、通知、`totalAmount`、待辦、檢核項）；每步標 PRD AC 與 TS-016 / 023 / 024 / 007 / 008 / 028 對應步驟。舊版 `MODIFYING` / `REFUND_VERIFY` / `previous_status` 流程、舊端點與舊頁面全部作廢 | PRD-016 / 023 / 024、SD-ORDER、SD-FULFILL Approved 2026-11-22；baseline §3.4（CQ-1） |
