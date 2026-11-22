# PRD-028: 行程單日 SOP 檢核項

| 項目 | 內容 |
|------|------|
| 狀態 | Approved |
| 建立日期 | 2026-11-22 |
| 歸屬 SD | SD-FULFILL（見 `docs/sd/00-SD-PLAN.md` §0；00-PRD-PLAN 已登記 11.1，00-SD-PLAN §0 待主流程登記） |
| 測試情境 | TS-028 |
| 相依 PRD | PRD-008（行程狀態、開始 / 結束行程、服務執行面板、訂單鎖；本卡自 PRD-008 拆出）；PRD-022（日誌：文字與媒體寫在日誌，不在檢核項）；PRD-006（訂單內容不含工項清單，BR-15）；**PRD-003（方案「預設任務清單」為初始檢核項來源，BR-2；PRD-003 仍為 Draft，本卡依賴其改寫時正式定義欄位與上限）**；PRD-005 / PRD-023（行程建立時點：O1 建立行程、變更新增行程於 `SETTLED` 建立） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1、§1.2（角色、資源歸屬、ADMIN 可讀範圍）；§2.2（訂閱鎖定不擋既有訂單）；§3.1（主狀態與前端「進行中」分頁）；§3.2（`DISPUTED` 行程照常 CQ-48、CQ-57）；§3.6（鎖、冪等）；§3.7（錯誤碼、判斷順序；`VISIT_STATE_CONFLICT` 跨模組碼，第 9 步，CQ-80）；§5（時間儲存與顯示）；§6（行程定義） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 本卡 2026-11-22 自 PRD-008 拆出：PRD-008 子模組已用滿 3 個。行程狀態與開始 / 結束規則一律見 PRD-008。
> AC 內以「情境 n」分隔的每個情境各自有完整的 Given / When / Then。

---

## 1. 背景與目的

BR §5.2 要求訂單進行中時，保母可為每一次到府服務編輯「單日專屬 SOP」：增刪項目、拖曳排序，並在服務執行面板逐項打勾；打勾不強制，也不影響結束行程與日誌。訂單本身不含工項清單（PRD-006 BR-15），所以檢核項掛在行程（visit）上。行程建立時，系統把該行程所屬方案的預設任務清單（PRD-003）複製一份到行程作為初始項目，之後由保母逐行程維護；飼主可唯讀查看進度（BR §3.5 前端「排程完成狀態列表」以打勾 / 時鐘圖示呈現）。

## 2. 使用者故事

- **US-1** 身為保母，我希望行程一建立就帶入我在方案設定的預設照護項目，以便不必每次重打。
- **US-2** 身為保母，我希望為某次行程新增、刪除、排序照護項目（例如餵食、換砂、餵藥），以便到府時照表操作。
- **US-3** 身為保母，我希望服務時逐項打勾，但沒全勾也能結束行程。
- **US-4** 身為飼主，我希望看到每次行程有哪些項目、完成了哪些，以便掌握照護內容。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 新增 / 刪除 / 排序檢核項 | ✗ 403 `ROLE_MISMATCH` | 本人訂單的行程 | ✗ 403 `ROLE_MISMATCH` | ✗ 401 | 無（FREE / BASIC / PRO / ULTIMATE 皆同）；訂閱鎖定 / 停權不擋 |
| 打勾 / 取消打勾 | ✗ 403 `ROLE_MISMATCH` | 本人訂單的行程 | ✗ 403 `ROLE_MISMATCH` | ✗ 401 | 同上 |
| 檢視檢核項 | 本人訂單（唯讀） | 本人訂單 | 唯讀：`DISPUTED` 訂單或有 OPEN 待辦的訂單，其他 → 403 `FORBIDDEN` | ✗ 401 | 無 |
| 初始帶入（行程建立時） | ✗ | ✗ | ✗ | ✗ | 系統於行程建立交易內執行，無對外端點 |

- 行程存在但所屬訂單不屬於呼叫者 → 403 `FORBIDDEN`；行程或檢核項不存在 → 404 `NOT_FOUND`；檢核項不屬於路徑上的行程 → 404 `NOT_FOUND`（baseline §1.2）。
- SAAS「AI 報告助理：整合勾選數據」不在 baseline §2.1 方案矩陣，本卡不做；檢核項不分方案。

