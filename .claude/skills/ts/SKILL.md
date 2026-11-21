---
name: ts
description: 依 Approved 的 PRD 撰寫測試情境 TS，或撰寫跨模組旅程 TS-JOURNEY。用法：/ts <PRD-NNN ...> 或 /ts journey <描述>
argument-hint: "<PRD-NNN ...> | journey <描述>"
disable-model-invocation: true
---

# /ts

參數：`$ARGUMENTS`

1. **關卡**：每張目標 PRD 狀態必須 Approved（doc-conventions 的 Approved 變體也算），否則停止並列出目前狀態。
2. 輸出路徑：`PRD-022` → `docs/test-scenario/TS-022-{同 PRD slug}.md`；journey → `TS-JOURNEY-{下一號}-{slug}.md`，涉及的 PRD 全部要 Approved。
3. 每份 TS 派一個 ts-writer，多份平行。
4. 回報：TS 清單（ID、步驟數、AC 覆蓋率）、未覆蓋 AC、需要後端配合的前置。提醒使用者審閱後核准。下一步：有 SD 的 → `/e2e-write <TS>`；沒 SD 的 → `/sd <SD-NNN>`。
