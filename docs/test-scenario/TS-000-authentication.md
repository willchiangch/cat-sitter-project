# TS-000: 使用者認證與權限控管 (Authentication & Authorization)

## 1. 測試目標
驗證系統的身分驗證機制（JWT）、Token 刷新邏輯以及角色權限控制（RBAC）是否符合安全規範。

## 2. 前置條件
- 後端服務已啟動
- 資料庫已初始化 `users`、`refresh_tokens` 與 `registration_otps` 表

## 3. 測試情境 (Test Cases)

| 編號 | 標題 | 測試步驟 | 預期結果 |
|:---|:---|:---|:---|
| TS-000-01 | 送出註冊表單觸發 OTP 寄送 | 1. POST `/api/auth/register` 帶入新用戶資訊 | 回傳 200 `{status: "OTP_SENT"}`，`users` 表尚未建立該筆帳號，`registration_otps` 出現待驗證紀錄 |
| TS-000-02 | 成功登入 | 1. POST `/api/auth/login` 帶入正確憑證 | 回傳 200，包含有效 accessToken |
| TS-000-03 | 登入失敗 (密碼錯誤) | 1. POST `/api/auth/login` 帶入錯誤密碼 | 回傳 401，錯誤訊息為「帳號或密碼錯誤」 |
| TS-000-04 | Token 刷新 (Refresh) | 1. 使用登入取得的 refreshToken 呼叫 `/api/auth/refresh` | 回傳 200，取得新的 accessToken |
| TS-000-05 | 存取受保護 API (無 Token) | 1. 不帶 Header 呼叫 `/api/orders/booking` | 回傳 401 Unauthorized |
| TS-000-06 | 角色權限衝突 (RBAC) | 1. 使用 OWNER Token 呼叫 SITTER 專用 API (如報價) | 回傳 403 Forbidden |
| TS-000-07 | Token 偽造/無效 | 1. 攜帶隨意竄改的 JWT 呼叫 API | 回傳 401 Unauthorized |
| TS-000-08 | 連續 5 次登入失敗後鎖定 | 1. 對同一帳號連續送出 5 次錯誤密碼 2. 第 6 次改用正確密碼登入 | 第 6 次仍回傳 429（帳號鎖定 10 分鐘），而非 401；鎖定不因密碼正確而解除 |
| TS-000-09 | 忘記密碼／重設密碼完整流程 | 1. POST `/api/auth/forgot-password` 2. 用取得的 token 呼叫 `/api/auth/reset-password` 帶新密碼 3. 用新密碼登入 | 重設成功，token 標記 `used=true` 後不可重複使用；新密碼可正常登入 |
| TS-000-10 | 重設密碼 token 已過期/不存在 | 1. 使用過期或亂造的 token 呼叫 `/api/auth/reset-password` | 回傳 400，提示需重新申請 |
| TS-000-11 | OTP 驗證成功完成註冊 (PRD-000 AC-1) | 1. POST `/api/auth/register` 取得 OTP 2. POST `/api/auth/register/verify-otp` 帶入正確驗證碼 | 回傳 200，包含 accessToken/refreshToken（自動登入）；此時 `users` 表才建立該筆帳號，`registration_otps` 該筆紀錄已刪除 |
| TS-000-12 | OTP 輸入錯誤 | 1. POST `/api/auth/register/verify-otp` 帶入錯誤驗證碼 | 回傳 400 `OTP_INVALID`，該筆 `registration_otps.attempts` 累加 1 |
| TS-000-13 | OTP 連續輸入錯誤達上限 | 1. 對同一筆註冊連續送出 5 次錯誤驗證碼 2. 第 6 次再次呼叫 | 第 6 次回傳 429 `OTP_LOCKED`，須重新寄送驗證碼才能繼續 |
| TS-000-14 | OTP 已過期 | 1. 等待或將 `expires_at` 調整至過去 2. 呼叫 `/api/auth/register/verify-otp` | 回傳 400 `OTP_EXPIRED` |
| TS-000-15 | OTP 重寄冷卻中重複重寄 | 1. POST `/api/auth/register` 後立即 2. POST `/api/auth/register/resend-otp` | 回傳 429 `OTP_RESEND_TOO_SOON`（未滿 60 秒冷卻時間） |
| TS-000-16 | Email 已是正式帳號時重複註冊 | 1. 完成一次註冊+OTP 驗證 2. 用同一 Email 再次 POST `/api/auth/register` | 回傳 400，提示「電子郵件已存在」 |
| TS-000-17 | 帳號註銷成功 (PRD-000 AC-8) | 1. 登入後 POST `/api/auth/deactivate` 帶正確密碼，且名下無未結案訂單 | 回傳 200，`users.is_deleted = true` |
| TS-000-18 | 帳號註銷密碼錯誤 | 1. POST `/api/auth/deactivate` 帶錯誤密碼 | 回傳 403（非 401，避免撞上前端全域 refresh-token 重試） |
| TS-000-19 | 帳號註銷尚有未結案訂單 | 1. 名下有 `CONFIRMED`/`IN_PROGRESS`/`DISPUTED` 等非終態訂單時 POST `/api/auth/deactivate` | 回傳 409 `ACCOUNT_DEACTIVATION_BLOCKED` |
| TS-000-20 | 帳號註銷不阻擋已結案訂單 | 1. 名下僅有 `COMPLETED`/`CANCELLED` 訂單時 POST `/api/auth/deactivate` | 回傳 200，成功註銷 |
| TS-000-21 | 註銷後無法登入 | 1. 註銷成功後用原帳密 POST `/api/auth/login` | 回傳 401「帳號或密碼錯誤」，不透露帳號已註銷 |
| TS-000-22 | 註銷後 Refresh Token 全數失效 | 1. 註銷前取得的 `refreshToken` 於註銷後查詢 | `refresh_tokens` 表中對應紀錄已被刪除 |
| TS-000-23 | 註銷後信任圈與我的最愛自動清除 | 1. 保母帳號涉及信任圈（`trust_relationships`）與被收藏（`favorite_sitters`）2. 執行註銷 | 兩表中涉及該使用者的紀錄 `is_deleted = true`（非阻擋條件，自動清除） |
| TS-000-24 | 註銷後公開檔案回 404 | 1. 保母帳號註銷後，任意使用者 GET `/api/sitter/profile/{sitterId}` | 回傳 404 `MSG_DATA_F11`（沿用 `SitterPublicProfileServiceImpl` 既有的 `user.isDeleted()` 檢查） |
| TS-000-25 | Google 登入新 Email 未帶角色 | 1. POST `/api/auth/google` 帶入尚未有對應帳號的 Email 之有效 ID Token、不帶 `role` | 回傳 200 `NEEDS_ROLE_SELECTION`，`users` 表未建立該筆帳號 |
| TS-000-26 | Google 登入新 Email 帶角色建立帳號 | 1. 承上，再次 POST `/api/auth/google` 帶入相同 ID Token 與 `role=OWNER` | 回傳 200 `SUCCESS` 並核發 Token，`users` 表建立該筆帳號且角色正確 |
| TS-000-27 | Google 登入既有 Email 自動綁定 (PRD-000 AC-5) | 1. 使用已用 Email/密碼註冊過的帳號 Email，POST `/api/auth/google` | 直接回傳 200 `SUCCESS` 並核發 Token，不重複建立帳號 |
| TS-000-28 | Google 登入對應已註銷帳號 | 1. Email 對應帳號已執行 AC-8 註銷，POST `/api/auth/google` | 回傳 401「帳號或密碼錯誤」，不透露帳號已註銷 |
| TS-000-29 | Google ID Token 驗證失敗 | 1. POST `/api/auth/google` 帶入無效/偽造/過期的 ID Token | 回傳 401 `GOOGLE_TOKEN_INVALID` |
| TS-000-30 | Google 帳號 Email 未驗證 | 1. ID Token 的 `email_verified` 為 false 時 POST `/api/auth/google` | 回傳 401 `GOOGLE_EMAIL_NOT_VERIFIED` |
| TS-000-31 | 註冊頁未勾選服務條款無法送出 (PRD-000 AC-2) | 1. 於註冊表單填妥姓名/Email/密碼，不勾選「我已閱讀並同意」 | 送出按鈕維持 disabled，無法呼叫 `/api/auth/register`；勾選後按鈕才可點擊 |
| TS-000-32 | 帳號註銷與新帳號建立寫入稽核日誌 | 1. 完成 OTP 註冊 / Google 首次登入建立帳號 2. 執行帳號註銷 | `log_user_action` 出現對應紀錄：建立帳號 `func_code=AUTH_REGISTER`/`action_type=CREATE`；註銷 `func_code=AUTH_DEACTIVATE`/`action_type=DELETE` |
| TS-000-33 | 登出所有裝置成功 | 1. 登入後 POST `/api/auth/logout-all-devices` | 回傳 200 `{status: "SUCCESS"}`；該使用者的 `refresh_tokens` 紀錄被刪除 |
| TS-000-34 | 登出所有裝置後仍可用密碼重新登入 | 1. 承上執行登出所有裝置 2. 用原帳密 POST `/api/auth/login` | 回傳 200 並核發新的 accessToken/refreshToken（僅撤銷登入狀態，非帳號停用） |
| TS-000-35 | 未登入無法取得生物辨識註冊選項 (PRD-000 AC-6) | 1. 不帶 Token POST `/api/auth/webauthn/register/options` | 回傳 401 |
| TS-000-36 | 已登入可取得合法的生物辨識註冊選項 | 1. 帶 Token POST `/api/auth/webauthn/register/options` | 回傳 200，內容含 `publicKey.challenge`/`publicKey.rp.id`/`publicKey.user.name`（= 使用者 email） |
| TS-000-37 | 生物辨識註冊驗證失敗 | 1. POST `/api/auth/webauthn/register/verify` 帶入非法的 `credentialJson` | 回傳 400 `WEBAUTHN_REGISTRATION_FAILED` |
| TS-000-38 | 生物辨識註冊挑戰已過期 | 1. 取得 options 後將對應 `webauthn_challenges.expires_at` 改為過去 2. 呼叫 verify | 回傳 400 `WEBAUTHN_CHALLENGE_EXPIRED` |
| TS-000-39 | 生物辨識登入選項不洩漏帳號存在性 | 1. POST `/api/auth/webauthn/login/options` 帶入不存在或尚未開通生物辨識的 Email | 回傳 200，`publicKey.allowCredentials` 為空陣列（非 404/400） |
| TS-000-40 | 生物辨識登入選項回傳已註冊憑證 | 1. 使用者已註冊過一組生物辨識憑證 2. POST `/api/auth/webauthn/login/options` | 回傳 200，`publicKey.allowCredentials` 長度為已註冊憑證數 |
| TS-000-41 | 生物辨識登入驗證失敗 | 1. POST `/api/auth/webauthn/login/verify` 帶入非法的 `credentialJson` | 回傳 401 `WEBAUTHN_LOGIN_FAILED` |
| TS-000-42 | 已註銷帳號無法用生物辨識登入 | 1. 帳號已執行 AC-8 註銷 2. POST `/api/auth/webauthn/login/verify` | 回傳 401「帳號或密碼錯誤」，不透露帳號已註銷 |
| TS-000-43 | 生物辨識裝置清單僅回傳自己名下的裝置 | 1. 已登入使用者 GET `/api/auth/webauthn/credentials` | 回傳 200，僅含自己名下的裝置摘要（id/createdAt/lastUsedAt） |
| TS-000-44 | 刪除他人的生物辨識裝置應被拒絕 | 1. DELETE `/api/auth/webauthn/credentials/{他人裝置 id}` | 回傳 403，該筆裝置未被刪除 |
| TS-000-45 | 完整生物辨識開通與登入流程 (端對端) | 1. 登入後於帳號設定頁開啟生物辨識 2. 登出 3. 於登入頁輸入 Email 並點擊「使用生物辨識登入」 | 開通與登入均成功，最終導向 `/demo`；透過 Playwright CDP virtual authenticator 模擬真實裝置，全程打真實後端與資料庫，非 mock |
| TS-000-46 | 不支援 WebAuthn 的裝置自動隱藏生物辨識按鈕 | 1. 模擬 `window.PublicKeyCredential === undefined` 2. 開啟登入頁與帳號設定頁 | 「使用生物辨識登入」與「開啟此裝置的生物辨識」按鈕皆不顯示（NFR-005 降級原則，比照 Google 按鈕） |

