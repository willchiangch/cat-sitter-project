# NFR-003: 安全性 (Security) 規格書

| 項目 | 內容 |
|------|------|
| 對應需求 | 全系統；重點 PRD-000、001（帳號身分）、005、007、016、027（訂單 / 付款 / 排程）、008、022（履約 / 日誌媒體）、017、020（KYC / ADMIN）、021（照護媒體）、025（收款帳號）。PRD-015 線上金流不在本版範圍，SD-015 撰寫前另補 SEC-PAY 節（§10.4、CQ-42） |
| 負責人 | Will |
| 狀態 | Approved |
| 優先級 | P0 (Critical) |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1–§1.2（角色、資源歸屬、403 / 404、CQ-81 未送出日誌 404）、§2.2（方案即時查詢）、§3.3（收款帳號快照加密 CQ-72）、§3.6（同交易稽核、外部 I/O、冪等、排程）、§3.7（錯誤碼與判斷順序）、§7 CQ-75（收款帳號修改不重驗）；`docs/sd/SD-GLOBAL-SPEC.md` §1.2、§2.4、§2.6、§2.7、§3.2、§3.4、§4.3、§6.2、§6.3；`docs/sd/SD-ORDER.md` §3.6.6、§6.3、§8.3 D1–D4、D7、§11 抽樣缺陷 #1（`payee_account_key_version`）；`docs/sd/SD-FULFILL.md` §3.3、§4.4、技術決策 3、DF2、DF7、§8 設定表 |
| 更新日期 | 2026-11-22 |

> 本文件把安全要求寫成可驗收的條件。每條要求有編號（`SEC-{類別}-n`），子項以 `[k]` 標示，引用時寫 `SEC-{類別}-n.k`（例 `SEC-AUTH-3.4`）。SD 須在「AC 對應實作」或安全章節逐項引用。
> 沒有需求出處的數值一律標「依 CQ-n」，以 §13 待釐清事項的建議預設值暫代，使用者決定前不得當成定案（專案硬性規則 3）。審查第 1 輪新增的補強項是否全數納入本版範圍，見 CQ-52。
> 與 Approved 文件衝突時以 Approved 文件為準；本文件認為上游該改的，列在 §15「需回寫上游的項目」，不在本文件另訂衝突規則。
> §11「現況落差」以 2026-11-22 程式碼為證據，只說明落差，不代表正確答案。
> 本文件的所有限制**不依 SaaS 方案等級區分**：FREE / BASIC / PRO / ULTIMATE 完全相同（方案判斷本身的安全要求見 SEC-AZ-5）。

---

## 0. 讀法

### 0.1 欄位

| 欄位 | 說明 |
|------|------|
| 類型 | `保留`：舊稿 NFR-003（2026-10 前）與 `00-NFR-OVERVIEW.md` 已有、使用者 2026-11-22 指示保留並改寫為可驗收；`補強`：使用者 2026-11-22 同意補強的缺口；`審查補`：審查第 1 輪提出、範圍待 CQ-52 確認 |
| 出處 | 需求文件、baseline、Approved PRD / SD、使用者決定；數值無出處者標 CQ |
| 落地 | `GLOBAL`（SD-GLOBAL-SPEC）、`FRONT`（SD-FRONTEND-SPEC）、`IDENTITY`（SD-IDENTITY，尚未撰寫）、`ORDER`、`FULFILL`、`TRUST`（SD-TRUST，KYC）、`SUPPLY`（SD-SUPPLY，收款帳號、保母頭像）、`CARE`（SD-CARE，照護媒體、毛孩頭像）、`NOTIFY`（SD-NOTIFY）、`DEPLOY`（部署清單：`deploy.yml`、`firebase.json`、GCP / Supabase 設定，雲端變更需使用者當次同意） |
| 驗證 | `IT`：後端整合測試（真 `SecurityConfig` filter chain、真 JWT，不用 `@WithMockUser`）；`AU`：ArchUnit 或靜態掃描測試；`E2E`：Playwright；`CI`：GitHub Actions 檢查；`MAN`：上線前手動檢查（附指令或觀察點） |

### 0.2 時間與單位

- 時間一律 UTC 儲存與比較；畫面顯示 Asia/Taipei（baseline §5）。本文件的「分鐘 / 秒」皆為伺服器時鐘的經過時間。
- 大小以位元組計：1 MB = 1,048,576 bytes。
- 「推測」標記的技術說法須由落地 SD 實測後確認。

---

## 1. 身分認證與 session（SEC-AUTH）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-AUTH-1 | 保留 | 密碼只存單向加鹽雜湊 | [1] `users.password_hash` 符合 BCrypt 格式 `^\$2[aby]\$[0-9]{2}\$` 且 cost ≥ 10（CQ-1），或 Argon2id 格式；[2] 任何 API 回應不含 `password`、`passwordHash` 欄位；[3] 同一密碼註冊兩個帳號，雜湊值不同 | PRD-000「密碼安全」；00-NFR-OVERVIEW；舊稿 §1 | IDENTITY | IT |
| SEC-AUTH-2 | 保留 | access token 有時效、演算法固定、內容最小 | [1] `exp − iat ≤ 86400` 秒（實際值依 CQ-4）；[2] 以下 token → 401 `UNAUTHENTICATED`：已過期、簽章被竄改、header `alg=none`、`alg=HS512`、`alg=RS256`、帶指向外部的 `kid` / `jku`；parser 固定只接受 HS256；[3] payload 只含 `userId`（GLOBAL §3.2 稱 accountId）、`currentRole`、`iat`、`exp`，refresh 若也用 JWT 另加 `typ` 區分；不含 `planType`、email 或其他個資；[4] 簽章金鑰 ≥ 256 bit，只經 Secret Manager 注入（SEC-SECRET-1） | 舊稿 §1；00-NFR-OVERVIEW；PRD-000 AC-3；GLOBAL §3.2；baseline §3.7 | IDENTITY | IT |
| SEC-AUTH-3 | 保留 | refresh 換發機制，可輪替、可撤銷、有絕對上限 | [1] 以有效 refresh token 換發 → 200 取得新 access token；[2] 每次換發輪替 refresh token，舊 token 標記撤銷原因 `ROTATED`；寬限期與重用處理依 CQ-3；[3] 有效期與 session 絕對上限依 CQ-2，逾期 → 401；[4] 單一 session：同帳號重新登入或切換角色後，前一組 token 標記 `SUPERSEDED`，再使用 → 401，**不視為重用**、不撤銷新裝置那組（PRD-000）；[5] 「登出所有裝置」、改密碼、重設密碼、停用帳號後，舊 refresh token（`LOGOUT`）→ 401，舊 access token 最遲在 CQ-4 的時限後 → 401 | 舊稿 §1；00-NFR-OVERVIEW；PRD-000「單一 session」 | IDENTITY | IT |
| SEC-AUTH-4 | 補強 | token 不可被頁面 JavaScript 讀到（目標）；過渡期以 CSP 降低 XSS 竊取風險 | 依 CQ-5：[1] 登入、換發回應的 body 不含 refresh token，改以 `Set-Cookie` 下發（屬性見 CQ-5）；[2] E2E 登入後 `localStorage`、`sessionStorage` 皆無 `refreshToken` 鍵，`document.cookie` 讀不到 refresh token；[3] access token 只放記憶體，重新整理頁面以 refresh cookie 換發；[4] refresh cookie 只在 refresh、logout 端點被讀取；其他端點一律不把 cookie 當身分（只認 `Authorization` 標頭），IT：只帶 cookie 打任一需登入的端點 → 401（匿名白名單端點不在此列）；[5] logout 回應以 `Max-Age=0` 清除 cookie；[6] refresh、logout 端點檢查 `Origin`：缺漏或不在 CORS 白名單（SEC-WEB-4）→ 403 `FORBIDDEN`；[7] 若上線時仍採過渡方案（token 存 localStorage），SEC-WEB-2 的 CSP 必須已強制生效（順序見 CQ-22） | 使用者 2026-11-22（補強範圍）；CQ-5、CQ-22 | IDENTITY、FRONT、DEPLOY | IT、E2E |
| SEC-AUTH-5 | 保留 | 暴力破解防護：連續登入失敗鎖定 | [1] 計數鍵 = 正規化後的 email；正規化規則（去頭尾空白、轉小寫）與 SD-IDENTITY 的 email 唯一性約束共用同一個函式，大小寫不同的 email 不得成為兩個帳號；**不存在的 email 也計數並鎖定**，不因帳號存在與否表現不同；計數列在最後一次失敗滿 10 分鐘且不在鎖定中時視為過期，查詢時忽略並由排程清除，避免灌入大量隨機 email 使計數表無限增長；[2] 同一鍵連續 5 次失敗後，第 6 次起（含正確密碼）回鎖定錯誤（碼依 CQ-45），畫面「嘗試次數過多，請於 10 分鐘後再試」；[3] 第 5 次失敗起算滿 10 分鐘後可登入，計數歸零；鎖定期間的嘗試**不延長**鎖定；[4] 成功登入使計數歸零；[5] 同鍵 10 個錯誤請求併發送出，鎖定仍成立；[6] 查無帳號時也對固定假雜湊做一次 BCrypt 比對；IT 以 50 次取樣比較「帳號不存在」與「密碼錯誤」的回應時間，P50 差 < 50 ms（CQ-7）；[7] 鎖定時寫 WARN log 與帳號安全稽核（SEC-AUDIT-1.4），不含輸入的密碼；[8] IP 維度依 CQ-6；註冊、忘記密碼的列舉處理依 CQ-7 | PRD-000 AC-4、例外表；00-NFR-OVERVIEW；舊稿 §1 | IDENTITY | IT |
| SEC-AUTH-6 | 保留 | 敏感操作再驗證 | [1] 改密碼須帶目前密碼；缺漏或錯誤 → 不變更、回 4xx（碼由 SD-IDENTITY 定），錯誤計入 SEC-AUTH-5 依 CQ-8；[2] 綁定 WebAuthn 前須再驗證，方式依 CQ-9；[3] **修改收款帳號不要求再驗證**（已決：使用者 2026-11-22 再確認；baseline CQ-75、PRD-025 BR-10），改以同交易 `enqueue` 不可關閉的「收款帳號已變更」通知偵測盜改，驗收沿用 PRD-025 AC-1.6；[4] 改密碼成功 → 依 SEC-AUTH-3.5 撤銷 session | PRD-000「敏感操作驗證」；舊稿 §3；baseline CQ-75；PRD-025 BR-8、BR-10；使用者 2026-11-22 | IDENTITY、SUPPLY | IT |
| SEC-AUTH-7 | 補強 | WebAuthn 防重放與來源檢查 | 依 CQ-10：[1] 每個 challenge 只能用一次，逾時即失效；同一 assertion 重送 → 401；[2] 只接受白名單 origin 與 RP ID（正式網域；local profile 另加 `localhost`），其他 origin 的 assertion → 401；[3] 裝置不支援時前端隱藏入口（NFR-005），不影響密碼登入 | PRD-000 D；使用者 2026-11-22（補強範圍）；CQ-10 | IDENTITY、FRONT | IT、E2E |
| SEC-AUTH-8 | 審查補 | 註冊 OTP 與密碼重設憑證有時效、單次、有嘗試上限 | 參數依 CQ-40：[1] OTP 為 `SecureRandom` 產生的 6 位數，10 分鐘失效，每顆最多驗證 5 次，第 6 次起即使正確也拒絕；[2] 同一 email 重送 OTP 冷卻 60 秒，重送後舊 OTP 失效；寄信總量另受 SEC-INPUT-7 限制；[3] 重設憑證 ≥ 128 bit 隨機，30 分鐘失效，使用一次即失效，重新申請使舊憑證失效；[4] 重設成功 → 依 SEC-AUTH-3.5 撤銷全部 session；[5] 過期、已使用、不存在的憑證回同一錯誤碼與文案 | PRD-000 A、AC-7（時效、單次）；CQ-40 | IDENTITY | IT |
| SEC-AUTH-9 | 審查補 | refresh token、重設憑證、OTP 只存雜湊 | [1] DB 只存 SHA-256（OTP 可用加鹽雜湊），以雜湊查詢；[2] IT：取得 refresh token / 重設憑證後，以 SQL 讀出的欄位值不等於回應或信件中的明文，且不含明文子字串；[3] log 不含明文（SEC-LOG-1） | PRD-000（憑證安全）；CQ-52 | IDENTITY | IT |
| SEC-AUTH-10 | 審查補 | Google 登入只接受經驗證的 email | [1] 驗證 ID token 簽章、`iss`（`accounts.google.com` 或 `https://accounts.google.com`）、`aud` = 設定的 client id、`exp`；任一不符 → 401；[2] `email_verified` 不是 `true` → 401，不建立帳號、不綁定既有帳號；[3] IT 以偽造 `aud`、`email_verified=false` 的 token 各測一次 | PRD-000 C、AC-5；CQ-52 | IDENTITY | IT |
| SEC-AUTH-11 | 審查補 | 密碼長度上下限，避免 BCrypt 72 bytes 截斷與例外外洩 | 依 CQ-39：[1] 註冊、改密碼、重設密碼皆要求 8–64 字元（code point）且 UTF-8 ≤ 72 bytes，不符 → 400 `VALIDATION_FAILED`（`field=password`，payload 帶 `minLength=8`、`maxLength=64`、`maxBytes=72`）；前端以同一規則即時提示（全中文密碼上限約 24 字，文案須說明位元組限制）；[2] 送 73 bytes 的密碼不得回 500，回應不含例外訊息（SEC-LOG-2） | PRD-000「密碼安全」；CQ-39 | IDENTITY | IT |

