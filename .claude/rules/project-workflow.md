# 專案開發流程（agents / skills）

```
/prd → /prd-review → [使用者核准] → /ts → /sd → /sd-review → [使用者核准]
     → /dev（backend-dev / frontend-dev → code-reviewer → /code-review）→ /e2e-write → /ship → /e2e-cycle ci
輔助：/trace（追溯斷鏈）、/impl-review（單 SD 對照）、/project-auditor（全域指標）
```

文件命名、狀態、AC、追溯規則：`.claude/skills/doc-conventions/SKILL.md`。全域技術規則：`docs/sd/SD-GLOBAL-SPEC.md`、`docs/sd/SD-FRONTEND-SPEC.md`。

**硬性規則**
1. 文件狀態 `Approved` 只有使用者能設定；使用者明確說「核准 X」時才由主流程代寫，agent 一律不得寫。
2. 下游只能依 Approved 上游：TS / SD 依 Approved PRD，開發依 Approved SD，E2E spec 依 Approved TS。
3. 沒出處的需求不寫成規格，列「待釐清事項」並附建議預設值。
4. **禁止自行 `git push` 與觸發部署**：`deploy.yml` 自 2026-11-21 起改為手動觸發（`gh workflow run deploy.yml --ref main`），push main 不再自動部署。push 與觸發部署（會部署正式站並對正式站跑 E2E）都必須使用者在當次對話同意，兩者分開問。
5. **禁止自行變更雲端資源**：`gcloud run deploy/update`、Secret Manager 寫入、Cloud SQL / Supabase schema 手動修改、Firebase deploy，都要當次同意。`gcloud logging read` 與 `gcloud secrets versions access`（讀到環境變數，不印出）可直接用。
6. Secret（`INTERNAL_CRON_SECRET`、JWT、DB 密碼、Resend key）不得寫進檔案、log、commit、對話。
7. 改 code 同步改 PRD / SD / TS（見 doc-conventions）。
8. Markdown 表格儲存格內不用 HTML 標籤。