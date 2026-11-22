# 審查：PRD-022（第 1 輪，2026-11-22）

結論：需釐清（另有 1 項阻擋需 prd-writer 修正）

審查範圍：PRD-022 全文；對照 baseline 第 3 版修訂（§1.2 CQ-81、§2.1 / §2.2、§3.2 O11 / O12 / O15 / O17、§3.6、§3.7、§5 `CARE_LOG_EDIT_HOURS` / `completion_basis_at`、§6）、SD-ORDER §2.3.1 / §2.4.1 `LockedOrder` / §3.1.1 O11 / O12 / O15 / §7.2、PRD-008、PRD-009 BR-25 / CQ-6、PRD-013、BR §5.2、`reports/rereview/00-sample-SD-007.md` P1–P5。AC 數 8 / 8 / 8 = 24（每子模組 ≤ 8）。

已確認無問題：判斷順序與 baseline §3.7 對齊（`VISIT_STATE_CONFLICT` 第 9 步、`CARE_LOG_STATE_CONFLICT` 緊接其後、`DEADLINE_PASSED` 第 10 步、模組 422 第 12 步、不用 `PLAN_REQUIRED`）；期限以 `receivedAt` 判斷；系統逾時轉 `DONE` 與手動結束同規則（BR-27、AC-1.6）；額度依 O2 快照、GCS 交易外、冪等在 GCS 前、孤兒檔兜底；owner / ADMIN 讀草稿 404（CQ-81）；O15 鎖草稿（CQ-16）。AC 時間算術驗算無誤。

