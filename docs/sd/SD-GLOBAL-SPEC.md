# SD-GLOBAL-SPEC: 全域架構與開發規範

## 1. 基礎設施與成本定調 (Infrastructure Strategy)
- **原則**：MVP 階段嚴控預算，極小化雲端帳單。
- **Database**: Supabase (PostgreSQL)。應用連線走 Transaction Pooler (6543)，Flyway migration 走 Session Pooler (5432)，避免 pgbouncer transaction mode 的 advisory lock 限制。
- **Computing**: Cloud Run 開啟 `min-instances: 0`（無請求時不計費）。
- **Cache**: **MVP 階段不導入 Redis/Memorystore**。
- **Logging**: 全域使用 SLF4J 輸出至 Console，由 GCP Cloud Logging 自動收集，禁止引入 ELK 或第三方監控工具。

## 1.2 容器化標準 (Containerization Standard)
- **基底鏡像**：
  - **Backend**: `eclipse-temurin:21-jre-jammy` (Java 21 LTS), **Spring Boot 4.0.x**。
  - **Frontend**（2026-11-22 依 NFR-003 U-1 改寫，原寫「打包進後端映像」與實際部署不符）：React + Vite，`npm run build` 產出的 `frontend/dist` 由 **Firebase Hosting** 提供；`firebase.json` 以 rewrite 把 `/api/**` 轉給 Cloud Run 服務 `cat-sitter-app`（asia-east1），其餘路徑回 `index.html`（SPA）。**後端映像不含前端靜態檔**，`SecurityConfig` 不得再對 `/`、`/index.html`、`/assets/**`、`/static/**`、`/*.js` 等靜態路徑 `permitAll`（現況殘留，NFR-003 G-21）。前端頁面的資安標頭由 Firebase Hosting 下發（SD-FRONTEND-SPEC §2.6），API 回應的資安標頭由 Spring 下發（§3.5）。
  - **支援期**：Spring Boot、Java、建置用 Node.js 必須在官方開源支援期內，上線日距 EOL ≥ 90 天；Spring Boot 4.0.x 開源支援至 2026-12-31，上線前升到 4.1 以上當時最新 GA 版本列，Node.js 改用 24 LTS（NFR-003 SEC-SCA-3、CQ-37）。
- **多階段構建 (Multi-stage Build)**：強制採用多階段構建，確保 Production Image 不包含原始碼與編譯工具。
- **環境變數**：嚴禁將 Secrets 寫死在 Dockerfile。統一透過 `ENTRYPOINT` 讀取系統環境變數，並由 GCP Secret Manager 注入。
- **時區對齊**：容器內 OS 時區統一強制設定為 `UTC`，與資料庫規格對齊。

## 1.3 預算監控 (Cost Monitoring)
- **預算警報 (Budget Alerts)**：於 GCP 控制台設定 $10 USD 預算閾值，觸發時透過 Email 通知，嚴防帳單意外爆掉。
- **本地環境 (Local Dev)**：強制使用 `docker-compose` 建立包含 PostgreSQL 的本地環境，確保「開發即生產」，減少環境差異導致的 Bug。


## 2. 全域資料處理標準
### 2.1 時區 (Timezone)
- **資料庫儲存**：一律採用 `UTC`。
- **API 傳輸**：一律採用 `ISO 8601` 字串。
- **前端顯示**：一律以 Asia/Taipei 顯示；「日」的邊界也以 Asia/Taipei 計算（2026-11-21 依 baseline §5 修正，原為依 Client 時區）。

### 2.2 貨幣與精度 (Currency)
- **幣別**：新台幣 (TWD)。
- **儲存精度**：一律使用 `INT` 儲存於 DB。計算中途使用 `BigDecimal`，入庫前四捨五入。

### 2.3 併發控制 (Concurrency Control)
> **2026-11-21**：訂單、付款、變更、行程的狀態轉移改依 `docs/sa/00-DOMAIN-BASELINE.md` §3.6（鎖父訂單 `FOR NO KEY UPDATE`、`@Version` 僅作防線、同交易稽核、outbox、冪等回放）。本節與之衝突處以 baseline 為準，待 SD-ORDER 核准後改寫本節。
- **資料列級鎖**：實體更新使用 JPA `@Version` 樂觀鎖。
- **業務級分散式鎖**：需要跨列序列化的業務規則（例如 SD-021 記事模板 / 媒體上限、SD-022 草稿 get-or-create、SD-000 單一 session），統一使用 **PostgreSQL Advisory Locks**。
  - **不做檔期 / 容量鎖**：系統不做接單容量控管（PRD-005 / PRD-006，2026-10 產品決策），沒有「保母 + 日期」的檔期佔用鎖（2026-10 backlog 第 7 批移除 `CapacityGuard` 與 `AdvisoryLockService.acquireLocks/generateLockKey`）。同一張訂單的狀態轉移（`PENDING` → `PENDING_PAYMENT`）以訂單列 `SELECT ... FOR UPDATE` 序列化（SD-006 §2.1）。
  - **實作規範**：直接使用 `JdbcTemplate` 呼叫 `pg_advisory_xact_lock(lockId)`。
  - **生命週期**：必須與 Transaction 綁定（Transaction-level lock），確保事務結束（Commit/Rollback）時自動釋放，避免 Deadlock。

