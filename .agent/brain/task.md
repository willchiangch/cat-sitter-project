# Task: 社群登入與通訊驗證實作 (Social Auth & Verification)

- `[x]` **後端基礎架構 (Backend Foundations)**
    - `[x]` 更新 `pom.xml` 加入 `oauth2-client` 依賴
    - `[x]` 擴充 `OAuthProvider` 枚舉以支援 `FACEBOOK`
    - `[x]` 在 `application-smoke.yml` 配置 OAuth2 Client Placeholders
- `[x]` **認證邏輯實作 (Auth Logic Implementation)**
    - `[x]` 實作 `CustomOAuth2UserService` (Handle Account creation/binding)
    - `[x]` 實作 `OAuth2AuthenticationSuccessHandler` (Issuing JWT & Redirect)
    - `[x]` 更新 `SecurityConfig.java` 串接 OAuth2 流程
- `[x]` **Email 通訊驗證 (Email Verification Flow)**
    - `[x]` 實作 `EmailVerificationService` (呼叫 Resend API)
    - `[x]` 新增驗證端點與 Token 管理邏輯
- `[x]` **前端 UI 整合 (Frontend UI Integration)**
    - `[x]` 建立登入頁面 `LoginPage.jsx` (Google/FB/Apple 按鈕)
    - `[x]` 建立回呼處理頁面 `LoginCallback.jsx`
    - `[x]` 實作 Dashboard 內的 Email 驗證提示與彈窗
- `[x]` **整合驗證 (Integration & E2E)**
    - `[x]` 撰寫 Mock OAuth2 登入測試
    - `[x]` 執行全流程 E2E 驗證
