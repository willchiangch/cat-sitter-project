# 審查：PRD-007（第 1 輪，2026-11-21）

結論：需釐清

範圍：`docs/sa/fr/PRD-007-offline-payment.md`（In Review，3 子模組 × 8 AC = 24 條）。基準：`docs/sa/00-DOMAIN-BASELINE.md` 第 2 版（§7 CQ-1~67 視為已決）；抽樣 `reports/rereview/00-sample-SD-007.md`。相關卡只核對引用：PRD-006 / 016 / 023 / 024 / 025 / 026 / 027、PRD-014。

## 抽樣缺陷回顧

| 抽樣 # | 是否解決 | 說明 |
|--------|---------|------|
| #9 AC 不可觀察、缺錯誤碼 / 越權 / 格式 / 免責 / 冪等 / 通知失敗 / 補款 | 大致解決 | 24 條 G/W/T，錯誤碼、testid、狀態值齊全；殘留：401 與 ADMIN 上傳 / 駁回、SITTER 確認退款等越權格沒有 AC（#9），SUPPLEMENT 確認框沒有 AC（#4） |
| #10 壓縮 vs 拒絕；逾期未付 | 已解決 | BR-5 不壓縮、超過 422；O8 取代「未來擴充」 |
| #11 帳號無快照、可被盜改 | 部分解決 | 快照與「改帳號不影響既有 payment」已寫（BR-2、AC-1.1）；但快照本身是否加密未寫（#6），盜改偵測的盲點見 PRD-025 審查 |

| 缺陷模式 | 結果 |
|---------|------|
| P1 AC 不可觀察 | 殘留少量：AC-1.1「畫面不出現訂單總額」對 INITIAL 不可判定（`amount` = `total_amount`，數字相同）（#14） |
| P2 例外表沒錯誤碼 | 通過 |
| P3 跨模組流程只寫在下游 | 內容已上收，但引用仍指向拆卡前的 PRD-005 / 006 / 016（#1） |
| P4 前後矛盾並存 | 有：BR-22 兩種文案 vs EX-28 一種；§4.2-3.3 對 SUPPLEMENT 也說「由 O8 取消」；`DEADLINE_PASSED` payload 與 PRD-023 不一致（#3、#4） |
| P5 快照 / 竄改 | 快照加密未寫（#6）；付款提醒「已提醒」標記無人寫入，緊急單會被重複提醒（#7） |

