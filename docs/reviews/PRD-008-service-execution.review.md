# 審查：PRD-008（第 1 輪，2026-11-22）

結論：需釐清

審查範圍：PRD-008 全文；對照 baseline 第 3 版修訂（§1.2、§3.2 O9 / O11–O17、§3.6、§3.7、§5、§6）、SD-ORDER §2.3.1 / §2.4 / §3.1 / §3.6.3 / §7.1 / §7.2 / §8.1 C7、PRD-009、PRD-022、PRD-028、PRD-023、BR §5.2、SD-FRONTEND-SPEC §6.4、`reports/rereview/00-sample-SD-007.md` P1–P5。AC 數 8 / 6 / 7 = 21（每子模組 ≤ 8）。

狀態機檢查結果：T1–T4 與 `PENDING → CANCELLED`（他卡）的觸發者、前置條件、錯誤碼齊全；終態無轉出；O9 只由 T1 / T3 觸發、`CANCELLED` 不觸發，與 baseline O9、SD-ORDER `onVisitLeftPending` 相容；`completion_basis_at` 由 `onVisitFinished` 重算，`DONE` = `completed_at + 24h`、`CLOSED_BY_SYSTEM` = `closed_at`，與 baseline §5 一致。AC 時間算術（72h 殭屍點、24h 逾時、Taipei 換算）逐條驗算無誤。

