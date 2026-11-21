# PRD-005: 公開預約頁與送出預約

| 項目 | 內容 |
|------|------|
| 狀態 | Needs Clarification |
| 建立日期 | 2026-05-09 |
| 歸屬 SD | SD-ORDER（見 `docs/sd/00-SD-PLAN.md` §0） |
| 測試情境 | TS-005 |
| 相依 PRD | PRD-001（門禁名單的設定）；PRD-002（毛孩資料、邏輯刪除）；PRD-003（方案、適用寵物、可預約日期區間）；PRD-004（問卷題目 CRUD、必填、啟用 / 停用、排序）；PRD-017（KYC 狀態）；PRD-018（公開 / 隱藏狀態、頁首資料）；PRD-012（訂閱狀態與等級）；PRD-019（我的最愛）；PRD-014（通知投遞）；PRD-006（接手 `PENDING` 訂單：評估、檢視問卷答案、報價）；PRD-027（評估逾期 O4）；PRD-026（飼主撤回 O3 OWNER、O6，自本卡拆出） |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1 角色、§1.2 資源歸屬（毛孩、問卷）、§2.1 方案矩陣（每月接單上限、特定日期才開放預約、黑名單、白名單）、§2.2 方案規則（即時查詢、每月接單上限、訂閱逾期 / 停權）、§3.1 主狀態、§3.2 O1（CQ-60 毛孩綁定）、§3.5 事前問卷（CQ-58、CQ-59）、§3.6 轉移規則（稽核、通知、冪等、版本 / `PRICE_CHANGED`）、§3.7 錯誤碼、§4 預約試算、§5 時間規則（首日、`URGENT_WINDOW_HOURS`、`review_expires_at`）、§6 熟客 |

> 一張 PRD = 一個可獨立驗收的功能。子模組 AC ≤ 8 條，子模組 > 3 個就拆卡。
> 共用概念（角色、SaaS 額度、訂單狀態、時間起算點）只能**引用** DOMAIN-BASELINE，不得在這裡重新定義；要改就改 baseline。
> 本卡範圍：預約頁的可預約判斷與門禁、日期群組 × 方案 × 毛孩與試算、送單時的事前問卷填答、送出預約（O1）與送單後自動加入我的最愛。飼主撤回（O3 OWNER、O6）拆到 PRD-026。問卷題目的 CRUD 屬 PRD-004；保母檢視問卷答案屬 PRD-006。

---

## 1. 背景與目的

保母的對外預約網頁是飼主發起交易的唯一入口（BR §2.3、§4.5）。飼主在此以「日期群組 × 方案 × 每日次數 × 毛孩」組合預約內容，必要時填寫保母的事前問卷，看到後端可重現的試算金額後送出，系統建立一筆 `PENDING` 訂單交給保母評估（PRD-006）。本卡要把「誰在什麼條件下可以送單」寫成可驗證的規則：保母 KYC、公開狀態、訂閱鎖定、每月接單上限、門禁名單（依方案等級生效）、方案可預約區間、首個行程日期、自我預約、毛孩綁定與問卷必填，並定義送單當下寫入的評估期限 `review_expires_at`（含緊急單）與問卷子狀態。

## 2. 使用者故事

- **US-1** 身為訪客，我希望不登入也能瀏覽保母的公開預約頁與方案，以便決定要不要預約；要送出時再登入。
- **US-2** 身為飼主，我希望在同一張預約中建立多個日期群組，每組可選單日、連續區間或跳選多日，並綁定一個方案與每日服務次數，以便一次排好整段照護。
- **US-3** 身為飼主，我希望選了不適合我毛孩種類的方案時被提醒，以便避免下錯單。
- **US-4** 身為飼主，我希望送出前看到試算金額與保母的回覆期限，並知道最終金額由保母確認，以便評估預算與時程。
- **US-5** 身為飼主，我希望網路不穩重送時不會產生兩張訂單。
- **US-6** 身為飼主，我希望送單後該保母自動出現在我的保母清單，以便之後追蹤與再次預約。
- **US-7** 身為保母，我希望不符合我門禁、方案區間或接單額度的預約根本送不進來，以便只處理可以接的單。
- **US-8** 身為飼主，我希望每個日期群組預設帶入我全部的毛孩，只需取消不需要照顧的，以便快速指定照護對象。
- **US-9** 身為保母，我希望新客戶送單時就已填好我的事前問卷，熟客則免填，以便評估時直接看答案。

## 3. 角色與權限

| 動作 | OWNER | SITTER | ADMIN | 匿名 | 方案限制 |
|------|-------|--------|-------|------|---------|
| 瀏覽公開預約頁與方案 | ✓（套用門禁過濾） | ✓（`currentRole=SITTER` 時視同匿名瀏覽） | ✓（視同匿名瀏覽） | ✓（白名單方案不顯示；保母設全域白名單時不顯示任何方案） | 無 |
| 試算金額 | ✓ | ✗ | ✗ | ✓（僅前端試算顯示） | 無 |
| 選擇毛孩、填寫問卷 | ✓（僅本人毛孩） | ✗ | ✗ | ✗（登入後才顯示） | 無 |
| 送出預約（O1） | ✓（`currentRole=OWNER`，非自己的保母頁） | ✗ 403 `ROLE_MISMATCH` | ✗ 403 `ROLE_MISMATCH` | ✗ 401 `UNAUTHENTICATED` | 保母未鎖定、未達每月接單上限（依保母當下等級） |

- 呼叫者身分一律取自 token；送單端點要求 `currentRole=OWNER`，不自動切換（baseline §1.1）。
- 同一帳號以 OWNER 身分預約自己的 SITTER 頁面 → 422 `SELF_BOOKING_NOT_ALLOWED`（baseline §1.1、CQ-25）。
- 方案、問卷題目、公開檔案是公開資訊，匿名可讀（baseline §1.2）；門禁名單本身不公開。
- 毛孩只能綁定本人的：他人的毛孩 → 403 `FORBIDDEN`；不存在或已刪除 → 404 `NOT_FOUND`（baseline §1.2、O1、CQ-60）。

## 4. 主流程

### 4.1 進入預約頁（可預約判斷）

1. 任何人開啟保母預約網址。系統查保母狀態，依下列順序判斷可預約狀態，取第一個成立者：
   1. 保母 KYC 不是 `VERIFIED`（PRD-017）→ 顯示「此保母尚未完成實名認證，暫不開放預約」，不顯示方案。
   2. 下列任一成立 → 顯示「保母目前不開放預約」，不顯示方案（文案相同，不揭露被封鎖）：保母公開狀態為隱藏（PRD-018）；登入中的飼主在保母的**全域黑名單**且保母當下等級為 PRO / ULTIMATE；保母設有**全域白名單**、當下等級為 ULTIMATE，且呼叫者（含匿名）不在名單內（CQ-8）。
   3. 保母訂閱逾期或停權（baseline §2.2）→ 顯示方案但「送出預約」停用，顯示「保母目前暫停接受新預約」（CQ-1）。
   4. 保母本月接單數已達當下等級上限（baseline §2.2）→ 顯示方案但「送出預約」停用，顯示「保母目前暫停接受新預約」（CQ-1）。
   5. 登入中的帳號就是此保母本人 → 「送出預約」停用，顯示「這是您自己的預約頁，無法預約」（CQ-1）。
