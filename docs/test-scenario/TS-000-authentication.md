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
| TS-000-47 | 同帳號併發登入（單一 session，後登入者覆蓋） | 1. 4 個執行緒同時 POST `/api/auth/login` 同一帳號，重複 8 輪 2. 帳號已有登入失敗紀錄（`failed_login_attempts=2`）時再併發登入 | 每個請求都回 200（不得 409 `MSG_DATA_CONCURRENCY_CONFLICT`）；每輪結束 `refresh_tokens` 該使用者只剩 1 筆，且是本輪某個回應拿到的那顆；失敗次數歸 0 |
| TS-000-48 | 同帳號併發 switch-role 與 Google 登入 | 1. 同時混打 `/api/auth/switch-role` 與 `/api/auth/google`（既有 Email） | 全部 200，`refresh_tokens` 只剩 1 筆 |
| TS-000-49 | 登入頁防重複送出 | 1. 雙擊「登入」、送出中再按 Enter 2. 登入成功、整頁換頁前 3. Google callback 同時觸發兩次 | 只送出 1 次 `/api/auth/login`／`/api/auth/google`；送出中與成功後換頁前按鈕維持 disabled（「登入中...」），Google 按鈕區塊 `pointer-events:none` |
| TS-000-50 | 同帳號併發打錯密碼 | 1. 4 個執行緒同時以錯誤密碼 POST `/api/auth/login`，重複 5 輪（每輪先歸零） 2. 7 個執行緒同時打錯密碼 3. 之後用正確密碼登入 | 1. 每個請求 401（不得 409），每輪 `failed_login_attempts` 恰為 4、未鎖定 2. 只有 401/429，`locked_until` ≈ now+10min，`failed_login_attempts = 0`（鎖定期間的失敗不累加） 3. 429 `ACCOUNT_LOCKED` |
| TS-000-51 | 跨分頁 refresh 失敗不清掉別分頁的新 token | 1. 分頁 A 的 API 401 → 送出 refresh 2. refresh 飛行中分頁 B 重新登入，把新 token 寫入共用 localStorage 3. A 的 refresh 401 4. 對照組：沒有別的分頁換 token | 1~3. A 不清 token，localStorage 維持 B 的新 token，A 的原請求以新 access token 重送成功、畫面正常渲染；refresh 只送一次（帶 A 原本那顆） 4. 照舊清除 `accessToken`/`refreshToken`，並導向 `/login?redirect=<原頁>`；refresh 只送一次 |
| TS-000-52 | 沒有 refresh token 時遇 401 | 1. 正式登入 session 只剩 access token（refresh token 不在）2. 開受保護頁，API 回 401 | 不送 `/auth/refresh`（過去送 `{ refreshToken: null }` 且隨 React Query 重試再送 3 次）；清除 access token 並導向 `/login?redirect=<原頁>`；React Query 對 401 不重試 |
| TS-000-53 | 沒有身分的請求不再以 dummy UUID 執行（prod profile） | 1. 匿名 POST `/api/auth/logout-all-devices`、`/deactivate`、`/switch-role` 2. 簽章有效但缺 `userId` claim 的 JWT 打 `GET /api/care-notes/templates` | 皆 401（過去 1 為 400、2 為 200：以 0000 身分查到空清單） |
| TS-000-46 | 不支援 WebAuthn 的裝置自動隱藏生物辨識按鈕 | 1. 模擬 `window.PublicKeyCredential === undefined` 2. 開啟登入頁與帳號設定頁 | 「使用生物辨識登入」與「開啟此裝置的生物辨識」按鈕皆不顯示（NFR-005 降級原則，比照 Google 按鈕） |

