# [驗證] 前端整合與 E2E 運作校驗

在完成後端大規模重構（移除 Lombok、更新 Schema）後，我們需要確保前端能與新的 POJO 式後端正確溝通。

## 需要用戶確認
> [!IMPORTANT]
> 此計畫涉及在後端新增一個 `smoke` 專用 Profile。
> 這將在測試環境下 **繞過 API 鑑權** 並 **自動播種測試資料**（如貓咪 `Oliver`、保母 `s1` 等），以利 Playwright 自動化測試。這僅會在啟動參數包含 `-Dspring-boot.run.profiles=smoke` 時生效。

## 擬議變更

### [後端環境設置]

#### [NEW] [application-smoke.yml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/application-smoke.yml)
配置 `smoke` Profile，使用 H2 資料庫並包含測試專用的環境變數。

#### [MODIFY] [SecurityConfig.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/config/SecurityConfig.java)
在 `smoke` Profile 下，將所有 `/api/v1/**` 路徑設為 `permitAll()`，或新增一個允許前端 Mock Token 的 Filter。

#### [NEW] [SmokeDataSeeder.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/service/SmokeDataSeeder.java)
實作一個 `CommandLineRunner`，在系統啟動時檢查是否為 `smoke` 模式，若是則自動寫入測試腳本需要的 Pet、Sitter 與 Order 資料。

### [前端連接驗證]

#### [MODIFY] [playwright.config.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/playwright.config.ts)
確保 `webServer` 啟動指令帶有正確的環境變數，並指向後端 8081 埠。

## 開放問題
- 除了現有的 `Oliver` 貓咪資料，是否還有其他特定測試帳號需要預先建立？

## 驗證計畫

### 自動化測試
1. 以 `smoke` Profile 啟動後端。
2. 執行 `npx playwright test`。

### 手動校驗
1. 進入「個人檔案」頁面，確認可正確讀寫新增的銀行帳戶欄位。
2. 進入「問卷編輯器」，確認「必填」與「題型」設定能正確存入新的資料庫欄位。
