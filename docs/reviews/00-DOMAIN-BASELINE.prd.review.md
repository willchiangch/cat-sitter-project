# 審查：BASELINE（00-DOMAIN-BASELINE，第 1 輪，2026-11-21）

結論：需釐清

審查範圍：`docs/sa/00-DOMAIN-BASELINE.md`。對照 BR、SAAS、PRD-005/006/007/008/009/012/013/016/022、PRD-020（爭議）、SD-GLOBAL-SPEC、00-SD-PLAN §3–4、`reports/rereview/00-sample-SD-007.md` 缺陷模式 P1–P5。§7 CQ-1~8 的決定本身不列為問題。

摘要：CQ-1（主狀態與子流程拆開）方向正確，但拆開後子流程只畫了 happy path：多個狀態沒有離開路徑（付款逾期遇 SUBMITTED、補款不付、退款不確認、行程全被系統關閉的訂單），時間規則的起算點「首個行程開始時間」在資料模型裡不存在（行程只有日期）。這些不補，SD-ORDER / SD-FULFILL 會各自發明規則，正是這次重建要消除的問題。

## 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 阻擋 | §5 `PAYMENT_REMINDER_HOURS` / `PAYMENT_TIMEOUT_HOURS` 起算點 | 起算點寫「首個行程開始時間」，但行程只有日期（PRD-005「系統只提供日期」、BR §5.4.1「以天為單位」），沒有開始時間。T−24h 無法計算，AC 寫不出來 | 定義為「首個行程日期 00:00 Asia/Taipei」（CQ-9），並寫成可觀察範例：首個行程 11/25 → 提醒 11/23 00:00、逾期 11/24 00:00 |
| 2 | 阻擋 | §5 × §3.1 `PENDING → AWAITING_PAYMENT` | 當天 / 隔天的預約、或 PENDING 擱置很久才報價時，報價當下已過付款期限：要嘛立刻被排程取消，要嘛永遠不會逾期。提醒點（T−48h）已過時是否補發也沒寫 | CQ-10：送單時首個行程日期 ≥ 今天 +2 天，否則 422；報價時已過期限回 409；報價時已過提醒點則隨報價通知一併提醒、不另發 |
| 3 | 阻擋 | §3.2 付款狀態機 `UNPAID → VOID` | 逾期 / 訂單取消只定義 UNPAID → VOID。飼主已上傳憑證（SUBMITTED）但保母遲遲不核對，到期時：要取消（飼主錢已匯出）還是不取消（訂單無限期卡在評估中）？SUBMITTED 時訂單被取消，payment 落在哪個狀態也沒寫 | CQ-11；並補 `SUBMITTED → VOID` 轉移（或明寫不允許） |
| 4 | 阻擋 | §3.1 `CONFIRMED → IN_PROGRESS: 首個行程開始` | 觸發者沒寫（保母按開始？排程？），行程被系統關閉（CLOSED_BY_SYSTEM）算不算「開始」也沒寫。保母一次都沒開始、所有行程 72h 後被系統關閉時，訂單永遠停在 CONFIRMED；自動結案只從 IN_PROGRESS 出發，會卡住且吃保母月額度 | CQ-28：任一行程離開 PENDING（含 CLOSED_BY_SYSTEM）→ 訂單在同一交易轉 IN_PROGRESS；寫 order_logs |
| 5 | 阻擋 | §3.1 `IN_PROGRESS → CANCELLED`、§3.3「新內容為無任何預約項目 → CANCELLED」 | 進行中訂單已有 DONE / CLOSED_BY_SYSTEM 行程，PRD-016 規定已完成行程不能刪，所以「無任何預約項目」在 IN_PROGRESS 永遠不成立，這條轉移不可能觸發。就算觸發，已完成行程對應的錢（已付 − 已退 > 0）落在 CANCELLED，§4「保母收入只算 COMPLETED」會把保母實際賺到的錢算成 0 | CQ-14 |
| 6 | 阻擋 | §3.3 變更狀態機 | 子流程沒有任何逾時或撤回路徑：REQUESTED / QUOTED 對方不回、AWAITING_SUPPLEMENT 飼主不付、AWAITING_REFUND 飼主不按確認，都會永遠停住；而 §3.3 又規定有未結束請求時暫停自動結案，結果訂單永遠不會結案。發起者能否撤回自己的請求也沒寫 | CQ-15 |
| 7 | 阻擋 | §3.3 `AWAITING_REFUND → SETTLED`、§4「已退總額」 | 退款只有「OWNER 確認收到退款」一步，缺 PRD-016 規定的「保母上傳退款憑證、上傳前飼主不能確認」。§1.2 提到退款憑證，但沒有實體承接；§4 保母收入用到的「已退總額」沒有資料來源（BR §7 `payment_status` 有 REFUNDED）。重蹈「憑證欄位散在兩處」 | 在 §3.2 明定退款也是 payment（`direction = REFUND`，或另一個 `refunds` 實體），狀態 `UNPAID → SUBMITTED（保母上傳）→ VERIFIED（飼主確認）`；AWAITING_REFUND → SETTLED 改成「退款 payment VERIFIED」；已退總額 = Σ REFUND 且 VERIFIED |
| 8 | 阻擋 | §2.1 末段 vs §4「快照」 | §2.1 寫「訂單成立時」（CONFIRMED）快照媒體額度與保留期限；§4 寫「報價時」快照同一組欄位。PRD-013、SD-GLOBAL-SPEC §4.2 寫 CONFIRMED。報價到核款之間保母若升降級，兩種寫法結果不同。屬 P4 | CQ-20 |
| 9 | 阻擋 | §3.3「SETTLED 時套用新內容」×「行程照常執行」 | 要刪的行程在協商中照常執行：補款等 3 天、退款等匯款期間，日期到了保母可以開始那個行程，或 72h 後被系統關閉；SETTLED 時要刪的行程已不是 PENDING，差額卻是依「會刪掉」算的。新增的行程在 SETTLED 前不存在，日期到了也無法執行。屬 P5（協商中內容被改掉） | CQ-16 |
| 10 | 重要 | §3.2「駁回時憑證紀錄保留（狀態 REJECTED 的歷史列）」 | 狀態圖沒有 REJECTED，駁回是 `SUBMITTED → UNPAID`。「REJECTED 歷史列」指的是 payment 本身還是另一個憑證嘗試實體，沒講清楚；SD 會一邊做 payment.status = REJECTED、一邊做 UNPAID | 明定一個 payment 可以有多次憑證送出（`payment_attempts` 或 `payment_proofs`），嘗試有自己的狀態 `SUBMITTED / VERIFIED / REJECTED`；payment 狀態只看最新一次嘗試 |
| 11 | 重要 | §3.1 `AWAITING_PAYMENT → CANCELLED: OWNER 撤回` | 沒有前置條件。PRD-006 AC-7（2026-10-25 已實作）限「尚未上傳憑證」，baseline 沒帶過來，等於允許飼主上傳憑證後再撤回。保母在 AWAITING_PAYMENT 能不能取消 / 改價也沒寫 | 撤回前置條件寫成「首次 payment = UNPAID」，否則 409 `ORDER_STATE_CONFLICT`；保母能否取消見 CQ-13；改價明寫不允許 |
| 12 | 重要 | §3.1 PENDING | 保母一直不報價也不婉拒：PENDING 沒有任何離開條件，首個行程日期過了訂單還在評估中 | CQ-12 |
| 13 | 重要 | §3.1 PENDING（BR §3.4、§3.6） | BR 的 `questionnaire_status`（NOT_REQUIRED / PENDING_CLIENT / COMPLETED）是 PENDING 內的子狀態，baseline 只定義了「熟客」（CQ-6）卻沒定義子狀態：保母在 PENDING_CLIENT 時能否直接報價或婉拒、飼主未填問卷時保母能否報價，都沒寫 | §3.1 加問卷子狀態表（出處 BR §3.4），寫清楚 PENDING_CLIENT 時「報價」可不可以 |
| 14 | 重要 | §3.3 × §5 `DISPUTE_WINDOW_HOURS` / `AUTO_COMPLETE_HOURS` | 變更與爭議的互斥規則沒寫：有未結束變更時能否回報問題？DISPUTED 時能否發起變更（§3.3 只說 CONFIRMED / IN_PROGRESS 可發起，DISPUTED 不行，但沒寫錯誤碼）？變更在 48h 視窗內結束後，自動結案的 48h 從哪裡重算？爭議視窗會不會在協商中過期？ | CQ-17 |
| 15 | 重要 | §5 全部時間參數、§5 末段排程 | 沒寫排程頻率與邊界。PRD-009 寫「每日執行」，那 48h 自動結案實際可能 72h 才發生，T−24h 逾期可能拖到 T−0；`now == 期限` 時算到期還是沒到期也沒寫。爭議視窗是以時間判斷還是看排程跑了沒有也沒寫 | 加一列：排程每 15 分鐘（或每小時）執行，到期判斷 `now ≥ 期限`；使用者端操作一律以時間判斷（例：48h 過了就算排程還沒跑也不能回報問題，回 409）。AC 才能寫成「期限後 ≤ 1 個排程週期內轉移」 |
| 16 | 重要 | §3.1 `IN_PROGRESS → COMPLETED: OWNER 確認結案` | 手動結案沒寫前置條件：所有行程 DONE / CLOSED_BY_SYSTEM、沒有未結束變更、零行程不可結案（PRD-009 A.1 / B.3），以及「飼主剛結案就把保母 24h 內的日誌補送權砍掉」（PRD-022 對 COMPLETED 一律拒絕） | 前置條件寫進轉移表；日誌衝突見 CQ-27 |
| 17 | 重要 | §1.2 訂單、憑證可讀範圍；§3.1 `DISPUTED → COMPLETED: ADMIN 調解結案` | §1.2 訂單與憑證只有雙方可讀，ADMIN 調解卻要看訂單、日誌與付款憑證（PRD-020 C）。PRD-009 E / PRD-020 C 的「調整最終訂單金額」在 baseline 沒有對應機制，§4 保母收入公式（已付 − 已退）不知道調解結果 | §1.2 加「ADMIN：DISPUTED 訂單唯讀（含憑證、日誌）」；金額處理見 CQ-18 |
| 18 | 重要 | §2.1 末段「訂閱逾期 → 保母端除我的外全部鎖定」 | (a) 鎖定期間既有訂單怎麼辦：保母不能核款 → 付款逾期取消；不能執行行程 → 系統關閉；不能回應變更 → 卡住。飼主是受害者。(b) PRD-012 C.1 寫「到期當日 23:59 回落 FREE」，與 BR「隔天 00:00 鎖定」是兩套規則，baseline 只寫一套、沒處理另一套。(c) ADMIN 停權（PRD-017 SUSPENDED）對進行中訂單的影響同樣沒定義 | CQ-19 |
| 19 | 重要 | §2.1 每月接單上限、CQ-5 | CQ-5 定了計數時點與上限行為，但沒定：CONFIRMED 後取消要不要返還（PRD-012 有「當月返還、跨月不返」的規則）、月中升降級用哪個上限、達上限時預約 API 的錯誤碼 | CQ-21；錯誤碼見 #23 |
| 20 | 重要 | §4「變更差額」 | 「已付總額」沒寫是否扣掉已退。第二次變更時，若用毛額，第一次的退款會再退一次。退款上限（不得超過已付淨額）、新總額下限（≥ 0）沒寫。FREE / BASIC 在變更時能否自己輸入新總額（等於繞過「手動調價限 PRO」）沒寫 | 差額 = 新總額 −（Σ 收款 VERIFIED − Σ 退款 VERIFIED）；新總額 ≥ 0；FREE / BASIC 見 CQ-22 |
| 21 | 重要 | §1.2 保母收款帳號（P5，抽樣 #11） | 飼主看到的帳號是即時讀取；保母（或盜用者）在飼主匯款前後改帳號，飼主會匯到錯的帳戶，訂單沒有證據證明當時顯示的是哪個帳號。補款階段（AWAITING_SUPPLEMENT）可否讀帳號也沒列 | CQ-23；§1.2 可讀範圍改成「該訂單有 UNPAID / SUBMITTED payment 時」 |
| 22 | 重要 | §3.4 冪等 | 只定義「同 key 重送 → 回放」。沒定義：同 key 不同 payload、同 key 第一次還在處理中（並行）、key 的範圍（全域？每使用者？每端點？）。抽樣 S5 就是這裡出事 | 補三列：同 key 不同 payload → 422 `IDEMPOTENCY_KEY_REUSED`；第一次處理中 → 409 `IDEMPOTENCY_IN_PROGRESS`；key 範圍 =（呼叫者, 端點, key） |
| 23 | 重要 | §3.4 錯誤碼 | 只有三個通用碼（P2）。baseline 自己定義的規則沒有錯誤碼，下游 PRD 會各取各的名字（PRD-022 已用 `AUTH_PLAN_LIMIT`、`IDEMPOTENCY_CONFLICT`）：方案不足、媒體額度超過、月額度已滿、訂閱鎖定、已有未結束變更、付款期限已過、變更不在可發起狀態 | 加跨模組錯誤碼表，例：403 `PLAN_REQUIRED`、403 `PLAN_LIMIT_EXCEEDED`、409 `SITTER_QUOTA_EXCEEDED`、403 `SUBSCRIPTION_LOCKED`、409 `MODIFICATION_IN_PROGRESS`、409 `PAYMENT_DEADLINE_PASSED` |
| 24 | 重要 | §5 `VISIT_ZOMBIE_HOURS`、`AUTO_COMPLETE_HOURS`、`DISPUTE_WINDOW_HOURS` | 出處都是 PRD-009，但 PRD-009 是 Draft。依 doc-conventions，出處只能是 BR / 使用者決定 / Approved PRD。BR §3.6 只有 `CONFIRMED_SERVICE_DAYS`，沒有數值；72h、爭議視窗在 BR 完全沒有 | CQ-24（請使用者確認現值） |
| 25 | 重要 | §6 行程 | (a) 沒寫誰讓行程變 DONE：PRD-008 是「送出報告 → DONE」，PRD-022（Approved）是「行程 DONE 後才能送日誌」，兩者互為前提。(b)「隨訂單 CANCELLED」不是狀態值，變更刪除的行程是硬刪還是 CANCELLED 也沒寫。(c) BR §3.5 用 SCHEDULED，baseline 用 PENDING，沒註明取代 | 列行程狀態表：`PENDING → IN_PROGRESS`（保母開始）、`IN_PROGRESS → DONE`（保母結束行程，與日誌送出無關，依 PRD-022）、`PENDING → CLOSED_BY_SYSTEM`、`PENDING → CANCELLED`（訂單取消或變更刪除，軟刪）；註明 SCHEDULED 改名為 PENDING |
| 26 | 重要 | §1.1 雙 profile 帳號 | 同一帳號可以用 OWNER 身分預約自己的 SITTER 頁面，訂單雙方是同一人，「雙方確認」形同虛設（可以自己刷月額度、刷熟客資格） | CQ-25 |
| 27 | 重要 | §1.1「同一時間只以一個角色操作」× §1.2 | 沒寫 token 的 `currentRole` 與訂單角色不一致時怎麼處理（帳號是訂單 X 的 sitter，但目前用 OWNER 角色呼叫保母端點）。是 403，還是以訂單歸屬放行 | 明寫：端點要求的角色必須等於 token `currentRole`，否則 403 `FORBIDDEN`，即使該帳號是此訂單另一方 |
| 28 | 建議 | §4 報價 | `total_amount` 可以等於 0（全額折扣），0 元 payment 怎麼付、怎麼核對沒寫 | CQ-26 |
| 29 | 建議 | §2.1 方案矩陣 | (a) SAAS §1 / §2 的「AI 報告助理」（PRO）、「定期扣款」（ULTIMATE）、「可設定額滿日期」（ULTIMATE，已延後）沒列，應列為「不做 / 延後」，避免下游以為是遺漏。(b) PRD-012 有而 SAAS 沒有的分級（客製問卷、信任圈、行事曆同步、BASIC 線上支付）baseline 沒表態，應明寫「不分級，全方案相同」或「依 PRD-012」 | 矩陣加「不做 / 延後」列，並加註「未列出的功能不分級」 |
| 30 | 建議 | §2.1 媒體保留、§1.1 | 與 BR 衝突但沒有標出取代關係（P4）：BR §5.4.2「上傳後保留 60 天」被 SAAS 依方案、自結案日起取代；BR §5.4「開放預約期間」寫成一般功能，SAAS 限 PRO 以上 | 在出處欄註明「取代 BR §5.4.2」「SAAS 優先於 BR §5.4」 |
| 31 | 建議 | §5 媒體保留 | 起算點「訂單結案日」：CANCELLED 但已有媒體的訂單（進行中取消）從哪天算？「日」是 Asia/Taipei 日期還是 UTC 時刻？「永久」到帳號註銷為止（PRD-013）沒寫 | 起算點改為「訂單進入 COMPLETED 或 CANCELLED 的 Asia/Taipei 日期」，到期 = 起算日 + N 天的隔日 00:00；永久 = 至帳號註銷 |
| 32 | 建議 | §3.1 CANCELLED | `cancelledBy` 的值域沒列，變更造成的取消要記發起者還是同意者也沒寫 | 值域 `OWNER / SITTER / SYSTEM`；變更造成的取消記「變更請求發起者」，另記 `cancelReason = MODIFICATION` |
| 33 | 建議 | §3.4 | 鎖、稽核、通知、外部 I/O 規則沒有出處欄（實際出自抽樣報告 #2–#6 與 SD-GLOBAL-SPEC）。§3.4「不另用 @Version」與 SD-GLOBAL-SPEC §2.3「實體更新使用 @Version」字面衝突 | 加出處欄；SD-GLOBAL-SPEC §2.3 改為「訂單與子流程狀態轉移以列鎖為準，其他實體可用 @Version」 |
| 34 | 建議 | §1.2 毛孩 | 「有過任何狀態訂單的 sitter」可讀：被婉拒、飼主撤回的訂單，保母永久可讀飼主毛孩資料（含醫療備註） | 可讀改為「PENDING 起至訂單結束後 N 天」，或「曾有非 CANCELLED 訂單」；這屬於 PRD-021 範圍時至少標出 |
| 35 | 建議 | §2.1 快照 | 降級不影響進行中訂單（有快照保護），但升級時只有保留期限可以展延，媒體額度不行，這種不對稱沒寫理由。保母升級到 PRO 後想在進行中訂單上傳影片會被擋 | 明寫「升級只展延保留期限，媒體額度依快照」，或改為媒體額度取 max(快照, 當前) |
| 36 | 建議 | §1.2 / §3.3 線下退款 | 線下退款要匯到飼主的帳戶，baseline 沒有飼主收款資訊的歸屬與可讀規則（保母要怎麼知道匯到哪） | 加「退款 payment 由飼主提供收款資訊，保母在 AWAITING_REFUND 期間可讀」，或明寫在系統外處理 |