## 問題清單
| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 重要 | 4.7 步驟 2、BR-8、AC-3.7 | 命名的檢查順序前後矛盾（P4）：BR-8 把「名稱 > 20 字 → 400」放在第 6 步（早於 409），4.7 步驟 2 卻把長度檢查寫在 `DEADLINE_PASSED` 之後最後一步。`CLOSED_BY_SYSTEM` 行程送 21 字名稱時，一邊得 400、一邊得 409 `VISIT_STATE_CONFLICT` | 4.7 步驟 2 改為依 BR-8：400（長度）在鎖之前；AC-3.7 加一個組合情境（`CLOSED_BY_SYSTEM` + 21 字 → 400 `VALIDATION_FAILED`）鎖定唯一結果 |
| 2 | 重要 | 4.6 步驟 1、§9 testid、AC-3.3 | 飼主訂單詳情行程列 testid 與 PRD-009 衝突：本卡 `owner-order-detail-visit-row-{visitId}`，PRD-009 §4.4 / §9 / AC-1.7 為 `owner-order-detail-visit-item-{visitId}`；PRD-028 4.4 也用 `-visit-row-`。同一元素兩個 testid，E2E 必有一邊找不到 | 三卡統一一個名稱（建議沿用 PRD-009 已寫入 AC 的 `-visit-item-`，或反之改 PRD-009）；PRD-028 4.4 / §9 一併改 |
| 3 | 重要 | BR-14 預設名稱 | 預設名稱 `{方案名稱}` 未指定來源（P5 快照）：是方案現值、O1 時 `createVisits` 帶入的 `planTitle`、還是 O2 報價快照（baseline §2.2 快照方案名稱）？保母之後改方案名稱時既有行程名稱會不會跟著變，AC 無法判定 | 見 CQ-9 |
| 4 | 重要 | BR-14、4.7；PRD-022 BR-6 | 同一需求被兩張卡各做一個名稱（P4）：BR §5.2 只有「保母可以編輯日誌名稱（早上服務 / 晚上服務）」，本卡做「行程名稱」（0–20 字，預設 `{方案名稱} 第 n 次`），PRD-022 又做「日誌標題」（1–30 字，預設「{行程日期} 行程」）。飼主會看到兩個不同名稱；本卡 BR-14 還說名稱用在「日誌 PRD-022」，與 PRD-022 自己的標題衝突 | 見 CQ-10 |
| 5 | 重要 | EX-13、AC-2.4 情境 3 | AC 要求保母結束時得 409 後畫面顯示「此行程已由系統自動結束」，但 409 `VISIT_STATE_CONFLICT` payload 只有 `currentVisitStatus=DONE`，EX-7 對 `DONE` 的文案是「此行程已結束」。前端無法從回應分辨完成來源 `SYSTEM` / `SITTER`，此 AC 不可實作 | `VISIT_STATE_CONFLICT` 在行程為 `DONE` 時另帶 `completionSource`（`SITTER` / `SYSTEM`），或明訂前端收到 409 後重新讀取行程再決定文案；EX-7 文案表補上 `DONE` + `SYSTEM` 一列 |
| 6 | 重要 | 4.3 步驟 3、BR-11、AC-2.1、§9 卡外依賴 | 殭屍關閉的通知沒有負責方：SD-ORDER §8.1 C7 逐筆處理只列 `closeZombieVisits` → `onVisitLeftPending` → `onVisitFinished` → `VISITS_CLOSED_BY_SYSTEM`，沒有 `enqueue`；§7.2 也沒有對應通知類型（O9 列為「不通知」）。本卡要求雙方各 1 則且同批合併，SD-FULFILL 寫 SD 時會不知道該在 `closeZombieVisits` 內 enqueue 還是要求 SD-ORDER 改 C7 | §9 卡外依賴明寫：通知由 SD-FULFILL `closeZombieVisits` 在同交易內 enqueue（每方 1 則，列出本批所有日期），類型代碼由 SD-FULFILL 定義；或列為 SD-ORDER 需補 C7 步驟與 §7.2 類型 |
| 7 | 建議 | 4.1 步驟 3 | 判斷順序寫成「冪等回放 → 401 → 403…」，BR-8 與 baseline §3.7 是「401 → 冪等回放」 | 改為 401 在前 |
| 8 | 建議 | 4.1 步驟 4、AC-1.1、§9 | `order_logs` 粒度：觸發 O9 時 `onVisitLeftPending` 已自寫 `ORDER_STARTED`（SD-ORDER §2.3.1），本卡寫「列法依 SD-ORDER 稽核粒度」不夠可測。另外 `VISIT_STARTED` / `VISIT_COMPLETED` / `VISIT_AUTO_COMPLETED` / `VISIT_RENAMED` 由 SD-FULFILL 寫入，但 SD-ORDER §2.3.1 未對外列出 `OrderLogWriter` | AC-1.1 加「無 `VISIT_STARTED`」；§9 卡外依賴寫明需 SD-ORDER §2.3.1 補列 `OrderLogWriter`（`MANDATORY`）供 SD-FULFILL 使用 |
| 9 | 建議 | §9 資料 | 欄位名：本卡統一用 `completed_at`，SD-ORDER §2.4.2 `VisitView.completedAt` 註記「對應 `visits.finished_at`」 | §9 註明 SD-FULFILL 以 `completed_at` 為準並回寫 SD-ORDER §2.4.2 註記（或反之），避免兩欄並存 |
| 10 | 建議 | 4.1 步驟 2 | 面板只定義「今天 < 行程日期 → 停用」，未定義 `now ≥ VISIT_ZOMBIE_AT`、排程尚未關閉時「開始行程」按鈕狀態；目前會顯示可按、按下才得 409 | 補：已過 `VISIT_ZOMBIE_AT` 時按鈕停用並顯示 EX-9 文案，testid 一併列 |
| 11 | 建議 | §3 權限表「保母今日行程」 | ADMIN 欄只寫「✗」沒有錯誤碼 | 寫 403 `ROLE_MISMATCH` |
| 12 | 建議 | EX-12 | 「開始先 → O15 / O17 前置不成立（PRD-020 處理）」：PRD-020 尚未改寫；SD-ORDER E29 已定 409 `VISITS_NOT_FINISHED` | 改引 SD-ORDER E29 / baseline O15 的 409 `VISITS_NOT_FINISHED` |
| 13 | 建議 | §7 例外 | 缺跨角色併發：殭屍排程關閉 V 與飼主同意減少 V 的變更同時（PRD-023）。排程先 → 同意得 409 `VISIT_ALREADY_STARTED`；同意先 → V `CANCELLED`、排程重驗略過 | 補 EX 一列（可併入 EX-11） |
| 14 | 建議 | 4.2 步驟 1 | 結束確認框只定義「尚有 {n} 項未打勾」，未定義 n = 0 或行程無檢核項時是否顯示該行 | 補：n = 0 時不顯示 |
| 15 | 建議 | BR-14、4.7 步驟 2 | 命名逾期以 `now` 判斷；PRD-022 同一期限名稱 `CARE_LOG_EDIT_ENDS_AT` 以伺服器收到請求時間 `receivedAt` 判斷（baseline §5） | 統一寫「伺服器收到請求時間」 |
| 16 | 建議 | T4、4.4 步驟 1 | T4 前置條件未寫訂單狀態；`IN_PROGRESS` 行程的訂單只可能是 `IN_PROGRESS` / `DISPUTED`，但候選查詢與鎖後重驗是否限定訂單狀態未寫 | T4 前置條件補「訂單 `IN_PROGRESS` / `DISPUTED`（重驗防線）」 |

## 待使用者回答
| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-9 | 行程預設名稱中的 `{方案名稱}` 取自哪裡：方案現值、O1 建立行程時的方案名稱，或 O2 報價快照（baseline §2.2） | 取 O2 報價快照的方案名稱（baseline §2.2 已快照方案名稱）；保母之後修改方案名稱不影響既有行程；變更新增的行程取該方案在訂單快照中的名稱 |
| CQ-10 | BR §5.2 只有一個「日誌名稱」，本卡（行程名稱，BR-14）與 PRD-022（日誌標題，BR-6）各做一個，長度、預設值、可改時機都不同，是否合併 | 合併為單一「行程名稱」（本卡 BR-14），PRD-022 移除日誌標題欄位，日誌與通知改顯示行程名稱；可改時機沿用本卡 BR-14 |