2. 系統列出保母 `is_active` 的方案（BR §4.1），依門禁過濾（§6 BR-6、BR-7）：被過濾的方案不出現在清單。
3. 匿名訪客可完成步驟 4.2 的日期、方案選擇與前端試算；按「送出預約」時導向登入（PRD-000），登入後回到預約頁，已選內容保留，並顯示毛孩選擇（4.2 步驟 2）與問卷步驟（4.3，若需要）。

### 4.2 日期群組、方案、毛孩與試算

1. 飼主新增日期群組：選擇日期（單日、連續區間或跳選多日，可混用），為群組綁定一個方案，設定每日服務次數。可新增多個群組，可單獨刪除群組（BR §4.5、沿用原 PRD-005）。數量上限見 BR-18。
2. 每個群組列出飼主名下未刪除的毛孩，**預設全部勾選**，飼主可取消勾選；每個群組至少勾選 1 隻（baseline O1、CQ-60）。同一隻毛孩可出現在多個群組。飼主名下沒有任何毛孩時，「送出預約」停用並顯示「您尚未新增毛孩，請先至個人檔案新增」。
3. 日期選擇器停用：今天（Asia/Taipei）以前的日期；方案設有可預約日期區間且保母當下等級為 PRO / ULTIMATE 時，區間外的日期。更換方案時，已選日期落在新方案區間外則該方案在方案選擇視窗顯示為不可選並附紅字提示（BR §4.1）。不檢查方案置前天數與保母全域開放預約期間（CQ-4）。
4. 登入飼主為群組選定方案時，若該方案的適用寵物種類與該群組勾選的毛孩種類都不相符，彈出提示「您沒有適合此方案的寵物，請先至個人檔案新增或選擇其他方案」；提示不阻擋繼續（沿用原 PRD-005；比對對象改為所選毛孩，CQ-3）。
5. 前端即時顯示試算：`Σ(各群組天數 × 每日次數 × 方案單價)`（baseline §4），並固定顯示「最終總額由保母評估後確認」（沿用原 PRD-005）。

### 4.3 事前問卷

1. 飼主登入後，系統依 baseline §3.5 判斷是否需填問卷：
   1. 飼主是熟客（與該保母有過 `COMPLETED` 訂單，baseline §6、CQ-6）→ 不顯示問卷。
   2. 保母沒有啟用中的題目（PRD-004）→ 不顯示問卷。
   3. 其他 → 顯示保母全部啟用中的題目（依 PRD-004 排序，不依寵物種類過濾，CQ-11），必填題加註「必填」。
2. 需填時，全部必填題作答前「送出預約」停用；非必填題可留空。答案格式規則見 BR-22（CQ-10）。
3. 沒有保母自訂的永久免填名單；舊門禁規則 `NO_QUESTIONNAIRE` 不再有任何效果（baseline §3.5、CQ-59）。

### 4.4 送出預約（O1）

1. 送出前確認區顯示試算金額與「保母需於 {review_expires_at，Asia/Taipei，YYYY/MM/DD HH:mm} 前回覆，逾時將自動取消」；首日 < 送單時間 + 2h 時另加「距服務日很近，保母可能來不及回覆」（CQ-7）。期限以前端依 BR-5 試算，送出後以後端回傳值為準。
2. 飼主按「送出預約」。前端送出：保母、各日期群組（方案、日期清單、每日次數、毛孩 id 清單）、問卷答案（需填時；每題 `questionId` + 答案）、畫面上的試算金額 `expectedAmount`，並帶 `Idempotency-Key`（同一次送出的重試沿用同一把 key）。
3. 後端依序檢查（第一個不符即回錯，不建立任何資料）：
   1. 身分與角色：401 `UNAUTHENTICATED` / 403 `ROLE_MISMATCH`。
   2. 格式與數量上限（BR-18）→ 400 `VALIDATION_FAILED`。
   3. 冪等：同 key 同內容 → 回放；同 key 不同內容 → 422 `IDEMPOTENCY_KEY_REUSED`；處理中 → 409 `IDEMPOTENCY_IN_PROGRESS`（baseline §3.6）。
   4. 保母不存在 → 404 `NOT_FOUND`。
   5. 自我預約 → 422 `SELF_BOOKING_NOT_ALLOWED`。
   6. 保母不可預約（§4.1 步驟 1.1、1.2）→ 403 `SITTER_NOT_BOOKABLE`。
   7. 訂閱鎖定 → 403 `SUBSCRIPTION_LOCKED`。
   8. 每月接單上限已滿 → 422 `MONTHLY_QUOTA_REACHED`。
   9. 任一方案不屬於該保母、已下架、或對此飼主被門禁過濾 → 422 `PLAN_NOT_BOOKABLE`。
   10. 毛孩：任一群組未選毛孩 → 422 `PET_REQUIRED`；任一毛孩不存在或已刪除 → 404 `NOT_FOUND`；任一毛孩不屬於呼叫者 → 403 `FORBIDDEN`（BR-17）。
   11. 任一日期 < 今天（Asia/Taipei），或落在方案可預約區間外（保母 PRO+）→ 422 `INVALID_VISIT_DATE`。
   12. 問卷：後端以送單當下重新判斷是否需填（§4.3 步驟 1）。需填時，當下任一啟用中必填題沒有答案 → 409 `QUESTIONNAIRE_CHANGED`（payload 帶目前題目清單，CQ-9）；答案格式不符 → 400 `VALIDATION_FAILED`（payload 帶 `questionId`，CQ-10）。不需填時忽略送來的答案（CQ-10）。
   13. 後端重算 `base_amount`，與 `expectedAmount` 不符 → 409 `PRICE_CHANGED`（payload 帶目前金額）。
4. 全部通過後，同一交易內：
   1. 建立訂單：`status=PENDING`、`base_amount`（後端重算值）、`questionnaire_status`（需填 → `COMPLETED`；免填 → `NOT_REQUIRED`）與 `questionnaire_exempt_reason`（熟客 → `REGULAR_OWNER`；保母無題目 → `NO_ACTIVE_QUESTIONS`；兩者皆成立記 `REGULAR_OWNER`；作答時為 null，baseline §3.5 / CQ-67）、`review_expires_at = max(首日, 送單時間 + URGENT_WINDOW_HOURS)`（baseline §5）。
   2. 建立群組明細與毛孩綁定：每群組一列（方案、日期清單、每日次數、單價），並記錄所綁定的毛孩。
   3. 需填問卷時寫入答案快照：每題的題目文字、題型與答案（選擇題存所選選項文字）；非必填未答的題目存空答案（baseline §3.5；空答案見 CQ-10）。
   4. 建立行程：每個群組的每個日期 × 每日次數各一個行程，狀態 `PENDING`，帶日期（Asia/Taipei 日期）與方案（行程實體歸 SD-FULFILL，透過其 Service 建立）。
   5. 寫 `order_logs`：`ORDER_CREATED`，操作者 = 飼主（BR §4.5、baseline §3.6）。
   6. `NotificationService.enqueue()`：通知保母「新預約申請」（站內 + Email）。
   7. 若飼主的保母清單尚無此保母且未達 PRD-019 上限，加入；已達上限則略過（BR §5.5、CQ-6）。
5. 回 201，body 含 `orderId`、`status=PENDING`、`baseAmount`、`reviewExpiresAt`、`questionnaireStatus`。前端導向飼主訂單詳情（評估中分頁）。

