# 審查：PRD-005（第 1 輪，2026-11-21）

結論：需釐清

基準：`docs/sa/00-DOMAIN-BASELINE.md` 第 2 版（§7 CQ-1~67 視為已決）、`reports/rereview/00-sample-SD-007.md` 缺陷模式 P1–P5、`docs/requirements/`。AC 數 24（8 / 8 / 8）。

## 問題清單
| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 重要 | AC-3.1、AC-3.5、AC-3.7 | 沒有一條 AC 斷言 `questionnaire_exempt_reason`（baseline §3.5 / CQ-67）。這個欄位是 PRD-006 評估頁顯示「免填：熟客 / 保母無題目」的唯一資料來源，本卡寫錯 PRD-006 AC-1.3 就會失敗，卻沒有一條上游 AC 抓得到（P3） | AC-3.1 加 `questionnaire_exempt_reason = NO_ACTIVE_QUESTIONS`；AC-3.5 加 `= null`；AC-3.7 熟客案例加 `= REGULAR_OWNER`、保母 T 案例加 `= NO_ACTIVE_QUESTIONS`，並加「熟客且保母無啟用題目 → `REGULAR_OWNER`」一例 |
| 2 | 重要 | §4.4 步驟 3.2 vs 3.10、BR-18 vs BR-17、AC-2.5 / AC-2.6 | 空陣列兩種處理方式並存（P4）：「群組日期清單為空」→ 400 `VALIDATION_FAILED`（步驟 2），「群組毛孩清單為空」→ 422 `PET_REQUIRED`（步驟 10）。`petIds: []` 算格式錯誤還是業務錯誤沒寫清楚，步驟 2 先跑就會回 400，與 AC-2.5 衝突 | 二擇一並寫死：(a) 建議：刪掉 `PET_REQUIRED`，空毛孩清單併入步驟 2 → 400 `VALIDATION_FAILED`（payload `field=groups[i].petIds`），與 BR-18 一致；(b) 保留 `PET_REQUIRED` 就在步驟 2 註明「不檢查毛孩清單是否為空」 |
| 3 | 重要 | §4.4 步驟 3.12、BR-22、AC-3.6 | 必填多選題送空陣列、必填簡答只送空白字元，該回 409 `QUESTIONNAIRE_CHANGED`（「沒有答案」）還是 400 `VALIDATION_FAILED`（BR-22「必填時至少 1 項」）？兩條規則都能套用（P4） | 寫死：去除前後空白後為空字串、或多選為空陣列 = 「沒有答案」，必填題沒有答案 → 409 `QUESTIONNAIRE_CHANGED`；400 只用在選項不存在與長度超過。AC-3.6 加一例「Q2 只填空白 → 409」 |
| 4 | 重要 | CQ-9 建議預設 vs §4.4 步驟 3.12、EX-25、AC-3.6 | CQ-9 的預設寫了「答案對應的題目已停用或不存在 → 忽略該答案」，但正文、例外表、AC 都沒有。飼主開啟頁面後保母停用 Q2 是最常見的畫面過期情境，現在沒有 AC 定義結果（201 且快照不含 Q2？） | CQ-9 定案後寫入步驟 3.12 與 EX-25，AC-3.6 加一例：「O 開啟頁面後 S 停用 Q2，O 依原畫面送出 → 201，快照只有 Q1、Q3」 |
| 5 | 重要 | §4.4 步驟 3.12、EX-26 | 飼主開啟頁面後保母改了單選 / 多選的選項文字或題型（PRD-004 允許改題型），飼主依原畫面送出時舊選項已不存在 → 依現寫會回 400 `VALIDATION_FAILED`（題目旁錯誤），但原因其實是畫面過期，應該跟新增必填題一樣重新載入問卷 | 併入 CQ-9：答案不符合**當下**題目定義（選項不存在、題型不符）→ 409 `QUESTIONNAIRE_CHANGED`，payload 帶目前題目清單；400 只保留長度超過與重複 `questionId` |
| 6 | 重要 | BR-3、§4.4 步驟 3.13（跨 PRD-006 BR-5） | PRD-006 BR-5 規定 `total_amount ≤ 999,999`，超過 → 400，但本卡對 `base_amount` 沒有上限（PRD-003 方案單價也沒有上限）。例：單價 6,000 × 60 天 × 3 次 = 1,080,000，O1 會成功建立，保母卻無論如何都無法報價，只能婉拒或等 O4 | 新增 CQ-13：O1 後端重算的 `base_amount` > 999,999 → 拒絕送單 |
| 7 | 重要 | BR-4、§4.4 步驟 3.11 | 只有日期下限（≥ 今天），沒有上限。FREE / BASIC 的方案沒有可預約區間，飼主可以下 2030 年的單；`review_expires_at = 首日` 讓訂單在 `PENDING` 卡好幾年，月額度、O4 也都不會處理 | 新增 CQ-12：最後一個行程日期設上限 |
| 8 | 重要 | §4.1 步驟 1.1、BR-11、EX-4（跨 PRD-017、baseline §2.2） | 「停權」有兩種定義：PRD-017 的 KYC `SUSPENDED`（ADMIN 強制停權）走步驟 1.1 → 顯示「此保母尚未完成實名認證」，跟事實不符；baseline §2.2「被 ADMIN 停權 → 403 `SUBSCRIPTION_LOCKED`」。同一個 ADMIN 停權動作會回哪一個碼、哪個文案，沒有定義 | 新增 CQ-14 |
| 9 | 重要 | AC-3.8（跨 PRD-014、PRD-006 BR-19、PRD-027 BR-10） | 直接斷言「1 筆 Email 投遞紀錄」，但 Email 是否寄出依 PRD-014 通知偏好；PRD-006 / PRD-027 都寫「依 PRD-014 偏好」。Given 沒有固定偏好，E2E 結果取決於帳號預設值 | Given 加「保母的『新預約』類通知 Email 偏好為開啟」，或改成斷言「站內通知 1 則；Email 依偏好排入」 |
| 10 | 建議 | §4.1 步驟 1 vs §4.4 步驟 3 | 前端判斷順序把「預約自己」放最後（1.5），後端放在 KYC / 隱藏 / 鎖定之前（3.5）。保母本人在鎖定狀態開自己的頁面：畫面顯示「保母目前暫停接受新預約」，API 卻回 `SELF_BOOKING_NOT_ALLOWED` | 前後端改成同一個順序；建議把「預約自己」提到前端判斷的第 1 位 |
| 11 | 建議 | §4.4 步驟 3.9、EX-8 | `planId` 根本不存在的情況沒寫（404 還是 422？） | 一律 422 `PLAN_NOT_BOOKABLE`（不揭露是否存在），寫進步驟 3.9 |
| 12 | 建議 | AC-1.3、AC-1.5 | 「兩者 response body 相同」：body 若含 traceId / timestamp 就不可能相同 | 改成「`code`、`reason` 與 payload 欄位相同（不含 traceId / timestamp）」 |
| 13 | 建議 | BR-3、§4.4 步驟 2、EX-10 vs PRD-006 | 畫面金額欄位本卡叫 `expectedAmount`、PRD-006 叫 `expectedBaseAmount`；`PRICE_CHANGED` payload 本卡用 `baseAmount`、PRD-006 用 `base_amount`。比對的是同一個值 | 統一成 `expectedBaseAmount` 與 camelCase payload `baseAmount`（JSON 命名以 SD-GLOBAL-SPEC 為準） |
| 14 | 建議 | §4.4 步驟 4.2 vs PRD-006 BR-7 / BR-8 | O1 在群組明細寫入「單價」，O2 以當下單價重算並快照。報價後 `order_items` 的單價是舊值還是快照值沒寫，SD 會做出兩個來源（P5） | 註明 O1 的單價只用來算 `base_amount`，報價時由 PRD-006 覆寫（或改名為 `unit_price_at_booking`，另存快照欄位）；交給 SD-ORDER 決定，但 PRD 要寫出「報價後以快照為準」 |
| 15 | 建議 | §4.4 步驟 4.7、BR-14 | 加入我的最愛在 O1 同一交易內，失敗（例如唯一鍵衝突）會讓整筆訂單回滾，與「略過、不影響訂單」的意圖不一致 | 寫明以冪等插入（已存在 / 已達上限都略過）；或改成 commit 後執行、失敗只記 log |
| 16 | 建議 | AC-3.2 | 第三例（首日 = 送單當天）是緊急單，但沒斷言出現「距服務日很近」提示 | 補上斷言 |
| 17 | 建議 | AC-1.2 | KYC 狀態寫 `PENDING`；PRD-017 的狀態值是 `UNVERIFIED` / `PENDING_REVIEW` / `REJECTED` | 改成 `PENDING_REVIEW`，並在同一條加一例 `UNVERIFIED` |
| 18 | 建議 | AC-1.4、AC-1.7、AC-3.8 | 單條 AC 塞了多個獨立情境（AC-1.4 三個等級共 7 個斷言；AC-3.8 把我的最愛、通知、Resend 失敗放在一起）。子模組已到 8 條上限，再拆就要拆卡 | 不拆，但每個情境各自寫完整 Given/When/Then，TS 以「AC-x.y-情境 n」逐列對應 |
| 19 | 建議 | 全卡 | 沒有任何 testid（送出按鈕、問卷、毛孩勾選、確認區）。PRD-007 已採 testid；E2E 過去因 testid 吃過虧 | 補關鍵 testid：`btn-submit-booking`、`booking-group`、`pet-checkbox-{petId}`、`questionnaire-q-{questionId}`、`booking-review-expires-at`、`booking-unavailable-message` |
| 20 | 建議 | §4.4 步驟 3（跨 baseline §3.2 O1） | 模組碼 `SITTER_NOT_BOOKABLE`、`PLAN_NOT_BOOKABLE`、`PET_REQUIRED`、`QUESTIONNAIRE_CHANGED` 的命名與 §3.7 沒有衝突，也只有 O1 會用到，不需要提升到 §3.7；但 baseline O1 的「不符時」欄沒列 KYC / 門禁 / 問卷 / `PRICE_CHANGED`，讀 baseline 的人會以為 O1 只有 4 個錯誤碼 | baseline O1 的「不符時」欄補一句「模組碼見 PRD-005 §5」。`QUESTIONNAIRE_CHANGED` 也用在「沒改題目、只是缺答」（AC-3.6 第一例，竄改才會發生），在 SD 錯誤碼表註明這個語意 |

