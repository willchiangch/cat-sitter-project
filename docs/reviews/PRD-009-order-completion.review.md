# 審查：PRD-009（第 1 輪，2026-11-22）

結論：需修正

範圍：`docs/sa/fr/PRD-009-order-completion.md`（In Review，23 條 AC：8 / 8 / 7）。基準：DOMAIN-BASELINE 第 3 版修訂（§7 CQ-1~83 已決，不質疑）、SD-ORDER（Approved）E2 / E3 / E25 / E26 / E30 / C8 / §7.2 / §9.1、PRD-008、PRD-022、PRD-024、PRD-020（舊版）、`reports/rereview/00-sample-SD-007.md` P1–P5。

時間推演（全部核對無誤）：AC-2.1 基準 `2026-11-21T02:00:00Z` → 自動結案 `2026-11-23T02:00:00Z`（台北 11-23 10:00）；AC-2.2 基準 = max(11-21 10:00, 11-21 18:00) = 11-21 18:00 → 11-23 18:00，AC-2.5 於 11-22 12:00 在視窗內；AC-2.6 V3 在同意時已取消，變更 `AWAITING_REFUND` 期間 BR-21 不顯示，`SETTLED` 於 11-22 15:00 後基準 = `2026-11-22T07:00:00Z`，`deadlineAt = 2026-11-24T07:00:00Z`；AC-3.2 `2026-11-30T15:59Z` = 11-30 23:59、`16:00Z` = 12-01 00:00、`2026-10-31T16:30Z` = 11-01 00:30；AC-3.1 1500 + 200 + 1200 = 2900。O12 與 O13 都用同一個門檻 `completion_basis_at + 48h`，`<` 對上 `≥`，時間上互斥，不會有兩邊都成立的時刻。