## 5. 狀態轉移

| # | 從 | 到 | 觸發者 | 前置條件 | 不符時錯誤碼 |
|---|----|----|-------|---------|-------------|
| T1（baseline O1） | — | `PENDING` | OWNER | `currentRole=OWNER`；非自己的保母頁；保母 KYC `VERIFIED` 且非隱藏、飼主不在全域黑名單（PRO+ 生效）、設全域白名單時在名單內（ULTIMATE 生效）；保母未鎖定；未達每月接單上限；方案可預約；每個日期群組至少綁定 1 隻本人未刪除的毛孩；首個行程日期 ≥ 今天（Asia/Taipei）；需填問卷時當下啟用中必填題皆已作答；`expectedAmount` = 後端重算值 | 403 `ROLE_MISMATCH`；422 `SELF_BOOKING_NOT_ALLOWED`；403 `SITTER_NOT_BOOKABLE`；403 `SUBSCRIPTION_LOCKED`；422 `MONTHLY_QUOTA_REACHED`；422 `PLAN_NOT_BOOKABLE`；422 `PET_REQUIRED`；403 `FORBIDDEN`；404 `NOT_FOUND`；422 `INVALID_VISIT_DATE`；409 `QUESTIONNAIRE_CHANGED`；400 `VALIDATION_FAILED`；409 `PRICE_CHANGED` |

- 行程以 `PENDING` 建立（baseline §6）；`questionnaire_status` 於 T1 寫入 `NOT_REQUIRED` 或 `COMPLETED` 後不再改變（baseline §3.5）。本卡不造成其他轉移。

## 6. 業務規則

| # | 規則 | 出處 |
|---|------|------|
| BR-1 | 預約內容 = 一或多個日期群組；每群組 = 一個方案 + 一組日期（單日、區間、跳選可混用）+ 每日服務次數 + 一或多隻毛孩；群組可獨立刪除 | BR §4.5；沿用原 PRD-005；baseline O1、CQ-60 |
| BR-2 | 行程數 = Σ(群組日期數 × 每日次數)；行程只有日期、沒有時刻，日期以 Asia/Taipei 解讀 | baseline §4、§5；BR §5.2（同日多次服務）；沿用原 PRD-005「系統只提供日期」 |
| BR-3 | `base_amount = Σ(各群組天數 × 每日次數 × 方案單價)`，TWD 整數，由後端重算，不信任前端金額；與毛孩數量無關；前端以 `expectedAmount` 送出畫面金額，與後端值不符 → 409 `PRICE_CHANGED`。加價 / 折扣與快照於報價時處理（PRD-006） | baseline §4、§3.6（CQ-35）、§2.2（CQ-20） |
| BR-4 | 首個行程日期 ≥ 今天（Asia/Taipei），否則 422 `INVALID_VISIT_DATE`；不設最短提前天數（允許緊急單） | baseline O1、CQ-10 |
| BR-5 | `review_expires_at = max(首日, 送單時間 + URGENT_WINDOW_HOURS)`，`URGENT_WINDOW_HOURS = 2`；首日 = 首個行程日期 00:00 Asia/Taipei；送單時寫入，UTC 儲存。`首日 < 送單時間 + 2h` 的訂單即緊急單，期限 = 送單時間 + 2h | baseline §5、CQ-9、CQ-10 |
| BR-6 | 全域黑名單與方案黑名單只在保母當下等級為 PRO / ULTIMATE 時生效；全域白名單與方案白名單只在 ULTIMATE 時生效；等級不足時名單保留但不套用，所有方案對所有人可見。等級以當下訂閱即時查詢 | baseline §2.1、§2.2；SAAS「預約門禁」；本卡 CQ-8 |
| BR-7 | 方案白名單生效時，不在名單內的帳號（含匿名）看不到該方案；方案黑名單內的帳號看不到該方案；全域黑名單內的帳號，或全域白名單生效時不在名單內的帳號（含匿名），看到「保母目前不開放預約」，與隱藏狀態同文案 | 沿用原 PRD-005（門禁隱藏制、全域封鎖）；全域白名單見 CQ-8（使用者 2026-11-21）；名單內容與衝突處理見 PRD-001 |
| BR-8 | 方案可預約日期區間只在保母當下等級為 PRO / ULTIMATE 時生效；FREE / BASIC 一律常態開放。方案置前天數（`advance_booking_days`）與保母全域「開放預約期間」本次不做 | baseline §2.1（特定日期才開放預約）；SAAS「檔期控制」；BR §4.1；CQ-4（使用者 2026-11-21） |
| BR-9 | 每月接單上限：計數 = 該保母當月（Asia/Taipei 自然月）進入 `CONFIRMED` 的訂單數，取消不返還；上限取送單當下等級（FREE 3、BASIC 20、PRO / ULTIMATE 無限）；達上限 → 422 `MONTHLY_QUOTA_REACHED`。`PENDING` / `PENDING_PAYMENT` 訂單不計入，送單不佔額度 | baseline §2.1、§2.2、CQ-5、CQ-21 |
| BR-10 | 保母訂閱到期日隔天 00:00（Asia/Taipei）未續訂，或被 ADMIN 停權 → 送單 403 `SUBSCRIPTION_LOCKED`；不影響該保母既有訂單 | baseline §2.2、CQ-19 |
| BR-11 | 保母 KYC 不是 `VERIFIED` → 預約頁不顯示方案，送單 403 `SITTER_NOT_BOOKABLE` | 沿用原 PRD-005（KYC 封鎖）；PRD-017 |
| BR-12 | 不做接單容量 / 檔期控管：同一日期已有其他訂單不阻擋送單；要不要接由保母於 PRD-006 判斷 | 既有產品決策（doc-conventions「已定案產品決策」）；沿用原 PRD-005 |
| BR-13 | 問卷於送單時填寫：熟客（與該保母有過 `COMPLETED` 訂單）或保母沒有啟用中題目 → 不顯示問卷、`questionnaire_status = NOT_REQUIRED`；其他 → 必須作答全部必填題才能送出，`questionnaire_status = COMPLETED`。是否需填以送單當下後端判斷為準 | baseline §3.5、§6、CQ-6、CQ-58；BR §4.5 Step 2（BR §3.6「送單不填」不採用） |
| BR-14 | 送單成功後，若飼主的保母清單尚無此保母，自動加入；加入不通知保母；清單已達 PRD-019 上限（50 位）時略過加入，訂單照常建立，不提示 | BR §5.5；PRD-019 單向隱私；CQ-6（使用者 2026-11-21） |
| BR-15 | 送單須帶 `Idempotency-Key`（缺少 → 400 `VALIDATION_FAILED`）；範圍、回放、`IDEMPOTENCY_KEY_REUSED`、`IDEMPOTENCY_IN_PROGRESS`、有效期 24h 依 baseline；request hash 含毛孩與問卷答案 | baseline §3.6、§5 `IDEMPOTENCY_KEY_TTL_HOURS` |
| BR-16 | O1 的 `order_logs`（`ORDER_CREATED`）、問卷答案快照、通知 enqueue 與建立訂單同一交易；Email 由排程寄送，失敗不回滾訂單 | baseline §3.6、CQ-33、CQ-52；BR §4.5 |
| BR-17 | 每個日期群組至少綁定 1 隻本人未刪除（PRD-002 邏輯刪除）的毛孩；畫面預設全選飼主名下毛孩，飼主可取消勾選。送出時群組未選毛孩 → 422 `PET_REQUIRED`（模組碼）；毛孩不存在或已刪除 → 404 `NOT_FOUND`；他人的毛孩 → 403 `FORBIDDEN` | baseline O1、§1.2、CQ-60（使用者 2026-11-21）；本卡 CQ-3 |
| BR-18 | 數量上限：每日服務次數 1–3；單張訂單 ≤ 10 個群組、日期總數（各群組日期數加總）≤ 60；同一方案同一日期只能出現在一個群組，不同方案可同日；違反 → 400 `VALIDATION_FAILED`（payload 指出欄位） | CQ-5（使用者 2026-11-21） |
| BR-19 | 問卷答案快照：寫入每題的題目文字、題型與答案；之後保母修改、停用或刪除題目，不影響既有訂單顯示的題目與答案 | baseline §3.5；PRD-004「歷史隔離」 |
| BR-20 | 不提供保母自訂的永久免填名單；舊門禁規則 `NO_QUESTIONNAIRE` 移除，免填只有熟客與保母無啟用題目兩種 | baseline §3.5、CQ-59（使用者 2026-11-21） |
| BR-21 | 送出前顯示評估期限「保母需於 {review_expires_at} 前回覆，逾時將自動取消」（Asia/Taipei）；緊急單另加「距服務日很近，保母可能來不及回覆」 | CQ-7（使用者 2026-11-21） |
| BR-22 | 問卷答案格式：單選答案須為該題選項之一；多選答案須為選項子集，必填時至少 1 項；簡答 ≤ 200 字、詳答 ≤ 1000 字；非必填可空，未答時快照存空答案；不符 → 400 `VALIDATION_FAILED`（payload 帶 `questionId`）。免填時送來的答案忽略、不存 | 本卡 CQ-10（建議預設，待確認） |
| BR-23 | 不可送單時的飼主端文案：訂閱鎖定與每月額度已滿都顯示「保母目前暫停接受新預約」（不揭露保母的訂閱 / 額度狀況）；預約自己顯示「這是您自己的預約頁，無法預約」 | CQ-1（使用者 2026-11-21） |

