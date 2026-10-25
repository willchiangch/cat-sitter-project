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
  - **Frontend**: React + Vite，`npm run build` 產出的靜態檔案於 CI 階段複製進 Backend 的 `src/main/resources/static`，由 Spring Boot 內嵌 serve，與後端打包成同一個 Docker Image、部署為同一個 Cloud Run service（非獨立 nginx/Next.js 容器）。
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
- **前端顯示**：由前端根據 Client 時區轉換。

### 2.2 貨幣與精度 (Currency)
- **幣別**：新台幣 (TWD)。
- **儲存精度**：一律使用 `INT` 儲存於 DB。計算中途使用 `BigDecimal`，入庫前四捨五入。

### 2.3 併發控制 (Concurrency Control)
- **資料列級鎖**：實體更新使用 JPA `@Version` 樂觀鎖。
- **業務級分散式鎖**：需要跨列序列化的業務規則（例如 SD-021 記事模板 / 媒體上限、SD-022 草稿 get-or-create、SD-000 單一 session），統一使用 **PostgreSQL Advisory Locks**。
  - **不做檔期 / 容量鎖**：系統不做接單容量控管（PRD-005 / PRD-006，2026-10 產品決策），沒有「保母 + 日期」的檔期佔用鎖（2026-10 backlog 第 7 批移除 `CapacityGuard` 與 `AdvisoryLockService.acquireLocks/generateLockKey`）。同一張訂單的狀態轉移（`PENDING` → `PENDING_PAYMENT`）以訂單列 `SELECT ... FOR UPDATE` 序列化（SD-006 §2.1）。
  - **實作規範**：直接使用 `JdbcTemplate` 呼叫 `pg_advisory_xact_lock(lockId)`。
  - **生命週期**：必須與 Transaction 綁定（Transaction-level lock），確保事務結束（Commit/Rollback）時自動釋放，避免 Deadlock。

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
- **資源歸屬檢查**：Service 層以 token 身分比對資源擁有者（例：`order.sitter.id == token.userId`），找得到資源但不屬於呼叫者 → `AccessDeniedException` → **403**；資源不存在 → 400 / 404。不可用 `findByIdAndXxxId` 查不到就一律 400，否則分不出越權。
- **允許的例外**（參數是「操作對象」而非「呼叫者」）：公開查詢（`GET /api/sitters/{sitterId}/plans|questions`、`GET /api/sitter/profile/{sitterId}`）、對象型參數（收藏 / 黑名單 / 轉介的目標 `sitterId` / `ownerId`）、ADMIN 專用端點、配對型路徑（`/api/care-notes/{sitterId}/{ownerId}`：其中之一必須等於 token 身分，寫入端點限 `sitterId == token`，SD-021 §1.3）。
- **部署相容**：移除身分參數時，後端不宣告該參數即可——Spring 對未宣告的 query param / Jackson 對未知 JSON 欄位（`FAIL_ON_UNKNOWN_PROPERTIES=false`）都直接忽略，舊前端在「後端已部署、前端未部署」的空窗期仍可運作。部署順序：先後端再前端。
- **已套用的端點（fix-batch11）**：`POST /api/orders/{id}/confirm|quote|reject`（SITTER）、`/complete|/dispute`（OWNER）——原本 `@RequestParam sitterId/ownerId`；`POST /api/orders/booking`（OWNER）——原本 body `ownerId`。2026-10-25 全 controller 掃描（23 支）後，其餘端點的呼叫者身分都已取自 token；仍收 `sitterId` / `ownerId` 的只剩上列「允許的例外」（`/api/sitter/trust-circle/referral-candidates?ownerId=` 與 `CreateReferralRequest.ownerId` 是 PRD-010 D 主動分享的通知對象）。
- **迴歸測試**：`OrderIdentityFromTokenSecurityTest`（真 JWT + 真 `SecurityConfig` filter chain，不用 `@WithMockUser`）——每支端點「別人的 token 操作他人訂單 → 403」「錯角色 → 403」「本人 → 200（多帶亂填的舊身分參數也被忽略）」。

## 4. 檔案與資料共用機制
### 4.1 檔案儲存命名與清理 (GCS)
- **路徑格式**：`/{bucket}/{plan_tier}/{date}/{order_id}/{file_uuid}`。
- **不可變原則 (Immutability)**：檔案寫入 GCS 後路徑即凍結。若發生方案變更（升/降級），**不進行檔案搬移**。
- **自動清理**：配合 `PRD-013`，利用 GCS Lifecycle Management 根據路徑前綴自動刪檔。真正的存取權限與「已過期」顯示由 DB 欄位控管。
- **永久儲存路徑例外**：涉及長期客戶關係之檔案（如 PRD-021 照護媒體庫），必須使用獨立的 `/{bucket}/care_media/{sitter_id}_{client_id}/` 路徑，絕對禁止寫入帶有生命週期週期前綴的資料夾，以免遭排程誤刪。

### 4.2 快照機制 (Snapshot Pattern)
- **實作規則**：當訂單進入 `CONFIRMED` 時，系統必須對當時的「單價」、「媒體保留規則」執行實體快照，存入 `ORDER_SNAPSHOT` 表，確保後續業務邏輯不受保母方案異動影響。

### 4.3 業務稽核日誌 (Audit Trail)
- **實作規則**：全域使用 `order_logs` 表紀錄關鍵動作（狀態變更、財務修改、免責同意），作為日後爭議調解的唯一憑證。

## 5. 開發工作流與 CI/CD (DevOps & Workflow)
### 5.1 版本控制 (Git Strategy)
- 採用輕量化 **GitHub Flow**：`main` 分支保持 Production-ready，功能開發使用 `feature/*`。
- **Pre-commit Hook**：使用 `Husky` + `lint-staged`。在 `git commit` 當下自動執行 Linting 與 Formatting。

### 5.2 程式碼風格 (Code Style)
- **Backend (Java)**：導入 `Spotless` (Google Java Format)，於編譯階段卡控。
- **Frontend (Next.js)**：強制 `ESLint` + `Prettier`。

### 5.3 CI/CD 部署管線 (GitHub Actions)
- **CI**: 發起 PR 時執行 Lint 檢查與 Build Test，失敗則阻擋合併。
- **CD**: `main` 分支合併後，自動觸發「Docker Build -> Artifact Registry -> Cloud Run Deploy」，實現無縫切換。

## 6. 單人開發效率與自動化規範 (Solo Developer Efficiency)
### 6.1 資料庫遷移
- 統一使用 **Flyway** 管理資料庫版本。
- 嚴禁手動於控制台修改 Schema，所有異動必須轉化為 `V__xxx.sql` 腳本，確保「資料庫即代碼」。

### 6.2 自動化文檔與測試
- **API 文檔**: 導入 `springdoc-openapi` (Swagger UI)，路徑固定為 `/swagger-ui.html`。
- **測試策略**: 優先確保「核心業務路徑 (Golden Path)」的整合測試通過。

### 6.3 監控與維運
- **主動告警**: 串接 `GCP Cloud Logging & Error Reporting`。生產環境噴 Exception 時，系統必須主動發信通知開發者。
- **健康檢查**: 開啟 `Spring Boot Actuator`。
  - **資安規範**：`exposure.include` 僅限 `health`，禁止暴露 `env` 或 `beans`。
  - **隱私規範**：`show-details` 設為 `never`，防止向外部洩漏資料庫連線細節。

---