---

## 2. 授權與角色隔離（SEC-AZ）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-AZ-1 | 保留 | 呼叫者身分一律取自 token；越權存取依統一碼拒絕（防 BOLA） | 重建模組的每支需登入端點涵蓋 5 個案例：[1] 本人 → 2xx；[2] 他人資源 → 403 `FORBIDDEN`；[3] 錯角色 → 403 `ROLE_MISMATCH`；[4] 無 token → 401 `UNAUTHENTICATED`；[5] 資源不存在 → 404 `NOT_FOUND`。不適用者（例：列表端點無 [5]）在 SD 的 AC 對應表註明。多帶舊身分參數被忽略。判斷順序依 baseline §3.7 | 舊稿 §3；baseline §1.2、§3.7；GLOBAL §3.4 | GLOBAL、各模組 SD | IT |
| SEC-AZ-2 | 補強 | 每支端點都明確宣告存取規則，不得漏掛 | [1] AU 掃描所有 controller handler：不在 [3] 匿名白名單、不在 `/api/internal/**` 者必須掛 `@PreAuthorize`，且表達式不得為 `permitAll()`；[2] `SecurityConfig` 在 URL 層不得對整個 `/api/auth/**` 設 `permitAll()`，只放行 [3] 列出的匿名 auth 端點路由樣板；需要身分的 auth 端點（切換角色、停用、登出所有裝置、改密碼、綁定 WebAuthn）落入 `anyRequest().authenticated()`，匿名請求由 authentication entry point 回 401 `UNAUTHENTICATED`，不會走到方法層的 `@PreAuthorize`（否則會被 GLOBAL §2.6 對映成 403 `ROLE_MISMATCH`）；IT：匿名呼叫這幾支 → 401 `UNAUTHENTICATED`，不是 403；[3] 匿名白名單（路由樣板，變更須同步本表與 SD-GLOBAL-SPEC）：`OPTIONS /**`；`GET /api/sitters/*/plans`；`GET /api/sitters/*/questions`；`GET /api/sitters/*/booking-context`；`GET /api/sitter/profile/*`；匿名 auth 端點（登入、註冊、OTP、refresh、logout、忘記 / 重設密碼、Google、WebAuthn 登入，SD-IDENTITY 逐條列出路由樣板）；`GET /actuator/health`；`/error`；local profile 限定：`PUT /local-storage/**`；非 prod 限定：Swagger（SEC-LOG-3） | GLOBAL §3.4（漏掛 `@PreAuthorize` 以 0000 身分執行的事故）；使用者 2026-11-22 | GLOBAL、IDENTITY | AU、IT |
| SEC-AZ-3 | 保留 | 飼主與保母的私有資料互相隔離 | OWNER token 打保母私有端點（帳務總覽、收款帳號、報價、核對收款）→ 403 `ROLE_MISMATCH`；SITTER token 打飼主私有端點（付款憑證上傳、確認結案）→ 403 `ROLE_MISMATCH`；同帳號切換角色後以新 token 才能操作另一角色 | 舊稿 §3；baseline §1.1 | ORDER、SUPPLY、IDENTITY | IT |
| SEC-AZ-4 | 保留 | ADMIN 為獨立權限，不能經切換取得，範圍受限 | [1] 切換角色目標為 ADMIN → 拒絕（碼依 CQ-46）；[2] ADMIN 端點以 OWNER / SITTER token 呼叫 → 403 `ROLE_MISMATCH`；[3] ADMIN 讀取範圍外訂單（非 `DISPUTED` 且從未有 ADMIN 待辦）→ 403 `FORBIDDEN`；作廢（O16）時無 OPEN 首次付款待核對待辦 → 403 `FORBIDDEN`（baseline O16、PRD-007）；[4] ADMIN 為獨立帳號（無密碼、無 OWNER / SITTER profile），開通、第二因素與遺失裝置流程依 CQ-11、CQ-12；以密碼登入 ADMIN 帳號的回應依 CQ-44 | 舊稿 §3；baseline §1.1、§1.2、O16、CQ-8、CQ-84 | IDENTITY、ORDER、TRUST | IT |
| SEC-AZ-5 | 補強 | 方案權限即時判斷，不放進 token | [1] JWT 不含 `planType`；[2] 保母由 PRO 降為 BASIC 後，下一個請求呼叫 PRO 功能（例：報價調價）→ 403 `PLAN_REQUIRED`，不需重新登入；FREE / BASIC 呼叫 PRO / ULTIMATE 功能、FREE / BASIC / PRO 呼叫 ULTIMATE 專屬功能（白名單）一律 403 `PLAN_REQUIRED`；[3] 訂閱逾期或停權 → 新生意操作 403 `SUBSCRIPTION_LOCKED`（baseline §2.2） | GLOBAL §3.1、§3.2；baseline §2.1、§2.2 | GLOBAL、各模組 SD | IT |

---

## 3. 資料加密、Secret 與資料庫（SEC-DATA、SEC-SECRET）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-DATA-1 | 保留 | 敏感個資欄位以 AES-256 加密存放 | 適用：保母即時收款帳號（`profiles.bank_account_info`）、CHARGE payment 的收款帳號快照（`payments.payee_account_snapshot`）；日後若儲存身分證號比照。[1] SQL 直接讀取欄位值不含明文帳號（PRD-025 AC-1.5）；[2] AES-256-GCM，每次加密隨機 96-bit IV，同一明文加密兩次密文不同；[3] 竄改密文任一位元組 → 解密失敗 → 500 `INTERNAL_ERROR` 並記 ERROR log，不回傳部分欄位；[4] 是否以 AAD 綁定所在列依 CQ-48 | 舊稿 §2；00-NFR-OVERVIEW；baseline §3.3、CQ-72；PRD-025 BR-4；PRD-007 BR-2；SD-ORDER §8 外部服務表 | SUPPLY、ORDER | IT |
| SEC-DATA-2 | 保留 | 加密金鑰與業務資料隔離、無預設值、可辨識版本 | [1] `APP_CRYPTO_KEY` 為 32 bytes（base64）；[2] 受版控的 `application*.yml` 不含預設值，缺值時應用程式啟動失敗（啟動測試：不給金鑰 → ApplicationContext 失敗）；[3] prod 只經 `deploy.yml` 的 `--set-secrets` 自 Secret Manager 注入；[4] 金鑰不存在資料庫、不進 log；[5] 金鑰版本以獨立欄位記錄：快照沿用 SD-ORDER `payee_account_key_version`，即時帳號比照加欄位（CQ-13） | 舊稿 §2；baseline CQ-72；PRD-025 BR-4；SD-ORDER §8.3 D1、§11 #1 | ORDER、SUPPLY、DEPLOY | IT、MAN |
| SEC-DATA-3 | 審查補 | Supabase 資料庫不經 Data API 暴露，應用程式帳號最小權限 | 依 CQ-47：[1] 專案關閉 Data API（PostgREST），或 `public` schema 所有表啟用 RLS 且無 anon / authenticated 政策；Supabase Security Advisor 顯示 0 筆 critical（MAN）；[2] 以 anon key 呼叫 `https://{ref}.supabase.co/rest/v1/users` → 不回任何資料列（MAN）；[3] 應用程式執行期帳號不是 `postgres` 超級使用者、不是 service role，只有應用 schema 的 DML 權限；Flyway 用另一個 migration 帳號（推測：現行 prod 以 `postgres.{ref}` 連線，需確認權限） | CQ-47、CQ-52 | DEPLOY、GLOBAL | MAN |
| SEC-SECRET-1 | 補強 | 所有 secret 只經 Secret Manager 注入，不得有預設值、不得外洩 | 適用：`APP_SECURITY_JWT_SECRET`、`INTERNAL_CRON_SECRET`、`APP_CRYPTO_KEY`、`SPRING_DATASOURCE_PASSWORD`、`RESEND_API_KEY`。[1] CI 掃描 `backend/src/main/resources/application*.yml`：上列名稱以 `${NAME:` 形式帶預設值者 0 筆；測試值只放 `backend/src/test/resources`；[2] `deploy.yml` 中上列名稱只出現在 `--set-secrets`，不出現在 `--set-env-vars`，且五項齊全；[3] prod 缺任一值 → 啟動失敗（`RESEND_API_KEY` 依 CQ-14）；[4] 不出現在 API 回應、log（SEC-LOG-1）、commit；commit 掃描依 CQ-15 | 專案硬性規則 6（使用者規則）；GLOBAL §1.2；baseline CQ-72；SD-ORDER §8.3 D1、D2；使用者 2026-11-22 | GLOBAL、DEPLOY | CI、IT、MAN |

