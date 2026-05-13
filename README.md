# Cat Sitter PWA (Core Engine)

這是貓保姆預約系統的 Monorepo 專案，包含 PWA 前端與 Java Spring Boot 後端核心引擎。

## 🚀 技術棧 (Tech Stack)

### 後端 (Backend)
- **Framework**: Spring Boot 4.0.6 (Java 21)
- **Database**: PostgreSQL 16
- **Migration**: Flyway 11.x
- **ORM**: Hibernate 7.x (Jakarta Persistence 3.2)
- **Auth**: Spring Security 6 + JJWT 0.12.6 (Stateless JWT)

### 前端 (Frontend)
- **Framework**: React 19 + Vite 8
- **Design System**: Stitch "The Intuitive Concierge" (Editorial Aesthetic)
- **Style**: Vanilla CSS + CSS Variables (No-Line Philosophy)
- **Testing**: Playwright (E2E) + TS Strict Mode Enabled

---

## 📂 專案結構

```text
.
  ├── backend/            # Java Spring Boot 核心引擎
  │   ├── src/main/java   # 業務邏輯 (Domain Model, Service, API)
  │   └── src/main/resources/db/migration # Flyway SQL 遷移腳本
  ├── frontend/           # React PWA 前端專案 (開發中)
  ├── docs/               # 專案文件、系統設計 (SD) 與系統分析 (SA)
  ├── .agent/brain/       # AI 助理開發進度持久化目錄 (task.md, walkthrough.md)
  ├── docker-compose.yml  # 本地 PostgreSQL 16 環境設定
  └── README.md           # 專案說明文件
```

---

## 📊 核心引擎開發狀態 (Module Status)

| 模組 | SD 設計 | Backend 實作 | 測試狀態 | 備註 |
| :--- | :---: | :---: | :---: | :--- |
| **SD-000 身分驗證** | ✅ | ✅ | ✅ TS-000 | JWT 無狀態認證與 RBAC 權限控管 |
| **SD-005 預約申請** | ✅ | ✅ | ✅ TS-005 | 支援 Advisory Lock 並升級多項目複合式排程矩陣 |
| **SD-006 報價快照** | ✅ | ✅ | ✅ TS-006 | 支援 SaaS Gating 與零信任校驗 |
| **SD-009 訂單結案** | ✅ | ✅ | ✅ TS-009 | 支援 72hr 殭屍清理與 48hr 自動結案 |
| **SD-016 訂單變更** | ✅ | ✅ | ✅ TS-016 | 支援跨日容量校驗與加減價差額試算 |
| **SD-FRONTEND-SPEC**| ✅ | ✅ | ✅ E2E | 預約精靈 Refactor 完成，支援動態趟次與 3-Step 截圖驗證 |

---

## 🛠️ 本地開發環境啟動

### 1. 啟動資料庫 (Docker)
確保已安裝 Docker 並啟動 PostgreSQL 16：
```bash
docker-compose up -d
```

### 2. 啟動後端服務
進入 `backend` 目錄並使用 Maven 啟動：
```bash
cd backend
export JAVA_HOME=<path_to_java_21>
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```
*後端啟動後會自動執行 Flyway 遷移，初始化 Schema。*

### 3. 開發進度追蹤
本專案使用 AI 助理協同開發，詳細進度與待辦事項請參考：
- [最新進度總結](file:///.agent/brain/walkthrough.md)
- [待辦清單 (Task List)](file:///.agent/brain/task.md)

---

## 📜 開發規範
- 時區統一使用 `UTC`。
- 金額統一使用 `INT` (最小單位，如分) 儲存，嚴禁使用 Float/Double。
- 遵循 `BaseEntity` 規範，包含 `UUID` 主鍵與自動審計欄位 (`created_at`, `updated_at`, `version`)。
