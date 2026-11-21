---
name: trace
description: 檢查 PRD → AC → TS → SD → 後端測試 → E2E spec / 結果的追溯覆蓋率，列出斷鏈。用法：/trace、/trace <PRD-NNN | SD-NNN | TS-JOURNEY-NN>
argument-hint: "[<PRD-NNN | SD-NNN | TS-JOURNEY-NN>]"
disable-model-invocation: true
---

# /trace

參數：`$ARGUMENTS`（空白 = 全部 PRD；SD / TS 編號 = 對應的 PRD；JOURNEY = 它涉及的所有 PRD）

依 `.claude/skills/doc-conventions/SKILL.md` 追溯規則，直接讀檔，不派 agent（全部 PRD 時可派 Explore 平行讀，但結論由你彙整）：

1. **PRD**：從「驗收標準（AC）」段抓出 `AC-x.y` 清單與勾選狀態。
2. **TS**：`docs/test-scenario/TS-NNN-*.md` 是否存在；步驟表有沒有列到每條 AC（找 `AC-x.y` 字樣，沒有就語意比對並標「推定」）；表頭「自動化路徑」的 spec 是否存在。
3. **SD**：`docs/sd/SD-NNN-*.md` 是否存在；有「AC 對應實作」表就逐條對，沒有就檢查 AC 提到的 API / 狀態 / 錯誤碼是否出現在 SD。PRD 表頭「對應 SD」有例外時照例外對。
4. **後端測試**：grep `backend/src/test` 的 `@DisplayName` 含 `PRD-NNN AC-x.y`；沒有時用 SD 的 API 路徑 / 錯誤碼 grep 找推定對應的測試並標「推定」。
5. **E2E**：spec 的 `step()` 標籤是否對到 TS 步驟編號；最新 `.e2e-runs/*/acceptance.md` 中該 TS 的判定。
6. **狀態一致性**：下游 Approved 但上游不是；上游 Approved 後又被修改（`git log -1 --format=%cs` 晚於下游最後修改日）；README「模組狀態」與實際不符。

輸出（不寫檔，除非使用者要求）：

| PRD | AC | TS 步驟 | SD | 後端測試 | E2E step | 最新驗收 | 問題 |
|-----|----|---------|----|---------|---------|---------|------|

最後列斷鏈清單，每項附建議指令（`/ts`、`/sd`、`/e2e-write`、`/impl-review`）。