### CQ-9~11 評估（Open，不代為定案）
- **CQ-9**：方向合理（以送單當下題目驗證、409 + 目前題目、保留已填答案）。要補三點才能寫成 AC：(1) 停用 / 刪除題目的答案忽略要寫進正文與 AC（#4）；(2) 選項 / 題型被改也應該走 409，不是 400（#5）；(3) 題目文字改了但答案仍然有效時，快照存的是送單當下的文字，不是飼主當時看到的文字，建議接受並寫明。
- **CQ-10**：上限值合理（簡答 200、詳答 1000 與 PRD-004 題目 200 字同一量級）。要補：去除前後空白後才計長度與判斷是否為空（#3）、長度以 Unicode 字元計、同一 `questionId` 出現兩次 → 400。「非必填未答存空答案」可以保留，保母看得出哪題沒答。
- **CQ-11**：同意不依寵物類型過濾。PRD-004 沒有寵物類型屬性，要過濾得先改 PRD-004 和題目資料模型，不該在本卡偷做。

## 待使用者回答
| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-12 | 行程日期沒有上限，FREE / BASIC 可以預約好幾年後，`PENDING` 會一直掛著 | 最後一個行程日期 ≤ 今天（Asia/Taipei）+ 365 天；超過 → 422 `INVALID_VISIT_DATE`（payload 帶日期）；日期選擇器停用超過範圍的日期 |
| CQ-13 | `base_amount` 沒有上限，與 PRD-006 `total_amount ≤ 999,999` 衝突，會產生無法報價的訂單 | O1 後端重算 `base_amount` > 999,999 → 400 `VALIDATION_FAILED`（payload `field=baseAmount`、`max=999999`），前端試算超過時停用送出並顯示「單筆預約金額不可超過 NT$999,999，請拆成多筆預約」 |
| CQ-14 | ADMIN 停權保母時，預約頁走 KYC `SUSPENDED`（文案「尚未完成實名認證」）還是 baseline 的訂閱停權（403 `SUBSCRIPTION_LOCKED`）？ | baseline §2.2 的「ADMIN 停權」就是 KYC / 帳號停權：送單 403 `SUBSCRIPTION_LOCKED`、文案「保母目前暫停接受新預約」（不揭露停權）；步驟 1.1 只處理 `UNVERIFIED` / `PENDING_REVIEW` / `REJECTED`；PRD-017 同步改寫 |