### 2.4 冪等（共用機制，2026-11-22，本節擁有 `idempotency_records`）
> 由 SD-ORDER §3.6.2 定案後移入共用（SD-FULFILL CQ-2）。所有模組重建時沿用；舊 `IdempotencyService.checkAndConsume` / `idempotency_keys`（重複回 409）改名 `LegacyIdempotencyService`，只留給尚未重建的 KYC、照護記事 / 媒體。
- **套件與擁有權**：`application/common/idempotency`（`@Idempotent`、`IdempotencyBodyCachingFilter`、`IdempotencyInterceptor`、`IdempotencyService`、`IdempotencyRecordRepository`）。表 `idempotency_records` 歸本規範；DDL 隨 `V20261122_01` 建立。`IdempotencyRecordRepository` 只准本套件依賴（ArchUnit）；任何模組可依賴 `IdempotencyService` 與 `@Idempotent`。
- **宣告**：冪等端點在 controller 方法上標 `@Idempotent`，不維護寫死的端點清單。範圍 = （token 使用者, HTTP method + 路由樣板, key）；有效期 `IDEMPOTENCY_KEY_TTL_HOURS`（baseline §5）。`@Idempotent` 端點缺 key → 400 `VALIDATION_FAILED`（`field=Idempotency-Key`，判斷順序第 6 步，baseline §3.7）。
- **讀 body**：`IdempotencyBodyCachingFilter` 在 handler mapping 前執行、看不到 `@Idempotent`，以 `Content-Length` 判斷，**絕不讀超過上限的串流**：(a) 帶 `Idempotency-Key`、Content-Type 為 JSON、且 `Content-Length` 存在且 ≤ 64 KB → 讀成 byte[] 並包成可重讀 wrapper；(b) `Content-Length` > 64 KB → 不讀、不包裝，只設 request attribute `idempotency.bodyTooLarge`；(c) 無 `Content-Length`（chunked）→ 最多讀 64 KB + 1 位元組，未超過則照 (a)，超過則以「已讀前綴 + 剩餘原串流」的 wrapper 交回（非冪等 handler 仍拿到完整 body）並設同一 attribute。`IdempotencyInterceptor` 確認 handler 有 `@Idempotent` 後：有 `bodyTooLarge` → 400 `VALIDATION_FAILED`；實際帶 body 且 Content-Type 既不是 JSON 也不是 multipart → 400 `VALIDATION_FAILED`（`field=Content-Type`），不讀 body；沒有 body 且沒有 Content-Type 的請求（例：E22 / E23 / E25）視為 `{}`；`bodyTooLarge` 回 400 `field=body` 並帶 `maxBytes`。64 KB 上限與 (a)–(c) 只適用 JSON，multipart 不套用（上限由 multipart 全域設定管）；multipart 由 `MultipartFile` 重讀。未帶 header 的請求完全不經處理。
- **request hash**：SHA-256（路由樣板 + 已解析路徑變數 + 正規化 JSON；multipart = 各欄位值依名稱排序 + 檔案 SHA-256）。JSON 正規化：解析成樹、物件 key 依碼位排序、數字以十進位字串（整數不帶小數點）、字串不 trim、緊湊輸出、空 body 視為 `{}`。
- **三段式**：段 0 `IdempotencyInterceptor` 回放（`COMPLETED`、未過期、同 hash → 回放第一次狀態碼與 body；在 `@PreAuthorize` 之前）；段 1 `IdempotencyService.claim`（自行開短交易，傳播 `NEVER`）：`INSERT ... ON CONFLICT`，每次認領或接手換新 `claim_token`，衝突時 hash 不同 → 422 `IDEMPOTENCY_KEY_REUSED`、處理中（`locked_until` 30 秒內）→ 409 `IDEMPOTENCY_IN_PROGRESS`、逾時 → 條件式 UPDATE 接手；段 0 之後、認領前同 hash 紀錄恰好變 `COMPLETED` → 409 `IDEMPOTENCY_IN_PROGRESS`（客戶端同 key 重試即走回放）；段 2 交易外 I/O（如上傳）；段 3 `IdempotencyService.complete`（`MANDATORY`，在呼叫端的業務交易內，**不要求持有任何業務鎖**）：`UPDATE ... WHERE id AND status='PROCESSING' AND claim_token=:mine`，0 列 → 回滾並回 409 `IDEMPOTENCY_IN_PROGRESS`；失敗 `IdempotencyService.fail`（自行開短交易，`NEVER`，同樣比對 token）：可記錄的業務錯誤（409 / 422，不含 `ORDER_BUSY`、`IDEMPOTENCY_IN_PROGRESS`）→ `COMPLETED` 存錯誤回應；5xx、`ORDER_BUSY`、儲存服務失敗 → 刪紀錄；被接手 → 不動。
- **回應 body** 不含簽名網址等時效性資料；每個請求同一時間只佔 1 條連線（不用 `REQUIRES_NEW`）。

### 2.5 實體基底（2026-11-22）
- **重建模組不得使用舊 `BaseEntity`**（含 `is_deleted`、`@CreatedBy` / `@LastModifiedBy`）。舊 `BaseEntity` 只留給尚未重建的模組，重建時移除。
- `AuditedEntity`：`id`（UUID）、`created_at`（`@CreatedDate`）、`updated_at`（`@LastModifiedDate`）。`VersionedEntity extends AuditedEntity`：再加 `@Version version`，只給有並行寫入的狀態機或計數實體（例：`orders`、`payments`、`modification_requests`、`visits`）。不可變的明細 / 稽核列（例：`order_items`、`order_logs`）不繼承任何基底，自管欄位。
- **不做軟刪除**：重建模組的表不含 `is_deleted`，查詢不過濾；需要保留紀錄的情境改用狀態欄或墓碑列。
- **建立者欄位**：不掛 `@CreatedBy`。需要記建立者的欄位由 Service 明確寫入（使用者操作寫 token 使用者；系統建立寫 NULL）。原因：`RequestAuditorAware` 回傳的是觸發請求的使用者，系統在同一交易內代為建立的資料（例：O1 時建立的行程與檢核項）會被誤填成飼主。
- **實體只映射資料表實際存在的欄位**：local `ddl-auto: validate` 守護；遷移刪欄時同一次發布修改實體。