## 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 重要 | 表頭「相依 PRD」、§1、§4.2-2、§4.2-4、§5 末段、BR-22、BR-23、AC-2.2、EX-18 | 引用的還是拆卡前的歸屬：O6 寫 PRD-005（實際 PRD-026）、O7 寫 PRD-006（實際 PRD-027）、補款 / 退款建立與結清寫 PRD-016（實際 PRD-023）、O14 寫 PRD-016（實際 PRD-024）、BR-23「PRD-016 同意變更時的通知」（同意在 PRD-023 BR-20）、EX-18「`PAYMENT_ALREADY_SUBMITTED`（PRD-006）」（實際 PRD-027 BR-6）。TS / SD 會依此追溯到錯的卡 | 表頭改為 PRD-006（O2）、PRD-026（O6）、PRD-027（O7）、PRD-023（補款 / 退款建立與結清）、PRD-024（O14、DISPUTED 凍結）；內文逐處替換 |
| 2 | 重要 | §4.1-4 步驟順序 | 「呼叫者為付款方」的檢查放在 GCS 上傳**之後**的交易內（步驟 3）。結果：任何登入者（BOLA）或方向不符的當事人都能讓系統先寫 GCS 物件再被 403 擋下；非當事人送不合格檔案拿到 422 而不是 403；payment 已 `VOID` / 已過 `due_at` 也會先上傳。冪等檢查在流程中的位置也沒寫 | 步驟改為：冪等查詢 → 授權（當事人、方向、角色）與狀態 / 期限預檢（不取鎖）→ 欄位與檔案驗證 → GCS 上傳 → 交易內重驗。並在 §3 寫明錯誤判斷順序（見 #5） |
| 3 | 重要 | §4.4-4、EX-12、AC-1.7 vs PRD-023 EX-13 / AC-2.7、PRD-016 BR-15、PRD-006 BR-9 | 同一個上傳端點（PRD-007 擁有）對 SUPPLEMENT 逾期：PRD-007 寫 payload `deadline=due_at`（AC-1.7 讀起來是「值 = `due_at`」），PRD-023 寫 `deadline=PAYMENT_DUE_AT`（期限名稱，符合 baseline §3.2「payload 帶期限名稱」）。PRD-006 又用小寫 `review_expires_at`。E2E 必有一方失敗 | PRD-007 改為 `deadline=PAYMENT_DUE_AT`，另帶 `deadlineAt`（ISO-8601 值）供畫面顯示；AC-1.7 改斷言 `deadline = "PAYMENT_DUE_AT"` 且 `deadlineAt = 2026-11-23T16:00:00Z`。PRD-006 的命名風格另通知 prd-writer 對齊 |
| 4 | 重要 | EX-28、§4.2-3.3、BR-22 | BR-22 已分 INITIAL / SUPPLEMENT 兩種確認框文案，但 EX-28 只寫 INITIAL 文案；§4.2-3.3「由 O8 排程取消」對 SUPPLEMENT 不成立（O8 只處理 INITIAL，補款逾時是 PRD-023 T8）；SUPPLEMENT 確認框沒有任何 AC。另外 SUPPLEMENT 文案「駁回後此次變更將失效」與 baseline §3.4 / PRD-023 4.3-3 不符：補款逾時時減少的行程**維持取消**、總額重算、可能產生系統退款，並非整個變更失效（見 CQ-14） | EX-28 改為兩種文案並分別寫後果；§4.2-3.3 改為「INITIAL 由 O8 取消；SUPPLEMENT 由 PRD-023 補款逾時處理」；在子模組 2 的既有 AC（例 AC-2.3）補一個 SUPPLEMENT 逾期駁回的確認框斷言 |
| 5 | 重要 | §3、§5 P1–P3、EX-9 / EX-11 / EX-12 | 多個錯誤同時成立時沒有判斷順序，AC 結果不唯一：例 payment `SUBMITTED` 且已過 `due_at` 時上傳 → `PAYMENT_STATE_CONFLICT` 還是 `DEADLINE_PASSED`？訂單 `DISPUTED` 時飼主駁回退款 → 403 還是 409？O8 已取消後上傳 → EX-9（`PAYMENT_STATE_CONFLICT`）還是 EX-12（`DEADLINE_PASSED`）？ | 在 §3 末加一條判斷順序：401 → 403 `ROLE_MISMATCH` → 404 → 403 `FORBIDDEN`（非當事人 / 方向不符 / O16 待辦已關閉）→ 400 / 422（欄位、檔案）→ 409 `ORDER_STATE_CONFLICT` → 409 `PAYMENT_STATE_CONFLICT` → 409 `DEADLINE_PASSED`。依此修 EX-9（「含訂單已取消」維持 `PAYMENT_STATE_CONFLICT`）並在 AC-1.7 補「`SUBMITTED` 且已過期限 → `PAYMENT_STATE_CONFLICT`」 |
| 6 | 重要 | BR-2、§9 資料 | 收款帳號快照存在 `payments`（SD-ORDER），但沒寫快照要加密。NFR-03「敏感個資加密」：「銀行帳號在儲存層必須 AES-256 加密、金鑰與業務資料隔離」；PRD-025 只加密即時帳號，快照若存明文等於繞過（抽樣 #1 / #11 同類風險） | BR-2 補「快照的帳號欄位依 NFR-03 加密存放，與 PRD-025 同一金鑰管理方式」；§9 資料欄註明 |
| 7 | 重要 | §4.4-2、BR-13、AC-3.2；PRD-006 BR-11 | 排程條件是「`now ≥ 首日 − 48h` 且 `UNPAID` 且尚未提醒」，但「已提醒」標記只由排程自己寫；PRD-006 O2 隨報價附提醒時**沒有**寫這個標記。緊急單報價後第一次排程仍會符合條件 → 重複提醒，AC-3.2「緊急單排程不另發」不會通過 | 二擇一並在兩卡一致：(a) 排程條件改為「`quoted_at < 首日 − 48h`」才處理；(b) O2 附提醒時同交易寫入已提醒標記（PRD-006 補一句）。建議 (a)，不需改 PRD-006 |
| 8 | 重要 | §3 角色表 | 方向 × 角色 × 動作仍有空格：(1) `method=ADMIN_ADJUSTMENT` 的 payment 被呼叫上傳 / 確認 / 駁回 → 403 還是 409 未定義；(2) ADMIN 對 `SUPPLEMENT` / `MODIFICATION_REFUND` 呼叫 O16 作廢端點的回應未定義；(3)「駁回 `MODIFICATION_REFUND`」ADMIN 欄只寫 ✗ 沒有碼 | (1) 對 `ADMIN_ADJUSTMENT` 的寫入一律 409 `PAYMENT_STATE_CONFLICT`（已 `VERIFIED`），不判方向；(2) 非 INITIAL → 403 `FORBIDDEN`（無對應 OPEN 待辦）；(3) 補 403 `ROLE_MISMATCH` |
| 9 | 重要 | 子模組 1、2 AC | 越權格沒有 AC：匿名 401（全卡沒有一條）；ADMIN 上傳 → `ROLE_MISMATCH`；SITTER 確認 / 駁回退款 → `FORBIDDEN`；OWNER 駁回 CHARGE → `FORBIDDEN`；ADMIN 駁回 → `ROLE_MISMATCH` | 併入 AC-1.3（上傳類：匿名、ADMIN）與 AC-2.5（確認 / 駁回類：OWNER 駁回 CHARGE、SITTER 確認退款、ADMIN 駁回），不增 AC 數 |
| 10 | 重要 | §4.1-4-3、BR-12、EX-12 | 上傳的 `now < due_at` 在「GCS 上傳後、取得訂單鎖時」判斷。飼主 23:59:58 按送出、5 MB 上傳 3 秒 → 進交易時已過期限被 409，使用者看到的是「我在期限前送出卻被拒」。時間基準未定義（見 CQ-15） | 依 CQ-15 決定後寫入 BR-12，AC-1.7 補邊界值（`due_at − 1 秒` 收到請求、進交易時已過 `due_at` 的結果） |
| 11 | 建議 | EX-18、AC-3.8 | 併發清單缺：保母駁回 vs ADMIN 作廢（EX-25 有文字，AC-3.8 只測確認）；O8 排程 vs 保母駁回 / 飼主上傳；升級排程（A1）vs 保母確認 / 駁回。排程端都依 §3.6 鎖後重驗，但未寫出預期結果 | EX 加一列「排程 vs 使用者」：排程鎖後重驗失敗即跳過、不寫 log；AC-3.8 補駁回分支（ADMIN 後到 → 409 `PAYMENT_STATE_CONFLICT`、payment `UNPAID`、待辦 `RESOLVED`） |
| 12 | 建議 | §4.4-2 | 提醒條件沒有 `now < due_at`；排程停擺超過 24h 後恢復會在期限後才寄「請付款」提醒 | 條件補 `now < due_at` |
| 13 | 建議 | §4.1、§4.3 | 空狀態與多筆 payment 未定義：報價前（無 payment）付款區塊顯示什麼；submission 為 0 筆時歷史區顯示什麼；同一訂單同時有 INITIAL（`VERIFIED`）+ SUPPLEMENT / REFUND 時列表排序與哪一筆顯示上傳表單 | §4.1 補：付款區塊依 `created_at` 列出所有 payment，只有 `UNPAID` 且呼叫者為付款方的那筆顯示上傳表單；無 payment 不顯示區塊；歷史為空顯示「尚未上傳憑證」 |
| 14 | 建議 | AC-1.1、AC-2.7 | AC-1.1「畫面不出現訂單總額或差額公式」對 INITIAL 不可判定（數字相同），應改為不出現「訂單總額」標籤；快照範例 `812-0001-…` 的「0001」與 PRD-025 分行為文字（例「台北分行」）不符；AC-2.7 把 testid `payment-account-snapshot` 當 API 回應欄位 | AC-1.1 改斷言「無 `order-total-amount` 元素」並把分行改「台北分行」；AC-2.7 改為「回應含 `accountSnapshot`，畫面 `payment-account-snapshot` 唯讀」 |
| 15 | 建議 | AC-1.2、AC-1.5、AC-2.6、AC-2.7、AC-3.4、AC-3.7 | 單條 AC 塞 4–6 個情境（AC-1.2 同時驗退款頁、上傳表單、API 400 與 `ADMIN_ADJUSTMENT`）。三個子模組都已 8 條，再拆會超過 3 子模組 | 不強制拆；TS 撰寫時每個 Given 拆成獨立步驟並標同一 AC 編號。若之後要再加 AC，先把 ADMIN 作廢（AC-3.6–3.8）與 ADMIN 讀取（AC-2.7 後半）拆成新卡 |
| 16 | 建議 | BR-20 | 「服務執行在 `CONFIRMED` 或 `IN_PROGRESS` 開放」屬 PRD-008，本卡沒有 AC 覆蓋，留著會變成無人驗收的規則 | 刪除或改為「見 PRD-008」 |
| 17 | 建議 | 表頭「引用基準」 | 「§3.1（`PENDING_PAYMENT` / `DISPUTED` 凍結）」——baseline 只有 `DISPUTED` 凍結 | 改為「§3.1（`PENDING_PAYMENT` 定義、`DISPUTED` 凍結）」 |
| 18 | 建議 | §4.3 | payment 與 submission 的 ID 屬於另一張訂單時（`/orders/A/payments/{B 的 payment}`）回 404 還是 403 未寫 | 以路徑上的訂單為準，payment / submission 不屬於該訂單 → 404 `NOT_FOUND` |

## 待使用者回答

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-14 | SUPPLEMENT 逾期後駁回的確認框文案「駁回後此次變更將失效」與實際後果不符（減少的行程維持取消、總額重算、可能產生系統退款，baseline §3.4 / CQ-53）。文案要改嗎？ | 「已過補款期限，駁回後新增的行程將不會生效（已減少的行程維持取消）」 |
| CQ-15 | 上傳憑證的期限判斷用哪個時間：伺服器收到請求的時間，還是取得訂單鎖時的時間？（大檔上傳數秒，期限前送出可能被判逾期） | 以伺服器收到請求的時間判斷；但進交易時 payment 已被 O8 作廢則仍 409 `PAYMENT_STATE_CONFLICT` |
| CQ-16 | 付款相關通知（上傳、確認、駁回、付款提醒、逾期取消、升級、作廢）是否受 PRD-014 使用者通知偏好影響？本卡 AC 都假設 Email 一定排入 | 付款期限、逾期取消、駁回、作廢列為不可關閉的關鍵通知；其餘（上傳、確認）依 PRD-014「訂單事務」偏好 |