## 7. 例外處理

| # | 情境 | 系統行為 | HTTP / 錯誤碼 | 使用者看到 |
|---|------|---------|--------------|-----------|
| EX-1 | 未登入按送出 | 不建立；前端導向登入，登入後回預約頁並保留已選內容 | 401 `UNAUTHENTICATED` | 登入頁 |
| EX-2 | `currentRole=SITTER` 或 ADMIN 呼叫送單 | 拒絕，不建立 | 403 `ROLE_MISMATCH` | 「請切換為飼主身分後再預約」 |
| EX-3 | 預約自己的保母頁 | 拒絕，不建立 | 422 `SELF_BOOKING_NOT_ALLOWED` | 「這是您自己的預約頁，無法預約」 |
| EX-4 | 保母 KYC 未通過 | 拒絕 | 403 `SITTER_NOT_BOOKABLE`（payload `reason=NOT_VERIFIED`） | 「此保母尚未完成實名認證，暫不開放預約」 |
| EX-5 | 保母隱藏；飼主在全域黑名單（PRO+）；全域白名單生效（ULTIMATE）且飼主不在名單 | 拒絕；三者回應相同 | 403 `SITTER_NOT_BOOKABLE`（payload `reason=UNAVAILABLE`） | 「保母目前不開放預約」 |
| EX-6 | 保母訂閱逾期 / 停權（方案不足的一種：保母已無有效訂閱） | 拒絕 | 403 `SUBSCRIPTION_LOCKED` | 「保母目前暫停接受新預約」 |
| EX-7 | 保母本月接單上限已滿 | 拒絕 | 422 `MONTHLY_QUOTA_REACHED` | 「保母目前暫停接受新預約」 |
| EX-8 | 方案不屬於該保母、已下架，或被門禁過濾（例：畫面停留期間飼主被加入方案黑名單） | 拒絕；不揭露是否因門禁 | 422 `PLAN_NOT_BOOKABLE` | 「此方案目前無法預約，請重新整理後再選擇」 |
| EX-9 | 日期 < 今天，或在方案可預約區間外 | 拒絕 | 422 `INVALID_VISIT_DATE`（payload 帶不符的日期） | 「所選日期 {日期} 無法預約」 |
| EX-10 | 畫面停留期間保母改了方案單價（畫面過期，替代樂觀鎖） | 拒絕，不建立 | 409 `PRICE_CHANGED`（payload 帶目前 `baseAmount`） | 「方案價格已更新，請確認新金額後再送出」，畫面更新為新金額 |
| EX-11 | 網路中斷重送（同 key 同內容） | 回放第一次結果，不建第二張 | 201（回放，同一 `orderId`） | 同第一次 |
| EX-12 | 同 key 不同內容 | 拒絕 | 422 `IDEMPOTENCY_KEY_REUSED` | 「請重新送出」 |
| EX-13 | 同 key 第一次仍在處理 | 拒絕，可稍後重試 | 409 `IDEMPOTENCY_IN_PROGRESS` | 前端自動重試，不提示 |
| EX-14 | 欄位格式錯誤或超過數量上限（群組為空、群組日期為空、每日次數不在 1–3、群組 > 10、日期總數 > 60、同方案同日重複、缺 `Idempotency-Key`） | 拒絕 | 400 `VALIDATION_FAILED` | 欄位旁錯誤訊息 |
| EX-15 | 保母不存在 | 拒絕 | 404 `NOT_FOUND` | 「找不到此保母」 |
| EX-16 | Email 寄送失敗（Resend） | 訂單照常建立；站內通知已寫入；Email 由排程重試最多 5 次（baseline §3.6） | 201 | 無感 |
| EX-17 | 同日已有其他訂單 | 不阻擋（BR-12） | 201 | 正常送出 |
| EX-18 | 自動加入我的最愛時清單已達 50 位上限 | 略過加入；不影響訂單建立（BR-14） | 201 | 無感，不提示 |
| EX-19 | 狀態不允許的轉移 | 不適用：O1 是建立新訂單，沒有前置主狀態 | — | — |
| EX-20 | 訂單列鎖 / 跨角色併發 | 不適用：O1 不修改既有訂單；月額度只在 O5 增加，送單不佔額度，不需鎖 | — | — |
| EX-21 | GCS 失敗 | 不適用：送單不上傳檔案 | — | — |
| EX-22 | 某群組未選任何毛孩（前端停用被繞過） | 拒絕，不建立 | 422 `PET_REQUIRED`（模組碼，payload 帶群組索引） | 「每個日期群組至少選擇 1 隻毛孩」 |
| EX-23 | 綁定他人的毛孩（BOLA） | 拒絕，不建立 | 403 `FORBIDDEN` | 「無權限使用此毛孩」 |
| EX-24 | 毛孩不存在或已刪除（例：畫面停留期間在另一頁刪除） | 拒絕，不建立 | 404 `NOT_FOUND` | 「找不到所選毛孩，請重新整理」 |
| EX-25 | 需填問卷但當下啟用中必填題缺答（例：畫面停留期間保母新增必填題或由非必填改必填） | 拒絕，不建立 | 409 `QUESTIONNAIRE_CHANGED`（模組碼，payload 帶目前題目清單；CQ-9） | 「保母的問卷已更新，請確認後再送出」，問卷重新載入並保留仍存在題目的已填答案 |
| EX-26 | 問卷答案格式不符（選項不存在、超過字數） | 拒絕，不建立 | 400 `VALIDATION_FAILED`（payload 帶 `questionId`；CQ-10） | 題目旁錯誤訊息 |
| EX-27 | 飼主名下沒有任何毛孩 | 前端停用送出；直接呼叫 API 依 EX-22 處理 | 422 `PET_REQUIRED` | 「您尚未新增毛孩，請先至個人檔案新增」 |

