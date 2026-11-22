# NFR-003: 安全性 (Security) 規格書

| 項目 | 內容 |
|------|------|
| 對應需求 | 全系統；重點 PRD-000、001（帳號身分）、005、007、016、027（訂單 / 付款 / 排程）、008、022（履約 / 日誌媒體）、017、020（KYC / ADMIN）、025（收款帳號） |
| 負責人 | Will |
| 狀態 | Draft |
| 優先級 | P0 (Critical) |
| 引用基準 | `docs/sa/00-DOMAIN-BASELINE.md` §1.1–§1.2（角色、資源歸屬、403 / 404）、§2.2（方案即時查詢）、§3.3（收款帳號快照加密 CQ-72）、§3.6（同交易稽核、外部 I/O、冪等、排程）、§3.7（錯誤碼與判斷順序）、§7 CQ-75（收款帳號修改不重驗）；`docs/sd/SD-GLOBAL-SPEC.md` §1.2、§2.4、§2.6、§3.2、§3.4、§4.3、§6.3；`docs/sd/SD-ORDER.md` §3.6.6、§6.3、§8.3 D1–D4、D7；`docs/sd/SD-FULFILL.md` §3.3、§4.4、§8 設定表 |
| 更新日期 | 2026-11-22 |

> 本文件把安全要求寫成可驗收的條件。每條要求有編號（`SEC-{類別}-n`）、出處、落地 SD 與驗證方式；SD 撰寫時須在「AC 對應實作」或安全章節逐條引用編號。
> 沒有需求出處的數值一律標「依 CQ-n」，以 §13 待釐清事項的建議預設值暫代，使用者決定前不得當成定案（專案硬性規則 3）。
> §11「現況落差」以 2026-11-22 程式碼為證據，只說明落差，不代表正確答案。
> 本文件的所有限制**不依 SaaS 方案等級區分**：FREE / BASIC / PRO / ULTIMATE 完全相同（方案判斷本身的安全要求見 SEC-AZ-5）。

---

## 0. 讀法

### 0.1 欄位

| 欄位 | 說明 |
|------|------|
| 類型 | `保留`：舊稿 NFR-003（2026-10 前）與 `00-NFR-OVERVIEW.md` 已有、使用者 2026-11-22 指示保留並改寫為可驗收；`補強`：使用者 2026-11-22 同意補強的缺口 |
| 出處 | 需求文件、baseline、Approved PRD / SD、使用者決定；數值無出處者標 CQ |
| 落地 | 由哪份文件實作此要求：`GLOBAL`（SD-GLOBAL-SPEC）、`FRONT`（SD-FRONTEND-SPEC）、`IDENTITY`（SD-IDENTITY，尚未撰寫）、`ORDER`、`FULFILL`、`TRUST`（SD-TRUST，KYC）、`SUPPLY`（SD-SUPPLY，收款帳號）、`NOTIFY`（SD-NOTIFY）、`DEPLOY`（部署清單：`deploy.yml`、`firebase.json`、GCP 設定，雲端變更需使用者當次同意） |
| 驗證 | `IT`：後端整合測試（真 `SecurityConfig` filter chain、真 JWT，不用 `@WithMockUser`）；`AU`：ArchUnit 或靜態掃描測試；`E2E`：Playwright；`CI`：GitHub Actions 檢查；`MAN`：上線前手動檢查（附指令或觀察點） |

### 0.2 時間與單位

- 時間一律 UTC 儲存與比較；畫面顯示 Asia/Taipei（baseline §5）。本文件的「分鐘 / 秒」皆為伺服器時鐘的經過時間。
- 大小以位元組計：1 MB = 1,048,576 bytes。

---

## 1. 身分認證與 session（SEC-AUTH）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-AUTH-1 | 保留 | 密碼只存單向加鹽雜湊，不存明文、不可還原 | (1) 註冊後 `users.password_hash` 符合 BCrypt 格式 `^\$2[aby]\$[0-9]{2}\$`，cost ≥ 10（依 CQ-1），或 Argon2id 格式；(2) 任何 API 回應（含註冊、登入、取得個人資料）的 JSON 不含 `password`、`passwordHash` 欄位；(3) 同一密碼註冊兩個帳號，雜湊值不同 | PRD-000「密碼安全」；00-NFR-OVERVIEW；舊稿 §1 | IDENTITY | IT |
| SEC-AUTH-2 | 保留 | access token 有時效且內容最小 | (1) `exp − iat ≤ 86400` 秒（24 小時上限；實際值依 CQ-4）；(2) 過期、簽章被竄改、`alg=none` 的 token → 401 `UNAUTHENTICATED`；(3) payload 只含身分 id 與 `currentRole`，不含 `planType`、email 以外的個資；(4) 簽章金鑰 ≥ 256 bit，只經 Secret Manager 注入（SEC-SECRET-1） | 舊稿 §1；00-NFR-OVERVIEW；PRD-000 AC-3；GLOBAL §3.2；baseline §3.7 | IDENTITY | IT |
| SEC-AUTH-3 | 保留 | refresh 換發機制，且能撤銷 | (1) 以有效 refresh token 換發 → 200 取得新 access token；(2) 每次換發輪替 refresh token，舊 token 失效；已輪替的舊 token 再被使用的處理依 CQ-3；(3) refresh token 有效期依 CQ-2，過期 → 401；(4) 單一 session：同帳號重新登入或切換角色後，前一組 refresh token → 401（PRD-000）；(5) 「登出所有裝置」、改密碼、停用帳號後，舊 refresh token → 401，舊 access token 最遲在 CQ-4 的時限後 → 401 | 舊稿 §1；00-NFR-OVERVIEW；PRD-000「單一 session」 | IDENTITY | IT |
| SEC-AUTH-4 | 補強 | token 不可被頁面 JavaScript 讀到（目標）；過渡期以 CSP 降低 XSS 竊取風險 | 依 CQ-5 預設：(1) 登入、換發回應的 body 不含 refresh token，改以 `Set-Cookie` 下發，屬性含 `HttpOnly`、`Secure`、`SameSite=Strict`；(2) E2E 登入後檢查 `localStorage`、`sessionStorage` 皆無 `refreshToken` 鍵，`document.cookie` 讀不到 refresh token；(3) access token 只放記憶體，重新整理頁面以 refresh cookie 換發；(4) refresh 端點檢查 `Origin` 屬於 CORS 白名單（SEC-WEB-4），不符 → 403 `FORBIDDEN`。過渡期（仍存 localStorage）上線前，SEC-WEB-2 的 CSP 必須已強制生效 | 使用者 2026-11-22（補強範圍）；CQ-5 | IDENTITY、FRONT、DEPLOY | IT、E2E |
| SEC-AUTH-5 | 保留 | 暴力破解防護：連續登入失敗鎖定 | (1) 同一帳號連續 5 次密碼錯誤後，第 6 次起（含正確密碼）回 429 `ACCOUNT_LOCKED`，畫面「嘗試次數過多，請於 10 分鐘後再試」；(2) 第 5 次失敗起算滿 10 分鐘後，正確密碼可登入，計數歸零；(3) 成功登入使計數歸零；(4) 同帳號 10 個錯誤密碼請求併發送出，鎖定仍成立（計數不遺漏）；(5) 鎖定時寫一筆 WARN log 與帳號安全稽核（SEC-AUDIT-2），不含輸入的密碼；(6) IP 維度是否另行鎖定依 CQ-6；不存在的 email 與密碼錯誤的回應依 CQ-7 | PRD-000 AC-4、例外表；00-NFR-OVERVIEW；舊稿 §1 | IDENTITY | IT |
| SEC-AUTH-6 | 保留 | 敏感操作再驗證 | (1) 改密碼須帶目前密碼；缺漏或錯誤 → 不變更、回 4xx（碼由 SD-IDENTITY 定），錯誤是否計入 SEC-AUTH-5 依 CQ-8；(2) 綁定生物辨識（WebAuthn）前須再驗證，方式依 CQ-9；(3) **修改收款帳號不要求再驗證**（baseline CQ-75、PRD-025 BR-10），改以同交易 `enqueue` 不可關閉的「收款帳號已變更」通知作為盜改偵測，驗收沿用 PRD-025 AC-1.6；(4) 改密碼成功 → 依 SEC-AUTH-3 (5) 撤銷 session | PRD-000「敏感操作驗證」；舊稿 §3；baseline CQ-75；PRD-025 BR-8、BR-10 | IDENTITY、SUPPLY | IT |
| SEC-AUTH-7 | 補強 | WebAuthn 防重放與來源檢查 | 依 CQ-10：(1) 每個 challenge 只能用一次，簽發後逾時即失效；同一 assertion 重送 → 401；(2) 只接受白名單 origin 與 RP ID（正式網域；local profile 另加 `localhost`），其他 origin 的 assertion → 401；(3) 裝置不支援時前端隱藏入口（NFR-005），不影響密碼登入 | PRD-000 D；使用者 2026-11-22（補強範圍）；CQ-10 | IDENTITY、FRONT | IT、E2E |

