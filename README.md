# Cat Sitter PWA (Core Engine)

這是貓保姆預約系統的 Monorepo 專案，包含 PWA 前端與 Java Spring Boot 後端核心引擎。

## 🚀 技術棧 (Tech Stack)

### 後端 (Backend)
- **Framework**: Spring Boot 4.0.6 (Java 21)
- **Database**: PostgreSQL 16（本地開發用 Docker；正式環境為 Supabase Postgres, ap-southeast-2）
- **Migration**: Flyway 11.x
- **ORM**: Hibernate 7.x (Jakarta Persistence 3.2)
- **Auth**: Spring Security 7 + JJWT 0.12.6 (Stateless JWT)
- **Monitoring**: Spring Boot Actuator (健康檢查曝露限制 /actuator/health)
- **OpenAPI**: Springdoc OpenAPI / Swagger UI (`/swagger-ui.html`、`/v3/api-docs` 僅非 prod profile 匿名開放，正式環境需驗證)

### 前端 (Frontend)
- **Framework**: React 19 + Vite 8 + react-router (正式路由，`RequireAuth` 登入守衛)
- **Design System**: Stitch "The Intuitive Concierge" (Editorial Aesthetic)
- **Style**: Vanilla CSS + CSS Variables (No-Line Philosophy)
- **PWA**: vite-plugin-pwa（`virtual:pwa-register` 手動註冊，偵測新版本自動 reload）
- **Testing**: Playwright (E2E) + TS Strict Mode Enabled

---

## 📂 專案結構

```text
.
  ├── backend/            # Java Spring Boot 核心引擎
  │   ├── src/main/java   # 業務邏輯 (Domain Model, Service, API)
  │   └── src/main/resources/db/migration # Flyway SQL 遷移腳本
  ├── frontend/           # React PWA 前端專案 (已部署正式環境)
  ├── docs/               # 專案文件、系統設計 (SD) 與系統分析 (SA)
  ├── .agent/brain/       # AI 助理開發進度持久化目錄 (task.md, walkthrough.md)
  ├── docker-compose.yml  # 本地 PostgreSQL 16 環境設定
  └── README.md           # 專案說明文件
```

---

## 📊 核心引擎開發狀態 (Module Status)