## 8. 驗收標準（AC）

### 子模組 1：預約頁可預約判斷與門禁

- [ ] **AC-1.1 (匿名瀏覽與登入送單)**：Given 保母 KYC `VERIFIED`、公開、有 2 個 `is_active` 方案（無白名單），When 匿名訪客開啟預約頁，Then 看到 2 個方案且可選日期與看到試算，不顯示毛孩選擇與問卷；When 按「送出預約」，Then 導向登入頁，登入後回到預約頁且已選群組仍在，並出現毛孩選擇；直接呼叫送單 API 無 token → 401 `UNAUTHENTICATED`。對應：EX-1
- [ ] **AC-1.2 (KYC 未通過)**：Given 保母 KYC 為 `PENDING`，When 飼主開啟預約頁，Then 顯示「此保母尚未完成實名認證，暫不開放預約」且不顯示方案；When 直接呼叫送單 API，Then 403 `SITTER_NOT_BOOKABLE`、`reason=NOT_VERIFIED`，資料庫無新訂單。對應：BR-11、EX-4
- [ ] **AC-1.3 (隱藏與全域黑名單同文案)**：Given PRO 保母 A 的全域黑名單含飼主 X、保母 B 為隱藏狀態，When X 開啟 A 的頁面、飼主 Y 開啟 B 的頁面，Then 兩者都顯示「保母目前不開放預約」、不顯示方案，送單皆回 403 `SITTER_NOT_BOOKABLE`、`reason=UNAVAILABLE`（兩者 response body 相同）。Given A 降為 BASIC，When X 再開啟，Then 看到所有方案且可送單（201）。對應：BR-6、BR-7、EX-5
- [ ] **AC-1.4 (方案黑 / 白名單依等級生效)**：Given ULTIMATE 保母有方案 P1（白名單 = 飼主 X）、P2（黑名單 = 飼主 Y）、P3（無名單），When X 開啟頁面 Then 看到 P1、P2、P3；When Y 開啟 Then 只看到 P3；When 匿名開啟 Then 看到 P2、P3；When Y 以 P1 或 P2 的 planId 直接送單 Then 422 `PLAN_NOT_BOOKABLE`。Given 保母降為 PRO，Then 匿名看到 P1、P2、P3，Y 仍看不到 P2。Given 降為 BASIC，Then Y 看到全部三個方案。對應：BR-6、BR-7、EX-8
- [ ] **AC-1.5 (全域白名單)**：Given ULTIMATE 保母設全域白名單 = 飼主 X，When 飼主 Y 或匿名訪客開啟預約頁，Then 顯示「保母目前不開放預約」且不顯示方案；Y 直接送單 → 403 `SITTER_NOT_BOOKABLE`、`reason=UNAVAILABLE`，response body 與 AC-1.3 相同；When X 開啟 Then 看到所有方案且送單 201。Given 保母降為 PRO，When Y 送單 Then 201。對應：BR-6、BR-7、EX-5、CQ-8
- [ ] **AC-1.6 (訂閱鎖定)**：Given 保母訂閱到期日 2026-11-20，When 飼主於 2026-11-21 00:00（Asia/Taipei）之後開啟預約頁，Then 方案可見、「送出預約」按鈕停用並顯示「保母目前暫停接受新預約」；直接呼叫送單 API → 403 `SUBSCRIPTION_LOCKED`，無新訂單；該保母既有 `PENDING` 訂單狀態不變。2026-11-20 23:59 送單 → 201。對應：BR-10、BR-23、EX-6
- [ ] **AC-1.7 (每月接單上限)**：Given FREE 保母在 2026-11 有 3 張訂單於 11 月內進入 `CONFIRMED`（其中 1 張之後被取消），When 飼主於 2026-11-30 23:59（Asia/Taipei）送單，Then 422 `MONTHLY_QUOTA_REACHED`、預約頁按鈕停用並顯示「保母目前暫停接受新預約」（與 AC-1.6 文案相同）；When 於 2026-12-01 00:00 送單 Then 201。Given 同一保母 11 月中升級為 BASIC，When 11 月送單 Then 201。Given 保母有 5 張 `PENDING` 訂單、0 張 `CONFIRMED`，Then 送單仍為 201。對應：BR-9、BR-23、EX-7
- [ ] **AC-1.8 (角色不符與預約自己)**：Given 帳號 U 有 OWNER 與 SITTER profile，When U 以 `currentRole=OWNER` 開啟自己的預約頁，Then 按鈕停用並顯示「這是您自己的預約頁，無法預約」，直接呼叫送單 API → 422 `SELF_BOOKING_NOT_ALLOWED`；When U 以 `currentRole=SITTER` 對其他保母送單 → 403 `ROLE_MISMATCH`；ADMIN token 呼叫同端點 → 403 `ROLE_MISMATCH`。以上皆無新訂單。對應：BR-23、EX-2、EX-3

### 子模組 2：預約內容（日期群組、方案、毛孩與試算）

