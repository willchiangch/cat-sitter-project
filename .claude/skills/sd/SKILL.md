---
name: sd
description: 依 Approved 的 PRD 撰寫或修訂模組 SD、修訂全域設計，或做技術 PoC。用法：/sd <SD-NNN> | /sd global <主題> | /sd poc <主題>
argument-hint: "<SD-NNN> | global <主題> | poc <主題>"
disable-model-invocation: true
---

# /sd

參數：`$ARGUMENTS`

- **`/sd <SD-NNN>`**：
  1. 關卡：對應 PRD 必須 Approved。TS 不存在時提醒（建議先 `/ts`，TS 會暴露 SD 該補的 testid 與 provision 需求），問使用者是否仍要繼續。
  2. 派 sd-architect，輸出 `docs/sd/SD-NNN-{slug}.md`，同步更新 `SD-ERD.md`、`00-SD-PLAN.md`。
  3. 回報：摘要、新增 table / API / 遷移檔、AC 覆蓋數、需要使用者決策的技術選擇（附推薦）。下一步：`/sd-review SD-NNN`。

- **`/sd global <主題>`**：修訂 `SD-GLOBAL-SPEC.md` / `SD-FRONTEND-SPEC.md` / `SD-ERD.md` 中的共用規則（例：金流 webhook 簽章、冪等鍵、通知管道）。派 sd-architect，回報受影響的既有 SD 清單（用 Grep 找引用處）。

- **`/sd poc <主題>`**：派 sd-architect 產出 `docs/sd/poc-{主題}.md`，必要時在 `poc/{主題}/` 寫最小可執行驗證。列候選方案、驗證標準、結果、推薦。**不得呼叫真實第三方付費 API、不得變更 GCP 資源**，除非使用者當次同意。目前已知候選：SD-015 金流供應商（綠界 / 藍新 / TapPay）與 webhook 驗簽、T+3 撥款對帳。
