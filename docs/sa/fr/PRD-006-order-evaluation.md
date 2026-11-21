# PRD-006: 保母訂單評估、事前問卷與報價

| 項目 | 內容 |
|------|------|
| 狀態 | Needs Clarification |
| 建立日期 | 2026-05-09 |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0） |
| 測試情境 | TS-006 |
| 相依 PRD | PRD-005（訂單建立 O1：`review_expires_at`、`questionnaire_status` 與問卷答案快照、毛孩綁定）；PRD-026（飼主撤回 O3 OWNER / O6）；PRD-004（問卷題目設定）；PRD-002（毛孩資料）；PRD-007（首次收款 payment 的規則、付款、O5 核對與月額度計數）；PRD-012（訂閱等級與鎖定）；PRD-014（通知偏好）；PRD-025（保母收款帳號設定）；PRD-027（婉拒 O3 SITTER、評估逾期 O4、付款前保母取消 O7，自本卡拆出） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1、§1.2（角色、資源歸屬）；§2.1、§2.2（手動調價 PRO+、快照、訂閱鎖定、每月接單上限）；§3.1、§3.2 O1 / O2（主狀態與報價前置條件，含 CQ-62 月額度、CQ-63 收款帳號）；§3.3（首次收款 payment）；§3.5（問卷送單時填寫、答案快照）；§3.6（鎖、版本 / `PRICE_CHANGED`、稽核、通知、冪等）；§3.7（錯誤碼）；§4（金額公式、整數 TWD）；§5（`review_expires_at`、`due_at`、`escalate_at`、付款提醒、時區）；§6（熟客、首日） |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念（角色、SaaS 額度、訂單狀態、時間起算點）只能**引用** DOMAIN-BASELINE，不得在這裡重新定義；要改就改 baseline。
> 拆卡：訂單離開 `PENDING` / `PENDING_PAYMENT` 的「非報價」出口（O3 SITTER 婉拒含轉介入口、O4 評估逾期、O7 付款前保母取消）移至 **PRD-027**。本卡只處理「評估 → 報價」。
> 問卷填寫在送單時（PRD-005，baseline §3.5、CQ-58）；本卡只**顯示**送單時的答案快照或免填原因，不提供任何改變問卷的操作。

---

## 1. 背景與目的

飼主送出預約（PRD-005，O1）後訂單為 `PENDING`。保母需要一個評估頁，集中看行程、毛孩資料與飼主送單時填的問卷答案，決定接不接單；接單時以原價或（PRO 以上）手動調價送出報價，讓訂單進入 `PENDING_PAYMENT` 並產生首次收款。報價是訂單內容與金額的凍結點（baseline §2.2 快照），之後只能透過變更協商（PRD-016）改動。系統不做容量 / 檔期控管，接不接單由保母判斷；系統只保證同一張訂單只會被成功轉出 `PENDING` 一次，並在報價時檢查保母的月接單額度與收款帳號。

## 2. 使用者故事

- **US-1** 身為保母，我希望在一個頁面看到預約的行程、方案、毛孩資料與評估期限，以便判斷是否接單。
- **US-2** 身為保母，我希望在評估頁看到飼主送單時填的問卷答案，或看到這筆為何免填（熟客 / 我沒有設定題目），以便不必另外詢問飼主。
- **US-3** 身為保母，我希望以原價一鍵接單，以便快速成立訂單。
- **US-4** 身為 PRO / ULTIMATE 保母，我希望在報價時加價或折扣並寫下原因，以便反映車馬費、餵藥等非標準服務。
- **US-5** 身為飼主，我希望看到報價明細（原始試算、加價、折扣、原因、付款期限），以便決定是否付款。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 檢視評估頁（含問卷答案快照） | ✗（飼主看自己的訂單詳情） | 本人訂單 | ✗（`PENDING` / `PENDING_PAYMENT` 不在 ADMIN 可讀範圍） | ✗ | 無；訂閱鎖定不擋（BR-14） |
| 原價報價 | ✗ | 本人訂單 | ✗ | ✗ | 全方案；訂閱鎖定 → 403 `SUBSCRIPTION_LOCKED`；月額度已滿 → 422 `MONTHLY_QUOTA_REACHED`（FREE 3 / BASIC 20 / PRO、ULTIMATE 無限） |
| 手動調價報價（加價 / 折扣） | ✗ | 本人訂單 | ✗ | ✗ | PRO / ULTIMATE；FREE / BASIC → 403 `PLAN_REQUIRED` |
| 檢視報價明細 | 本人訂單 | 本人訂單 | ✗ | ✗ | 無 |

- 「本人訂單」以 token 身分判斷；存在但不屬於呼叫者 → 403 `FORBIDDEN`，不存在 → 404 `NOT_FOUND`（baseline §1.2）。
- token 的 `currentRole` 與端點要求不符（例：OWNER 角色呼叫報價）→ 403 `ROLE_MISMATCH`，不自動切換（baseline §1.1）。未登入 → 401 `UNAUTHENTICATED`。

## 4. 主流程

### 4.1 評估頁

1. 保母從通知或「訂單 > 評估中」進入某筆 `PENDING` 訂單的評估頁。
2. 系統檢查呼叫者為該訂單的 sitter → 回傳：行程清單（日期、每日次數、方案名稱、方案單價）、目前原始試算 `base_amount`、飼主顯示名稱、熟客標記（BR-3）、各日期群組綁定的毛孩基本資料（BR-21）、問卷區塊（BR-2）、`review_expires_at`（Asia/Taipei 顯示）。
3. 報價預檢提示（後端仍於報價時強制檢查，見 4.3）：
   - 保母訂閱鎖定 → 頁首顯示鎖定提示。
   - 本月額度已滿（BR-12）→ 顯示「本月額度已滿」。
   - 未設定收款帳號（BR-20）→ 顯示「請先設定收款帳號」與設定頁連結。
   - 任一成立時停用「原價接單」「調整價格」「送出報價」；「婉拒預約」（PRD-027）不受影響。