---

## 2. 授權與角色隔離（SEC-AZ）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-AZ-1 | 保留 | 呼叫者身分一律取自 token；越權存取依統一碼拒絕（防 BOLA） | 重建模組的每支需登入端點至少 4 個案例：本人 → 2xx；他人資源 → 403 `FORBIDDEN`；錯角色 → 403 `ROLE_MISMATCH`；無 token → 401 `UNAUTHENTICATED`；資源不存在 → 404 `NOT_FOUND`。多帶舊身分參數（`sitterId`、`ownerId`）被忽略。判斷順序依 baseline §3.7 | 舊稿 §3；baseline §1.2、§3.7；GLOBAL §3.4 | GLOBAL、各模組 SD | IT |
| SEC-AZ-2 | 補強 | 每支端點都明確宣告存取規則，不得漏掛 | AU 測試掃描所有 controller handler：不在 `SecurityConfig` 匿名白名單（公開頁、`/api/auth/**` 的匿名端點、`/actuator/health`）且不在 `/api/internal/**` 者，必須掛 `@PreAuthorize`；違反即測試失敗。匿名白名單變更須同步本表與 SD-GLOBAL-SPEC | GLOBAL §3.4（漏掛 `@PreAuthorize` 以 0000 身分執行的事故）；使用者 2026-11-22 | GLOBAL | AU |
| SEC-AZ-3 | 保留 | 飼主與保母的私有資料互相隔離 | OWNER token 打保母私有端點（帳務總覽、收款帳號、報價、核對收款）→ 403 `ROLE_MISMATCH`；SITTER token 打飼主私有端點（付款憑證上傳、確認結案）→ 403 `ROLE_MISMATCH`；同帳號切換角色後以新 token 才能操作另一角色 | 舊稿 §3；baseline §1.1 | ORDER、SUPPLY、IDENTITY | IT |
| SEC-AZ-4 | 保留 | ADMIN 為獨立權限，不能經切換取得，範圍受限 | (1) 切換角色目標為 ADMIN → 403；(2) ADMIN 端點以 OWNER / SITTER token 呼叫 → 403 `ROLE_MISMATCH`；(3) ADMIN 讀取範圍外訂單（非 `DISPUTED` 且從未有 ADMIN 待辦）→ 403 `FORBIDDEN`；作廢（O16）無 OPEN 首次付款待核對待辦 → 依 SD-ORDER 錯誤碼拒絕；(4) ADMIN 帳號開通方式依 CQ-11，ADMIN 登入是否要求第二因素依 CQ-12 | 舊稿 §3；baseline §1.1、§1.2、CQ-8、CQ-84 | IDENTITY、ORDER、TRUST | IT |
| SEC-AZ-5 | 補強 | 方案權限即時判斷，不放進 token | (1) JWT 不含 `planType`；(2) 保母由 PRO 降為 BASIC 後，下一個請求呼叫 PRO 功能（例：報價調價）→ 403 `PLAN_REQUIRED`，不需重新登入；FREE / BASIC 呼叫 PRO / ULTIMATE 功能、BASIC / PRO 呼叫 ULTIMATE 專屬功能（白名單）一律 403 `PLAN_REQUIRED`；(3) 訂閱逾期或停權 → 新生意操作 403 `SUBSCRIPTION_LOCKED`（baseline §2.2） | GLOBAL §3.1、§3.2；baseline §2.1、§2.2 | GLOBAL、各模組 SD | IT |

---

## 3. 資料加密與 Secret（SEC-DATA、SEC-SECRET）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-DATA-1 | 保留 | 敏感個資欄位以 AES-256 加密存放 | 適用：保母即時收款帳號（`profiles.bank_account_info`）、CHARGE payment 的收款帳號快照（`payments.payee_account_snapshot`）；日後若儲存身分證號比照。(1) 以 SQL 直接讀取欄位值，不含明文帳號（PRD-025 AC-1.5）；(2) 演算法 AES-256-GCM，每次加密使用隨機 96-bit IV，同一明文加密兩次密文不同；(3) 竄改密文任一位元組 → 解密失敗 → 500 `INTERNAL_ERROR` 並記 ERROR log，不回傳部分欄位 | 舊稿 §2；00-NFR-OVERVIEW；baseline §3.3、CQ-72；PRD-025 BR-4；PRD-007 BR-2；SD-ORDER §8 外部服務表 | SUPPLY、ORDER | IT |
| SEC-DATA-2 | 保留 | 加密金鑰與業務資料隔離、無預設值 | (1) `APP_CRYPTO_KEY` 為 32 bytes（base64）；(2) 受版控的 `application*.yml` 不含預設值，缺值時應用程式啟動失敗（啟動測試：不給金鑰 → ApplicationContext 失敗）；(3) prod 只經 `deploy.yml` 的 `--set-secrets` 自 Secret Manager 注入；(4) 金鑰不存在資料庫、不進 log；(5) 輪替方式依 CQ-13 | 舊稿 §2；baseline CQ-72；PRD-025 BR-4；SD-ORDER §8.3 D1 | ORDER、SUPPLY、DEPLOY | IT、MAN |
| SEC-SECRET-1 | 補強 | 所有 secret 只經 Secret Manager 注入，不得有預設值、不得外洩 | 適用：`APP_SECURITY_JWT_SECRET`、`INTERNAL_CRON_SECRET`、`APP_CRYPTO_KEY`、`SPRING_DATASOURCE_PASSWORD`、`RESEND_API_KEY`。(1) CI 掃描 `backend/src/main/resources/application*.yml`：上列名稱以 `${NAME:` 形式帶預設值者 0 筆；測試值只放 `backend/src/test/resources`；(2) `deploy.yml` 中上列名稱只出現在 `--set-secrets`，不出現在 `--set-env-vars`；(3) prod 缺任一值 → 啟動失敗（`RESEND_API_KEY` 依 CQ-14）；(4) 不出現在 API 回應、log（SEC-LOG-1）、commit；commit 掃描依 CQ-15 | 專案硬性規則 6（使用者規則）；GLOBAL §1.2；baseline CQ-72；SD-ORDER §8.3 D1、D2；使用者 2026-11-22 | GLOBAL、DEPLOY | CI、IT、MAN |

