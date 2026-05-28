# SD-001: 角色切換與檔案管理 (含預約門禁) 實作計畫

本計畫旨在實作「角色切換與檔案管理」功能，使單一帳號能流暢切換「保母 (Sitter)」與「飼主 (Client)」雙重身分，且資料依角色 Profile 隔離。同時，將為專業版 (Pro) 與頂級版 (Ultimate) 保母實作「預約門禁 (Gatekeeper)」設定，提供黑/白名單以及免填問卷清單管理，並在預約流程中進行防禦性卡控。

---

## User Review Required

> [!IMPORTANT]
> **1. 雙身分切換與 Lazy Initialization 機制 (Race Condition 防守)**
> - **切換機制**：後端新增 `POST /api/auth/switch-role` 接口。傳入欲切換的角色 (`SITTER` 或 `OWNER`)，後端檢核使用者是否擁有該 Profile；若無則自動執行 Lazy Initialization 新增。
> - **資料庫安全防線**：為防止高併發切換時 `check-then-act` 導致重複建立 Profile 拋出 `NonUniqueResultException`，將在 `profiles` 表加上唯一索引 `UNIQUE(user_id, type)`。後端 Java 程式碼在 save 時若遇到 `DataIntegrityViolationException`，將自動 catch 並重新 `find` 獲取以實現冪等。
> - **Token Refresh 防回退防線 (CRITICAL)**：
>   - `POST /api/auth/switch-role` 會返回包含全新 `accessToken` 與 `refreshToken` 的完整 `AuthResponse`。
>   - 在 `refresh_tokens` 表中加入 `active_role` 欄位。切換身分時將切換後的 active role 存入 refresh token 中。
>   - 重構 `AuthService.refreshToken()`：重新簽發 access token 時，其 role 欄位**不再**讀取 DB `User.getRole()`，而是從 `RefreshToken.getActiveRole()` 讀取，以防 access token 過期自動刷新時角色被默默回退。
> - **TokenContext dummy UUID 修正 (CRITICAL)**：
>   - 在 `AuthService.createAuthResponse()` 中，將 `userId` 寫入 JWT extra claims。
>   - 在 `JwtAuthenticationFilter.doFilterInternal()` 驗證 token 時取出 `userId`，並呼叫 `TokenContext.setUserId(...)`；並在過濾鏈執行完後於 `finally` 區塊呼叫 `TokenContext.clear()` 避免洩漏。
> - **E2E 相容性**：為了不打爆現有 20 支 E2E 測試中對 `loginAsRole()` 的硬編碼登入依賴，我們將**維持**原先 `loginAsRole()` 的帳號自動登入機制不動。切換角色功能的 API `/api/auth/switch-role` 與相關 Token 切換邏輯僅在真實前台點擊「切換角色」時呼叫，並在 `gatekeeper-flow.spec.ts` 中單獨對此 `switch-role` 接口進行 mock 驗證。
>
> **2. 預約門禁 (Gatekeeper) 的黑白名單互斥與優先級**
> - **互斥驗證**：同一目標帳號不能同時被加入同一個方案（或全域）的黑名單與白名單中。
> - **資料庫互斥索引 (DB-level Exclusion)**：除在 Service 層進行互斥檢核與 Pessimistic Lock 外，為防高並發髒資料，在 `gatekeeper_rules` 新增排除性 Partial Unique Indexes：
>   - 全域：`CREATE UNIQUE INDEX uidx_gatekeeper_global_excl ON gatekeeper_rules(sitter_id, target_user_id) WHERE scope_type = 'GLOBAL' AND rule_type IN ('BLACK', 'WHITE');`
>   - 方案：`CREATE UNIQUE INDEX uidx_gatekeeper_plan_excl ON gatekeeper_rules(sitter_id, plan_id, target_user_id) WHERE scope_type = 'PLAN' AND rule_type IN ('BLACK', 'WHITE');`
> - **CHECK 約束防漏**：在 `gatekeeper_rules` 加入 `CONSTRAINT chk_plan_scope CHECK (scope_type != 'PLAN' OR plan_id IS NOT NULL)` 防止 NULL 欄位繞過 Unique Index 檢核。
> - **優先級防線**：若因其他意外導致衝突，系統設計預設以**黑名單優先級高於白名單** (Deny-by-default)。
> - **查無帳號防禦**：保母設定規則時，只允許輸入已註冊的 Email。後端在設定時會先查詢對應使用者，若查無此 Email 則直接返回 `404 Not Found`，不進行 Email 暫存。
>
> **3. 隱私遮蔽與敏感資料卡控**
> - **名單遮蔽**：在門禁設定清單 API 中，為防個資洩漏，目標使用者的 Email 應進行遮蔽處理（如 `wi***@gmail.com`）。
> - **信用指標 (Trust Score) 洩漏卡控**：保母 Profile 內含 `trust_score` (初始值 100)。**本 SD 僅負責 DB schema 建立與 switch-role 的 Lazy 初始化**，Profile 的公開 CRUD（個人資料讀取與更新）API 屬於 `PRD-018` 範疇。為防敏感資料外洩，本 SD 將確保 DTO（例如 `SitterProfileDto`）完全不宣告 `trustScore` 欄位，且該欄位在 JWT token claims 中完全隱藏，僅供後續 Admin Dashboard API 獲取。
>
> **4. 方案到期與降級失效處理 (Subscription Gating)**
> - 在預約網頁載入方案及送出預約時，後端會即時查詢保母的 `Subscription`。
> - 若保母的訂閱已過期 (`expiredAt` < 現在時間) 或降為 `FREE`/`BASIC`：系統會**繞過**門禁過濾邏輯（即名單失效，視為全域 `OPEN` 正常開放），直到重新訂閱。

