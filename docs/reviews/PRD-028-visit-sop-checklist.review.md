# 審查：PRD-028（第 1 輪，2026-11-22）

結論：可核准

審查範圍：PRD-028 全文；對照 baseline 第 3 版修訂（§1.2、§2.2、§3.1、§3.2 `DISPUTED` 行程照常、§3.6、§3.7）、SD-ORDER §2.4.2（`createVisits`、`ServicePlanQuery`）、PRD-008（行程狀態、結束確認框）、PRD-003（Draft）、PRD-006 BR-15、PRD-023、BR §5.2 / §3.5、`docs/sd/00-SD-PLAN.md` §0。AC 數 6 / 4 = 10（每子模組 ≤ 8）。

已確認無問題：編輯窗口（訂單 `CONFIRMED` / `IN_PROGRESS` / `DISPUTED` 且行程 `PENDING` / `IN_PROGRESS`）與打勾窗口（行程 `IN_PROGRESS`）錯誤碼與判斷順序一致（第 8 / 9 / 12 步，AC-1.5 情境 4、AC-2.2 情境 1–2 鎖定唯一結果）；打勾不影響 `DONE`（與 PRD-008 BR-5、baseline §6 一致）；寫入經訂單鎖、與 T4 / 變更取消序列化；初始帶入為快照、上限處理依已決 CQ-7；越權 / 角色 / 404 先於 400 皆有 AC。

## 問題清單
| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 建議 | 4.4、§9 testid；PRD-009 | 依附的飼主行程列 testid `owner-order-detail-visit-row-{visitId}` 與 PRD-009 `owner-order-detail-visit-item-{visitId}` 不一致（見 PRD-008 審查 #2） | 隨 PRD-008 統一後同步改 |
| 2 | 建議 | 表頭「歸屬 SD」、§9 卡外依賴 | 「00-SD-PLAN §0 待主流程登記」已過時：`docs/sd/00-SD-PLAN.md` 第 10 行 SD-FULFILL 已列 PRD-028 | 刪除該註記 |
| 3 | 建議 | BR-10、4.3 步驟 1 | 打勾為整值寫入、「重送結果相同」，但未定義已勾再送「勾」時 `checked_at` 是否更新；更新則重送結果不同 | 補：目標狀態與現值相同時不改資料（`checked_at` 不變），回 200 |
| 4 | 建議 | §9 卡外依賴、BR-2 | 初始帶入需在 O1 / 變更 `SETTLED` 交易內讀方案預設任務，但 SD-ORDER §2.4.2 `ServicePlanQuery.findForBooking` 的 `PlanView` 不含任務清單，`createVisits` 參數也沒有；PRD-003（Draft）第 39 行寫「預設任務清單…可於訂單評估時修改」，與 PRD-006 BR-15（訂單不含工項）及本卡（行程建立時快照、逐行程編輯）衝突 | 卡外依賴補兩項：SD-FULFILL 需向 SD-SUPPLY 取得預設任務的查詢方法（`SUPPORTS`，於行程建立交易內呼叫）；PRD-003 改寫時刪除「可於訂單評估時修改」 |
| 5 | 建議 | AC-1.4 情境 5 | 「面板與 API 皆無套用功能」沒有 Given / When，否定式斷言難以觀察 | 改為：When 開啟面板，Then 不存在 `sitter-visit-panel-checklist-btn-apply*`；API 部分刪除（不存在的端點不需驗收） |

## 待使用者回答
| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| — | 無新增 | — |
