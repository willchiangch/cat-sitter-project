# PRD-000: 帳號註冊與登入認證

| 項目 | 內容 |
|------|------|
| 負責 SA | AI (Antigravity) |
| 建立日期 | 2026-05-09 |
| 狀態 | Draft |
| 影響模組 | frontend / backend |
| 影響實體 (Entities) | 帳號 (Account)、登入紀錄 (Login Log) |
| 相依共用設計 | JWT 授權機制、Google OAuth 服務、WebAuthn (生物辨識) |
| 對應 SD | SD-000 |
| 對應測試情境 | TS-000 |
| 引用 NFR | `docs/sa/nfr/03-SECURITY.md`（NFR-003，Approved 2026-11-22）SEC-AUTH-1 ~ AUTH-11、SEC-AZ-4、SEC-INPUT-7、SEC-INT-2.2、CQ-2 ~ CQ-4、CQ-6、CQ-7、CQ-11、CQ-12、CQ-39 ~ CQ-41、CQ-44 ~ CQ-46、CQ-51；跨模組碼 429 `RATE_LIMITED` 見 baseline §3.7 |

---

## 背景與目的

為了保護使用者資料安全並提供個人化服務，系統需要一套完整的身分驗證機制。本模組負責使用者從進入系統、註冊、登入到獲取授權憑證 (Token) 的完整流程，並支援現代化的生物辨識與第三方社交登入，以提升使用者體驗與安全性。

## 使用者故事

- 身為 **訪客**，我希望能透過 Email 註冊帳號，以便開始使用保母或飼主服務。
- 身為 **使用者**，我希望能透過帳號密碼登入，並在行動裝置上使用生物辨識 (如 FaceID/指紋) 快速進入系統。
- 身為 **使用者**，我希望能直接透過 Google 帳號登入，省去記憶密碼的麻煩。
-身為 **使用者**，當我不再需要服務時，我希望能註銷帳號，並確保我的個人資料不再對外公開。
- 身為 **使用者**，當我忘記密碼時，希望能透過 Email 重設密碼以找回帳號。

---

## 功能規格

### 主流程

**A. 註冊流程 (Registration)**
1. 使用者輸入 Email 並設定密碼，或選擇第三方登入 (Google)。密碼須符合「密碼規則」（業務規則）；Email 網域為 `@e2e-journey.test` → 400 `VALIDATION_FAILED`（`field=email`），不寄 OTP（NFR-003 CQ-51）。
2. **條款同意確認**：系統彈出「服務條款與隱私權政策」視窗，使用者必須勾選「我已閱讀並同意」方可繼續。
3. 系統發送 6 位數 **Email 驗證碼 (OTP)** 至該 Email（參數見「OTP 規則」）。寄送前檢查「寄信上限」，超過 → 429 `RATE_LIMITED`，不寄信。
4. 使用者在系統輸入正確驗證碼後完成驗證，系統建立帳號實體，預設狀態為 `ACTIVE`。

**B. 傳統登入與憑證發放**
1. 使用者輸入帳號密碼。
2. 驗證通過後，系統發放具備時效性的「授權憑證 (Token)」：access token 與 refresh token（時效、輪替與上限見「憑證與 session 規則」）。
3. 憑證內需包含基本的帳號識別資訊，但不含敏感個資（payload 只含 `userId`、`currentRole`、`iat`、`exp`，NFR-003 SEC-AUTH-2）。
4. 帳號不存在、密碼錯誤、以密碼登入 ADMIN 帳號，三者回應完全相同：401、同一錯誤碼（由 SD-IDENTITY 定）、文案「帳號或密碼錯誤」，且同樣計入「登入鎖定」（NFR-003 SEC-AUTH-5、CQ-7、CQ-44）。

**C. 第三方登入 (Google OAuth)**
1. 使用者點擊「使用 Google 登入」。
2. 系統導向 Google 授權頁面。
3. 後端驗證 Google ID token：簽章、`iss` 為 `accounts.google.com` 或 `https://accounts.google.com`、`aud` 等於設定的 client id、`exp` 未過期；任一不符 → 401。`email_verified` 不是 `true` → 401，不建立帳號、不綁定既有帳號（NFR-003 SEC-AUTH-10）。Email 網域為 `@e2e-journey.test` → 400 `VALIDATION_FAILED`（`field=email`），不建立、不綁定（CQ-51）。
4. 驗證通過後，系統自動識別該 Email 是否已存在：
   - 若不存在：自動建立新帳號並登入。
   - 若已存在：直接登入並與 Google 身分綁定。

