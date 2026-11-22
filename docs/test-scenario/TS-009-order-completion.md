# TS-009: 訂單結案、服務爭議回報與帳務總覽

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 對應 PRD | PRD-009（Approved 2026-11-22，含核准後回寫）；前置資料另用到 PRD-008（行程）、PRD-016 / PRD-023（變更）、PRD-020（ADMIN 待辦與調解 E28 / E29）、PRD-022（日誌） |
| 對應 SD | SD-FULFILL §1.4、§9.2（畫面與 testid）、§6.3 FT1 / FT2、§10.2；SD-ORDER E2（`serverTime`、`completionHint`、`disputeWindowEndsAt`）、E3、E25、E26、E27、E28、E29、E30（`serverMonth`）、C8（`?orderId=`）、§1.9（後端測試類別）、§7.1 / §7.2、§9.3、§10.4（`inspect`、provision、setup） |
| 類型 | 旅程（打真後端、正式站） |
| 自動化路徑 | `frontend/e2e/journeys/order-completion.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD 與 SD 撰寫，未參考程式碼。PRD-009 的狀態轉移、權限、冪等、跨角色互動、時間平移都要真後端，因此整份為旅程型。
> 正式站無法注入時鐘：所有時間條件以「相對 E2 `serverTime`」的位移造出（SD-ORDER `shift-time COMPLETION_BASIS_AT`、SD-FULFILL FT1 / FT2），不斷言 AC 範例的絕對時刻；精確邊界列 §4 由後端整合測試覆蓋。
> 排程一律帶 `?orderId=` 只處理自己的測試訂單，計數只算該筆（SD-ORDER §6.3 C1–C10），因此斷言精確計數；Cloud Scheduler 自動觸發會跳過 `@e2e-journey.test` 訂單，不會搶先推進測試資料。
> `order_logs`、通知（含 Email 投遞狀態）、`admin_tasks`、payments、`completionBasisAt` 一律以 SD-ORDER §10.4 `inspect` 斷言（下稱 `INSPECT`）。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 自己 provision 帳號與訂單、可獨立重跑；行程 id 一律由 SD-FULFILL F1 `GET /api/orders/{orderId}/visits` 取得（依 `serviceDate` 排序，下稱 V1、V2…）。

### 1.1 帳號

> 全部以 SD-ORDER §10.4 `provision-account`（`@e2e-journey.test`、`displayName`、`roles[]`）建立；`owner-setup` / `sitter-setup` 為 upsert，回應以 `key` 取得 `petId` / `planId`。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `ownerA` | OWNER | `roles=[OWNER]`、`displayName="飼主A"`；`owner-setup`：寵物 1 隻（`key=p1`）、`emailPreferences.ORDER_AFFAIR = 開` | — |
| `ownerB` | OWNER | `roles=[OWNER]`；寵物 1 隻；**不建任何訂單**（越權與「尚無歷史訂單」用） | — |
| `ownerC` | OWNER | `roles=[OWNER]`；寵物 1 隻 | 25 筆歷史訂單（§1.3 H25） |
| `ownerH` | OWNER | `roles=[OWNER]`；寵物 1 隻 | 7 種狀態各一筆（§1.3 H 組） |
| `sitterS` | SITTER | `roles=[SITTER]`；`sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄、`plans=[{ key: "feed", 標題 "到府餵食", 單價 500 }]`、`emailPreferences.ORDER_AFFAIR = 開` | — |
| `sitterT` | SITTER | 同 `sitterS`，但 `emailPreferences.ORDER_AFFAIR = 關` | — |
| `sitterH`、`sitterU`、`sitterL` | SITTER | 同 `sitterS`（分別給 H 組、H25、帳務段，不互相共用） | — |
| `sitterF` | SITTER | 同 `sitterS` 但 `tier=FREE` | — |
| `admin` | ADMIN | `roles=[ADMIN]` | — |

### 1.2 造單與工具程序（皆經 API；e2e-author 可封裝成 helper）

| 程序 | 步驟 | 結果 |
|------|------|------|
| `MK-CONFIRMED(owner, sitter, n)` | owner E1 `POST /api/orders`（方案 `feed`、n 個未來日期、每日 1 次）→ sitter E6 `POST /api/orders/{orderId}/quote` 原價 → owner E10 `GET /api/orders/{orderId}/payments` 取 INITIAL `paymentId` → owner E11 `POST /api/orders/{orderId}/payments/{paymentId}/submissions`（憑證圖檔 fixture、後五碼、免責勾選、`Idempotency-Key`）→ sitter E14 `.../verify`（`submissionId`） | 訂單 `CONFIRMED`、已收淨額 = 500 × n、n 個 `PENDING` 行程 |
| `MK-INPROGRESS(...)` | `MK-CONFIRMED` 後 FT1 `POST /api/internal/test-data/visits/{V1}/force-status`（`status=IN_PROGRESS`） | 訂單 `IN_PROGRESS`（O9） |
| `DONE+LOG(Vx)` | FT1 `status=DONE`、`careLogSubmitted=true`（可帶 `completedAt`；允許 `PENDING → DONE`） | 行程 `DONE`、日誌 `SUBMITTED` |
| `DONE+DRAFT(Vx)` | FT1 `status=DONE` → sitter SD-FULFILL F14 `PUT …/care-log/draft`（`text="草稿未送出"`） | 行程 `DONE`、日誌 `DRAFT`、編輯期內 |
| `CLOSED(Vx)` | FT1 `status=CLOSED_BY_SYSTEM`（可帶 `closedAt`；允許 `PENDING → CLOSED_BY_SYSTEM`） | 行程 `CLOSED_BY_SYSTEM` |
| `SHIFT-WINDOW(order, Δ)` | 讀 E2 `serverTime` 與 `disputeWindowEndsAt`，以 SD-ORDER `POST /api/internal/test-data/orders/{orderId}/shift-time`（`target=COMPLETION_BASIS_AT`、`minutes`）位移，使 `disputeWindowEndsAt = serverTime + Δ`；**必須在該訂單最後一次 FT1 / FT2 之後呼叫**（FT1 / FT2 會重算基準） | 視窗終點落在指定相對時刻 |
| `RUN-C8(order)` | `POST /api/internal/cron/orders/auto-complete?orderId={order}`，標頭 `X-Internal-Secret` | 200 `{ job, processed, skipped, failed, startedAt, finishedAt }`，計數只算該訂單 |
| `INSPECT(order)` | `GET /api/internal/test-data/orders/{order}/inspect`（`X-Internal-Secret`） | `{ status, totalAmount, completionBasisAt, orderLogs[], notifications[], adminTasks[], payments[], fulfill }` |

### 1.3 各段訂單