---

## 4. 檔案與儲存（SEC-FILE）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-FILE-1 | 保留 | 私有檔案一律為私有物件，公開物件與私有物件分開存放 | 私有：KYC 證件、付款 / 退款憑證、照護日誌媒體、PRD-021 照護媒體（`care_media/`）、毛孩頭像（依 CQ-17）。公開：只有保母頭像（依 CQ-17）。[1] 以未簽名網址 `https://storage.googleapis.com/{bucket}/{objectKey}` 匿名 GET 私有物件 → 401 或 403，且 body 不含物件內容；[2] 私有 bucket 啟用 uniform bucket-level access 與 public access prevention = enforced（`gcloud storage buckets describe` 可觀察；啟用順序依 CQ-17，搬遷完成前不得啟用）；[3] API 回應不含私有物件的未簽名網址（回應中私有物件的網址皆帶 `X-Goog-Signature`）；[4] 可讀者依 baseline §1.2：付款 / 退款憑證 = 該 payment 雙方與可讀範圍內 ADMIN；KYC = 本人與 ADMIN；照護日誌媒體 = 該訂單 sitter 可讀全部，owner 與可讀範圍內 ADMIN 只可讀 `SUBMITTED` 日誌的媒體、未送出 → 404 `NOT_FOUND`（baseline CQ-81），非當事人 → 403 `FORBIDDEN`；照護媒體依 PRD-021 協作規則（SD-CARE 定）；拒絕時不簽發網址 | 舊稿 §2；PRD-017「個資保護」；PRD-007 BR-17；PRD-022 BR-18；baseline §1.2、CQ-81；SD-FULFILL DF7 | ORDER、FULFILL、TRUST、CARE、SUPPLY、DEPLOY | IT、MAN |
| SEC-FILE-2 | 保留 | 檢視只用每次授權後產生的短效簽名網址 | [1] 付款 / 退款憑證：`X-Goog-Expires=300`（PRD-007 BR-17）；[2] 照護日誌媒體讀取：`X-Goog-Expires=900`（PRD-022 BR-18）；[3] 照護日誌上傳 PUT 網址：TTL = min(900 秒, 上傳槽位剩餘時間)，綁定 `Content-Type`、`x-goog-content-length-range`、`x-goog-if-generation-match: 0`（SD-FULFILL §3.3、決策 3）；[4] KYC 證件、私有化後的照護媒體與毛孩頭像：依 CQ-18；[5] 簽名網址不寫入 DB、不寫入冪等回放 body | 舊稿 §2；PRD-007 BR-17；PRD-022 BR-18；SD-ORDER §3.6.6；SD-FULFILL §3.3；baseline §3.6 | ORDER、FULFILL、TRUST、CARE | IT |
| SEC-FILE-3 | 補強 | 以檔案內容（magic bytes）判斷格式，物件的 `Content-Type` 等於判定格式 | [1] 格式白名單：付款 / 退款憑證 JPEG、PNG、WebP；日誌照片 JPEG、PNG、WebP；日誌影片 MP4、MOV；KYC JPEG、PNG（PRD-017）；頭像與照護媒體由 SD-SUPPLY / SD-CARE 定，但 SVG、HTML、PDF 一律不在任何白名單；[2] 內容為 HTML 但宣告 `Content-Type: image/jpeg`、副檔名 `.jpg` → 422 模組碼（憑證 `PROOF_FILE_TYPE_UNSUPPORTED`、日誌 `CARE_LOG_MEDIA_TYPE_UNSUPPORTED`、其他由各 SD 定），且不留下 GCS 物件；[3] 物件的 `Content-Type` 必須等於伺服器判定的格式：後端代傳的由伺服器設定，不沿用客戶端宣告與副檔名；瀏覽器直傳的由簽名綁定宣告值，確認時 magic bytes 必須等於宣告，不符就刪除物件並回 422（SD-FULFILL 決策 3），不改寫 metadata；[4] 物件鍵的副檔名由判定格式決定，不取自客戶端檔名；[5] 後端只讀檔頭與容器 metadata，不對圖片做完整解碼 | 使用者 2026-11-22（補強範圍）；SD-ORDER §3.6.6；SD-FULFILL §4.4、決策 3、`MediaInspector`；PRD-017 例外表 | ORDER、FULFILL、TRUST、CARE、SUPPLY | IT |
| SEC-FILE-4 | 補強 | 檔案大小上限在伺服器端強制 | [1] 付款 / 退款憑證 ≤ 5,242,880 bytes，超過 → 422 `PROOF_FILE_TOO_LARGE`；[2] 日誌照片 < 1,048,576 bytes、影片 < 52,428,800 bytes（PRD-022 BR-12、BR-13），直傳以 `x-goog-content-length-range` 強制，確認時以實際物件大小再驗；[3] KYC ≤ 5 MB（PRD-017），後端也要檢查；[4] 經 Cloud Run 的 multipart：單檔 20 MB、整個請求 21 MB，超過 → 400 `VALIDATION_FAILED`；[5] 影片 `moov` 緩衝 ≤ 8 MB，超過視為格式不支援 | PRD-007；PRD-022 BR-12、BR-13；PRD-017；SD-ORDER §8.3 D4；SD-FULFILL §8 | ORDER、FULFILL、TRUST、GLOBAL | IT |
| SEC-FILE-5 | 保留 | 前端只顯示後端簽名網址，不渲染使用者提供的網址 | [1] 憑證與媒體只以後端提供的網址放進 img / video 元素；使用者輸入的網址不做成可點擊連結、不放入 `src`；[2] 退款憑證由保母上傳圖片，不接受自填外部網址（舊稿 §2）；[3] 照片位置資訊（EXIF GPS）處理依 CQ-19 | 舊稿 §2（SD-016 §2.5）；使用者 2026-11-22 | FRONT、ORDER、FULFILL | E2E、IT |

---

## 5. 傳輸、資安標頭、CORS 與前端導頁（SEC-WEB）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-WEB-1 | 保留 | 全站強制 HTTPS | [1] `curl -sI http://{PROJECT_ID}.web.app/` 回 301、302、307 或 308 導向 `https://`；Cloud Run `*.run.app` 同（`.app` 網域在 HSTS preload 清單，瀏覽器端本來就只走 HTTPS）；[2] 後端連 Supabase 使用 `sslmode=require`（等級依 CQ-20）；[3] 對 Resend、GCS、Google 的呼叫只用 `https://` | 舊稿 §2；00-NFR-OVERVIEW | DEPLOY | MAN |
| SEC-WEB-2 | 補強 | 前端頁面資安標頭由 Firebase Hosting 下發 | `firebase.json` 的 `hosting.headers` 對前端路徑設定（值依 CQ-21、CQ-23）：`Content-Security-Policy`（`script-src` 不允許 `'unsafe-inline'`、`'unsafe-eval'`；含 `frame-ancestors 'none'`、`object-src 'none'`、`base-uri 'self'`）、`Strict-Transport-Security`、`X-Content-Type-Options: nosniff`、`Referrer-Policy`、`X-Frame-Options: DENY`、`Permissions-Policy`。[1] `curl -sI` 首頁、任一 SPA 深層路徑、`/assets/*.js` 皆帶上列標頭；[2] `curl -sI https://{PROJECT_ID}.web.app/api/sitters/{id}/plans` 的 CSP 只有 Spring 那一組（SEC-WEB-3）；若 Firebase 的 headers 也套到 rewrite 回應（推測），在 `headers` 排除 `/api/**`；[3] CI 測試解析 `firebase.json`，缺任一標頭即失敗；[4] E2E 黃金路徑（登入、預約、上傳憑證、看日誌媒體、Google 登入、WebAuthn）監聽 `securitypolicyviolation`，違規數 = 0；上線方式依 CQ-22 | 使用者 2026-11-22（補強範圍） | FRONT、DEPLOY | CI、E2E、MAN |
| SEC-WEB-3 | 補強 | API 回應資安標頭由 Spring Security 下發 | 所有 `/api/**` 回應（含 2xx、401、403、404、500，含直接打 `*.run.app` 者）：[1] 帶 `X-Content-Type-Options: nosniff`、`Cache-Control: no-store`、`X-Frame-Options: DENY`、`Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`、`Referrer-Policy: no-referrer`；[2] `Strict-Transport-Security`（值同 CQ-23）：有自訂網域時必須；只用 `*.web.app` / `*.run.app` 時建議（`.app` 已 preload）。輸出 HSTS 不得依賴 `request.isSecure()`（Cloud Run 在前端終結 TLS）。驗收：IT 以 MockMvc 斷言四種狀態碼的 [1]；上線後 `curl -sI` 檢查 | 使用者 2026-11-22（補強範圍） | GLOBAL | IT、MAN |
| SEC-WEB-4 | 補強 | CORS 只允許白名單來源 | [1] 不得使用 `*`；prod 白名單依 CQ-24，local profile 另加 `http://localhost:5173`；[2] `Origin: https://evil.example` 的預檢請求 → 回應不含 `Access-Control-Allow-Origin`；白名單來源 → 回應該來源原值；[3] 允許標頭、方法逐一列舉；改用 refresh cookie（SEC-AUTH-4）時 `allowCredentials=true` 只配合精確來源；[4] 照護日誌直傳的 GCS bucket CORS 依 SD-FULFILL DF2：方法只有 `PUT`，origin 清單與允許標頭以 DF2 為準（是否保留 run.app origin 見 §15） | 使用者 2026-11-22（補強範圍）；SD-FULFILL DF2 | GLOBAL、FULFILL、DEPLOY | IT、MAN |
| SEC-WEB-5 | 補強 | 開放重導防護：前端導頁只允許站內相對路徑 | [1] 由網址參數（例 `/login?redirect=`）或 API 回應決定的導頁目標，須以 `/` 開頭、第二字元不是 `/` 也不是 `\`、不含控制字元與空白，且 `new URL(target, location.origin).origin === location.origin`；不符 → 改導預設頁；[2] E2E / 單元測試：`redirect` 為 `//evil.example`、`/\evil.example`、`https://evil.example`、`javascript:alert(1)`、`/%09/evil.example`、`%2F%2Fevil.example` 時，登入後 URL 的 origin 仍為站內；[3] 後端產生的 Email 連結一律以設定值 `app.frontend-base-url` 組成，不取自請求的 `Host`、`X-Forwarded-Host` | 使用者 2026-11-22（補強範圍） | FRONT、NOTIFY、IDENTITY | E2E、IT |
| SEC-WEB-6 | 保留 | 使用者內容在輸出端轉義（取代舊稿「輸入自動過濾」） | [1] 輸入端不改寫字串內容（只依 GLOBAL §2.7 去頭尾空白），輸出端轉義；[2] 前端禁止以 `dangerouslySetInnerHTML`、`innerHTML` 呈現使用者內容：ESLint `react/no-danger` 設為 error，CI lint 失敗即擋；[3] 顯示名稱含 script 標籤字串（含左右角括號）時，通知 Email HTML 中的角括號輸出為 `&lt;`、`&gt;`，不形成可執行標籤 | 舊稿 §3「輸入安全檢核」；GLOBAL §2.7 | FRONT、NOTIFY | CI、IT |