**D. 生物辨識登入 (WebAuthn)**
1. 使用者登入後，可於設定頁面選擇「開啟生物辨識」。
2. 系統與裝置進行金鑰交換與綁定。
3. 下次登入時，可直接呼叫裝置原生的生物辨識介面完成驗證，無需輸入密碼。

**E. 帳號註銷流程 (Account Deletion)**
1. 使用者於安全設定頁點擊「註銷帳號」。
2. **前置檢查**：系統驗證該帳號是否具備註銷資格（例如：無進行中訂單、無未結清之款項）。
3. **確認與警示**：系統告知註銷後之影響（公開網頁失效、無法再登入、資料保留於後端供查核）。
4. **驗證與執行**：使用者需輸入密碼或通過 OTP 驗證進行最終確認。
5. **執行註銷**：系統將帳號狀態改為 `DELETED`。給予使用者端「永久刪除」的感受，但後端資料採**軟刪除 (Soft Delete)** 處理。

**F. 忘記密碼與重設（2026-11-22 依 NFR-003 補）**
1. 使用者於忘記密碼頁輸入 Email。
2. 系統一律回應「若此 Email 已註冊，將收到重設信」：Email 已註冊 → 寄重設信；未註冊 → 不寄信；兩者回應相同（NFR-003 CQ-7）。兩種情況都計入「寄信上限」，超過 → 429 `RATE_LIMITED`。
3. 重設信連結以設定值 `app.frontend-base-url` 組成，不取自請求的 `Host`（NFR-003 SEC-WEB-5.3）；連結內含重設憑證（見「重設憑證規則」）。
4. 使用者以連結設定新密碼（須符合「密碼規則」）。成功後撤銷該帳號全部 session（所有 refresh token 失效）。

**G. ADMIN 帳號（2026-11-22 依 NFR-003 補）**
1. ADMIN 是**獨立帳號**：`password_hash` 為 NULL、沒有 OWNER / SITTER profile，不由既有帳號升級；不能經切換角色取得（切換目標為 ADMIN → 403 `ROLE_MISMATCH`，NFR-003 CQ-46，屬 PRD-001 切換角色端點）。
2. 不提供開通 API。開通由使用者執行：在本機產生一次性註冊憑證明文（≥ 128 bit，例 `openssl rand -base64 32`），自行計算 SHA-256，只把雜湊以 SQL 寫入（15 分鐘、單次有效）；DB 變更需使用者當次同意；明文不經對話、log、Resend；註冊連結由使用者自行交付給 ADMIN（NFR-003 CQ-11）。
3. ADMIN 以連結進入後立即註冊 WebAuthn；之後只能以 WebAuthn 登入，建議每位 ADMIN 註冊 2 個裝置（CQ-12）。
4. 裝置全部遺失：由使用者手動 SQL 撤銷舊 credential，並依步驟 2 同樣方式重發註冊連結（CQ-12）。
5. 開通與 WebAuthn 註冊皆寫帳號安全稽核（NFR-003 SEC-AUDIT-1、SEC-AUDIT-2）。

### 例外處理