---

## 4. 檔案與儲存（SEC-FILE）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-FILE-1 | 保留 | KYC 證件、付款 / 退款憑證、照護日誌媒體一律為私有物件 | (1) 以未簽名網址 `https://storage.googleapis.com/{bucket}/{objectKey}` 匿名 GET → 403；(2) bucket 啟用 uniform bucket-level access，public access prevention = enforced（依 CQ-17；`gcloud storage buckets describe` 可觀察）；(3) API 回應不含未簽名的物件網址（回應中的 GCS 網址皆帶 `X-Goog-Signature`）；(4) 可讀者依 baseline §1.2：付款 / 退款憑證 = 該 payment 雙方與可讀範圍內 ADMIN；KYC = 本人與 ADMIN；日誌媒體 = 該訂單 sitter、已送出後的 owner 與可讀範圍內 ADMIN；範圍外 → 403 `FORBIDDEN`，且不簽發網址 | 舊稿 §2；PRD-017「個資保護」；PRD-007 BR-17；PRD-022 BR-18；baseline §1.2 | ORDER、FULFILL、TRUST、DEPLOY | IT、MAN |
| SEC-FILE-2 | 保留 | 檢視只用每次授權後產生的短效簽名網址 | (1) 付款 / 退款憑證：`X-Goog-Expires=300`（PRD-007 BR-17）；(2) 照護日誌媒體讀取：`X-Goog-Expires=900`（PRD-022 BR-18）；(3) 照護日誌上傳 PUT 網址：TTL = min(900 秒, 上傳槽位剩餘時間)，附 `x-goog-content-length-range` 與 `x-goog-if-generation-match: 0`（SD-FULFILL §3.3）；(4) KYC 證件：依 CQ-18；(5) 簽名網址不寫入 DB、不寫入冪等回放 body | 舊稿 §2；PRD-007 BR-17；PRD-022 BR-18；SD-ORDER §3.6.6；SD-FULFILL §3.3；baseline §3.6 | ORDER、FULFILL、TRUST | IT |
| SEC-FILE-3 | 補強 | 以檔案內容（magic bytes）判斷格式，不信任宣告 | (1) 格式白名單：付款 / 退款憑證 JPEG、PNG、WebP；日誌照片 JPEG、PNG、WebP；日誌影片 MP4、MOV；KYC JPEG、PNG（PRD-017）；SVG、HTML、PDF 一律不在白名單；(2) 內容為 HTML 但宣告 `Content-Type: image/jpeg`、副檔名 `.jpg` → 422 模組碼（憑證 `PROOF_FILE_TYPE_UNSUPPORTED`、日誌 `CARE_LOG_MEDIA_TYPE_UNSUPPORTED`、KYC 由 SD-TRUST 定），且不留下 GCS 物件；(3) 儲存物件的 `Content-Type` 由伺服器依判定格式設定，不沿用客戶端宣告；(4) 後端只讀檔頭與容器 metadata，不對圖片做完整解碼 | 使用者 2026-11-22（補強範圍）；SD-ORDER §3.6.6；SD-FULFILL §4.4、`MediaInspector`；PRD-017 例外表 | ORDER、FULFILL、TRUST | IT |
| SEC-FILE-4 | 補強 | 檔案大小上限在伺服器端強制 | (1) 付款 / 退款憑證 ≤ 5,242,880 bytes，超過 → 422 `PROOF_FILE_TOO_LARGE`；(2) 日誌照片 < 1,048,576 bytes、影片 < 52,428,800 bytes（PRD-022 BR-12、BR-13），直傳 GCS 以 `x-goog-content-length-range` 強制，確認時以實際物件大小再驗；(3) KYC ≤ 5 MB（PRD-017），後端也要檢查，不只前端；(4) 經 Cloud Run 的 multipart：單檔 20 MB、整個請求 21 MB，超過 → 400 `VALIDATION_FAILED`；(5) 影片 `moov` 緩衝 ≤ 8 MB，超過視為格式不支援 | PRD-007；PRD-022 BR-12、BR-13；PRD-017；SD-ORDER §8.3 D4；SD-FULFILL §8 | ORDER、FULFILL、TRUST、GLOBAL | IT |
| SEC-FILE-5 | 保留 | 前端只顯示後端簽名網址，不渲染使用者提供的網址 | (1) 憑證與媒體只以簽名網址放進 img / video 元素；使用者輸入的網址不做成可點擊連結、不放入 `src`；(2) 退款憑證由保母上傳圖片，不接受自填外部網址（舊稿 §2）；(3) 照片位置資訊（EXIF GPS）處理依 CQ-19 | 舊稿 §2（SD-016 §2.5）；使用者 2026-11-22 | FRONT、ORDER、FULFILL | E2E、IT |

---

