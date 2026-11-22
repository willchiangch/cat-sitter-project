# TS-028: 行程單日 SOP 檢核項

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-028（Approved 2026-11-22，含 §4.3 / §9 / EX-8 / BR-14 核准後回寫）；行程狀態與服務面板依 PRD-008；前置資料另經 PRD-005 / 006 / 007（建單、報價、付款）、PRD-016 / 023 / 024（變更、款項爭議）、PRD-009（O11 結案） |
| 對應 SD | SD-FULFILL（F1、F2、F3、F4、F8–F12、FC1、FT1、FT2、§2.3.3 / §10.2 inspect 的 `fulfill.checklistItems`、§3.4 寫入窗口、§4.8 帶入、§6.5 錯誤碼、§9.1 testid 與文案）；SD-ORDER（E1、E2、E6、E8、E11、E14、E17、E20、E23–E25、§10.4 provision-account / sitter-setup（`plans[].defaultTasks`）/ owner-setup / inspect / cleanup） |
| 類型 | 旅程（打真後端，正式站） |
| 自動化路徑 | `frontend/e2e/journeys/visit-checklist.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 |

> TS 只依 PRD-028、PRD-008、SD-FULFILL、SD-ORDER、DOMAIN-BASELINE 撰寫，未參考程式碼。
> 類型：寫入窗口依訂單 / 行程狀態、越權、冪等、與排程的先後都要真後端 → 旅程型。初始帶入發生在行程建立交易內，只能經 E1 真建單驗證。

---

## 1. 前置資料

> 不寫死 UUID：所有 id 取自建立回應（`sitter-setup` / `owner-setup` 以請求 `key` 回傳 `planId` / `petId`）；「不存在的 id」於執行時以 `crypto.randomUUID()` 產生。每個 `test()` 自建帳號與訂單、可獨立重跑。

### 1.1 記號

沿用 TS-008 §1.1：`D0` / `D+n`（以 E2 `serverTime` 換算的 Asia/Taipei 日期）、`[t0, t1]`、`TPE(x)`、`新 key`、`[INS]`（inspect）、`[NOTI]`。另：

| 記號 | 意義 |
|------|------|
| `I1`、`I2`、`I3` | 某行程依 `sortOrder` 排序的第 1、2、3 個檢核項 id（取自 F8） |
| `標題(v)` | F8（`visitId=v`）回應 `items[].title` 依 `sortOrder` 的陣列 |
| `[INS] 項目` | inspect `fulfill.checklistItems` 中該項：`title`、`sortOrder`、`isCompleted`、`checkedAt`、`createdSource`、`createdBy`、`updatedBy`、`updatedAt` |

### 1.2 端點代號（路徑取自 SD-FULFILL §6.2；`…` = `/api/orders/{orderId}/visits/{visitId}`）

| 代號 | 端點 |
|------|------|
| F1 | `GET /api/orders/{orderId}/visits`（`visits[].checklist`） |
| F2 | `GET …`（服務面板） |
| F3 / F4 | `POST …/start`、`POST …/complete` |
| F8 | `GET …/checklist` |
| F9 | `POST …/checklist/items`（body `title`；必帶 `Idempotency-Key`） |
| F10 | `DELETE …/checklist/items/{itemId}` |
| F11 | `PUT …/checklist/order`（body `itemIds`） |
| F12 | `PUT …/checklist/items/{itemId}/check`（body `checked` 布林） |
| FC1 | `POST /api/internal/cron/visits/in-progress-timeout?orderId={orderId}` |
| FT1 / FT2 / inspect | 同 TS-008 §1.2（FT1 支援 `CANCELLED`） |
| E1 / E6 / E8 / E11 / E14 / E17 / E20 / E23 / E24 / E25 | 同 TS-008 §1.2（E8 撤回帶 `expectedStatus`） |

### 1.3 帳號

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `sitter` | SITTER | `provision-account`（`@e2e-journey.test`，`roles=[SITTER]`）+ `sitter-setup`：`tier=PRO`、`kycStatus=VERIFIED`、`profileVisible=true`、`payoutAccount` 四欄；`plans`：`key=P1`「到府餵食」500（`defaultTasks` = 餵乾糧、換貓砂、清水碗）、`key=P2`「陪玩」300（`defaultTasks` = []）、`key=P3`「全套照護」800（`defaultTasks` = 任務01 … 任務20，共 20 項）、`key=P4`「髒資料測試」400（`defaultTasks` = 22 個元素：第 1 個為 3 個半形空白、第 2 個為 60 個「貓」、其後 B02 … B21）；皆 `active=true`、可預約區間涵蓋 `D0`–`D+30` | 每個 test 新建 |
| `owner` | OWNER | `provision-account`（`roles=[OWNER]`）+ `owner-setup`：`pets` = `key=PET1` 咪咪（CAT） | 每個 test 新建 |
| `sitterB` / `ownerB` | SITTER / OWNER | 同上 | 越權用 |
| `admin` | ADMIN | `provision-account`（`roles=[ADMIN]`） | — |

清除：`cleanup-e2e-journeys`（SD-ORDER §10.4 清單含 `visit_checklist_items`；跨模組含通知、方案、毛孩、帳號）。

### 1.4 造資料程序

沿用 TS-008 §1.4：「建 CONFIRMED 訂單」「建 PENDING_PAYMENT 訂單」「平移日期」「強制狀態」「跨日防護」。建單日期一律 ≥ `D+1`。排程一律帶 `orderId`。

---

## 2. 測試步驟

> 步驟 ID 前綴 `K-`；spec `step()` / `apiStep()` 標籤以同一 ID 開頭。AC 欄格式 `AC-x.y-n` = PRD-028 AC-x.y 情境 n。

### 2.1 行程建立時從方案帶入（訂單 X、X2、X3）

前置：`owner` E1 建訂單 X：G1 = P1 `[D+1, D+2]` × 1（VX1、VX2）、G2 = P2 `[D+1]` × 1（VX3），停在 `PENDING`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| K-01 | AC-1.1-1；BR-2；BR-11 | sitter | F8 查 VX1、VX2、VX3；[INS] 訂單 X | — | `標題(VX1)` = `標題(VX2)` = [餵乾糧, 換貓砂, 清水碗]；`sortOrder` = [0, 1, 2]；全部 `isCompleted=false`、無 `checkedAt`、`createdSource=SYSTEM`；`標題(VX3)` = []；F1 `checklist.total` 依序 3、3、0，`completed` 皆 0；[INS] 這 6 項 `createdBy` 皆為 null（系統帶入，非建單的 OWNER） | API JSON |
| K-02 | AC-1.1-1；BR-11 | system | [INS] 訂單 X | — | `orderLogs` 只有 O1 本身的列，無任何檢核項相關 action；`notifications` 只有 O1 本身的新預約通知類型（PRD-005），無檢核項相關類型 | API JSON |
| K-03 | 前置 | sitter / owner | 完成 E6 → E11 → E14（CONFIRMED）；平移 G1 −1（VX1 = `D0`、VX2 = `D+1`）、G2 −1（VX3 = `D0`） | — | E2 `CONFIRMED`；F8 VX1 仍同 K-01（付款與平移不影響檢核項） | API JSON |
| K-04 | AC-1.1-1；EX-14 | sitter | 開啟 `/sitter/orders/{X}/visits/{VX3}` | `sitter-visit-panel-checklist` 內顯示 `sitter-visit-panel-checklist-empty`「尚無檢核項，可新增照護項目」 | — | 截圖 |
| K-05 | AC-1.1-1 | sitter | 開啟 VX1 面板 | 3 個 `sitter-visit-panel-checklist-item-{itemId}` 依序「餵乾糧」「換貓砂」「清水碗」；無 `sitter-visit-panel-checklist-checked-at-*` | — | 截圖 |
| K-06 | AC-1.1-1；EX-14；BR-13 | owner | 開啟 `/owner/orders/{X}`，展開 VX1、VX3 的行程列 | `owner-order-detail-visit-checklist-{VX1}` 內 3 個 `owner-order-detail-visit-checklist-item-{itemId}` 依序同上，皆 `data-checked=false`；`owner-order-detail-visit-checklist-{VX3}` 顯示「保母未設定檢核項」 | F1（OWNER）`checklist.items` 與 K-01 相同 | 截圖 + API JSON |
| K-07 | AC-1.1-2；BR-2 | sitter | `sitter-setup` upsert `key=P1` 的 `defaultTasks` = 餵乾糧、換貓砂、梳毛（新增「梳毛」、刪除「清水碗」）；F8 查 VX1、VX2 | — | `標題(VX1)` = `標題(VX2)` = [餵乾糧, 換貓砂, 清水碗]（既有行程為快照） | API JSON |
| K-08 | BR-2（之後建立的行程取當下預設）；AC-1.1-3 替代驗證 | owner | E1 建訂單 X2：P1 `[D+3]` × 1；`sitter` F8 查其行程 | — | 標題 = [餵乾糧, 換貓砂, 梳毛]，`createdSource=SYSTEM` | API JSON |
| K-09 | BR-2；CQ-7（帶入超出上限） | owner | E1 建訂單 X3：P4 `[D+1]` × 1；`sitter` F8 查其行程 | — | 建單 201（不擋行程建立）；`items` 恰 20 筆；第 1 筆 `title` = 50 個「貓」；第 2–20 筆依序 B02 … B20；無空白標題；無 B21 | API JSON |

### 2.2 新增、刪除、排序（訂單 E）

前置：建 CONFIRMED 訂單 E：G1 = P1 `[D+1]` × 1（VE1）、G2 = P2 `[D+1]` × 1（VE2）；平移兩群組 −1（皆 `D0`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| K-10 | AC-1.2-1；BR-1；BR-11 | sitter | VE2 面板在 `sitter-visit-panel-checklist-input-new` 輸入「  餵乾糧 50g  」，點 `sitter-visit-panel-checklist-btn-add` | 新增一列「餵乾糧 50g」、未打勾；`-checklist-empty` 消失 | 頁面送出 F9 恰 1 次，帶 `Idempotency-Key`（記為 K1）；201 `title=餵乾糧 50g`、`sortOrder=0`、`isCompleted=false`；[INS] 項目 `createdSource` 非 SYSTEM、`createdBy = updatedBy = sitter.userId` | 截圖 + API JSON |
| K-11 | AC-1.2-1；BR-11；BR-12 | sitter | 同面板新增「換貓砂」 | 兩列依序「餵乾糧 50g」「換貓砂」 | 201 `sortOrder=1`；`標題(VE2)` = [餵乾糧 50g, 換貓砂]，皆 `isCompleted=false`；`標題(VE1)` 仍為 P1 預設 3 項；[INS] K-10 前後 `orderLogs`、`notifications` 皆無新增 | 截圖 + API JSON |
| K-12 | AC-1.2-1；BR-12（不回寫方案） | owner | E1 另建訂單：P2 `[D+2]` × 1；`sitter` F8 查其行程 | — | `items = []`（P2 預設任務未被 K-10、K-11 改動） | API JSON |
| K-13 | AC-1.2-2；BR-10；EX-11 | sitter | 以 K1 與 K-10 相同 body 重送 F9（VE2） | — | 201，body 與 K-10 相同（同一 `itemId`）；`標題(VE2)` 仍 2 項 | API JSON |
| K-14 | EX-11（同 key 不同內容） | sitter | 以 K1、`title=別的項目` 送 F9（VE2） | — | 422 `IDEMPOTENCY_KEY_REUSED`；`標題(VE2)` 仍 2 項 | API JSON |
| K-15 | AC-1.4-5；BR-12 | sitter | 檢視 VE1、VE2 面板 | 頁面無任何含「套用」文字的按鈕或連結 | SD-FULFILL §6.2 無套用端點（不另打 API） | 截圖 |
| K-16 | AC-1.3 前置 | sitter | VE1 面板開始行程；勾選 `sitter-visit-panel-checklist-check-{I1}`（餵乾糧） | 「執行中」；I1 已勾 | F3 200；F8 VE1 I1 `isCompleted=true`、`createdSource=SYSTEM` | 截圖 + API JSON |
| K-17 | AC-1.3-1；BR-1；BR-11 | sitter | 點 `sitter-visit-panel-checklist-btn-delete-{I1}`（已打勾、自方案帶入） | I1 列消失，剩「換貓砂」「清水碗」 | 頁面送出 F10 200，`items` = [I2（`sortOrder=0`）、I3（`sortOrder=1`）]；[INS] I1 不存在；I2、I3 `updatedBy = sitter.userId`（重編序號） | 截圖 + API JSON |
| K-18 | AC-1.3-1；BR-10；EX-4 | sitter | 以 API 再送 F10（I1） | — | 404 `NOT_FOUND`；`標題(VE1)` = [換貓砂, 清水碗] | API JSON |
| K-19 | AC-1.3-2；BR-7；BR-11 | sitter | 拖曳 `sitter-visit-panel-checklist-drag-{I3}` 到 I2 之前放開 | 順序「清水碗」「換貓砂」 | 頁面送出 F11 恰 1 次，body `itemIds = [I3, I2]`；200 `items` 依序 I3、I2；[INS] I3、I2 `updatedAt` 介於 `[t0, t1]`、`updatedBy = sitter.userId` | 截圖 + API JSON |
| K-20 | AC-1.3-2；BR-13 | owner | 開啟 `/owner/orders/{E}` 展開 VE1 | 依序「清水碗」「換貓砂」 | — | 截圖 |
| K-21 | AC-1.3-3；BR-7；EX-5 | sitter | 送 F11（VE1）三次：`[I3]`；`[I3, I2, I2]`；`[I3, VE2 的第 1 項 id]` | — | 皆 400 `VALIDATION_FAILED`、`field=itemIds`；F8 VE1 順序仍 [I3, I2] | API JSON |
| K-22 | AC-1.6-3；EX-4 | sitter | 送 F10，路徑 `visitId=VE2`、`itemId=I2`（屬 VE1） | — | 404 `NOT_FOUND`；F8 VE1 仍含 I2 | API JSON |

### 2.3 可編輯時機、上限與格式（訂單 F、P、C、Wd、G、Lk）

前置：建 CONFIRMED 訂單 F：P1 `[D+1, D+2, D+3]` × 1（VF1–VF3）→ FT1 VF1 `DONE`、FT1 VF2 `CLOSED_BY_SYSTEM`（訂單 → `IN_PROGRESS`）；VF3 維持 `PENDING`（3 項預設）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| K-23 | AC-1.4-2；BR-3；EX-8 | sitter | 對 VF1、VF2 各送 F9（新 key）、F10（I1）、F11（目前全部 id） | — | 全部 409 `VISIT_STATE_CONFLICT`：VF1 `currentVisitStatus=DONE`（附 `completionSource=SITTER`）、VF2 `currentVisitStatus=CLOSED_BY_SYSTEM`；F8 兩行程內容與順序不變 | API JSON |
| K-24 | AC-1.4-2；§4.2-5 | sitter | 開啟 VF1、VF2 面板 | 唯讀清單照列 3 項；無 `-checklist-input-new`、`-checklist-btn-add`、`-checklist-btn-delete-*`、`-checklist-drag-*` | — | 截圖 |
| K-25 | AC-1.5-2；BR-6；EX-5 | sitter | VF3 面板輸入 3 個空白後點新增；再以 API 送 F9（VF3）`title` = 3 個空白、全形空白（U+3000）包住的空字串、去空白後 51 字、去空白後 50 字 | 顯示「請輸入 1–50 字」 | 空白、全形空白、51 字皆 400 `VALIDATION_FAILED`、`field=title`；50 字 201（`title` 長度 50）；F8 VF3 共 4 項 | 截圖 + API JSON |
| K-26 | AC-1.5-3；BR-10；EX-5 | sitter | 送 F9（VF3）不帶 `Idempotency-Key` | — | 400 `VALIDATION_FAILED`、`field=Idempotency-Key`；F8 VF3 仍 4 項 | API JSON |
| K-27 | AC-1.4-2（`CANCELLED`）；EX-8（編輯文案） | sitter | 開啟 VF3 面板並保持不重新整理；FT1 把 VF3 設為 `CANCELLED`；在面板輸入「餵藥」點新增 | 顯示「此行程已結束，無法修改」 | 頁面送出的 F9 回 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=CANCELLED`；F8 VF3 仍 4 項 | 截圖 + API JSON |