| 情境 | 預期行為 |
|------|---------|
| 登入失敗次數過多 | 以正規化後的 Email 計數（不另做 IP 鎖定，登入端點計入全域匿名 IP 限流，NFR-003 CQ-6）：**連續失敗 5 次**後，第 6 次起（含正確密碼）回 429 `ACCOUNT_LOCKED`，提示「嘗試次數過多，請於 10 分鐘後再試」；自第 5 次失敗起算滿 10 分鐘解除，鎖定期間的嘗試不延長鎖定。不存在的 Email 同樣計數並鎖定（NFR-003 SEC-AUTH-5、CQ-45）。 |
| Email 已被註冊 | 提示「此 Email 已被使用」，並提供登入或找回密碼的連結（會透露帳號存在，MVP 接受，NFR-003 CQ-7）。 |
| 授權憑證 (Token) 逾期 | 前端自動攔截 API 請求並導向登入頁面。 |
| 第三方登入授權取消 | 回到登入頁面，不進行任何帳號變動。 |
| 密碼不符密碼規則（含 UTF-8 超過 72 bytes） | 400 `VALIDATION_FAILED`（`field=password`，payload `minLength=8`、`maxLength=64`、`maxBytes=72`）；不得回 500，回應不含例外訊息。 |
| 註冊或 Google 登入使用 `@e2e-journey.test` 網域 | 400 `VALIDATION_FAILED`（`field=email`），不建立帳號、不綁定、不寄信。 |
| Google ID token 的 `iss`、`aud`、`exp` 或簽章不符，或 `email_verified` 不是 `true` | 401，不建立帳號、不綁定既有帳號。 |
| OTP 逾時（> 10 分鐘）、已達 5 次驗證、已被重送取代 | 拒絕（碼由 SD-IDENTITY 定），帳號不建立。 |
| 重送 OTP 距上次未滿 60 秒 | 拒絕（碼由 SD-IDENTITY 定），不寄信。 |
| 重設憑證過期、已使用、不存在、已被新申請取代 | 同一錯誤碼與同一文案（碼由 SD-IDENTITY 定），密碼不變。 |
| 寄信類匿名端點超過寄送上限 | 429 `RATE_LIMITED` + `Retry-After`（秒），不寄信。 |
| 以密碼登入 ADMIN 帳號 | 與「帳號或密碼錯誤」完全相同的 401，計入登入鎖定。 |
| Resend 寄送失敗 | 不影響 API 回應；Email 由排程重試（baseline §3.6）。 |

### 資料規則 / 業務規則