## 4. 自動化追溯 (Automation Trace)
- **單元測試**: `JwtUtilsTest.java`
- **整合測試**: `AuthControllerTest.java`（含 `should_VerifyOtp_Successfully_And_CreateUser_And_ReturnToken`, `should_Return429_When_OtpAttempts_ExceedLimit`, `should_Return400_When_OtpExpired`, `should_Return429_When_ResendTooSoon`, `should_LockAccount_After5FailedAttempts`, `should_ResetPassword_Successfully`, `should_DeactivateAccount_Successfully`, `should_Return403_When_DeactivatePasswordIncorrect`, `should_Return409_When_DeactivateWithActiveOrder`, `should_AllowDeactivate_When_OnlyTerminalOrdersExist`, `should_RejectLogin_After_Deactivate`, `should_RevokeRefreshToken_After_Deactivate`, `should_CleanupTrustCircleAndFavorites_After_Deactivate`, `should_ReturnNeedsRoleSelection_When_GoogleLogin_NewEmail_NoRole`, `should_CreateUser_When_GoogleLogin_NewEmail_WithRole`, `should_AutoLogin_When_GoogleLogin_ExistingEmail`, `should_Return401_When_GoogleLogin_ExistingButDeletedEmail`, `should_Return401_When_GoogleTokenInvalid`, `should_Return401_When_GoogleEmailNotVerified`, `should_RevokeRefreshToken_After_LogoutAllDevices`, `should_StillAllowLogin_After_LogoutAllDevices`）、`WebAuthnControllerTest.java`（15 個情境，含 TS-000-35~44 對應測試）、`SitterPublicProfileControllerTest.java`（含 `testGetProfile_ReturnsNotFound_When_SitterDeactivated`）
- **E2E 對應**: `auth-register-and-password-reset.spec.ts`（含 `register-terms-checkbox` gating 斷言）、`account-deactivation.spec.ts`、`google-login.spec.ts`、`webauthn-login.spec.ts`
- **稽核日誌手動驗證**：TS-000-32 目前僅程式碼串接（`AuthService` 呼叫 `AuditLogService.writeUserActionLogInline`/`writeUserActionLog`），未另建自動化整合測試斷言 `log_user_action` 資料列（現有測試僅涵蓋 API 回應狀態，DB 稽核紀錄需人工查詢驗證）
- **WebAuthn 測試取捨**：`WebAuthnControllerTest.java` 涵蓋選項產生、挑戰過期、權限與帳號存在性防護等業務邏輯，但**不**涵蓋真正通過簽章驗證的完整 attestation/assertion 迴圈——在 Java 端手刻一組合法的假憑證簽章（CBOR/COSE 結構）成本過高。這段改由 `webauthn-login.spec.ts` 用 Playwright 的 Chrome DevTools Protocol WebAuthn virtual authenticator（Playwright 官方推薦作法）在瀏覽器層跑真實的 `navigator.credentials.create()/get()` 補上，因此該支 E2E **刻意不 mock** `/api/auth/webauthn/**`，需要真正在跑的後端 + 資料庫，跟其餘全 mock 的 E2E 慣例不同；也因為打的是共用的種子帳號（`owner@test.com`）在真實持久 DB 上的紀錄，測試一開始會先清空該帳號既有的生物辨識憑證再重新註冊，避免多次執行後 `allowCredentials` 累積出跟本次虛擬 authenticator 不匹配的舊憑證。
- **測試指令**: `mvn test -Dtest=AuthControllerTest,WebAuthnControllerTest,SitterPublicProfileControllerTest,JwtUtilsTest`