| 模組 | SD 設計 | 實作狀態 | 測試狀態 | 備註 |
| :--- | :---: | :---: | :---: | :--- |
| **SD-000 身分驗證** | ✅ | 前後端 ✅ | ✅ TS-000 | JWT 無狀態認證與 RBAC 權限控管、登入 5 次失敗鎖定 10 分鐘 (429)、忘記密碼/重設密碼流程 (Resend 寄信)、Email OTP 註冊驗證（10 分鐘效期/60 秒重寄冷卻/5 次錯誤鎖定）、帳號註銷 (軟刪除，未結案訂單卡控 + 信任圈/我的最愛自動清除 + 公開頁 404)、Google 第三方登入 (GIS ID Token 驗證，既有 Email 自動綁定 + 新 Email 選角色建立帳號)、生物辨識登入 (WebAuthn/FIDO2，Yubico 官方函式庫，一人可綁多裝置)、登出所有裝置 |
| **SD-001 角色切換與預約門禁** | ✅ | 前後端 ✅ | ✅ TS-001 | 支援角色雙向切換（懶加載 Profile）、SaaS 階梯式門禁卡控（Free鎖定、Pro黑名單、Ultimate無限制）、黑白名單互斥防禦與免填問卷卡控，已完成前後端聯調 |
| **SD-002 毛孩資料與注意事項管理** | ✅ | 前後端 ✅ | ✅ TS-002 | 支援 8 大物種選單、雙欄管理版面、醫療/個性/環境備註共同編輯與防重複點選、JPA 樂觀鎖衝突 (409) 提示與進行中服務刪除卡控 |
| **SD-003 服務方案** | ✅ | Backend ✅ | ✅ TS-003 | 支援自訂任務 SOP、SaaS 四級日期區間控制、適用寵物類型涵蓋 8 種（與 PRD-002 同步） |
| **SD-004 事前問卷設定** | ✅ | 前後端 ✅ | ✅ TS-004 | 保母自訂單選/多選/文字題目（上限 20 題），飼主預約時動態渲染填答，答案以 questionText 快照寫入訂單，題目異動不影響歷史訂單 |
| **SD-005 預約申請** | ✅ | 前後端 ✅ | 🟡 TS-005 (4/5) | 支援 Advisory Lock 並升級多項目複合式排程矩陣、Zero-Trust 價格校驗、動態問卷必填驗證 |
| **SD-006 報價快照** | ✅ | Backend ✅ | ✅ TS-006 | 支援 SaaS Gating 與零信任校驗 |
| **SD-007 線下付款憑證** | ✅ | 前後端 ✅ | ✅ TS-007 | 支援 AES-256-GCM 銀行帳戶加密儲存、BOLA 橫向越權防護、限額與檔案格式校驗、退回重填與非同步事件通知，E2E 綠燈通過 |
| **SD-008 服務執行與 Check-in** | ✅ | 前後端 ✅ | ✅ TS-008 | 支援選填 `Idempotency-Key`、異步事件防幽靈通知、訂單 `CONFIRMED -> IN_PROGRESS` 狀態機流轉、大按鈕單手防呆控制、E2E 綠燈通過 |
| **SD-009 訂單結案** | ✅ | 前後端 ✅ | ✅ TS-009 | 支援 72hr 殭屍清理與 48hr 自動結案，前端結案與爭議提報已與後端對接、管理員爭議調解二次密碼驗證 (403)、保母帳務總覽（月份篩選+總營收） |
| **SD-010 信任圈與轉介** | ✅ | 前後端 ✅ | ✅ TS-010 | 保母間雙向同意制信任圈、婉拒訂單時轉介信任圈成員（黑名單前置過濾）、跨角色通知 |
| **SD-016 訂單變更** | ✅ | 前後端 ✅ | ✅ TS-016 | 支援跨日容量校驗與加減價差額試算，前端雙向變更與線下退款確認已與後端對接、保母報價/拒絕機制、`ModificationRequest` 狀態機、Zero-Trust 確認金額校驗 |
| **SD-021 照護記事與媒體庫** | ✅ | 前後端 ✅ | ✅ TS-021 | 支援 Recreate-on-Save、Append-Only 模板套用、409 冪等防重送、GCS 失敗補償機制與 IDOR 雙重角色放行，前端照護管理與照片牆已與後端對接 |
| **SD-022 行程照護日誌** | ✅ | 前後端 ✅ | ✅ TS-022 | PRD-022 已 Approved，Pure Lazy 逾期判定、SaaS 四級媒體配額、合約保護快照。已完成前後端聯調，防呆、版本衝突樂觀鎖及檔案檢核實裝。 |
| **SD-017 保母實名認證 (KYC)** | ✅ | 前後端 ✅ | ✅ TS-017 | KYC 狀態機 (UNVERIFIED→PENDING_REVIEW→VERIFIED/REJECTED/SUSPENDED)、GCS Signed URL 10min TTL、Partial Unique Index 防並發重送、BookingService 雙重卡控 (isOpen + VERIFIED)、@Version 樂觀鎖、AFTER_COMMIT 非同步事件通知；前端：保母 5 狀態分支上傳表單 + Admin 待審清單/審查詳情/停權工具；未實名保母公開頁與預約比照停權一律模糊化/攔截 |
| **SD-019 我的最愛保母** | ✅ | 前後端 ✅ | ✅ TS-019 | 飼主收藏保母（上限 50 位）、公開預約頁愛心 toggle、保母休息中/隱藏中狀態連動顯示、單向隱私（不通知保母） |
| **SD-020 內部信用指標管理** | ✅ | 前後端 ✅ | ✅ TS-020 | 管理後台增減保母信用點數（Idempotency-Key + 稽核日誌）、低於門檻標註高風險，僅後台可見；為 PRD-020 主流程 E，B/C/D 分別歸屬 SD-017/SD-009 |
| **SD-014 訊息中心與通知偏好** | ✅ | 前後端 ✅ | ✅ TS-014 | 支援未讀 Badge、下拉選單、分頁通知中心、iOS風格通知偏好（ACCOUNT_AUTH卡控）及保母端置頂 KYC Banner，E2E 綠燈通過 |
| **SD-018 保母公開檔案管理** | ✅ | 前後端 ✅ | ✅ TS-018 | 5 級 Gating 隱私卡控、Optional JWT 匿名讀取、GCS 事務外上傳 Cache-Busting、@Version 樂觀鎖、敏感詞過濾 TOCTOU 防禦，E2E 綠燈通過 |
| **Admin Subscription API** | — | 前後端 ✅ | — | 管理員手動覆寫保母 SaaS 方案等級與到期日（Close Beta 早鳥/補償用），`ADMIN_SUBSCRIPTION_SET` 審計日誌，無需獨立 SD |
| **SD-015 線上支付與金流整合** | 🟡 Designing | — | — | 設計第三方金流、Webhooks 簽章驗證、代收代付撥款紀錄（T+3）與高精度整數運算 |
| **SD-013 多媒體生命週期** | ✅ Implemented & COMPLIANT | 前後端 ✅ | ✅ TS-013 | 設計 GCP Cloud Scheduler + Internal API 相容 Cloud Run 物理清理、SaaS 方案升級追溯展延與前端 per-media 逾期佔位盒 |
| **SD-FRONTEND-SPEC**| ✅ | Frontend ✅ | ✅ E2E | 預約精靈重構為方案導向 (Plan-Oriented) 流程，支援卡片選擇與日期互斥，完成 2-Step E2E 驗證 |