4. 畫面不出現任何執行工項清單或 SOP 欄位（工項見 PRD-021）。

### 4.2 問卷區塊（唯讀）

1. 問卷由飼主在送單時填寫（PRD-005 O1），`questionnaire_status` 在 O1 寫入後不再改變（baseline §3.5、CQ-58）。本卡沒有「要求填寫問卷」「改為免填」「飼主事後填答」的流程，也沒有保母自訂的永久免填名單（baseline CQ-59）。
2. `questionnaire_status = COMPLETED` → 依題目順序顯示送單時快照的題目文字、題型與答案；保母之後在 PRD-004 修改、停用或刪除題目不影響此顯示。
3. `questionnaire_status = NOT_REQUIRED` → 依送單時記錄的免填原因顯示「免填：熟客」或「免填：保母無題目」（CQ-10）。
4. 問卷不是報價前置條件（baseline O2）。

### 4.3 報價（O2）

1. 保母按「原價接單」，或（PRO+）按「調整價格」輸入加價金額、折扣金額與原因後按「送出報價」。前端同時送出畫面上的原始試算金額（`expectedBaseAmount`）與 `Idempotency-Key`。
2. 系統鎖訂單列（baseline §3.6），依序檢查（第一個不符者決定回應的錯誤碼）：
   1. 訂單 `PENDING`（409 `ORDER_STATE_CONFLICT`）；
   2. 保母未鎖定（403 `SUBSCRIPTION_LOCKED`）；
   3. `now < review_expires_at`（409 `DEADLINE_PASSED`）；
   4. 首個行程日期 ≥ 今天 Asia/Taipei（409 `FIRST_VISIT_DATE_PASSED`）；
   5. 欄位格式（400 `VALIDATION_FAILED`）；
   6. FREE / BASIC 無調整（403 `PLAN_REQUIRED`）；
   7. 未達月接單上限（422 `MONTHLY_QUOTA_REACHED`，BR-12）；
   8. 已設定收款帳號（422 `PAYOUT_ACCOUNT_REQUIRED`，BR-20）；
   9. 以當下方案單價重算 `base_amount` 並與 `expectedBaseAmount` 比對（409 `PRICE_CHANGED`，BR-7）；
   10. `total_amount = base_amount + surcharge_amount − discount_amount ≥ 1`（422 `INVALID_QUOTE_AMOUNT`）。
3. 全部通過 → 同一交易內：
   - 訂單 `PENDING → PENDING_PAYMENT`，寫入 `base_amount`、`surcharge_amount`、`discount_amount`、`pricing_notes`、`total_amount`。
   - 快照：各行程對應方案的單價與方案名稱、保母當下訂閱等級的媒體額度與媒體保留期限（baseline §2.2）。
   - 建立首次收款 payment（`direction=CHARGE`、`purpose=INITIAL`、`amount = total_amount`、`UNPAID`），寫入 `due_at`、`escalate_at`、收款帳號快照（公式見 baseline §5；payment 本身的規則見 PRD-007）。
   - 寫 `order_logs`（`ORDER_QUOTED`，payload 含金額明細）。
   - 通知飼主報價與付款期限；`now ≥ 首日 − 48h` 時同一則通知附付款提醒（baseline §5）。
4. 回應 200，回傳訂單新狀態、金額明細與 payment 的 `due_at`。

## 5. 狀態轉移

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1（O2） | 訂單 `PENDING` | `PENDING_PAYMENT` | SITTER | baseline O2 全部條件（含未達月上限 CQ-62、已設收款帳號 CQ-63）；`expectedBaseAmount` 與重算值相同（BR-7） | 409 `ORDER_STATE_CONFLICT` / `DEADLINE_PASSED` / `FIRST_VISIT_DATE_PASSED` / `PRICE_CHANGED`；422 `INVALID_QUOTE_AMOUNT` / `MONTHLY_QUOTA_REACHED` / `PAYOUT_ACCOUNT_REQUIRED`；403 `PLAN_REQUIRED` / `SUBSCRIPTION_LOCKED`；400 `VALIDATION_FAILED` |
| T2 | 首次收款 payment（不存在） | `UNPAID` | SYSTEM（隨 T1 同交易） | T1 成功 | — |

