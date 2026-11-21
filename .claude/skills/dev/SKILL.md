---
name: dev
description: 依 Approved 的 SD 進行前後端開發，完成前經實作審查與程式碼審查，commit 但不 push。用法：/dev <SD-NNN> [backend|frontend]
argument-hint: "<SD-NNN> [backend|frontend]"
disable-model-invocation: true
---

# /dev

參數：`$ARGUMENTS`

1. **關卡**：SD 與對應 PRD 必須 Approved。`git status` 有不相關的未 commit 變更 → 先問使用者怎麼處理，不要混進來。
2. **分支**：`push main = 部署正式站`，所以開發在 `feature/sd-NNN-{slug}`（GLOBAL-SPEC §5.1）。記下 `BASE=$(git rev-parse HEAD)` 給審查用。使用者明確說「直接在 main」才留在 main。
3. **派工**（依 SD 判斷，或依第二個參數限定）：
   - 前後端都要、SD 的 API 契約完整 → backend-dev 與 frontend-dev 平行。
   - 契約不完整 → 先 backend-dev，完成後把實際 API（`docs/api/openapi.json` diff）交給 frontend-dev。
   - 有 TS 且 TS 引用的 testid / provision 尚未實作 → 一併交辦。
4. **實作審查**：兩邊都回報驗證通過後，派 code-reviewer（`SD`、`BASE`），輸出 `reports/code-review/`。
5. **程式碼審查**：對 `BASE..HEAD` 跑 `/code-review high`。變更涉及 auth、JWT、權限、WebAuthn、檔案上傳、金流、internal 端點、KYC 個資 → 再跑 `/security-review`。
6. **處理問題**：實作者不得自審。偏離設計 / 測試不足 / 全域違反 / 文件落後 → 交回原開發 agent（SendMessage 延續 context）修，重跑驗證與審查，最多 2 輪。「設計問題」「多做」→ 停該部分，交使用者決定，不改程式遷就。
7. **追溯**：照 `/trace SD-NNN` 的步驟檢查，確認 PRD ↔ SD ↔ TS ↔ 測試沒有斷鏈。
8. **文件同步**：README「模組狀態」表、SD 狀態（實作細節變動寫回 SD）。
9. **Commit**：通過後 commit 到分支，訊息比照 git log（`feat: ...` / `fix: ...`，中文），結尾加 attribution。**不 push、不 merge**；遷移檔 V 號若與 main 衝突，合併前改號。
10. 回報：完成的 AC、審查結論與報告路徑、新增遷移檔 / testid / API、未解問題。下一步：有 TS → `/e2e-write <TS>`；準備上線 → `/ship`。
