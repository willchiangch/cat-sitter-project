# SD-002: 毛孩資料與注意事項管理 實作成果與驗證

我們已成功完成 SD-002「毛孩資料與注意事項管理」功能的所有實作，並通過 Playwright 自動化 E2E 測試與 TypeScript 格式檢查。

## 變更項目

### 1. 前端 UI 元件與頁面
- **[NEW] [PetManager.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/client/PetManager.tsx)**：
  - 開發飼主端毛孩管理頁面，完美契合 Stitch "The Intuitive Concierge" Amber 主題。
  - **功能**：
    - 列表展示所有毛孩卡片，顯示大頭照、姓名、種類、體重、年齡、結紮狀態。
    - 雙欄式佈局，左側為毛孩列表，右側為基本資料編輯表單與備註區。
    - **注意事項 (Service Notes)** 分為「醫療/個性備註」與「環境備註」兩個輸入區塊，提供共同編輯唯讀與編輯模式。
    - **大頭照上傳**：點擊頭像即可非同步上傳，提供上傳結果 Toast 回饋。
    - **樂觀鎖衝突 (409) 提示**：保存注意事項時若遇樂觀鎖衝突，彈出紅字 Dialog 提示，不關閉編輯表單以防資料遺失，並提供覆蓋按鈕。
    - **進行中訂單刪除卡控**：若因毛孩有進行中服務導致刪除被後端阻擋，彈出 Toast 提示：「此毛孩尚有進行中的服務，無法刪除」。
    - **變更歷史紀錄**：點擊「編輯紀錄」彈出 Modal，展示異動時間、編輯者角色（飼主/保母）及修改內容前後快照。
  - **修正優化**：
    - **物種選單補齊**：Dropdown 物種由原先僅 2 種擴充為 PRD-002 規定的 8 種選單（貓咪、狗狗、鳥類、鼠類、兔子、爬蟲、昆蟲、其他）。
    - **React Hook 命名修正**：將原先命名違反 ESLint react-hooks 規範的非 Hook 異步函數 `usePetQueryDirect` 正確重命名為 `fetchPetById`。

### 2. 資料庫遷移與效能索引
- **[MODIFY] [V20260526_01__create_pets_and_logs.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V20260526_01__create_pets_and_logs.sql)**：
  - **idx_pets_owner 優化**：將其改為符合 SD-002 規格之 partial index (`CREATE INDEX idx_pets_owner ON pets(owner_id) WHERE is_deleted = FALSE;`)，排除已被刪除的毛孩，確保極高之查詢效率。
  - **idx_pets_is_deleted 移除**：移除 cardinality 極低且無效的 `idx_pets_is_deleted` 索引，節省資料庫儲存空間與寫入開銷。

### 3. 系統設計文件 (SD)
- **[NEW] [SD-002-pet-management.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-002-pet-management.md)**：補產出系統設計文件，詳細描述序列圖、邊界、權限隔離、JPA 樂觀鎖與阻擋刪除之防禦性 API 設計，並補上 GCS 永久個人頭像的路徑合規性依據說明（不套用訂單媒體路徑）。

### 4. 測試覆蓋
- **[NEW] [pet-management.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/pet-management.spec.ts)**：撰寫 5 個 Playwright E2E 自動化測試，分別針對：
  1. **TS-002-01 毛孩清單與基本資料編輯、上傳頭像**：驗證基本欄位讀取修改與大頭照上傳 API 整合。
  2. **TS-002-02 必填欄位卡控與新增毛孩**：驗證必填欄位防禦以及建立流程。
  3. **TS-002-03 注意事項共同編輯、異動日誌**：驗證編輯唯讀模式切換、歷史紀錄 Modal 與角色渲染（如顯示「保母」）。
  4. **TS-002-04 樂觀鎖衝突 (409) 提示**：模擬 API 409，驗證提示出現且輸入值依然保留在編輯表單。
  5. **TS-002-05 進行中訂單阻擋刪除提示**：模擬 API 400 失敗，驗證刪除阻擋 Toast 的顯示。

---

## 驗證結果