- 本卡沒有問卷子狀態轉移（baseline §3.5：O1 寫入後不變）。

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | `questionnaire_status`（`NOT_REQUIRED` / `COMPLETED`）與答案快照由 PRD-005 於 O1 寫入，之後不變；本卡只顯示，不提供要求、改為免填、補填或修改答案的操作；問卷不是報價前置條件 | baseline §3.5、O2、CQ-58、CQ-59；使用者決定 2026-11-21 |
| BR-2 | 評估頁問卷區塊：`COMPLETED` → 顯示送單時的題目文字、題型與答案快照；`NOT_REQUIRED` → 依送單時記錄的免填原因顯示「免填：熟客」或「免填：保母無題目」 | baseline §3.5；使用者決定 2026-11-21；免填原因的資料來源待確認 CQ-10 |
| BR-3 | 熟客 = 與該保母有過 `COMPLETED` 訂單的飼主；評估頁的「熟客」標記於讀取時即時判定，與白名單（ULTIMATE 門禁）無關。問卷是否免填以送單時判定為準（BR-2），兩者可能不同（例：送單後另一筆訂單才結案），不回頭改問卷狀態 | baseline §3.5、§6、CQ-6 |
| BR-4 | 問卷答案保存題目文字與題型快照；保母之後修改、停用或刪除題目不影響此訂單的答案顯示 | baseline §3.5；PRD-004「歷史隔離」 |
| BR-5 | 報價金額：`total_amount = base_amount + surcharge_amount − discount_amount`，`1 ≤ total_amount ≤ 999,999`；`surcharge_amount`、`discount_amount` 為 0–999,999 的整數 TWD，可同時填；任一 > 0 時 `pricing_notes`（原因）必填，1–200 字（去除前後空白後）；計算中途 BigDecimal，入庫為整數。`total_amount < 1` → 422 `INVALID_QUOTE_AMOUNT`；欄位超出範圍或總額 > 999,999 → 400 `VALIDATION_FAILED` | baseline §4、O2、CQ-26；BR §5.1；上限為使用者決定 2026-11-21（CQ-7） |
| BR-6 | FREE / BASIC 只能原價（`surcharge_amount = discount_amount = 0`）；PRO / ULTIMATE 可調整。方案以報價當下的訂閱狀態即時判定 | baseline §2.1、§2.2；SAAS「定價權限」 |
| BR-7 | 報價時 `base_amount` 由後端以當下方案單價重算並覆寫，不信任前端；方案在 `PENDING` 期間被停用仍以其單價計算；與前端帶入的 `expectedBaseAmount` 不符 → 409 `PRICE_CHANGED`（payload 帶目前 `base_amount`），不寫入任何資料 | baseline §2.2（報價時快照單價）、§3.6（關鍵值比對）、§4（後端重算）；使用者決定 2026-11-21（CQ-5） |
| BR-8 | 報價時快照：各行程方案的單價與名稱、保母當下訂閱等級的媒體額度、媒體保留期限。之後保母改方案價格或升降級只影響新訂單（媒體保留展延例外見 PRD-013） | baseline §2.2、CQ-20 |
| BR-9 | 報價前置：`now < review_expires_at`（否則 409 `DEADLINE_PASSED`，payload `deadline=review_expires_at`）；首個行程日期 ≥ 今天 Asia/Taipei（否則 409 `FIRST_VISIT_DATE_PASSED`）；以時間判斷，不等排程 | baseline O2、§3.2、CQ-45、CQ-46 |
| BR-10 | 報價同交易建立首次收款 payment：`CHARGE` / `INITIAL` / `UNPAID`，`amount = total_amount`；`due_at = max(首日 − 24h, 報價時間 + URGENT_WINDOW_HOURS)`、`escalate_at = max(首日, due_at) + URGENT_WINDOW_HOURS`；收款帳號快照。報價不會因付款期限太近而被拒（緊急單允許） | baseline O2、§3.3、§5、CQ-9、CQ-10、CQ-23、CQ-44 |
| BR-11 | 報價通知：通知飼主「保母已報價 NT${total_amount}，請於 {due_at} 前完成付款」；報價時 `now ≥ 首日 − 48h` 則同一則通知附付款提醒，不另發。時間以 Asia/Taipei `YYYY-MM-DD HH:mm` 顯示 | baseline O2、§5（付款提醒）；BR §3.2 |
| BR-12 | 月接單上限在報價（O2）時檢查：計數 = 該保母於報價時間所屬 Asia/Taipei 自然月內進入 `CONFIRMED` 的訂單數（之後取消不返還；`PENDING_PAYMENT` 不計）；上限取報價當下訂閱等級：FREE 3、BASIC 20、PRO / ULTIMATE 無限。計數 ≥ 上限 → 422 `MONTHLY_QUOTA_REACHED`，不寫入任何資料。已報價、待付款的訂單不受影響：之後飼主付款、保母核對（O5，PRD-007）照常成立，即使使當月確認數超過上限。評估頁顯示「本月額度已滿」並停用報價按鈕；婉拒（PRD-027）不受影響。原「報價不檢查、只顯示額度已滿」作廢 | baseline §2.1、§2.2、O2、O5、CQ-5、CQ-21、CQ-62；使用者決定 2026-11-21 |
| BR-13 | 報價後（`PENDING_PAYMENT` 起）訂單行程、方案、金額不可再編輯；不提供改價 API；要改內容由飼主撤回重下（PRD-026），`CONFIRMED` 後走變更協商（PRD-016） | baseline §3.2、CQ-3、CQ-13；沿用原 PRD-006「不可逆性」 |
| BR-14 | 保母訂閱鎖定時：報價 → 403 `SUBSCRIPTION_LOCKED`；檢視評估頁不擋（baseline 列舉的鎖定範圍不含讀取）；婉拒不擋（PRD-027） | baseline §2.2、CQ-19 |
| BR-15 | 訂單內容只載明行程時間、方案、數量、加價、折扣、原因與最終金額，不含執行工項清單 | 沿用原 PRD-006「訂單內容範圍」；PRD-021 |
| BR-16 | 飼主訂單詳情顯示報價明細：原始試算、加價、折扣、原因與最終總額；保母輸入原因時欄位標示「飼主會看到」 | 沿用原 PRD-006 B.5（2026-10-25 fix-batch12 U6） |
| BR-17 | 不做容量 / 檔期控管：同日已有其他訂單不影響報價。只保證同一張訂單只會轉出 `PENDING` 一次（鎖訂單列，後到者 409 `ORDER_STATE_CONFLICT`） | 已定案產品決策（doc-conventions）；baseline §3.6 |
| BR-18 | 報價帶 `Idempotency-Key`，依 baseline §3.6 回放 | baseline §3.6 |
| BR-19 | T1 寫一筆 `order_logs`，與狀態變更同交易；通知以 outbox 同交易寫入（站內），Email 依 PRD-014 偏好由排程寄送 | baseline §3.6 |
| BR-20 | 報價時保母須已設定收款帳號（設定見 PRD-025），否則 422 `PAYOUT_ACCOUNT_REQUIRED`（baseline §3.7 跨模組碼），不寫入任何資料；成功時收款帳號快照到首次收款 payment | baseline O2、§3.3、§3.7、CQ-23、CQ-63；使用者決定 2026-11-21（CQ-6） |
| BR-21 | 評估頁依日期群組顯示該群組綁定的毛孩基本資料（欄位依 PRD-002）；baseline O1 規定每個日期群組至少綁定 1 隻本人的毛孩，故不會有「未綁定」的情況 | baseline §1.2、O1、CQ-60；使用者決定 2026-11-21（CQ-8） |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 其他保母讀評估頁或報價 | 拒絕，不改資料 | 403 `FORBIDDEN` | 「無權限操作此訂單」 |
| EX-2 | token 角色不符（OWNER 角色讀評估頁或報價） | 拒絕 | 403 `ROLE_MISMATCH` | 「請切換至保母身分」 |
| EX-3 | ADMIN 讀 `PENDING` / `PENDING_PAYMENT` 訂單評估頁 | 拒絕（非 baseline §1.2 ADMIN 可讀範圍） | 403 `FORBIDDEN` | — |
| EX-4 | 訂單不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此訂單」 |
| EX-5 | 未登入 | 拒絕 | 401 `UNAUTHENTICATED` | 導向登入頁 |
| EX-6 | FREE / BASIC 帶加價或折扣 | 拒絕，不改資料 | 403 `PLAN_REQUIRED` | 調價按鈕顯示鎖頭，點擊提示「手動調價為專業版功能」與升級入口（沿用原 PRD-006） |
| EX-7 | 保母訂閱逾期 / 停權時報價 | 拒絕 | 403 `SUBSCRIPTION_LOCKED` | 評估頁頂部鎖定提示；「原價接單」「送出報價」停用 |
| EX-8 | 總額 < 1（含折扣 ≥ 原始試算） | 拒絕 | 422 `INVALID_QUOTE_AMOUNT` | 「報價總額至少需 NT$1」 |
| EX-9 | 加價 / 折扣為負數、非整數、> 999,999；總額 > 999,999；有調整但原因空白或 > 200 字 | 拒絕 | 400 `VALIDATION_FAILED` | 欄位下方錯誤訊息 |
| EX-10 | 已過 `review_expires_at`（排程尚未取消） | 拒絕 | 409 `DEADLINE_PASSED`（`deadline=review_expires_at`） | 「此預約已超過評估期限」，報價按鈕停用（婉拒仍可，PRD-027） |
| EX-11 | 首個行程日期 < 今天 | 拒絕 | 409 `FIRST_VISIT_DATE_PASSED` | 「首個行程日期已過，無法報價，請婉拒或請飼主重新預約」 |
| EX-12 | 本月接單額度已滿（BR-12） | 拒絕，不改資料 | 422 `MONTHLY_QUOTA_REACHED` | 「本月接單額度已滿」與升級入口；報價按鈕停用 |
| EX-13 | 畫面上的原始試算已過期（保母於評估期間改了方案單價） | 拒絕，不寫入 | 409 `PRICE_CHANGED`（payload 帶目前 `base_amount`） | 重新載入金額，提示「訂單金額已更新為 NT${base_amount}，請確認後再送出」 |
| EX-14 | 保母未設定收款帳號 | 拒絕，不改資料 | 422 `PAYOUT_ACCOUNT_REQUIRED` | 「請先設定收款帳號」與設定頁連結（PRD-025） |
| EX-15 | 狀態不允許：訂單已非 `PENDING`（已報價、飼主已撤回、已逾期取消、已婉拒） | 拒絕 | 409 `ORDER_STATE_CONFLICT` | 「訂單狀態已變更，請重新整理」 |
| EX-16 | 跨角色併發：飼主撤回（PRD-026）與保母報價同時送出 | 鎖訂單列序列化，先到者生效，後到者看到 EX-15 | 409 `ORDER_STATE_CONFLICT` | 同 EX-15 |
| EX-17 | 鎖等待逾時 | 不改資料 | 409 `ORDER_BUSY` + `Retry-After: 1` | 前端自動重試 1 次後提示「系統忙碌，請稍後再試」 |
| EX-18 | 重複送出（同 `Idempotency-Key` 同內容） | 回放第一次的狀態碼與 body，不重複建立 payment | 同第一次 | 同第一次 |
| EX-19 | 同 key 不同內容 / 同 key 處理中 | 拒絕 | 422 `IDEMPOTENCY_KEY_REUSED` / 409 `IDEMPOTENCY_IN_PROGRESS` | 「請重新操作」 |
| EX-20 | Resend 寄信失敗 | 不影響轉移（站內通知已同交易寫入）；Email 由排程重試最多 5 次，4xx 直接判失敗 | — | 站內通知照常 |
| EX-21 | GCS | 不適用：本功能無檔案上傳 | — | — |
| EX-22 | 樂觀鎖 `@Version` 衝突（理論上被列鎖擋下的最後防線） | 交易回滾 | 409 `ORDER_STATE_CONFLICT` | 同 EX-15 |