| 代號 | owner / sitter | 建立 | 用於 |
|------|---------------|------|------|
| `X1` | ownerA / sitterS | `MK-INPROGRESS`，n=2 | AC-1.2 |
| `X2` | ownerA / sitterS | `MK-INPROGRESS`，n=3 | AC-1.1、1.3、1.4、1.6、1.7、2.6（顯示）、2.7 |
| `X3` | ownerA / sitterT | `MK-INPROGRESS`，n=2；`DONE+LOG(V1)`、`DONE+LOG(V2)` | AC-1.1（Email 關）、1.5、1.6 |
| `Z1` | ownerA / sitterS | `MK-INPROGRESS`，n=2；`DONE+LOG(V1)`、`CLOSED(V2)` | AC-2.1、2.4、1.6（C8 先） |
| `Z2` | ownerA / sitterS | 同 `Z1` | AC-2.6（視窗已過） |
| `Y1` | ownerA / sitterS | `MK-INPROGRESS`，n=2；見 OC-36 | AC-2.2 |
| `Y2` | ownerA / sitterS | `MK-INPROGRESS`，n=2；`DONE+DRAFT(V1)`、`DONE+LOG(V2)` | AC-2.5、2.8（冪等）、2.3 (a) |
| `Y3`、`Y4` | ownerA / sitterS | `MK-INPROGRESS`，n=2；`DONE+LOG(V1)`、`DONE+LOG(V2)` | AC-2.8（驗證、越權、字數） |
| `Y5` | ownerA / sitterS | `MK-INPROGRESS`，n=3；`DONE+LOG(V1)`，V2、V3 維持 `PENDING` | AC-2.7 |
| `Y6` | ownerA / sitterS | `MK-INPROGRESS`，n=3；`DONE+LOG(V1)`、`DONE+LOG(V2)`，V3 `PENDING` | AC-1.3（`AWAITING_REFUND`）、2.3 (b) |
| `L1` | ownerA / sitterL | `MK-INPROGRESS`，n=2；`DONE+LOG` ×2 → ownerA E25 | AC-3.1、3.8 |
| `L2` | ownerA / sitterL | E1 → E6 → ownerA E8 `withdraw`（`expectedStatus=PENDING_PAYMENT`） | AC-3.1（淨額 0 的 `CANCELLED` 不列） |
| `L3` | ownerA / sitterL | `MK-INPROGRESS`，n=2 | AC-3.3（非終態不列） |
| `L4` | ownerA / sitterL | `MK-INPROGRESS`，n=2；`DONE+LOG` ×2 → ownerA E26 | AC-3.3、3.8（O15 全額調解淨額 0） |
| `F1` | ownerA / sitterF | `MK-INPROGRESS`，n=1；`DONE+LOG(V1)` → ownerA E25 | AC-3.5 |
| H 組 | ownerH / sitterH | 依序建立：`H-PEND`（E1）、`H-PP`（E1 → E6）、`H-CONF`（`MK-CONFIRMED` n=2）、`H-INP`（`MK-INPROGRESS` n=2、`DONE+LOG(V1)`，V2 `PENDING`）、`H-DISP`（n=2、`DONE+LOG` ×2 → E26）、`H-COMP`（n=2、`DONE+LOG` ×2 → E25）、**最後**建立 `H-CANC`（E1 → E8 `expectedStatus=PENDING`），使 `H-CANC.cancelledAt` 晚於 `H-COMP.completedAt` | AC-1.4、1.8、3.6、3.7 |
| H25 | ownerC / sitterU | 25 次 E1 → E8（`expectedStatus=PENDING`），記錄取消順序 | AC-3.6（載入更多） |

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4 訂單段與跨模組範圍：帳號、角色、方案、毛孩、通知與通知偏好、訂閱）。

---

## 2. 測試步驟

> 步驟 ID 前綴 `OC-`；e2e spec 的 `step()` / `apiStep()` 標籤以同一 ID 開頭。
> 通知斷言以 `INSPECT.notifications`（`type`、`recipientUserId`、`recipientRole`、`mandatory`、`emailStatus`）為準；「有 Email」= `emailStatus` 為待寄 / 已寄，「無 Email」= `emailStatus` 表示不寄（列舉值見 §5 #1）。
> 畫面時間斷言：把 API 回傳的 ISO-8601 時間換算 Asia/Taipei `YYYY-MM-DD HH:mm` 後比對畫面文字。金額依 SD-FRONTEND-SPEC §6.4.1：`$` + U+00A0 + 千分位（例 `$ 1,000`）。
> 「空白」依 SD-GLOBAL-SPEC §2.7 `strip()`（含全形空白）。
> 每個 E25 / E26 呼叫皆帶新的 `Idempotency-Key`（UUID），除非該列指定沿用某把 key。

