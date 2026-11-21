---
name: impl-review
description: 獨立確認程式碼是否照 Approved 的 SD 實作，逐條 AC 對照。用法：/impl-review <SD-NNN> [BASE commit，預設 origin/main]
argument-hint: "<SD-NNN> [base]"
disable-model-invocation: true
---

# /impl-review

參數：`$ARGUMENTS`

1. **關卡**：SD 必須 Approved。`BASE..HEAD` 沒有相關變更時，改為審查「整個模組現況 vs SD」（用 SD 的 API 路徑與 table 名 grep 出範圍），並在報告註明。
2. 派 code-reviewer（`SD`、`BASE`），輸出 `reports/code-review/{yyyyMMdd-HHmm}-sd-nnn.md`。**不要用實作此 SD 的 agent 審。**
3. 回報結論、AC 符合數 / 總數、阻擋問題，並依分類處理：
   - 偏離設計 / 測試不足 / 全域違反 / 文件落後 → 問使用者是否交 backend-dev / frontend-dev 修
   - 多做 → 使用者決定移除或補進 SD
   - 設計問題 → 使用者決定，交 sd-architect 修 SD（Approved 的退回 In Review），核准前不改程式遷就

與 `/project-auditor` 的差異：這個是**單一 SD 的 AC 逐條對照**；project-auditor 是**全專案的全域指標掃描**（金額、時區、鎖、Gating）。大改版後兩個都跑。
