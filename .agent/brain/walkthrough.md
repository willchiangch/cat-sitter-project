# SD-000: 身分驗證與授權 (Authentication & Authorization) 實作回顧

## 實作亮點
1. **無狀態 JWT 架構**: 使用 Spring Security 6 結合 JJWT 0.12.6，實作 Stateless 認證流，確保系統可水平擴展。
2. **Security-First 整合**: 在進入核心業務（SD-009）前先行部署安全防護網，確保所有 API 預設受保護。
3. **RBAC 權限控管**: 整合 `OWNER`, `SITTER`, `ADMIN` 角色權限，並與現有 SaaS Gating AOP 完美相容。
4. **全域異常映射**: 透過 `GlobalExceptionHandler` 將 `BadCredentialsException` 正確映射為 401，提升 API 易用性。

## 變動檔案
- `pom.xml`: 加入 `spring-boot-starter-security` 與 JWT 相關依賴。
- `SecurityConfig.java`: 核心安全配置，定義過濾器鏈與密碼編碼器。
- `JwtAuthenticationFilter.java`: 請求攔截器，解析 Token 並填充 SecurityContext。
- `JwtUtils.java`: JWT 生成、解析與驗證工具類。
- `AuthService.java` & `AuthController.java`: 使用者註冊與登入入口。
- `GlobalExceptionHandler.java`: 整合安全異常處理。

## 測試驗證
- **AuthControllerTest**: 
    - 註冊流程：成功建立使用者並取得 Token。
    - 登入流程：驗證憑證並回傳正確 Token 與角色。
    - 異常流程：密碼錯誤回傳 401。
- **OrderControllerTest**:
    - 驗證 `@WithMockUser` 與手動 User 注入在 Security Filter 下的運作。
    - 確保受保護 API（如調價）在無 Token 時回傳 403，有 Token 時正常運作。

## 基礎設施加固 (Infrastructure Hardening)
- **多階段構建 Dockerfile**: 實作了符合 `SD-GLOBAL-SPEC` 的 Production-ready Dockerfile，確保最小化鏡像體積並強制時區為 UTC。
- **單元測試覆蓋**: 補齊 `JwtUtilsTest`，確保加密簽名與過期邏輯的底層穩定性。
- **全域審計通過**: 經 `project-auditor` 掃描，系統在時區、精度與併發控制上已達到 100% 合規。

## SD-009: 訂單結案與排程觸發 (Order Completion & Cron Trigger)
- **API-Driven Cron**: 為解決 Cloud Run 縮容至 0 導致排程失效的問題，改採 `POST /api/internal/cron/...` 觸發模式，由外部 GCP Cloud Scheduler 驅動。
- **內部安全防護**: 透過 `InternalSecretFilter` 校驗自定義 Header，確保內部 API 不被外網非法呼叫。
- **結案邏輯實作**:
    - **殭屍行程清理**: 自動標記 72hr 未打卡行程。
    - **48hr 緩衝結案**: 行程結束後 48 小時自動完成訂單，並預設 D+3 財務撥款基準。
- **驗證**: 通過 `CompletionServiceTest` 與 `InternalCronControllerTest`。

## 下一步
- 進入 **SD-016 (Refund/Dispute)**: 實作退款策略 AOP 與爭議回報流程。