## 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 阻擋 | BR-20、§9「SD-ORDER 需補欄位」 | SD-ORDER §7.2 明寫「O9、O11、O12 不通知，PRD-009 改寫時若要求，再新增 type」，O13 `SERVICE_DISPUTE_OPENED` 的文案出處也還是「範本由 SD-NOTIFY 擬定」。PRD-009 BR-20 要求 O11 通知 SITTER、O12 通知雙方（與 BASELINE CQ-82 一致），但 §9 移交清單 ①–④ 沒有列這項。照目前 SD 實作，AC-1.1、AC-2.1、AC-2.4 的通知斷言一定失敗（P3：跨模組行為只寫在一邊） | §9 補 ⑤：SD-ORDER §7.2 新增 O11 `ORDER_COMPLETED_BY_OWNER`（SITTER）、O12 `ORDER_AUTO_COMPLETED`（OWNER、SITTER）兩個 type（`mandatory=false`），O13 文案出處改為 PRD-009 BR-20 的字面；刪除「O11、O12 不通知」那一列 |
| 2 | 重要 | BR-20、EX-20、AC-1.1 | EX-20 寫「只投遞收件人仍開啟的管道」，AC-1.1 的 Given 又寫「該類別站內與 Email 皆開啟」，等於假設站內也能關。但 SD-ORDER §7.2 / §2.3.3 是「站內一律寫，Email 依偏好」，PRD-005 BR-16、PRD-008 §9、PRD-026 BR-6、PRD-027 BR-10 也都寫「站內必寫」。測試寫手會不知道「站內關閉」這個情境該怎麼驗 | BR-20、EX-20 改成「站內必寫；Email 依收件人 PRD-014『訂單事務』偏好」；AC-1.1 / 2.1 / 2.5 的 Given 只固定 Email 偏好（「訂單事務 Email 開啟 / 關閉」），拿掉「站內開啟」 |
| 3 | 重要 | §4.1 步驟 1、AC-1.2、AC-2.2 | `DONE` 行程超過 `completed_at + 24h` 還沒送日誌，就永遠送不出去（PRD-022），O11 也就永遠不會成立（CQ-40），只能等 O12。但提示還是顯示「所有行程完成且保母送出照護日誌後，即可確認結案」，叫飼主等一件不會發生的事。AC-2.2 正好就是這個情境 | 列 CQ-9。建議預設值：任一 `DONE` 行程日誌未送出且 `now ≥ completed_at + CARE_LOG_EDIT_HOURS` 時，`owner-order-detail-complete-hint` 改顯示「保母未於期限內送出照護日誌，訂單將於 YYYY-MM-DD HH:mm 自動結案」（時間 = BR-21 的值）；AC-2.2 補這條畫面斷言 |
| 4 | 重要 | BR-5、EX-8、EX-12、AC-2.6 | (a) 視窗判斷的 `now` 沒定義是「收到請求的時間」還是「取得鎖後的時間」。SD-ORDER §3.1.1 寫的是鎖後以注入的 `Clock` 比對，PRD-022 的日誌期限則用 `receivedAt`，兩種口徑都有人在用。(b) 呼叫方指定要看的「O13 與 C8 在視窗邊界併發」沒有 EX 也沒有 AC，EX-12 只寫了 O11 對 C8 | BR-5 補「以取得訂單鎖後的伺服器時間判斷（SD-ORDER §3.1.1）」。新增 EX：例如基準 `2026-11-22T07:00:00Z`，O13 於 `2026-11-24T06:59:59.5Z` 送達，C8 於 `07:00:00Z` 執行。O13 先拿到鎖且鎖後時間 < `07:00:00Z` → `DISPUTED`，C8 `SKIP LOCKED` 略過，下一輪不是候選；鎖後時間 ≥ `07:00:00Z` → 409 `DEADLINE_PASSED`；C8 先提交 → O13 回 409 `ORDER_STATE_CONFLICT`（`currentStatus=COMPLETED`）。不管哪種，都恰好只有一次轉移、只有一組通知。併入 AC-2.6 或 AC-1.6 |
| 5 | 重要 | AC-2.5 | 斷言「出現在 ADMIN 待辦清單（PRD-020）」，但 PRD-020 還沒改寫、沒有畫面規格，PRD-009 的 E2E 沒辦法驗 | 改成 API 斷言：ADMIN 呼叫 E28 `GET /api/admin/tasks?type=SERVICE_DISPUTE` 回傳含該 `adminTaskId`、`orderId` 的 `OPEN` 項目；畫面另交 PRD-020 |
| 6 | 重要 | §4.6 步驟 3、§4.4、§5 | 和 PRD-020 的邊界只講清楚了轉移（O15 / O17 見 PRD-020），結果畫面的歸屬沒講：(a) `CANCELLED` 詳情只指向 PRD-027 / PRD-026，漏了 O10（變更取消，PRD-023）與 O17（調解取消，`cancelledBy=ADMIN`）；(b) O15 進入的 `COMPLETED`，飼主 / 保母詳情要不要顯示調解結果（`ADMIN_ADJUSTMENT` 金額、依據），哪張卡都沒定義；AC-1.7 只涵蓋 O11 | 列 CQ-14。建議預設值：§4.4 `COMPLETED` 列註明 O11 / O12 / O15 共用；調解結果區塊（結果、調整金額、依據）歸 PRD-020；§4.6 步驟 3 補「O10 見 PRD-023、O17 見 PRD-020」 |
| 7 | 重要 | §4.6、AC-3.6、AC-3.7 | 飼主歷史分頁沒有空狀態（沒有歷史訂單、篩選後沒有結果），也沒有分頁規則。E3 是分頁 API（`page` / `size`），但 PRD 沒寫要用載入更多、頁碼還是無限捲動，`size` 多少也沒寫 | 列 CQ-10。建議預設值：每頁 20 筆、「載入更多」按鈕（`owner-orders-history-btn-load-more`）；無資料顯示「尚無歷史訂單」、篩選無結果顯示「沒有符合條件的訂單」（`owner-orders-history-empty`）；AC-3.7 補一條空狀態 |
| 8 | 重要 | AC-3.1、BR-10 | 呼叫方要求的計算例沒有涵蓋到：(a) 非 `VERIFIED` 不計（`VOID` 補款）；(b) 補款逾時重算後的淨額（BASELINE §3.4 補款、CQ-53）；(c) `COMPLETED` 但淨額 0 的訂單（O15 全額 `ADMIN_ADJUSTMENT`）要不要列出。BR-22 只排除淨額 0 的 `CANCELLED` | AC-3.1 加訂單 J：原 `total_amount` 2000（4 次 × 500），INITIAL CHARGE 2000 `VERIFIED`；變更取消 1 次、加 3 次，新總額 3000 → SUPPLEMENT 1000 逾時 `VOID`，`total_amount` 重算為 1500，系統退款變更的 REFUND 500 `VERIFIED` → J 淨額 1500（不是 2000、也不是 3000）。再加訂單 K：`COMPLETED`（O15），CHARGE 800、`ADMIN_ADJUSTMENT` 800 → 列出、淨額 NT$ 0。BR-22 明寫「`COMPLETED` 淨額 0 仍列出」 |
| 9 | 重要 | BR-6、AC-2.8、EX-9 | 「必填 1–500 字」沒寫要不要先去前後空白，也沒寫字數怎麼算。只有空白的 `"   "` 該回 400 還是 200 判不出來；emoji / 組合字算 1 字還是 2 字也沒定。SD-ORDER 其他文字欄位都寫「去前後空白 1–N 字」，E26 偏偏沒寫 | 列 CQ-13。建議預設值：去前後空白後 1–500 字，以 Unicode code point 計；前端計數規則相同。AC-2.8 補「只有空白 → 400」「500 個 emoji → 200」 |
| 10 | 建議 | §4.5、E30 | 帳務總覽沒寫分頁 / 上限。ULTIMATE 接單無上限，E30 也沒有分頁參數 | 列 CQ-11。建議預設值：不分頁，一次回傳該月全部（單月筆數有限）；在 BR-22 寫明 |
| 11 | 建議 | §4.5 步驟 3、BR-22 | 「訂單編號」沒有定義。訂單只有 UUID，全站沒有一個人看得懂的編號（grep 確認） | 列 CQ-12。建議預設值：顯示 `orderId` 前 8 碼大寫；點擊進入訂單詳情 |
| 12 | 建議 | BR-25、§9「跨卡」 | 已經過時：PRD-022 BR-4 已改成「O11 / O12 進入的 `COMPLETED` 不鎖、O15 / O17 才鎖」，但 PRD-009 還寫「PRD-022 BR-4 目前對 `COMPLETED` 一律 409，需由 PRD-022 修訂」 | BR-25 末句與 §9 跨卡改成「與 PRD-022 BR-4 一致（O15 / O17 立即鎖定由 PRD-022 定義）」。注意：PRD-022 自己的 §3 判斷順序第 8 步、§4 步驟、L1 / L3、EX-6 還寫「訂單非 `IN_PROGRESS` / `DISPUTED` → 409」，跟它的 BR-4 互相矛盾，交給 PRD-022 處理 |
| 13 | 建議 | AC-1.6 | 併發情境沒有斷言通知：O11 先 → 只有給 SITTER 的「飼主已確認結案」，沒有 O12 通知；C8 先 → 只有 O12 的兩則。另外 `ORDER_BUSY` 依規定不寫冪等紀錄，同一把 key 重試應該能成功，這點也沒驗 | 補兩條斷言：「通知只有勝出那一方的」「`ORDER_BUSY` 後以同 key 重試 → 200」 |
| 14 | 建議 | AC-2.3 (b) | 「依新的 `completion_basis_at` + 48h 判斷，未到不結案」沒有給具體時間 | 給具體值：變更於 2026-11-25 09:00 `SETTLED` → 基準 = `2026-11-25T01:00:00Z`；排程於 11-27 08:59 不結案、11-27 09:00 後第一輪結案 |
| 15 | 建議 | AC-1.1、AC-1.7、AC-2.1 | AC-1.7 寫「2026-11-23 10:00 格式」，但 AC-1.1 沒固定結案時間；AC-2.1 的「≤ 15 分鐘內」在注入 `Clock` 的測試裡驗不了 | AC-1.1 Given 固定時鐘為 `2026-11-23T02:00:00Z`，Then `completedAt` 等於該值；AC-1.7 斷言文字「2026-11-23 10:00」。AC-2.1 改成「時鐘 `2026-11-23T02:00:00Z` 時執行 C8 → 結案」 |
| 16 | 建議 | BR-21、AC-2.6 | 視窗已過、但排程還沒跑的那段空窗，「可回報問題至 X」/「預計自動結案時間 X」仍顯示一個已經過去的時間，文案沒定義 | 已過時改顯示「即將自動結案」，或維持原時間不變；擇一寫進 BR-21 並在 AC-2.6 斷言 |
| 17 | 建議 | AC-3.4 | 預設月份只用 11-22 驗，沒驗 UTC 與台北跨日、跨月的邊界 | 補：時鐘 `2026-11-30T16:30:00Z`（台北 12-01 00:30）、瀏覽器時區 UTC → `sitter-ledger-select-month` 預設 2026-12 |
| 18 | 建議 | AC-1.7 vs §3 | §3 說保母訂單列表「不在本卡」，AC-1.7 卻斷言「雙方歷史分頁」 | 保母列表的分頁歸屬依 BR-9，改成「保母歷史分頁見 SD-ORDER E4，本卡只驗飼主」，或在 §3 註明保母分頁歸屬也由本卡驗 |
| 19 | 建議 | §4.4 `DISPUTED` 列 | 橫幅沒說也適用 O14（款項爭議）造成的 `DISPUTED`。PRD-024 §9 只寫「爭議中訂單顯示『訂單爭議處理中』」，兩張卡可能各做一條橫幅 | 註明 `owner-order-detail-disputed-banner` / `sitter-order-detail-disputed-banner` 不分 O13 / O14 來源，PRD-024 引用本卡 |
| 20 | 建議 | BR-22、BR-23（P5） | 「飼主稱呼」/「保母名稱」用當下的顯示名稱還是快照，沒寫；帳務與歷史紀錄會因對方改名而變動 | 寫明「讀當下的顯示名稱，不快照」（或反之） |
| 21 | 建議 | AC-2.4 | 沒驗 C8 的回應計數（`processed` / `failed`），也沒驗排程端點身分（一般 token → 403）。PRD-008 AC-2.6 有為 C7 驗這兩項 | 補「X 失敗、Y 成功 → 200 `processed=1`、`failed=1`」「一般使用者 token 呼叫 C8 → 403 `FORBIDDEN`、訂單不變」 |
| 22 | 建議 | BR-17、AC-1.6、AC-2.8 | 冪等「同 key 不同內容」沒有可測的例子。E25 沒有 body，唯一的情況是同一把 key 用在另一張訂單（SD-ORDER §3.6.2 的 hash 含路徑變數） | 補：OWNER-A 用 AC-1.1 的 key 對另一張訂單呼叫 E25 → 422 `IDEMPOTENCY_KEY_REUSED`；E26 同 key 換說明 → 422 |
| 23 | 建議 | §9「SD-ORDER 需補欄位」 | ① 沒寫 E30 的排序（終態時間新到舊）；④ 沒提飼主依終態時間排序需要的索引（現有 `ix_orders_sitter_terminal` 只涵蓋保母）；SD-ORDER §9.1 `/sitter/ledger` 的空狀態文案「本月尚無收入」和位置，與 PRD「收款分頁」、「本月沒有已結束的訂單」不一致 | ① 補「`orders[]` 依 `terminalAt` 新到舊」；④ 補「`(owner_id, COALESCE(completed_at, cancelled_at))` 索引」；新增 ⑥ SD-ORDER §9.1 帳務路由的位置與空狀態文案依 PRD-009 BR-22 |