### 2.6 錯誤回應與例外對映（2026-11-22）
- **格式（全域）**：`{ "code", "message", "payload", "traceId", "timestamp" }`；值為 null 的欄位不輸出（`default-property-inclusion: non-null`）；`authenticationEntryPoint` 回 401 `UNAUTHENTICATED`、`accessDeniedHandler` 回 403 `FORBIDDEN`，皆用此格式。
- **全域 `GlobalExceptionHandler`**：`@PreAuthorize` 拒絕（`AuthorizationDeniedException`）→ 403 `ROLE_MISMATCH`；其他 `AccessDeniedException` → 403 `FORBIDDEN`；進方法前的格式錯誤（`HttpMessageNotReadableException`、`MethodArgumentTypeMismatchException`、缺必填 part / param、不支援的 media type、multipart 解析失敗、超過容器上限）→ 400 `VALIDATION_FAILED`；未處理例外 → 500 `INTERNAL_ERROR`（不含堆疊）。`spring.jackson.deserialization.accept-float-as-int=false`。
- **模組專屬 advice**：各模組以 `@RestControllerAdvice(basePackageClasses = 模組 controller 套件)` + 最高優先序處理自己的例外與碼（例：SD-ORDER `OrderExceptionHandler`、SD-FULFILL `FulfillExceptionHandler`）。
- **traceId**（2026-11-22 依 NFR-003 U-2、SEC-LOG-5 改寫）：取 `X-Cloud-Trace-Context`（格式 `TRACE_ID/SPAN_ID;o=...`）斜線前的 trace 部分，**必須完全符合 `^[0-9a-f]{32}$`** 才採用；標頭缺漏、格式不符、含 CR / LF 或其他字元時一律重新產生（32 位小寫十六進位，即去掉連字號的隨機 UUID），所有 traceId 同一格式。traceId 同時寫入錯誤回應與 log，不得把未驗證的標頭值放進任一處。IT：送 `X-Cloud-Trace-Context: abc` + CRLF + `FAKE` → 回應 `traceId` 不等於輸入且符合格式（現況 `ApiErrors` 未驗證，NFR-003 G-16）。
- **`message` 不得帶例外原文**（NFR-003 SEC-LOG-2）：錯誤 body 只有上列 5 欄；`message` 只能是固定文案——本專案 `application/exception/*Exception` 建構時傳入的業務文案可用，**框架、JDK、JDBC、Jackson、第三方程式庫例外的 `getMessage()` 一律不得出現在回應**（不得拼接、不得原樣回傳），也不得含堆疊、例外類別名、SQL、約束名、表名、檔案路徑、主機名。細節只進 ERROR / WARN log（帶 traceId）。現況違反處（實作清單）：`GlobalExceptionHandler` 對 `AuthenticationException` 拼接 `ex.getMessage()`、`IllegalArgumentException` / `IllegalStateException` 直接回 `ex.getMessage()`（BCrypt 超過 72 bytes 的例外訊息會原樣外洩），改為 400 `VALIDATION_FAILED` / 500 `INTERNAL_ERROR` 固定文案；未重建模組的 `DataIntegrityViolationException` 400 `DATA_ERROR` 維持固定文案。IT `ErrorResponseLeakIntegrationTest` 觸發：(a) 訊息含 `SELECT` 的未處理 RuntimeException、(b) SQLSTATE 23514、(c) 不合法 JSON、(d) `AuthenticationException`、(e) `IllegalArgumentException`、(f) 73 bytes 密碼，回應 body 皆不含原例外訊息、約束名與 Jackson 解析訊息。
- **429 `RATE_LIMITED`**（NFR-003 CQ-28；跨模組碼，baseline §3.7 待 U-5 回寫）：由 §2.8 的限流 filter 直接寫出（不經 `GlobalExceptionHandler`、不列入 baseline 判斷順序），body 同上格式，必帶 `Retry-After`（整數秒，≥ 1）。CORS `exposedHeaders` 已含 `Retry-After`（§3.5）。
- **鎖與資料完整性**（只在重建模組的 advice 基底 `AbstractModuleExceptionHandler` 生效；未重建模組維持舊對映，重建時改繼承基底）：SQLSTATE 55P03 / 40P01（含 Spring 轉換後的 `CannotAcquireLockException`、`PessimisticLockingFailureException`、`DeadlockLoserDataAccessException`）與 `@Version` 衝突 → 409 `ORDER_BUSY` + `Retry-After: 1`。`DataIntegrityViolationException` **只有 SQLSTATE 23505**（unique violation，含 commit 時的延遲唯一約束）可對映 409（依約束名對應模組碼，未列名者 `ORDER_BUSY`）；23514 / 23502 / 23503 等確定性錯誤一律 500 `INTERNAL_ERROR` 並記 ERROR log，不得對映為可重試碼。
- **欄位驗證時點**：需遵守 baseline §3.7 判斷順序的端點，Request DTO 不掛 `@Valid`、必填 header 宣告為非必填，改由 Service 在第 6 步驗證；金額欄以 `BigDecimal` 接收並驗證為整數。

### 2.7 文字欄位的空白與字數（2026-11-22）
- **「空白」的定義**：Java `String.strip()` 的判定（`Character.isWhitespace`，含 U+3000 全形空白、Tab、換行）**再加上 U+00A0 不換行空白**（`isWhitespace` 不含它，從網頁複製常帶入），實作集中在 `TextRules.isBlankChar`（2026-11-22 使用者確認）。全站所有「去前後空白 / 去頭尾空白」一律用它，不得用 `String.trim()`（只去 ≤ U+0020）。
- **字數**：以 strip 後的 Unicode code point 計（與 PostgreSQL `char_length` 一致）；前端計數規則相同。
- **儲存**：存 strip 後的值；strip 後為空的必填欄位 → 400 `VALIDATION_FAILED`，選填欄位存 NULL。

### 2.8 請求解析與濫用防護（2026-11-22，NFR-003 SEC-INPUT-1 ~ 6）
> 本節所有上限**不依 SaaS 方案區分**。

