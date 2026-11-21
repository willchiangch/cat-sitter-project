# 審查：PRD-027（第 1 輪，2026-11-21）

結論：需釐清

基準：`docs/sa/00-DOMAIN-BASELINE.md` 第 2 版（O3 SITTER、O4、O7、CQ-12、CQ-13、CQ-61、CQ-65、§3.6 排程）、`reports/rereview/00-sample-SD-007.md` P1–P5、BR §3.6 / §5.3、PRD-010（Draft）。AC 數 18（7 / 4 / 7）。

與 baseline 的一致性：O3 / O4 / O7 的前置條件、CQ-61 期限例外、CQ-65 附帶動作、`PAYMENT_ALREADY_SUBMITTED`、排程鎖後重驗都正確。O4 的時間基準（`orders.review_expires_at`，UTC，`now ≥`）寫得清楚。與 PRD-026（撤回）、PRD-006（報價）的邊界清楚，互相引用的編號（PRD-006 AC-3.4、EX-10）正確。

## 問題清單
| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 重要 | §4.1 步驟 2、§4.3 步驟 2、§9 通知、AC-1.1、AC-3.1（跨 PRD-026 BR-6） | BR §3.6 步驟 4 寫「取消接單…可能由保母或飼主發動，需信件通知雙方」。PRD-026 依這條通知雙方，本卡的婉拒與 O7 卻只通知飼主。這是與出處不一致，不是產品決策 | 婉拒、O7 改成通知雙方（保母收到確認通知，例：「您已婉拒 {飼主} 的預約」「您已取消訂單」）；AC-1.1、AC-3.1 補保母端站內通知斷言 |
| 2 | 重要 | §4.1 步驟 3、BR-3、AC-1.5（跨 PRD-010） | PRD-010（Draft）B.1 寫的是單一動作「不接單並轉介」，AC-2「在婉拒訂單的流程中」；本卡決定拆成兩步，婉拒先讓訂單 `CANCELLED`。PRD-010 若沿用「訂單須為 `PENDING`」或沒定義前置條件，兩步流程第二步就會被拒。另外，轉介入口關掉之後能不能從歷史訂單再開，沒定義（P3） | 新增 CQ-5；PRD-010 重寫時要寫入轉介的前置條件 |
| 3 | 建議 | AC-1.3 | EX-3 列了 ADMIN 婉拒 → 403 `ROLE_MISMATCH`，AC-1.3 沒有這例（AC-3.4 的 O7 有） | AC-1.3 加「ADMIN → 403 `ROLE_MISMATCH`」 |
| 4 | 建議 | §3 第 2 點、EX 表 | 「排程端點只接受 Cloud Scheduler 身分」，但沒寫其他身分呼叫的結果，也沒有 AC | 補 EX：非 Scheduler 身分呼叫 `/api/internal/cron/*` → 403 `FORBIDDEN`、不處理任何訂單；AC-2.4 或新增例。若這屬 NFR / SD-GLOBAL-SPEC 的共通規則，改成引用 |
| 5 | 建議 | AC-2.1、AC-2.2 | 沒有邊界例 `now == review_expires_at`（AC-2.1 用 16:05、AC-2.2 用 15:59） | 加「排程於 `16:00:00Z` 整執行 → 取消」 |
| 6 | 建議 | EX-10、AC-3.5 | 「飼主上傳被拒（碼依 PRD-007）」沒寫碼；PRD-007 EX-18 已定為 409 `PAYMENT_STATE_CONFLICT`（P2） | 直接寫出 409 `PAYMENT_STATE_CONFLICT`，並斷言 GCS 物件已刪除（PRD-007 AC-1.8） |
| 7 | 建議 | EX-7、AC-3.3（跨 PRD-026 EX-5） | 同一個 409 `ORDER_STATE_CONFLICT` 要依目前狀態顯示兩種文案，前端要知道 `currentStatus`，payload 沒定義 | `ORDER_STATE_CONFLICT` payload 帶 `currentStatus`（建議寫成 baseline §3.7 的共通規則），AC-3.3 斷言 |
| 8 | 建議 | EX-19 | 「排程單筆處理失敗」沒有 HTTP / 可觀察結果（P2）；排程整體回應碼與失敗筆數沒定義 | 寫明排程端點回 200，body 含 `processed` / `skipped` / `failed` 計數；失敗筆寫錯誤 log |
| 9 | 建議 | T4、T5 | 附帶轉移的觸發者寫 SYSTEM；PRD-026 同類轉移寫 OWNER | 兩卡統一寫法（見 PRD-026 審查 #7） |
| 10 | 建議 | BR-11 | 沒寫缺 `Idempotency-Key` 的結果 | 補「缺少 → 400 `VALIDATION_FAILED`」 |
| 11 | 建議 | AC-1.1、AC-2.1、AC-3.1 | 通知斷言沒有固定 Email 偏好（PRD-014）；BR-10 寫依偏好，AC 只斷言「收到」 | 斷言站內通知 1 則（含文案），Email 寫明依偏好 |
| 12 | 建議 | 全卡 | 沒有 testid（婉拒按鈕、原因欄、快速帶入按鈕、轉介入口、取消訂單按鈕） | 補 `btn-decline-booking`、`decline-reason-input`、`decline-quick-reason-{n}`、`btn-referral-entry`、`btn-sitter-cancel-order`、`sitter-cancel-reason-input` |

## 待使用者回答
| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-5 | 婉拒後的轉介：PRD-010 要接受哪種狀態的訂單？轉介入口關掉後還能從歷史訂單再開嗎？ | PRD-010 的轉介前置條件 = 訂單 `CANCELLED`、`cancelledBy=SITTER`、有 `ORDER_REJECTED` log、呼叫者為該訂單 sitter；入口只在婉拒成功的對話框提供，關掉就不再提供（避免事後打擾飼主）；PRD-010 的「不接單並轉介」改成本卡的兩步驟 |