## 與訂單相關 PRD 的衝突（重寫 PRD 時必須改）

| # | PRD | 段落 | 現在寫的 | baseline 規定 | 要怎麼改 |
|---|-----|------|---------|--------------|---------|
| 1 | PRD-005 | 表頭 | 對應 SD-005 | 00-SD-PLAN §0：歸屬 SD-ORDER | 表頭「歸屬 SD」改 SD-ORDER，相依共用設計加 BASELINE |
| 2 | PRD-005 | 例外處理 | 沒有「本月額度已滿」 | §2.1 / CQ-5：達上限時公開頁不可預約 | 加例外與 AC：預約 API 回 409 `SITTER_QUOTA_EXCEEDED`，公開頁顯示固定文案 |
| 3 | PRD-005 | 例外處理 | 只擋 KYC 未完成 | §2.1：訂閱逾期鎖定保母端（CQ-19 待決） | 依 CQ-19 加「保母訂閱鎖定 / 停權時公開頁不可預約」 |
| 4 | PRD-005 | 業務規則「單價快照」 | 「一旦訂單確認成交」鎖定單價 | §4：報價時快照（CQ-20 待決） | 依 CQ-20 統一時點，PRD-005 只寫「送單時後端重算 base_amount，不信任前端」 |
| 5 | PRD-005 | 例外「提交時網路中斷」 | 「確保後端冪等防止重複建立」 | §3.4：同 key 回放第一次結果 | AC 寫成「同 Idempotency-Key 重送回同一 orderId 與 201，不建第二筆」 |
| 6 | PRD-005 | Step 1 門禁 | 黑白名單沒有方案分級 | §2.1：黑名單 PRO+、白名單 ULTIMATE | 寫明保母低於該方案時規則不生效（或依 PRD-012 降級下架） |
| 7 | PRD-005 | Step 2 / AC-3（動態問卷） | Step 2 是試算；又說「此階段尚未填問卷」，但 AC-3 驗 Step 2 問卷 | BR §3.6：送單不填問卷；BR §4.5 又有 Step 2 問卷（BR 自相矛盾） | 刪掉送單時的問卷 AC，改由 PRD-006 依 BASELINE 問卷子狀態（#13）處理；BR 矛盾請使用者確認以 §3.6 為準 |
| 8 | PRD-005 | 新增 | 無 | CQ-10：首個行程最早日期；CQ-25：不得預約自己 | 加兩條 AC（422 `BOOKING_TOO_SOON`、422 `SELF_BOOKING_NOT_ALLOWED`） |
| 9 | PRD-005 | AC-1~5 | 「正確顯示」「與預期邏輯一致」；兩條 AC-3 | P1 | 全部改成可觀察 AC 並重新編號 |
| 10 | PRD-006 | C.2、AC-3、AC-5、例外 | `PENDING_PAYMENT` | §3.1：`AWAITING_PAYMENT` | 全文換名 |
| 11 | PRD-006 | 例外「飼主在審核中取消」 | 可撤條件含「不是訂單變更後的補款階段（補款代表首次已付款）」 | §3.1–3.3：補款是變更子流程，訂單停在 CONFIRMED / IN_PROGRESS，不會回到待付款 | 刪掉補款階段的條件，改為「首次 payment = UNPAID」 |
| 12 | PRD-006 | 例外「已付款後（PAID 以後）」 | `PAID` 主狀態 | §3.2：PAID 不存在，改看 payment SUBMITTED / VERIFIED | 改成「首次 payment 為 SUBMITTED 或 VERIFIED、或訂單非 PENDING / AWAITING_PAYMENT → 409 `ORDER_STATE_CONFLICT`」 |
| 13 | PRD-006 | 業務規則「報價有效期」 | 「（未來擴充）報價後 24 小時未付自動失效」 | §5 / CQ-2：首個行程前 24h 逾期取消，這次就做 | 刪掉這條，引用 BASELINE §5；報價時已過期限的處理依 CQ-10 |
| 14 | PRD-006 | 業務規則「金額計算公式」、B.3 | 最終 = 原始 + 調整金額（單一帶正負號的欄位） | §4：`base + surcharge − discount`，調整需附原因 | 改公式與欄位；AC 驗三個欄位 |
| 15 | PRD-006 | 業務規則「金額精準度」 | `BigDecimal(18,4)` | §4：TWD 整數，計算中途 BigDecimal | 改為整數；NFR-009「BigDecimal(18,4)」一併修 |
| 16 | PRD-006 | A.2 問卷決策 | 「白名單熟客」免填問卷、「加入免填清單」 | CQ-6：熟客 = 曾有 COMPLETED 訂單；白名單是 ULTIMATE 門禁（§2.1），兩者無關 | 熟客判定改依 CQ-6；「加入免填清單」沒有出處，刪除或列入待釐清 |
| 17 | PRD-006 | C.2「鎖定訂單金額」 | 鎖定行程、方案、數量、報價 | §4 快照：單價、方案名稱、媒體額度、保留期限 | 補媒體額度與保留期限（時點依 CQ-20） |
| 18 | PRD-006 | 例外「撤單與接單同時發生」 | 「訂單版本已過期需重新整理」 | §3.4：列鎖，不用 @Version；後到者 409 `ORDER_STATE_CONFLICT` | 改寫成錯誤碼，拿掉「版本」字眼 |
| 19 | PRD-006 | 新增 | 保母在 AWAITING_PAYMENT 的操作沒寫 | CQ-13 | 依 CQ-13 加 AC |
| 20 | PRD-007 | B.4、C.3、例外、AC-1~3 | `PENDING_PAYMENT → PAID → CONFIRMED`；駁回退回 `PENDING_PAYMENT` | §3.2：上傳 = payment `UNPAID → SUBMITTED`，訂單停在 AWAITING_PAYMENT；核款 = `VERIFIED` → 訂單 CONFIRMED；駁回 = `SUBMITTED → UNPAID` 並保留嘗試紀錄 | 全部改寫成 payment 狀態 + 訂單狀態兩個斷言 |
| 21 | PRD-007 | 例外「逾期未付」 | 「（未來擴充）」 | §5 / CQ-2：提醒與逾期這次就做 | 加子模組「付款期限」：提醒、逾期取消、SUBMITTED 的處理（CQ-11） |
| 22 | PRD-007 | 影響範圍「在訂單關聯中新增憑證欄位」 | 憑證放 orders | §3.2：每次應付款一筆 payment，憑證不放 orders / modification_requests | 改成 payment 實體；補款也走同一流程，PRD-007 要寫補款（P3） |
| 23 | PRD-007 | A.2 收款帳號 | 顯示保母預設帳號（即時） | §1.2 可讀範圍；CQ-23 快照 | 依 CQ-23 改；加越權 AC（非該訂單 owner 讀帳號 → 403） |
| 24 | PRD-007 | 業務規則「只有 CONFIRMED 才開放 PRD-008」 | 只有 CONFIRMED | §3.1：CONFIRMED / IN_PROGRESS 都可以執行 | 改為「CONFIRMED 或 IN_PROGRESS」 |
| 25 | PRD-007 | 新增 | 沒寫冪等 | §3.4：上傳憑證帶 Idempotency-Key，回放 | 加 AC |
| 26 | PRD-008 | C.3 | 行程 `PENDING → DONE`，由「送出報告」觸發 | §6：`PENDING → IN_PROGRESS → DONE`；PRD-022（Approved）：DONE 後才能送日誌 | 拆成「開始行程」「結束行程」兩個動作；報告 / 日誌送出依 PRD-022 |
| 27 | PRD-008 | 新增 | 沒寫訂單主狀態連動 | §3.1：首個行程開始 → 訂單 IN_PROGRESS（CQ-28） | 加 AC：第一個行程開始時訂單 `CONFIRMED → IN_PROGRESS`，寫 order_logs |
| 28 | PRD-008 | 例外「服務日結束仍未提交」 | 翌日清晨標記「未執行」 | §5 `VISIT_ZOMBIE_HOURS`：行程日期 + 72h → `CLOSED_BY_SYSTEM` | 改用 baseline 參數與狀態值 |
| 29 | PRD-008 | C.1、AC-2 | 依「方案等級」限制（當下） | §2.1：只依訂閱等級（CQ-4），且依訂單快照 | 改為「依訂單快照的媒體額度」，錯誤碼用 baseline 錯誤碼表（#23） |
| 30 | PRD-008 | 業務規則「影片 720p / 50MB」 | 50MB、720p | PRD-022（Approved）：15–30 秒、< 50MB、MP4 / MOV | 媒體規格只留在 PRD-022，PRD-008 引用 |
| 31 | PRD-008 | B.2 | 顯示 PRD-006 制定的「執行契約任務清單」 | PRD-006 AC-6：訂單不含工項清單 | 改引用 PRD-021 工項來源 |
| 32 | PRD-009 | A.3 | `CONFIRMED → COMPLETED` | §3.1：`IN_PROGRESS → COMPLETED` | 改起點狀態 |
| 33 | PRD-009 | B.1 | 自動結案條件「狀態為 CONFIRMED」、「所有行程 DONE」 | §3.1 / §5：IN_PROGRESS；最後一個行程 DONE 或 CLOSED_BY_SYSTEM 起 48h；沒有未結束變更 | 改條件；與 C 段的 CLOSED_BY_SYSTEM 放行寫在同一處 |
| 34 | PRD-009 | D.3 | `CONFIRMED → DISPUTED` | §3.1：`IN_PROGRESS → DISPUTED` | 改起點狀態 |
| 35 | PRD-009 | A.4、例外表 | 列 `MODIFYING`、`REFUND_VERIFY`、補款的 `PENDING_PAYMENT` / `PAID` | §3.1：這些主狀態不存在 | 改成「有未結束變更請求時 → 409 `MODIFICATION_IN_PROGRESS`」；行程清單顯示規則改依主狀態 |
| 36 | PRD-009 | 例外「飼主有爭議（未來擴充）」 | 未來擴充 | D 段已經定義爭議（P4） | 刪掉這列 |
| 37 | PRD-009 | 段落編號 | 兩個「C.」 | — | 帳務總覽改為 F，或拆到另一張 PRD（帳務總覽屬於 SD-FULFILL 還是 SD-BILLING 要確認） |
| 38 | PRD-009 | C（帳務）.3–4、AC-3 | 總收入 = 結案訂單的「最終總金額」之和 | §4：保母收入 = 已付總額 − 已退總額 | 改公式；AC-3 驗含退款的訂單 |
| 39 | PRD-009 | E.3 | ADMIN「調整最終訂單金額」 | baseline 沒有此機制 | 依 CQ-18 改寫 |
| 40 | PRD-009 | 業務規則「金額精準度」 | `BigDecimal(18,4)` | §4：整數 | 改整數 |
| 41 | PRD-009 | B.1「每日執行」 | 每日 | #15：排程頻率與到期判斷 | 依 baseline 排程頻率 |
| 42 | PRD-009 | 新增 | 手動結案與日誌 24h 補送期的關係沒寫 | CQ-27 | 加 AC |
| 43 | PRD-016 | 背景 | 「完成付款（CONFIRMED 或 PAID 狀態）」 | §3.3：只在 CONFIRMED / IN_PROGRESS 發起 | 改發起條件；其他狀態 → 409 `ORDER_STATE_CONFLICT` |
| 44 | PRD-016 | A.3–A.4、AC-1 | 請求狀態 `PENDING_REVIEW`；訂單轉 `MODIFYING` | §3.3：`REQUESTED`；訂單主狀態不變 | 改狀態名；AC-1 改為「請求 REQUESTED，訂單狀態維持原值」 |
| 45 | PRD-016 | B.3、C.3（無差額）、AC-7、AC-8 | 「訂單恢復發起變更前的狀態」 | §3.1：主狀態從頭到尾沒變，沒有還原 | 刪掉所有「恢復 / 回到」字眼，AC 改為「訂單狀態仍為 IN_PROGRESS」 |
| 46 | PRD-016 | C.3（應補款） | 訂單轉 `PENDING_PAYMENT`，補款憑證記在變更請求 | §3.2–3.3：變更 `AWAITING_SUPPLEMENT` + 新 payment UNPAID；憑證在 payment | 改寫成兩個實體的轉移 |
| 47 | PRD-016 | C.3（應退款，線下） | 訂單轉 `REFUND_VERIFY` | §3.3：變更 `AWAITING_REFUND` | 改名，並補保母上傳退款憑證的步驟（依 #7 的 baseline 修正） |
| 48 | PRD-016 | C.3（整筆取消） | 不論差額都轉 CANCELLED | §3.3：「新內容無任何預約項目 → CANCELLED」；IN_PROGRESS 待 CQ-14 | 依 CQ-14 分 CONFIRMED / IN_PROGRESS 兩種情況寫 |
| 49 | PRD-016 | C.3 系統動作時點 | 飼主同意時調整行程、更新金額 | §3.3：SETTLED 時套用 | 依 CQ-16 統一時點 |
| 50 | PRD-016 | C.2 保母發起 | 保母送出原因與新報價，飼主同意 | §3.3 圖：REQUESTED → QUOTED 由 SITTER 報價，沒有保母發起的路徑 | baseline 補「保母發起直接建立為 QUOTED」後，PRD 對應 |
| 51 | PRD-016 | B.2 差額 | 「已付金額 vs 新的最終金額」 | §4：差額 = 新總額 − 已付；#20 建議用淨額 | 依 baseline 修正後的公式；加「退款 ≤ 已付淨額」 |
| 52 | PRD-016 | B.2 新總額輸入 | 任何方案保母都可輸入新總額 | §2.1：手動調價限 PRO+ | 依 CQ-22 |
| 53 | PRD-016 | 備註「飼主長時間未確認可發起爭議」 | 未確認退款 → 發起爭議 | §5：爭議只在最後一個行程完成後 48h 內；§3.3 沒有逾時 | 依 CQ-15 / CQ-17 改寫 |
| 54 | PRD-016 | AC 數與編號 | 9 條 AC（AC-9 排在 AC-8 前） | doc-conventions：子模組 ≤ 8 | 切子模組：A 發起與報價、B 結清（補款 / 退款 / 取消） |
| 55 | PRD-016 | 例外表、業務規則「重送已完成動作回 409」 | 一律 409 | §3.4：同 key 回放；不同 key 才 409 `ORDER_STATE_CONFLICT` | 分開寫兩種情況 |
| 56 | PRD-012 | 方案對照表「月費」 | Basic $299、Pro $699；名稱 Premium、新手版 | §2.1：BASIC $499、PRO $899、ULTIMATE $1299 | 改價格與代號（ULTIMATE） |
| 57 | PRD-012 | 方案對照表「多媒體限制」 | 3 / 10 / 20+15s / 不限+60s | §2.1：文字 / 5 照片 / 10+2 / 25+5 | 改數值，引用 BASELINE |
| 58 | PRD-012 | 方案對照表「線上支付」 | BASIC 起支援 | §2.1：PRO 起 | 改 |
| 59 | PRD-012 | 方案對照表「客製問卷」「信任圈」「行事曆同步」 | 有分級 | §2.1 沒有這三列（SAAS 沒有出處） | 刪掉，或列待釐清請使用者確認（見 #29） |
| 60 | PRD-012 | C.1–C.2 逾期 | 到期當日 23:59 回落 FREE，無寬限期 | §2.1：到期隔天 00:00 鎖定保母端（BR §5.7.2） | 依 CQ-19 改寫；AC-5「寬限期後」與 C.1「無寬限期」自相矛盾一併修 |
| 61 | PRD-012 | AC-2 | 第 21 筆進入 CONFIRMED 的訂單被阻擋 | CQ-5：上限只擋公開頁預約，在流程中的訂單不受影響（第 21 筆可以 CONFIRMED） | 改 AC：計數到 20 後，預約 API 回 409；已在 PENDING / AWAITING_PAYMENT 的訂單照常 CONFIRMED |
| 62 | PRD-012 | 業務規則「配額返還」 | 當月取消即時返還，跨月不返 | baseline 沒寫 | 依 CQ-21 |
| 63 | PRD-012 | 業務規則「降級緩衝」 | 只保護 IN_PROGRESS 的訂單 | §2.1：快照保護所有已快照訂單 | 改為「已快照的訂單不受影響」 |
| 64 | PRD-012 | AC-4 | 「根據目前方案等級」做 count 檢查 | §2.1 快照 | 改依訂單快照 |
| 65 | PRD-013 | §2.A「合約規格快照」 | 訂單 CONFIRMED 時鎖定保留天數 | §4：報價時（CQ-20） | 依 CQ-20 |
| 66 | PRD-013 | §3「未結案訂單（狀態非 DONE）」 | 訂單狀態 DONE | §3.1：訂單沒有 DONE，結案是 COMPLETED | 改為「訂單非 COMPLETED / CANCELLED」；CANCELLED 起算點依 #31 |
| 67 | PRD-013 | §1 表格 Premium | 「帳號存續期間」、Premium | §2.1：ULTIMATE 永久 | 代號改 ULTIMATE；baseline 補「永久 = 至帳號註銷」（#31） |
| 68 | PRD-013 | AC-1、AC-3 | 「精準為 7 天後」「模擬日期到達」 | §5：日的邊界 Asia/Taipei | 寫出可觀察日期：11/20 結案 → 11/28 00:00（Asia/Taipei）刪除 |
| 69 | PRD-022 | 狀態 | Approved | 修改規格內容要退回 In Review | 依下列各點修改時狀態改 In Review，變更紀錄註明依 BASELINE |
| 70 | PRD-022 | 例外表「網路重試」、AC-2.2 | 重複 key 回 409 `IDEMPOTENCY_CONFLICT` | §3.4：同 key 回放第一次結果（抽樣 S5） | 改 AC：同 key 重送回第一次的狀態碼與 body，不新增媒體 |
| 71 | PRD-022 | A.4、例外「方案上傳超限」 | 檢核「保母當前的 SaaS 訂閱等級」 | §2.1：訂單快照的媒體額度 | 改依快照；錯誤碼依 baseline 錯誤碼表（`AUTH_PLAN_LIMIT` 統一名稱） |
| 72 | PRD-022 | 例外「訂單已結案」 | COMPLETED 一律 403 `ORDER_COMPLETED` | §5 `CARE_LOG_EDIT_HOURS` 24h × 飼主可隨時手動結案 | 依 CQ-27 |
| 73 | PRD-022 | A.2、例外表 | 行程狀態 `PENDING / SCHEDULED` | §6：PENDING（SCHEDULED 不用） | 拿掉 SCHEDULED |
| 74 | PRD-022 | 業務規則「樂觀鎖防同帳號多裝置並發」 | 以多裝置並發當理由 | doc-conventions：不處理同人多裝置自我併發 | 理由改為「弱網重送 / 舊分頁覆蓋」，或確認日誌的 @Version 仍需要（不屬於訂單狀態轉移，不受 §3.4 列鎖規則限制） |