## 4. 主流程

### 4.1 初始帶入（系統，行程建立時）

1. 行程建立時（PRD-005 O1 依每個日期 × 每日次數建立行程；PRD-023 變更新增行程於 `SETTLED` 建立），系統在同一交易內讀取該行程所屬方案目前的預設任務清單（PRD-003），依原順序複製為該行程的檢核項，全部未打勾（BR-2）。
2. 複製為快照：之後保母修改或刪除方案的預設任務、或修改方案本身，都不影響已建立的行程；只影響之後才建立的行程（BR-2）。
3. 方案沒有預設任務時，行程檢核項為空白。
4. 帶入不寫 `order_logs`、不通知；帶入的項目 `created_by` 記為 SYSTEM（BR-11）。

### 4.2 編輯檢核項（保母）

1. 服務執行面板（PRD-008 `sitter-visit-panel`）的「SOP 檢核項」區（`sitter-visit-panel-checklist`）列出該行程的項目（`sitter-visit-panel-checklist-item-{itemId}`），依排序顯示。無項目時顯示 `sitter-visit-panel-checklist-empty`「尚無檢核項，可新增照護項目」。
2. 可編輯時（訂單 `CONFIRMED` / `IN_PROGRESS` / `DISPUTED` 且行程 `PENDING` / `IN_PROGRESS`，BR-3）顯示：
   - 新增：輸入框 `sitter-visit-panel-checklist-input-new` +「新增」`sitter-visit-panel-checklist-btn-add`（帶 `Idempotency-Key`）→ 項目加到最後、未打勾。
   - 刪除：每項的 `sitter-visit-panel-checklist-btn-delete-{itemId}`（已打勾的也可刪；從方案帶入的也可刪）。
   - 排序：拖曳把手 `sitter-visit-panel-checklist-drag-{itemId}`，放開後送出完整的項目 id 順序。
3. 系統檢查順序（BR-8）→ 經 SD-ORDER 鎖入口鎖訂單列 → 訂單狀態 → 行程狀態 → 數量上限 → 寫入。
4. 編輯只影響該行程，不回寫方案預設任務、不影響同訂單其他行程；不提供「套用到同訂單其他行程」（BR-12）。
5. 行程 `DONE` / `CLOSED_BY_SYSTEM` / `CANCELLED` 或訂單非上述狀態時，面板只顯示唯讀清單，不顯示新增 / 刪除 / 拖曳元素。

### 4.3 打勾（保母）

1. 行程 `IN_PROGRESS` 時，每項顯示勾選框 `sitter-visit-panel-checklist-check-{itemId}`；點擊即送出該項的目標狀態（勾 / 不勾），寫入 `is_completed` 與 `checked_at`（取消打勾時清空）。
2. 行程 `PENDING` 時勾選框停用並提示「開始行程後才能打勾」；`DONE` 後唯讀，保留最後的勾選結果，不延長到日誌 24h 編輯期（BR-4）。
3. 打勾不強制：結束行程不檢查打勾（PRD-008 BR-5），結束確認框只提示未打勾數量。

### 4.4 飼主檢視

1. 飼主訂單詳情每個行程列（PRD-008 `owner-order-detail-visit-row-{visitId}`）可展開檢核項 `owner-order-detail-visit-checklist-{visitId}`，逐項顯示名稱與狀態圖示：已打勾 → 打勾圖示（`data-checked=true`），未打勾 → 時鐘圖示（`data-checked=false`）（BR §3.5）。`PENDING` 行程的規劃項目也可見（BR-13）。無項目時顯示「保母未設定檢核項」。
2. 飼主端沒有任何編輯或打勾元素。

## 5. 狀態轉移

