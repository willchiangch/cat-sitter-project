# 貓咪到府保母 PWA (Cat Sitter)

為專職貓咪保母打造的雙角色（**保母 / 飼主**）預約與照護管理系統，採前後端分離 Monorepo，部署於 GCP。

---

## 專案結構 (Monorepo)

```
cat-sitter-project/
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
| 前端     | React、Vite、Tailwind CSS、Zustand/Context |
| 後端     | Java 21、Spring Boot 3.x、Spring Data JPA |
| 資料庫   | PostgreSQL 15+（本地 Docker Compose，正式 Cloud SQL） |
| 版控     | Flyway（Schema 在 `backend/src/main/resources/db/migration/`） |
| 雲端     | GCP：Firebase Hosting、Cloud Run、Cloud SQL、GCS、Artifact Registry、Secret Manager |

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

更完整的架構與開發守則見專案根目錄 `.cursorrules` 與 `.cursor/rules/cat-sitter-rule.mdc`。