- [ ] **AC-2.1 (日期群組組合與試算)**：Given 方案 P1 單價 500、P2 單價 800，When 飼主建立群組 G1 = P1、11/25–11/27 區間、每日 2 次，群組 G2 = P2、跳選 11/29 與 12/02、每日 1 次，Then 畫面列出 2 個群組，試算顯示 `3 × 2 × 500 + 2 × 1 × 800 = 4600`，金額下方顯示「最終總額由保母評估後確認」；When G1 由 1 隻毛孩改為勾選 2 隻，Then 試算仍為 4600；When 刪除 G2，Then 試算更新為 3000。對應：BR-1、BR-3
- [ ] **AC-2.2 (過去日期停用)**：Given 現在為 2026-11-21 00:30（Asia/Taipei，= 2026-11-20T16:30Z），When 飼主開啟日期選擇器，Then 11/20 以前停用、11/21 可選；When 直接呼叫送單 API 帶日期 2026-11-20，Then 422 `INVALID_VISIT_DATE`（payload 含 `2026-11-20`），無新訂單。對應：BR-4、EX-9
- [ ] **AC-2.3 (方案可預約區間依等級生效)**：Given PRO 保母的方案 P1 可預約區間 12/30–01/02，When 飼主已選 12/28 再開啟方案選擇視窗，Then P1 顯示為不可選並附紅字提示；When 直接送單 P1 + 12/28，Then 422 `INVALID_VISIT_DATE`。Given 保母降為 BASIC，When 送單 P1 + 12/28，Then 201。Given 首個行程日期 = 今天，Then 不因置前天數被擋（201）。對應：BR-8、EX-9
- [ ] **AC-2.4 (毛孩預設全選與種類提示)**：Given 飼主名下有貓 A、貓 B、狗 C 及已刪除的貓 D，When 登入後新增群組，Then 群組列出 A、B、C 且三隻預設勾選、不列出 D；When 取消勾選 C 並為該群組選適用寵物 = 狗的方案 P1，Then 彈出「您沒有適合此方案的寵物，請先至個人檔案新增或選擇其他方案」，關閉後仍可送出（201），該群組綁定 A、B。Given 飼主名下沒有毛孩，Then 「送出預約」停用並顯示「您尚未新增毛孩，請先至個人檔案新增」。對應：BR-17、US-3、US-8、EX-27
- [ ] **AC-2.5 (毛孩綁定檢核)**：When 直接呼叫送單 API 且某群組毛孩清單為空 → 422 `PET_REQUIRED`（payload 帶群組索引）；含其他飼主的毛孩 id → 403 `FORBIDDEN`；含不存在或已刪除的毛孩 id → 404 `NOT_FOUND`；以上皆無新訂單、無 `order_logs`。對應：BR-17、EX-22~EX-24
- [ ] **AC-2.6 (格式與數量上限)**：When 送單時群組清單為空、某群組日期清單為空、每日次數為 0 或 4、群組數 11、日期總數 61、或同一方案的 12/01 同時出現在兩個群組，Then 400 `VALIDATION_FAILED`（payload 指出欄位），無新訂單；Given 群組 10 個、日期總數 60、每日次數 3，Then 201；Given 方案 P1、P2 各一群組且都含 12/01，Then 201。對應：BR-18、EX-14
- [ ] **AC-2.7 (同日不阻擋)**：Given 保母在 11/25 已有 3 張其他飼主的 `PENDING` 訂單與 1 張 `CONFIRMED` 訂單，When 飼主送單 11/25，Then 201，新訂單 `PENDING`。對應：BR-12、EX-17
- [ ] **AC-2.8 (方案不可預約)**：Given 飼主頁面上已選方案 P1，When 保母在飼主送出前將 P1 下架，或以其他保母的 planId 送單，Then 422 `PLAN_NOT_BOOKABLE`，無新訂單，畫面顯示「此方案目前無法預約，請重新整理後再選擇」。對應：EX-8

### 子模組 3：事前問卷與送出預約（O1）

- [ ] **AC-3.1 (建立訂單、行程與毛孩綁定)**：Given AC-2.1 的 G1（綁定貓 A、貓 B）、G2（綁定狗 C）與 `expectedAmount=4600`，保母無啟用題目，When 飼主送單，Then 201，body 含 `orderId`、`status=PENDING`、`baseAmount=4600`、`reviewExpiresAt`、`questionnaireStatus=NOT_REQUIRED`；資料庫該訂單有 2 筆群組明細（G1 綁 A、B，G2 綁 C）與 8 個 `PENDING` 行程（11/25、11/26、11/27 各 2 個，11/29、12/02 各 1 個）；`order_logs` 有 1 筆 `ORDER_CREATED`、操作者 = 該飼主。對應：T1、BR-1、BR-2、BR-3、BR-16、BR-17
- [ ] **AC-3.2 (評估期限：一般單與緊急單)**：Given 送單時間 2026-11-21 10:00（Asia/Taipei）、首個行程日期 11/25，When 開啟確認區 Then 顯示「保母需於 2026/11/25 00:00 前回覆，逾時將自動取消」，送單後 `review_expires_at = 2026-11-24T16:00:00Z`，回應 `reviewExpiresAt` 相同。Given 送單時間 11/21 23:00、首個行程日期 11/22，Then 確認區另顯示「距服務日很近，保母可能來不及回覆」，`review_expires_at = 2026-11-21T17:00:00Z`（= 11/22 01:00 台北，送單 + 2h）。Given 送單時間 11/21 10:00、首個行程日期 11/21，Then `review_expires_at = 2026-11-21T04:00:00Z`（= 11/21 12:00）。對應：BR-4、BR-5、BR-21
- [ ] **AC-3.3 (後端重算與畫面過期)**：Given 飼主開啟頁面時 P1 單價 500，保母之後改為 600，When 飼主以 `expectedAmount=3000`（G1）送單，Then 409 `PRICE_CHANGED`、payload `baseAmount=3600`，無新訂單；When 飼主以 `expectedAmount=3600` 重送（新 key），Then 201、`baseAmount=3600`。前端竄改 `expectedAmount=1` 且單價未變 → 409 `PRICE_CHANGED`。對應：BR-3、EX-10
- [ ] **AC-3.4 (冪等重送)**：Given 飼主以 key K 送單成功得到 `orderId=A`，When 24 小時內以 K 與相同內容重送，Then 201、body 的 `orderId=A`，資料庫訂單數不變、`ORDER_CREATED` log 仍 1 筆、保母通知仍 1 則；When 以 K 但不同日期、不同毛孩或不同問卷答案重送，Then 422 `IDEMPOTENCY_KEY_REUSED`；When 第一次仍在處理時以 K 重送，Then 409 `IDEMPOTENCY_IN_PROGRESS`。缺 `Idempotency-Key` → 400 `VALIDATION_FAILED`。對應：BR-15、EX-11~EX-14
- [ ] **AC-3.5 (新客戶填寫問卷)**：Given 飼主 O 與保母 S 沒有 `COMPLETED` 訂單，S 有啟用題目 Q1（必填單選，選項「是 / 否」）、Q2（必填簡答）、Q3（非必填詳答）與停用題目 Q4，When O 登入後進入預約頁，Then 問卷依序顯示 Q1、Q2、Q3（不含 Q4），Q1、Q2 標「必填」，Q1、Q2 未答時「送出預約」停用；When O 答 Q1「是」、Q2「每天餵兩次」、Q3 留空送出，Then 201、`questionnaireStatus=COMPLETED`，訂單答案快照有 3 筆（含題目文字、題型、答案，Q3 為空）。When S 之後修改 Q1 題目文字並停用 Q2，Then 該訂單仍顯示送出時的題目文字與答案。對應：BR-13、BR-19、US-9
- [ ] **AC-3.6 (問卷檢核失敗)**：Given AC-3.5 的 S 與 O，When 直接呼叫送單 API 缺 Q2 答案，Then 409 `QUESTIONNAIRE_CHANGED`（payload 含 Q1、Q2、Q3），無新訂單；Given O 開啟頁面後 S 新增必填題 Q5，When O 依原畫面送出，Then 409 `QUESTIONNAIRE_CHANGED`，畫面顯示「保母的問卷已更新，請確認後再送出」並重新載入問卷、保留 Q1–Q3 已填答案，補答 Q5 後送出 201。When Q1 答案為「不確定」（非選項）或 Q2 答案 201 字，Then 400 `VALIDATION_FAILED`（payload 帶該 `questionId`）。對應：BR-13、BR-22、EX-25、EX-26
- [ ] **AC-3.7 (熟客與無題目免填)**：Given 飼主 X 與保母 S 有 1 張 `COMPLETED` 訂單、S 有啟用題目，When X 進入預約頁，Then 不顯示問卷，送單 201、`questionnaireStatus=NOT_REQUIRED`、無答案快照；X 若仍在請求中帶答案，答案被忽略不存。Given 保母 T 的題目全部停用，When 新飼主 Y 送單，Then 不顯示問卷，`NOT_REQUIRED`。Given 飼主 Z 與 S 只有 `CANCELLED` 訂單，且 S 的舊門禁資料有對 Z 的 `NO_QUESTIONNAIRE` 規則，Then Z 仍須填問卷，送單後 `COMPLETED`。對應：BR-13、BR-20、BR-22
- [ ] **AC-3.8 (送單附帶：我的最愛與通知)**：Given 飼主的保母清單沒有保母 S，When 對 S 送單成功，Then 清單出現 S、S 沒有收到「被收藏」通知，S 有 1 則站內通知「新預約申請」（含訂單連結）與 1 筆 Email 投遞紀錄；When 再對 S 送第二張單，Then 清單中 S 仍只有 1 筆。Given 清單已有 50 位保母且不含保母 R，When 對 R 送單，Then 201、清單仍 50 位、不含 R、無提示。Given Resend 回 5xx，Then 訂單仍為 `PENDING`、站內通知存在、Email 由排程重試（最多 5 次）。對應：BR-14、BR-16、EX-16、EX-18