### 2.1 飼主確認結案（O11）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| OC-01 | AC-1.2 | ownerA、sitterS | `X1`（V1 `IN_PROGRESS`、V2 `PENDING`）。ownerA 開 `/owner/orders/{X1}`；sitterS 開 `/sitter/orders/{X1}` | 飼主：無 `owner-order-detail-btn-complete`；`owner-order-detail-complete-hint` 文字「所有行程完成且保母送出照護日誌後，即可確認結案」；無 `owner-order-detail-btn-report-issue`、無 `owner-order-detail-dispute-window-ends-at`。保母：無 `sitter-order-detail-auto-complete-at` | ownerA E2 → `status=IN_PROGRESS`、`completionHint.code=VISITS_NOT_FINISHED`、無 `disputeWindowEndsAt`、`allowedActions` 不含 `COMPLETE` / `REPORT_SERVICE_ISSUE` | 截圖 ×2 + API JSON |
| OC-02 | AC-1.2 | ownerA | 直接呼叫 E25 `POST /api/orders/{X1}/complete` | — | 409 `VISITS_NOT_FINISHED`；E2 仍 `IN_PROGRESS`、無 `completedAt`；`INSPECT(X1).orderLogs` 無 `ORDER_COMPLETED` | API JSON |
| OC-03 | AC-1.2；AC-2.5（行程條件不看日誌） | ownerA | `DONE+DRAFT(V1)`（`completedAt` = now）、`DONE+LOG(V2)` 後重新整理 `/owner/orders/{X1}` | 無確認結案按鈕；`owner-order-detail-complete-hint` 仍為「所有行程完成且保母送出照護日誌後，即可確認結案」；`owner-order-detail-btn-report-issue` 可見；`owner-order-detail-dispute-window-ends-at`「可回報問題至 {disputeWindowEndsAt}」；`owner-order-detail-visit-row-{V1}` 內無 `owner-order-detail-btn-view-log-{V1}`，`{V2}` 有 | E2 → `completionHint.code=CARE_LOG_PENDING`、`disputeWindowEndsAt` = `INSPECT.completionBasisAt` + 48h、`visits[V1].careLogSubmitted=false`、`visits[V2]=true`；ownerA F1 → `careLog.submitted` 同上 | 截圖 + API JSON |
| OC-04 | AC-1.2 | ownerA | E25 | — | 409 `VISITS_NOT_FINISHED`；E2 仍 `IN_PROGRESS` | API JSON |
| OC-05 | AC-1.3；AC-2.7 | sitterS、ownerA | `X2`（V1 `IN_PROGRESS`、V2 / V3 `PENDING`）。sitterS E17 `POST /api/orders/{X2}/modifications`（`type=CHANGE`、`removeVisitIds=[V3]`、`surchargeAmount=500`、`adjustmentReason="維持原總額"`、`reason="少一次到府"`）；ownerA 開 `/owner/orders/{X2}` | 無確認結案、無回報問題按鈕；`owner-order-detail-complete-hint`「訂單變更處理中，完成後才能確認結案」；無 `owner-order-detail-dispute-window-ends-at` | E17 → 201 `status=QUOTED`、`diff=0`；E2 → `completionHint.code=MODIFICATION_IN_PROGRESS`（V1 仍 `IN_PROGRESS`，提示不是 `VISITS_NOT_FINISHED`）、無 `disputeWindowEndsAt` | 截圖 + API JSON |
| OC-06 | AC-1.3；EX-6 | ownerA | E25 | — | 409 `MODIFICATION_IN_PROGRESS`（不是 `VISITS_NOT_FINISHED`） | API JSON |
| OC-07 | AC-2.7；EX-6 | ownerA | E26 `POST /api/orders/{X2}/service-disputes`（`description="測試"`） | — | 409 `MODIFICATION_IN_PROGRESS`；`INSPECT(X2).adminTasks=[]`；E2 仍 `IN_PROGRESS` | API JSON |
| OC-08 | AC-2.6（`SETTLED` 推遲基準）；BR-4；BR-21 | ownerA、sitterS | ownerA E23 `.../modifications/{id}/accept`；`DONE+LOG(V1)`（`completedAt` = now − 25h）；`CLOSED(V2)`（`closedAt` = now − 2h）；雙方開詳情 | 飼主 `owner-order-detail-dispute-window-ends-at` 與保母 `sitter-order-detail-auto-complete-at` 顯示同一時間 = {E18 該變更 `endedAt` + 48h} | E23 → `status=SETTLED`；`INSPECT(X2).completionBasisAt` = E18 該筆 `endedAt`（晚於 V1 結束時間 now − 1h 與 V2 關閉時間）；E2 `disputeWindowEndsAt = completionBasisAt + 48h`；F1 V3 `status=CANCELLED` | 截圖 ×2 + API JSON |
| OC-09 | AC-1.1 | ownerA | 重新整理 `/owner/orders/{X2}` | `owner-order-detail-btn-complete` 可見；無 `owner-order-detail-complete-hint` | E2 → 無 `completionHint`、`allowedActions` 含 `COMPLETE` 與 `REPORT_SERVICE_ISSUE` | 截圖 + API JSON |
| OC-10 | AC-1.1；BR-18 | ownerA | 點 `owner-order-detail-btn-complete` → 確認框 → 點 `owner-order-complete-btn-confirm` | 確認框文字「確認結案後將無法再回報服務問題，確定要結案嗎？」；送出後顯示「已結案」、`owner-order-detail-completed-at` = {completedAt} | 頁面恰發出 1 次 E25，帶 `Idempotency-Key`（記為 K1）、無 body；回應 200 `{ orderId: X2, status: "COMPLETED", completedAt }`；E2 `status=COMPLETED`、`completedAt` 同回應；`INSPECT(X2).orderLogs` 恰 1 筆 `actionType=ORDER_COMPLETED`、`operatorRole=OWNER`、`payload.completedBy=OWNER` | 截圖 ×2 + API JSON |
| OC-11 | AC-1.1；BR-20 | — | `INSPECT(X2).notifications` | — | 恰 1 筆 `type=ORDER_COMPLETED_BY_OWNER`、`recipientUserId=sitterS`、`recipientRole=SITTER`、`mandatory=false`、有 Email（sitterS 偏好開）；無收件者為 ownerA 或 ADMIN 的結案通知；無 `ORDER_AUTO_COMPLETED` | API JSON |
| OC-12 | AC-1.6；EX-10 | ownerA | 以 K1 重送 E25 | — | 200，body 與 OC-10 完全相同；E2 `completedAt` 不變；`INSPECT(X2)` 的 `ORDER_COMPLETED` 仍 1 筆、`ORDER_COMPLETED_BY_OWNER` 仍 1 筆 | API JSON |
| OC-13 | AC-1.4；EX-5；EX-11；BR-8 | ownerA | 新 key E25；ownerA E17（`type=CHANGE`、`reason="結案後變更"`）；新 key E26（`description="結案後回報"`） | — | 三者皆 409 `ORDER_STATE_CONFLICT`、payload `currentStatus=COMPLETED`；E2 `completedAt` 與 OC-10 相同；`INSPECT(X2)` 的 `orderLogs` 與 `notifications` 筆數與 OC-12 後相同 | API JSON |
| OC-14 | AC-1.7 | ownerA | 開 `/owner/orders/{X2}`，點 `owner-order-detail-btn-view-log-{V1}` | 「已結案」；`owner-order-detail-completed-at` = {completedAt}；`owner-order-detail-visit-row-{V1}` 可見；開啟後 `owner-care-log-view` 可見；`owner-order-detail-btn-complete`、`owner-order-detail-btn-report-issue`、`owner-order-detail-btn-modify`、付款元件（`proof-submit-btn`）皆不存在 | — | 截圖 ×2 |
| OC-15 | AC-1.7 | sitterS | 開 `/sitter/orders/{X2}` | 「已結案」；`sitter-order-detail-completed-at` 與 OC-14 同文字；無 `sitter-order-detail-btn-modify`、`sitter-order-detail-btn-cancel` 等任何操作按鈕；無 `sitter-order-detail-auto-complete-at` | — | 截圖 |
| OC-16 | AC-1.7；BR-9 | ownerA | `/owner/orders` 點 `owner-orders-tab-history`，再點 `owner-orders-tab-active` | 歷史分頁有 `owner-order-card-{X2}`；進行中分頁無 `owner-order-card-{X2}` | E3 `tab=HISTORY` 含 `X2`；`tab=ACTIVE` 不含 | 截圖 ×2 + API JSON |
| OC-17 | AC-1.5；EX-1 | ownerB | 對 `X3` 呼叫 E25 | — | 403 `FORBIDDEN`；E2 `X3` 仍 `IN_PROGRESS` | API JSON |
| OC-18 | AC-1.5；EX-2 | sitterT、admin | sitterT（`currentRole=SITTER`）與 admin 各呼叫 E25 `X3` | — | 皆 403 `ROLE_MISMATCH` | API JSON |
| OC-19 | AC-1.5；EX-3 | ownerA | 以執行時產生的隨機 UUID 呼叫 E25 | — | 404 `NOT_FOUND` | API JSON |
| OC-20 | AC-1.5；BR-17 | ownerA、匿名 | ownerA 不帶 `Idempotency-Key` 呼叫 E25 `X3`；不帶 token 呼叫 | — | 400 `VALIDATION_FAILED`、payload `field=Idempotency-Key`；401 `UNAUTHENTICATED`；E2 `X3` 仍 `IN_PROGRESS`；`INSPECT(X3).orderLogs` 無 `ORDER_COMPLETED` | API JSON |
| OC-21 | AC-1.6 | ownerA | 以 K1（`X2` 用過）呼叫 E25 `X3` | — | 422 `IDEMPOTENCY_KEY_REUSED`；E2 `X3` 仍 `IN_PROGRESS` | API JSON |
| OC-22 | AC-1.1（Email 關）；EX-20 | ownerA | ownerA 於 UI 完成 `X3` 確認結案（同 OC-10 操作）；`INSPECT(X3)` | 「已結案」 | E25 200；`notifications` 恰 1 筆 `ORDER_COMPLETED_BY_OWNER`、`recipientUserId=sitterT`、無 Email（`emailStatus` 為不寄） | 截圖 + API JSON |
| OC-23 | AC-1.3 | sitterS、ownerA | `Y6`：sitterS E17（`CHANGE`、`removeVisitIds=[V3]`、無加減、`reason="少一次"`）→ ownerA E23；雙方開詳情 | 飼主：無確認結案按鈕；`owner-order-detail-complete-hint`「訂單變更處理中，完成後才能確認結案」；無 `owner-order-detail-dispute-window-ends-at`。保母：無 `sitter-order-detail-auto-complete-at` | E17 → `QUOTED`、`diff=-500`；E23 → `status=AWAITING_REFUND`；E2 `completionHint.code=MODIFICATION_IN_PROGRESS`、無 `disputeWindowEndsAt` | 截圖 ×2 + API JSON |
| OC-24 | AC-1.3；EX-6 | ownerA | E25 `Y6` | — | 409 `MODIFICATION_IN_PROGRESS`；E2 仍 `IN_PROGRESS` | API JSON |