## 5. 傳輸、資安標頭、CORS 與前端導頁（SEC-WEB）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-WEB-1 | 保留 | 全站強制 HTTPS | (1) `curl -sI http://{PROJECT_ID}.web.app/` → 301 或 308 導向 `https://`；Cloud Run `*.run.app` 同；(2) 後端連 Supabase 使用 `sslmode=require`（等級依 CQ-20）；(3) 對 Resend、GCS、Google 的呼叫只用 `https://` | 舊稿 §2；00-NFR-OVERVIEW | DEPLOY | MAN |
| SEC-WEB-2 | 補強 | 前端頁面資安標頭由 Firebase Hosting 下發 | `firebase.json` 的 `hosting.headers` 對 `**` 設定（值依 CQ-21、CQ-23）：`Content-Security-Policy`（含 `script-src` 不允許 `'unsafe-inline'` 與 `'unsafe-eval'`、`frame-ancestors 'none'`、`object-src 'none'`、`base-uri 'self'`）、`Strict-Transport-Security`、`X-Content-Type-Options: nosniff`、`Referrer-Policy`、`X-Frame-Options: DENY`、`Permissions-Policy`。驗收：(1) `curl -sI` 首頁、任一 SPA 深層路徑（rewrite 到 index.html）、`/assets/*.js` 皆帶上列標頭；(2) CI 測試解析 `firebase.json`，缺任一標頭即失敗；(3) E2E 黃金路徑（登入、預約、上傳憑證、看日誌媒體、Google 登入、WebAuthn）監聽 `securitypolicyviolation` 事件，違規數 = 0；上線方式依 CQ-22 | 使用者 2026-11-22（補強範圍） | FRONT、DEPLOY | CI、E2E、MAN |
| SEC-WEB-3 | 補強 | API 回應資安標頭由 Spring Security 下發 | 所有 `/api/**` 回應（含 2xx、401、403、404、500，含直接打 `*.run.app` 者）帶：`X-Content-Type-Options: nosniff`、`Cache-Control: no-store`、`X-Frame-Options: DENY`、`Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`、`Referrer-Policy: no-referrer`、`Strict-Transport-Security`（值同 CQ-23）。Cloud Run 在前端終結 TLS，HSTS 不得依賴 `request.isSecure()`（須信任 forwarded 標頭或對所有請求輸出）。驗收：IT 以 MockMvc 斷言四種狀態碼的標頭；上線後 `curl -sI https://{PROJECT_ID}.web.app/api/sitters/{id}/plans` 與 run.app 網址皆帶 HSTS | 使用者 2026-11-22（補強範圍） | GLOBAL | IT、MAN |
| SEC-WEB-4 | 補強 | CORS 只允許白名單來源 | (1) 不得使用 `*`；prod 白名單依 CQ-24，local profile 另加 `http://localhost:5173`；(2) `Origin: https://evil.example` 的預檢請求 → 回應不含 `Access-Control-Allow-Origin`；白名單來源 → 回應該來源原值；(3) 允許標頭、方法逐一列舉；改用 refresh cookie（SEC-AUTH-4）時 `allowCredentials=true` 只配合精確來源；(4) 照護日誌直傳用的 GCS bucket CORS 只允許同一白名單、方法限 `PUT`、`GET` | 使用者 2026-11-22（補強範圍）；SD-FULFILL §4.4（bucket CORS） | GLOBAL、FULFILL、DEPLOY | IT、MAN |
| SEC-WEB-5 | 補強 | 開放重導防護：前端導頁只允許站內相對路徑 | (1) 由網址參數（例 `/login?redirect=`）或 API 回應決定的導頁目標，須以 `/` 開頭、第二字元不是 `/` 也不是 `\`、不含控制字元與空白，且 `new URL(target, location.origin).origin === location.origin`；不符 → 改導預設頁；(2) E2E / 單元測試：`redirect` 為 `//evil.example`、`/\evil.example`、`https://evil.example`、`javascript:alert(1)`、`/%09/evil.example`、`%2F%2Fevil.example` 時，登入後 URL 的 origin 仍為站內；(3) 後端產生的 Email 連結一律以設定值 `app.frontend-base-url` 組成，不取自請求的 `Host`、`X-Forwarded-Host` | 使用者 2026-11-22（補強範圍） | FRONT、NOTIFY、IDENTITY | E2E、IT |
| SEC-WEB-6 | 保留 | 使用者內容在輸出端轉義（取代舊稿「輸入自動過濾」） | (1) 輸入端不改寫字串內容（只依 GLOBAL §2.7 去頭尾空白），輸出端轉義；(2) 前端禁止以 `dangerouslySetInnerHTML`、`innerHTML` 呈現使用者內容：ESLint `react/no-danger` 設為 error，CI lint 失敗即擋；(3) 顯示名稱含 script 標籤字串（含左右角括號）時，通知 Email HTML 中的角括號輸出為 `&lt;`、`&gt;`，不形成可執行標籤 | 舊稿 §3「輸入安全檢核」；GLOBAL §2.7 | FRONT、NOTIFY | CI、IT |

---

## 6. 請求解析與濫用防護（SEC-INPUT）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-INPUT-1 | 補強 | 請求 body 有上限，超過不讀入記憶體 | (1) 帶 `Idempotency-Key` 的 JSON：> 64 KB → 400 `VALIDATION_FAILED`（`field=body`、`maxBytes`），chunked 請求最多讀 64 KB + 1 bytes（GLOBAL §2.4）；(2) 其他 JSON：全域上限依 CQ-25，超過 → 400 `VALIDATION_FAILED`，以串流計數中止，不先整包讀入；(3) multipart 上限見 SEC-FILE-4 (4) | GLOBAL §2.4；SD-ORDER §8.3 D4；使用者 2026-11-22 | GLOBAL | IT |
| SEC-INPUT-2 | 補強 | 型別嚴格，拒絕浮點冒充整數與客戶端決定的伺服器欄位 | (1) `spring.jackson.deserialization.accept-float-as-int=false`：整數欄送 `100.5` 或 `100.0` → 400 `VALIDATION_FAILED`；(2) 金額欄以 `BigDecimal` 接收並驗證為整數（GLOBAL §2.6）；(3) Request DTO 不得宣告伺服器決定的欄位（`id`、`ownerId`、`sitterId`、`status`、`version`、金額計算結果）；多送時被忽略（GLOBAL §3.4、baseline CQ-35） | GLOBAL §2.6、§3.4；baseline §3.6 CQ-35；使用者 2026-11-22 | GLOBAL、各模組 SD | IT、AU |
| SEC-INPUT-3 | 補強 | 解析與數值運算不被惡意輸入耗盡資源 | (1) 不得調高 Jackson 3 `StreamReadConstraints` 預設值；巢狀 2,000 層的 JSON → 400 `VALIDATION_FAILED`（不得 500 或 StackOverflowError）；(2) 金額欄送 `1e999999999` → 400 `VALIDATION_FAILED`，回應時間 < 1 秒：做任何 `BigDecimal` 運算（`setScale`、`toBigIntegerExact`、`stripTrailingZeros`）前，先以 `precision()` 與 `scale()` 檢查範圍（上限依 CQ-26）；(3) 文字欄先檢查字數上限（GLOBAL §2.7）再做其他處理 | 使用者 2026-11-22（補強範圍）；GLOBAL §2.6、§2.7 | GLOBAL | IT |
| SEC-INPUT-4 | 補強 | ReDoS 防護 | (1) 伺服器端對使用者輸入套用正規表示式前，先限制輸入長度；(2) 套用在使用者輸入的樣式不得含巢狀量詞（例 `(a+)+`、`(\w*)*`）或可重疊的選擇分支加量詞；(3) 每個以 regex 驗證的欄位，送 100,000 字元的惡意字串 → 400 且回應時間 < 1 秒 | 使用者 2026-11-22（補強範圍） | GLOBAL、各模組 SD | IT、AU |
| SEC-INPUT-5 | 補強 | 列表查詢有筆數上限 | 分頁參數 `size` 超過上限（依 CQ-27）→ 400 `VALIDATION_FAILED`；未帶 `size` 時用預設值；不提供無上限的「全部匯出」端點給一般使用者 | 使用者 2026-11-22（補強範圍） | GLOBAL、各模組 SD | IT |
| SEC-INPUT-6 | 保留 | 全域限流 | (1) 未登入：同一來源 IP 對 `/api/**` 每分鐘最多 60 次（滑動或固定 60 秒視窗皆可），第 61 次 → 429（碼依 CQ-28）並帶 `Retry-After`（秒）；(2) 登入使用者：以 token 身分計數，上限依 CQ-29；(3) `/api/internal/**` 不套用；(4) 不同 IP 互不影響；帶有效 token 的請求不計入匿名額度；(5) IP 取法與多實例計數依 CQ-30；(6) 限制與方案等級無關（FREE / BASIC / PRO / ULTIMATE 相同）。驗收：IT 同 IP 匿名 60 秒內打 61 次 `GET /api/sitters/{id}/plans` → 前 60 次 200、第 61 次 429 | 舊稿 §3；使用者 2026-11-22 | GLOBAL | IT |

