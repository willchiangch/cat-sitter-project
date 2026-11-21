---
name: ship
description: 上線前檢查 → 取得同意 → push main（觸發 deploy.yml 部署 Cloud Run + Firebase 並跑 CI E2E）→ 盯 CI → 失敗時接 /e2e-cycle ci。用法：/ship 或 /ship <feature 分支>
argument-hint: "[feature 分支]"
disable-model-invocation: true
---

# /ship

參數：`$ARGUMENTS`（空白 = 目前分支）

`push main` 會直接部署**正式站**，沒有測試環境。每一步失敗就停。

1. **狀態**：`git status`（不得有未 commit 變更）、`git log origin/main..HEAD --oneline` 列出要上線的 commit。分支不是 main → 確認可以 fast-forward 或需要 merge。
2. **本機閘門**（與 CI 一致，先在本機擋）：
   ```bash
   cd backend && mvn -q test
   cd frontend && npm run build && npm run lint && npm run e2e:lint
   ```
3. **部署相容性**（看 `git diff origin/main..HEAD`）：
   - 新遷移檔：與目前線上程式相容嗎（不得 DROP / RENAME 線上程式還在用的欄位；NOT NULL 要有 default）？V 號有沒有跟 main 衝突？
   - API：有沒有移除或改名前端線上版本還在呼叫的端點 / 欄位？（部署順序是後端先、前端後，中間有空窗）
   - 新 secret / env var：deploy.yml 的 `--set-secrets` / `--set-env-vars` 有沒有加？Secret Manager 有沒有建？（建 secret 要使用者自己做或另外同意）
   - PWA：Service Worker 快取策略有變嗎（SD-FRONTEND-SPEC §2.2）？
4. **文件**：README「模組狀態」、對應 PRD / SD / TS 是否已同步（doc-conventions）。
5. **同意**：用 AskUserQuestion 列出 commit 清單 + 第 3 步發現的風險，問「push 到 main 部署正式站？」。不同意就停。
6. **Push 與盯 CI**：
   ```bash
   git push origin main
   gh run watch $(gh run list --workflow deploy.yml -L1 --json databaseId -q '.[0].databaseId') --exit-status
   ```
7. **結果**：
   - 成功 → 回報 run URL、部署 commit；建議 `/e2e-cycle ci` 做驗收（看證據，不是只看綠燈）。
   - 失敗在 test / build / deploy step → 回報失敗 step 與 log 摘要（`gh run view --log-failed`），回復方式：`gcloud run services update-traffic cat-sitter-app --to-revisions=<上一版>=100 --region asia-east1`（執行前再次取得同意）。
   - 失敗在 Playwright step → 直接進 `/e2e-cycle ci`。
