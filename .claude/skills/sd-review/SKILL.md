---
name: sd-review
description: 獨立審查模組 SD 或全域設計，並做跨 SD 衝突檢查。用法：/sd-review <SD-NNN ...> | /sd-review global | /sd-review pending
argument-hint: "<SD-NNN ...> | global | pending"
disable-model-invocation: true
---

# /sd-review

參數：`$ARGUMENTS`

1. 範圍：`pending` = `docs/sd/` 中狀態為 Draft / In Review / Needs Clarification 者；`global` = 三份全域設計；否則為指定 ID。
2. 派 sd-reviewer，每個 agent 3–5 份，平行。**不要用 sd-architect 審。**
3. 多份時再派一個 sd-reviewer 做跨 SD 檢查：table / API 衝突、訂單狀態機在各 SD 定義是否一致、鎖策略是否一致，寫入 `docs/reviews/_sd-cross-check.md`。
4. 回報：每份結論、待使用者回答（附建議預設值，用 AskUserQuestion 問）、「需修正」→ 問是否交 sd-architect 修、「可核准」→ 提醒使用者核准。
5. 使用者說「核准 X」時，由主流程代寫 Approved。
