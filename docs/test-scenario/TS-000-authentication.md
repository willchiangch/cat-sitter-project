# TS-000: 使用者認證與權限控管 (Authentication & Authorization)

## 1. 測試目標
驗證系統的身分驗證機制（JWT）、Token 刷新邏輯以及角色權限控制（RBAC）是否符合安全規範。

## 2. 前置條件
- 後端服務已啟動
- 資料庫已初始化 `users` 與 `refresh_tokens` 表

## 3. 測試情境 (Test Cases)

| 編號 | 標題 | 測試步驟 | 預期結果 |
|:---|:---|:---|:---|
| TS-000-01 | 成功註冊 | 1. POST `/api/auth/register` 帶入新用戶資訊 | 回傳 200，包含 accessToken 與 refreshToken |
| TS-000-02 | 成功登入 | 1. POST `/api/auth/login` 帶入正確憑證 | 回傳 200，包含有效 accessToken |
| TS-000-03 | 登入失敗 (密碼錯誤) | 1. POST `/api/auth/login` 帶入錯誤密碼 | 回傳 401，錯誤訊息為「帳號或密碼錯誤」 |
| TS-000-04 | Token 刷新 (Refresh) | 1. 使用登入取得的 refreshToken 呼叫 `/api/auth/refresh` | 回傳 200，取得新的 accessToken |
| TS-000-05 | 存取受保護 API (無 Token) | 1. 不帶 Header 呼叫 `/api/orders/booking` | 回傳 401 Unauthorized |
| TS-000-06 | 角色權限衝突 (RBAC) | 1. 使用 OWNER Token 呼叫 SITTER 專用 API (如報價) | 回傳 403 Forbidden |
| TS-000-07 | Token 偽造/無效 | 1. 攜帶隨意竄改的 JWT 呼叫 API | 回傳 401 Unauthorized |

## 4. 自動化追溯 (Automation Trace)
- **單元測試**: `JwtUtilsTest.java`
- **整合測試**: `AuthControllerTest.java`
- **測試指令**: `mvn test -Dtest=AuthControllerTest,JwtUtilsTest`