| 項目 | 規則 | 出處 |
|------|------|------|
| 非冪等 body 上限 | 非 multipart 的請求 body 上限 **1,048,576 bytes**。`RequestBodySizeLimitFilter`（Servlet filter，排在 `IdempotencyBodyCachingFilter` 之前）：`Content-Length` > 上限 → 不讀串流，直接 400 `VALIDATION_FAILED`（`field=body`、`maxBytes=1048576`）；無 `Content-Length`（chunked）→ 以計數串流包裝，讀到第 1,048,577 bytes 時中止並拋 `RequestBodyTooLargeException` → 400 同上。不得先整包讀入再判斷。帶 `Idempotency-Key` 的 JSON 仍適用 §2.4 的 64 KB；multipart 適用全域 multipart 上限（單檔 20 MB、整個請求 21 MB，SD-ORDER §6.1） | SEC-INPUT-1、CQ-25 |
| Jackson 解析限制 | 不得調高 Jackson 3 `StreamReadConstraints` 預設值（巢狀深度、數字長度、字串長度）；超限由 `HttpMessageNotReadableException` → 400 `VALIDATION_FAILED`，不得 500 或 StackOverflowError。`accept-float-as-int=false`（§2.6） | SEC-INPUT-2、SEC-INPUT-3 |
| 金額 `BigDecimal` 範圍 | 金額欄以 `BigDecimal` 接收；共用 `AmountRules` 在**任何運算之前**（`setScale`、`toBigIntegerExact`、`stripTrailingZeros`、比較）先檢查 `precision() ≤ 15` 且 `-15 ≤ scale() ≤ 15`，不符 → 400 `VALIDATION_FAILED`（`field` = 該欄）；通過後才做「必須為整數」與業務上下限檢查。IT：送 `1e999999999` → 400 且回應 < 1 秒 | SEC-INPUT-3、CQ-26 |
| 文字與 regex | 文字欄先依 §2.7 檢查字數上限再做其他處理；套用在使用者輸入的 regex 必須先限制長度，樣式不得含巢狀量詞（`(a+)+`、`(\w*)*`）或可重疊分支加量詞；AU 掃描 `Pattern.compile` 與 `@Pattern` 的樣式字串 | SEC-INPUT-3、SEC-INPUT-4 |
| Request DTO 欄位 | 不得宣告代表呼叫者自己的身分欄位、`id`、`status`、`version`、伺服器計算的金額；多送時忽略（§3.4）。AU 白名單引用 §3.4「允許的例外」 | SEC-INPUT-2 |
| 分頁 | `size` 預設 20、**全域上限 100**，超過 → 400 `VALIDATION_FAILED`（`field=size`、`max`）；模組可訂更小上限（例：SD-ORDER 50）。不得依賴 `spring.data.web.pageable.max-page-size`（它是靜默截斷，不回 400），由共用 `PageParams` 解析器明確驗證。不提供無上限的「全部匯出」端點給一般使用者 | SEC-INPUT-5、CQ-27 |
| 全域限流 | 見下方「限流」 | SEC-INPUT-6、CQ-28 ~ 30 |

**限流（`RateLimitFilter`）**

- **位置**：Spring Security filter chain 內、`JwtAuthenticationFilter` 之後（才知道是否帶有效 token）、授權判斷之前。套用 `/api/**`；**不套** `/api/internal/**` 與 `OPTIONS`。
- **計數鍵與上限**：帶有效 access token → 鍵 `U:{userId}`，每分鐘 300 次（CQ-29）；未帶或 token 無效 → 鍵 `IP:{來源 IP}`，每分鐘 60 次（SEC-INPUT-6.1）。帶有效 token 的請求不計入匿名額度；不同鍵互不影響。
- **演算法**：每個鍵「首次請求起算」的 60 秒固定窗（窗內第 61 次 / 第 301 次起拒絕，`Retry-After` = 窗結束剩餘秒數無條件進位，≥ 1）；時間取注入的 `Clock`。計數放各實例記憶體（Caffeine，`expireAfterWrite` 2 分鐘、`maximumSize` 100,000，被淘汰的鍵視為歸零）；MVP 無 Redis，多實例時實際上限約為上限 × 實例數，接受（CQ-30）。
- **超限回應**：429 `RATE_LIMITED` + `Retry-After`（§2.6），不進 controller；同一鍵每分鐘最多記 1 筆 WARN（含 traceId、路徑、鍵的類型、遮蔽後的 IP 或 userId，SEC-LOG-4）。
- **來源 IP（`ClientIpResolver`，CQ-30）**：不得用 `request.getRemoteAddr()` 當計數鍵的唯一依據，`server.forward-headers-strategy` 明確設 `none`（不讓容器改寫 remote address 與 scheme）。規則：取 `X-Forwarded-For` **最右項**；只有當最右項落在 `app.security.trusted-proxy-cidrs`（實測得到的 Firebase Hosting 轉發出口網段，IPv4 / IPv6 都列）內時，才改取前一項，**最多往前退一項**，其餘項一律不採用；可信網段明確排除 GCP 客戶可用網段（Google 公開的 `cloud.json`）。沒有 XFF（local）→ `getRemoteAddr()`。**`trusted-proxy-cidrs` 未實測填入前，經 Firebase 的請求最右項都是 Firebase 出口 IP，全站匿名流量會共用同一個計數（＝全站 429）**，因此匿名限流另有開關 `app.security.rate-limit.anonymous-enforce`：預設 `false`（只計數並記 WARN、不回 429），待兩條路徑實測、填入網段並通過下方 (c) 驗證後才改 `true`，正式上線前必須為 `true`。登入使用者的 `U:{userId}` 限流不受此開關影響。Firebase 出口網段是否有公開清單、是否穩定未查證（推測需以實測 log 歸納），實測結果寫回本節。
- **驗證**：(a) `ClientIpResolverTest` 以四種 XFF 樣本驗證：直打 run.app、經 Firebase 轉發、直打並偽造 XFF、自 GCP 客戶網段直打並偽造 XFF，後兩者的計數鍵必須是實際連線來源；(b) IT：同 IP 匿名 60 秒內打 61 次 `GET /api/sitters/{id}/plans` → 前 60 次 200、第 61 次 429 且帶 `Retry-After`；帶有效 token 的第 61 次 → 200；(c) 部署後 MAN：經 `web.app` 與直打 `run.app` 各一次，記錄 XFF 原文（寫回本節）。
- **上限調整門檻**（SEC-INPUT-6.7）：E2E 實測記錄每個公開頁、登入後主要頁面（訂單詳情、日誌、帳務）單次載入的 API 呼叫數，寫回本節；公開頁單次 > 10 支或登入後頁面單次 > 30 支時提請使用者調整上限（台灣行動網路多為 CGNAT）。
- **寄信類匿名端點**（註冊 OTP、重送 OTP、忘記密碼）另有每 email 每小時 5 封、每 IP 每小時 20 封的上限（SEC-INPUT-7、CQ-41），超過同樣回 429 `RATE_LIMITED`；計數儲存位置由 SD-IDENTITY 決定（本節的記憶體計數在 `min-instances: 0` 縮容時歸零，不適合小時級上限，建議 DB）。