---

## Open Questions

目前設計已與使用者完成對齊，所有 Questions 已釐清。

---

## Proposed Changes

### 1. 資料庫變更 (Flyway Migrations)

#### [NEW] [V20260527_01__create_profiles_and_gatekeeper.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V20260527_01__create_profiles_and_gatekeeper.sql)
- 建立 `profiles` 表（包含唯一索引防止 Lazy Initialization 並發建立）。
- 建立 `gatekeeper_rules` 表，並建立 **Partial Unique Indexes** 與 `chk_plan_scope` 的 CHECK 約束。
- 補建 `log_user_action` 表以供多租戶審計。
- 擴充 `refresh_tokens` 表，新增 `active_role` 欄位儲存切換角色狀態。

---

### 2. 後端 Domain 與 Infrastructure 實作

#### [NEW] [Profile.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/Profile.java)
- 儲存角色專屬資訊（包含 `userId`、`type`、`trustScore`、`kycStatus` 等）。

#### [NEW] [GatekeeperRule.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/GatekeeperRule.java)
- 門禁規則實體。

#### [MODIFY] [RefreshToken.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/RefreshToken.java)
- 擴充新增 `activeRole` 屬性與其資料庫映射。

#### [NEW] [ProfileRepository.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/repository/ProfileRepository.java)
- 提供 `Optional<Profile> findByUserIdAndType(UUID userId, String type)`。

#### [NEW] [GatekeeperRuleRepository.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/repository/GatekeeperRuleRepository.java)
- 提供保母門禁規則查詢。

---

### 3. 後端 Application 服務層

#### [NEW] [GatekeeperService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/GatekeeperService.java)
- 提供門禁規則設定的 CRUD，並在 @Transactional 寫入時進行對應的互斥檢核防禦。
- 提供 `isBlocked(UUID sitterId, UUID clientId, UUID planId)` 判斷是否被黑名單封鎖或被排除於白名單之外。
- 提供 `checkExemption(UUID sitterId, UUID clientId)`，若在 `NO_QUESTIONNAIRE` 白名單則回傳 `true`。

#### [MODIFY] [ServicePlanService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/ServicePlanService.java)
- 整合 `GatekeeperService`，在飼主讀取方案時進行方案隱藏過濾。

