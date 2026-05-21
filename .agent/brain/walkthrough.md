# E2E 測試 403 阻擋修復

## 變更內容
修復 Playwright E2E 測試時在 Cloud Run 因 Spring Security 阻擋靜態資源導致 403 Forbidden 的問題。

### [SecurityConfig.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/infrastructure/security/SecurityConfig.java)
- 在 `authorizeHttpRequests` 規則中放行前端靜態資源與主頁面：
  ```java
  .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**", "/static/**", "/*.js", "/*.css", "/*.png", "/*.svg", "/*.ico").permitAll()
  ```

### [.github/workflows/deploy.yml](file:///Users/will_chiang/Widget_home/cat-sitter-project/.github/workflows/deploy.yml)
- 優化 **Start Cloud SQL Database** 與 **Stop Cloud SQL Database** 步驟：
  - 開關機前自動偵測是否有 `RUNNING`/`PENDING` 的 GCP 異步 operation，有的話先 Polling 等待結束以徹底防止 409 Conflict。
  - 開機後除了檢查 DB 狀態是否 `RUNNABLE`，還需要 Polling 對應的 operation ID 直到狀態為 `DONE`，確保 PostgreSQL 服務已完全就緒，防止 Cloud Run 容器啟動連線超時。
  - 增加避免重複開機/關機的邏輯（檢查 `activationPolicy`），減少不必要的 API 呼叫。

## 驗證結果
- 本地代碼已套用修改，推送至 `main` 後將由 GitHub Actions 重新建置並部署測試。
