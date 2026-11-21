# 審查：PRD-023（第 1 輪，2026-11-21）

結論：需修正

AC 數：21（子模組 1 / 2 / 3 = 8 / 7 / 6）。舊設計殘留：無（補款以獨立 `SUPPLEMENT` payment + 變更 `AWAITING_SUPPLEMENT` 表示，訂單主狀態不動）。

## 情境推演

| 情境 | 路徑 | 結果 |
|------|------|------|
| CONFIRMED 整筆取消，無差額 | AC-3.4：同意 → V1~V3 `CANCELLED` → `SETTLED` → O10 | 通 |
| CONFIRMED 整筆取消，退款 | AC-1.3 → PRD-007 上傳 / 確認 → AC-3.3 `SETTLED` → O10 | 通 |
| CONFIRMED 整筆取消，退款爭議 | `AWAITING_REFUND` → PRD-024 AC-1.2 O14 → ADMIN O17（無 DONE / CLOSED 行程）→ 變更 `REJECTED`、payment `VOID` | 本卡這端通；ADMIN 端依賴的 PRD-020 尚未定義 O15 / O17（見 PRD-024 審查） |
| IN_PROGRESS 減 1 加 3 → 補款 → 逾時 | V1 DONE、減 V3、加 3 → 新總額 2500、補款 1000；同意時 V3 取消；逾時 → `VOID`、`EXPIRED`、`total_amount` = 1500 − 500 = 1000、系統退款 500 `AWAITING_REFUND` → 確認 → `SETTLED`，`total_amount` 不動 → PRD-009 結案，收入 1000 | 通；但前一筆變更已取消過行程時，`Σ 已取消行程` 會被讀成整張訂單，見 #2 |
| 補款 SUBMITTED 72h 未核對 | AC-2.7 SUBMITTED 不逾時 → PRD-024 AC-1.3 O14（駁回重傳重新起算） | 通 |
| 減少行程在同意前已開始 | AC-1.4 → 409 `VISIT_ALREADY_STARTED`、維持 `QUOTED` → OWNER 拒絕 / 撤回，或 SITTER 撤回（SITTER 發起時），或等 72h 逾時 | 通；OWNER 發起的 `QUOTED` 期間 SITTER 沒有任何動作，只能等 OWNER（PRD-016 BR-8 已定案） |
| DISPUTED 期間操作 | EX-7 409 `ORDER_STATE_CONFLICT`。`DISPUTED` 只會帶著 `AWAITING_*` 進入（O13 要求無未結束變更），不會有 `QUOTED` 或 `UNPAID` 補款被排程逾時 | 通 |
| O10 延伸路徑（補款 EXPIRED 且不需系統退款） | 首次付款後 已收淨額 = `total_amount`，每次結清後仍相等；重算 = 原總額 − 本次取消，只有「本次沒取消任何行程」時才不需退款，而這時 `CONFIRMED` 訂單一定還有有效行程。實際上只有單價 0 的方案才走得到 | 與 BASELINE O10 一致；當防禦性規則保留即可，不需另寫 AC |

## 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議修正 |
|---|--------|------|------|---------|
| 1 | 重要 | §3「讀取補款收款帳號快照」列 | ADMIN 標 ✗，與 BASELINE §1.2（CQ-66「ADMIN 於可讀訂單範圍內唯讀快照」）矛盾；PRD-007 AC-2.7 也斷言 ADMIN 可讀 `payment-account-snapshot` | ADMIN 欄改「唯讀，限 BASELINE §1.2 可讀訂單」 |
| 2 | 重要 | §4.3-3-2、BR-9、T8、AC-2.4~2.6 | 「`Σ(已取消行程的快照單價)`」沒限定是本變更同意時取消的行程。訂單之前若有變更已取消 V2 並 `SETTLED`（`total_amount` 早已扣掉 V2），照字面會再扣一次。三條 AC 的前提都是「本變更是第一筆」，抓不到這個錯 | 改為「本變更同意時取消的減少目標行程」；子模組 2 補一個情境（可併入 AC-2.4 或移到 TS）：前一筆變更已取消 V2 並 `SETTLED`（`total_amount` 1000），本變更減 V3、加 2 後補款逾時 → `total_amount` = 1000 − 500 = 500 |
| 3 | 重要 | EX-15、AC-3.6 | 撤回系統建立的退款變更回 409 `MODIFICATION_STATE_CONFLICT`；PRD-016 §3 / T5 / EX-4 的寫法是「非發起者 → 403 `FORBIDDEN`」，SYSTEM 變更沒有人是發起者。同一端點兩張卡給出不同答案 | 依 PRD-016 審查 #2 的錯誤碼優先序（變更狀態檢查先於發起者檢查），本卡維持 409，PRD-016 補優先序 BR |
| 4 | 建議 | BR-15 | 結束時間清單只列 `SETTLED` / `EXPIRED` / `REJECTED`，沒有 `WITHDRAWN` 與 PRD-016 的 `REQUESTED` / `QUOTED` 逾時、SITTER 拒絕；系統退款變更 `SETTLED` 也要記 | 改為「任何變更進入終態都記結束時間（PRD-016 T4–T6、本卡 T1、T4、T7、T8、T10、ADMIN 調解）」 |
| 5 | 建議 | §4.1-4「已收淨額於此刻重算」vs PRD-016 §9「報價當下的已收淨額與差額」 | 差額有兩個來源（P4）。實際上兩者相等（變更進行中沒有其他 payment 能 `VERIFIED`），但規格沒說以哪個為準、要不要回寫 | 寫明「以同意時重算的差額分流，並把同意時的已收淨額與差額寫回變更列；與報價時不同不擋」 |
| 6 | 建議 | EX-8、子模組 1 | EX-8 的「OWNER 同意 vs SITTER 撤回（SITTER 發起的 `QUOTED`）」是跨角色競爭，但沒有 AC；AC-1.5 只涵蓋行程開始 | AC-1.5 補一句，或在 TS 補步驟：恰一個成功，後到者 409 `MODIFICATION_STATE_CONFLICT`，同意後到時無 payment 建立 |
| 7 | 建議 | BR-1、§4.1-3 | 沒寫保母在 `QUOTED` 之後被鎖定 / 降級時，同意是否重驗方案（例：PRO 時報了加價，之後鎖定） | 寫明「同意不重驗方案，依報價內容生效」（與 PRD-016 #13 同步） |
| 8 | 建議 | §4.3-3-3、BR-9 | 系統退款變更其餘欄位沒定義：`expires_at`、系統計算值、加價 / 折扣、原因、報價當下已收淨額 | 寫明 `expires_at=null`、加減與原因為空、原因固定為「補款逾期自動退款」、已收淨額 = 建立當下值 |
| 9 | 建議 | AC-2.7、AC-1.7 | AC-2.7 塞了 4 個情境（SUBMITTED 不逾時、逾時後確認、逾時後駁回再逾時、駁回後上傳 409），AC-1.7 也有 4 個 | 主路徑留在 AC，其餘移到 TS 步驟並註明 BR |

## 待使用者回答

無。
