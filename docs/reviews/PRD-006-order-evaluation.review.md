# 審查：PRD-006（第 1 輪，2026-11-21）

結論：需釐清

基準：`docs/sa/00-DOMAIN-BASELINE.md` 第 2 版（O2、§3.5、CQ-58/59/62/63/67、§5 公式）、`reports/rereview/00-sample-SD-007.md` P1–P5、PRD-007（payment 與付款提醒）。AC 數 20（5 / 7 / 8）。

與 baseline 的一致性：O2 的全部前置條件與錯誤碼（含 CQ-62 月額度、CQ-63 收款帳號）、§3.5 問卷唯讀、CQ-67 免填原因都對得上。AC-2.4 / AC-2.5 / AC-3.4 的 `due_at`、`escalate_at`、`review_expires_at` 已逐一驗算，數值正確。和 PRD-027 的邊界清楚（婉拒 / O4 / O7 不在本卡）。

## 問題清單
| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 重要 | AC-2.5 第一例 | Given 只寫「首個行程為當天 12-10、12-10 09:00 報價」，沒寫送單時間。`review_expires_at = max(首日 12-10 00:00, 送單 + 2h)`：送單時間早於 12-10 07:00 時期限已過，報價會得到 409 `DEADLINE_PASSED` 而不是 200。照字面建測資會失敗（P1：Given 不完整） | Given 補「飼主於 2026-12-10 08:00（台北）送單 → `review_expires_at` = 12-10 10:00」 |
| 2 | 重要 | AC-3.5 第二例 | 「於 2027-01-01 00:00 之後報價 → 200」沒交代訂單的首個行程日期與 `review_expires_at`；12 月建立、首日在 12 月的訂單到 1 月已經過期 | Given 補「首個行程 2027-01-05、`review_expires_at` 尚未到」 |
| 3 | 重要 | §3、EX-3、AC-1.4（跨 PRD-026 EX-2、PRD-027 EX-3、baseline §1.1） | ADMIN 讀評估頁回 403 `FORBIDDEN`，但同一條 AC 裡「A 以 OWNER 角色讀取 → `ROLE_MISMATCH`」，表示這是保母角色專用端點；依 baseline §1.1，ADMIN token 打角色專用端點應該回 `ROLE_MISMATCH`（PRD-026 / PRD-027 也這樣寫）。如果評估頁其實是三方共用的訂單詳情端點，OWNER 角色那例就應該是 `FORBIDDEN`。同一條 AC 內兩種假設並存（P4） | 寫死端點性質：建議評估頁 = 保母專用端點 → ADMIN 改成 403 `ROLE_MISMATCH`（EX-3、AC-1.4）。ADMIN 讀 `DISPUTED` / 有 OPEN 待辦訂單走 PRD-007 / PRD-020 的端點 |
| 4 | 重要 | §4.3 步驟 3、BR-11、AC-2.5（跨 PRD-007 §9「已提醒標記」、AC-3.2） | 報價時已過首日 − 48h 會「隨報價通知附付款提醒、不另發」，但本卡沒寫要在同一交易設定 PRD-007 的「已提醒」標記；PRD-007 的提醒排程（每 15 分鐘、`now ≥ 首日 − 48h`）下一輪就會再發一次（P3：跨模組流程只寫一邊） | §4.3 步驟 3 加「附提醒時同交易設定首次收款 payment 的已提醒標記」；AC-2.5 加「之後付款提醒排程執行，飼主不再收到付款提醒」；PRD-007 AC-3.2 補對應例 |
| 5 | 重要 | AC-3.1、EX 表 | §3 表 ADMIN 報價為 ✗，但例外表與 AC 都沒寫 ADMIN 呼叫報價的錯誤碼 | AC-3.1 加「ADMIN 報價 → 403 `ROLE_MISMATCH`」，EX-2 併入 ADMIN |
| 6 | 建議 | BR-21、§4.1 步驟 2（跨 PRD-002 邏輯刪除） | 綁定的毛孩在送單後被飼主邏輯刪除，評估頁怎麼顯示沒寫；PRD-002 的讀取若過濾已刪除，評估頁會缺資料甚至 404 | 新增 CQ-11 |
| 7 | 建議 | AC-1.5、EX-7 | 鎖定時「頁首顯示鎖定提示」沒有文案（P1）；其他兩種預檢都有文案 | 定文案，例：「您的訂閱已到期或遭停權，暫時無法接新訂單」 |
| 8 | 建議 | AC-1.2 | 「也沒有對應 API」無法觀察 | 改成「呼叫舊端點 `POST /api/orders/{id}/questionnaire/request` → 404」，或刪除這句（交給 SD API 清單） |
| 9 | 建議 | BR-5、§4.3 步驟 2、AC-3.3 | 總額 > 999,999 回 400 `VALIDATION_FAILED`，但總額要等步驟 9 重算 `base_amount` 才算得出來，排在步驟 5「欄位格式」不合理；而且同樣是總額規則，< 1 回 422、> 999,999 回 400 | 把「總額上限」移到步驟 10 與 `INVALID_QUOTE_AMOUNT` 一起檢查；碼維持 400 或擴充 `INVALID_QUOTE_AMOUNT` 定義（後者要改 baseline §3.7）。另見 PRD-005 CQ-13（O1 端的上限） |
| 10 | 建議 | §4.3 步驟 1、BR-7 | 沒寫缺 `expectedBaseAmount` 或缺 `Idempotency-Key` 的結果 | 補「缺少 → 400 `VALIDATION_FAILED`」 |
| 11 | 建議 | AC-3.4 | 沒有邊界例 `now == review_expires_at` | 加一例「`now = review_expires_at` → 409 `DEADLINE_PASSED`」 |
| 12 | 建議 | §4.3 步驟 1 vs PRD-005 | 畫面金額欄位本卡叫 `expectedBaseAmount`、PRD-005 叫 `expectedAmount`；`PRICE_CHANGED` payload 本卡用 `base_amount`、PRD-005 用 `baseAmount` | 兩卡統一（見 PRD-005 審查 #13） |
| 13 | 建議 | AC-2.1、AC-2.4 | 報價通知斷言沒有固定 Email 偏好（PRD-014）；本卡 BR-19 寫依偏好，但 AC 也沒有斷言站內通知 | 斷言「站內通知 1 則」，Email 部分寫明依偏好 |
| 14 | 建議 | 全卡 | 沒有 testid（原價接單、調整價格、送出報價、問卷區塊、預檢提示） | 補 `btn-quote-original`、`btn-quote-adjust`、`btn-quote-submit`、`questionnaire-answers`、`questionnaire-exempt-label`、`quote-precheck-banner` |
| 15 | 建議 | 變更紀錄 | 舊列還寫「飼主撤回移至 PRD-005」「`@Version`（EX-26）」，現已改成 PRD-026 / EX-22 | 歷史列可以不改；最新一列補一句「撤回現屬 PRD-026」，避免讀者誤判 |

## 待使用者回答
| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-11 | 送單時綁定的毛孩，之後被飼主邏輯刪除（PRD-002），評估頁與飼主訂單詳情怎麼顯示？ | 照樣顯示該毛孩的基本資料，並標示「已刪除」；baseline §1.2 保母可讀「有過訂單的飼主的毛孩」，包含已刪除者；不另做毛孩快照 |