無行程或訂單狀態變化。檢核項只有 `is_completed`（true / false）一個布林欄位，不是狀態機；可寫入的時機受行程狀態限制（BR-3、BR-4）。

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 檢核項屬於單一行程（單日專屬），欄位：名稱、排序、是否打勾、打勾時間；訂單本身不含工項清單 | BR §5.2（`visit_services` 增刪與 `sort_order`）；PRD-006 BR-15 |
| BR-2 | 初始檢核項：行程建立時（O1 建立行程、變更新增行程於 `SETTLED` 建立），依原順序複製該行程所屬方案當下的預設任務清單，全部未打勾；複製後為行程快照，方案之後的修改不影響既有行程；方案沒有預設任務時為空白。帶入項目的上限處理見 CQ-7 | 使用者決定 2026-11-22：保留現行「方案預設任務」行為，待 PRD-003 改寫時正式定義（本卡 CQ-1） |
| BR-3 | 新增 / 刪除 / 排序：訂單 `CONFIRMED` / `IN_PROGRESS` / `DISPUTED`（前端「進行中」分頁）且行程 `PENDING` / `IN_PROGRESS`；否則訂單 → 409 `ORDER_STATE_CONFLICT`（`currentStatus`），行程 → 409 `VISIT_STATE_CONFLICT`（`currentVisitStatus`） | BR §5.2「訂單進行中時」；baseline §3.1 分頁對應、§3.2 `DISPUTED` 行程照常；行程狀態窗口為本卡 CQ-2（已決，使用者 2026-11-22） |
| BR-4 | 打勾 / 取消打勾：訂單 `IN_PROGRESS` / `DISPUTED` 且行程 `IN_PROGRESS`；`PENDING`、`DONE`、`CLOSED_BY_SYSTEM`、`CANCELLED` → 409 `VISIT_STATE_CONFLICT`。`DONE` 後全部唯讀，不延長到日誌編輯期 | BR §5.2 服務執行面板打勾；本卡 CQ-2（已決，使用者 2026-11-22） |
| BR-5 | 打勾不強制，不影響結束行程與日誌送出 | BR §5.2「逐項打勾（不強制）…有沒有打勾並不影響報告」；PRD-008 BR-5 |
| BR-6 | 上限：每個行程 ≤ 20 項；名稱去前後空白後 1–50 字。名稱違反 → 400 `VALIDATION_FAILED`（`field=title`）；新增第 21 項 → 400 `VALIDATION_FAILED`（`field=items`、`max=20`，屬依當下資料判斷的上限，判斷時點在 baseline §3.7 第 12 步） | 本卡 CQ-3（已決，使用者 2026-11-22）；baseline §3.7 判斷順序附註 |
| BR-7 | 排序：送出該行程目前全部項目 id 的新順序；id 集合與目前不一致（缺、多、重複、他行程的 id）→ 400 `VALIDATION_FAILED`（`field=itemIds`），不改順序 | 本卡設計（BR §5.2 拖曳排序的可驗證寫法） |
| BR-8 | 錯誤判斷順序依 baseline §3.7：401 → 冪等回放 → 403 `ROLE_MISMATCH` → 404（行程 / 項目不存在）→ 403 `FORBIDDEN` → 400（格式：名稱、缺 key、id 清單）→ 冪等衝突 → 409 `ORDER_STATE_CONFLICT`（第 8 步）→ 409 `VISIT_STATE_CONFLICT`（baseline §3.7 跨模組碼，第 9 步）→ 400 `VALIDATION_FAILED`（20 項上限，第 12 步） | baseline §3.7、CQ-70、CQ-76、CQ-80；PRD-008 BR-8 |
| BR-9 | 所有寫入經 SD-ORDER 訂單鎖入口（逾時 409 `ORDER_BUSY` + `Retry-After: 1`），與行程狀態轉移（保母結束、系統逾時結束、殭屍關閉、變更取消）序列化 | baseline §3.6 鎖；PRD-008 BR-9 |
| BR-10 | 新增須帶 `Idempotency-Key`（缺少 → 400 `VALIDATION_FAILED`），同 key 同內容回放、不重複建立；刪除、排序、打勾為整值寫入，重送結果相同，不要求 key（重複刪除 → 404 `NOT_FOUND`） | baseline §3.6 冪等 |
| BR-11 | 檢核項編輯與初始帶入都不是訂單或行程的狀態轉移，不寫 `order_logs`、不通知；每項保留 `created_by`（帶入者為 SYSTEM）/ `updated_by` / `updated_at` | baseline §3.6 稽核範圍（轉移）；sa-skill 稽核欄位 |
| BR-12 | 不提供「套用到同訂單其他行程」；每個行程的檢核項只能在該行程上編輯，編輯不回寫方案預設任務 | 使用者決定 2026-11-22：需求無出處，MVP 不做（本卡 CQ-5） |
| BR-13 | 飼主唯讀可見所有本人訂單行程的檢核項與打勾狀態（含 `PENDING` 行程的規劃項目），以打勾 / 時鐘圖示呈現 | BR §3.5（狀態列表依 `is_completed` 與 `visits.status` 計算）；本卡 CQ-4（已決，使用者 2026-11-22） |
| BR-14 | 時間（`checked_at`）UTC 儲存、Asia/Taipei 顯示 | baseline §5 |
| BR-15 | 檢核項沒有單項備註欄位；服務過程的文字紀錄統一寫在照護日誌（PRD-022） | 本卡 CQ-6（已決，使用者 2026-11-22）；原 PRD-008「單項備註」不保留 |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 其他保母編輯 / 打勾 | 拒絕 | 403 `FORBIDDEN` | 「無權限操作此行程」 |
| EX-2 | OWNER / ADMIN 角色編輯 / 打勾 | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換至保母身分」 |
| EX-3 | 其他飼主讀取；ADMIN 讀取不在可讀範圍的訂單 | 拒絕 | 403 `FORBIDDEN` | 「無權限檢視此訂單」 |
| EX-4 | 行程或項目不存在、項目不屬於該行程 | 拒絕 | 404 `NOT_FOUND` | 「找不到此項目，請重新整理」 |
| EX-5 | 名稱空白或 > 50 字；排序 id 集合不符；缺 `Idempotency-Key` | 拒絕 | 400 `VALIDATION_FAILED`（`field=title` / `itemIds` / `Idempotency-Key`） | 欄位下方「請輸入 1–50 字」 |
| EX-6 | 新增第 21 項 | 拒絕，不改資料 | 400 `VALIDATION_FAILED`（`field=items`、`max=20`） | 「每個行程最多 20 項」 |
| EX-7 | 訂單狀態不允許（`PENDING_PAYMENT`、`COMPLETED`、`CANCELLED`；打勾時 `CONFIRMED`） | 拒絕 | 409 `ORDER_STATE_CONFLICT`（`currentStatus`） | 「訂單狀態已變更，請重新整理」 |
| EX-8 | 行程狀態不允許（編輯時 `DONE` / `CLOSED_BY_SYSTEM` / `CANCELLED`；打勾時非 `IN_PROGRESS`） | 拒絕 | 409 `VISIT_STATE_CONFLICT`（`currentVisitStatus`） | 「此行程已結束，無法修改」/「開始行程後才能打勾」 |
| EX-9 | 跨角色 / 系統併發：保母打勾時系統逾時結束行程（PRD-008 T4），或變更同意取消目標行程（PRD-023） | 鎖序列化；系統 / 飼主先 → 保母得 EX-8；保母先 → 打勾成立，之後行程照常轉移 | 409 `VISIT_STATE_CONFLICT` | 依先後 |
| EX-10 | 鎖等待逾時 | 不改資料 | 409 `ORDER_BUSY` + `Retry-After: 1` | 「系統忙碌，請稍後再試」 |
| EX-11 | 新增重送（同 key 同內容 / 不同內容 / 處理中） | 回放 / 拒絕 / 拒絕 | 同第一次 / 422 `IDEMPOTENCY_KEY_REUSED` / 409 `IDEMPOTENCY_IN_PROGRESS` | 同第一次 / 「請重新操作」 |
| EX-12 | 方案不足 / 訂閱鎖定 | 不適用：不分方案、鎖定不擋 | 200 | 同正常流程 |
| EX-13 | Resend / GCS | 不適用：本卡不寄信、無檔案 | — | — |
| EX-14 | 行程建立時讀不到方案預設任務（方案無預設任務） | 檢核項為空白，行程照常建立，不回錯 | — | `sitter-visit-panel-checklist-empty` / 「保母未設定檢核項」 |
| EX-15 | 初始帶入寫入失敗（例外） | 與行程建立同一交易回滾，由觸發的訂單流程（PRD-005 / PRD-023）回應錯誤 | 500 `INTERNAL_ERROR`（帶 `traceId`） | 「系統發生錯誤，請稍後再試」 |