---

## 6. 請求解析與濫用防護（SEC-INPUT）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-INPUT-1 | 補強 | 請求 body 有上限，超過不讀入記憶體 | [1] 帶 `Idempotency-Key` 的 JSON：> 64 KB → 400 `VALIDATION_FAILED`（`field=body`、`maxBytes`），chunked 最多讀 64 KB + 1 bytes（GLOBAL §2.4）；[2] 其他 JSON：全域上限依 CQ-25，超過 → 400 `VALIDATION_FAILED`，以串流計數中止，不先整包讀入；[3] multipart 上限見 SEC-FILE-4.4 | GLOBAL §2.4；SD-ORDER §8.3 D4；使用者 2026-11-22 | GLOBAL | IT |
| SEC-INPUT-2 | 補強 | 型別嚴格，拒絕浮點冒充整數與客戶端決定的伺服器欄位 | [1] `spring.jackson.deserialization.accept-float-as-int=false`：整數欄送 `100.5` 或 `100.0` → 400 `VALIDATION_FAILED`；[2] 金額欄以 `BigDecimal` 接收並驗證為整數（GLOBAL §2.6）；[3] Request DTO 不得宣告**代表呼叫者自己**的身分欄位，以及 `id`、`status`、`version`、伺服器計算的金額；多送時被忽略（GLOBAL §3.4、baseline CQ-35）。AU 測試的白名單直接引用 GLOBAL §3.4「允許的例外」（操作對象型的 `sitterId` / `ownerId`，例 `CreateReferralRequest.ownerId`） | GLOBAL §2.6、§3.4；baseline §3.6 CQ-35；使用者 2026-11-22 | GLOBAL、各模組 SD | IT、AU |
| SEC-INPUT-3 | 補強 | 解析與數值運算不被惡意輸入耗盡資源 | [1] 不得調高 Jackson 3 `StreamReadConstraints` 預設值；巢狀 2,000 層的 JSON（總長 < 64 KB）→ 400 `VALIDATION_FAILED`（不得 500 或 StackOverflowError）；[2] 金額欄送 `1e999999999` → 400 `VALIDATION_FAILED`，回應時間 < 1 秒：任何 `BigDecimal` 運算（`setScale`、`toBigIntegerExact`、`stripTrailingZeros`）前，先以 `precision()` 與 `scale()` 檢查範圍（上限依 CQ-26）；[3] 文字欄先檢查字數上限（GLOBAL §2.7）再做其他處理 | 使用者 2026-11-22（補強範圍）；GLOBAL §2.6、§2.7 | GLOBAL | IT |
| SEC-INPUT-4 | 補強 | ReDoS 防護 | [1] 伺服器端對使用者輸入套用正規表示式前，先限制輸入長度；[2] 套用在使用者輸入的樣式不得含巢狀量詞（例 `(a+)+`、`(\w*)*`）或可重疊的選擇分支加量詞（AU：掃描 `Pattern.compile` 與 `@Pattern` 的樣式字串）；[3] 每個以 regex 驗證的欄位，以**該欄位允許的最大長度**構造回溯最壞情況的字串（例：重複可匹配字元後接一個不匹配字元），請求必須通過 body 與字數上限、實際進入 regex 比對，回應時間 < 1 秒 | 使用者 2026-11-22（補強範圍） | GLOBAL、各模組 SD | IT、AU |
| SEC-INPUT-5 | 補強 | 列表查詢有筆數上限 | 分頁參數 `size` 超過上限（CQ-27）→ 400 `VALIDATION_FAILED`；未帶 `size` 時用預設值；不提供無上限的「全部匯出」端點給一般使用者 | 使用者 2026-11-22（補強範圍） | GLOBAL、各模組 SD | IT |
| SEC-INPUT-6 | 保留 | 全域限流 | [1] 未登入：同一來源 IP 對 `/api/**` 每分鐘最多 60 次，第 61 次 → 429（碼依 CQ-28）並帶 `Retry-After`（秒）；[2] 登入使用者：以 token 身分計數，上限依 CQ-29；[3] `/api/internal/**` 不套用；[4] 不同 IP 互不影響；帶有效 token 的請求不計入匿名額度；[5] 來源 IP 取法依 CQ-30；不得直接用 `getRemoteAddr()`（開啟 forwarded 標頭處理後會被改寫）；[6] 驗證分兩層：(a) 單元測試以 `X-Forwarded-For` 字串樣本驗證取法，四種樣本：直打 run.app、經 Firebase 轉發、直打並偽造 XFF、自 GCP 客戶網段（VM / 其他 Cloud Run）直打並偽造 XFF，後兩者的計數鍵都必須是實際連線來源、不是偽造值；(b) 部署後在兩條路徑（經 `web.app`、直打 `run.app`）各做一次 MAN 驗證並記錄 XFF 原文；IT：同 IP 匿名 60 秒內打 61 次 `GET /api/sitters/{id}/plans` → 前 60 次 200、第 61 次 429；[7] SD-GLOBAL-SPEC 以 E2E 實測記錄每個公開頁、登入後主要頁面（訂單詳情、日誌、帳務）單次載入的 API 呼叫數；台灣行動網路多為 CGNAT（多人共用 IP），公開頁單次超過 10 支 API 時提請使用者調整 60 次的上限，登入後頁面單次超過 30 支時提請調整 CQ-29；[8] 限制與方案等級無關 | 舊稿 §3；使用者 2026-11-22 | GLOBAL、DEPLOY | IT、E2E |
| SEC-INPUT-7 | 審查補 | 會寄信的匿名端點另有寄送上限 | 適用：註冊 OTP、重送 OTP、忘記密碼。依 CQ-41：[1] 同一 email 每小時最多 5 封，同一來源 IP 每小時最多 20 封，超過 → 429（碼同 CQ-28），不寄信；[2] 忘記密碼對不存在的 email 也計數、回相同回應（不寄信）；[3] IT：同 email 第 6 次 → 429，Resend Mock 只收到 5 封 | CQ-41、CQ-52 | IDENTITY | IT |
| SEC-INPUT-8 | 審查補 | SSRF 防護：後端不對使用者提供的 URL 發請求 | [1] 目前後端只呼叫固定主機（GCS、Resend、Google）；任何 HTTP client 的目標主機必須來自設定檔，不得取自請求參數或資料庫中使用者填寫的值；[2] 日後確有需要（例：ICS 訂閱、金流回呼）時，只允許主機白名單、解析後拒絕私有、loopback、link-local（含 `169.254.169.254` metadata server）位址、不跟隨重導；[3] AU：掃描 `RestClient`、`WebClient`、`HttpClient`、`URL.openConnection` 的呼叫點，只允許列冊的類別使用 | CQ-52 | GLOBAL | AU |

---

## 7. 錯誤回應與日誌（SEC-LOG）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-LOG-1 | 補強 | log 遮蔽敏感值 | 不得出現在任何 log（含 DEBUG）：`Authorization`、`Cookie`、`Set-Cookie`、`X-Internal-Secret` 標頭值；access / refresh token、重設憑證、OTP；密碼（含目前密碼）；WebAuthn 原始 credential；完整收款帳號與戶名（只可「銀行代碼 + 帳號後五碼」，PRD-025 BR-8）；簽名網址的 query 字串；email 依 CQ-16。驗收：IT 以 log 擷取跑「登入成功 / 失敗、換發、改密碼、重設密碼、OTP、修改收款帳號、建立 payment 快照、內部端點錯誤 secret、取得簽名網址」，擷取內容不含測試用的密碼、token、OTP、secret、帳號明文、`X-Goog-Signature=` | 專案硬性規則 6；PRD-025 BR-8；SD-ORDER §8.3 D3；使用者 2026-11-22 | GLOBAL、IDENTITY、SUPPLY、ORDER | IT |
| SEC-LOG-2 | 補強 | 錯誤回應不洩漏內部細節 | [1] 所有錯誤 body 只含 `code`、`message`、`payload`、`traceId`、`timestamp`（GLOBAL §2.6）；[2] `message` 為固定文案，不拼接框架、JDK、JDBC、第三方例外的訊息；[3] 不含堆疊、例外類別名、SQL、約束名、表名、檔案路徑、主機名；[4] 未處理例外 → 500 `INTERNAL_ERROR` + `traceId`，細節只進 ERROR log。驗收：IT 觸發 (a) 訊息含 `SELECT` 的未處理 RuntimeException、(b) SQLSTATE 23514、(c) 不合法 JSON、(d) `AuthenticationException`、(e) `IllegalArgumentException`、(f) 73 bytes 密碼，回應 body 皆不含原例外訊息、約束名與 Jackson 解析訊息 | GLOBAL §2.6；baseline §3.7「非業務錯誤」；使用者 2026-11-22 | GLOBAL | IT |
| SEC-LOG-3 | 補強 | 管理與文件端點不對外暴露 | [1] Actuator 只暴露 `health`，`show-details=never`（GLOBAL §6.3）：`GET /actuator/env` → 404；[2] prod 的 OpenAPI / Swagger 依 CQ-31：預設 `GET /v3/api-docs`、`/swagger-ui.html` 帶任何有效 token 仍 404 | GLOBAL §6.2、§6.3；`SecurityConfig` 註解（prod 不公開規格書） | GLOBAL、DEPLOY | IT、MAN |
| SEC-LOG-4 | 補強 | 安全事件可追查 | 以下事件記 WARN，含 `traceId`、路徑、token 身分（若有）、來源 IP，不含 secret：登入鎖定、內部端點驗證失敗、refresh token 重用（CQ-3）、限流觸發（同一 IP 每分鐘最多記 1 筆）、寄信上限觸發、加密解密失敗（ERROR）。驗收：IT 擷取 log 斷言欄位存在 | 舊稿 §1「記錄異常嘗試」；GLOBAL §1、§6.3 | GLOBAL、IDENTITY | IT |
| SEC-LOG-5 | 審查補 | log 注入防護與 traceId 格式 | [1] 取自 `X-Cloud-Trace-Context` 的 trace 部分必須符合 `^[0-9a-f]{32}$`，否則重新產生（GLOBAL §2.6 需回寫，§15）；[2] 寫進 log 的使用者輸入跳脫 CR、LF，或改用結構化 JSON log；[3] IT：送 `X-Cloud-Trace-Context: abc` 加 CRLF 加 `FAKE` → 回應的 `traceId` 不等於輸入且符合格式；以含 CRLF 的 email 登入失敗，log 不出現偽造的新行 | CQ-52 | GLOBAL | IT |