## 4. 自動化追溯 (Automation Trace)
- **單元測試**: `JwtUtilsTest.java`、`TokenContextTest.java`（TS-000-53）
- **prod profile 整合測試**: `TokenContextNoIdentityProdProfileTest.java`（TS-000-53）
- **整合測試**: `AuthControllerTest.java`（含 `should_VerifyOtp_Successfully_And_CreateUser_And_ReturnToken`, `should_Return429_When_OtpAttempts_ExceedLimit`, `should_Return400_When_OtpExpired`, `should_Return429_When_ResendTooSoon`, `should_LockAccount_After5FailedAttempts`, `should_ResetPassword_Successfully`, `should_DeactivateAccount_Successfully`, `should_Return403_When_DeactivatePasswordIncorrect`, `should_Return409_When_DeactivateWithActiveOrder`, `should_AllowDeactivate_When_OnlyTerminalOrdersExist`, `should_RejectLogin_After_Deactivate`, `should_RevokeRefreshToken_After_Deactivate`, `should_CleanupTrustCircleAndFavorites_After_Deactivate`, `should_ReturnNeedsRoleSelection_When_GoogleLogin_NewEmail_NoRole`, `should_CreateUser_When_GoogleLogin_NewEmail_WithRole`, `should_AutoLogin_When_GoogleLogin_ExistingEmail`, `should_Return401_When_GoogleLogin_ExistingButDeletedEmail`, `should_Return401_When_GoogleTokenInvalid`, `should_Return401_When_GoogleEmailNotVerified`, `should_RevokeRefreshToken_After_LogoutAllDevices`, `should_StillAllowLogin_After_LogoutAllDevices`, `should_NotConflict_When_ConcurrentLoginSameAccount`, `should_NotConflict_When_ConcurrentLogin_WithPreviousFailedAttempt`, `should_NotConflict_When_ConcurrentSwitchRoleAndGoogleLogin`, `should_CountEveryFailure_When_ConcurrentWrongPasswords`, `should_LockExactlyOnce_When_ConcurrentWrongPasswordsExceedThreshold`（TS-000-50））、`WebAuthnControllerTest.java`（15 個情境，含 TS-000-35~44 對應測試）、`SitterPublicProfileControllerTest.java`（含 `testGetProfile_ReturnsNotFound_When_SitterDeactivated`）
- **E2E 對應**: `auth-register-and-password-reset.spec.ts`（含 `register-terms-checkbox` gating 斷言）、`account-deactivation.spec.ts`、`google-login.spec.ts`、`webauthn-login.spec.ts`、`login-double-submit.spec.ts`（TS-000-49）、`axios-refresh-cross-tab.spec.ts`（TS-000-51/52）
- **稽核日誌手動驗證**：TS-000-32 目前僅程式碼串接（`AuthService` 呼叫 `AuditLogService.writeUserActionLogInline`/`writeUserActionLog`），未另建自動化整合測試斷言 `log_user_action` 資料列（現有測試僅涵蓋 API 回應狀態，DB 稽核紀錄需人工查詢驗證）
- **WebAuthn 測試取捨**：`WebAuthnControllerTest.java` 涵蓋選項產生、挑戰過期、權限與帳號存在性防護等業務邏輯，但**不**涵蓋真正通過簽章驗證的完整 attestation/assertion 迴圈——在 Java 端手刻一組合法的假憑證簽章（CBOR/COSE 結構）成本過高。這段改由 `webauthn-login.spec.ts` 用 Playwright 的 Chrome DevTools Protocol WebAuthn virtual authenticator（Playwright 官方推薦作法）在瀏覽器層跑真實的 `navigator.credentials.create()/get()` 補上，因此該支 E2E **刻意不 mock** `/api/auth/webauthn/**`，需要真正在跑的後端 + 資料庫，跟其餘全 mock 的 E2E 慣例不同；也因為打的是共用的種子帳號（`owner@test.com`）在真實持久 DB 上的紀錄，測試一開始會先清空該帳號既有的生物辨識憑證再重新註冊，避免多次執行後 `allowCredentials` 累積出跟本次虛擬 authenticator 不匹配的舊憑證。
- **測試指令**: `mvn test -Dtest=AuthControllerTest,WebAuthnControllerTest,SitterPublicProfileControllerTest,JwtUtilsTest`