### 2.9 日誌遮蔽與注入防護（2026-11-22，NFR-003 SEC-LOG-1、SEC-LOG-4、SEC-LOG-5）
- **不得出現在任何 log（含 DEBUG）**：`Authorization`、`Cookie`、`Set-Cookie`、`X-Internal-Secret` 標頭值；access / refresh token、密碼重設憑證、OTP；密碼（含目前密碼）；WebAuthn 原始 credential；完整收款帳號與戶名；簽名網址的 query 字串（只記 `objectKey`）；稽核 details 全文（現況 `AuditLogService` 以 `log.info` 印出，NFR-003 G-13）。
- **遮蔽函式**集中在 `LogMask`：email → 首字元 + `***` + `@網域`（`w***@example.com`，CQ-16；需要追查時記 userId）；收款帳號 → 銀行代碼 + 帳號後五碼（PRD-025 BR-8）；IP 可完整記錄（SEC-LOG-4 需要）。
- **禁止**：`CommonsRequestLoggingFilter` 或任何會印出 header / payload 的請求記錄器；`spring.mvc.log-request-details=true`；在 log 中拼接整個 request DTO（`toString()` 可能含密碼或帳號）。
- **CR / LF 注入**：`logging.pattern.console` 沿用 Spring 預設格式，但訊息段改為 `%replace` 把 CR、LF 換成字面 `\r`、`\n`（堆疊段 `%ex` 不受影響）；使用者輸入（email、名稱、路徑參數）因此無法偽造新行。IT：以含 CRLF 的 email 登入失敗，擷取的 log 不出現偽造的新行。
- **安全事件**記 WARN（含 traceId、路徑、token 身分若有、來源 IP，不含 secret）：登入鎖定、內部端點驗證失敗、refresh token 重用、限流觸發（同一鍵每分鐘最多 1 筆）、寄信上限觸發；加解密失敗記 ERROR。
- **驗收**：IT 以 log 擷取跑「登入成功 / 失敗、換發、改密碼、重設密碼、OTP、修改收款帳號、建立 payment 快照、內部端點錯誤 secret、取得簽名網址」，擷取內容不含測試用的密碼、token、OTP、secret、帳號明文、`X-Goog-Signature=`（`LogRedactionIntegrationTest`；IDENTITY / SUPPLY 部分隨各 SD 實作補案例）。

## 3. 開發效率與權限規範 (SaaS Gating)
### 3.1 SaaS Gating via AOP
- **實作方式**：自定義註解 `@RequirePlan(PlanType)` 搭配 Spring AOP 攔截器。
- **規範**：禁止在 Service 層寫死方案判斷，統一由 Controller 層標註權限。
- **方案歸屬的身分**：`PlanGatingAspect` 先找方法參數中名為 `sitterId` 的 `UUID`，找不到時改用 `TokenContext`（token 身分）。端點依 §3.4 不再收身分參數後，controller 層的 `@RequirePlan` 仍會生效（例：`POST /api/orders/{id}/quote` 調價需 PRO，fix-batch11 I1）。

### 3.2 JWT 最小化 Payload
- **規範**：Token 僅存放 `accountId` 與 `currentRole`。
- **禁令**：禁止將 `planType` 塞入 JWT。權限檢查統一由攔截器即時查庫或一級緩存，確保降級/升級即時生效。

### 3.3 外部服務隔離 (Mocking Layer)
- **規範**：所有外部服務（Google Calendar、金流、簡訊）必須實作 `MockService` 開關。
- **目的**：開發測試時禁止真實呼叫第三方 API，節省額外頻寬與呼叫費用。

### 3.4 身分一律取自 token（防 BOLA，2026-10-25 fix-batch11）
- **規範**：「呼叫者是誰」一律以 `TokenContext.getUserId()`（`JwtAuthenticationFilter` 從 JWT `userId` claim 寫入）取得，並在 controller 掛 `@PreAuthorize("hasRole(...)")` 限定角色。**禁止**以 `@RequestParam` / `@PathVariable` / `@RequestBody` 接收「代表呼叫者自己」的身分欄位（`sitterId`、`ownerId`、`userId`、`requesterId`…）再拿它去比對資源歸屬——這些 UUID 會出現在 API 回應（訂單詳情、公開頁），任何已登入者都拿得到。
- **資源歸屬檢查**：Service 層以 token 身分比對資源擁有者（例：`order.sitter.id == token.userId`），找得到資源但不屬於呼叫者 → `AccessDeniedException` → **403**；資源不存在 → **404**（2026-11-21 依 baseline §1.2 統一，原為 400 / 404）。不可用 `findByIdAndXxxId` 查不到就一律 400，否則分不出越權。
- **允許的例外**（參數是「操作對象」而非「呼叫者」）：公開查詢（`GET /api/sitters/{sitterId}/plans|questions`、`GET /api/sitter/profile/{sitterId}`）、對象型參數（收藏 / 黑名單 / 轉介的目標 `sitterId` / `ownerId`）、ADMIN 專用端點、配對型路徑（`/api/care-notes/{sitterId}/{ownerId}`、`/api/care-media/{sitterId}/{ownerId}`：其中之一必須等於 token 身分，寫入端點限 `sitterId == token`，且雙方須有過任何狀態的訂單，SD-021 §1.3）。
- **部署相容**：移除身分參數時，後端不宣告該參數即可——Spring 對未宣告的 query param / Jackson 對未知 JSON 欄位（`FAIL_ON_UNKNOWN_PROPERTIES=false`）都直接忽略，舊前端在「後端已部署、前端未部署」的空窗期仍可運作。部署順序：先後端再前端。
- **已套用的端點（fix-batch11）**：`POST /api/orders/{id}/confirm|quote|reject`（SITTER）、`/complete|/dispute`（OWNER）——原本 `@RequestParam sitterId/ownerId`；`POST /api/orders/booking`（OWNER）——原本 body `ownerId`。2026-10-25 全 controller 掃描（23 支）後，其餘端點的呼叫者身分都已取自 token；仍收 `sitterId` / `ownerId` 的只剩上列「允許的例外」（`/api/sitter/trust-circle/referral-candidates?ownerId=` 與 `CreateReferralRequest.ownerId` 是 PRD-010 D 主動分享的通知對象）。
- **沒有身分時 → 401（2026-10-25 fix-batch12 J2）**：`TokenContext.getUserId()` 沒有身分（未帶 token、或 JWT 缺 `userId` claim）時拋 `AuthenticationCredentialsNotFoundException` → `GlobalExceptionHandler` 回 **401**，所有 profile 皆同（過去回全 0 的 dummy UUID，讓 permitAll 路徑 `/api/auth/switch-role|deactivate|logout-all-devices` 與漏掛 `@PreAuthorize` 的端點靜默以 0000 身分執行）。匿名可用、身分可選的端點（公開頁、WebAuthn、auditing、`PlanGatingAspect` 的 fallback）改用 `tryGetUserId()`。內部排程（`/api/internal/**`、`@Scheduled`）不經 `TokenContext.getUserId()`。迴歸：`TokenContextTest`、`TokenContextNoIdentityProdProfileTest`（prod profile）。
- **迴歸測試**：`OrderIdentityFromTokenSecurityTest`（真 JWT + 真 `SecurityConfig` filter chain，不用 `@WithMockUser`）——每支端點「別人的 token 操作他人訂單 → 403」「錯角色 → 403」「本人 → 200（多帶亂填的舊身分參數也被忽略）」。重建模組的每支需登入端點涵蓋 NFR-003 SEC-AZ-1 的 5 案例（本人 2xx、他人 403 `FORBIDDEN`、錯角色 403 `ROLE_MISMATCH`、無 token 401、不存在 404；不適用者在模組 SD 的 AC 對應表註明）。
- **身分只認 `Authorization` 標頭**（NFR-003 SEC-AUTH-4.4）：`JwtAuthenticationFilter` 只從 `Authorization: Bearer` 取身分。refresh token 改放 `__session` httpOnly cookie 後（SD-IDENTITY、SD-FRONTEND-SPEC §2.1），該 cookie **只有** refresh、logout 兩支端點讀取；`Path=/` 會讓每個 `/api/**` 請求都帶它，其他端點一律不得把 cookie 當身分。IT：只帶 cookie、不帶 `Authorization` 打任一需登入端點 → 401 `UNAUTHENTICATED`。refresh、logout 另檢查 `Origin` 必須在 §3.5 CORS 白名單內，缺漏或不符 → 403 `FORBIDDEN`（SEC-AUTH-4.6，SD-IDENTITY 實作）。