---

## 8. 稽核紀錄（SEC-AUDIT）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-AUDIT-1 | 保留 | 稽核紀錄與被稽核的操作同交易，不可單獨寫入 | [1] 訂單、付款、變更、行程轉移的 `order_logs` 與狀態變更同一交易（baseline §3.6）；收款帳號稽核與帳號更新同交易（PRD-025）；帳號安全稽核（註冊、改密碼、重設密碼、停用、登出所有裝置、KYC 核准 / 退件、停權、ADMIN 作廢 / 調解）與主操作同交易；[2] 稽核寫入方法的交易傳播為 `MANDATORY`，無交易呼叫即拋例外；禁止 `REQUIRES_NEW`、`@Async`、交易提交後 hook 寫稽核；[3] 主操作含外部 I/O（GCS 刪除、寄信）時：稽核與該操作的狀態標記（例 `purged = true`、通知 outbox 列）放同一交易；刪除類先完成 I/O、成功後才開交易寫標記與稽核；寄信類在交易內寫 outbox（baseline §3.6），不以寄送結果寫稽核；[4] 登入鎖定的稽核寫在登入失敗計數器的獨立交易內（計數器必須在登入回滾後留存，是本條唯一允許的獨立交易）；[5] IT：注入失敗使主交易回滾 → 稽核 0 列；成功 → 恰 1 列 | baseline §3.6；GLOBAL §4.3；PRD-025 §4.1；使用者 2026-11-22 | ORDER、FULFILL、SUPPLY、IDENTITY、TRUST、CARE | IT、AU |
| SEC-AUDIT-2 | 保留 | 稽核內容足以追查且不含敏感明文 | 每列含：操作者（userId；系統為 `SYSTEM`）、時間（UTC `timestamptz`）、動作碼、目標 id、變動欄位與前後值摘要；收款帳號只存「銀行代碼 + 帳號後五碼」；不存密碼、token、完整帳號、戶名；帳號安全類事件是否記 IP 依 CQ-32。覆蓋事件：身分狀態變更、權限異動（含 ADMIN 開通）、個資修改、訂單轉移（baseline §3.6） | 舊稿 §4；PRD-025 BR-8、AC-1.5；baseline §3.6 | 同上 | IT |
| SEC-AUDIT-3 | 保留 | 稽核紀錄不可抹除 | [1] 稽核實體（`order_logs` 與各稽核表）的 Repository 繼承自訂的 append-only 介面（只有 `insert` 與查詢），不繼承 `CrudRepository` / `JpaRepository`；AU 斷言：稽核實體的 Repository 都不是 `CrudRepository` 子型別、沒有 `delete*` / `save*` 方法；[2] 沒有任何 API 可修改或刪除稽核；[3] 交易相關稽核保存 ≥ 5 年（NFR-008）；DB 層防護與帳號註銷時的處理依 CQ-33 | 舊稿 §4；NFR-008 §3；GLOBAL §4.3 | ORDER、FULFILL、SUPPLY、IDENTITY | AU |

---

## 9. 內部端點（SEC-INT）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-INT-1 | 補強 | `/api/internal/**` 只認 `X-Internal-Secret` | [1] 標頭缺漏、不符、只帶一般使用者 token、只帶 ADMIN token → 403 `FORBIDDEN`（GLOBAL §2.6 格式），不處理任何資料；[2] 比對使用常數時間比較（`MessageDigest.isEqual`）；[3] 驗證失敗只記路徑，log 不含標頭值；[4] secret 強度、輪替與替代方案（OIDC）依 CQ-34。驗收：`InternalCronSecurityTest` 涵蓋 [1] 四種情況與 log 擷取 | SD-ORDER §6.3、§8.3 D3；PRD-027 AC-2.4；PRD-008 AC-2.6 | ORDER、FULFILL、DEPLOY | IT |
| SEC-INT-2 | 補強 | 測試支援端點不得觸及真實使用者資料 | [1] `/api/internal/test-data/**` 與排程端點的 `orderId` 參數：目標訂單的 owner 或 sitter 不是 `@e2e-journey.test` 帳號 → 404 `NOT_FOUND`，不處理任何資料；Cloud Scheduler 自動觸發時排除測試帳號訂單；[2] 一般註冊與 Google 綁定遇到 `@e2e-journey.test` 網域的處理依 CQ-51 | SD-ORDER §6.3（使用者決定 2026-11-22）；SD-FULFILL §2.3 `CronScope` | ORDER、FULFILL、IDENTITY | IT |

---

## 10. 依賴、供應鏈與平台支援期（SEC-SCA）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-SCA-1 | 補強 | CI 依賴漏洞掃描 | [1] `.github/workflows/security-scan.yml` 於 PR、push main、每週一 01:17 UTC 執行；[2] 前端 runtime 依賴有 High 以上 → 失敗，dev 依賴只列報告；掃描工具依 CQ-49（需能設定附到期日的例外）；[3] 後端 osv-scanner 掃 `backend/pom.xml`（含傳遞依賴）有已知漏洞 → 失敗；掃描結果是否反映實際解析版本的驗證方式依 CQ-50；[4] Dependabot：maven、npm 每週，GitHub Actions 每月；[5] 是否設為 PR 必要檢查依 CQ-35 | 使用者 2026-11-22（補強範圍；CI 已建） | DEPLOY | CI |
| SEC-SCA-2 | 補強 | 漏洞修補時限 | 自 CI 首次因該漏洞失敗或 Dependabot alert 建立起算：Critical、High 的修補期限依 CQ-36；Medium、Low 隨例行升級。暫時無法修補者逐筆寫原因與到期日（前端與後端皆同，工具見 CQ-49），到期日距建立 ≤ 90 天（CQ-36），到期未處理 CI 恢復失敗 | 使用者 2026-11-22（補強範圍） | DEPLOY | CI、MAN |
| SEC-SCA-3 | 補強 | 框架與執行環境在官方開源支援期內 | [1] 上線日與營運期間，Spring Boot、Java、建置用 Node.js、PostgreSQL 版本皆在官方開源支援期內；[2] Spring Boot 4.0.x 開源支援至 2026-12-31（使用者提供），上線前須升到仍受支援的版本列，緩衝依 CQ-37；[3] Node.js 20 已於 2026-04-30 EOL，`deploy.yml` 與 `security-scan.yml` 須改用受支援的 LTS（CQ-37）；[4] 每次發布前以 MAN 檢查表核對 `backend/pom.xml` parent 版本、Dockerfile 基底映像、CI `node-version` | 使用者 2026-11-22（補強範圍）；Node.js Release Schedule | GLOBAL、DEPLOY | MAN、CI |
| SEC-SCA-4 | 審查補 | GitHub Actions 供應鏈 | [1] 所有第三方 action（`actions/*`、`google-github-actions/*` 等）以完整 commit SHA 釘選，註解標版本，由 Dependabot 更新；[2] CI 檢查：workflow 中 `uses:` 不符 `@[0-9a-f]{40}` 者 0 筆；[3] 具 `id-token: write`（部署用 WIF）的 workflow 只在手動觸發或 main 執行 | `deploy.yml`（WIF 部署權限）；CQ-52 | DEPLOY | CI |

### 10.4 線上金流（SEC-PAY）

本版不涵蓋。SD-015 撰寫前另補 SEC-PAY 節（金流回呼驗簽、金額與訂單比對、回呼冪等、重放視窗），見 CQ-42。

---

## 11. 現況落差（2026-11-22 程式碼證據）

