# 審查：PRD-024（第 1 輪，2026-11-21）

結論：需釐清

AC 數：16（子模組 1 / 2 = 8 / 8）。舊設計殘留：無。

## 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 重要 | §4.2-3、BR-6、AC-2.3 | AC-2.3 斷言「退款 payment 為 `SUBMITTED` 且 ADMIN 作廢 → 同交易另有一筆等額 `ADMIN_ADJUSTMENT`」。BASELINE §3.3 的條件是「已實際匯出的 `SUBMITTED` 退款」，系統無法自行判斷是否已匯出，誰來判定、何時不建都沒寫。寫成「SUBMITTED 一律自動建」，保母上傳假憑證時飼主會被記成已收到退款 | 列 CQ-4；AC-2.3 拆成「已匯出 / 未匯出」兩個 Given |
| 2 | 重要 | AC-2.8 | 一條 AC 塞了 7 個動作加 PLAN_REQUIRED 加公開頁預約，Then 寫「依各自狀態規則成功或回對應 409」：不可觀察（P1），TS 無法寫出預期值 | 改成表格：每個動作列出 Given 狀態與預期回應（例：發起 → 201 `QUOTED`；確認補款 → 200 `SETTLED`；上傳退款憑證 → 200 payment `SUBMITTED`；申報 → 200 `DISPUTED`），或只留「皆不回 403 `SUBSCRIPTION_LOCKED`」並把各動作的成功路徑交給各自的卡 |
| 3 | 重要 | §3、BR-10、AC-2.4 | ADMIN 可讀範圍寫「有逾期待辦」；BASELINE 第 2 版已統一為「OPEN 待辦」（§1.2、§8） | 三處改「`DISPUTED` 或有 OPEN 待辦」 |
| 4 | 重要 | 相依 PRD、AC-2.3、AC-2.5~2.7 | 本卡把 O11 / O12 / O13 的驗收寫成「PRD-009 端點的可觀察結果」，把 O15 / O17 寫成「PRD-020 操作的結果」。但 PRD-009 現行版本仍以 `MODIFYING` / `REFUND_VERIFY` / `PAID` 主狀態判斷（§A.4、例外表），沒有 `MODIFICATION_IN_PROGRESS` 與 `completion_basis_at`；PRD-020 完全沒有 O15 / O17、調解或待辦處理。這兩張卡重寫前，AC-2.3、AC-2.5~2.7 沒有可呼叫的端點定義 | 表頭相依欄註明 PRD-009 / PRD-020 待重寫；在 00-PRD-PLAN 把 PRD-009、PRD-020 重寫排在 SD-ORDER 之前，或在本卡寫清楚這幾條 AC 需要哪些端點行為（錯誤碼、排程條件），讓 PRD-009 / 020 重寫時照抄 |
| 5 | 建議 | T2 | 「`AWAITING_*` / 其他未結束狀態 → `REJECTED`」超出 BASELINE §3.4 狀態圖（只有 `AWAITING_SUPPLEMENT` / `AWAITING_REFUND` → `REJECTED`）。`REQUESTED` / `QUOTED` 不可能跟 `DISPUTED` 並存（O13 要求無未結束變更，O14 只接受 `AWAITING_*`） | 刪「其他未結束狀態」 |
| 6 | 建議 | §4.2-3、BR-6 | ADMIN 作廢 `SUBMITTED` 補款（飼主可能已實際匯款給保母）時的金額處理沒定義；BASELINE 只寫了退款方向 | 列 CQ-5 |
| 7 | 建議 | §4.4、BR-9、AC-2.8 vs PRD-016 BR-17、BR-18、AC-2.8 | 鎖定期間計價在兩張卡重複定義、重複驗收 | 本卡只驗「不回 `SUBSCRIPTION_LOCKED`」，計價引用 PRD-016 |
| 8 | 建議 | BR-7、BR-8（推測，請自行判斷） | 每筆變更結束都會把 `completion_basis_at` 往後推，且有未結束變更時 O12 暫停。`IN_PROGRESS` 訂單在行程全部 `DONE` 後，OWNER 仍可發起只含新增的 `CHANGE`；反覆發起 / 撤回可以無限延後自動結案與保母入帳。這是 BASELINE CQ-17 的直接結果，不是本卡錯誤 | 先記錄風險即可；若要處理，可考慮「行程全部結束後只允許 SITTER 發起」或撤回不計入基準時間，需另開 BASELINE CQ |
| 9 | 建議 | AC-1.3 | 3 組 Given（首筆 72h、駁回重傳重新起算、UNPAID）塞在同一條 | 可保留，或把 UNPAID 併入 AC-1.4 |
| 10 | 建議 | AC-2.1 | 斷言 `DISPUTED` 且有 `AWAITING_REFUND` 時「發起新變更 → `ORDER_STATE_CONFLICT`」，依賴 PRD-016 尚未定義的錯誤碼優先序（主狀態先於 `MODIFICATION_IN_PROGRESS`） | 等 PRD-016 補優先序 BR 後在此引用 |

## 待使用者回答

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-4 | ADMIN 調解作廢 `SUBMITTED` 退款時，「已實際匯出」由誰判定 | ADMIN 調解畫面對每筆 `SUBMITTED` 退款必選「已匯出 / 未匯出」（必填，寫入 `order_logs`）；已匯出 → 同交易自動建立等額 `ADMIN_ADJUSTMENT`（`VERIFIED`，計入已收淨額上限檢查）；未匯出 → 只 `VOID` |
| CQ-5 | ADMIN 調解作廢 `SUBMITTED` 補款（飼主可能已匯款）時怎麼記 | 比照 O16：不登記任何金額，通知雙方「此筆補款由雙方自行處理」；已收淨額不含該筆 |
