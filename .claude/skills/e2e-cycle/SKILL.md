---
name: e2e-cycle
description: 串起 4 個 E2E agent 的完整循環：e2e-runner 跑測試 → e2e-triage 找根因 → e2e-fixer 修 bug（push 前問使用者）→ 重跑 → e2e-acceptance 依 TS 文件驗收。使用者說「跑 e2e 循環」「e2e 修到綠」「驗收 journey」或 /e2e-cycle 時使用。
---

# E2E 循環（主控流程）

參數：`/e2e-cycle [local|ci] [scope]`，預設 `ci e2e/journeys`（`ci` = 看最新一次 deploy.yml 的結果，不額外打正式站）。

主控（你）只負責調度與跟使用者溝通，**不自己修 code、不自己判讀截圖**——那是各 agent 的職責，混在一起就失去互相檢查的意義。

## 流程

```
RUN_DIR=.e2e-runs/$(date +%Y%m%d-%H%M)
iteration = 1
loop:
  1. e2e-runner(RUN_DIR, MODE, SCOPE)
  2. if 有 failed/flaky 或 evidenceMissing:
       e2e-triage(RUN_DIR)
       - 只有 ENV/DATA/UNKNOWN → 停，把 triage 摘要給使用者
       - 有 PRODUCT_BUG/TEST_BUG/CONSISTENCY_WINDOW → 3
     else → 4
  3. e2e-fixer(RUN_DIR, 項目 IDs)
     → 回報 commit 清單，用 AskUserQuestion 問「是否 push 到 main（會部署正式站並觸發 CI E2E）」
     → 同意：SendMessage 給同一個 fixer「使用者已同意 push」，等 CI 跑完
     → 不同意：停
     iteration += 1；新 RUN_DIR；MODE=ci；回到 1
  4. e2e-acceptance(RUN_DIR)
     - ACCEPT → 結束
     - REJECT / ACCEPT_WITH_GAPS 且有退回項目 → 把退回項目當成 triage 項目交給 e2e-fixer，回到 3
  最多 3 輪；超過就停，回報卡住的項目
```

## 規則

- runner 與 acceptance 一律開新 agent（不要沿用舊 context，避免被前一輪的結論影響）；fixer 在同一輪內用 SendMessage 延續（push 那一步需要它記得改了什麼）。
- 每輪結束給使用者一行狀態：`第 N 輪：passed X / failed Y / flaky Z → <下一步>`。
- 結束時回報：各輪 commit、acceptance 判定表、仍待補的 `NOT_COVERED` 與「另外發現」。
- `local` 模式會在正式站建測試帳號（`@e2e-journey.test`），runner 會自己清；中途中斷的話手動打 `cleanup-e2e-journeys`。