#### [MODIFY] [BookingService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/BookingService.java)
- **全域封鎖防線**：在 `createBooking` 最前端呼叫 `GatekeeperService.isBlocked(sitterId, clientId, null)`，若為 `true` 則拋出 `AccessDeniedException`，防止被封鎖的客戶直接 POST 打入預約。
- **免填問卷卡控**：於預約送單中調用 `checkExemption()` 來作為問卷卡控的跳過依據（保留為與問卷 PRD-004 串接的接口）。

#### [MODIFY] [AuthService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/AuthService.java)
- **userId claim 注入**：於 `createAuthResponse` 簽發 jwt 時，注入 `userId` 到 claims。
- **Refresh 角色不回退**：修改 `refreshToken` 邏輯，自 `RefreshToken` 取得 `activeRole` 進行簽發，而非讀取 `User.role`。

---

### 4. 後端 Interfaces 與 Security 過濾

#### [MODIFY] [JwtAuthenticationFilter.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/infrastructure/security/JwtAuthenticationFilter.java)
- 於 `doFilterInternal` 驗證 token 時取出 `userId` 並呼叫 `TokenContext.setUserId(...)`；在 `finally` 區塊呼叫 `TokenContext.clear()` 阻斷洩漏。

#### [MODIFY] [AuthController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/AuthController.java)
- 新增 `POST /api/auth/switch-role` 接口：
  - 驗證並 Lazy Initialize 建立 Profile，捕獲並處理 `DataIntegrityViolationException` 以防並發建立衝突。
  - 註銷舊的 refresh token，重新簽發包含切換後角色特權的全新 `AuthResponse` (含 accessToken 與 refreshToken)。

#### [NEW] [GatekeeperController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/GatekeeperController.java)
- 提供 `/api/sitter/gatekeeper` 規則管理端點。
- 檢驗保母訂閱權限（Pro 方案僅限黑名單，Ultimate 方案可設定黑白名單，其餘 403）。

---

### 5. 前端實作 (Vite + React)

#### [MODIFY] [RoleContext.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/contexts/RoleContext.tsx)
- 在 `setRole` 中發送 `POST /api/auth/switch-role` 請求以換取新 JWT（在測試環境時，若 mock 不存在，相容原先 local `loginAsRole` 的自動登入行為）。

#### [NEW] [GatekeeperSettings.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/GatekeeperSettings.tsx)
- 保母端門禁設定管理頁面。包含 SaaS 方案限制提示、新增已註冊 Email 的防禦校驗、以及黑/白/免問卷名單 CRUD。

#### [MODIFY] [App.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/App.tsx)
- 註冊 `/sitter/gatekeeper` 路由並在 Sitter Dashboard 加入入口。

---

## Verification Plan

### Automated Tests

#### [NEW] [gatekeeper-flow.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/gatekeeper-flow.spec.ts)
E2E 測試驗證情境：
1. **角色切換與主題**：驗證點擊切換角色後，HTML 主題 data-theme 變更且 token 更新。
2. **SaaS 權限攔截**：測試 Free 方案保母進入設定頁顯示功能鎖定與升級提示，拒絕 API 寫入。
3. **黑白名單互斥與格式校驗**：輸入格式錯誤 Email 給予提示，且限制同一 Email 不能並存於黑/白名單。
4. **預約攔截防線**：
   - 飼主 A 被保母列入「全域黑名單」：造訪預約頁時，阻擋並提示「保母目前不開放預約」。
   - 飼主 B 被保母列入「特定方案 A 黑名單」：預約頁面中方案 A 將自動隱藏。
5. **降級處理失效**：模擬保母方案過期或降級，驗證原本的黑名單阻擋失效，放行預約。

### Manual Verification
- 登入 `sitter@test.com` 切換為 Sitter 身份，手動配置門禁規則。
- 使用 `owner@test.com` 登入，進入該 Sitter 預約網頁驗證方案隱藏與封鎖訊息。