AC 大小：8 / 8 / 7，符合上限。AC-1.6（冪等 + 併發 + 鎖逾時）、AC-2.8（驗證 + 越權 + 冪等）一條塞了多個主題，但子模組 1、2 都已經 8 條，拆了就超過上限，維持現狀。

## 待使用者回答

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-9 | `DONE` 行程日誌已過 `CARE_LOG_EDIT_HOURS` 仍未送出（O11 永遠不會成立），飼主看到什麼提示 | `owner-order-detail-complete-hint` 顯示「保母未於期限內送出照護日誌，訂單將於 YYYY-MM-DD HH:mm 自動結案」（= `completion_basis_at + 48h`，Asia/Taipei）；不顯示確認結案按鈕 |
| CQ-10 | 飼主歷史分頁的分頁方式與空狀態 | 每頁 20 筆、「載入更多」（`owner-orders-history-btn-load-more`）；無資料「尚無歷史訂單」、篩選無結果「沒有符合條件的訂單」（`owner-orders-history-empty`） |
| CQ-11 | 帳務總覽要不要分頁 | 不分頁，一次回傳該月全部 |
| CQ-12 | 帳務列表的「訂單編號」怎麼顯示 | `orderId` 前 8 碼大寫，點擊進入訂單詳情 |
| CQ-13 | 回報問題說明的字數規則 | 去前後空白後 1–500 字，以 Unicode code point 計；只有空白 → 400 `VALIDATION_FAILED` |
| CQ-14 | O15 / O17 / O10 結果在飼主、保母詳情的呈現歸屬 | `COMPLETED` 基本畫面（§4.4）O11 / O12 / O15 共用；調解結果區塊（結果、`ADMIN_ADJUSTMENT` 金額與依據）歸 PRD-020；`CANCELLED` 詳情：O3 / O4 / O7 見 PRD-027、O3 撤回 / O6 見 PRD-026、O10 見 PRD-023、O17 見 PRD-020 |

