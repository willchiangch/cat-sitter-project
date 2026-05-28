# SD-001: 角色切換與檔案管理 (含預約門禁) 工作清單

- [x] **1. 資料庫變更與側表建立**
  - [x] 建立 `V20260527_01__create_profiles_and_gatekeeper.sql` 遷移檔案（建立 `profiles`, `gatekeeper_rules` 與 `log_user_action` 表，補上 `active_role` 欄位至 `refresh_tokens`，建立唯一索引與 `chk_plan_scope` 約束）

- [x] **2. 後端 Domain 與 Infrastructure 實作**
  - [x] 建立 `Profile.java` 實體
  - [x] 建立 `GatekeeperRule.java` 實體
  - [x] 修改 `RefreshToken.java`（新增 `activeRole` 屬性與其映射）
  - [x] 建立 `ProfileRepository.java` 介面
  - [x] 建立 `GatekeeperRuleRepository.java` 介面

- [x] **3. 後端安全過濾器與 JWT Claims 綁定**
  - [x] 修改 `AuthService.java` 
    - [x] `createAuthResponse` 簽發 jwt 時注入 `userId` claims
    - [x] `refreshToken` 邏輯改自 `RefreshToken` 取得 `activeRole`，並實作 `NULL` 安全 fallback 回退至 `user.getRole()`
  - [x] 修改 `JwtAuthenticationFilter.java`
    - [x] `doFilterInternal` 中解析 `userId` 呼叫 `TokenContext.setUserId(...)`
    - [x] 於 `finally` 區塊呼叫 `TokenContext.clear()` 確保安全清除

- [x] **4. 後端 Application 服務層與 API 實作**
  - [x] 建立 `GatekeeperService.java`（CRUD 規則、`isBlocked` 與 `checkExemption` 整合點）
  - [x] 修改 `ServicePlanService.java`（整合方案隱藏過濾）
  - [x] 修改 `BookingService.java`（加入全域門禁卡控與免填問卷接口）
  - [x] 修改 `AuthController.java`（新增 `POST /api/auth/switch-role`，處理 lazy initialization 與 `DataIntegrityViolationException` 冪等，返回全新 `AuthResponse`）
  - [x] 建立 `GatekeeperController.java`（提供 CRUD 端點與 SaaS 訂閱等級卡控）

- [x] **5. 前端實作與路由註冊**
  - [x] 修改 `RoleContext.tsx`（`setRole` 整合 switch-role API 與 token 覆寫，相容 E2E 自動登入）
  - [x] 建立 `GatekeeperSettings.tsx` 頁面（SaaS 方案升級提示、Email 防呆驗證與規則管理）
  - [x] 修改 `App.tsx` 註冊 `/sitter/gatekeeper` 路由與 Menu 入口

- [x] **6. 驗證與測試 (Verification)**
  - [x] 建立 `frontend/e2e/gatekeeper-flow.spec.ts` 測試檔案
  - [x] 執行 E2E 與編譯檢查（5 個 scenarios 100% 通過）

- [x] **7. 系統審計 (System Audit) 修正與優化**
  - [x] 修改 `pom.xml` 加入 `spring-boot-starter-actuator` 與 `springdoc-openapi-starter-webmvc-ui`
  - [x] 修改 `SecurityConfig.java` 允許 Actuator 與 Swagger 匿名存取
  - [x] 修改 `application.yml` 設定健康檢查路徑與細節隱蔽
  - [x] 於 `GatekeeperService.java` 中將日誌 (`log.info` 與 `log.warn`) 實裝到 addRule 與 deleteRule 關鍵點
  - [x] 執行 Maven 測試與驗證編譯合規