## 問題清單
| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 阻擋 | BR-4 vs §3 判斷順序第 8 步、§4.1-1、§4.2-2.3、L1 / L3、EX-6、§3「唯一結果」 | 同卡規則互相矛盾（P4）：BR-4（依 PRD-009 BR-25 / CQ-6 修訂）允許「由 O11 / O12 進入的 `COMPLETED`」，但第 8 步、開放條件、送出重驗、L1 / L3 前置條件、EX-6 仍寫「訂單非 `IN_PROGRESS` / `DISPUTED` → 409 `ORDER_STATE_CONFLICT`」；「唯一結果」又寫「訂單 `COMPLETED` 且已逾期 → `ORDER_STATE_CONFLICT`」未限定 O15。實作照第 8 步做就違反 BR-4，照 BR-4 做則需要知道結案來源，但 SD-ORDER §2.4.1 `LockedOrder` 只暴露 `orderId` / `status` / `ownerId` / `sitterId`，沒有 `completed_by`。另外沒有任何 AC 驗證 O11 / O12 結案後的行為。補充推導：O11 要求所有 `DONE` 行程日誌已送出、O12 發生於 `completion_basis_at + 48h`（必晚於每個 `completed_at + 24h`），所以 O11 / O12 結案後不會有寫入成功，差別只在錯誤碼（`CARE_LOG_STATE_CONFLICT` / `DEADLINE_PASSED` vs `ORDER_STATE_CONFLICT`），但 TS / E2E 會依 AC 斷言錯誤碼 | 依已決的 BR-4 統一全文：第 8 步改為「訂單非 `IN_PROGRESS` / `DISPUTED`，且非（`COMPLETED` 且 `completed_by ∈ {OWNER, SYSTEM}`）→ `ORDER_STATE_CONFLICT`」，同步改 §4.1-1、§4.2-2.3、L1、L3、EX-6、「唯一結果」（改為「訂單經 O15 `COMPLETED` 且已逾期 → `ORDER_STATE_CONFLICT`」）；新增 AC 情境：O11 結案後暫存 → 409 `CARE_LOG_STATE_CONFLICT`、O12 結案後對逾期草稿暫存 → 409 `DEADLINE_PASSED`；§9 卡外依賴登記 SD-ORDER `LockedOrder` 需加 `completedBy`（或 `OrderQueryService` 提供），屬已核准 SD-ORDER 的介面變更 |
| 2 | 重要 | BR-6、US-2、AC-1.3、§4.5-3；PRD-008 BR-14 | 同一需求兩個名稱（P4）：BR §5.2 只有「保母可以編輯日誌名稱」，本卡做日誌標題（1–30 字，預設「{行程日期} 行程」），PRD-008 做行程名稱（0–20 字，預設 `{方案名稱} 第 n 次`）並宣稱用於日誌顯示 | 見 CQ-18 |
| 3 | 重要 | BR-17、§9 通知、AC-3.1、EX-25 | 通知通道與 SD-ORDER / PRD-008 不一致：AC-3.1「O1 關閉訂單事務 → 不產生該通知」、EX-25「通知只有站內，無外部寄送」；SD-ORDER §7.2 與 PRD-008 §9 的共同規則是「站內一律寫，Email 依 PRD-014 偏好」。同一 SD-FULFILL 內兩種語意 | 改為站內一律寫入、Email 依「訂單事務」偏好；AC-3.1 改為「關閉後仍有站內通知、無 Email 投遞紀錄」；EX-25 改為 Resend 失敗不影響送出、站內已同交易寫入 |
| 4 | 重要 | §4.3-4 步驟 2–3 | 上傳流程把「授權與狀態 / 期限預檢」放在「欄位與檔案驗證」之前，違反 §3 判斷順序（400 第 6 步早於 409 第 8–10 步）：逾期草稿上傳說明 101 字的檔案，流程得 409 `DEADLINE_PASSED`，判斷順序得 400 | 步驟改為：冪等回放 → 401 / 403 / 404 / 403 → 400（說明長度、檔案數、缺 key）→ 冪等認領 → 409 狀態 / 期限預檢 → 422 檔案與額度預檢 → GCS；AC-3.8 加此組合情境 |
| 5 | 建議 | AC-1.6 | 「伺服器於 02:59:59Z 收到 40 MB 影片上傳」未寫快照等級；範例預設訂單 A 未定快照，BASIC / FREE 會先得 422 | Given 補「快照 PRO」 |
| 6 | 建議 | AC-3.7 末段 | Given 為「暫存」（不帶 key），Then 卻寫「同 key 的上傳 / 送出重試不被回放為該錯誤」，Given / When / Then 不對應 | 拆成獨立情境：以 key K 上傳遇 `ORDER_BUSY` → 以 K 重試得 201 |
| 7 | 建議 | AC-3.2 | 「簽發 15 分鐘後該網址被儲存服務拒絕」在 E2E 需等 15 分鐘或操控 GCS 時鐘 | 註明此項以後端整合測試驗證（檢查簽名參數 `X-Goog-Expires=900`），E2E 只驗重新取得網址 |
| 8 | 建議 | §8 全部 AC | 多情境 AC 未標「情境 n」（PRD-008 / PRD-028 有標），TS 以「AC-x.y-情境 n」對應時無錨點；AC-1.6、AC-2.2 各塞 4 種以上情境 | 依 PRD-008 格式標「情境 n」，每個情境獨立 Given / When / Then |
| 9 | 建議 | §1 第 3 段 | 「行程檢核表（SOP 勾選）…屬 PRD-008」已過時，SOP 已拆至 PRD-028 | 改為 PRD-028 |
| 10 | 建議 | BR-17 文案 | 通知只帶行程日期；同日多次行程時飼主無法分辨是哪一次 | 依 CQ-18 結果帶入行程名稱 |

## 待使用者回答
| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-18 | BR §5.2 只有一個「日誌名稱」，本卡日誌標題（BR-6，1–30 字，預設「{行程日期} 行程」）與 PRD-008 行程名稱（BR-14，0–20 字，預設 `{方案名稱} 第 n 次`）重複，是否合併（同 PRD-008 CQ-10） | 合併為 PRD-008 的行程名稱；本卡移除標題欄位與 `sitter-care-log-input-title`，日誌檢視、送出通知改顯示行程名稱 |