## 8. 驗收標準（AC）

### 子模組 1：評估頁與問卷答案檢視

- [ ] **AC-1.1 (評估頁內容)**：Given SITTER A 的 `PENDING` 訂單（2 個日期群組，各綁定 1 隻不同毛孩），When A 開啟評估頁，Then 顯示每個行程的日期、每日次數、方案名稱與單價、原始試算 `base_amount`、飼主名稱、各日期群組綁定的毛孩基本資料、評估期限（Asia/Taipei `YYYY-MM-DD HH:mm`）；頁面無任何工項清單 / SOP 欄位。對應：BR-15、BR-21
- [ ] **AC-1.2 (問卷答案快照)**：Given 訂單 `questionnaire_status = COMPLETED`，飼主送單時回答 3 題，When A 開啟評估頁，Then 依題目順序顯示這 3 題送單時的題目文字與答案；A 之後在 PRD-004 修改第 1 題文字、停用第 2 題，再開啟評估頁仍顯示送單時的題目文字與答案；評估頁沒有「要求填寫問卷」「改為免填」或任何編輯問卷的按鈕，也沒有對應 API。對應：BR-1、BR-4
- [ ] **AC-1.3 (免填顯示與熟客標記)**：Given 飼主 O 送單時已與 A 有 1 筆 `COMPLETED` 訂單（`NOT_REQUIRED`，熟客），Then 問卷區塊顯示「免填：熟客」、頁首顯示「熟客」標記；Given O 非熟客、送單時 A 沒有啟用中題目（`NOT_REQUIRED`），Then 顯示「免填：保母無題目」、無「熟客」標記；Given O 與 A 只有 `CANCELLED` 訂單，Then 無「熟客」標記。對應：BR-2、BR-3
- [ ] **AC-1.4 (評估頁越權)**：Given 訂單屬於 SITTER A，When SITTER B 讀取 → 403 `FORBIDDEN`；When A 以 OWNER 角色讀取 → 403 `ROLE_MISMATCH`；When ADMIN 讀取 → 403 `FORBIDDEN`；When 未登入 → 401 `UNAUTHENTICATED`；When 訂單 id 不存在 → 404 `NOT_FOUND`。對應：EX-1–EX-5
- [ ] **AC-1.5 (報價預檢提示)**：Given A 訂閱已鎖定，When 開啟評估頁，Then 200、頁首顯示鎖定提示；Given FREE 保母 A 本月（Asia/Taipei）已有 3 筆訂單進入 `CONFIRMED`，Then 200、顯示「本月額度已滿」；Given A 沒有收款帳號，Then 200、顯示「請先設定收款帳號」與設定頁連結。三種情況「原價接單」「調整價格」「送出報價」皆停用，「婉拒預約」可用。對應：BR-12、BR-14、BR-20