#### 3.4.1 匿名白名單與 URL 層規則（2026-11-22，NFR-003 U-4、SEC-AZ-2）
- **白名單**（路由樣板；變更須同步本表與 NFR-003 SEC-AZ-2.3）：

| 方法 | 路由樣板 | 說明 |
|------|---------|------|
| OPTIONS | `/**` | CORS 預檢 |
| GET | `/api/sitters/*/plans` | 公開方案 |
| GET | `/api/sitters/*/questions` | 公開問卷題目 |
| GET | `/api/sitters/*/booking-context` | SD-ORDER E31（帶 token 時仍解析身分） |
| GET | `/api/sitter/profile/*` | 保母公開頁 |
| POST | `/api/auth/login` | 密碼登入 |
| POST | `/api/auth/register` | 註冊（寄 OTP） |
| POST | `/api/auth/register/verify-otp` | 驗證 OTP |
| POST | `/api/auth/register/resend-otp` | 重送 OTP |
| POST | `/api/auth/refresh` | 以 `__session` cookie 換發（檢查 `Origin`） |
| POST | `/api/auth/logout` | 清除 `__session`（檢查 `Origin`；SD-IDENTITY 新增，現況無此端點） |
| POST | `/api/auth/forgot-password` | 申請重設信 |
| POST | `/api/auth/reset-password` | 以重設憑證改密碼 |
| POST | `/api/auth/google` | Google 登入 |
| POST | `/api/auth/webauthn/login/options` | WebAuthn 登入 challenge |
| POST | `/api/auth/webauthn/login/verify` | WebAuthn 登入驗證 |
| GET | `/actuator/health` | 健康檢查（§6.3） |
| 任何 | `/error` | 容器錯誤轉發 |
| PUT | `/local-storage/**` | **只限 local profile**（SD-FULFILL §8.2） |
| GET | `/swagger-ui/**`、`/swagger-ui.html`、`/v3/api-docs/**` | **只限非 prod profile**（§6.2） |

  SD-IDENTITY 撰寫時若新增或改名匿名 auth 端點（例：ADMIN 註冊連結，NFR-003 CQ-11），同步改本表。`/api/internal/**` 不在白名單，由 `InternalSecretFilter` 認證（§3.7）。
- **URL 層**：`SecurityConfig` **不得**對整個 `/api/auth/**` 設 `permitAll()`，只逐條放行上表路由；需要身分的 auth 端點（`POST /api/auth/switch-role`、`/deactivate`、`/logout-all-devices`、`/api/auth/webauthn/register/options`、`/register/verify`、`GET /api/auth/webauthn/credentials`、`DELETE /api/auth/webauthn/credentials/*`、日後的改密碼）落入 `anyRequest().authenticated()`，匿名請求由 `authenticationEntryPoint` 回 401 `UNAUTHENTICATED`，不會走到方法層 `@PreAuthorize`（否則會被 §2.6 對映成 403 `ROLE_MISMATCH`）。
- **AU `EndpointAccessRuleArchTest`**：掃描所有 controller handler，不在上表、不在 `/api/internal/**` 者必須掛 `@PreAuthorize`（類別或方法層），且表達式不得為 `permitAll()`；`SecurityConfig` 內 `permitAll()` 的 matcher 集合必須等於上表（以常數表 `AnonymousEndpoints` 為單一來源，`SecurityConfig` 與 AU 都讀它）。
- **IT `AnonymousAccessIntegrationTest`**：自 `RequestMappingHandlerMapping` 列舉所有 handler，以匿名請求逐一呼叫，不在白名單者必須 401 `UNAUTHENTICATED`；需要身分的 auth 端點匿名 → 401（不是 403）。

### 3.5 API 回應資安標頭與 CORS（2026-11-22，NFR-003 SEC-WEB-3、SEC-WEB-4）
- **資安標頭**（Spring Security `headers()`，對所有經 filter chain 的回應生效，含 2xx、401 / 403（entry point / denied handler）、404、429、500，含直接打 `*.run.app` 者）：

