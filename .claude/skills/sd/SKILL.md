---
name: sd
description: 依 Approved 的 PRD 撰寫或修訂模組 SD、修訂全域設計，或做技術 PoC。用法：/sd <SD-MODULE> | /sd global <主題> | /sd poc <主題>
argument-hint: "<SD-MODULE> | global <主題> | poc <主題>"
disable-model-invocation: true
---

# /sd

參數：`$ARGUMENTS`

- **`/sd <SD-MODULE>`**（例 `/sd SD-ORDER`）：
  1. 關卡：`docs/sa/00-DOMAIN-BASELINE.md` 必須 Approved；`00-SD-PLAN.md` §0 列出的該模組 PRD 必須全部 Approved（未 Approved 的列給使用者，不得納入）。TS 不存在時提醒，問是否仍要繼續。
  2. 派 sd-architect，輸出 `docs/sd/SD-{MODULE}.md`；舊 `SD-NNN-*.md` 只當現況參考，不得整段照搬。同步更新 `SD-ERD.md`。
  3. 回報：摘要、AC 覆蓋數 / 總數、新增 table / API / 遷移檔、從舊 SD 刻意捨棄的設計與理由、需要使用者決策的技術選擇（附推薦）。下一步：`/sd-review SD-{MODULE}`。
  4. 新 SD 核准後，由主流程把被取代的舊 `SD-NNN-*.md` 狀態改 `Superseded`（註明取代者），並更新各 PRD 表頭「歸屬 SD」。

- **`/sd global <主題>`**：修訂 `SD-GLOBAL-SPEC.md` / `SD-FRONTEND-SPEC.md` / `SD-ERD.md` 中的共用規則（例：金流 webhook 簽章、冪等鍵、通知管道）。派 sd-architect，回報受影響的既有 SD 清單（用 Grep 找引用處）。

- **`/sd poc <主題>`**：派 sd-architect 產出 `docs/sd/poc-{主題}.md`，必要時在 `poc/{主題}/` 寫最小可執行驗證。列候選方案、驗證標準、結果、推薦。**不得呼叫真實第三方付費 API、不得變更 GCP 資源**，除非使用者當次同意。目前已知候選：SD-015 金流供應商（綠界 / 藍新 / TapPay）與 webhook 驗簽、T+3 撥款對帳。