### 子模組 2：報價成立與快照

- [ ] **AC-2.1 (原價接單)**：Given FREE 保母 A（本月 0 筆 `CONFIRMED`、已設收款帳號）的訂單 `PENDING`、`base_amount = 1800`，When A 按「原價接單」，Then 200；訂單 `PENDING_PAYMENT`；`surcharge_amount = 0`、`discount_amount = 0`、`total_amount = 1800`；寫 `order_logs`（`ORDER_QUOTED`，payload 含四個金額）；飼主收到「保母已報價 NT$1800，請於 {due_at} 前完成付款」。問卷 `COMPLETED` 或 `NOT_REQUIRED` 結果相同。對應：T1、BR-1、BR-5、BR-6、BR-11
- [ ] **AC-2.2 (PRO 手動調價)**：Given PRO 保母、`base_amount = 1800`，When 送出加價 300、折扣 100、原因「車馬費；首次優惠」，Then `total_amount = 2000`；飼主訂單詳情顯示「原始試算 NT$1800 / 加價 NT$300 / 折扣 NT$100 / 原因：車馬費；首次優惠 / 總額 NT$2000」；保母端原因欄位標示「飼主會看到」。ULTIMATE 保母同樣成功。對應：BR-5、BR-6、BR-16
- [ ] **AC-2.3 (報價快照)**：Given PRO 保母以方案 P（單價 600）報價成功，When 之後保母把 P 改為 800、並降級為 BASIC，Then 該訂單的方案單價仍為 600、方案名稱為報價時名稱、媒體額度仍為 PRO（10 照片 + 2 影片）、保留期限仍為 90 天；新訂單才套用新值。對應：BR-8
- [ ] **AC-2.4 (首次收款建立與期限—一般單)**：Given 首個行程 2026-12-10，When 於 2026-12-01 10:00（Asia/Taipei）報價 `total_amount = 2000`，Then 同交易建立一筆 payment：`direction=CHARGE`、`purpose=INITIAL`、`UNPAID`、`amount = 2000`、`due_at = 2026-12-08T16:00:00Z`（12-09 00:00 台北）、`escalate_at = 2026-12-09T18:00:00Z`（12-10 02:00 台北）、含收款帳號快照；報價通知不附付款提醒。對應：T2、BR-10、BR-11、BR-20
- [ ] **AC-2.5 (首次收款期限—緊急單)**：Given 首個行程為當天 2026-12-10，When 於 12-10 09:00 台北報價，Then `due_at = 2026-12-10T03:00:00Z`（11:00 台北）、`escalate_at = 2026-12-10T05:00:00Z`（13:00 台北），報價成功不被拒；Given 首個行程 2026-12-11，When 於 12-10 23:30 台北報價，Then `due_at = 2026-12-10T17:30:00Z`（12-11 01:30 台北）、`escalate_at = 2026-12-10T19:30:00Z`（12-11 03:30 台北）。兩者報價通知都附付款提醒（已過首日 − 48h）。對應：BR-10、BR-11
- [ ] **AC-2.6 (重複送出與跨角色併發)**：Given 報價已以 key K 成功，When 同 key 同內容重送，Then 回放同一 200 與 body，payment 仍只有 1 筆；When 不帶相同 key 再報價 → 409 `ORDER_STATE_CONFLICT`。Given 飼主撤回（PRD-026）與保母報價同時送出，Then 只有一方成功，另一方 409 `ORDER_STATE_CONFLICT`，訂單最終為 `CANCELLED`（無 payment）或 `PENDING_PAYMENT`（1 筆 payment）其一。鎖等待逾時 → 409 `ORDER_BUSY` + `Retry-After: 1`。對應：BR-17、BR-18、EX-15–EX-18
- [ ] **AC-2.7 (報價後不可編輯)**：Given 訂單 `PENDING_PAYMENT`，Then 保母評估頁不顯示「調整價格」「送出報價」，金額與行程為唯讀；再呼叫報價 API → 409 `ORDER_STATE_CONFLICT`，金額與 payment 不變。對應：BR-13

