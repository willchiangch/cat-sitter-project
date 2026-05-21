# GCP 極省錢部署與 CI/CD 工作流任務清單 (修訂版 2)

- [x] **GCP 基礎設施與憑證初始化 (手動/CLI)** (使用者已完成)
  - [x] 啟用必要 API (sqladmin, run, registry, secretmanager, iamcredentials)
  - [x] 建立 Artifact Registry 與 Cloud SQL (`db-f1-micro`)
  - [x] 於 Cloud SQL 內建立 `petsitter_db` 資料庫與 `petsitter_user` 使用者
  - [x] 於 Secret Manager 內建立 `DB_PASSWORD` 與 `JWT_SECRET` 兩個 secret
  - [x] 建立專屬 Cloud Run Runtime SA (`cat-sitter-runner`) 並授權讀取上面兩個 secret 與 SQL Client
  - [x] 建立部署專用 SA (`github-actions-deployer`) 並授予相關部署權限
  - [x] 設定 Workload Identity Federation (WIF) OIDC 連結，綁定 GitHub Repo
- [x] **專案配置與代碼調整 (Blocking)**
  - [x] 於 `pom.xml` 中引入 `postgres-socket-factory` 依賴
  - [x] 新增 `application-prod.yml` 並限制 `maximum-pool-size: 2` 且對齊環境變數名稱
  - [x] 調整 `frontend/playwright.config.ts` 的 `baseURL` 與 `webServer` 配置
- [x] **CI/CD 自動化實作**
  - [x] 建立前端打包至後端的建置指令
  - [x] 撰寫 `.github/workflows/deploy.yml` 檔 (包含 WIF 登入、自動開關機 Shell 與 Playwright 觸發)
- [ ] **驗證**
  - [ ] 測試 GitHub Actions 連通性與 Cloud Run 部署成功率
  - [ ] 驗證部署後自動關機安全鎖 (`always()` 觸發) 確實將 `cat-sitter-db` 關機