---

## 7. 錯誤回應與日誌（SEC-LOG）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-LOG-1 | 補強 | log 遮蔽敏感值 | 不得出現在任何 log（含 DEBUG）：`Authorization`、`Cookie`、`Set-Cookie`、`X-Internal-Secret` 標頭值；access / refresh token；密碼（含目前密碼）；WebAuthn 原始 credential；完整收款帳號與戶名（只可「銀行代碼 + 帳號後五碼」，PRD-025 BR-8）；簽名網址的 query 字串；email 依 CQ-16。驗收：IT 以 log 擷取跑「登入成功 / 失敗、換發、改密碼、修改收款帳號、建立 payment 快照、內部端點錯誤 secret、取得簽名網址」，擷取內容不含測試用的密碼、token、secret、帳號明文、`X-Goog-Signature=` | 專案硬性規則 6；PRD-025 BR-8；SD-ORDER §8.3 D3；使用者 2026-11-22 | GLOBAL、IDENTITY、SUPPLY、ORDER | IT |
| SEC-LOG-2 | 補強 | 錯誤回應不洩漏內部細節 | (1) 所有錯誤 body 只含 `code`、`message`、`payload`、`traceId`、`timestamp`（GLOBAL §2.6）；(2) `message` 為固定文案，不拼接框架、JDK、JDBC、第三方例外的訊息；(3) 不含堆疊、例外類別名、SQL、約束名、表名、檔案路徑、主機名；(4) 未處理例外 → 500 `INTERNAL_ERROR` + `traceId`，細節只進 ERROR log。驗收：IT 觸發 (a) 訊息含 `SELECT` 的未處理 RuntimeException、(b) SQLSTATE 23514、(c) 不合法 JSON、(d) `AuthenticationException`、(e) `IllegalArgumentException`，回應 body 皆不含原例外訊息、約束名與 Jackson 解析訊息 | GLOBAL §2.6；baseline §3.7「非業務錯誤」；使用者 2026-11-22 | GLOBAL | IT |
| SEC-LOG-3 | 補強 | 管理與文件端點不對外暴露 | (1) Actuator 只暴露 `health`，`show-details=never`（GLOBAL §6.3）：`GET /actuator/env` → 404；(2) prod 的 OpenAPI / Swagger 依 CQ-31：預設 `GET /v3/api-docs`、`/swagger-ui.html` 帶任何有效 token 仍 404 | GLOBAL §6.2、§6.3；`SecurityConfig` 註解（prod 不公開規格書） | GLOBAL、DEPLOY | IT、MAN |
| SEC-LOG-4 | 補強 | 安全事件可追查 | 以下事件記 WARN，含 `traceId`、路徑、token 身分（若有）、來源 IP，不含 secret：登入鎖定、內部端點驗證失敗、refresh token 重用（CQ-3）、限流觸發（同一 IP 每分鐘最多記 1 筆）、加密解密失敗（ERROR）。驗收：IT 擷取 log 斷言欄位存在 | 舊稿 §1「記錄異常嘗試」；GLOBAL §1、§6.3 | GLOBAL、IDENTITY | IT |

---

## 8. 稽核紀錄（SEC-AUDIT）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-AUDIT-1 | 保留 | 稽核紀錄與被稽核的操作同交易，不可單獨寫入 | (1) 訂單、付款、變更、行程轉移的 `order_logs` 與狀態變更同一交易（baseline §3.6）；收款帳號稽核與帳號更新同交易（PRD-025）；帳號安全稽核（註冊、改密碼、停用、登出所有裝置、KYC 核准 / 退件、停權、ADMIN 作廢 / 調解）與主操作同交易；(2) 稽核寫入方法的交易傳播為 `MANDATORY`，無交易呼叫即拋例外；禁止 `REQUIRES_NEW`、`@Async`、交易提交後 hook 寫稽核；(3) IT：注入失敗使主交易回滾 → 稽核 0 列；成功 → 恰 1 列。例外：登入失敗計數（SEC-AUTH-5）必須在登入回滾後留存，屬計數器不屬稽核，可用獨立交易 | baseline §3.6；GLOBAL §4.3；PRD-025 §4.1；使用者 2026-11-22 | ORDER、FULFILL、SUPPLY、IDENTITY、TRUST | IT、AU |
| SEC-AUDIT-2 | 保留 | 稽核內容足以追查且不含敏感明文 | 每列含：操作者（userId；系統為 `SYSTEM`）、時間（UTC `timestamptz`）、動作碼、目標 id、變動欄位與前後值摘要；收款帳號只存「銀行代碼 + 帳號後五碼」；不存密碼、token、完整帳號、戶名；帳號安全類事件是否記 IP 依 CQ-32。覆蓋事件：身分狀態變更、權限異動（含 ADMIN 開通）、個資修改、訂單轉移（baseline §3.6） | 舊稿 §4；PRD-025 BR-8、AC-1.5；baseline §3.6 | 同上 | IT |
| SEC-AUDIT-3 | 保留 | 稽核紀錄不可抹除 | (1) 應用程式沒有對 `order_logs` 與各稽核表 UPDATE / DELETE 的程式路徑（AU：稽核 Repository 不得被 `delete*` 或對既有列 `save` 呼叫；實體標 `@Immutable`）；(2) 沒有任何 API 可修改或刪除稽核；(3) 交易相關稽核保存 ≥ 5 年（NFR-008）；DB 層防護與帳號註銷時的處理依 CQ-33 | 舊稿 §4；NFR-008 §3；GLOBAL §4.3 | ORDER、FULFILL、SUPPLY、IDENTITY | AU、IT |

---

## 9. 內部端點（SEC-INT）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-INT-1 | 補強 | `/api/internal/**` 只認 `X-Internal-Secret` | (1) 標頭缺漏、不符、只帶一般使用者 token、只帶 ADMIN token → 403 `FORBIDDEN`（GLOBAL §2.6 格式），不處理任何資料；(2) 比對使用常數時間比較（`MessageDigest.isEqual`）；(3) 驗證失敗只記路徑，log 不含標頭值；(4) secret 強度與輪替依 CQ-34。驗收：`InternalCronSecurityTest` 涵蓋 (1) 四種情況與 log 擷取 | SD-ORDER §6.3、§8.3 D3；PRD-027 AC-2.4；PRD-008 AC-2.6 | ORDER、FULFILL、DEPLOY | IT |
| SEC-INT-2 | 補強 | 測試支援端點不得觸及真實使用者資料 | `/api/internal/test-data/**` 與排程端點的 `orderId` 參數：目標訂單的 owner 或 sitter 不是 `@e2e-journey.test` 帳號 → 404 `NOT_FOUND`，不處理任何資料；Cloud Scheduler 自動觸發時排除測試帳號訂單 | SD-ORDER §6.3（使用者決定 2026-11-22）；SD-FULFILL §2.3 `CronScope` | ORDER、FULFILL | IT |