- **密碼安全**：密碼必須進行強雜湊 (Salted Hash) 儲存，嚴禁明文儲存（BCrypt cost ≥ 10 或 Argon2id，NFR-003 SEC-AUTH-1、CQ-1）。
- **密碼規則**（註冊、改密碼、重設密碼共用）：8–64 字元（以 Unicode code point 計）且 UTF-8 ≤ 72 bytes；不強制字元類型；MVP 不檢查外洩密碼清單。不符 → 400 `VALIDATION_FAILED`（`field=password`，payload `minLength=8`、`maxLength=64`、`maxBytes=72`）。前端以同一規則即時提示，並說明位元組限制（全中文密碼上限約 24 字）（NFR-003 SEC-AUTH-11、CQ-39）。
- **Email 正規化**：去頭尾空白、轉小寫；登入鎖定計數與 Email 唯一性約束共用同一個正規化函式，大小寫不同的 Email 不得成為兩個帳號（NFR-003 SEC-AUTH-5.1）。
- **登入鎖定**：計數鍵 = 正規化後的 Email；不存在的 Email 也計數並鎖定；連續 5 次失敗後第 6 次起回 429 `ACCOUNT_LOCKED`（SD-IDENTITY 模組碼）；自第 5 次失敗起算滿 10 分鐘解除並歸零；鎖定期間嘗試不延長；成功登入歸零；計數列在最後一次失敗滿 10 分鐘且不在鎖定中時視為過期，由排程清除；同鍵併發失敗仍須正確鎖定；查無帳號時也對固定假雜湊做一次 BCrypt 比對，「帳號不存在」與「密碼錯誤」的回應時間 P50 差 < 50 ms；不另做 IP 維度鎖定（NFR-003 SEC-AUTH-5、CQ-6、CQ-7、CQ-45）。
- **OTP 規則**：`SecureRandom` 產生 6 位數；10 分鐘失效；每顆最多驗證 5 次，第 6 次起即使正確也拒絕；同一 Email 重送冷卻 60 秒，重送後舊 OTP 失效；只存雜湊（NFR-003 SEC-AUTH-8、SEC-AUTH-9、CQ-40）。
- **重設憑證規則**：≥ 128 bit 隨機；30 分鐘失效；使用一次即失效；重新申請使舊憑證失效；只存 SHA-256 雜湊；過期、已使用、不存在回同一錯誤碼與文案；重設成功撤銷全部 session（NFR-003 SEC-AUTH-8、SEC-AUTH-9、CQ-40）。
- **寄信上限**：註冊 OTP、重送 OTP、忘記密碼三個端點合計，同一 Email 每小時最多 5 封、同一來源 IP 每小時最多 20 封；超過 → 429 `RATE_LIMITED` + `Retry-After`，不寄信；忘記密碼對不存在的 Email 也計數、回相同回應（NFR-003 SEC-INPUT-7、CQ-41）。
- **測試網域**：一般註冊與 Google 建立 / 綁定遇到 `@e2e-journey.test` 網域一律 400 `VALIDATION_FAILED`；這類帳號只能由 `/api/internal/test-data/**` 建立（NFR-003 SEC-INT-2.2、CQ-51）。
- **憑證與 session 規則**：access token 有效 15 分鐘（NFR-003 CQ-4）；單顆 refresh token 有效 7 天，每次換發輪替、新 token 重新起算；同一 token family 自首次登入起 30 天為絕對上限，期滿必須重新登入，不受輪替影響（CQ-2）。舊 token 輪替後標記 `ROTATED`；30 秒寬限內再被使用 → 換發另一顆新 token（同一 family），同一顆舊 token 寬限內最多換發 3 次（含第一次）；超過寬限或超過次數再使用 → 401，撤銷整個 family 並記 WARN；同一父 token 衍生的兄弟 token，其中一顆被用來換發時其餘標記 `SUPERSEDED`（CQ-3）。refresh token 只存雜湊（SEC-AUTH-9）。
- **帳號軟刪除規則**：執行註銷後，帳號狀態標記為 `DELETED`，使用者將無法登入且公開檔案回傳 404。然而，相關的訂單紀錄、財務明細與法律必要資訊將在資料庫中以「軟刪除」形式保留，以維護財務審計與歷史紀錄之完整性。
- **身分唯一性**：一個 Email 僅能對應一個系統帳號（以正規化後的 Email 判斷）。已註銷帳號之 Email 於一定期間內（例如 30 天冷卻期）不可重新註冊。
- **單一 session（2026-10 決策）**：現階段以單裝置情境為準，同一帳號同時只維持一組登入狀態，**後登入者覆蓋先登入者**（先登入的裝置下次換發憑證時被要求重新登入）；同時登入不得出現錯誤，兩邊都應成功。被覆蓋的 token 標記 `SUPERSEDED`，再使用 → 一般 401，不視為重用、不撤銷新裝置那組（NFR-003 SEC-AUTH-3.4）。仍提供「登出所有裝置」功能（撤銷目前這組登入狀態，舊 refresh token 標記 `LOGOUT`，再使用 → 一般 401）。改密碼、重設密碼、停用帳號同樣撤銷 session。多裝置同時在線列為未來需求。
- **敏感操作驗證**：修改密碼或綁定生物辨識時，需重新驗證當前登入狀態。
- **ADMIN 帳號**：獨立無密碼帳號，只能以 WebAuthn 登入；開通、遺失裝置處理見主流程 G（NFR-003 SEC-AZ-4.4、CQ-11、CQ-12、CQ-44）。
- 以上規則不依 SaaS 方案等級區分（FREE / BASIC / PRO / ULTIMATE 相同，NFR-003 開頭說明）。

---

## 驗收標準（AC）

