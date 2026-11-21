---
name: prd-review
description: 獨立審查 PRD / NFR，產出審查紀錄與待釐清清單。用法：/prd-review <PRD-NNN ...> 或 /prd-review pending（所有 Draft / In Review / Needs Clarification）
argument-hint: "<PRD-NNN ...> | pending"
disable-model-invocation: true
---

# /prd-review

參數：`$ARGUMENTS`

1. 範圍：`pending` = `docs/sa/fr`、`docs/sa/nfr` 中表頭狀態為 Draft / In Review / Needs Clarification 者；否則為指定 ID。
2. 派 prd-reviewer，每個 agent 3–5 張，平行。**不要用寫這張卡的 prd-writer 審。**
3. 結論「需修正（過大）」的卡直接派 prd-writer 拆卡（先分配不重疊新編號），拆完再審一輪，最多 2 輪。
4. 多張卡時再派一個 prd-reviewer 做跨卡檢查（重疊、矛盾、訂單狀態機一致性），寫入 `docs/reviews/_prd-cross-check.md`。
5. 回報：
   - 每張結論表
   - 待使用者回答彙整（附建議預設值），用 AskUserQuestion 逐題問（一次最多 4 題）
   - 「需修正」→ 問是否交 prd-writer 修
   - 「可核准」→ 提醒使用者自己改狀態為 Approved，或明確說「核准 X」由主流程代寫
6. 使用者說「核准 X」時，由主流程（不是 agent）把表頭狀態改 `Approved` 並在變更紀錄寫日期。