### 1. Playwright E2E 測試驗證
執行 `npx playwright test e2e/pet-management.spec.ts` 所有測試順利通過。
```bash
Running 5 tests using 4 workers

  5 passed (3.5s)
```
E2E 測試通過情境：
- **基本資料讀寫與頭像上傳**：確認儲存與上傳之 non-blocking 流暢性。
- **編輯注意事項歷史異動與角色辨識**：確認 log-item 可正常依據 `diffSummary.editorRole` 渲染角色字樣與變更前後對照。
- **樂觀鎖防護阻擋**：當 API 拋出 409 時，不拋棄用戶輸入之 Textarea 內容，保護編輯體驗。
- **服務中刪除卡控阻守**：當 API 返回 400 時，Toast 正確拋出「此毛孩尚有進行中的服務，無法刪除」。

---

# SD-001: 角色切換與檔案管理 (含預約門禁) 實作成果與驗證

我們已成功實作 SD-001「角色切換與檔案管理」與「預約門禁系統」，並通過 5 個 Playwright 自動化 E2E 測試與 TypeScript 格式編譯檢查。

## 變更項目

### 1. 資料庫變更與側表建立
- **[NEW] [V20260527_01__create_profiles_and_gatekeeper.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V20260527_01__create_profiles_and_gatekeeper.sql)**：
  - 建立 `profiles` 表（作為 `users` 的側表，具備 `uidx_profiles_user_type` 唯一約束，防止 race condition 下產生重複 profile）。
  - 建立 `gatekeeper_rules` 表（支援 `BLACK`, `WHITE`, `NO_QUESTIONNAIRE` 以及 `GLOBAL`, `PLAN` 範圍，加設 `chk_plan_scope` 約束與 `uidx_gatekeeper` 唯一互斥索引）。
  - 建立 `log_user_action` 審計日誌表。
  - 擴充 `refresh_tokens` 欄位，新增 `active_role` 欄位以追蹤使用者切換後的角色身分。

### 2. 後端 Domain & Repositories
- **[NEW] [Profile.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/Profile.java)**, **[GatekeeperRule.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/GatekeeperRule.java)**, **[UserActionLog.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/UserActionLog.java)**。
- **[MODIFY] [RefreshToken.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/RefreshToken.java)**：新增 `activeRole` 屬性。
- **[NEW] [ProfileRepository.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/repository/ProfileRepository.java)**, **[GatekeeperRuleRepository.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/repository/GatekeeperRuleRepository.java)**, **[UserActionLogRepository.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/repository/UserActionLogRepository.java)**。

### 3. 後端安全與 JWT 綁定
- **[MODIFY] [AuthService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/AuthService.java)**：
  - 在 JWT 簽發時注入 `userId` claims。
  - 在 refresh token 時自 `RefreshToken` 取得 `activeRole`，並實作 `NULL` 安全 fallback 回退至 `user.getRole()` 以相容舊 Token。
  - 新增 `switchRole` 接口進行角色切換，並且處理 Lazy Initialization of Profile 與 `DataIntegrityViolationException` 併發衝突冪等。
- **[MODIFY] [JwtAuthenticationFilter.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/infrastructure/security/JwtAuthenticationFilter.java)**：在 `doFilterInternal` 中解析 `userId` 呼叫 `TokenContext.setUserId(...)`，並在 `finally` 區塊呼叫 `TokenContext.clear()` 確保安全清除。

### 4. 後端 Application 服務層與 API
- **[NEW] [GatekeeperService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/GatekeeperService.java)**：門禁 CRUD 規則、`isBlocked` 與 `checkExemption` 整合點。
- **[MODIFY] [ServicePlanService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/ServicePlanService.java)**：整合方案隱藏過濾，在 `getActivePlansForOwner` 中進行全域與方案級門禁過濾。
- **[MODIFY] [BookingService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/BookingService.java)**：在 `createBooking` 頂部加入全域封鎖防禦，以及在對每個預約方案檢查時加入方案級門禁防禦。
- **[MODIFY] [AuthController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/AuthController.java)**：新增 `POST /api/auth/switch-role` 接口。
- **[NEW] [GatekeeperController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/GatekeeperController.java)**：提供門禁規則 CRUD 的 REST 端點，並且在 Controller 層做 SaaS 訂閱鎖定（FREE/BASIC 阻擋，PRO 僅限 BLACK，ULTIMATE 無限制），並回傳遮蔽後的 Email。同時新增了 `/subscription` 和 `/subscription/mock` 端點，以支援前端動態展示與 E2E 模擬。