## 9. 影響範圍

| 面向 | 內容 |
|------|------|
| 資料 | `orders`：新增時寫 `status`、`base_amount`、`questionnaire_status`、`review_expires_at`（新欄位，現況未實作）、owner / sitter；`order_items`：每群組一列（方案、日期清單、每日次數、單價）；群組 × 毛孩綁定（新關聯，例 `order_item_pets`）；`order_answers`：題目 id、題目文字快照、題型快照、答案；`order_logs`：`ORDER_CREATED`；`idempotency_keys`。行程（`visits`，歸 SD-FULFILL）以 `PENDING` 建立，日期以 Asia/Taipei 日期儲存；我的最愛（歸 SD-SUPPLY）新增關聯；通知（`notifications`，歸 SD-NOTIFY）。讀取：KYC 狀態（SD-TRUST）、公開狀態 / 方案 / 問卷題目（SD-SUPPLY）、毛孩（SD-CARE）、門禁名單（SD-IDENTITY）、訂閱等級與鎖定（SD-BILLING）、當月 `CONFIRMED` 計數與熟客判斷（SD-ORDER） |
| 畫面 | 保母對外預約網頁（匿名 / OWNER，隱藏底部導覽，BR §5.5）：可預約狀態提示、方案清單、日期群組編輯（含毛孩勾選，預設全選）、方案選擇視窗、寵物種類提示、問卷步驟（需填時）、試算區、送出前確認區（評估期限）、送出；送出後導向飼主訂單詳情 |
| 通知 | 送出預約 → SITTER → 站內 + Email「新預約申請」 |
| 排程 | 無（`review_expires_at` 到期的 O4 由 PRD-027 處理） |
| 現況差異（2026-11-21 程式碼，只作證據） | 送單時已填問卷並驗必填（保留，改為依熟客 / 無題目判斷免填，並補題型快照）；ULTIMATE 門禁規則 `NO_QUESTIONNAIRE`（要移除）；毛孩欄位為選填（改為每群組必選 ≥ 1）；無 `review_expires_at`；無每月接單上限、訂閱鎖定、自我預約檢查；門禁未依方案等級判斷；未自動加入我的最愛；錯誤碼為舊格式（`MSG_DATA_INVALID_STATUS`、`PLAN_NOT_IN_RANGE`）；行程日期以 UTC 00:00 存放 |

## 10. 待釐清事項

| CQ | 問題 | 建議預設值 / 決定 | 狀態 |
|----|------|-----------|------|
| CQ-1 | 預約頁不可送單時的飼主端文案（訂閱鎖定、每月額度已滿、預約自己）沒有出處 | 訂閱鎖定與額度已滿都顯示「保母目前暫停接受新預約」（不向飼主揭露保母的訂閱 / 額度狀況）；預約自己顯示「這是您自己的預約頁，無法預約」（寫入 BR-23、EX-3、EX-6、EX-7） | 已決（使用者 2026-11-21） |
| CQ-2 | 送單時 `questionnaire_status` 的初始值 | 由 baseline CQ-58 取代：問卷改回送單時填寫；熟客或保母無啟用題目 → `NOT_REQUIRED`，其他 → 作答必填題後 `COMPLETED`（寫入 BR-13、§4.3、AC-3.5~3.7） | 已決（使用者 2026-11-21） |
| CQ-3 | 送單時是否要選「本次照護的毛孩」 | 每個群組必選 ≥ 1 隻本人未刪除的毛孩，畫面預設全選（baseline CQ-60）；群組未選 → 422 `PET_REQUIRED`，他人的毛孩 → 403 `FORBIDDEN`，不存在 → 404 `NOT_FOUND`；寵物種類提示改以所選毛孩比對（寫入 BR-17、§4.2、AC-2.4、AC-2.5） | 已決（使用者 2026-11-21） |
| CQ-4 | BR §4.5 的方案 `advance_booking_days`（置前天數）與 BR §5.4 的保母全域「開放預約期間」 | 本次都不做；開放日期只用方案層「可預約日期區間」（PRO+，SAAS）；最短提前天數不設（允許緊急單，CQ-10）（寫入 BR-8、§4.2、AC-2.3） | 已決（使用者 2026-11-21） |
| CQ-5 | 數量上限：每日服務次數範圍、單張訂單日期數 / 群組數上限、同一方案同一日期能否出現在兩個群組 | 每日次數 1–3；單張訂單 ≤ 10 個群組、日期總數 ≤ 60；同一方案同一日期只能出現一次（重複 → 400 `VALIDATION_FAILED`），不同方案可同日（寫入 BR-18、EX-14、AC-2.6） | 已決（使用者 2026-11-21） |
| CQ-6 | 自動加入我的最愛時，飼主清單已達 PRD-019 的 50 位上限 | 略過加入，訂單照常建立，不提示（寫入 BR-14、EX-18、AC-3.8） | 已決（使用者 2026-11-21） |
| CQ-7 | 送單前 / 後是否向飼主顯示評估期限（尤其緊急單） | 送出前確認區顯示「保母需於 {review_expires_at，Asia/Taipei} 前回覆，逾時將自動取消」；首日 < 送單時間 + 2h 時另加「距服務日很近，保母可能來不及回覆」（寫入 BR-21、§4.4、AC-3.2） | 已決（使用者 2026-11-21） |
| CQ-8 | 全域白名單（ULTIMATE）在預約頁的效果 | 全域白名單 = 所有方案都套用該名單：不在名單內的帳號（含匿名）看不到任何方案，頁面顯示「保母目前不開放預約」（同 EX-5）（寫入 BR-6、BR-7、§4.1、AC-1.5） | 已決（使用者 2026-11-21） |
| CQ-9 | 飼主開啟預約頁後保母修改題目（新增必填題、非必填改必填、停用題目），送出時如何判斷；baseline §3.7 沒有對應錯誤碼 | 以送單當下啟用中題目驗證：當下必填題缺答 → 409 `QUESTIONNAIRE_CHANGED`（模組碼，payload 帶目前題目清單），前端重新載入問卷並保留仍存在題目的答案；答案對應的題目已停用或不存在 → 忽略該答案 | Open |
| CQ-10 | 問卷答案格式與長度沒有出處（PRD-004 只定題目 200 字、選項 50 字）；免填時請求帶答案如何處理 | 單選須為選項之一；多選為選項子集、必填至少 1 項；簡答 ≤ 200 字、詳答 ≤ 1000 字；不符 → 400 `VALIDATION_FAILED`（payload 帶 `questionId`）；非必填未答的題目仍寫入快照、答案為空（保母可看出該題未答）；免填時忽略答案、不存 | Open |
| CQ-11 | BR §4.5 Step 2 寫「依保母設定的題目（依寵物類型等）動態產生」，但 PRD-004 的題目沒有寵物類型屬性 | 不依寵物類型過濾，顯示保母全部啟用中題目；若要依寵物類型出題，另開 PRD-004 修訂 | Open |
| CQ-12 | 行程日期只有下限（≥ 今天）沒有上限；FREE / BASIC 沒有可預約區間，可以預約好幾年後，`review_expires_at = 首日` 讓訂單長期卡在 `PENDING`（審查第 1 輪 #7） | 最後一個行程日期 ≤ 今天（Asia/Taipei）+ 365 天；超過 → 422 `INVALID_VISIT_DATE`（payload 帶日期）；日期選擇器停用超過範圍的日期 | Open |
| CQ-13 | `base_amount` 沒有上限（PRD-003 單價也沒有上限），與 PRD-006 BR-5 `total_amount ≤ 999,999` 衝突，會建出保母無法報價的訂單（審查第 1 輪 #6） | O1 後端重算 `base_amount` > 999,999 → 400 `VALIDATION_FAILED`（payload `field=baseAmount`、`max=999999`）；前端試算超過時停用送出並顯示「單筆預約金額不可超過 NT$999,999，請拆成多筆預約」 | Open |
| CQ-14 | ADMIN 停權保母時，預約頁走 PRD-017 KYC `SUSPENDED`（步驟 1.1，文案「尚未完成實名認證」）還是 baseline §2.2 的停權（403 `SUBSCRIPTION_LOCKED`）？（審查第 1 輪 #8） | baseline §2.2 的「ADMIN 停權」= KYC / 帳號停權：送單 403 `SUBSCRIPTION_LOCKED`，文案「保母目前暫停接受新預約」（不揭露停權）；步驟 1.1 只處理 `UNVERIFIED` / `PENDING_REVIEW` / `REJECTED`；PRD-017 同步修改 | Open |