---

## 🌐 正式環境部署 (Production Deployment)

前後端分離部署，`main` 分支 push 後由 GitHub Actions (`.github/workflows/deploy.yml`) 自動執行：

1. **後端測試閘門**：`mvn test` 全部通過才會繼續 build（擋在 Docker build 之前）。
2. **後端**：Docker image 建置後推送至 GCP Artifact Registry，部署至 **Cloud Run**（`asia-east1`，純 API，`--allow-unauthenticated`，機敏設定透過 GCP Secret Manager `--set-secrets` 注入）。
3. **前端**：靜態資源建置後部署至 **Firebase Hosting**（`wd-pet-sitter.web.app`），`/api/**` 透過 `run` rewrite 同源代理至 Cloud Run，避免 CORS。
4. **資料庫**：正式環境使用 **Supabase Postgres**（`ap-southeast-2`），透過 Transaction Pooler（應用查詢）與 Session Pooler（Flyway migration）連線。
5. **E2E 驗證**：對正式部署網址執行 Playwright 全量 E2E，跑完後（`if: always()`）呼叫內部端點軟刪除種子帳號測試訂單，避免持久化資料庫累積測試髒資料。

### 排程任務 (Cloud Scheduler)
`min-instances: 0` 的 Cloud Run 無法使用 Spring `@Scheduled`，內部 Cron 端點（`/api/internal/cron/**`，`X-Internal-Secret` 保護）改由 **GCP Cloud Scheduler**（`asia-east1`）主動觸發：

| Job | 端點 | 頻率 (UTC) |
| :--- | :--- | :--- |
| `cat-sitter-orders-auto-complete` | `orders/auto-complete` | 每小時 |
| `cat-sitter-media-expiry-warning` | `media/expiry-warning` | 每日 02:00 |
| `cat-sitter-media-cleanup` | `media/cleanup` | 每日 03:00 |
| `cat-sitter-notifications-cleanup` | `notifications/cleanup` | 每日 04:00 |

> **⚠️ 目前狀態為 `PAUSED`**：4 個 Job 已建立但手動暫停，尚未正式對外營運前不會自動執行。正式上線時需逐一恢復：
> ```bash
> for job in cat-sitter-orders-auto-complete cat-sitter-media-expiry-warning cat-sitter-media-cleanup cat-sitter-notifications-cleanup; do
>   gcloud scheduler jobs resume "$job" --project=wd-pet-sitter --location=asia-east1
> done
> ```
> 查詢目前狀態：`gcloud scheduler jobs list --project=wd-pet-sitter --location=asia-east1`

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
- [最新進度總結](.agent/brain/walkthrough.md)
- [待辦清單 (Task List)](.agent/brain/task.md)



---

## 📜 開發規範
- 時區統一使用 `UTC`。
- 金額統一使用 `INT` (最小單位，如分) 儲存，嚴禁使用 Float/Double。
- 遵循 `BaseEntity` 規範，包含 `UUID` 主鍵與自動審計欄位 (`created_at`, `updated_at`, `version`)。
