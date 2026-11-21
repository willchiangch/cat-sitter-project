# 審查：PRD-016（第 1 輪，2026-11-21）

結論：需釐清

AC 數：24（子模組 1 / 2 / 3 = 8 / 8 / 8）。舊設計殘留（`MODIFYING` / `REFUND_VERIFY` / `previousStatus` / 補款回待付款）：正文無，只出現在變更紀錄的歷史描述，OK。

## 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 阻擋 | BR-14、AC-2.5、EX-11、CQ-4 | 「方案可預訂區間不看保母等級，降為 BASIC 仍 422」與 PRD-005 BR-8 / AC-2.3（區間只在保母當下為 PRO / ULTIMATE 時生效，降為 BASIC → 201）及 BASELINE §2.1「特定日期才開放預約」限 PRO+ 正面衝突。BR-14 還寫「與 PRD-005 送單同碼」，錯誤碼相同但規則相反，同一系統送單與變更會出現相反結果。BR-14 寫「區間設定本身的生效規則依 PRD-003」，但 PRD-003（Draft）也沒定義降級後的行為 | 列 CQ-8 讓使用者在兩個決定中擇一；建議對齊 BASELINE §2.1 / PRD-005：檢查當下（發起 / 報價）保母為 PRO / ULTIMATE 且未鎖定才套用區間，FREE / BASIC / 鎖定中一律常態開放。AC-2.5「降為 BASIC → 同樣 422」改為 200 |
| 2 | 重要 | §3、T1–T5、EX-1~EX-10、AC-3.3 | 錯誤碼優先序沒定義，同一請求同時違反多條規則時三張卡會給不同答案：(a) `DISPUTED` 且有 `AWAITING_REFUND` 的訂單發起變更：BR-2 可回 `ORDER_STATE_CONFLICT` 或 `MODIFICATION_IN_PROGRESS`（PRD-024 AC-2.1 斷言前者）；(b) 對 `AWAITING_*` 變更撤回的非發起者：EX-4 回 403 `FORBIDDEN`、AC-3.3 回 409；(c) 撤回 `initiatedBy=SYSTEM` 的變更：§3 說「無人可撤回」，依 T5「呼叫者 = 發起者」應 403，PRD-023 EX-15 / AC-3.6 卻斷言 409 `MODIFICATION_STATE_CONFLICT`。另 T1~T3 的錯誤碼欄漏列 400 `VALIDATION_FAILED`（BR-6 行程不屬本訂單、原因空白） | 新增一條 BR 定優先序，建議：`ROLE_MISMATCH` → 404 / 403 `FORBIDDEN`（非訂單當事人）→ 409 `ORDER_STATE_CONFLICT` → 409 `MODIFICATION_IN_PROGRESS` / `MODIFICATION_STATE_CONFLICT` → 403 `FORBIDDEN`（非發起者）→ 409 `DEADLINE_PASSED` → 400 / 422 內容驗證 → 403 `PLAN_REQUIRED`。依此 (a) 409 `ORDER_STATE_CONFLICT`、(b)(c) 409 `MODIFICATION_STATE_CONFLICT`，與 PRD-023 AC-3.6、PRD-024 AC-2.1 一致；T1~T3 補 400 |
| 3 | 重要 | §4.1-1、BR-1、BR-25、§9 資料 | 新增行程沒定義綁定哪些毛孩。BASELINE O1（CQ-60）規定每個日期群組至少綁 1 隻本人的毛孩，行程執行與照護日誌（PRD-008 / PRD-022）都需要；SD 只能自創 | 列 CQ-9；補 BR 與 AC |
| 4 | 重要 | BR-10、BR-11、BR-5、BR-8、EX-12、EX-19 | 缺邊界值：加價 / 折扣 / 新總額沒有上限（PRD-006 BR-5 報價為各 0–999,999、總額 ≤ 999,999）；發起原因、拒絕原因、調整原因沒有長度；新增行程每日次數、可新增數量、與既有行程同方案同日是否允許都沒定義（PRD-005 BR-18 送單有上限） | 列 CQ-10；寫入 BR-10 / BR-11 與 EX-19，AC-2.3 補一組超出上限 → 400 |
| 5 | 重要 | T4、T5、T6、§4.5、AC-3.1~3.4 | BASELINE §5 `completion_basis_at` 取「最後一筆變更請求結束時間」，不限 `SETTLED`。本卡造成的終態（SITTER 拒絕、撤回、`REQUESTED` / `QUOTED` 逾時）只在 §9 資料欄提到「結束時間」，轉移表與 AC 都沒寫要記錄。PRD-023 BR-15 只列 `SETTLED` / `EXPIRED` / `REJECTED`，漏了 `WITHDRAWN`。照現在寫法實作，`IN_PROGRESS` 訂單在變更被撤回後會用舊的基準時間立即自動結案 | T4 / T5 / T6 前置或附帶動作寫「記錄結束時間」；AC-3.1、AC-3.4 各補一句「結束時間 = 轉移時間」；PRD-024 AC-2.7 加一個 `WITHDRAWN` 範例 |
| 6 | 重要 | §3 檢視列 | ADMIN 可讀範圍寫「有逾期待辦」。BASELINE 第 2 版已把用詞統一為「OPEN 待辦」（§1.2、§8 變更紀錄）；「逾期」會被讀成待辦的子集合 | 改「`DISPUTED` 或有 OPEN 待辦的訂單」 |
| 7 | 重要 | AC-1.6、AC-2.3、AC-2.4、AC-2.5、AC-2.6、AC-2.8 | 三個子模組都剛好 8 條，靠把情境塞進同一條 AC 才壓住上限：AC-1.6 有 6 個情境、AC-2.5 有 5 個、AC-2.3 / AC-2.6 / AC-2.8 各 4 個。TS 拆步驟時一條 AC 對應 4–6 列，失敗時看不出是哪條規則壞了 | 主路徑留在 AC，把驗證矩陣（各錯誤碼組合）移到 TS 步驟並註明 BR 編號；若要保留在 AC，就拆出第 4 個子模組（「內容與計價驗證」），整張卡 > 3 子模組時依規則再拆 |
| 8 | 建議 | BR-8、§4.3-6 | （延伸解讀 e）「須等 OWNER 拒絕（或變更逾時）」漏了 OWNER 撤回（OWNER 發起的 `QUOTED` 可以撤回，§4.6） | 改「須等該變更結束（OWNER 拒絕、OWNER 撤回或逾時）後」 |
| 9 | 建議 | BR-9、BR-18、EX-5 | 「帶加價或折扣 → 403」沒定義 0 算不算「帶」；前端若固定送 `surcharge=0` 會被誤擋 | 寫明「任一 > 0」才算 |
| 10 | 建議 | AC-3.5 | Given 寫 `QUOTED` 已逾期，When 卻包含「SITTER 對 `REQUESTED` 報價 / 拒絕」，前提與動作對不上 | 拆成兩個 Given：`REQUESTED` 逾期（報價、拒絕、撤回）與 `QUOTED` 逾期（撤回） |
| 11 | 建議 | §4.5-1 | 「重驗條件」沒寫是哪些條件 | 寫明「鎖住後變更仍為 `REQUESTED` / `QUOTED` 且 `now ≥ expires_at` 才改 `EXPIRED`，否則略過」 |
| 12 | 建議 | BR-17、AC-2.8 vs PRD-024 §4.4、BR-9、AC-2.8 | 鎖定期間計價在兩張卡各有一條 AC 驗同一件事；BR-17 寫「驗收見 PRD-024 AC-2.8」，本卡 AC-2.8 卻也驗 | 擇一：本卡保留「報價當下判斷 / 鎖定依 FREE」，PRD-024 AC-2.8 只驗「不回 `SUBSCRIPTION_LOCKED`」並引用本卡 |
| 13 | 建議 | BR-18 | 沒寫鎖定前以 PRO 加價報出的 `QUOTED`，鎖定後 OWNER 同意時要不要重驗方案 | 寫明「方案只在報價時判斷；同意時不重驗」（PRD-023 同步） |
| 14 | 建議 | §4.6、§9 API「查詢變更（含歷史）」 | 歷史列表的排序與空狀態沒定義 | 依 `created_at` 倒序；無變更時顯示「尚無變更紀錄」 |

