# SD-GLOBAL-SPEC: 全域架構與開發規範

## 1. 基礎設施與成本定調 (Infrastructure Strategy)
- **原則**：MVP 階段嚴控預算，極小化雲端帳單。
- **Database**: GCP Cloud SQL (PostgreSQL)，初期僅選用 `db-f1-micro`。
- **Computing**: Cloud Run 開啟 `min-instances: 0`（無請求時不計費）。
- **Cache**: **MVP 階段不導入 Redis/Memorystore**。
- **Logging**: 全域使用 SLF4J 輸出至 Console，由 GCP Cloud Logging 自動收集，禁止引入 ELK 或第三方監控工具。

## 1.2 容器化標準 (Containerization Standard)
- **基底鏡像**：
  - **Backend**: `eclipse-temurin:21-jre-jammy` (Java 21 LTS), **Spring Boot 4.0.x**。
  - **Frontend**: `node:24-alpine` (構建階段) -> `nginx:alpine` 或直接用 Next.js standalone 模式。
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
- **業務級分散式鎖**：針對 PRD-005 的「檔期佔用鎖」，統一使用 **PostgreSQL Advisory Locks**。
  - **實作規範**：直接使用 `JdbcTemplate` 呼叫 `pg_advisory_xact_lock(lockId)`。
  - **生命週期**：必須與 Transaction 綁定（Transaction-level lock），確保事務結束（Commit/Rollback）時自動釋放，避免 Deadlock。

## 3. 開發效率與權限規範 (SaaS Gating)
### 3.1 SaaS Gating via AOP
- **實作方式**：自定義註解 `@RequirePlan(PlanType)` 搭配 Spring AOP 攔截器。
- **規範**：禁止在 Service 層寫死方案判斷，統一由 Controller 層標註權限。

### 3.2 JWT 最小化 Payload
- **規範**：Token 僅存放 `accountId` 與 `currentRole`。
- **禁令**：禁止將 `planType` 塞入 JWT。權限檢查統一由攔截器即時查庫或一級緩存，確保降級/升級即時生效。

### 3.3 外部服務隔離 (Mocking Layer)
- **規範**：所有外部服務（Google Calendar、金流、簡訊）必須實作 `MockService` 開關。
- **目的**：開發測試時禁止真實呼叫第三方 API，節省額外頻寬與呼叫費用。

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