### 子模組 3：報價前置檢查

- [ ] **AC-3.1 (報價越權)**：Given 訂單屬於 SITTER A，When SITTER B 報價 → 403 `FORBIDDEN`；When A 以 OWNER 角色報價 → 403 `ROLE_MISMATCH`；When 訂單不存在 → 404 `NOT_FOUND`。訂單維持 `PENDING`、無 payment。對應：EX-1、EX-2、EX-4
- [ ] **AC-3.2 (方案不足調價)**：Given FREE 或 BASIC 保母，Then 評估頁「調整價格」顯示鎖頭，點擊顯示「手動調價為專業版功能」與升級入口；直接呼叫 API 帶 `surcharge_amount = 100` 或 `discount_amount = 100` → 403 `PLAN_REQUIRED`，訂單維持 `PENDING`。對應：BR-6、EX-6
- [ ] **AC-3.3 (金額與欄位驗證)**：Given PRO 保母、`base_amount = 1800`，When 折扣 1800 → 422 `INVALID_QUOTE_AMOUNT`；When 折扣 1799 → 200、`total_amount = 1`；When 加價 -100、加價 100.5、加價 1,000,000、加價 999,000（總額 1,000,800）、加價 100 但原因空白、或原因 201 字 → 400 `VALIDATION_FAILED`。對應：BR-5、EX-8、EX-9
- [ ] **AC-3.4 (評估期限與首個行程日)**：Given `now ≥ review_expires_at` 且排程尚未取消，When 報價 → 409 `DEADLINE_PASSED`（payload `deadline = review_expires_at`）。Given 飼主於 2026-12-10 23:30（台北）送出首個行程為 12-10 的預約（`review_expires_at` = 12-11 01:30 台北），When 保母於 12-11 00:30 報價 → 409 `FIRST_VISIT_DATE_PASSED`。Given 首個行程日期 = 今天，Then 報價成功。對應：BR-9、EX-10、EX-11
- [ ] **AC-3.5 (月接單額度)**：Given FREE 保母 A 於 2026-12（Asia/Taipei）已有 3 筆訂單進入 `CONFIRMED`（其中 1 筆之後已取消），When A 於 2026-12-20 報價 → 422 `MONTHLY_QUOTA_REACHED`，訂單維持 `PENDING`、無 payment；When A 於 `2026-12-31T16:00:00Z`（2027-01-01 00:00 台北）之後報價 → 200。Given FREE 保母本月 2 筆 `CONFIRMED`、另有 3 筆 `PENDING_PAYMENT` → 報價 200（待付款不計數）。Given BASIC 本月 20 筆 → 422；PRO 或 ULTIMATE 本月 50 筆 → 200；PRO 本月 5 筆後降級為 FREE → 422。對應：BR-12、EX-12
- [ ] **AC-3.6 (訂閱鎖定)**：Given 保母訂閱到期日隔天 00:00（Asia/Taipei）後未續訂，或被 ADMIN 停權，When 原價接單或調價報價 → 403 `SUBSCRIPTION_LOCKED`，訂單維持 `PENDING`；同一保母讀評估頁 → 200。對應：BR-14、EX-7
- [ ] **AC-3.7 (畫面金額過期)**：Given 保母開啟評估頁時 `base_amount = 1800`，之後把方案單價由 600 改為 700（重算為 2100），When 以 `expectedBaseAmount = 1800` 送出 → 409 `PRICE_CHANGED`（payload `base_amount = 2100`），訂單維持 `PENDING`；畫面顯示「訂單金額已更新為 NT$2100，請確認後再送出」；以 `expectedBaseAmount = 2100` 重送 → 200。Given 方案 P 於評估期間被停用，Then 仍以 P 的單價重算，報價成功。對應：BR-7、EX-13
- [ ] **AC-3.8 (未設定收款帳號)**：Given 保母沒有任何收款帳號，When 報價 → 422 `PAYOUT_ACCOUNT_REQUIRED`，訂單維持 `PENDING`、無 payment；畫面顯示「請先設定收款帳號」與設定頁連結；設定收款帳號（PRD-025）後再報價 → 200，payment 含該帳號快照。對應：BR-20、EX-14

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | 訂單：`base_amount`（報價時重算覆寫）、`surcharge_amount`、`discount_amount`、`pricing_notes`、`total_amount`；訂單快照（單價、方案名稱、媒體額度、保留期限）；首次收款 payment（欄位定義見 PRD-007）；`order_logs` 動作 `ORDER_QUOTED`。本卡只讀：`questionnaire_status`、問卷答案快照、免填原因（PRD-005 寫入，CQ-10）、當月 `CONFIRMED` 計數、收款帳號。DDL 在 SD-ORDER |
| 畫面 | 保母：`/sitter/orders/{id}` 評估頁（評估資訊 / 問卷答案唯讀 / 報價區塊）；飼主：`/owner/orders/{id}` 訂單詳情的報價明細（實際路徑以 SD-FRONTEND-SPEC 為準） |
| 通知 | 報價（含必要時的付款提醒）→ OWNER → 站內 + Email。Email 依 PRD-014 偏好 |
| 排程 | 本卡無。評估逾期（O4）在 PRD-027；付款逾期 / 提醒 / 待核對升級在 PRD-007，以本卡寫入的 `due_at`、`escalate_at` 為基準 |
| API | 讀評估頁、報價（原價與調價同一端點，調整欄位為 0 即原價）。不提供要求問卷、改為免填、送出問卷答案的端點。契約在 SD-ORDER |
| 相容性 | 現況送單時填問卷（PRD-005 Step 2）與 baseline CQ-58 一致；ULTIMATE 門禁規則 `NO_QUESTIONNAIRE` 依 baseline CQ-59 移除（PRD-005 處理）；現況報價未檢查月額度與收款帳號，需新增。既有交易資料依 baseline CQ-36 清空，不做對映 |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 | 狀態 |
|----|------|-----------|------|
| CQ-1 | baseline §3.5 只給三個值，沒定義送單時初始值，也沒說熟客能不能被要求問卷 | （原建議：送單一律 `NOT_REQUIRED`、熟客不可被要求）。決定：問卷於送單時填寫，`questionnaire_status` 由 O1 寫入且之後不變，無「要求問卷」流程（BR-1） | 已決（由 baseline CQ-58 取代） |
| CQ-2 | 原 PRD-006「加入免填清單」（保母把某飼主設為永久免填）沒有需求出處；程式現有 ULTIMATE 門禁規則 `NO_QUESTIONNAIRE` | 決定：不做；免填只有熟客與保母無題目兩種（baseline §3.5）。`NO_QUESTIONNAIRE` 規則隨 PRD-005 門禁改寫移除 | 已決（使用者 2026-11-21，baseline CQ-59） |
| CQ-3 | 保母要求問卷後能否改回免填（`PENDING_OWNER → NOT_REQUIRED`） | 決定：沒有事後要求問卷，`PENDING_OWNER` 狀態與「改為免填」一併刪除 | 已決（由 baseline CQ-58 取代） |
| CQ-4 | 問卷題目取哪個時點的題目；保母無啟用題目時能否要求 | 決定：取送單當下的啟用題目並快照（PRD-005）；保母無啟用題目 → 送單時 `NOT_REQUIRED`，評估頁顯示「免填：保母無題目」（BR-2） | 已決（由 baseline CQ-58 取代） |
| CQ-5 | `PENDING` 期間保母改方案單價，報價時 `base_amount` 用送單時的值還是當下單價重算 | 決定：報價時以當下單價重算並覆寫 `base_amount`；與畫面不符 → 409 `PRICE_CHANGED`；方案在 `PENDING` 期間被停用仍以其單價計算（BR-7、AC-3.7） | 已決（使用者 2026-11-21） |
| CQ-6 | 保母沒有收款帳號時能否報價 | 決定：不可，422 `PAYOUT_ACCOUNT_REQUIRED`，為 baseline §3.7 跨模組碼（BR-20、AC-3.8） | 已決（使用者 2026-11-21，baseline CQ-63） |
| CQ-7 | 加價 / 折扣與原因的上限 | 決定：加價、折扣各 0–999,999 的整數；`total_amount` ≤ 999,999；原因 1–200 字（BR-5、AC-3.3） | 已決（使用者 2026-11-21） |
| CQ-8 | 評估頁顯示哪些毛孩 | 決定：只顯示各日期群組綁定的毛孩；baseline CQ-60 規定每群組至少綁定 1 隻，原預設值「未綁定時顯示全部」不再發生（BR-21、AC-1.1） | 已決（使用者 2026-11-21） |
| CQ-9 | BR §3.6「送單時不填問卷」與 BR §4.5 Step 2「填寫問卷」矛盾（第 1 輪衝突 #7） | （原建議以 BR §3.6 為準）。決定：以 BR §4.5 為準，送單時填寫 | 已決（由 baseline CQ-58 取代） |
| CQ-10 | 評估頁要分辨「免填：熟客」與「免填：保母無題目」，但 baseline §3.5 兩者都只寫 `questionnaire_status = NOT_REQUIRED`，沒有記錄免填原因；事後重算會因熟客狀態改變（送單後才有訂單結案）或保母新增題目而與送單時不符 | PRD-005 於 O1 一併寫入 `questionnaire_exempt_reason`（`REGULAR_OWNER` / `NO_ACTIVE_QUESTIONS`；`COMPLETED` 時為 null），評估頁只讀此欄。兩者同時成立時記 `REGULAR_OWNER` | 已決（使用者 2026-11-21，baseline CQ-67）：送單時寫入 `questionnaire_exempt_reason`，見 PRD-005 |
| CQ-11 | 送單時綁定的毛孩之後被飼主邏輯刪除（PRD-002），評估頁與飼主訂單詳情怎麼顯示？（審查第 1 輪 #6） | 照樣顯示該毛孩的基本資料，並標示「已刪除」；baseline §1.2 保母可讀「有過訂單的飼主的毛孩」，包含已刪除者；不另做毛孩快照 | Open |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-21 | 依 baseline 第 2 版修訂（CQ-58~66）與使用者決定改寫：刪除「要求填寫問卷 / 改為免填 / 飼主事後填答」流程、`PENDING_OWNER`、Q1–Q3 轉移、`QUESTIONNAIRE_PENDING` / `QUESTIONNAIRE_STATE_CONFLICT` / `REGULAR_OWNER_EXEMPT` / `NO_ACTIVE_QUESTIONS`；4.2 改為唯讀問卷區塊（答案快照或「免填：熟客 / 保母無題目」）；O2 移除問卷前置條件，新增月額度檢查 422 `MONTHLY_QUOTA_REACHED`（BR-12 改寫，原「只顯示不擋」作廢）與收款帳號檢查 422 `PAYOUT_ACCOUNT_REQUIRED`（改為跨模組碼，新增 BR-20）；4.3 檢查順序編號；BR-5 寫入上限（CQ-7）、BR-7 寫入停用方案計價（CQ-5）、新增 BR-21 毛孩顯示（CQ-8）；例外表重編為 EX-1~22；子模組重排為「評估頁與問卷答案檢視 5 AC、報價成立與快照 7 AC、報價前置檢查 8 AC」（新增 AC-1.2、1.3、1.5、3.5，原 AC-1.3~1.8、3.5 刪除）；CQ-1~9 全部已決，新增 CQ-10（免填原因欄位）；相依 PRD 補 PRD-025 | 使用者決定 2026-11-21（baseline CQ-58、59、62、63；PRD-006 CQ-5~8 採建議預設值） |
| 2026-11-21 | 依新範本全文重寫，狀態 `In Review`。拆卡：O3 SITTER 婉拒（含轉介入口）、O4 評估逾期、O7 付款前保母取消移至 PRD-027；飼主撤回（原例外與 AC-7）移至 PRD-005；本卡改為三個子模組（評估頁與問卷 8 AC、報價成立 7 AC、報價前置檢查 8 AC） | `docs/reviews/00-DOMAIN-BASELINE.prd.review.md` 第 1、2 輪 PRD 衝突清單；baseline Approved 2026-11-21 |
| 2026-11-21 | 第 1 輪 #10（`PENDING_PAYMENT` 改名 `AWAITING_PAYMENT`）：不改名，沿用 `PENDING_PAYMENT` | baseline CQ-29 已決定沿用，覆蓋第 1 輪建議 |
| 2026-11-21 | 第 1 輪 #11（撤回可撤條件含「補款階段」）：刪除補款階段條件；飼主撤回整段移至 PRD-005（O3 OWNER / O6，前置 = 首次 payment `UNPAID`）；本卡只保留與報價的併發（AC-2.6） | baseline O6、§3.3；範圍劃分 |
| 2026-11-21 | 第 1 輪 #12（「已付款後（PAID 以後）」）：刪除 `PAID` 主狀態用語；撤回失敗碼依第 2 輪 #3 為 409 `PAYMENT_ALREADY_SUBMITTED`（定義在 PRD-005 / PRD-027 O7），本卡不再描述 | baseline O6、O7、§3.7；第 2 輪 #3 |
| 2026-11-21 | 第 1 輪 #13（報價有效期「未來擴充」）：刪除；改為報價時寫入 `due_at` / `escalate_at`（BR-10），AC-2.4、AC-2.5 含一般單、當天緊急單、23:30 報價三個範例；報價不因付款期限被拒（第 2 輪 #4 作廢 `PAYMENT_DEADLINE_PASSED`） | baseline §5、CQ-2、CQ-10、CQ-44；第 2 輪 #4 |
| 2026-11-21 | 第 1 輪 #14（最終 = 原始 + 單一調整欄位）：改為 `base + surcharge − discount`，兩欄各 ≥ 0、有調整必附原因；AC-2.2 驗原始 / 加價 / 折扣 / 總額 | baseline §4；BR §5.1 |
| 2026-11-21 | 第 1 輪 #15（`BigDecimal(18,4)`）：改為整數 TWD，計算中途 BigDecimal（BR-5）。NFR-009 需另行修正（不在本卡範圍） | baseline §4 |
| 2026-11-21 | 第 1 輪 #16（「白名單熟客」、「加入免填清單」）：熟客改依 baseline CQ-6（有 `COMPLETED` 訂單），與白名單分開（BR-3）；「加入免填清單」無出處，刪除並列 CQ-2 | baseline §3.5、§6；第 2 輪「熟客與白名單分開」 |
| 2026-11-21 | 第 1 輪 #17（鎖定行程、方案、數量、報價）：快照補方案名稱、媒體額度、保留期限，時點 = 報價（BR-8、AC-2.3） | baseline §2.2、CQ-20 |
| 2026-11-21 | 第 1 輪 #18（「訂單版本已過期需重新整理」）：依第 2 輪 #3 改寫——跨角色併發以列鎖序列化，後到者 409 `ORDER_STATE_CONFLICT`（AC-2.6）；畫面金額過期以 `expectedBaseAmount` 比對，409 `PRICE_CHANGED`（AC-3.7）；`@Version` 保留為最後防線（EX-26），不接受客戶端 version | baseline §3.6、CQ-35；第 2 輪 #3 |
| 2026-11-21 | 第 1 輪 #19（保母在待付款的操作）：依 CQ-13 不可改價（BR-13、AC-2.7）；`UNPAID` 時可取消（O7）寫在 PRD-027 | baseline O7、CQ-13 |
| 2026-11-21 | 原 D 段婉拒：「未填原因時註明保母未填寫原因」與 baseline O3「婉拒需原因」衝突，改為原因必填，移至 PRD-027 | baseline O3 |
| 2026-11-21 | 新增：問卷子狀態流程（子模組 1）、首個行程日期與評估期限檢查、訂閱鎖定、收款帳號檢查、冪等 | baseline §3.5、O2、§2.2、§3.6 |
| 2026-05-09 | 建立 | |