| # | 落差 | 證據 | 對應要求 | 嚴重度 |
|---|------|------|---------|--------|
| G-1 | **prod 部署未注入 `APP_CRYPTO_KEY`**：`application.yml` 已無預設值（缺值啟動失敗），但 `deploy.yml` 的 `--set-secrets` 只有 DB、JWT、INTERNAL_CRON_SECRET、Resend。`--set-secrets` 會取代服務全部 secret 對映，推測下次部署 prod 啟動失敗（修正需建立 Secret Manager 項目，雲端變更需使用者當次同意） | `.github/workflows/deploy.yml:97`；`application.yml:46` | SEC-DATA-2、SEC-SECRET-1 | 高 |
| G-2 | **PRD-021 照護媒體為公開物件**：`uploadMedia` 寫入 `care_media/` 走 `uploadPublic`（`allUsers READER`），飼主與保母之間的私有媒體可被匿名讀取 | `GcsMediaStorageServiceImpl.java:62-75,92-95` | SEC-FILE-1 | 高 |
| G-3 | 公開與私有物件混在同一 bucket：毛孩頭像（`pet-avatars/`）、保母頭像（`avatars/`）、照護媒體為公開；憑證、KYC、日誌媒體為私有。直接啟用 UBLA + PAP 會讓 `setAcl(allUsers)` 上傳全部失敗 | `GcsMediaStorageServiceImpl.java:98-104,139-145` | SEC-FILE-1、CQ-17 | 高 |
| G-4 | 全域限流未實作；只有 KYC 提交每小時 5 次；寄信類匿名端點只有每 email 60 秒冷卻 | `KycServiceImpl.checkRateLimit`；`AuthService.java:84,180` | SEC-INPUT-6、SEC-INPUT-7 | 高 |
| G-5 | `firebase.json` 無 `headers` 設定 | `firebase.json` | SEC-WEB-2 | 高 |
| G-6 | access / refresh token 存 localStorage | `frontend/src/api/axiosClient.ts:62,102,118`、`pages/auth/LoginPage.tsx:32`、`contexts/RoleContext.tsx:44`、`contexts/manualRoleSwitch.ts:63`、`components/auth/RequireAuth.tsx:18`、`hooks/useCurrentUser.ts:30` | SEC-AUTH-4 | 高 |
| G-7 | refresh token 與密碼重設憑證以明文存 DB（unique 明文欄位），DB 外洩即可接管 session 或重設密碼 | `domain/model/RefreshToken.java`、`PasswordResetToken.java`；`AuthService.java:247,534` 以明文查詢 | SEC-AUTH-9 | 高 |
| G-8 | CORS `allowedOrigins("*")` | `SecurityConfig.java:99` | SEC-WEB-4 | 中 |
| G-9 | 開放重導：`getSafeRedirectTarget` 用 `/^\/(?!\/)/`，`/\evil.example` 會通過，瀏覽器把 `\` 視為 `/` 導到外站 | `LoginPage.tsx:19-22`、`RegisterPage.tsx` | SEC-WEB-5 | 中 |
| G-10 | refresh token 換發時不輪替（回傳原 token）；有效期 7 天寫死常數，無絕對上限 | `AuthService.java:81,263` | SEC-AUTH-3、CQ-2、CQ-3 | 中 |
| G-11 | 「登出所有裝置」、停用只刪 `refresh_tokens`，既有 access token 仍有效最多 24 小時 | `AuthService.java:361,386`；`application.yml:43` | SEC-AUTH-3.5、CQ-4 | 中 |
| G-12 | JWT 簽章金鑰在受版控的 `application.yml` 有公開預設值；prod 由 `application-prod.yml` 覆蓋，但 local、CI、任何漏設 prod profile 的環境用公開金鑰簽章 | `application.yml:42`；`application-prod.yml:24` | SEC-SECRET-1 | 中 |
| G-13 | 帳號類稽核以 `REQUIRES_NEW` 在主交易外寫入；`log.info` 印出稽核 details | `AuditLogService.java:24-26,42-44`；呼叫端 `AuthService.java:362,389`、`MediaRetentionServiceImpl.java:100` | SEC-AUDIT-1、SEC-LOG-1 | 中 |
| G-14 | 錯誤回應回顯例外訊息：`AuthenticationException` 拼接 `ex.getMessage()`；`IllegalArgumentException`、`IllegalStateException` 直接回 `ex.getMessage()`（BCrypt 超過 72 bytes 拋的 `IllegalArgumentException` 會原樣回給前端）；未重建模組的 `DataIntegrityViolationException` 一律 400 `DATA_ERROR` | `GlobalExceptionHandler.java:60,171,192,197` | SEC-LOG-2、SEC-AUTH-11 | 中 |
| G-15 | KYC 與頭像上傳沿用客戶端 `Content-Type` 與檔名副檔名設定物件 metadata 與物件鍵 | `GcsMediaStorageServiceImpl.java:66,81,99-101,131-133,140-142`；`KycServiceImpl.java:285`；`SitterPublicProfileServiceImpl.java:146` | SEC-FILE-3 | 中 |
| G-16 | traceId 直接取 `X-Cloud-Trace-Context` 斜線前的字串，未驗證格式，可注入任意字元到 log 與錯誤回應 | `ApiErrors.java:82-86` | SEC-LOG-5 | 中 |
| G-17 | GitHub Actions 只釘 tag（`@v4`、`@v2`），`deploy.yml` 具 WIF 部署權限 | `deploy.yml:25,31,39,45,59,141`；`security-scan.yml:21,22,39` | SEC-SCA-4 | 中 |
| G-18 | Node.js 20（2026-04-30 已 EOL）用於前端建置與掃描；osv-scanner 釘在 v2.0.0；Spring Boot 4.0.10（4.0 開源支援至 2026-12-31，距今 39 天，90 天緩衝已無法以 4.0.x 達成） | `deploy.yml:47`；`security-scan.yml:24,43`；`backend/pom.xml:8` | SEC-SCA-3、SEC-SCA-1 | 高（上線前必須處理） |
| G-19 | API 端 HSTS 推測未輸出（Cloud Run 終結 TLS，未設定 forwarded 標頭處理）；Referrer-Policy、API CSP 未設定（需 `curl -sI` 驗證） | `SecurityConfig.java`；`application.yml` 無 `server.forward-headers-strategy` | SEC-WEB-3 | 低 |
| G-20 | prod 仍掛載 springdoc：任何登入者可讀 `/v3/api-docs` | `SecurityConfig.java:64-66,78`；`application-prod.yml` 無 springdoc 設定 | SEC-LOG-3 | 低 |
| G-21 | 後端映像不含前端，`SecurityConfig` 仍 permitAll 靜態檔路徑（殘留） | `SecurityConfig.java:61`；`backend/Dockerfile` | SEC-AZ-2 | 低 |
| G-22 | 應用程式以 `postgres.{ref}` 帳號連線（推測具超級使用者權限）；Supabase Data API 是否關閉未確認 | `application-prod.yml:6` | SEC-DATA-3 | 待確認 |
| G-23 | 非冪等 JSON body 無全域上限 | `application.yml:3-17`；GLOBAL §2.4 | SEC-INPUT-1 | 低 |
| G-24 | log 含 email | `AuthService.java:252` | SEC-LOG-1、CQ-16 | 低 |
| G-25 | `RESEND_API_KEY` 預設空字串 | `application.yml:48` | SEC-SECRET-1、CQ-14 | 低 |
| G-26 | 登入鎖定只對存在的帳號計數（不存在的 email 不鎖，可藉此列舉）；無 IP 維度 | `LoginAttemptService.java` | SEC-AUTH-5、CQ-6、CQ-7 | 低 |
| G-27 | 容器以 root 執行；CI 未掃描容器映像 | `backend/Dockerfile` | CQ-38 | 低 |
| — | **已符合、不是落差**：`APP_CRYPTO_KEY`、`INTERNAL_CRON_SECRET` 預設值已在 B4 移除；`InternalSecretFilter` 已回 403 `FORBIDDEN`、`MessageDigest.isEqual`、log 只記路徑；Google 登入已檢查 `email_verified`；註冊 OTP 已以雜湊存放（`otp_hash`） | `application.yml:38,46`；`InternalSecretFilter.java:45-55`；`AuthService.java:399`；`RegistrationOtp.java:35` | SEC-INT-1、SEC-SECRET-1、SEC-AUTH-9、SEC-AUTH-10 | — |

---

## 12. 需求來源互相矛盾之處（本文件的處理）

| # | 矛盾 | 本文件處理 |
|---|------|-----------|
| X-1 | 舊稿 §3 與 `00-NFR-OVERVIEW` 要求「變更銀行撥款帳戶需二次驗證」；baseline CQ-75 與 PRD-025 BR-10 定為 MVP 不重驗 | **已決（使用者 2026-11-22）**：不重驗，以不可關閉通知偵測盜改（SEC-AUTH-6.3） |
| X-2 | 舊稿 §2 與 PRD-017（Draft）寫 KYC「僅管理員可讀」；baseline §1.2 為「本人與 ADMIN」 | 依 baseline（SEC-FILE-1.4）；PRD-017 重寫時同步 |
| X-3 | 舊稿 §2 付款憑證簽名網址 10 分鐘；PRD-007 BR-17 為 5 分鐘；PRD-022 BR-18 日誌媒體 15 分鐘 | 依 PRD：憑證 300 秒、日誌媒體 900 秒（SEC-FILE-2） |
| X-4 | 舊稿 §1 鎖定「單一帳號」；PRD-000 AC-4 寫「該帳號 / IP」 | email 維度為必要（SEC-AUTH-5）；IP 維度列 CQ-6 |
| X-5 | SD-GLOBAL-SPEC §1.2 寫前端打包進後端映像；實際由 Firebase Hosting 提供（`firebase.json`、`backend/Dockerfile`） | 以實際部署為準（SEC-WEB-2）；回寫 GLOBAL §1.2（§15） |
| X-6 | 舊文件稱最高方案為 Premium；baseline §2.1 為 ULTIMATE | 依 baseline 用 ULTIMATE |
| X-7 | `00-NFR-OVERVIEW.md` 的 NFR-003 段仍是舊內容 | 待本文件核准後同步（§15） |

---

## 13. 待釐清事項

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-1 | BCrypt cost 下限 | 10（OWASP 下限、Spring 預設）；登入 P95 延遲可接受時再提高到 12 |
| CQ-2 | refresh token 有效期與 session 絕對上限（含審查 R-CQ-6） | 單顆 refresh token 7 天，輪替時新 token 重新起算；同一 token family 自首次登入起 30 天為絕對上限，期滿必須重新登入，不受輪替影響 |
| CQ-3 | refresh token 輪替、寬限與重用處理 | 每次換發輪替，舊 token 標記 `ROTATED` 並記 `rotated_at`；30 秒寬限內舊 token 再被使用 → **換發另一顆新 token（同一 family）**，不回傳先前那顆（伺服器只存雜湊，取不回明文），容許多分頁同時換發；同一顆舊 token 在寬限內最多換發 3 次（含第一次），第 4 次起視同重用；同一父 token 衍生的兄弟 token，其中一顆被用來換發時，其餘兄弟標記 `SUPERSEDED`（不算重用）。改成 httpOnly cookie 後所有分頁共用同一顆 cookie，正常使用只會留下一顆，不會誤傷；超過寬限或超過次數再使用 `ROTATED` token → 401，撤銷整個 family、記 WARN；`SUPERSEDED`（單一 session 被後登入者覆蓋）與 `LOGOUT` 再使用 → 一般 401，不觸發撤銷 |
| CQ-4 | 撤銷後舊 access token 的殘留時間 | access token 縮短為 15 分鐘（仍符合 ≤ 24 小時）；不做每請求查庫的黑名單 |
| CQ-5 | token 存放方式與 cookie 屬性 | refresh token 改 httpOnly cookie，名稱必須是 `__session`（Firebase Hosting 轉發 Cloud Run 時只保留這個 cookie，官方文件明載；SD-IDENTITY 仍須實測），因此只能有一顆 cookie、不能用 `__Host-` 前綴；屬性 `HttpOnly; Secure; SameSite=Strict; Path=/`，host-only（不設 `Domain`）；`Path=/` 會讓每個 `/api/**` 請求都帶 cookie，故其他端點不得讀它（SEC-AUTH-4.4）；access token 只放記憶體 |
| CQ-6 | 登入鎖定是否加 IP 維度 | 不另做 IP 鎖定；登入端點計入 SEC-INPUT-6 的匿名 IP 限流。帳號維度鎖定可被他人故意觸發（鎖定型 DoS），MVP 接受 |
| CQ-7 | 帳號列舉的處理範圍（登入、註冊、忘記密碼） | 登入：不存在與密碼錯誤同碼同文案、同樣計數鎖定、跑假雜湊，回應時間 P50 差 < 50 ms；忘記密碼：一律回「若此 Email 已註冊，將收到重設信」；註冊：PRD-000 的「此 Email 已被使用」會透露帳號存在，MVP 接受（改為只在 OTP 步驟後提示則需改 PRD-000） |
| CQ-8 | 改密碼時目前密碼錯誤是否計入登入失敗次數 | 計入同一計數，達 5 次一併鎖定 |
| CQ-9 | 綁定 WebAuthn 前的再驗證方式 | 有密碼的帳號輸入目前密碼；只有 Google 登入的帳號須在 5 分鐘內重新完成 Google 登入 |
| CQ-10 | WebAuthn 參數 | challenge 單次使用、5 分鐘失效；`userVerification=required`；RP ID 只用 `{PROJECT_ID}.web.app`（該網域在 Public Suffix List 上，一組 credential 只能綁一個網域）；`{PROJECT_ID}.firebaseapp.com` 一律轉址到 `web.app`，若無法轉址則從 WebAuthn origin 白名單排除（該來源不提供生物辨識登入） |
| CQ-11 | ADMIN 帳號開通方式 | 不提供 API。ADMIN 是**獨立帳號**：`password_hash` 為 NULL、沒有 OWNER / SITTER profile，不由既有帳號升級。開通：使用者在本機產生一次性註冊憑證明文（例 `openssl rand -base64 32`，≥ 128 bit），自行計算 SHA-256，只把雜湊交給 SQL 寫入（15 分鐘、單次）；DB 變更需使用者當次同意；agent 不經手明文，明文不經對話、log、Resend（Resend 目前為測試模式，也不能當交付管道）；註冊連結由使用者自行交付給 ADMIN。ADMIN 以連結進入後立即註冊 WebAuthn；開通與註冊皆寫稽核 |
| CQ-12 | ADMIN 第二因素與遺失裝置 | ADMIN 只能以 WebAuthn 登入，建議每位 ADMIN 註冊 2 個裝置；全部遺失時由使用者手動 SQL 撤銷舊 credential，並依 CQ-11 同樣方式（本機產生明文、只交雜湊）重發註冊連結。替代方案：密碼加 TOTP（需放棄 CQ-11 的無密碼設計） |
| CQ-13 | 加密金鑰版本與輪替 | 沿用 SD-ORDER `payee_account_key_version` 的獨立欄位做法；`profiles.bank_account_info` 比照新增 `bank_account_key_version`（SD-SUPPLY），不使用密文前綴；MVP 不自動輪替，外洩時人工換鑰並以一次性工作重新加密 |
| CQ-14 | prod 缺 `RESEND_API_KEY` 的行為 | prod 缺值啟動失敗；local 可空（走 Mock） |
| CQ-15 | commit 的 secret 掃描 | `security-scan.yml` 加 gitleaks（PR 與 push main），命中即失敗 |
| CQ-16 | log 是否可記 email | 遮蔽為首字元加網域（`w***@example.com`）；需要追查時用 userId |
| CQ-17 | 公開與私有物件拆 bucket；毛孩頭像是否公開（含審查 R-CQ-2） | 拆成公開 bucket（只放保母頭像）與私有 bucket（其餘全部）；毛孩頭像與 PRD-021 照護媒體改私有、以簽名網址讀取（照護媒體立即改）。啟用順序：(1) 建公開 bucket 並把保母頭像寫入改到該 bucket；(2) 照護媒體、毛孩頭像改用私有上傳，既有物件移除 `allUsers` ACL；(3) 確認私有 bucket 已無 `uploadPublic` 呼叫與公開物件；(4) 才在私有 bucket 啟用 UBLA + PAP。每步雲端變更需使用者當次同意 |
| CQ-18 | KYC、私有化後的照護媒體與毛孩頭像的簽名網址有效期 | KYC 300 秒（同付款憑證）；照護媒體 900 秒（同日誌媒體）；毛孩頭像 900 秒 |
| CQ-19 | 照片的 EXIF 位置資訊 | 前端上傳前重新編碼（WebP 壓縮時以 canvas 重繪即移除 EXIF），後端不解析；影片 metadata MVP 不處理 |
| CQ-20 | 資料庫 TLS 等級 | 維持 `sslmode=require`；`verify-full` 列為後續強化 |
| CQ-21 | 前端 CSP 與其他標頭的值（含審查 R-CQ-1：字型改自行託管） | 字型檔改自行託管（移除 `global.css` 對 `fonts.googleapis.com` 的 `@import`）。CSP：`default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style; font-src 'self'; img-src 'self' data: blob: https://storage.googleapis.com https://*.googleusercontent.com; media-src 'self' blob: https://storage.googleapis.com; connect-src 'self' https://storage.googleapis.com https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'; worker-src 'self'; manifest-src 'self'`；`Referrer-Policy: strict-origin-when-cross-origin`；`Permissions-Policy: camera=(), microphone=(), geolocation=()`。保留外部字型則 `style-src` 加 `https://fonts.googleapis.com`、`font-src` 加 `https://fonts.gstatic.com` |
| CQ-22 | CSP 上線方式與和 token 存放的順序 | 先以 `Content-Security-Policy-Report-Only` 部署 7 天（E2E 監聽違規），**Report-Only 期必須在正式上線日前結束並改為強制**；來不及時，上線一律採 refresh cookie 方案（SEC-AUTH-4.1–4.6），不得以「token 在 localStorage + CSP 只是 Report-Only」上線；不建 report 收集端點 |
| CQ-23 | HSTS 值 | `max-age=31536000; includeSubDomains`，不加 `preload` |
| CQ-24 | prod CORS 白名單 | `https://{PROJECT_ID}.web.app`、`https://{PROJECT_ID}.firebaseapp.com`（之後有自訂網域再加）；前端經 Firebase rewrite 同源呼叫 API，白名單只影響跨源情境。`firebaseapp.com` 在 WebAuthn 上的處理見 CQ-10（轉址到 `web.app` 或排除） |
| CQ-25 | 非冪等 JSON body 全域上限 | 1 MB，超過 → 400 `VALIDATION_FAILED`（`field=body`、`maxBytes`） |
| CQ-26 | 金額欄位 `BigDecimal` 運算前的範圍檢查 | `precision() ≤ 15` 且 `-15 ≤ scale() ≤ 15`，否則 400 `VALIDATION_FAILED` |
| CQ-27 | 列表分頁筆數上限 | 每頁最多 100 筆，超過 → 400 `VALIDATION_FAILED`；預設 20 |
| CQ-28 | 限流錯誤碼 | 429 `RATE_LIMITED` + `Retry-After`；新的跨模組碼，需寫回 baseline §3.7（filter 層回應，不列入判斷順序） |
| CQ-29 | 登入使用者限流上限 | 每個 token 身分每分鐘 300 次；調整時須附 E2E 實測的每頁 API 數 |
| CQ-30 | 限流的來源 IP 取法 | 兩條入口跳數不同：經 Firebase Hosting 的請求比直接打 `*.run.app` 多一跳（推測：Cloud Run 把連線來源附加在最右；SD-GLOBAL-SPEC 須實測並記錄兩條路徑的 `X-Forwarded-For` 原文）。規則：取 `X-Forwarded-For` 最右項；只有當它落在「實測得到的 Firebase Hosting 轉發出口網段」內時，才改取前一項，**最多往前退一項**，其餘項一律不採用（最左值可偽造）；可信網段明確排除 GCP 客戶可用網段（Google 公開的 `cloud.json` 等），否則攻擊者可從 GCP VM 或其他 Cloud Run 服務直打 run.app 並偽造 XFF 繞過；IPv4 與 IPv6 皆適用同一規則（網段清單兩種位址都要列）。直接打 run.app 時最右項即實際連線來源，偽造的 XFF 不影響計數鍵。若日後 Cloud Run ingress 能限制只收 Firebase 轉發（推測 Firebase rewrite 需 ingress = all，目前做不到），改為擋掉直打 run.app。取錯跳數會讓全站共用 Google 出口 IP 的計數、等於全站 429，上線前須以 E2E 驗證。計數放各實例記憶體（MVP 無 Redis），多實例時實際上限約為 60 × 實例數，接受 |
| CQ-31 | prod 是否停用 OpenAPI / Swagger | 停用（prod profile 設 `springdoc.api-docs.enabled=false`、`springdoc.swagger-ui.enabled=false`）；回寫 GLOBAL §6.2 |
| CQ-32 | 稽核是否記 IP | 帳號安全類事件（登入鎖定、改密碼、重設密碼、登出所有裝置、收款帳號變更、停權）記 IP；訂單轉移不記 |
| CQ-33 | 稽核不可抹除的 DB 層防護、帳號註銷時的處理 | MVP 只做應用層（append-only Repository + AU），不加 DB trigger 或權限回收；帳號註銷時稽核列保留，只存 userId，不複製個資到稽核列 |
| CQ-34 | 內部端點認證強度、輪替與替代方案 | `INTERNAL_CRON_SECRET` ≥ 32 bytes 隨機；外洩時立即輪替，平時每年一次，Cloud Scheduler 標頭與 GitHub Actions secret 同步更新；比對前兩邊先各做 SHA-256 再 `MessageDigest.isEqual`（避免長度差異造成時間差）。替代方案：Cloud Scheduler 改用 OIDC token，後端驗證 `aud` 與服務帳號 email，可免共用 secret 與年度輪替（E2E 呼叫內部端點時需另行處理） |
| CQ-35 | security-scan 是否設為 PR 必要檢查 | 是（GitHub branch protection，由使用者設定） |
| CQ-36 | 漏洞修補時限 | Critical 7 天、High 30 天；暫時無法修補的例外到期日 ≤ 90 天 |
| CQ-37 | 支援期緩衝與版本 | 上線日距所用版本的開源支援 EOL ≥ 90 天；營運中 EOL 前 90 天開始升級。Spring Boot 上線前升到 4.1 以上當時最新 GA 版本列；Node.js 改用 24 LTS |
| CQ-38 | 容器加固 | Dockerfile 以非 root 使用者執行；Artifact Registry 映像漏洞掃描在確認費用後啟用 |
| CQ-39 | 密碼規則（審查 R-CQ-3） | 8–64 字元、UTF-8 ≤ 72 bytes；不強制字元類型；MVP 不檢查外洩密碼清單 |
| CQ-40 | OTP 與重設憑證參數（審查 R-CQ-4） | OTP 6 位數、10 分鐘、每顆 5 次、重送冷卻 60 秒（沿用現況）；重設憑證 ≥ 128 bit、30 分鐘、單次使用；兩者都只存雜湊 |
| CQ-41 | 寄信類匿名端點上限（審查 R-CQ-5） | 每 email 每小時 5 封、每 IP 每小時 20 封，超過回 429 `RATE_LIMITED` |
| CQ-42 | 線上金流安全要求的時點（審查 R-CQ-7） | SD-015 撰寫前另補 SEC-PAY 節：回呼驗簽、金額與訂單比對、回呼冪等、5 分鐘重放視窗 |
| CQ-43 | 同一位飼主對同一位保母同時存在的 `PENDING` 訂單上限（審查 R-CQ-8；與已定案的「不做接單容量控管」不同，這是飼主端濫用） | 3 張，超過回 422（新的模組碼，需改 PRD-005 / SD-ORDER） |
| CQ-44 | 以密碼登入 ADMIN 帳號時的回應（第 1 輪未使用此編號，第 2 輪補用） | 回與「帳號或密碼錯誤」完全相同的 401（同碼、同文案、同樣跑假雜湊、同樣計入 SEC-AUTH-5），不透露這是 ADMIN 帳號 |
| CQ-45 | 登入鎖定的錯誤碼（PRD-000 只寫「特定錯誤碼」，PRD-000 仍 Draft） | 沿用現況 429 `ACCOUNT_LOCKED`，由 SD-IDENTITY 列入模組碼 |
| CQ-46 | 切換角色到 ADMIN 的錯誤碼 | 403 `ROLE_MISMATCH` |
| CQ-47 | Supabase Data API 與 DB 帳號 | 關閉 Data API（應用不使用 PostgREST）；建立應用執行期專用角色（只有應用 schema 的 DML），Flyway 用另一個 migration 角色；切換需使用者當次同意 |
| CQ-48 | AES-GCM 是否以 AAD 綁定所在列 | 綁定，AAD = `表名:列 id:欄位名`，防止有 DB 寫入權者把 A 列密文搬到 B 列；需回寫 SD-ORDER 快照加密與 SD-SUPPLY |
| CQ-49 | 前端漏洞掃描與例外到期機制（npm audit 無法設到期日） | 前端改以 osv-scanner 掃 `frontend/package-lock.json`，例外寫在 `osv-scanner.toml` 並用 `ignoreUntil`；`npm audit` 保留為報告 |
| CQ-50 | 後端掃描是否反映實際解析版本（parent 覆寫，例 Tomcat） | CI 以 CycloneDX Maven plugin 產生 SBOM 後交給 osv-scanner 掃描；osv-scanner 升到當時最新 v2 版本並釘 SHA |
| CQ-51 | 一般註冊與 Google 綁定遇到 `@e2e-journey.test` 網域 | 一律 400 `VALIDATION_FAILED`，只有 test-data 端點能建立這類帳號 |
| CQ-52 | 審查第 1 輪新增的補強項是否納入本版範圍：SEC-AUTH-9、AUTH-10、SEC-DATA-3、SEC-INPUT-7、INPUT-8、SEC-LOG-5、SEC-SCA-4（以及 SEC-AUTH-8、AUTH-11 的範圍擴充） | 全數納入本版 |

---

## 14. 落地對照（給 SD 撰寫者）

| 落地文件 | 須涵蓋的要求 |
|---------|-------------|
| SD-GLOBAL-SPEC | SEC-AZ-1、AZ-2、AZ-5、DATA-3、SECRET-1、FILE-4.4、WEB-3、WEB-4、INPUT-1 ~ INPUT-6、INPUT-8、LOG-1 ~ LOG-5、SCA-3 |
| SD-FRONTEND-SPEC | SEC-AUTH-4、AUTH-7、FILE-5、WEB-2、WEB-5、WEB-6 |
| SD-IDENTITY（尚未撰寫） | SEC-AUTH-1 ~ AUTH-11、AZ-2.2、AZ-3、AZ-4、INPUT-7、WEB-5.3、LOG-1、LOG-4、AUDIT-1 ~ AUDIT-3、INT-2.2 |
| SD-ORDER | SEC-AZ-1、AZ-3、AZ-4、DATA-1、DATA-2、FILE-1 ~ FILE-4、AUDIT-1 ~ AUDIT-3、INT-1、INT-2 |
| SD-FULFILL | SEC-AZ-1、FILE-1 ~ FILE-5、WEB-4.4、AUDIT-1、AUDIT-3、INT-1、INT-2 |
| SD-TRUST（KYC） | SEC-AZ-4、FILE-1 ~ FILE-4、AUDIT-1 |
| SD-CARE（照護媒體、毛孩頭像） | SEC-FILE-1 ~ FILE-3、AUDIT-1 |
| SD-SUPPLY（收款帳號、保母頭像） | SEC-AUTH-6.3、AZ-3、DATA-1、DATA-2、FILE-1、FILE-3、LOG-1、AUDIT-1 ~ AUDIT-3 |
| SD-NOTIFY | SEC-WEB-5.3、WEB-6.3 |
| 部署清單 | SEC-AUTH-4（cookie 經 Firebase 轉發）、DATA-2、DATA-3、SECRET-1、FILE-1.2、WEB-1、WEB-2、WEB-4.4、INPUT-6（入口跳數實測）、LOG-3、INT-1、SCA-1 ~ SCA-4 |

---

## 15. 需回寫上游的項目

本文件不在此另訂衝突規則；下列項目需由上游文件擁有者修改（Approved 文件依 doc-conventions 判斷是否退回 In Review）。

| # | 上游文件 | 內容 | 對應 |
|---|---------|------|------|
| U-1 | SD-GLOBAL-SPEC §1.2 | 前端由 Firebase Hosting 提供，後端映像不含前端；`SecurityConfig` 靜態檔 permitAll 移除 | X-5、G-21 |
| U-2 | SD-GLOBAL-SPEC §2.6 | traceId 只接受 `^[0-9a-f]{32}$`，否則重新產生 | SEC-LOG-5 |
| U-3 | SD-GLOBAL-SPEC §6.2 | prod 停用 springdoc | CQ-31 |
| U-4 | SD-GLOBAL-SPEC §3.4、§2.6；`SecurityConfig` | 匿名白名單逐條列出路由樣板；AU 禁止白名單外 `permitAll()`；URL 層不再對整個 `/api/auth/**` 設 permitAll，只放行匿名 auth 端點，需身分的 auth 端點走 `authenticated()`（匿名 401，不落到方法層的 403 `ROLE_MISMATCH`） | SEC-AZ-2 |
| U-5 | baseline §3.7 | 新增跨模組碼 429 `RATE_LIMITED` | CQ-28 |
| U-6 | SD-FULFILL DF2 | 檢討 bucket CORS 是否保留 run.app origin（前端只由 Firebase 提供時，瀏覽器直傳不會來自 run.app） | SEC-WEB-4.4 |
| U-7 | SD-FULFILL 決策 3、SD-ORDER §3.6.6 | 簽名 GET 網址加 `response-content-type`（等於判定格式）與 `response-content-disposition=inline` | SEC-FILE-3 |
| U-8 | SD-ORDER（快照加密）、SD-SUPPLY | AES-GCM AAD 綁定所在列；`profiles` 新增金鑰版本欄位 | CQ-48、CQ-13 |
| U-9 | PRD-000（Draft）／ SD-IDENTITY | 密碼規則、OTP 與重設憑證參數、鎖定碼、email 維度計數、Google `email_verified` 綁定規則、`@e2e-journey.test` 網域禁止註冊 | SEC-AUTH-5、8–11、CQ-39、CQ-40、CQ-45、CQ-51 |
| U-10 | PRD-017（Draft）／ SD-TRUST | KYC 可讀者改為本人與 ADMIN；後端 magic bytes 與大小檢查 | X-2、SEC-FILE-3、FILE-4 |
| U-11 | PRD-021、PRD-013／ SD-CARE | 照護媒體、毛孩頭像改私有物件與簽名網址；bucket 拆分 | G-2、G-3、CQ-17 |
| U-12 | SD-FRONTEND-SPEC §2 | refresh 改 cookie 後的跨分頁換發流程（CQ-3 寬限）與 token 不寫 localStorage；E2E 以 Playwright `request` API 直接呼叫 refresh / logout 時須自行帶 `Origin`（預設不帶，會被 SEC-AUTH-4.6 擋成 403） | SEC-AUTH-4 |
| U-13 | PRD-005、SD-ORDER | 飼主對同一保母同時 `PENDING` 訂單上限（若採用） | CQ-43 |
| U-14 | `00-NFR-OVERVIEW.md` | NFR-003 段同步本文件 | X-7 |
| U-15 | `deploy.yml`、`security-scan.yml`（部署清單） | 補 `APP_CRYPTO_KEY` 注入、Node 24、Actions 釘 SHA、掃描工具調整 | G-1、G-17、G-18 |

---

## 16. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 全文重寫：舊稿 12 項要求改寫為可驗收條件，新增依賴漏洞管理、資安標頭、token 存放、secret 與 log 遮蔽、內部端點、上傳檔案內容檢查、請求解析防護、CORS 與開放重導、錯誤回應、稽核同交易等要求，共 44 條；新增現況落差、來源矛盾、CQ-1 ~ CQ-38。狀態維持 Draft | 全面重建；使用者 2026-11-22 同意補強安全缺口 |
| 2026-11-22 | 依審查第 1 輪（`docs/reviews/NFR-003.review.md`，2 阻擋、20 重要、11 建議）修正：新增 SEC-AUTH-8 ~ 11（OTP / 重設憑證、token 雜湊存放、Google `email_verified`、密碼 72 bytes）、SEC-DATA-3（Supabase）、SEC-INPUT-7、8（寄信上限、SSRF）、SEC-LOG-5（log 注入、traceId 格式）、SEC-SCA-4（Actions 釘 SHA），共 52 條；SEC-FILE-1 納入照護媒體與毛孩頭像、日誌未送出改 404、bucket 拆分順序；SEC-FILE-3 對齊 SD-FULFILL 決策 3；SEC-WEB-4 對齊 DF2；SEC-INPUT-2 對齊 GLOBAL §3.4 例外；SEC-AUTH-3、CQ-2、CQ-3 改 token family 與絕對上限；SEC-AUTH-4 補 cookie 範圍、清除與 Origin；SEC-AUTH-5 改 email 鍵、假雜湊、不延長；SEC-AUDIT-1 補外部 I/O 與鎖定稽核；SEC-AUDIT-3 改 append-only Repository；SEC-INPUT-4 測試改為實際進入 regex；子項改 `[k]` 編號；X-1 標為已決；審查 R-CQ-1 ~ 8 併入 CQ（CQ-2、17、21、39 ~ 43），新增 CQ-45 ~ 52；§11 改寫（Spring Boot 4.0.10、`deploy.yml:47` Node 20、照護媒體公開、客戶端 Content-Type、token 明文存放、traceId、Actions、DB 帳號）；新增 §15 需回寫上游的項目。狀態維持 Draft | 審查第 1 輪；使用者決定 2026-11-22（X-1） |
| 2026-11-22 | 依審查第 2 輪（N1–N9，0 阻擋、4 重要、5 建議）修正：SEC-AZ-2.2 改為 URL 層只放行匿名 auth 端點、需身分的 auth 端點走 `authenticated()`（匿名 401），併入 U-4（N1）；CQ-30 可信網段限實測的 Firebase 轉發出口、排除 GCP 客戶網段、最多退一項、IPv4 / IPv6 皆適用，SEC-INPUT-6.6 改為 XFF 樣本單元測試加部署後兩條路徑 MAN（N2）；CQ-3 同一顆舊 token 寬限內最多換發 3 次、兄弟 token 標 `SUPERSEDED`（N3）；CQ-11、CQ-12 改為 ADMIN 獨立無密碼帳號、註冊憑證由使用者本機產生只交雜湊，新增 CQ-44（密碼登入 ADMIN 回一般 401），CQ-10、CQ-24 補 RP ID 只用 `web.app`、`firebaseapp.com` 轉址或排除，SEC-AZ-4.4 同步（N4）；SEC-AUTH-4.4 限需登入端點、U-12 補 E2E 帶 `Origin`（N5）；SEC-AUTH-5.1 補計數列過期清除與共用正規化函式（N6）；CQ-44 補用，CQ-1 ~ CQ-52 連號共 52 條（N7）；SEC-AUTH-11 錯誤 payload 帶 `maxBytes=72`、前端同規則（N8）；SEC-INPUT-6.7 補登入後頁面超過 30 支 API 的門檻（N9）。要求仍 52 條，狀態維持 Draft | 審查第 2 輪 |
| 2026-11-22 | 狀態改 Approved；CQ-1~52 全數採建議預設值 | 使用者核准 |
