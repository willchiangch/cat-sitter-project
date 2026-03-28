# 貓咪到府保母 PWA (Cat Sitter)

為專職貓咪保母打造的雙角色（**保母 / 飼主**）預約與照護管理系統，採前後端分離 Monorepo，部署於 GCP。

**目前版本：V9 (Testing Suite Stabilized & Account Role Switching)**

---

## 專案結構 (Monorepo)

```
cat-sitter-project/
├── .agent/            # AI 開發大腦同步目錄 (進度備份)
├── frontend/          # React PWA（Vite + Tailwind CSS + Zustand/Context）
├── backend/           # Java Spring Boot 3.x（RESTful API + Spring Data JPA）
├── .github/workflows/  # CI/CD（前端 Firebase Hosting、後端 Cloud Run）
└── README.md
```

- **前端**：SPA/PWA，代管於 **Firebase Hosting**。
- **後端**：無狀態 API，代管於 **Cloud Run**；資料庫為 **Cloud SQL (PostgreSQL 15+)**；檔案存 **GCS**。
- **通訊**：RESTful API、JSON、認證使用 **JWT**（Header 攜帶，無 Server Session）。

---

## 技術棧

| 層級     | 技術 |
|----------|------|
| 前端     | React 19、Vite 7、Tailwind CSS 4、Zustand/Context |
| 後端     | Java 21、Spring Boot 3.4.3、Spring Data JPA |
| 資料庫   | PostgreSQL 15+（本地 Docker Compose，正式 Cloud SQL） |
| 安全認證 | Spring Security + JWT (Stateless, JJWT) |
| 資料庫版控| Flyway（Schema V9: 帳號角色切換持久化、支付、訂閱、行事曆、媒體） |

---

## 核心功能模組

- **雙軌行事曆同步**：支援 Google Calendar OAuth2 同步與 Universal iCal Feed (Apple/iOS)。
- **財務與訂閱**：整合 PAYUNi 金流，支援保母訂閱方案與促銷折扣碼。
- **多媒體管理**：具備 60 天自動保留政策 (Retention Policy) 的媒體存儲系統。
- **可觀測性 (Observability)**：
  - **MDC Trace ID**：每個請求皆有唯一追蹤碼。
  - **全域異常處理**：異常發生時自動捕捉並於日誌中標註 Trace ID。

---

## 安全架構
本系統採用 **無狀態 (Stateless) JWT 認證**：
- **認證流程**：登入後取得 Access Token 與 Refresh Token。
- **請求驗證**：在 Header 中使用 `Authorization: Bearer <Token>`。
- **身分稽核**：透過 `AuditableEntity` 自動紀錄 `created_by` 等稽核資訊。

---

## 本地開發

### 必要環境

- **Node.js** 20+（前端）
- **Java** 21、**Maven**（後端）
- **Docker / Docker Compose**（本地 PostgreSQL，預設 `localhost:5432`）

### 啟動步驟

1. **啟動本地資料庫**（根目錄若有 `docker-compose.yml`）  
   ```bash
   docker compose up -d
   ```

2. **後端**  
   ```bash
   cd backend
   mvn spring-boot:run
   ```  
   本機設定（含 DB、JWT 等）請用 `application-local.properties` 或 `application-local.yml`（已列入 `.gitignore`，勿提交機密）。

3. **前端**  
   ```bash
   cd frontend
   npm install
   npm run dev
   ```

### 測試執行 (Verification)

- **後端單元/整合測試**:
  ```bash
  cd backend && ./mvnw test
  ```
- **核心業務冒煙測試 (Backend Smoke)**:
  ```bash
  cd backend && ./mvnw test -Dtest=com.catsitter.api.smoke.*
  ```
- **前端 API 冒煙測試 (Frontend/Smoke)**:
  ```bash
  # 需先啟動後端伺服器 (port 8081)
  cd frontend && npx playwright test tests/smoke/
  ```
- **壓力測試 (Performance/k6)**:
  ```bash
  # 建議使用 Docker 執行以免去本地安裝 k6
  docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 grafana/k6 run - < backend/src/test/resources/performance/webhook-smoke.js
  ```

前端會依 `VITE_*` 環境變數接後端 API；後端時間以 UTC、ISO-8601 傳輸，前端可轉為 `Asia/Taipei` 顯示。

---

## 分支與 CI/CD

| 分支      | 環境     | 說明 |
|-----------|----------|------|
| `main`    | Production | 正式環境部署 |
| `develop` | UAT      | 測試環境部署 |

- 開發請開 **Feature branch**，完成後 PR 合併至 `develop`。
- **路徑過濾**：僅 `frontend/**` 變更觸發前端管線；僅 `backend/**` 變更觸發後端管線。  
詳見 [.github/workflows/CI_CD_GUIDELINES.md](.github/workflows/CI_CD_GUIDELINES.md)。

---

## 其他規範摘要

- **時區**：DB 使用 `timestamptz`；後端 UTC + ISO-8601；前端依需求轉本地時區。
- **金流**：後端以 **策略模式** 依賴 `PaymentGateway` 介面，綠界等實作獨立 Adapter，Webhook 需具冪等性。
- **進度同步**：開發者需定期同步 `.agent/brain/` 下的 `task.md` 與 `walkthrough.md` 至專案庫。

更完整的架構與開發守則見：
- [後端開發規範 (TDD & 測試策略)](backend/DEVELOPMENT_GUIDELINES.md)
- [業務測試情境 (Scenarios)](scenario/)
- 專案根目錄 `.cursorrules` 與 `.cursor/rules/cat-sitter-rule.mdc`
- [核心資料庫規格書 (Schema V9)](doc/schema.md)