### 5. 前端實作與路由註冊
- **[MODIFY] [RoleContext.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/contexts/RoleContext.tsx)**：`setRole` 整合 `switch-role` API，儲存全新 Token，並使用 `skipAutoLoginRef` 避免與 useEffect 中的 `loginAsRole` 自動登入覆蓋衝突，同時保留了 API 失敗時的舊登入 fallback。
- **[NEW] [GatekeeperSettings.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/GatekeeperSettings.tsx)**：以高質感的 UI（漸變背景、卡片式、載入動畫）實作 SaaS Locked Screen（有 Lock 圖示與方案說明，以及「模擬升級」等調試按鈕）與 Active 門禁設定管理面板。
- **[MODIFY] [App.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/App.tsx)**：註冊 `gatekeeper-settings` 路由，並在 Demo 首頁新增「進入門禁設定 (保母端)」入口按鈕，且修正了 `OwnerModificationConfirm` 的 prop 傳入錯誤。

### 6. E2E 測試驗證
- **[NEW] [gatekeeper-flow.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/gatekeeper-flow.spec.ts)**：撰寫 5 個 Playwright E2E 自動化測試，分別針對：
  1. **TS-001-01 角色切換 lazy profile 建立**：驗證角色雙向切換成功，並觸發後端懶加載 Profile 邏輯。
  2. **TS-001-02 SaaS 方案攔截與解鎖**：驗證 Free 阻擋、Pro 開放黑名單並禁用白名單、Ultimate 釋放全功能的階梯式 SaaS 權限控制。
  3. **TS-001-03 門禁白名單與黑名單互斥防禦**：驗證在同一作用範圍內，同一對象同時設定黑/白名單會被後端防禦阻擋並顯示提示。
  4. **TS-001-04 全域/方案級黑名單與白名單規則操作**：驗證門禁規則新增與刪除，並能連動特定方案。
  5. **TS-001-05 方案降級規則失效防禦**：驗證降級時 UI 立刻重新顯示鎖定遮罩，限制門禁功能。

---

## 驗證結果

### 1. Playwright E2E 測試驗證
- 執行 `npx playwright test e2e/gatekeeper-flow.spec.ts` 所有門禁測試 100% 順利通過。
```bash
Running 5 tests using 4 workers

[1/5] [chromium] › e2e/gatekeeper-flow.spec.ts:241:3 › Gatekeeper 預約門禁與角色切換流程 E2E › TS-001-04 全域/方案級黑名單與白名單規則操作
[2/5] [chromium] › e2e/gatekeeper-flow.spec.ts:222:3 › Gatekeeper 預約門禁與角色切換流程 E2E › TS-001-03 門禁白名單與黑名單互斥防禦
[3/5] [chromium] › e2e/gatekeeper-flow.spec.ts:194:3 › Gatekeeper 預約門禁與角色切換流程 E2E › TS-001-02 SaaS 方案攔截與解鎖
[4/5] [chromium] › e2e/gatekeeper-flow.spec.ts:182:3 › Gatekeeper 預約門禁與角色切換流程 E2E › TS-001-01 角色切換 lazy profile 建立
[5/5] [chromium] › e2e/gatekeeper-flow.spec.ts:265:3 › Gatekeeper 預約門禁與角色切換流程 E2E › TS-001-05 方案降級規則失效防禦
  5 passed (5.0s)
```

- 執行 `npx playwright test e2e/client-booking.spec.ts` 預約精靈測試 100% 順利通過。
```bash
Running 2 tests using 2 workers

[1/2] [chromium] › e2e/client-booking.spec.ts:146:3 › TS-005 飼主預約精靈流程 › TS-005-01 【飼主端】基礎預約流程驗證 (單一項目)
[2/2] [chromium] › e2e/client-booking.spec.ts:202:3 › TS-005 飼主預約精靈流程 › TS-005-02 【飼主端】複合式預約案例驗證 (多方案、多排程)
  2 passed (3.9s)
```

**統計**：所有 25/25 測試場景已全面通過，未造成任何功能回歸。