---

## 10. 依賴漏洞與平台支援期（SEC-SCA）

| ID | 類型 | 要求 | 驗收條件 | 出處 | 落地 | 驗證 |
|----|------|------|---------|------|------|------|
| SEC-SCA-1 | 補強 | CI 依賴漏洞掃描 | (1) `.github/workflows/security-scan.yml` 於 PR、push main、每週一 01:17 UTC 執行；(2) 前端 `npm audit --omit=dev --audit-level=high` 有 High 以上 → 失敗；dev 依賴只列報告；(3) 後端 osv-scanner 掃 `backend/pom.xml`（含傳遞依賴）有已知漏洞 → 失敗；(4) Dependabot：maven、npm 每週，GitHub Actions 每月；(5) 此 workflow 是否設為 PR 必要檢查依 CQ-35 | 使用者 2026-11-22（補強範圍；CI 已建） | DEPLOY | CI |
| SEC-SCA-2 | 補強 | 漏洞修補時限 | 自 CI 首次因該漏洞失敗或 Dependabot alert 建立起算：Critical、High 的修補期限依 CQ-36；Medium、Low 隨例行升級。暫時無法修補者在 osv-scanner 忽略設定逐筆寫原因與到期日，到期日距建立 ≤ 90 天（依 CQ-36），到期未處理 CI 恢復失敗 | 使用者 2026-11-22（補強範圍） | DEPLOY | CI、MAN |
| SEC-SCA-3 | 補強 | 框架與執行環境在官方開源支援期內 | (1) 上線日與營運期間，Spring Boot、Java、建置用 Node.js、PostgreSQL 版本皆在官方開源支援期內；(2) Spring Boot 4.0.x 開源支援至 2026-12-31（使用者提供），上線前須升到仍受支援的版本列；距 EOL 的緩衝依 CQ-37；(3) 每次發布前以 MAN 檢查表核對 `backend/pom.xml` parent 版本、Dockerfile 基底映像、CI `node-version` | 使用者 2026-11-22（補強範圍） | GLOBAL、DEPLOY | MAN、CI |

---

## 11. 現況落差（2026-11-22 程式碼證據）