前置：建 PENDING_PAYMENT 訂單 P：P1 `[D+1]` × 1（VP）。建 CONFIRMED 訂單 C：P1 `[D+1]` × 1（VC）→ FT1 VC `DONE`（`careLogSubmitted=true`）→ `owner` E25 → `COMPLETED`。`owner` E1 建訂單 Wd：P1 `[D+1]` × 1（VW）→ `owner` E8（`expectedStatus=PENDING`）→ `CANCELLED`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| K-28 | AC-1.4-3；EX-7 | sitter | 送 F9（VP） | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=PENDING_PAYMENT` | API JSON |
| K-29 | EX-7（`COMPLETED`） | sitter | 送 F9（VC） | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=COMPLETED` | API JSON |
| K-30 | EX-7（`CANCELLED`） | sitter | 送 F9（VW） | — | 409 `ORDER_STATE_CONFLICT`、`currentStatus=CANCELLED`（訂單檢查先於行程 `CANCELLED`） | API JSON |

前置：建 CONFIRMED 訂單 G：P3 `[D+1, D+2]` × 1（VG1、VG2，各 20 項）→ 平移 −1（VG1 = `D0`）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| K-31 | AC-1.5-1；BR-6；EX-6 | sitter | VG1 面板輸入「第21項」點新增 | 顯示「每個行程最多 20 項」 | 頁面送出的 F9 回 400 `VALIDATION_FAILED`、`field=items`、`max=20`；F8 VG1 仍 20 項 | 截圖 + API JSON |
| K-32 | AC-1.5-4；BR-8 | sitter | FT1 VG2 `DONE`；送 F9（VG2，新 key） | — | 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=DONE`（第 9 步先於第 12 步上限）；F8 VG2 仍 20 項 | API JSON |

前置：另建一組 `sitter` / `owner`；建 CONFIRMED 訂單 Lk：P1 `[D+1]` × 1（VL）→ 平移 −1 → FT1 VL `IN_PROGRESS`；再以 `sitter-setup` upsert 把該 `sitter` 改為 `tier=FREE`、`subscriptionExpiresAt = now − 1d`。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| K-33 | AC-1.4-4；EX-12 | sitter | VL 面板新增「餵藥」 | 新增成功 | F9 201 | 截圖 + API JSON |

### 2.4 打勾（訂單 K）

前置：建 CONFIRMED 訂單 K：P1 `[D+1, D+2]` × 1（VK1、VK2）→ 平移 −1（VK1 = `D0`、VK2 = `D+1`）。VK1 的 I1、I2、I3 = 餵乾糧、換貓砂、清水碗。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| K-34 | AC-2.2-1；BR-4；EX-7；EX-8 | sitter | 開啟 VK1 面板（訂單 `CONFIRMED`、行程 `PENDING`）；以 API 送 F12（I1，`checked=true`） | 3 個 `sitter-visit-panel-checklist-check-{itemId}` 皆停用，顯示「開始行程後才能打勾」 | 409 `ORDER_STATE_CONFLICT`、`currentStatus=CONFIRMED`（訂單檢查先於行程檢查） | 截圖 + API JSON |
| K-35 | 前置 | sitter | VK1 面板點 `sitter-visit-panel-btn-start` | 「執行中」；勾選框可用 | F3 200；E2 `IN_PROGRESS` | 截圖 |
| K-36 | AC-2.2-2；EX-8 | sitter | 送 F12（VK2 的 I1，`checked=true`） | — | 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=PENDING` | API JSON |
| K-37 | AC-2.1-1；BR-14 | sitter | 點 `sitter-visit-panel-checklist-check-{I1}` | I1 已勾；`sitter-visit-panel-checklist-item-{I1}` 內出現 `sitter-visit-panel-checklist-checked-at-{I1}`，文字 = TPE(checkedAt) | 頁面送出 F12 恰 1 次、不帶 `Idempotency-Key`、body `checked=true`；200 `isCompleted=true`、`checkedAt` 介於 `[t0, t1]`；[INS] 項目 `checkedAt` 同值、`updatedBy = sitter.userId` | 截圖 + API JSON |
| K-38 | AC-2.1-1 | sitter | 再點一次 I1 勾選框 | I1 未勾；`sitter-visit-panel-checklist-checked-at-{I1}` 不存在 | F12 body `checked=false`；200 `isCompleted=false`、無 `checkedAt`；F8 I1 同 | 截圖 + API JSON |
| K-39 | AC-2.1-2；BR-5 | sitter | 只勾 I2；點 `sitter-visit-panel-btn-complete` | `sitter-visit-panel-complete-dialog` 含「尚有 2 項未打勾（不影響結束）」 | F2 `checklist.total=3`、`completed=1` | 截圖 |
| K-40 | AC-2.1-2；BR-5 | sitter | 點 `sitter-visit-panel-btn-complete-confirm` | 「已完工」 | F4 200 `status=DONE` | 截圖 + API JSON |
| K-41 | AC-2.1-3；BR-4；BR-14 | sitter | 檢視 VK1 面板；以 API 送 F12（I1，`checked=true`） | 勾選框皆不可操作；I2 仍顯示已勾且 `sitter-visit-panel-checklist-checked-at-{I2}` 照樣顯示；I1 / I3 未勾 | 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=DONE`、`completionSource=SITTER`；F8 I2 `isCompleted=true`、`checkedAt` 與 K-39 相同 | 截圖 + API JSON |
| K-42 | AC-2.2-3（循序版：保母先） | sitter | 平移 −1（VK2 = `D0`）；VK2 開始行程（F3）；勾選 VK2 的 I1 | VK2 I1 已勾 | F12 200 | 截圖 + API JSON |
| K-43 | AC-2.2-3 前置；BR-9 | sitter / system | 開啟 VK2 面板並保持不重新整理；FT2 VK2 `STARTED_AT`、`minutes=-1450`；呼叫 FC1（`orderId=K`） | — | FC1 計數 1 / 0 / 0；[INS] VK2 `DONE`、`completionSource=SYSTEM`；VK2 I1 仍 `isCompleted=true`（保母先完成的打勾保留，之後行程照常轉 `DONE`） | API JSON |
| K-44 | AC-2.2-3（循序版：系統先）；EX-8；EX-9 | sitter | 在 K-43 未重新整理的面板點 VK2 的 I2 勾選框 | 顯示「此行程已結束，無法修改」 | 頁面送出的 F12 回 409 `VISIT_STATE_CONFLICT`、`currentVisitStatus=DONE`、`completionSource=SYSTEM`；F8 VK2 I2 仍 `isCompleted=false` | 截圖 + API JSON |

### 2.5 飼主唯讀檢視（訂單 V）

前置：建 CONFIRMED 訂單 V：G1 = P1 `[D+1, D+2]` × 1（VV1、VV2）、G2 = P2 `[D+1]` × 1（VV3）→ FT1 VV1 `IN_PROGRESS`（訂單 → `IN_PROGRESS`）→ `sitter` F12 勾選 VV1 的 I1。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| K-45 | AC-2.3；BR-13；BR-15 | owner | 開啟 `/owner/orders/{V}`，展開 VV1、VV2、VV3 | VV1：`owner-order-detail-visit-checklist-item-{I1}` 為打勾圖示且 `data-checked=true`，I2、I3 為時鐘圖示且 `data-checked=false`；VV2（`PENDING`）3 項皆 `data-checked=false`；VV3 顯示「保母未設定檢核項」；頁面無 `sitter-visit-panel-checklist-*` 元素，`owner-order-detail-visit-checklist-*` 內無輸入框、按鈕、勾選框、拖曳把手，項目無備註欄位 | `owner` F8（VV1）200，`items` 與畫面一致（含 `isCompleted`）；F1（OWNER）`checklist` 同 | 截圖 + API JSON |

### 2.6 變更取消與爭議中的訂單（訂單 Dm）

前置：建 CONFIRMED 訂單 Dm：P1 `[D+1, D+2, D+3]` × 1（VD1–VD3，總額 `$ 1,500`）→ FT1 VD1 `DONE`（訂單 → `IN_PROGRESS`）→ `owner` E17 `CHANGE` 減少 [VD2] → `sitter` E20 報價新總額 1000 → `owner` E23 同意（VD2 `CANCELLED`、變更待退款）→ `owner` E24 → `DISPUTED`（`DISPUTED` 只能經此路徑造出，見 §5 L-8）。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| K-46 | AC-1.4-1；BR-3 | sitter | VD3（`PENDING`）面板新增「睡前餵藥」 | 新增成功 | F9 201；`標題(VD3)` 最後一項為「睡前餵藥」 | 截圖 + API JSON |
| K-47 | AC-2.4-3 | admin | `admin` 送 F8（VD3） | — | 200，`items` 含「睡前餵藥」 | API JSON |

## 3. 權限與例外

> 越權一律用另一個真實帳號打 API；每列斷言後以 [INS] 確認目標行程檢核項的數量、順序、`isCompleted`、`updatedAt` 不變。目標未註明者用 §2.2 訂單 E 的 VE1（`IN_PROGRESS`，於 K-16 之後）。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| K-E01 | AC-1.6-1；EX-1 | `sitterB` 送 F9（新 key）、F10（I2）、F11（[I3, I2]）、F12（I2） | 皆 403 `FORBIDDEN` |
| K-E02 | AC-1.6-2；EX-2 | `owner`、`admin` 各送 F9（新 key） | 皆 403 `ROLE_MISMATCH` |
| K-E03 | AC-2.4-2；EX-2 | `owner` 送 F12（I2）、F9 | 皆 403 `ROLE_MISMATCH` |
| K-E04 | AC-1.6-4；BR-8 | `sitterB` 送 F9，`visitId` = 隨機 UUID、不帶 key | 404 `NOT_FOUND`（404 先於 400） |
| K-E05 | AC-2.4-1；EX-3 | `ownerB` 送 F8（VE1） | 403 `FORBIDDEN` |
| K-E06 | AC-2.4-3；EX-3 | `admin` 送 F8（VE1；訂單 E 為 `IN_PROGRESS` 且無 OPEN 待辦） | 403 `FORBIDDEN`（`DISPUTED` 的 200 見 K-47） |
| K-E07 | AC-2.4-4；EX-4 | `owner` 送 F8，路徑 `orderId=E`、`visitId` = 隨機 UUID | 404 `NOT_FOUND` |
| K-E08 | §3 權限表 | 未帶 token 送 F8、F9 | 皆 401 `UNAUTHENTICATED` |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| AC-1.1-3（變更 `SETTLED` 新增的行程取當下預設任務） | 需完整新增變更 + 補款 + 核對流程；E2E 以 K-08（之後 O1 建立的行程取當下預設）替代驗證同一快照規則 | `ChecklistSeedIntegrationTest`；TS-023 旅程可加斷言 |
| CQ-7 其餘情況（方案已刪除 / 下架 → 空清單；非字串元素略過） | 正式站造不出非字串元素；方案下架後無法建單 | `ChecklistSeedIntegrationTest` |
| AC-2.2-3 真併發（打勾 vs FC1 同時取鎖）；AC-2.2-4、EX-10（`ORDER_BUSY` + `Retry-After: 1`） | 需兩條執行緒同時取鎖或持鎖 3 秒；E2E 只做循序版（K-42–K-44） | `VisitConcurrencyIntegrationTest`、`ChecklistCheckIntegrationTest` |
| EX-9 後半（打勾 vs 變更同意取消目標行程） | 同上 | `VisitConcurrencyIntegrationTest` |
| EX-11 處理中（409 `IDEMPOTENCY_IN_PROGRESS`） | 正式站無法讓第一個請求停在處理中 | `ChecklistEditIntegrationTest` |
| EX-13（Resend / GCS） | 本卡不適用 | — |
| EX-15（帶入寫入失敗 → 行程建立整筆回滾 500） | 正式站無法注入故障 | `ChecklistSeedIntegrationTest` |
| 排序 commit 時延遲唯一約束違反 → 409 `ORDER_BUSY` | 需同時兩筆重排 | `ChecklistEditIntegrationTest` |

## 5. 已知限制 / 待補

| # | 項目 | 狀態 |
|---|------|------|
| L-1 | `order_logs` 唯讀查詢、ADMIN 帳號、cleanup 通知 | 已解決：SD-ORDER §10.4 inspect、`provision-account roles[]`、cleanup 跨模組範圍 |
| L-2 | `sitter-setup` 的 `defaultTasks` 接受超過 20 項與空白 / 超長字串 | 已解決：SD-ORDER §10.4（K-09） |
| L-3 | 修改既有方案預設任務的入口 | 已解決：`sitter-setup` upsert，以 `key` 對應方案（K-07） |
| L-4 | 打勾時間顯示位置與 testid | 已解決：PRD-028 §4.3 / §9、SD-FULFILL §9.1 `sitter-visit-panel-checklist-checked-at-{itemId}`（K-37、K-38、K-41） |
| L-5 | 打勾遇已結束行程的文案 | 已解決：PRD-028 EX-8「此行程已結束，無法修改」（K-27、K-44） |
| L-6 | SD-FULFILL §10.1 未列 EX-15、EX-11 處理中、EX-9 vs E23、BR-11 | 已解決：已補進 `ChecklistSeedIntegrationTest`、`ChecklistEditIntegrationTest`、`VisitConcurrencyIntegrationTest`；BR-11 另由 [INS] 在 E2E 驗（K-01、K-10、K-17、K-19、K-37） |
| L-7 | 正式排程競爭 | 已解決：FC1 帶 `orderId`，自動觸發跳過測試帳號訂單（K-43 斷言精確計數） |
| L-8 | 訂單 Dm 的 `DISPUTED` 只能經變更待退款 + E24 完整流程造出（FT1 只造行程狀態），耗時且任一環節失敗會連帶失敗 | 未解決，可接受（同 TS-008 §5 L-9）；`CANCELLED` 行程已改用 FT1（K-27） |

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依 commit 477c75d 核准後修訂更新：`created_by` / `updated_by` / `updated_at`、無 `order_logs` / 通知改以 inspect 在 E2E 斷言（K-01、K-02、K-10、K-11、K-17、K-19、K-37）；打勾時間改用 `sitter-visit-panel-checklist-checked-at-{itemId}`（K-37、K-38、K-41）；已結束文案依 PRD-028 EX-8；新增 K-27（FT1 造 `CANCELLED`、編輯文案），訂單 Dm 改為直接造好 `DISPUTED`、刪除原以變更造 `CANCELLED` 的檢查步驟；方案預設任務改以 `sitter-setup` upsert；FC1 帶 `orderId` 並斷言精確計數；K-25 加全形空白；ADMIN 改用 `provision-account roles[]`；步驟重新編號（K-01–K-47）；§5 已解決項標註 | SD-ORDER §10.4、SD-FULFILL §6.3 / §9.1 / §10.1 / §10.2、PRD-028 核准後修訂 |
| 2026-11-22 | 建立：依 PRD-028（Approved，自 PRD-008 拆卡）與 SD-FULFILL / SD-ORDER | 全面重建 2026-11；PRD-028、SD-FULFILL 核准 |