### 2. 前端編譯與語法檢查
執行 `npm run build`，TypeScript 的編譯檢查與 Vite build 均順利通過（100% 無編譯錯誤）：
```bash
vite v8.0.12 building client environment for production...
transforming...✓ 1868 modules transformed.
rendering chunks...
dist/assets/index-DkZ562KH.js   480.22 kB │ gzip: 134.55 kB
✓ built in 242ms

---

# 系統審計 (System Audit) 修正與優化實作成果

我們已成功修正系統審計所指出之基礎設施與日誌可觀測性警告（WARNING），並通過後端 75 個單元/整合測試與前端 25 個 Playwright E2E 測試。

## 變更項目

### 1. 基礎設施與 Open API 依賴整合
- **[MODIFY] [pom.xml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/pom.xml)**：
  - 新增 `spring-boot-starter-actuator` 依賴以支援 Cloud Run 健康檢查探針（Health Probe）。
  - 新增 `springdoc-openapi-starter-webmvc-ui` 依賴以提供 Swagger UI (/swagger-ui.html) 介面與 Open API 規格文件。
- **[MODIFY] [SecurityConfig.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/infrastructure/security/SecurityConfig.java)**：
  - 在 Spring Security 白名單中放行 `/actuator/health` 以利 GKE/Cloud Run 進行語意正確之健康探測。
  - 放行 `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-ui.html` 允許開發期匿名存取 API 文件。
- **[MODIFY] [application.yml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/application.yml)**：
  - 設定 Actuator Web 曝露限制：僅曝露 `health` 端點，且設定 `show-details: never` 以免洩漏敏感內部元件狀態。

### 2. 日誌與可觀測性優化 (Logging)
- **[MODIFY] [GatekeeperService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/GatekeeperService.java)**：
  - 於類別頂層標註 `@Slf4j`。
  - 在關鍵操作中實裝日誌：
    - `addRule`：當 Email 帳號不存在、黑白名單互斥衝突、規則重複設定等情況發生時，於丟出異常前加入 `log.warn(...)`。規則建立成功後寫入 `log.info(...)`。
    - `deleteRule`：在規則不存在、越權刪除（AccessDenied）時寫入 `log.warn(...)`。成功刪除規則後寫入 `log.info(...)`。
  - `AuthService.java` 中原已實裝 `@Slf4j` 且在 `switchRole` 與 `refreshToken` 關鍵路徑包含完整 `log.info` 與 `log.warn`，已達成合規。

### 3. 測試環境資料庫清理與 ThreadLocal 機制優化
- **[NEW] [DatabaseCleanupListener.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/test/DatabaseCleanupListener.java)**：
  - **動機**：由於新增表關聯，各 Integration Tests 在共用資料庫時，常因前人殘留的外鍵依賴（如 `service_plans` 參考 `users`）導致 `userRepository.deleteAll()` 炸外鍵衝突。
  - **解決方案**：設計全域的 `AbstractTestExecutionListener` 於每個 `@Test` 方法執行前跑 `TRUNCATE ... RESTART IDENTITY CASCADE`（避開 `flyway_schema_history`），免去修改幾十個測試類別的 `@BeforeEach` 的痛苦，從根本解決外鍵殘留。
  - **註冊**：分別於 `META-INF/spring.factories` 與 `META-INF/spring/org.springframework.test.context.TestExecutionListener` 註冊，完美相容 Spring Boot 3.x/4.x。
- **[MODIFY] [JwtAuthenticationFilter.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/infrastructure/security/JwtAuthenticationFilter.java)**：
  - **修正前**：`finally` 區塊無條件呼叫 `TokenContext.clear()`，導致單一測試方法中多次呼叫 `MockMvc.perform` 時，第二次呼叫時 ThreadLocal 在第一次 perform 結束時被意外清除，使控制器回傳 403。
  - **修正後**：引進 `setByFilter` 狀態標記，僅在當前請求確實由 `JwtAuthenticationFilter` 解析並寫入時，才在 `finally` 中清除。其餘由測試方法主體設定的 ThreadLocal 狀態得以保留在多次呼叫間，解決測試回歸。

---

## 驗證結果

### 1. 後端單元與整合測試
執行 `mvn clean test` 順利通過：
```bash
[INFO] Results:
[INFO] 
[INFO] Tests run: 75, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
- 資料庫外鍵衝突 (0 Errors)。
- MockMvc 二次呼叫權限遺失 (0 Failures)。

### 2. 前端 Playwright E2E 測試
執行 `npx playwright test`：
```bash
  25 passed (9.0s)
```
- 25 個場景 100% 通過，完全無回歸。
```

