# CI/CD 與部署流程指引 (Deployment Pipeline Guidelines)

## 1. 部署策略概述 (Deployment Strategy)
本專案採用 Monorepo 架構，前後端的 CI/CD 管線必須嚴格物理隔離。
我們依賴 **「路徑過濾 (Path Filtering)」** 來決定觸發哪一條部署管線。
部署平台預設使用 **GitHub Actions** (或 GCP Cloud Build)。

## 2. 環境分支對應 (Branching Strategy)
* `main` 分支：對應 **Production (正式環境)**。
* `develop` 分支：對應 **UAT (測試環境)**。
* 所有開發必須開 Feature branch，確認無誤後 PR (Pull Request) 合併回 `develop`。

## 3. 前端部署管線 (Frontend Pipeline)
* **觸發條件**：當 `frontend/**` 目錄下的檔案有變動，且推送到 `develop` 或 `main` 分支時觸發。
* **執行步驟**：
  1. Checkout 程式碼。
  2. 設定 Node.js 環境。
  3. 安裝依賴 (`npm install`)。
  4. 注入環境變數 (讀取 GitHub Secrets，如後端 API URL)。
  5. 執行編譯 (`npm run build`)。
  6. **部署目標**：將 `dist/` 資料夾部署至 **Firebase Hosting** (或 GCP Cloud Storage)。

## 4. 後端部署管線 (Backend Pipeline)
* **觸發條件**：當 `backend/**` 目錄下的檔案有變動，且推送到 `develop` 或 `main` 分支時觸發。
* **執行步驟**：
  1. Checkout 程式碼。
  2. 設定 Java 17/21 與 Maven 環境。
  3. 執行單元測試 (`mvn test`)。
  4. 登入 GCP (使用 Workload Identity Federation 或 Service Account JSON)。
  5. **容器化打包**：透過 Jib 或 Dockerfile 進行 Build。
  6. 推送 Image 至 **GCP Artifact Registry (GAR)**。
  7. **部署目標**：將最新的 Image 部署至 **GCP Cloud Run**。
  8. 確保 Cloud Run 啟動時能正確讀取 GCP Secret Manager 裡的機密資料 (DB 密碼、JWT Secret)。

## 5. 資料庫遷移 (Database Migration - Flyway)
* **注意事項**：後端部署至 Cloud Run 啟動時，Spring Boot 會自動觸發 Flyway 執行 `db/migration` 裡的 SQL 腳本。
* 嚴禁在 CI/CD 過程中進行破壞性的 Schema 刪除操作，所有變更必須是向前相容的 (Forward-compatible)。