## 11. 變更紀錄

| 日期 | 變更 | 原因 / 審查輪次 |
|------|------|----------------|
| 2026-11-21 | 依使用者決定改寫：問卷改回送單時填寫（新增 §4.3、BR-13、BR-19、BR-20、BR-22、EX-25、EX-26、AC-3.5~3.7），刪除「送單不填、填答見 PRD-006 Q1–Q3」；移除 `NO_QUESTIONNAIRE` 免填名單；每群組綁定毛孩、預設全選（BR-17、EX-22~24、EX-27、AC-2.4、AC-2.5）；CQ-1、3~8 採建議預設寫入正文（BR-8、BR-14、BR-18、BR-21、BR-23、AC-1.5、AC-2.6、AC-3.2、AC-3.8），CQ-2 由 baseline CQ-58 取代；AC 重新分配：合併原 AC-1.7 + 1.8、2.1 + 2.2、3.2 + 3.3、3.7 + 3.8，原 AC-3.6 移為 AC-2.8；仍 3 個子模組，AC 8 / 8 / 8 = 24 條；新增 CQ-9~11（Open） | 使用者決定 2026-11-21；baseline 第 2 版 CQ-58、CQ-59、CQ-60 |
| 2026-11-21 | 依新範本全文重寫，狀態 In Review；飼主撤回（O3 OWNER、O6）拆到 PRD-026；本卡 3 個子模組 23 條 AC | 全面重建；子模組 4 個 > 3，拆卡 |
| 2026-11-21 | 衝突 R1-#1：表頭改「歸屬 SD = SD-ORDER」，新增「引用基準」列出 baseline 段落 | `00-DOMAIN-BASELINE.prd.review.md` 第 1 輪 |
| 2026-11-21 | 衝突 R1-#2 + R2-#2：新增每月接單上限（BR-9、EX-7、AC-1.6），錯誤碼依 baseline §3.7 用 422 `MONTHLY_QUOTA_REACHED`，不用 409 `SITTER_QUOTA_EXCEEDED` | 第 1 輪 #2、第 2 輪 #2 |
| 2026-11-21 | 衝突 R1-#3：新增訂閱逾期 / 停權 → 403 `SUBSCRIPTION_LOCKED`，既有訂單不受影響（BR-10、EX-6、AC-1.5） | 第 1 輪 #3、baseline CQ-19 |
| 2026-11-21 | 衝突 R1-#4：刪除「訂單確認成交時鎖定單價」；本卡只寫後端重算 `base_amount` 與 `PRICE_CHANGED`，快照於報價時由 PRD-006 處理（BR-3、AC-3.4） | 第 1 輪 #4、baseline CQ-20、CQ-35 |
| 2026-11-21 | 衝突 R1-#5：冪等改為可驗 AC：同 key 同內容回同一 `orderId` 與 201，不建第二筆；另含 `IDEMPOTENCY_KEY_REUSED`、`IDEMPOTENCY_IN_PROGRESS`（BR-15、AC-3.5） | 第 1 輪 #5、baseline §3.6 |
| 2026-11-21 | 衝突 R1-#6：黑名單 PRO+、白名單 ULTIMATE、可預約區間 PRO+；等級不足時名單保留但不套用（BR-6、BR-8、AC-1.3、AC-1.4、AC-2.4） | 第 1 輪 #6、baseline §2.1 |
| 2026-11-21 | 衝突 R1-#7：刪除送單時的問卷步驟與「動態問卷」AC；送單不填問卷（BR §3.6、baseline §3.5），飼主填答由 PRD-006 Q2 定義，本卡不重複；BR §4.5 Step 2 與 §3.6 的矛盾以 baseline §3.5 為準（已被本表首列推翻：baseline CQ-58 改回送單時填） | 第 1 輪 #7 |
| 2026-11-21 | 衝突 R1-#8 作廢（R2-#1）：不加 `BOOKING_TOO_SOON`；改為首個行程日期 < 今天 → 422 `INVALID_VISIT_DATE`（AC-2.3），並加 `review_expires_at` 一般單 / 緊急單 AC（AC-3.2、AC-3.3）；`SELF_BOOKING_NOT_ALLOWED` 保留（AC-1.7） | 第 1 輪 #8、第 2 輪 #1、baseline CQ-10、CQ-25 |
| 2026-11-21 | 衝突 R1-#9：原 AC-1~5（含兩條 AC-3、「正確顯示」「與預期邏輯一致」）全部改寫為 3 個子模組 23 條可觀察 AC | 第 1 輪 #9 |
| 2026-11-21 | 刪除原「Step 0 強制登入」：依 baseline §1.1 匿名可瀏覽，送出時才要求登入；登入頻率限制移交 PRD-000 | baseline §1.1、§1.2 |
| 2026-11-21 | 新增：送單後自動加入我的最愛（BR §5.5）、角色不符 403 `ROLE_MISMATCH`、模組專屬錯誤碼 403 `SITTER_NOT_BOOKABLE`、422 `PLAN_NOT_BOOKABLE` | BR §5.5、baseline §1.1、§3.7 |
| 2026-05-09 | 建立 | |
