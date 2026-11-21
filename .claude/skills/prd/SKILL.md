---
name: prd
description: 撰寫或修訂 PRD / NFR。用法：/prd <PRD-NNN | 新功能描述> 或 /prd nfr <NN | 主題>
argument-hint: "<PRD-NNN> | <新功能描述> | nfr <NN|主題>"
disable-model-invocation: true
---

# /prd

參數：`$ARGUMENTS`

1. 範圍：
   - `PRD-NNN`：修訂既有卡。
   - 新功能描述：讀 `docs/sa/fr/00-PRD-PLAN.md` 決定新編號（現有最大號 + 1），並另外預留 2 個備用號供拆卡。
   - `nfr ...`：同上，對象為 `docs/sa/nfr/`。
2. 需求模糊時先用 AskUserQuestion 問清楚最多 3 個關鍵問題（角色、SaaS 方案差異、狀態機邊界），每題附建議預設值；不要讓 prd-writer 自己猜。
3. 派 prd-writer，交辦內容含：編號 / 備用號、使用者原話與回答（作為出處）。多張卡時每張一個 agent 平行。
4. 完成後數每個子模組 AC，> 8 條就再派 prd-writer 拆。
5. 回報：卡片清單（ID、子模組數、AC 數、CQ 數）、待使用者回答的 CQ（附建議預設值）。下一步：`/prd-review <ID>`。