## 跨 PRD / SD 矛盾

1. SD-ORDER §7.2「O11、O12 不通知」vs PRD-009 BR-20 / BASELINE CQ-82（#1）。
2. SD-ORDER §7.2 與 PRD-005 / 008 / 026 / 027 都是「站內必寫」，PRD-009 EX-20（#2）卻假設站內能關。PRD-022 AC-3.1「關閉『訂單事務』類別則不產生該通知」也和這些卡不一致（另交 PRD-022）。
3. PRD-022 BR-4（O11 / O12 不鎖）vs PRD-022 自己的 §3 第 8 步、L1 / L3、EX-6（「非 `IN_PROGRESS` / `DISPUTED` → 409」）；PRD-009 BR-25 / §9 的描述已過時（#12）。
4. PRD-020（舊版）的「強制結案區」、「AC-3 線上刷退」vs BASELINE O15 / O17（系統依行程判斷終態、`ADMIN_ADJUSTMENT`）與 PRD-009 BR-13（線上撥款屬第二階段）。PRD-020 改寫時要清掉。
5. SD-ORDER §9.1 `/sitter/ledger`（空狀態「本月尚無收入」）vs PRD-009 BR-22（「收款」分頁、「本月沒有已結束的訂單」）（#23）。
6. SD-ORDER E26「1–500 字」沒有去空白規則，其他端點都有（#9）。