## 8. 驗收標準（AC）

### 子模組 1：初始帶入與編輯檢核項（保母）

- [ ] **AC-1.1 (行程建立時從方案帶入)**：Given SITTER A 的方案 P1「到府餵食」預設任務依序為「餵乾糧」「換貓砂」「清水碗」，方案 P2「陪玩」無預設任務；訂單 X 內容為 P1 兩天（12-10、12-11，每日 1 次）、P2 一天（12-10）。情境 1：When 訂單 X 依 PRD-005 O1 建立行程，Then P1 的兩個行程各有 3 項，依序「餵乾糧」「換貓砂」「清水碗」、皆未打勾、`created_by=SYSTEM`；P2 的行程無項目，A 開啟其面板顯示 `sitter-visit-panel-checklist-empty`，飼主展開顯示「保母未設定檢核項」；無 `order_logs`、無通知。情境 2：Given 行程已建立，When A 於 PRD-003 把 P1 預設任務改為新增「梳毛」並刪除「清水碗」，Then 訂單 X 既有兩個 P1 行程仍為原 3 項。情境 3：Given 情境 2 之後，訂單 X 的變更於 `SETTLED` 新增 P1 行程（12-12，PRD-023），Then 新行程依序為「餵乾糧」「換貓砂」「梳毛」。對應：BR-2、BR-11、EX-14
- [ ] **AC-1.2 (新增)**：Given 訂單 `CONFIRMED`、行程 V（`PENDING`）無檢核項。情境 1：When A 在 `sitter-visit-panel-checklist-input-new` 填「  餵乾糧 50g  」並按 `sitter-visit-panel-checklist-btn-add`（帶 key），再新增「換貓砂」，Then 兩次皆 201；V 依序有「餵乾糧 50g」「換貓砂」、皆未打勾；同訂單其他行程與方案 P1 預設任務皆不變；無 `order_logs`、無通知。情境 2：Given 同 key 同內容重送第一次新增，Then 回放同一 201，V 仍只有 2 項。對應：BR-1、BR-10、BR-11、BR-12、EX-11
- [ ] **AC-1.3 (刪除與排序)**：Given V（`IN_PROGRESS`）有 I1「餵乾糧」（已打勾，自方案帶入）、I2「換貓砂」、I3「餵藥」。情境 1：When A 按 `sitter-visit-panel-checklist-btn-delete-{I1}`，Then 200，V 剩 I2、I3；When 重送刪除 I1，Then 404 `NOT_FOUND`。情境 2：When A 拖曳 I3 到最前（送出 `[I3, I2]`），Then 200，面板順序為「餵藥」「換貓砂」，飼主端同序。情境 3：When 送出 `[I3]`、`[I3, I2, I2]` 或含其他行程項目 id 的清單，Then 400 `VALIDATION_FAILED`（`field=itemIds`），順序不變。對應：BR-1、BR-7、BR-10、EX-4、EX-5
- [ ] **AC-1.4 (可編輯時機)**：情境 1：Given 訂單 `DISPUTED`、V `PENDING`，When A 新增項目，Then 201。情境 2：Given V 為 `DONE` / `CLOSED_BY_SYSTEM` / `CANCELLED`（訂單 `IN_PROGRESS`），When A 新增、刪除或排序，Then 409 `VISIT_STATE_CONFLICT`（`currentVisitStatus` 為該值），面板不顯示新增 / 刪除 / 拖曳元素。情境 3：Given 訂單 `PENDING_PAYMENT`（V `PENDING`），When A 新增，Then 409 `ORDER_STATE_CONFLICT`（`currentStatus=PENDING_PAYMENT`）。情境 4：Given A 為 FREE 且訂閱已鎖定，When A 在 `IN_PROGRESS` 訂單新增項目，Then 201。情境 5：Then 面板與 API 皆無「套用到其他行程」功能。對應：BR-3、BR-8、BR-12、EX-7、EX-8、EX-12
- [ ] **AC-1.5 (上限與格式)**：情境 1：Given V 已有 20 項，When A 新增第 21 項，Then 400 `VALIDATION_FAILED`（`field=items`、`max=20`），仍 20 項，畫面顯示「每個行程最多 20 項」。情境 2：When 名稱為空白字元或去空白後 51 字，Then 400 `VALIDATION_FAILED`（`field=title`）；50 字則 201。情境 3：When 新增不帶 `Idempotency-Key`，Then 400 `VALIDATION_FAILED`（`field=Idempotency-Key`）。情境 4：Given V 為 `DONE` 且已有 20 項，When A 新增，Then 409 `VISIT_STATE_CONFLICT`（第 9 步先於第 12 步上限）。對應：BR-6、BR-8、BR-10、EX-5、EX-6
- [ ] **AC-1.6 (越權)**：情境 1：When SITTER B 對 A 的行程新增 / 刪除 / 排序，Then 403 `FORBIDDEN`。情境 2：When 飼主 O 或 ADMIN 呼叫新增，Then 403 `ROLE_MISMATCH`。情境 3：When A 以 V1 的路徑刪除屬於 V2 的項目 id，Then 404 `NOT_FOUND`，項目不變。情境 4：When SITTER B 對不存在的行程新增且不帶 key，Then 404 `NOT_FOUND`（404 先於 400）。皆不改資料。對應：BR-8、EX-1、EX-2、EX-4