- [ ] **AC-1 (註冊流程與 OTP)**：情境 1：輸入合法的 Email 與密碼後，系統寄出 6 位數 OTP，輸入正確 OTP 後帳號建立且狀態 `ACTIVE`。情境 2：OTP 寄出滿 10 分鐘後輸入正確 OTP → 拒絕、帳號不建立。情境 3：同一顆 OTP 輸入錯誤 5 次後，第 6 次輸入正確 OTP → 拒絕。情境 4：寄出後 60 秒內重送 → 拒絕且 Resend Mock 未收到新信；滿 60 秒重送 → 收到新 OTP，舊 OTP 再輸入 → 拒絕。情境 5：以 `qa@e2e-journey.test` 註冊 → 400 `VALIDATION_FAILED`（`field=email`），Resend Mock 未收到信。
- [ ] **AC-2 (註冊前置條件與密碼規則)**：情境 1：使用者必須勾選同意服務條款，註冊按鈕才可點擊或表單才可送出。情境 2：密碼 7 字元或 65 字元 → 400 `VALIDATION_FAILED`（`field=password`，payload `minLength=8`、`maxLength=64`、`maxBytes=72`）；8 字元與 64 個 ASCII 字元 → 通過。情境 3：25 個中文字（75 bytes）→ 400 `VALIDATION_FAILED`；24 個中文字（72 bytes）→ 通過；送 73 bytes 的密碼不得回 500，回應 body 不含例外訊息。情境 4：改密碼與重設密碼套用相同規則與相同錯誤回應。
- [ ] **AC-3 (憑證時效與輪替)**：情境 1：登入後 access token 的 `exp − iat ≤ 900` 秒，payload 不含 `planType`、email。情境 2：以有效 refresh token 換發 → 200，回傳新 refresh token；30 秒內以同一顆舊 token 再換發 → 成功（同一 family），同一顆舊 token 第 4 次使用 → 401，且該 family 所有 token 再使用皆 401。情境 3：同一 family 首次登入滿 30 天後換發 → 401（即使最後一次輪替未滿 7 天）。情境 4：裝置 A 登入後裝置 B 登入，A 以舊 refresh token 換發 → 401，B 的 token 仍可換發（不撤銷）。情境 5：「登出所有裝置」後舊 refresh token → 401。
- [ ] **AC-4 (帳號鎖定)**：情境 1：同一 Email 連續 5 次密碼錯誤，第 6 次以正確密碼登入 → 429 `ACCOUNT_LOCKED`，畫面「嘗試次數過多，請於 10 分鐘後再試」。情境 2：以 `User@Example.com ` 與 `user@example.com` 交錯失敗合計 5 次 → 視為同一鍵被鎖定。情境 3：不存在的 Email 連續失敗 5 次 → 第 6 次回 429 `ACCOUNT_LOCKED`。情境 4：第 5 次失敗後第 9 分鐘再嘗試仍 429，第 10 分鐘（自第 5 次失敗起算）後以正確密碼 → 200（鎖定未因第 9 分鐘的嘗試延長）。情境 5：失敗 4 次後成功登入，再失敗 4 次 → 仍可登入（計數已歸零）。情境 6：同鍵 10 個錯誤請求併發送出 → 鎖定成立。情境 7：以 50 次取樣比較「帳號不存在」與「密碼錯誤」，兩者錯誤碼與文案相同，回應時間 P50 差 < 50 ms。
- [ ] **AC-5 (Google 登入)**：情境 1：首次使用 Google 登入（`email_verified=true`）→ 自動建立對應的帳號與 Profile。情境 2：`aud` 為其他 client id 的 ID token → 401，不建立帳號。情境 3：`email_verified=false` 的 ID token → 401，不建立帳號、不綁定既有同 Email 帳號。情境 4：Google Email 為 `@e2e-journey.test` → 400 `VALIDATION_FAILED`，不建立、不綁定。
- [ ] **AC-6 (生物辨識與 ADMIN 帳號)**：情境 1：在支援的裝置上能成功啟動生物辨識綁定，且登出後能透過生物辨識再次登入。情境 2：ADMIN 帳號的 `password_hash` 為 NULL、沒有 OWNER / SITTER profile；以 ADMIN 的 Email 加任意密碼登入 → 401，錯誤碼與文案與「帳號或密碼錯誤」完全相同，且計入登入鎖定（連續 5 次後第 6 次 429 `ACCOUNT_LOCKED`）。情境 3：ADMIN 以已註冊的 WebAuthn 登入 → 成功，token `currentRole=ADMIN`。
- [ ] **AC-7 (密碼找回與寄信上限)**：情境 1：申請重設後 30 分鐘內以連結設定新密碼 → 成功，該帳號原有 refresh token 再使用 → 401；同一連結再使用 → 拒絕。情境 2：重新申請後，舊連結 → 拒絕；過期（滿 30 分鐘）、已使用、不存在的憑證三者錯誤碼與文案相同。情境 3：以未註冊 Email 申請 → 回應與已註冊相同「若此 Email 已註冊，將收到重設信」，Resend Mock 未收到信。情境 4：同一 Email 1 小時內第 6 次申請（含註冊 OTP、重送 OTP 合計）→ 429 `RATE_LIMITED` 且帶 `Retry-After`，Resend Mock 只收到 5 封。情境 5：資料庫的重設憑證欄位值不等於信件中的明文，且不含明文子字串。
- [ ] **AC-8 (帳號註銷驗證)**：當使用者帳號狀態為 `DELETED` 時，所有登入嘗試應被拒絕，且其在 `PRD-018` 的公開頁面應回傳 404。