共 74 項。

## 待使用者回答

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-9 | 付款提醒 / 逾期的「首個行程開始時間」：行程只有日期，起算點用什麼 | 首個行程日期 00:00 Asia/Taipei。提醒 = 該時刻 −48h，逾期 = 該時刻 −24h（首個行程 11/25 → 11/23 00:00 提醒、11/24 00:00 取消） |
| CQ-10 | 送單或報價時已經過了付款期限（當天 / 隔天的預約、PENDING 擱置太久）怎麼辦 | 送單時首個行程日期必須 ≥ 今天（Asia/Taipei）+ 2 天，否則 422 `BOOKING_TOO_SOON`；報價時 `now ≥ 付款期限` → 409 `PAYMENT_DEADLINE_PASSED`，保母只能婉拒；報價時已過提醒點，報價通知就兼提醒，不另外發 |
| CQ-11 | 付款期限到時，飼主已上傳憑證（SUBMITTED）但保母還沒核對 | 不取消，付款期限只對 UNPAID 生效；到期時通知保母盡快核對。首個行程日期 00:00 仍是 SUBMITTED → 訂單 CANCELLED（cancelledBy=SYSTEM），payment VOID，通知雙方並提示飼主向保母索回款項（線下交易，平台不代管） |
| CQ-12 | 保母一直不報價也不婉拒，PENDING 要不要自動結束 | 首個行程日期 00:00 Asia/Taipei 仍是 PENDING → CANCELLED（cancelledBy=SYSTEM），通知雙方 |
| CQ-13 | AWAITING_PAYMENT 期間保母能否取消或改價 | 不可改價（要改價只能取消後請飼主重下）；首次 payment = UNPAID 時保母可以取消（需填原因，cancelledBy=SITTER）；SUBMITTED 時不能取消，只能駁回憑證 |
| CQ-14 | IN_PROGRESS 時「整筆取消」：已完成的行程與金額怎麼處理 | IN_PROGRESS 的整筆取消 = 刪除所有 PENDING 行程，DONE / CLOSED_BY_SYSTEM 行程保留；SETTLED 後訂單不轉 CANCELLED，照正常流程結案（48h 自動結案或飼主確認），保母收入 = 已付 − 已退。只有 CONFIRMED（沒有任何行程離開 PENDING）的整筆取消才 CANCELLED |
| CQ-15 | 變更子流程卡住：對方不回應、飼主不補款、飼主不確認退款；發起者能否撤回 | 發起者在 REQUESTED / QUOTED 可撤回（→ REJECTED，reason=WITHDRAWN）；REQUESTED / QUOTED 72h 無回應 → REJECTED（reason=EXPIRED）；AWAITING_SUPPLEMENT 72h 未上傳憑證 → REJECTED、補款 payment VOID、內容不變；AWAITING_REFUND：保母上傳退款憑證後，飼主 72h 未確認也未回報 → 系統視為確認、SETTLED（寫 log）。72h 為推測值，請確認 |
| CQ-16 | 變更內容何時套用；協商中「要刪除的行程」照常執行、「要新增的行程」日期先到了怎麼辦 | SETTLED 時套用。協商期間，變更請求中要刪除的行程不能開始（409 `VISIT_PENDING_MODIFICATION`），也不被 72h 清理關閉；新增行程的最早日期必須晚於 `now + 72h`（給補款留時間），否則 422 |
| CQ-17 | 變更與爭議互斥；變更結束後自動結案與爭議視窗怎麼重算 | 有未結束變更時不能回報問題（409 `MODIFICATION_IN_PROGRESS`）；DISPUTED 時不能發起變更（409 `ORDER_STATE_CONFLICT`）；自動結案與爭議視窗起算點 = max(最後一個行程完成時間, 最後一筆變更請求結束時間) |
| CQ-18 | ADMIN 調解結案時「調整最終訂單金額」怎麼記 | 不直接改 total_amount。ADMIN 結案時可以登記一筆退款（線下，附雙方協商憑證，金額 ≤ 已付淨額），訂單轉 COMPLETED；保母收入照「已付 − 已退」計算。ADMIN 對 DISPUTED 訂單有唯讀權限（含憑證、日誌） |
| CQ-19 | 訂閱逾期鎖定（BR）與 PRD-012「回落 FREE」二選一；鎖定或停權期間既有訂單怎麼辦 | 採 BR：到期隔天 00:00 鎖定，不回落 FREE。鎖定只擋「新生意」：公開頁不可預約、不能報價、不能改方案 / 問卷設定（403 `SUBSCRIPTION_LOCKED`）；既有訂單的核款、行程、日誌、變更回應、婉拒照常。ADMIN 停權相同，另通知該保母所有進行中訂單的飼主 |
| CQ-20 | 快照時點：§2.1 寫訂單成立（CONFIRMED），§4 寫報價 | 全部在報價（`PENDING → AWAITING_PAYMENT`）時快照：單價、方案名稱、媒體額度、保留期限。理由：飼主付款時看到的條件就是成交條件 |
| CQ-21 | 月接單額度：CONFIRMED 後取消要不要返還；月中升降級用哪個上限 | 不返還（計數 = 當月 Asia/Taipei 內進入 CONFIRMED 的次數，與後續狀態無關，可測且不能靠取消洗額度）；上限取檢查當下的訂閱等級。PRD-012 的「當月返還」需使用者確認是否保留 |
| CQ-22 | FREE / BASIC 保母在變更協商能否自己輸入新總額 | 不能。FREE / BASIC 的新總額 = 快照單價 × 新內容，由系統計算；PRO+ 可再加 surcharge / discount（附原因）。新總額 ≥ 0 |
| CQ-23 | 保母收款帳號是否快照到 payment（抽樣報告 #11） | 建立 payment 時快照帳號（加密存放），飼主付款頁顯示快照；保母之後改帳號不影響既有 UNPAID / SUBMITTED payment，並寫 order_logs |
| CQ-24 | `VISIT_ZOMBIE_HOURS` 72h、`DISPUTE_WINDOW_HOURS` 48h、`AUTO_COMPLETE_HOURS` 48h 只出自 Draft PRD-009，BR 沒有數值 | 沿用現值（72 / 48 / 48），請使用者確認後改出處為「使用者決定」 |
| CQ-25 | 同一帳號能否用 OWNER 身分預約自己的 SITTER 頁面 | 不可以，送單回 422 `SELF_BOOKING_NOT_ALLOWED` |
| CQ-26 | 報價總額可否為 0（全額折扣） | 不可以，`total_amount ≥ 1`，否則 422 `INVALID_QUOTE_AMOUNT` |
| CQ-27 | 飼主在最後一個行程完成後立即手動結案，會讓保母失去 PRD-022 的 24h 日誌補送期 | 「確認結案」只在所有行程日誌都是 SUBMITTED、或已過 `CARE_LOG_EDIT_HOURS` 時開放，否則 409 `CARE_LOG_PENDING` |
| CQ-28 | `CONFIRMED → IN_PROGRESS` 的觸發者；行程全被系統關閉的訂單 | 任一行程離開 PENDING（保母開始，或系統關閉 CLOSED_BY_SYSTEM），在同一交易把訂單 `CONFIRMED → IN_PROGRESS`，寫 order_logs；操作者 = 該行程動作的操作者（系統關閉記 SYSTEM） |
