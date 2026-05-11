# 實作進度追蹤 (SD Implementation)

- [x] **SD-000 (Auth)**: 實作身分驗證基礎建設
    - [x] 配置 Spring Security 與 JWT 依賴 (JJWT 0.12.6, HS512)
    - [x] 實作 `JwtUtils` 與 `JwtAuthenticationFilter` (Access 15m)
    - [x] 實作 **Refresh Token 機制** (7d, DB 儲存與撤銷支援)
    - [x] 實作 `AuthService` 與 `AuthController` (雙 Token 回傳與 /refresh 接口)
    - [x] **安全加固**: 開啟 CORS、放行 OPTIONS 預檢、強制無狀態與關閉 CSRF
    - [x] 整合 `GlobalExceptionHandler` 處理認證異常 (401 映射)
    - [x] 驗證並修復現有整合測試 (`OrderControllerTest` @WithMockUser)
- [x] **SD-009 (Completion)**: 結案評價與服務流程結束
    - [x] 實作 `InternalCronController` 與 `X-Internal-Secret` 防護
    - [x] 實作 `CompletionService` (72hr 殭屍清理 + 48hr 自動結案)
    - [x] 實作手動結案 API 與權限檢查
    - [x] 實作 `LocalCronSimulator` 模擬外部觸發
    - [ ] 實作評價模型與 API (PRD-020)
- [ ] **SD-016 (Refund/Dispute)**: 退款與爭議管理
    - [ ] 實作取消退款策略 AOP
    - [ ] 實作爭議回報流程