---

## 影響範圍

| 面向 | 說明 |
|------|------|
| 資料模型變更 | 是。需建立「帳號」與「授權金鑰」相關實體；refresh token 需 token family、撤銷原因（`ROTATED` / `SUPERSEDED` / `LOGOUT`）、只存雜湊；登入失敗計數；重設憑證只存雜湊；ADMIN 註冊憑證雜湊。 |
| API 新增/修改 | 是。新增註冊、登入、Google 授權回傳、密碼重設、生物辨識綁定等 API。 |
| 前端頁面 | 是。開發登入頁、註冊頁、忘記密碼頁、個人安全設定頁；密碼規則即時提示。 |
| 通知/郵件 | 是。需發送註冊驗證信與重設密碼信；受寄信上限限制。 |
| 排程 | 是。清除過期的登入失敗計數列。 |
| 現況差異（2026-11-22 程式碼，NFR-003 §11，只作證據） | refresh token 與重設憑證以明文存 DB（G-7）；refresh 換發不輪替、7 天寫死、無絕對上限（G-10）；登出所有裝置只刪 refresh token（G-11）；BCrypt 超過 72 bytes 的例外訊息原樣回前端（G-14）；登入鎖定只對存在的帳號計數（G-26）；寄信端點只有每 Email 60 秒冷卻（G-4）；Google 登入已檢查 `email_verified`、OTP 已雜湊存放（已符合） |

---

## 相依功能

- **PRD-001 (角色切換與檔案管理)**：登入後需讀取對應的 Profile；切換角色到 ADMIN → 403 `ROLE_MISMATCH`（NFR-003 CQ-46）。
- **PRD-005 (飼主預約申請)**：預約前需強制完成此認證流程。
- **NFR-003 (安全性)**：本卡安全規則的來源（§15 U-9）。

---

## 備註 / 設計限制

- **生物辨識相容性**：受限於瀏覽器與作業系統環境 (WebAuthn API)，若裝置不支援則自動隱藏該功能。
- **Session 管理**：access token 無狀態（不做每請求查庫的黑名單，NFR-003 CQ-4）；refresh token 由伺服器記錄雜湊以支援輪替與撤銷。

---

## 待釐清事項

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-1 | 寄信上限 429 `RATE_LIMITED` 的畫面文案（NFR-003 只定碼與上限） | 「寄送次數過多，請於 {Retry-After 換算的分鐘數，無條件進位} 分鐘後再試」 |
| CQ-2 | 登入鎖定 429 `ACCOUNT_LOCKED` 是否帶 `Retry-After`（NFR-003 只定碼與文案） | 帶，值為剩餘鎖定秒數（自第 5 次失敗起算 600 秒減已過秒數，無條件進位）；畫面文案仍固定「請於 10 分鐘後再試」 |

---

## 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 依 NFR-003 回寫：表頭新增「引用 NFR」；主流程 A 補密碼規則、`@e2e-journey.test` 禁止註冊、寄信上限；B 補帳號不存在 / 密碼錯誤 / 密碼登入 ADMIN 同碼同文案；C 補 Google ID token `iss` / `aud` / `exp` / `email_verified` 驗證與測試網域；新增 F 忘記密碼與重設、G ADMIN 獨立帳號與開通；例外表改寫登入鎖定（Email 維度、不存在 Email 也鎖、429 `ACCOUNT_LOCKED`、不延長）並補 9 列；業務規則新增密碼規則、Email 正規化、登入鎖定（含假雜湊）、OTP、重設憑證、寄信上限、測試網域、憑證與 session（refresh family 30 天絕對上限、輪替寬限）、ADMIN 帳號；AC-1 ~ AC-7 改寫為可觀察情境（AC 仍 8 條）；新增待釐清事項 CQ-1、CQ-2；影響範圍補資料、排程、現況差異。狀態維持 Draft | NFR-003 核准回寫（§15 U-9），使用者 2026-11-22 同意 |