| # | 落差 | 證據 | 對應要求 | 嚴重度 |
|---|------|------|---------|--------|
| G-1 | **prod 部署未注入 `APP_CRYPTO_KEY`**：`application.yml` 已移除預設值（缺值啟動失敗），但 `deploy.yml` 的 `--set-secrets` 只有 DB、JWT、INTERNAL_CRON_SECRET、Resend 四項。`--set-secrets` 會取代服務全部 secret 對映，推測下次部署 prod 啟動失敗（需確認 Cloud Run 現行設定；修正需建立 Secret Manager 項目，屬雲端變更，需使用者當次同意） | `.github/workflows/deploy.yml:97`；`backend/src/main/resources/application.yml:46` | SEC-DATA-2、SEC-SECRET-1 | 高 |
| G-2 | 全域限流未實作；只有 KYC 提交每小時 5 次 | `KycServiceImpl.checkRateLimit`；全專案無 rate limit filter | SEC-INPUT-6 | 高 |
| G-3 | `firebase.json` 無 `headers` 設定：無 CSP、HSTS、nosniff、Referrer-Policy、X-Frame-Options（Firebase 是否預設附 HSTS 需 `curl -sI` 確認） | `firebase.json` | SEC-WEB-2 | 高 |
| G-4 | access / refresh token 存 localStorage | `frontend/src/api/axiosClient.ts:62,102,118`、`pages/auth/LoginPage.tsx:32`、`contexts/RoleContext.tsx:44`、`contexts/manualRoleSwitch.ts:63`、`components/auth/RequireAuth.tsx:18`、`hooks/useCurrentUser.ts:30` | SEC-AUTH-4 | 高 |
| G-5 | CORS `allowedOrigins("*")` | `SecurityConfig.java:95` | SEC-WEB-4 | 中 |
| G-6 | 開放重導：`getSafeRedirectTarget` 用 `/^\/(?!\/)/`，`/\evil.example` 會通過；瀏覽器把 `\` 視為 `/`，實際導到外站 | `LoginPage.tsx:19-22`、`RegisterPage.tsx` | SEC-WEB-5 | 中 |
| G-7 | refresh token 換發時不輪替（回傳原 token）；有效期 7 天寫死常數，無出處 | `AuthService.java:81,263` | SEC-AUTH-3、CQ-2、CQ-3 | 中 |
| G-8 | 「登出所有裝置」、停用只刪 `refresh_tokens`，既有 access token 仍有效最多 24 小時 | `AuthService.java:361,386`；`application.yml:43` | SEC-AUTH-3 (5)、CQ-4 | 中 |
| G-9 | JWT 簽章金鑰在受版控的 `application.yml` 有公開預設值；prod 由 `application-prod.yml` 覆蓋，但 local、CI、任何漏設 prod profile 的環境用公開金鑰簽章 | `application.yml:42`；`application-prod.yml:24` | SEC-SECRET-1 | 中 |
| G-10 | 帳號類稽核以 `REQUIRES_NEW` 在主交易外寫入（主操作回滾時稽核仍留存，或反之）；且 `log.info` 印出稽核 details | `AuditLogService.java:24-26,42-44`；呼叫端 `AuthService.java:362,389`、`MediaRetentionServiceImpl.java:100` | SEC-AUDIT-1、SEC-LOG-1 | 中 |
| G-11 | 錯誤回應回顯例外訊息：`AuthenticationException` 拼接 `ex.getMessage()`；`IllegalArgumentException`、`IllegalStateException` 直接回 `ex.getMessage()`；未重建模組的 `DataIntegrityViolationException` 一律 400 `DATA_ERROR` | `GlobalExceptionHandler.java:60,171,192,197` | SEC-LOG-2 | 中 |
| G-12 | API 端 HSTS 推測未輸出：Spring Security 只對 secure 請求送 HSTS，Cloud Run 終結 TLS 且未設定 forwarded 標頭處理；Referrer-Policy、API CSP 未設定（需 `curl -sI` 驗證） | `SecurityConfig.java`；`application.yml` 無 `server.forward-headers-strategy` | SEC-WEB-3 | 中 |
| G-13 | prod 仍掛載 springdoc：非 prod 才匿名開放，prod 落入 `anyRequest().authenticated()`，任何登入者可讀 `/v3/api-docs` | `SecurityConfig.java:64-66,74`；`application-prod.yml` 無 springdoc 設定 | SEC-LOG-3 | 低 |
| G-14 | 非冪等 JSON body 無全域上限（只有冪等 64 KB 與 multipart 20 / 21 MB；Cloud Run 平台上限 32 MiB） | `application.yml:3-17`；GLOBAL §2.4 | SEC-INPUT-1 | 低 |
| G-15 | log 含 email | `AuthService.java:252` | SEC-LOG-1、CQ-16 | 低 |
| G-16 | `RESEND_API_KEY` 預設空字串，prod 缺值不會啟動失敗 | `application.yml:48` | SEC-SECRET-1、CQ-14 | 低 |
| G-17 | 登入鎖定只有帳號維度，PRD-000 寫「帳號 / IP」 | `LoginAttemptService.java` | SEC-AUTH-5、CQ-6 | 低 |
| G-18 | 容器以 root 執行；CI 未掃描容器映像 | `backend/Dockerfile` | CQ-38 | 低 |
| G-19 | CI security-scan 使用 Node 20（推測已於 2026-04-30 EOL，需確認）；osv-scanner 釘在 v2.0.0；Spring Boot 4.0.6，4.0 開源支援至 2026-12-31 | `security-scan.yml:24,43`；`backend/pom.xml:8` | SEC-SCA-3 | 中（上線前必須處理 Spring Boot） |
| — | **已修正、不再是落差**（任務敘述與程式碼不符）：`APP_CRYPTO_KEY`、`INTERNAL_CRON_SECRET` 預設值已在 B4 移除；`InternalSecretFilter` 已改為 403 `FORBIDDEN`、`MessageDigest.isEqual` 常數時間比較、log 只記路徑 | `application.yml:38,46`；`InternalSecretFilter.java:45-55` | SEC-INT-1、SEC-SECRET-1 | — |

---

## 12. 需求來源互相矛盾之處（本文件的處理）

| # | 矛盾 | 本文件處理 |
|---|------|-----------|
| X-1 | 舊稿 §3 與 `00-NFR-OVERVIEW` 要求「變更銀行撥款帳戶需二次驗證」；baseline CQ-75 與 PRD-025 BR-10（Approved）定為 MVP 不重驗、改後發不可關閉通知 | 依 baseline：SEC-AUTH-6 (3) 不要求再驗證，以通知偵測盜改。若使用者要恢復再驗證，需改 baseline 與 PRD-025 |
| X-2 | 舊稿 §2 與 PRD-017（Draft）寫 KYC 證件「僅管理員可讀」；baseline §1.2 定為「本人與 ADMIN」 | 依 baseline（SEC-FILE-1 (4)）；PRD-017 重寫時同步 |
| X-3 | 舊稿 §2 付款憑證簽名網址 10 分鐘；PRD-007 BR-17（Approved）為 5 分鐘；PRD-022 BR-18 日誌媒體 15 分鐘 | 依 PRD：憑證 300 秒、日誌媒體 900 秒（SEC-FILE-2） |
| X-4 | 舊稿 §1 鎖定「單一帳號」；PRD-000 AC-4 寫「該帳號 / IP」 | 帳號維度為必要；IP 維度列 CQ-6 |
| X-5 | SD-GLOBAL-SPEC §1.2 寫前端打包進後端映像、由 Spring Boot serve；實際由 Firebase Hosting 提供，後端映像不含前端（`firebase.json`、`backend/Dockerfile`） | 本文件以實際部署為準：前端標頭由 Firebase Hosting 下發（SEC-WEB-2）。SD-GLOBAL-SPEC §1.2 需另行修正（本次不改其他檔案） |
| X-6 | 系統提示稱最高方案為 Premium；baseline §2.1 為 ULTIMATE | 依 baseline 用 ULTIMATE |
| X-7 | `00-NFR-OVERVIEW.md` 的 NFR-003 段仍是舊內容 | 待本文件核准後同步（本次不改其他檔案） |

---

## 13. 待釐清事項

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-1 | BCrypt cost 下限 | 10（現況 Spring 預設）；登入 P95 延遲可接受時再提高到 12 |
| CQ-2 | refresh token 有效期 | 7 天（現況），自簽發起算；輪替時新 token 重新起算 7 天 |
| CQ-3 | refresh token 輪替與重用處理 | 每次換發輪替；已輪替的舊 token 在 30 秒寬限內再被使用 → 回傳同一組新 token（容許多分頁同時換發，SD-FRONTEND-SPEC §2 跨分頁情境），超過寬限 → 401 並撤銷該帳號全部 refresh token、記 WARN |
| CQ-4 | 撤銷後舊 access token 的殘留時間 | access token 縮短為 15 分鐘（仍符合 ≤ 24 小時）；不做每請求查庫的黑名單 |
| CQ-5 | token 存放方式 | refresh token 改 httpOnly cookie：名稱必須是 `__session`（推測：Firebase Hosting 轉發到 Cloud Run 時只保留這個 cookie，SD-IDENTITY 需實測），屬性 `HttpOnly; Secure; SameSite=Strict; Path=/`；access token 只放記憶體；refresh 端點檢查 `Origin`。過渡期維持 localStorage，但 SEC-WEB-2 的 CSP 須先強制生效 |
| CQ-6 | 登入鎖定是否加 IP 維度（PRD-000 寫「帳號 / IP」） | 不另做 IP 鎖定；登入端點計入 SEC-INPUT-6 的匿名 IP 限流。註：帳號維度鎖定可被他人故意觸發（鎖定型 DoS），MVP 接受 |
| CQ-7 | 不存在的 email 與密碼錯誤是否同回應（帳號列舉） | 同碼同文案（401，「帳號或密碼錯誤」）；鎖定回應會透露帳號存在，MVP 接受 |
| CQ-8 | 改密碼時目前密碼錯誤是否計入登入失敗次數 | 計入同一計數，達 5 次一併鎖定 |
| CQ-9 | 綁定 WebAuthn 前的再驗證方式 | 有密碼的帳號輸入目前密碼；只有 Google 登入的帳號須在 5 分鐘內重新完成 Google 登入 |
| CQ-10 | WebAuthn 參數 | challenge 單次使用、5 分鐘失效；`userVerification=required`；RP ID = 正式網域 |
| CQ-11 | ADMIN 帳號開通方式 | 不提供 API；只經 Flyway 或手動 SQL（屬 DB 變更，需使用者當次同意），開通寫稽核 |
| CQ-12 | ADMIN 登入是否要求第二因素 | 要求：ADMIN 帳號只能以 WebAuthn 登入（ADMIN 可作廢付款、調解結案，風險高於一般帳號） |
| CQ-13 | 加密金鑰輪替 | 密文帶金鑰版本前綴（例 `v1:`），MVP 不做自動輪替；外洩時人工換鑰並以一次性工作重新加密 |
| CQ-14 | prod 缺 `RESEND_API_KEY` 的行為 | prod 缺值啟動失敗；local 可空（走 Mock） |
| CQ-15 | commit 的 secret 掃描 | `security-scan.yml` 加 gitleaks（PR 與 push main），命中即失敗 |
| CQ-16 | log 是否可記 email | 遮蔽為首字元加網域（`w***@example.com`）；需要追查時用 userId |
| CQ-17 | bucket 是否強制 public access prevention | enforced；若日後有公開頭像等需求，另開公開 bucket，不與私有物件混用 |
| CQ-18 | KYC 證件簽名網址有效期 | 300 秒（與付款憑證相同） |
| CQ-19 | 照片的 EXIF 位置資訊（保母在飼主家拍照可能帶 GPS） | 前端上傳前重新編碼（WebP 壓縮時以 canvas 重繪即移除 EXIF），後端不解析；影片 metadata MVP 不處理 |
| CQ-20 | 資料庫 TLS 等級 | 維持 `sslmode=require`（加密但不驗憑證）；`verify-full` 列為後續強化 |
| CQ-21 | 前端 CSP 與其他標頭的值 | `default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style; img-src 'self' data: blob: https://storage.googleapis.com https://*.googleusercontent.com; media-src 'self' blob: https://storage.googleapis.com; connect-src 'self' https://storage.googleapis.com https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'; worker-src 'self'; manifest-src 'self'`；`Referrer-Policy: strict-origin-when-cross-origin`；`Permissions-Policy: camera=(), microphone=(), geolocation=()`。須確認建置後的 `index.html` 與 PWA 註冊沒有 inline script |
| CQ-22 | CSP 上線方式 | 先以 `Content-Security-Policy-Report-Only` 部署 7 天，期間 E2E 監聽違規、無違規後改強制；不建 report 收集端點 |
| CQ-23 | HSTS 值 | `max-age=31536000; includeSubDomains`，不加 `preload` |
| CQ-24 | prod CORS 白名單 | `https://{PROJECT_ID}.web.app`、`https://{PROJECT_ID}.firebaseapp.com`（之後有自訂網域再加）；前端經 Firebase rewrite 同源呼叫 API，白名單只影響跨源情境 |
| CQ-25 | 非冪等 JSON body 全域上限 | 1 MB，超過 → 400 `VALIDATION_FAILED`（`field=body`、`maxBytes`） |
| CQ-26 | 金額欄位 `BigDecimal` 運算前的範圍檢查 | `precision() ≤ 15` 且 `-15 ≤ scale() ≤ 15`，否則 400 `VALIDATION_FAILED`（金額上限 999,999 遠在範圍內） |
| CQ-27 | 列表分頁筆數上限 | 每頁最多 100 筆，超過 → 400 `VALIDATION_FAILED`；預設 20 |
| CQ-28 | 限流錯誤碼 | 429 `RATE_LIMITED` + `Retry-After`；屬新的跨模組碼，需寫回 baseline §3.7（不列入判斷順序，在 401 之前由 filter 回應） |
| CQ-29 | 登入使用者限流上限 | 每個 token 身分每分鐘 300 次 |
| CQ-30 | 限流的 IP 取法與多實例計數 | IP 取 `X-Forwarded-For` 由右數第 N 個（Firebase Hosting 與 Google 前端附加的可信節點數，SD-GLOBAL-SPEC 實測決定），不採最左值（可被偽造）；計數放各實例記憶體（MVP 不導入 Redis，GLOBAL §1），多實例時實際上限約為 60 × 實例數，接受 |
| CQ-31 | prod 是否停用 OpenAPI / Swagger | 停用（`springdoc.api-docs.enabled=false`、`springdoc.swagger-ui.enabled=false` 於 prod profile）；SD-GLOBAL-SPEC §6.2 補 prod 例外 |
| CQ-32 | 稽核是否記 IP | 帳號安全類事件（登入鎖定、改密碼、登出所有裝置、收款帳號變更、停權）記 IP；訂單轉移不記 |
| CQ-33 | 稽核不可抹除的 DB 層防護、帳號註銷時的處理 | MVP 只做應用層（無 UPDATE / DELETE 路徑 + AU 測試），不加 DB trigger；帳號註銷時稽核列保留，只存 userId，不複製個資到稽核列 |
| CQ-34 | `INTERNAL_CRON_SECRET` 強度與輪替 | ≥ 32 bytes 隨機值；外洩時立即輪替，平時每年輪替一次，Cloud Scheduler 標頭與 GitHub Actions secret 同步更新（雲端變更需使用者當次同意） |
| CQ-35 | security-scan 是否設為 PR 必要檢查 | 是（GitHub branch protection，由使用者設定） |
| CQ-36 | 漏洞修補時限 | Critical 7 天、High 30 天；暫時無法修補的例外到期日 ≤ 90 天 |
| CQ-37 | 支援期緩衝 | 上線日距所用版本的開源支援 EOL ≥ 90 天；營運中於 EOL 前 90 天開始升級。Spring Boot 4.0 須在上線前升到當時最新的 GA 版本列 |
| CQ-38 | 容器加固 | Dockerfile 以非 root 使用者執行；Artifact Registry 映像漏洞掃描在確認費用後啟用 |