## 5. 本測試發現並修復的真實 bug
- **沒有身分時 `TokenContext.getUserId()` 回全 0 dummy UUID（2026-10-25，第 11 批另外發現 #5 / fix-batch12 J2，安全）**：`/api/auth/**` 整段 permitAll，`switch-role` / `deactivate` / `logout-all-devices` 匿名呼叫時以 `00000000-…` 身分往下跑（回 400「找不到使用者」而非 401）；JWT 缺 `userId` claim 時，需要身分的端點也以 0000 執行（`GET /api/care-notes/templates` 回 200 空清單）。忘了掛 `@PreAuthorize` 的端點會因此靜默成功而不是失敗。改成一律拋 `AuthenticationCredentialsNotFoundException` → 401，不分 profile（專案沒有獨立 test profile，測試跑 `local`）。全套件只有 `ServicePlanControllerTest` 兩支 fixture 漏設身分、靠 dummy 剛好通過（「Sitter B 越權 → 403」實際測的是 0000），已補 `TokenContext.setUserId`。迴歸：TS-000-53，`TokenContextNoIdentityProdProfileTest` 在舊程式碼 4 支全紅（400 / 400 / 400 / 200）。
- **同帳號併發登入回 409（2026-10，backlog F1）**：`RefreshTokenRepository.deleteByUser` 原為 Spring Data 衍生刪除（先 SELECT 再逐筆 remove），兩個請求同時登入同一帳號時，晚到者要刪的列已被先到者刪掉 → Hibernate 0 rows affected → `ObjectOptimisticLockingFailureException` → 409「內容已被更新，請重新整理後再試」。真實使用者在登入頁雙擊、或兩台裝置同時登入就會看到。帳號有失敗紀錄時 `save(user)` 清失敗次數也會撞 `users.version` 樂觀鎖。修法：bulk DELETE + per-user `pg_advisory_xact_lock` + 失敗次數改條件式 bulk UPDATE（SD-000 §2.1）；前端登入頁送出中到換頁前停用所有登入按鈕。TS-000-47/48 舊程式碼 3 支全紅（第 1 輪即 409）；只拿掉 advisory lock 時 3 支也紅（DB 留下 2~4 筆 refresh token）。TS-000-49 舊前端：成功後換頁前按鈕變回 enabled → 紅
- **同帳號併發打錯密碼回 409、失敗次數少算（2026-10-24，backlog G3）**：`LoginAttemptService.registerFailedLoginAttempt` 用 `findById` + `save`，兩個請求同時打錯同一帳號的密碼時讀到同一個 `users.version`，晚到者 `save` 撞樂觀鎖 → 409「內容已被更新，請重新整理後再試」（使用者應看到 401「帳號或密碼錯誤」），且那次失敗被 rollback 沒計入，暴力嘗試可以靠併發繞過部分計數。修法：改成 `UserRepository.registerFailedLoginAttempt` 單一條件式 bulk UPDATE（+1、達 5 次設 `locked_until` 並歸零、鎖定中不累加，SD-000 §4）。TS-000-50 兩支在舊程式碼第 1 輪即 409（紅）；修正後 `AuthControllerTest` 連跑 3 次皆綠。
- **refresh 失敗會清掉別分頁剛拿到的新 token（2026-10-24，backlog G2）**：`axiosClient.ts` 的 response 攔截器 refresh 失敗時無條件 `removeItem(accessToken/refreshToken)`。單一 session 下，分頁 B 重新登入會讓分頁 A 手上那顆 refresh token 失效；若 A 的 refresh 剛好在飛，401 回來後 A 把 localStorage 裡 B 的新 token 一起清掉，兩個分頁都變成未登入。修法：refresh 前記下所用的 refreshToken，失敗時若 localStorage 已換成別顆就不清、改用新 access token 重試原請求（SD-FRONTEND-SPEC §2.1）。TS-000-51 在舊 axiosClient 上紅（帳務頁顯示「$ 0」、token 被清），修正後連跑 3 次綠。
- **refresh 失敗後停在原頁「$ 0 + 載入失敗」、並以 `refreshToken: null` 重送 refresh（2026-10-24，驗收 N16）**：攔截器清掉 token 後沒有任何導頁（`RequireAuth` 只在換頁時重新判斷 token），帳務頁顯示「$ 0」容易被誤讀成沒有收入；React Query 預設重試 3 次，每次 401 都再進攔截器、拿已是 null 的 refreshToken 送 `/auth/refresh`（`refreshCalls: 4`）。修正：攔截器 refreshToken 為空時不送 refresh；refresh 失敗或沒有 refresh token 時清 token 並廣播 `auth:session-expired`，`RequireAuth` 收到後走同一條 `/login?redirect=` 導頁；`QueryClient` 預設 `retry` 對 401 不重試（SD-FRONTEND-SPEC §2.1）。迴歸：TS-000-51 對照組改斷言導向登入頁且 refresh 只 1 次、新增 TS-000-52（refresh 0 次），舊版兩支皆紅。
- **（E2E 本身的問題，非產品 bug）`account-deactivation.spec.ts` 一直是未登入狀態在跑（2026-10-24，驗收 N16 修正後現形）**：這支用假 token `fake` 進站，AppHeader 的通知 / KYC API 打到後端 401 → refresh 401 → token 被清，但過去頁面停在原地、註銷 API 又是 mock，所以照樣綠。N16 起 session 失效會導去 `/login`，兩支即紅。改接 `mockSessionApis()`（header API mock + 漏 mock 偵測）並補 `GET /api/auth/webauthn/credentials` mock，`test.afterEach` 斷言沒有漏 mock；步驟改用 `step()` 留截圖。同類：`google-login.spec.ts`、`auth-register-and-password-reset.spec.ts` 登入成功回假 token 後斷言 `/demo`，正式 build（vite preview）上 refresh 失敗導回 `/login?redirect=%2Fdemo` 比斷言更快而紅（google-login 首案）；一併接上 `mockSessionApis()`，並把「停在 `/demo`、看得到『當前角色:』」放進 `step()`。
- **（E2E 本身的問題，非產品 bug）TS-000-49 在 CI 正式 build 紅、本機 dev 綠（2026-10-24，.e2e-runs/20261024-1939 F1）**：`login-double-submit.spec.ts` 攔 `/register` document 回 204 把「成功後、換頁前」凍結住，但正式 build 會註冊 Workbox SW，導覽由 SW 的 NavigationRoute 從 precache 直接回 `index.html`，`page.route` 攔不到，頁面真的換到 /register、導覽計數一直是 0。修法：`playwright.config.ts` 全域 `serviceWorkers: 'block'`（E2E 不驗 SW/PWA）；步驟 3 先斷言 URL 仍是 `/login?redirect=` 再斷言按鈕；Google 登入那支補上「成功後換頁前 Google 按鈕、帳密按鈕仍停用」斷言（與帳密對稱）。產品邏輯（`LoginPage.runLogin` 導頁時不解除 busy）本來就正確。以 `vite preview` 正式 build 驗證：允許 SW 時兩支都紅（畫面為「建立新帳號」），block 後兩支綠。