### 子模組 2：打勾與檢視

- [ ] **AC-2.1 (打勾不強制)**：Given 訂單 `IN_PROGRESS`、V `IN_PROGRESS`，項目 I1、I2、I3。情境 1：When A 於 `2026-12-10T01:30:00Z` 勾選 `sitter-visit-panel-checklist-check-{I1}`，Then 200，I1 `is_completed=true`、`checked_at=2026-12-10T01:30:00Z`（畫面 Asia/Taipei 2026-12-10 09:30）；When 再點一次，Then I1 `is_completed=false`、`checked_at` 清空。情境 2：Given 只有 I2 已勾，When A 結束行程（PRD-008 AC-1.5），Then 200、V `DONE`，結束確認框顯示「尚有 2 項未打勾（不影響結束）」。情境 3：Given V `DONE`（`completed_at` 未滿 24h），Then 勾選框唯讀，保留 I2 已勾狀態；When 直接呼叫打勾 API，Then 409 `VISIT_STATE_CONFLICT`（`currentVisitStatus=DONE`）。對應：BR-4、BR-5、BR-14
- [ ] **AC-2.2 (打勾時機與併發)**：情境 1：Given V `PENDING`（訂單 `CONFIRMED`），Then 勾選框停用並顯示「開始行程後才能打勾」；When 直接呼叫打勾 API，Then 409 `ORDER_STATE_CONFLICT`（`currentStatus=CONFIRMED`）。情境 2：Given 訂單 `IN_PROGRESS`、V `PENDING`，When 打勾，Then 409 `VISIT_STATE_CONFLICT`（`currentVisitStatus=PENDING`）。情境 3：Given V `IN_PROGRESS` 已達逾時，When 系統逾時結束（PRD-008 T4）先取得鎖、A 隨後打勾，Then 409 `VISIT_STATE_CONFLICT`（`currentVisitStatus=DONE`）；When A 先取得鎖，Then 打勾 200、之後 V 照常轉 `DONE`。情境 4：Given 訂單列被鎖超過 3 秒，When A 打勾，Then 409 `ORDER_BUSY`、`Retry-After: 1`。對應：BR-4、BR-9、EX-9、EX-10
- [ ] **AC-2.3 (飼主唯讀檢視)**：Given 飼主 O 的訂單：V1（`IN_PROGRESS`，I1 已勾、I2 未勾）、V2（`PENDING`，I3）、V3（無項目）。When O 展開各行程的 `owner-order-detail-visit-checklist-{visitId}`，Then V1 顯示 I1 打勾圖示（`data-checked=true`）、I2 時鐘圖示（`data-checked=false`）；V2 顯示 I3 時鐘圖示；V3 顯示「保母未設定檢核項」；頁面無任何新增 / 刪除 / 拖曳 / 勾選元素，項目無備註欄位。對應：BR-13、BR-15
- [ ] **AC-2.4 (讀取越權)**：情境 1：When 飼主 O2 讀取 O 訂單的檢核項，Then 403 `FORBIDDEN`。情境 2：When 飼主 O 呼叫打勾或新增 API，Then 403 `ROLE_MISMATCH`。情境 3：Given 訂單 `DISPUTED`，When ADMIN 讀取，Then 200（唯讀）；Given 訂單 `IN_PROGRESS` 且無 OPEN 待辦，When ADMIN 讀取，Then 403 `FORBIDDEN`。情境 4：When 讀取不存在的行程，Then 404 `NOT_FOUND`。對應：EX-2、EX-3、EX-4

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | 行程檢核項（BR 稱 `visit_services`，SD-FULFILL 擁有）：行程 id、名稱、排序、`is_completed`、`checked_at`、`created_by`、`updated_by`、`updated_at`。行程建立交易內唯讀方案預設任務清單（PRD-003）並寫入快照。不改 `visits`、`orders`，不寫 `order_logs`。DDL 在 SD-FULFILL |
| 畫面 | 保母：服務執行面板「SOP 檢核項」區；飼主：訂單詳情行程列展開的唯讀檢核項。testid：`sitter-visit-panel-checklist`、`sitter-visit-panel-checklist-empty`、`sitter-visit-panel-checklist-item-{itemId}`、`sitter-visit-panel-checklist-input-new`、`sitter-visit-panel-checklist-btn-add`、`sitter-visit-panel-checklist-btn-delete-{itemId}`、`sitter-visit-panel-checklist-drag-{itemId}`、`sitter-visit-panel-checklist-check-{itemId}`、`owner-order-detail-visit-checklist-{visitId}`、`owner-order-detail-visit-checklist-item-{itemId}`（展開後的單一檢核項，帶 `data-checked`）（命名依 SD-FRONTEND-SPEC §4） |
| 通知 | 無 |
| 排程 | 無 |
| API | 檢核項清單讀取、新增、刪除、排序、打勾。初始帶入為行程建立的內部步驟（SD-ORDER 建立行程時呼叫 SD-FULFILL），無對外端點。契約在 SD-FULFILL |
| 卡外依賴 | PRD-003 改寫時正式定義「預設任務清單」欄位、順序與上限（宜與本卡 BR-6 一致，見 CQ-7）；00-SD-PLAN §0 登記 PRD-028（SD-FULFILL）；00-PRD-PLAN 對 PRD-008 的摘要「移除 SOP 勾選」與 BR §5.2 衝突，需更新 |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | 行程建立時檢核項的初始內容從哪來（PRD-003 方案「預設任務清單」為 Draft；PRD-021 只有記事本與模板，沒有檢核清單；PRD-006 訂單不含工項） | 空白，保母自行新增或用「套用」複製。若要從方案帶入，需先把 PRD-003 的預設任務清單定案，並決定是在 O1 建立行程時還是報價時複製 | 已決（使用者 2026-11-22）：**不採建議值**，保留現行行為，行程建立時快照方案預設任務清單；方案無預設任務時空白；待 PRD-003 改寫時正式定義 → 4.1、BR-2、EX-14、EX-15、AC-1.1 |
| CQ-2 | 編輯與打勾的行程狀態窗口 | 新增 / 刪除 / 排序：行程 `PENDING` / `IN_PROGRESS`；打勾：只有 `IN_PROGRESS`；`DONE` 後全部唯讀（不延長到日誌 24h 編輯期，避免結束後補勾） | 已決（使用者 2026-11-22）：採建議預設值 → 4.2、4.3、BR-3、BR-4 |
| CQ-3 | 數量與長度上限 | 每行程 ≤ 20 項；名稱 1–50 字 | 已決（使用者 2026-11-22）：採建議預設值 → BR-6、EX-5、EX-6 |
| CQ-4 | 飼主能否看到檢核項、從何時開始可見 | 可，唯讀，含 `PENDING` 行程的規劃項目；以打勾 / 時鐘圖示呈現（BR §3.5） | 已決（使用者 2026-11-22）：採建議預設值 → 4.4、BR-13 |
| CQ-5 | 是否提供「套用到同訂單其他待執行行程」，套用是取代還是附加 | 提供；目標限同訂單 `PENDING` 行程，整批取代、全部未打勾；任一目標不符整批不生效 | 已決（使用者 2026-11-22）：**不採建議值**，不提供（需求無出處，MVP 不做）→ 4.2 步驟 4、BR-12、AC-1.4 情境 5 |
| CQ-6 | 原 PRD-008 的「單項備註」是否保留 | 不做；文字紀錄統一寫在照護日誌（PRD-022） | 已決（使用者 2026-11-22）：採建議預設值 → BR-15 |
| CQ-7 | 初始帶入時方案預設任務不符本卡上限（超過 20 項、單項去空白後 > 50 字或為空白）怎麼處理；PRD-003 目前未定義上限 | 帶入時略過空白名稱、依順序只取前 20 項、名稱截到 50 字，不回錯、不阻擋行程建立；PRD-003 改寫時在方案端採同一上限（≤ 20 項、1–50 字），之後此情況不再發生 | 已決（主流程 2026-11-22）：採建議預設值（略過空白、只取前 20 項、名稱截到 50 字，不回錯、不擋行程建立）；方案已刪除或下架 → 空白，比照 EX-14；「方案當下」= 行程建立當下的方案內容 |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-22 | 核准後回寫，待使用者同意 2026-11-22：§9 畫面 testid 補飼主檢核單項 `owner-order-detail-visit-checklist-item-{itemId}`（供 TS 定位 AC-2.3 單項，`data-checked` 不變）。僅補 testid，不改 AC 與規則；狀態維持 Approved | `docs/reviews/SD-FULFILL.api.review.md` 第 2 輪 S20；SD-FULFILL CQ-14 |
| 2026-11-22 | 使用者核准：狀態 In Review → Approved | 使用者 2026-11-22 |
| 2026-11-22 | CQ-7 定案（帶入超出上限的處理、方案已刪除 / 下架、「方案當下」解讀） | 主流程 |
| 2026-11-22 | CQ-1~6 已決並寫回正文。CQ-1 改採方案預設任務帶入：新增 4.1 初始帶入、BR-2 改寫、EX-14 / EX-15、AC-1.1，相依 PRD 註明依賴 PRD-003 與 PRD-005 / PRD-023。CQ-5 改採不提供套用：刪除 US-2 套用、4.1 套用步驟、原 BR-12 套用規則（改為「不提供」）、BR-6 / BR-10 / EX-6 / EX-8 / EX-11 的套用部分、原 AC-1.3 與 AC-1.4 / 1.5 / 1.6 的套用情境、套用 testid 與 API。CQ-2 / 3 / 4 / 6 採建議值（BR-3、BR-4、BR-6、BR-13、新增 BR-15）。BR-8 `VISIT_STATE_CONFLICT` 改標 baseline §3.7 跨模組碼（第 9 步，CQ-80）。新增 CQ-7（帶入超出上限的處理）。主流程章節改為 4.1 初始帶入 / 4.2 編輯 / 4.3 打勾 / 4.4 飼主檢視；子模組 1 AC 重新編號。子模組 6 / 4 = 10 條 AC，狀態維持 `In Review` | 使用者決定 2026-11-22；baseline 第 3 版修訂 CQ-80 |
| 2026-11-22 | 建立（自 PRD-008 拆卡），狀態 `In Review`。內容：BR §5.2 單日 SOP 增刪、排序、打勾不強制；BR §3.5 飼主狀態圖示；新增「套用到其他待執行行程」（CQ-5）。子模組 6 / 4 = 10 條 AC；CQ-1~6 | PRD-008 子模組已滿 3 個；baseline 第 1 輪衝突 #31（原「顯示 PRD-006 執行契約任務清單」刪除，改依 BR §5.2） |
