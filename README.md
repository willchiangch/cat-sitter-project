# Cat Sitter PWA (Core Engine)

這是貓保姆預約系統的 Monorepo 專案，包含 PWA 前端與 Java Spring Boot 後端核心引擎。

## 🚀 技術棧 (Tech Stack)

### 後端 (Backend)
- **Framework**: Spring Boot 4.0.6 (Java 21)
- **Database**: PostgreSQL 16
- **Migration**: Flyway 11.x
- **ORM**: Hibernate 7.x (Jakarta Persistence 3.2)
- **Auth**: Spring Security 6 + JJWT 0.12.6 (Stateless JWT)
- **Monitoring**: Spring Boot Actuator (健康檢查曝露限制 /actuator/health)
- **OpenAPI**: Springdoc OpenAPI / Swagger UI (開發期匿名存取 /swagger-ui.html)

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

| 模組 | SD 設計 | 實作狀態 | 測試狀態 | 備註 |
| :--- | :---: | :---: | :---: | :--- |
| **SD-000 身分驗證** | ✅ | Backend ✅ | ✅ TS-000 | JWT 無狀態認證與 RBAC 權限控管 |
| **SD-001 角色切換與預約門禁** | ✅ | 前後端 ✅ | ✅ TS-001 | 支援角色雙向切換（懶加載 Profile）、SaaS 階梯式門禁卡控（Free鎖定、Pro黑名單、Ultimate無限制）、黑白名單互斥防禦與免填問卷卡控，已完成前後端聯調 |
| **SD-002 毛孩資料與注意事項管理** | ✅ | 前後端 ✅ | ✅ TS-002 | 支援 8 大物種選單、雙欄管理版面、醫療/個性/環境備註共同編輯與防重複點選、JPA 樂觀鎖衝突 (409) 提示與進行中服務刪除卡控 |
| **SD-003 服務方案** | ✅ | Backend ✅ | ✅ TS-003 | 支援自訂任務 SOP、SaaS 四級日期區間控制 |
| **SD-005 預約申請** | ✅ | Backend ✅ | ✅ TS-005 | 支援 Advisory Lock 並升級多項目複合式排程矩陣 |
| **SD-006 報價快照** | ✅ | Backend ✅ | ✅ TS-006 | 支援 SaaS Gating 與零信任校驗 |
| **SD-007 線下付款憑證** | ✅ | 前後端 ✅ | ✅ TS-007 | 支援 AES-256-GCM 銀行帳戶加密儲存、BOLA 橫向越權防護、限額與檔案格式校驗、退回重填與非同步事件通知，E2E 綠燈通過 |
| **SD-008 服務執行與 Check-in** | ✅ | 前後端 ✅ | ✅ TS-008 | 支援選填 `Idempotency-Key`、異步事件防幽靈通知、訂單 `CONFIRMED -> IN_PROGRESS` 狀態機流轉、大按鈕單手防呆控制、E2E 綠燈通過 |
| **SD-009 訂單結案** | ✅ | 前後端 ✅ | ✅ TS-009 | 支援 72hr 殭屍清理與 48hr 自動結案，前端結案與爭議提報已與後端對接 |
| **SD-016 訂單變更** | ✅ | 前後端 ✅ | ✅ TS-016 | 支援跨日容量校驗與加減價差額試算，前端雙向變更與線下退款確認已與後端對接 |
| **SD-021 照護記事與媒體庫** | ✅ | 前後端 ✅ | ✅ TS-021 | 支援 Recreate-on-Save、Append-Only 模板套用、409 冪等防重送、GCS 失敗補償機制與 IDOR 雙重角色放行，前端照護管理與照片牆已與後端對接 |
| **SD-022 行程照護日誌** | ✅ | 前後端 ✅ | ✅ TS-022 | PRD-022 已 Approved，Pure Lazy 逾期判定、SaaS 四級媒體配額、合約保護快照。已完成前後端聯調，防呆、版本衝突樂觀鎖及檔案檢核實裝。 |
| **SD-017 保母實名認證 (KYC)** | ✅ | Backend ✅ | ✅ TS-017 | KYC 狀態機 (UNVERIFIED→PENDING_REVIEW→VERIFIED/REJECTED/SUSPENDED)、GCS Signed URL 10min TTL、Partial Unique Index 防並發重送、BookingService 雙重卡控 (isOpen + VERIFIED)、@Version 樂觀鎖、AFTER_COMMIT 非同步事件通知 |
| **SD-FRONTEND-SPEC**| ✅ | Frontend ✅ | ✅ E2E | 預約精靈重構為方案導向 (Plan-Oriented) 流程，支援卡片選擇與日期互斥，完成 2-Step E2E 驗證 |

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

### 3. 執行後端測試
執行全部或特定模組的單元與整合測試（整合測試使用 Testcontainers 自動啟動 PostgreSQL 進行驗證）：
```bash
cd backend
# 執行全部測試
mvn test -Dmaven.compiler.release=17

# 執行 SD-021 照護記事與媒體庫測試
mvn test -Dmaven.compiler.release=17 -Dtest=CareNoteServiceTest,CareMediaServiceTest,CareNoteControllerTest,CareMediaControllerTest
```

### 4. 啟動與建置前端服務
進入 `frontend` 目錄並啟動開發伺服器或進行編譯：
```bash
cd frontend
# 安裝依賴
npm install

# 啟動開發伺服器 (Vite)
npm run dev

# 生產環境打包與 TypeScript 嚴格編譯檢查
npm run build
```

### 5. 開發進度追蹤
本專案使用 AI 助理協同開發，詳細進度與待辦事項請參考：
- [最新進度總結](file:///.agent/brain/walkthrough.md)
- [待辦清單 (Task List)](file:///.agent/brain/task.md)



---

## 📜 開發規範
- 時區統一使用 `UTC`。
- 金額統一使用 `INT` (最小單位，如分) 儲存，嚴禁使用 Float/Double。
- 遵循 `BaseEntity` 規範，包含 `UUID` 主鍵與自動審計欄位 (`created_at`, `updated_at`, `version`)。
