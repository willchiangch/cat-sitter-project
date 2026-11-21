# 審查：PRD-026（第 1 輪，2026-11-21）

結論：需修正

基準：`docs/sa/00-DOMAIN-BASELINE.md` 第 2 版（O3 OWNER、O6、CQ-3、CQ-7、CQ-61、CQ-65）、`reports/rereview/00-sample-SD-007.md` P1–P5、BR §3.3 / §3.6。AC 數 8（1 個子模組）。

與 baseline 的一致性：O3 OWNER / O6 的前置條件、`PAYMENT_ALREADY_SUBMITTED`、CQ-61 不檢查期限、CQ-65 取消附帶動作、CQ-7 付款後不提供撤回都正確，沒有殘留 `DEADLINE_PASSED`。和 PRD-027 的邊界清楚（撤回只寫在本卡）。

## 問題清單
| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 重要 | AC-1.1、AC-1.2（跨 PRD-014、PRD-006 BR-19、PRD-027 BR-10） | 斷言「保母與 O 各有 1 筆 Email 投遞紀錄」，但 Email 依 PRD-014 偏好；PRD-006 / PRD-027 寫的是「依偏好」。Given 沒有固定偏好 | Given 加「雙方的訂單類 Email 偏好為開啟」，或把斷言改成「站內 1 則；Email 依偏好排入」 |
| 2 | 建議 | §4.1 步驟 1、BR-7、BR-10 | 沒寫缺 `expectedStatus` 或缺 `Idempotency-Key` 時的結果（PRD-005 BR-15、PRD-007 BR-18 都寫了 400） | 補「缺 `expectedStatus` 或 `Idempotency-Key` → 400 `VALIDATION_FAILED`」，寫進 EX-18 並在 AC-1.8 加一例 |
| 3 | 建議 | BR-11、EX-18 | 原因只填空白字元時，要存空白字串、存 null 還是回 400？PRD-027 BR-9 寫了「去除前後空白」，本卡沒寫 | 去除前後空白後為空 → 視為未填（存 null），長度以去空白後計 |
| 4 | 建議 | §4.1 步驟 3.4、AC-1.1 | 通知只寫「1 則站內通知」，沒給標題 / 文案，AC 只驗得到「含原因」（P1）。PRD-027 的婉拒 / 逾期都有文案 | 定文案，例：保母「飼主已撤回預約（{日期}）」＋原因；飼主「您已撤回預約」；AC-1.1 斷言標題 |
| 5 | 建議 | 表頭「歸屬 SD」 | 「§0 對應表待主流程補上」已過時：`docs/sd/00-SD-PLAN.md` §0 已列 PRD-026 | 刪除括號註記 |
| 6 | 建議 | EX-5、AC-1.4（跨 PRD-027 EX-7） | `CONFIRMED` / `IN_PROGRESS` 要改顯示 PRD-016 入口，但錯誤只有 409 `ORDER_STATE_CONFLICT`，前端要知道目前狀態才能選文案；本卡與 PRD-027 EX-7 都依賴這點，卻沒定義 payload | `ORDER_STATE_CONFLICT` payload 帶 `currentStatus`（建議寫進 baseline §3.7 的共通 payload 規則），AC-1.4 斷言 payload |
| 7 | 建議 | T3、T4 | 附帶轉移的觸發者寫「OWNER（隨 T2）」，PRD-027 T4 / T5 寫「SYSTEM（隨 T3 同交易）」，同一種附帶動作在兩張卡的寫法不同 | 兩卡統一（建議寫「隨主轉移，同交易」，觸發者欄填主轉移的觸發者） |
| 8 | 建議 | AC-1.1、AC-1.7、AC-1.8 | 單條塞多個獨立情境（AC-1.7 = 畫面過期 + 跨角色併發 + 鎖逾時；AC-1.8 = 冪等 + Resend 失敗）。已到 8 條上限 | 不拆，每個情境各自寫完整 Given/When/Then，TS 逐情境對應 |
| 9 | 建議 | 全卡 | 沒有 testid（撤回按鈕、確認視窗、原因欄、無法撤回提示） | 補 `btn-withdraw-booking`、`withdraw-reason-input`、`btn-confirm-withdraw`、`withdraw-blocked-message` |

## 待使用者回答
| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| — | 本輪沒有新增 CQ | — |