### 2.2 系統自動結案（O12）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| OC-30 | AC-2.1 | system | `Z1`：`SHIFT-WINDOW(Z1, +10 分)` → `RUN-C8(Z1)` | — | C8 200 `processed=0`、`skipped=0`、`failed=0`；E2 `Z1` 仍 `IN_PROGRESS`；`INSPECT(Z1)` 無 `ORDER_COMPLETED`、無 `ORDER_AUTO_COMPLETED` | API JSON |
| OC-31 | BR-21（CQ-15） | ownerA、sitterS | `SHIFT-WINDOW(Z1, −1 分)`，**不跑 C8**，雙方開詳情 | 飼主 `owner-order-detail-dispute-window-ends-at` 與保母 `sitter-order-detail-auto-complete-at` 皆顯示「即將自動結案」；無 `owner-order-detail-btn-report-issue`；`owner-order-detail-btn-complete` 可見 | E2 `status=IN_PROGRESS`、`serverTime ≥ disputeWindowEndsAt`、`allowedActions` 含 `COMPLETE`、不含 `REPORT_SERVICE_ISSUE` | 截圖 ×2 + API JSON |
| OC-32 | AC-2.1；BR-3；BR-20 | system | `RUN-C8(Z1)` | ownerA 重新整理顯示「已結案」 | C8 200 `processed=1`、`skipped=0`、`failed=0`；E2 `Z1` `status=COMPLETED`、`startedAt ≤ completedAt ≤ finishedAt`（C8 回應時間）；`INSPECT(Z1).orderLogs` 恰 1 筆 `ORDER_COMPLETED`、`operatorRole=SYSTEM`、`payload.completedBy=SYSTEM`；`notifications` 恰 2 筆 `ORDER_AUTO_COMPLETED`（收件者 ownerA / OWNER 與 sitterS / SITTER 各 1，皆 `mandatory=false`、有 Email），無 ADMIN 收件者、無 `ORDER_COMPLETED_BY_OWNER` | 截圖 + API JSON |
| OC-33 | AC-1.6（C8 先提交的結果）；EX-12 | ownerA | 新 key E25 `Z1` | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=COMPLETED` | API JSON |
| OC-34 | AC-2.4；EX-15 | system | 再 `RUN-C8(Z1)` | — | C8 200 `processed=0`；`Z1` `completedAt` 與 OC-32 相同；`INSPECT(Z1)` 的 `ORDER_COMPLETED` 仍 1 筆、`ORDER_AUTO_COMPLETED` 仍 2 筆 | API JSON |
| OC-35 | AC-2.4 | ownerA、匿名 | 以 ownerA token（不帶 `X-Internal-Secret`）呼叫 C8；不帶任何標頭呼叫 C8 | — | 皆 403 `FORBIDDEN` | API JSON |
| OC-36 | AC-2.2；BR-26 | ownerA | `Y1`：FT1 V1 `DONE`（`completedAt` = now − 23h）→ sitterS F14 V1 `text="草稿未送出"`（200）→ FT2 `POST /api/internal/test-data/visits/{V1}/shift-time`（`target=COMPLETED_AT`、`minutes=-120`）→ `DONE+LOG(V2)`（`completedAt` = now）；ownerA 開 `/owner/orders/{Y1}` | 無確認結案按鈕；`owner-order-detail-complete-hint`「保母未於期限內送出照護日誌，訂單將於 {autoCompleteAt} 自動結案」；`owner-order-detail-btn-report-issue` 可見；`owner-order-detail-dispute-window-ends-at` 時間與提示相同 | E2 `completionHint.code=CARE_LOG_EXPIRED`、`completionHint.autoCompleteAt = disputeWindowEndsAt`；`INSPECT(Y1).completionBasisAt` = V2 `completedAt` + 24h；`fulfill.careLogs` V1 `status=DRAFT`；sitterS F13 V1 `expired=true` | 截圖 + API JSON |
| OC-37 | AC-2.2；BR-21 | sitterS | 開 `/sitter/orders/{Y1}` | `sitter-order-detail-auto-complete-at` 顯示與 OC-36 相同時間 | — | 截圖 |
| OC-38 | AC-2.2；EX-7 | ownerA | E25 `Y1` | — | 409 `VISITS_NOT_FINISHED` | API JSON |
| OC-39 | AC-2.2；BR-21（iii 空窗） | ownerA、system | `SHIFT-WINDOW(Y1, −1 分)` → ownerA 重新整理 → `RUN-C8(Y1)` | C8 前：`owner-order-detail-complete-hint` 改為「即將自動結案」；C8 後：「已結案」 | C8 `processed=1`；E2 `status=COMPLETED`；`INSPECT(Y1).fulfill.careLogs` V1 仍 `DRAFT`（自動結案不看日誌） | 截圖 ×2 + API JSON |

### 2.3 飼主回報服務問題（O13）

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| OC-40 | AC-2.5；BR-6 | ownerA | `Y2`（V1 日誌草稿、V2 已送出，視窗內）。開 `/owner/orders/{Y2}`，點 `owner-order-detail-btn-report-issue`，於 `owner-order-dispute-input-description` 輸入「保母未依約餵藥」，點 `owner-order-dispute-btn-submit` | 表單只有說明欄：無問題類別選項、表單內無 `input[type=file]`；輸入後 `owner-order-dispute-description-counter` 顯示剩餘 493 | 頁面恰發出 1 次 E26，帶 `Idempotency-Key`（記為 K2），body 只有 `description` 一個 key；200 `{ orderId: Y2, status: "DISPUTED", adminTaskId }` | 截圖 ×2 + API JSON |
| OC-41 | AC-2.5；§4.4 | ownerA、sitterS | 雙方開詳情 | 飼主 `owner-order-detail-disputed-banner`「訂單爭議處理中，請等候平台處理」；無確認結案、回報問題、`owner-order-detail-btn-modify`、付款元件；`owner-order-detail-visit-row-{V1}`、`{V2}` 與 `owner-order-detail-btn-view-log-{V2}` 照常。保母 `sitter-order-detail-disputed-banner` 同文字 | E2 `status=DISPUTED`、`allowedActions` 不含 `COMPLETE` / `REPORT_SERVICE_ISSUE` / `REQUEST_MODIFICATION` | 截圖 ×2 + API JSON |
| OC-42 | AC-2.5；BR-6 | admin | E28 `GET /api/admin/tasks?type=SERVICE_DISPUTE`；`INSPECT(Y2)` | — | E28 含一筆 `taskId=adminTaskId`、`type=SERVICE_DISPUTE`、`orderId=Y2`、`status=OPEN`；`INSPECT.adminTasks` 恰 1 筆同值；`orderLogs` 恰 1 筆 `SERVICE_DISPUTE_OPENED`、`operatorRole=OWNER`、`payload.description="保母未依約餵藥"`、`payload.adminTaskId=adminTaskId` | API JSON |
| OC-43 | AC-2.5；BR-20 | — | `INSPECT(Y2).notifications` | — | 恰 1 筆 `SERVICE_DISPUTE_OPENED`、收件者 sitterS / SITTER、有 Email；無 ownerA、無 ADMIN 收件者 | API JSON |
| OC-44 | AC-2.8；EX-10 | ownerA | 以 K2、相同說明重送 E26 | — | 200，body 與 OC-40 相同；`INSPECT(Y2)` 的 `adminTasks`（`OPEN`）、`SERVICE_DISPUTE_OPENED` log、通知皆仍 1 筆 | API JSON |
| OC-45 | AC-2.8；BR-17 | ownerA | 以 K2、說明「改說明」送 E26 | — | 422 `IDEMPOTENCY_KEY_REUSED` | API JSON |
| OC-46 | AC-2.8；AC-1.4；EX-5；EX-11 | ownerA | 新 key E26（`description="再回報"`）；新 key E25 | — | 皆 409 `ORDER_STATE_CONFLICT`、`currentStatus=DISPUTED` | API JSON |
| OC-47 | AC-2.8；EX-19 | ownerA | 新 key E26，`description="   "` | — | 400 `VALIDATION_FAILED`、`field=description`（不是 409） | API JSON |
| OC-48 | AC-2.5；AC-2.3 (a)；BR-7 | ownerA、system | ownerA E17（`CHANGE`、`reason="爭議中變更"`）；`SHIFT-WINDOW(Y2, −60 分)` → `RUN-C8(Y2)` | — | E17 409 `ORDER_STATE_CONFLICT`（`currentStatus=DISPUTED`）；C8 `processed=0`；E2 `Y2` 仍 `DISPUTED`；`INSPECT(Y2)` 無 `ORDER_COMPLETED`、無 `ORDER_AUTO_COMPLETED` | API JSON |
| OC-49 | AC-2.8；EX-9 | ownerA | `Y3`：E26 分別帶 `description=""`、`"   "`、`"　　"`（全形空白）、`" " + "字"×501 + " "` | — | 四者皆 400 `VALIDATION_FAILED`、`field=description`；`INSPECT(Y3).adminTasks=[]`；E2 仍 `IN_PROGRESS` | API JSON |
| OC-50 | AC-2.8；EX-1；EX-2 | ownerB、sitterS、admin | 各對 `Y3` 呼叫 E26（`description="越權"`） | — | ownerB 403 `FORBIDDEN`；sitterS、admin 403 `ROLE_MISMATCH`；E2 仍 `IN_PROGRESS` | API JSON |
| OC-51 | AC-2.8；BR-6 | ownerA | `Y3` 開回報表單：(1) 只輸入「　 」（全形 + 半形空白）；(2) 清空後輸入「🐱」×500；(3) 再輸入 1 個「🐱」；(4) 點送出 | (1) `owner-order-dispute-description-counter` 顯示剩餘 500、`owner-order-dispute-btn-submit` 停用；(2) 剩餘 0、送出可按；(3) 輸入框仍為 500 個「🐱」（code point 截斷、不切斷代理對）、剩餘 0；回報畫面無附件元件 | 送出時 E26 body `description` 為 500 code point；200 `status=DISPUTED` | 截圖 ×3 + API JSON |
| OC-52 | AC-2.8；BR-6（CQ-17） | ownerA | `Y4`：E26 `description = "  " + "字"×500 + "  "` | — | 200 `DISPUTED`；`INSPECT(Y4)` 的 `SERVICE_DISPUTE_OPENED.payload.description` 為 strip 後的 500 字（無前後空白） | API JSON |
| OC-53 | AC-2.6；EX-8 | ownerA | `Z2`：`SHIFT-WINDOW(Z2, −1 分)`；新 key E26（`description="逾期回報"`）；開詳情 | 無 `owner-order-detail-btn-report-issue`；`owner-order-detail-btn-complete` 可見 | E26 409 `DEADLINE_PASSED`、`deadline=DISPUTE_WINDOW_ENDS_AT`、`deadlineAt` = 位移後的 E2 `disputeWindowEndsAt` = `INSPECT.completionBasisAt` + 48h；E2 仍 `IN_PROGRESS`；`INSPECT(Z2).adminTasks=[]` | 截圖 + API JSON |
| OC-54 | AC-2.6 | ownerA | 於 UI 對 `Z2` 確認結案（同 OC-10） | 「已結案」 | E25 200 `COMPLETED`（自動觸發跳過測試訂單，不會被排程搶先） | 截圖 + API JSON |
| OC-55 | AC-2.7；EX-6 | ownerA | `Y5`：ownerA E17（`CHANGE`、`removeVisitIds=[V3]`、`reason="少一次"`）；開詳情；E26 | 無 `owner-order-detail-btn-report-issue` | E17 → 201 `REQUESTED`；E26 409 `MODIFICATION_IN_PROGRESS`（同時有 `PENDING` 行程 V2）；`INSPECT(Y5).adminTasks=[]` | 截圖 + API JSON |
| OC-56 | AC-2.7；EX-7 | ownerA | E22 撤回該變更；FT1 V2 `IN_PROGRESS`；開詳情；E26；E25 | 無回報問題、無確認結案按鈕；`owner-order-detail-complete-hint`「所有行程完成且保母送出照護日誌後，即可確認結案」 | E22 → `WITHDRAWN`；E26 409 `VISITS_NOT_FINISHED`；E25 409 `VISITS_NOT_FINISHED`；`INSPECT(Y5).adminTasks=[]` | 截圖 + API JSON |
| OC-57 | AC-2.3 (b) | system | `Y6`（`AWAITING_REFUND`）：`SHIFT-WINDOW(Y6, −60 分)` → `RUN-C8(Y6)` | — | C8 `processed=0`；E2 `Y6` 仍 `IN_PROGRESS`；`INSPECT(Y6)` 無 `ORDER_COMPLETED`、無 `ORDER_AUTO_COMPLETED` | API JSON |

### 2.4 保母帳務總覽

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| OC-60 | AC-3.1；AC-3.3；AC-3.4；BR-15；BR-22 | sitterL | `L1`（`COMPLETED`）、`L2`（`CANCELLED` 未付款）、`L3`（`IN_PROGRESS` 已收 1000）、`L4`（`DISPUTED` 已收 1000）。開 `/sitter/ledger` | `sitter-ledger-select-month` 預設 = E30 `serverMonth`；`sitter-ledger-total-income`「$ 1,000」；只有 `sitter-ledger-item-{L1}`，無 L2 / L3 / L4；該列顯示訂單編號 = `L1` 前 8 碼大寫、飼主稱呼「飼主A」、服務期間 = 首末行程日、「已結案」、終態日期 = {L1 `completedAt` 的 Asia/Taipei 日期}、「$ 1,000」 | E30 `GET /api/orders/sitter/ledger?month={serverMonth}` → `totalIncome=1000`、`orders` 恰 1 筆 `{ orderId: L1, status: COMPLETED, ownerDisplayName: "飼主A", netPaid: 1000, terminalAt = L1 completedAt, firstServiceDate, lastServiceDate }`；`INSPECT(L2).payments` INITIAL `status=VOID` | 截圖 + API JSON |
| OC-61 | AC-3.8 | sitterL | 點 `L1` 列的訂單編號 | 導向 `/sitter/orders/{L1}` | — | 截圖 |
| OC-62 | AC-3.8；AC-3.3；BR-10 | admin、sitterL | admin E29 `POST /api/admin/orders/{L4}/mediation`（`adjustment.amount=1000`、`adjustment.basis="全額退款"`）；sitterL 重新整理帳務 | 列表依序 `sitter-ledger-item-{L4}`、`{L1}`；L4「已結案」「$ 0」；總收入仍「$ 1,000」；無分頁元件 | E29 → `status=COMPLETED`、`adjustmentPaymentIds` 1 筆；`INSPECT(L4)`：`adminTasks` 該 `SERVICE_DISPUTE` `status=RESOLVED`、`resolution` 有值、`resolvedAt` 有值；`payments` 含該 `adjustmentPaymentId`、`purpose=ADMIN_ADJUSTMENT`、`status=VERIFIED`、`verifiedAt` 有值；E30 `orders[0].orderId=L4`、`netPaid=0`；`totalIncome=1000` | 截圖 + API JSON |
| OC-63 | AC-3.4；BR-22 | sitterL | 開 `/sitter/orders`，點 `sitter-orders-tab-history` | 不顯示總收入（無 `sitter-ledger-total-income`、無「總收入」文字）；有 `sitter-order-card-{L1}` | — | 截圖 |
| OC-64 | AC-3.4 | sitterL | `sitter-ledger-select-month` 選 {serverMonth 往前 3 個月}（帳號建立前） | `sitter-ledger-total-income`「$ 0」；`sitter-ledger-empty`「本月沒有已結束的訂單」 | E30 該月 → `totalIncome=0`、`orders=[]` | 截圖 + API JSON |
| OC-65 | AC-3.4；BR-16 | sitterL | 以 Playwright `timezoneId=UTC` 的 context 開 `/sitter/ledger` | `sitter-ledger-select-month` 的值 = E30 `serverMonth` | 頁面若先以瀏覽器月份查詢且與 `serverMonth` 不同，必須再以 `serverMonth` 查一次（SD-FULFILL §9.2） | 截圖 + API JSON |
| OC-66 | AC-3.4；EX-18 | sitterL | E30 `month=2026-13`；E30 不帶 `month` | — | 皆 400 `VALIDATION_FAILED`、`field=month` | API JSON |
| OC-67 | AC-3.5；EX-2 | sitterL、ownerA、admin | sitterL E30 `serverMonth`；ownerA（`currentRole=OWNER`）與 admin 呼叫 E30 | — | sitterL 的 `orders[].orderId` 只含 L1、L4（不含 `X2`、`Z1` 等 sitterS 訂單）；ownerA、admin 403 `ROLE_MISMATCH` | API JSON |
| OC-68 | AC-3.5；EX-4；BR-14 | sitterF | `F1` 已結案。依序在三種狀態下開 `/sitter/ledger`：(1) FREE；(2) `sitter-setup` upsert `subscriptionExpiresAt` = serverTime − 2 天（鎖定）；(3) `sitter-setup` upsert `suspended=true` | 三次皆顯示 `sitter-ledger-item-{F1}`、同一組欄位、「$ 500」 | 三次 E30 皆 200、`orders` 含 `F1`、`netPaid=500` | 截圖 ×3 + API JSON |

### 2.5 飼主歷史訂單與行程清單

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| OC-70 | AC-3.6；BR-9 | ownerH | `/owner/orders` 依序點 `owner-orders-tab-evaluating`、`owner-orders-tab-active`、`owner-orders-tab-history` | 評估中：`owner-order-card-{H-PEND}`、`{H-PP}`；進行中：`{H-CONF}`、`{H-INP}`、`{H-DISP}`；歷史：恰 2 張 `{H-CANC}`、`{H-COMP}`，無 `owner-orders-history-btn-load-more`；三個分頁都沒有 ownerC 的訂單 | E3 `tab=EVALUATING` / `ACTIVE` / `HISTORY` 的 `orderId` 集合與畫面一致 | 截圖 ×3 + API JSON |
| OC-71 | AC-3.7；BR-23 | ownerH | 歷史分頁（預設狀態全部、不篩月份） | 卡片順序 `H-CANC`、`H-COMP`；每張顯示保母名稱、服務期間（首末行程日）、狀態（已取消 / 已結案）、終態日期、實付淨額（`H-COMP`「$ 1,000」、`H-CANC`「$ 0」） | E3 `tab=HISTORY` → `content[0].orderId=H-CANC`、`terminalAt` 遞減；`H-COMP.netPaid=1000`、`H-CANC.netPaid=0` | 截圖 + API JSON |
| OC-72 | AC-3.7 | ownerH | `owner-orders-history-filter-status` 選「已結案」 | 只剩 `owner-order-card-{H-COMP}` | 請求帶 `status=COMPLETED`，回應只有 `H-COMP` | 截圖 + API JSON |
| OC-73 | AC-3.7 | ownerH | 狀態選「全部」、`owner-orders-history-select-month` 選當月；再改狀態「已取消」、月份選上個月 | 當月：`H-CANC` 在 `H-COMP` 之前；上個月 + 已取消：無卡片，`owner-orders-history-empty`「沒有符合條件的訂單」 | 請求分別帶 `month={當月}`、`status=CANCELLED&month={上月}` | 截圖 ×2 + API JSON |
| OC-74 | AC-3.7 | ownerB | 開歷史分頁 | `owner-orders-history-empty`「尚無歷史訂單」 | E3 `tab=HISTORY` → `content=[]` | 截圖 |
| OC-75 | AC-3.6；BR-23 | ownerC | 開歷史分頁 → 點 `owner-orders-history-btn-load-more` | 首批 20 張，第一張為最後取消的那筆，`owner-orders-history-btn-load-more` 可見；點擊後共 25 張、`orderId` 無重複、按鈕消失 | E3 `tab=HISTORY&page=0&size=20` → 20 筆；`page=1` → 5 筆 | 截圖 ×2 + API JSON |
| OC-76 | AC-1.8；BR-24 | ownerH | 逐一開 `H-CONF`、`H-INP`、`H-DISP`、`H-COMP`、`H-PEND`、`H-PP`、`H-CANC` 的 `/owner/orders/{id}` | `H-CONF`：行程列 ×2，無查看日誌；`H-INP`：行程列 ×2，只有 V1 有 `owner-order-detail-btn-view-log-{V1}`；`H-DISP`、`H-COMP`：行程列 ×2 且皆有查看日誌；`H-PEND`、`H-PP`、`H-CANC`：無任何 `owner-order-detail-visit-row-*` | — | 截圖 ×7 |
| OC-77 | AC-1.4；AC-2.7；EX-5 | ownerH | E25 對 `H-CONF`、`H-CANC`、`H-DISP`；E26 對 `H-CONF`、`H-COMP` | — | 皆 409 `ORDER_STATE_CONFLICT`，`currentStatus` 依序 `CONFIRMED`、`CANCELLED`、`DISPUTED`、`CONFIRMED`、`COMPLETED` | API JSON |

---

## 3. 權限與例外

> PRD-009 每個 EX 的對應位置；越權一律以另一個真實帳號對正式站打 API（依 `_TEMPLATE.md`）。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| OC-17、OC-50 | EX-1 | ownerB 對他人訂單 E25 / E26 | 403 `FORBIDDEN`，訂單不變 |
| OC-18、OC-50、OC-67 | EX-2 | SITTER / ADMIN 呼叫 E25 / E26；OWNER / ADMIN 呼叫 E30 | 403 `ROLE_MISMATCH` |
| OC-19 | EX-3 | 不存在的訂單 | 404 `NOT_FOUND` |
| OC-68 | EX-4 | FREE / 鎖定 / 停權保母查帳務 | 200，與 PRO 相同（O11 / O13 不分方案由後端測試覆蓋，§4） |
| OC-13、OC-46、OC-77 | EX-5 | 非 `IN_PROGRESS` 結案 / 回報 | 409 `ORDER_STATE_CONFLICT` + `currentStatus`；`completedAt` 不變 |
| OC-06、OC-07、OC-24、OC-55 | EX-6 | 有未結束變更 | 409 `MODIFICATION_IN_PROGRESS`（與行程未結束同時成立仍回本碼） |
| OC-02、OC-04、OC-38、OC-56 | EX-7 | 行程未結束 / 日誌未送出（含逾期） | 409 `VISITS_NOT_FINISHED` |
| OC-53 | EX-8 | 視窗已過、排程未結案 | 409 `DEADLINE_PASSED`、`deadline=DISPUTE_WINDOW_ENDS_AT`、`deadlineAt`；之後仍可結案（OC-54） |
| OC-49、OC-47 | EX-9 | 說明空白（含全形）/ 超長 | 400 `VALIDATION_FAILED`、`field=description`，不建待辦 |
| OC-12、OC-44 | EX-10 | 同 key 同內容重送 | 回放，不重複轉移 / 待辦 / log / 通知 |
| OC-13、OC-46 | EX-11 | 不同 key 重送 | 409 `ORDER_STATE_CONFLICT` |
| OC-33 | EX-12 | C8 先提交後 O11 | 409 `ORDER_STATE_CONFLICT`；真正併發見 §4 |
| — | EX-13 | 鎖逾時 / `@Version` | §4 |
| — | EX-14 | 排程單筆失敗 | §4 |
| OC-34、OC-53 | EX-15 | 排程延遲 / 重跑 | 重跑 `processed=0`、不重複結案與通知；空窗期 O13 依時間拒絕、O11 可用 |
| — | EX-16 | Resend 失敗 | §4 |
| OC-40 | EX-17 | GCS | 不適用（結案與回報不上傳檔案；OC-40 斷言表單無附件元件） |
| OC-66 | EX-18 | `month` 缺漏 / 格式錯 | 400 `VALIDATION_FAILED`、`field=month` |
| OC-47 | EX-19 | 多錯誤同時成立 | 說明空白且已 `DISPUTED` → 400 |
| OC-22 | EX-20 | 收件人關閉 Email | 站內通知照寫、`emailStatus` 為不寄 |
| — | EX-21 | O13 與 C8 在視窗終點併發 | §4 |

---

## 4. 不在 E2E 覆蓋的項目

> 後端測試類別依 SD-ORDER §1.9（PRD-009 的 E25 / E26 / C8 / E30 / E3 / E2 提示由 SD-ORDER 承擔）；行程端資料依 SD-FULFILL `OrderVisitListIntegrationTest`。

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.1（`completed_at` 等於注入時鐘 `2026-11-23T02:00:00Z`） | 正式站無法注入時鐘；E2E 以 OC-10 回應值與 E2 一致驗證 | `CompletionIntegrationTest` |
| AC-1.1 真實 Email 內容 | Resend 測試模式只寄專案擁有者；E2E 只驗 `emailStatus`（OC-11、OC-22） | 手動驗證 |
| AC-1.6（E25 與 C8 真正同時處理、只有勝出方通知；鎖占用 > 3 秒 → 409 `ORDER_BUSY` + `Retry-After: 1`，同 key 重試 200） | 正式站無法穩定造出併發與持鎖 | `CompletionIntegrationTest`、`CompletionBasisIntegrationTest`；E2E 只驗兩種先後的結果（OC-10、OC-33） |
| AC-1.5（判斷順序 G1–G6 的組合） | E2E 已逐一驗單一錯誤（OC-17~OC-20），組合順序由後端驗 | `CompletionIntegrationTest`、`OrderIdentitySecurityTest` |
| AC-2.1（時鐘 `01:59` / `02:00` 精確邊界） | 正式站時鐘不可注入；E2E 以 +10 分 / −1 分近似（OC-30、OC-32） | `CompletionBasisIntegrationTest` |
| AC-2.3 (b) 後半（變更 `SETTLED` 後基準改為結清時間、`08:59` / `09:00` 邊界）、(c)（行程仍 `IN_PROGRESS` 被略過） | 退款結清須走完 PRD-023 退款流程且需精確時鐘；(c) 的基準在行程未結束時不成立，E2E 無法造出「時間已過但行程未結束」；`SETTLED` 推遲基準本身由 OC-08 驗 | `CompletionBasisIntegrationTest` |
| AC-2.4（處理 X 時發生例外 → `processed=1`、`failed=1`，下一輪補處理） | 正式站無法注入單筆失敗 | `CompletionBasisIntegrationTest`；非排程身分 403 另由 `InternalCronSecurityTest` 與 OC-35 覆蓋 |
| AC-2.6（`06:59` / `07:00` 精確邊界；O13 與 C8 同時處理的三種順序；EX-21） | 需注入時鐘與併發 | `CompletionIntegrationTest`、`CompletionBasisIntegrationTest`；E2E 以 OC-40（視窗內成功）、OC-53（視窗後拒絕）、OC-48（`DISPUTED` 不被結案）驗結果 |
| AC-3.1 計算例 A（變更退款）、B（`CONFIRMED` 整筆取消留取消費）、C（部分 `ADMIN_ADJUSTMENT`） | A / B 需走完 PRD-023 退款結清流程；C 與 OC-62 的全額調解同一機制 | `SitterLedgerIntegrationTest` |
| AC-3.2（月份歸屬邊界 11-30 23:59 / 12-01 00:00、付款日與行程日不影響歸屬） | 正式站無法把終態時間寫到指定月份 | `SitterLedgerIntegrationTest` |
| AC-3.3 後半（H 於下個月經 O15 結案列入 12 月、11 月不變） | 需跨月 | `SitterLedgerIntegrationTest`；E2E 只驗非終態不列（OC-60） |
| AC-3.4（台北 12-01 00:30 / UTC 11-30 16:30 跨月瞬間的預設月份） | 需注入時鐘 | `SitterLedgerIntegrationTest`（`serverMonth`）；E2E OC-65 驗「瀏覽器 UTC 時仍以 `serverMonth` 為準」 |
| AC-3.7（10 月與 11 月跨月資料的月份篩選） | 終態時間無法寫到過去月份 | `OrderHistoryIntegrationTest`；E2E 以當月 / 上月驗篩選與空狀態（OC-73） |
| AC-3.8 計算例 J（補款逾時 `VOID`、系統退款）、L（補款 `VERIFIED`）；訂單編號以固定 UUID `3f9a2c1e-…` 驗大寫 | J 需補款逾時 + 退款結清；L 需補款流程；E2E 不寫死 UUID，改以執行時 `orderId` 驗前 8 碼大寫（OC-60、OC-61） | `SitterLedgerIntegrationTest` |
| EX-13 | 持鎖 > 3 秒與 `@Version` 衝突無法在正式站造出 | `CompletionIntegrationTest` |
| EX-14 | 單筆失敗注入 | `CompletionBasisIntegrationTest` |
| EX-16（Resend 失敗，轉移照常、Email 重試 5 次） | 正式站不能讓 Resend 故障 | SD-NOTIFY 寄送測試；手動驗證 |
| EX-21 | 併發 + 精確時鐘 | `CompletionIntegrationTest`、`CompletionBasisIntegrationTest` |
| AC-1.2 / AC-1.8 / AC-2.2 的行程端欄位（`careLog.submitted`、`deadlineAt`）細項 | E2E 只讀結果，欄位語意由後端驗 | SD-FULFILL `OrderVisitListIntegrationTest`；E2 提示由 `OrderDetailCompletionHintIntegrationTest` |

---

## 5. 已知限制 / 待補

| # | 項目 | 影響 | 建議 |
|---|------|------|------|
| 1 | `INSPECT.notifications[].emailStatus` 的列舉值（待寄 / 已寄 / 不寄 / 失敗）未在 SD-ORDER §10.4 或 SD-NOTIFY 定義 | OC-11、OC-22、OC-32、OC-43 只能以「有 Email / 不寄」語意描述 | SD-NOTIFY 重建時定義列舉值，e2e-author 依實際值對應 |
| — | 已解決（2026-11-22）：ADMIN 帳號（`provision-account roles[]`）；保母 Email 偏好（`sitter-setup.emailPreferences`）；排程搶先（自動觸發跳過測試訂單、`?orderId=`）；ADMIN 無通知（`INSPECT.notifications`）；金額格式（SD-FRONTEND-SPEC §6.4.1）；回報說明計數器與截斷（PRD-009 §4.3、`owner-order-dispute-description-counter`）；FT1 / FT2 允許的轉換與訂單狀態（SD-FULFILL §6.3）；訂單列表分頁 testid（PRD-009 §9 / SD-ORDER §9.3） | — | — |

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依 Approved PRD-009、SD-FULFILL、SD-ORDER 全文重寫：旅程型、步驟 ID `OC-`；舊版 TS-009-01~12 的場景（殭屍清理、`payout_at`、`/resolve`、零行程）已不在 PRD-009 範圍而刪除；舊版「本測試發現並修復的真實 bug」屬舊實作（`OwnerOrderDetail.tsx`、`CompletionService`），重建後不適用，歷史見 git | 全面重建 2026-11 |
| 2026-11-22 | 依 commit 477c75d 核准後修訂更新：`order_logs` / 通知 / `emailStatus` / `admin_tasks` / payments / `completionBasisAt` 改以 `INSPECT` 斷言（OC-02、03、07、08、10~13、20、22、30、32、34、36、39、42~44、48、49、52、53、55~57、60、62）；C8 一律 `?orderId=` 並斷言精確計數、刪除排程搶先因應（OC-30~34、39、48、54、57）；帳號改用 `roles[]` / `displayName` / upsert / `sitter-setup.emailPreferences`（§1.1）；FT1 直接 `PENDING → DONE / CLOSED_BY_SYSTEM`（§1.2）；新 testid（`owner-orders-tab-evaluating` / `-active`、`sitter-orders-tab-history`、`sitter-order-card-*`、`owner-order-detail-btn-modify`、`sitter-order-detail-btn-modify` / `-btn-cancel`、`owner-order-dispute-description-counter`）；計數器剩餘字數與 code point 截斷（OC-40、OC-51）；全形空白（OC-49、OC-51）；金額改 `$ 1,000` 格式（OC-60、62、64、68、71）；§4 移回 E2E 的項目刪除；§5 只留 `emailStatus` 列舉 | 主流程交辦（核准後修訂） |
| 2026-11-22 | 使用者核准：狀態 Draft → Approved | 使用者 2026-11-22 |