### 延伸解讀判斷

- (a) `MODIFICATION_EMPTY` 套用在報價階段：合理。SITTER 可調整內容，調成空的或 `CANCEL_ALL` 時 `PENDING` 已全部開始，都應擋。死路檢查：被擋後變更維持 `REQUESTED`，SITTER 唯一出口是拒絕（或等 72h 逾時），可走通。建議 EX-21 使用者文案補「請拒絕此變更」。
- (c) 方案區間不看等級：不合理，見 #1。
- (d) 鎖定中帶加減回 403 `PLAN_REQUIRED`：合理，是 BASELINE CQ-66「依 FREE 規則」加上 BR-9 的直接結果，且符合 CQ-19「不回 `SUBSCRIPTION_LOCKED`」。
- (e) BR-8 括號：方向對，但漏了 `WITHDRAWN`，見 #8。

## 待使用者回答

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-8 | 方案可預訂區間在保母為 FREE / BASIC 或訂閱鎖定時，是否仍限制變更的新增行程（本卡 CQ-4 決定「不看等級」，與 PRD-005 BR-8、BASELINE §2.1「限 PRO+」相反） | 對齊 BASELINE §2.1 / PRD-005：發起與報價當下保母為 PRO / ULTIMATE 且未鎖定才套用區間；FREE / BASIC / 鎖定 / 停權一律常態開放。AC-2.5 降級案例改為 200 |
| CQ-9 | 新增行程綁定哪些毛孩 | 必須綁定 ≥ 1 隻、只能從本訂單已綁定的毛孩中選，畫面預設全選（比照 BASELINE CQ-60）；OWNER 發起時選，SITTER 報價時預填且只能在訂單毛孩範圍內調整；違反 → 400 `VALIDATION_FAILED` |
| CQ-10 | 變更的數值與數量上限 | 比照 PRD-006 BR-5 與 PRD-005 BR-18：加價、折扣各 0–999,999 整數，新總額 ≤ 999,999；發起 / 拒絕 / 調整原因去前後空白後 1–200 字；新增行程每日次數 1–3，同方案同日與保留的有效行程合計 ≤ 3；變更後訂單日期總數 ≤ 60；違反 → 400 `VALIDATION_FAILED` |