---

## 14. 落地對照（給 SD 撰寫者）

| 落地文件 | 須涵蓋的要求 |
|---------|-------------|
| SD-GLOBAL-SPEC | SEC-AZ-1、AZ-2、AZ-5、SECRET-1、FILE-4 (4)、WEB-3、WEB-4、INPUT-1 ~ INPUT-6、LOG-1 ~ LOG-4 |
| SD-FRONTEND-SPEC | SEC-AUTH-4、AUTH-7、FILE-5、WEB-2、WEB-5、WEB-6 |
| SD-IDENTITY（尚未撰寫） | SEC-AUTH-1 ~ AUTH-7、AZ-3、AZ-4、WEB-5 (3)、LOG-1、LOG-4、AUDIT-1 ~ AUDIT-3 |
| SD-ORDER | SEC-AZ-1、AZ-3、AZ-4、DATA-1、DATA-2、FILE-1 ~ FILE-4、AUDIT-1 ~ AUDIT-3、INT-1、INT-2 |
| SD-FULFILL | SEC-AZ-1、FILE-1 ~ FILE-5、WEB-4 (4)、AUDIT-1、AUDIT-3、INT-1、INT-2 |
| SD-TRUST（KYC） | SEC-AZ-4、FILE-1 ~ FILE-4、AUDIT-1 |
| SD-SUPPLY（收款帳號） | SEC-AUTH-6 (3)、AZ-3、DATA-1、DATA-2、LOG-1、AUDIT-1 ~ AUDIT-3 |
| SD-NOTIFY | SEC-WEB-5 (3)、WEB-6 (3) |
| 部署清單 | SEC-AUTH-4（cookie 經 Firebase 轉發）、DATA-2、SECRET-1、FILE-1 (2)、WEB-1、WEB-2、WEB-4 (4)、LOG-3、INT-1、SCA-1 ~ SCA-3 |

---

## 15. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 全文重寫：舊稿 12 項要求改寫為可驗收條件（數值、觀察方式、驗證方法），新增依賴漏洞管理、資安標頭、token 存放、secret 與 log 遮蔽、內部端點、上傳檔案內容檢查、請求解析防護、CORS 與開放重導、錯誤回應、稽核同交易等要求，共 44 條（SEC-*）；每條標出處、落地 SD、驗證方式；新增現況落差（§11）、來源矛盾（§12）、待釐清 CQ-1 ~ CQ-38；收款帳號變更改依 baseline CQ-75 不再要求二次驗證，KYC 可讀者依 baseline §1.2，憑證簽名網址依 PRD-007 為 5 分鐘。狀態維持 Draft | 全面重建；使用者 2026-11-22 同意補強安全缺口 |