| 標頭 | 值 | 備註 |
|------|----|------|
| `X-Content-Type-Options` | `nosniff` | |
| `X-Frame-Options` | `DENY` | |
| `Cache-Control` | `no-store` | 停用 Spring 預設的 cache-control writer，改寫這個單一值 |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'` | 非 prod 的 Swagger 路徑（§6.2）不套，否則 UI 無法載入 |
| `Referrer-Policy` | `no-referrer` | |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains`（CQ-23，不加 `preload`） | **不得依賴 `request.isSecure()`**：Cloud Run 在前端終結 TLS、§2.8 設 `forward-headers-strategy: none`，Spring 預設的 HSTS writer 只在 secure 請求輸出，須把 request matcher 改為所有請求 |

  前端頁面的標頭（含前端 CSP）由 Firebase Hosting 下發（SD-FRONTEND-SPEC §2.6），且 Firebase `headers` 須排除 `/api/**`，經 rewrite 的 API 回應只帶本表這組。IT `SecurityHeadersIntegrationTest`：MockMvc 斷言 200、401、403、404、500 五種回應皆帶本表 6 個標頭；上線後 `curl -sI` 經 `web.app` 與直打 `run.app` 各檢查一次（MAN，NFR-003 G-19）。
- **CORS**（取代現況 `allowedOrigins("*")`，NFR-003 G-8）：

| 項目 | 值 |
|------|----|
| `allowedOrigins` | 設定 `app.security.cors.allowed-origins`（逐一列出，不得用 `*` 或 pattern）。prod：`https://{PROJECT_ID}.web.app`、`https://{PROJECT_ID}.firebaseapp.com`（CQ-24；之後有自訂網域再加）；local profile 另加 `http://localhost:5173`。prod 缺值 → 啟動失敗 |
| `allowedMethods` | `GET`、`POST`、`PUT`、`PATCH`、`DELETE`、`OPTIONS` |
| `allowedHeaders` | `Authorization`、`Content-Type`、`Idempotency-Key`（移除未使用的 `x-auth-token`） |
| `exposedHeaders` | `Retry-After`（409 `ORDER_BUSY`、429 `RATE_LIMITED`）；移除 `x-auth-token` |
| `allowCredentials` | `true`（refresh cookie 需要；只配合上列精確來源） |
| `maxAge` | 3600 秒 |

  前端經 Firebase rewrite 同源呼叫 API，但 Cloud Run 看到的 `Host` 與瀏覽器的 `Origin`（`web.app`）不同，Spring 仍會當成跨源請求處理，因此 `web.app` 必須在白名單內。同一份白名單也是 refresh / logout 的 `Origin` 檢查依據（§3.4）。IT `CorsIntegrationTest`：`Origin: https://evil.example` 的預檢 → 回應不含 `Access-Control-Allow-Origin`；白名單來源 → 回該來源原值。照護日誌直傳的 GCS bucket CORS 另見 SD-FULFILL §8.3 DF2。

### 3.6 對外 HTTP 呼叫（SSRF，2026-11-22，NFR-003 SEC-INPUT-8）
- 後端只呼叫固定主機（GCS、Resend、Google）；任何 HTTP client 的目標主機必須來自設定檔或程式常數，**不得**取自請求參數或資料庫中使用者填寫的值。
- 日後確有需要（ICS 訂閱、金流回呼查詢）時：只允許主機白名單、DNS 解析後拒絕私有、loopback、link-local（含 `169.254.169.254` metadata server）位址、不跟隨重導；寫進該模組 SD 並同步本節。
- AU `OutboundHttpArchTest`：`RestClient`、`WebClient`、`RestTemplate`、`java.net.http.HttpClient`、`URL.openConnection` 只允許列冊類別使用；現有列冊：`EmailService`（Resend）。GCS SDK、Google ID token verifier 為程式庫內部連線，不在掃描範圍，但其端點不得由使用者輸入覆寫。

### 3.7 內部端點認證（2026-11-22，NFR-003 SEC-INT-1、CQ-34）
- `/api/internal/**` 只認 `X-Internal-Secret`；標頭缺漏、不符、只帶一般使用者 token、只帶 ADMIN token → 403 `FORBIDDEN`（§2.6 格式），不處理任何資料；不套 §2.8 限流。
- **比對方式**：`InternalSecretFilter` 啟動時先算設定值的 SHA-256；每次請求對收到的標頭值也做 SHA-256，再以 `MessageDigest.isEqual` 比較兩個 32 bytes 摘要（避免長度不同時提早返回造成時間差；現況直接比較原始 bytes）。
- 驗證失敗只記 WARN（路徑、來源 IP、traceId），log 不含標頭值。
- `INTERNAL_CRON_SECRET` ≥ 32 bytes 隨機、無預設值、只經 Secret Manager 注入；外洩立即輪替，平時每年一次，Cloud Scheduler 標頭與 GitHub Actions secret 同步更新（部署清單，雲端變更需使用者當次同意）。替代方案（Cloud Scheduler OIDC token）列為後續強化。
- 迴歸：`InternalCronSecurityTest` 涵蓋上列四種拒絕情況與 log 擷取。

## 4. 檔案與資料共用機制
### 4.1 檔案儲存命名與清理 (GCS)
- **路徑格式**：`/{bucket}/{plan_tier}/{date}/{order_id}/{file_uuid}`。
- **不可變原則 (Immutability)**：檔案寫入 GCS 後路徑即凍結。若發生方案變更（升/降級），**不進行檔案搬移**。
- **自動清理**：配合 `PRD-013`，利用 GCS Lifecycle Management 根據路徑前綴自動刪檔。**Lifecycle 規則只能匹配媒體前綴（`/{bucket}/{plan_tier}/...`），`payment-proofs/`、`refund-proofs/`、`kyc/`、`care_media/` 必須排除**（財務憑證與證件永久保存，baseline §5）。真正的存取權限與「已過期」顯示由 DB 欄位控管。
- **永久儲存路徑例外**：涉及長期客戶關係之檔案（如 PRD-021 照護媒體庫），必須使用獨立的 `/{bucket}/care_media/{sitter_id}_{client_id}/` 路徑，絕對禁止寫入帶有生命週期週期前綴的資料夾，以免遭排程誤刪。

### 4.2 快照機制 (Snapshot Pattern)
> **2026-11-21**：快照時點改為**報價時**（baseline §2.2、CQ-20），下列「進入 CONFIRMED 時」已過時。
- **實作規則**：當訂單進入 `CONFIRMED` 時，系統必須對當時的「單價」、「媒體保留規則」執行實體快照，存入 `ORDER_SNAPSHOT` 表，確保後續業務邏輯不受保母方案異動影響。

### 4.3 業務稽核日誌 (Audit Trail)
- **實作規則**：全域使用 `order_logs` 表紀錄關鍵動作（狀態變更、財務修改、免責同意），作為日後爭議調解的唯一憑證。
- **同交易（2026-11-22，NFR-003 SEC-AUDIT-1）**：稽核寫入方法（`OrderLogWriter` 與各模組稽核寫入者）交易傳播一律 `MANDATORY`，無交易呼叫即拋例外；**禁止** `REQUIRES_NEW`、`@Async`、`AFTER_COMMIT` / `TransactionSynchronization` hook 寫稽核（現況 `AuditLogService` 以 `REQUIRES_NEW` 寫帳號類稽核，NFR-003 G-13，隨 SD-IDENTITY 重建改寫）。唯一例外：登入鎖定的稽核寫在登入失敗計數器的獨立交易內（SD-IDENTITY）。
- **外部 I/O 與稽核的順序**：刪除類（GCS 刪除）先在交易外完成 I/O，成功後才開交易寫狀態標記（例 `purged = true`、`object_deleted_at`）與稽核；寄信類在主交易內寫 outbox（`notifications`），不以寄送結果寫稽核。IT：注入失敗使主交易回滾 → 稽核 0 列；成功 → 恰 1 列。
- **append-only（NFR-003 SEC-AUDIT-3）**：稽核實體（`order_logs` 與各稽核表）的 Repository 繼承共用 `AppendOnlyRepository<T, ID>`（只有 `insert` 與查詢方法，Spring Data `Repository` 標記介面），**不得**繼承 `CrudRepository` / `JpaRepository`（現況 `OrderLogRepository extends JpaRepository`，需改）。AU `AuditAppendOnlyArchTest`：稽核實體的 Repository 都不是 `CrudRepository` 子型別、沒有 `delete*` / `save*` 方法。沒有任何 API 可修改或刪除稽核。MVP 只做應用層防護，不加 DB trigger（CQ-33）；交易相關稽核保存 ≥ 5 年（NFR-008）。帳號註銷時稽核列保留，只存 userId。
- **內容**：每列含操作者（userId；系統為 `SYSTEM`）、UTC 時間、動作碼、目標 id、變動摘要；收款帳號只存銀行代碼 + 帳號後五碼；不存密碼、token、完整帳號、戶名（SEC-AUDIT-2）。

## 5. 開發工作流與 CI/CD (DevOps & Workflow)
### 5.1 版本控制 (Git Strategy)
- 採用輕量化 **GitHub Flow**：`main` 分支保持 Production-ready，功能開發使用 `feature/*`。
- **Pre-commit Hook**：使用 `Husky` + `lint-staged`。在 `git commit` 當下自動執行 Linting 與 Formatting。

### 5.2 程式碼風格 (Code Style)
- **Backend (Java)**：導入 `Spotless` (Google Java Format)，於編譯階段卡控。
- **Frontend (Next.js)**：強制 `ESLint` + `Prettier`。

### 5.3 CI/CD 部署管線 (GitHub Actions)
- **CI**: 發起 PR 時執行 Lint 檢查與 Build Test，失敗則阻擋合併。
- **CD**: 2026-11-21 起改為手動觸發（`gh workflow run deploy.yml --ref main`），push main 不再自動部署；流程仍為「測試 -> Docker Build -> Artifact Registry -> Cloud Run Deploy -> Firebase -> E2E」。

## 6. 單人開發效率與自動化規範 (Solo Developer Efficiency)
### 6.1 資料庫遷移
- 統一使用 **Flyway** 管理資料庫版本。
- 嚴禁手動於控制台修改 Schema，所有異動必須轉化為 `V__xxx.sql` 腳本，確保「資料庫即代碼」。

### 6.2 自動化文檔與測試
- **API 文檔**: 導入 `springdoc-openapi` (Swagger UI)，路徑固定為 `/swagger-ui.html`。**prod 停用**（2026-11-22，NFR-003 U-3、SEC-LOG-3、CQ-31）：`application-prod.yml` 設 `springdoc.api-docs.enabled=false`、`springdoc.swagger-ui.enabled=false`；prod 的 `SecurityConfig` 不放行 Swagger 路徑（§3.4.1）。prod profile IT：帶有效 token `GET /v3/api-docs`、`GET /swagger-ui.html` → 404 `NOT_FOUND`（未映射路徑的 `NoResourceFoundException` 須對映 404，不得落入 500）；匿名 → 401。`docs/api/openapi.json` 以非 prod profile 產出。
- **測試策略**: 優先確保「核心業務路徑 (Golden Path)」的整合測試通過。

### 6.3 監控與維運
- **主動告警**: 串接 `GCP Cloud Logging & Error Reporting`。生產環境噴 Exception 時，系統必須主動發信通知開發者。
- **健康檢查**: 開啟 `Spring Boot Actuator`。
  - **資安規範**：`exposure.include` 僅限 `health`，禁止暴露 `env` 或 `beans`。
  - **隱私規範**：`show-details` 設為 `never`，防止向外部洩漏資料庫連線細節。

---

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
| 2026-11-22 | 使用者同意之核准後修訂（狀態維持 Approved）：§1.2 前端改由 Firebase Hosting 提供、後端映像不含前端、移除靜態檔 permitAll、支援期（U-1、SEC-SCA-3）；§2.6 traceId 限 `^[0-9a-f]{32}$` 否則重新產生（U-2）、`message` 不得帶框架 / JDK 例外原文、429 `RATE_LIMITED`；新增 §2.8 請求解析與濫用防護（非冪等 body 1 MB、Jackson 限制、`BigDecimal` 範圍、regex、分頁上限 100、限流與來源 IP 取法）、§2.9 日誌遮蔽與 CR / LF 注入；§3.4 身分只認 `Authorization`、新增 §3.4.1 匿名白名單逐條路由樣板與 URL 層規則、AU / IT（U-4）；新增 §3.5 API 資安標頭與 CORS 白名單、§3.6 SSRF、§3.7 內部端點雙邊 SHA-256 比對；§4.3 稽核同交易、外部 I/O 順序、append-only；§6.2 prod 停用 springdoc（U-3） | NFR-003 核准回寫，使用者 2026-11-22 同意 |
| 2026-11-22 | §2.4 補空 body 視為 `{}`、64 KB 只限 JSON、認領前已完成回 409、錯誤欄位名；§2.6 補 traceId 來源、鎖 / 完整性對映只限重建模組；§2.7 空白定義加 U+00A0 | A 階段實作回饋，使用者 2026-11-22 核准 |
