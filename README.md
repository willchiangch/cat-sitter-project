# 貓咪到府保母 PWA (Cat Sitter)

為專職貓咪保母打造的雙角色（**保母 / 飼主**）預約與照護管理系統，採前後端分離 Monorepo，部署於 GCP。

**目前版本：V14 (E2E 16/16 全通過 — Backend Smoke + POM Fixes Complete)**

---

## 專案結構 (Monorepo)

```
cat-sitter-project/
├── .agent/            # AI 開發大腦同步目錄 (進度備份)
├── frontend/          # React PWA（Vite 7 + Tailwind CSS 4 + Zustand + PWA Plugin）
├── backend/           # Java Spring Boot 3.x（RESTful API + Spring Data JPA）
├── scenario/          # 業務測試情境（標註有 [Vitest RTL] 與 [Playwright E2E] 層級）
├── .github/workflows/  # CI/CD（前端 Firebase Hosting、後端 Cloud Run）
└── README.md
```

- **前端**：專業 PWA 應用，具備離線快取與桌面安裝能力，代管於 **Firebase Hosting**。
- **後端**：無狀態 API，代管於 **Cloud Run**；資料庫為 **Cloud SQL (PostgreSQL 15+)**；檔案存 **GCS**。
- **通訊**：RESTful API、JSON、認證使用 **JWT**（Header 攜帶，無 Server Session）。

---

## 技術棧

| 層級     | 技術 |
|----------|------|
| 前端     | React 19、Vite 7、Tailwind CSS 4、Zustand、**vite-plugin-pwa (Offline Cache)** |
| 測試架構 | **Vitest + RTL (元件邏輯)**、**Playwright POM (黃金流程 E2E)** |
| 後端     | Java 21、Spring Boot 3.4.3、Spring Data JPA |
| 資料庫   | PostgreSQL 15+（本地 Docker Compose，正式 Cloud SQL） |
| 安全認證 | Spring Security + JWT (Stateless, JJWT) + **X-Smoke-Auth (Mock Auth)** |
| 資料庫版控| Flyway（Schema V9: 帳號角色切換持久化、支付、訂閱、行事曆、媒體） |

---

## 核心功能模組

- **全面 PWA 體驗**：支援 iOS/Android 桌面安裝、全螢幕沉浸式設計、Workbox 離線請求快取策略。
- **雙軌行事曆同步**：支援 Google Calendar OAuth2 同步與 Universal iCal Feed (Apple/iOS)。
- **VIP 熟客白名單（客群門禁管理）**：保母可設定白名單，讓特定飼主預約時自動跳過繁瑣問卷，直達結帳；門禁管理入口整合於保母 Profile。
- **自動化 Onboarding**：全新社交登入使用者自動偵測並強制導航至身分設定流程。
- **財務與訂閱**：整合 PAYUNi 金流，支援保母訂閱方案與促銷折扣碼；Finance 頁分為「待付款」與「收款紀錄」雙 tab。
- **多媒體管理**：具備 60 天自動保留政策 (Retention Policy) 的媒體存儲系統；人臉辨識自拍取代身分證背面上傳。
- **接單專屬網址**：保母 Profile 提供可複製的個人預約連結（`/book/{slug}`）供對外推廣。
- **Client 寵物管理**：飼主 Profile 整合寵物列表卡片，可快速新增或進入完整管理頁。

---

## 混合測試策略 (Hybrid Testing)
本專案遵循 **測試金字塔 (Test Pyramid)** 原則以平衡穩定性與執行速度：
1. **底層：Vitest + React Testing Library**  
   針對細節 UI 狀態、表單驗證、數學計算（如加成報價）進行毫秒級驗證。
2. **頂層：Playwright (Page Object Model)**  
   針對「跨帳號角色」的黃金流轉（如預約、報價、完工）進行真實資料庫環境驗證。

---

## 本地開發

### 必要環境
- **Node.js** 20+（前端）
- **Java** 21、**Maven**（後端）
- **Docker / Docker Compose**

### 測試與驗證 (Verification)

#### 1. 前端元件測試 (Vitest)
```bash
cd frontend
npm run test           # 執行所有元件測試
```

#### 2. API 契約同步（OpenAPI → TypeScript SDK）

> 後端每次新增或修改 API 後，執行此步驟將最新 spec 同步至前端型別。

```bash
cd frontend
npm run api:sync       # 從後端抓 spec + 重新生成 TypeScript SDK（需後端運行）
npm run api:generate   # 僅重新生成 SDK（使用現有 backend/openapi.json）
```

- `api:fetch`：從 `http://localhost:8081/v3/api-docs` 抓取最新 spec，覆寫 `backend/openapi.json`
- `api:generate`：從 `backend/openapi.json` 生成 `frontend/src/services/gen/`（型別 + SDK + client）
- `api:sync`：以上兩步合一

#### 3. 前端 E2E 流程測試 (Playwright)
需啟動後端 `smoke` profile (Port 8081) 後執行：
```bash
cd frontend
npx playwright test    # 執行所有 POM 化後的 E2E 腳本
```

#### 4. 後端單元與冒煙測試
```bash
cd backend
./mvnw test            # 全體測試
./mvnw test -Dtest=com.catsitter.api.smoke.*  # 業務冒煙測試
```

---

## 進度持久化工作流 (Persist Progress)
為了確保跨環境開發的連續性，請務必遵循 `.persist-progress.md`：
- **工作結束時**：將 `.gemini/antigravity/brain/` 下的 `.md` 文件同步至 `.agent/brain/`。
- **提交時**：確保 `.agent/brain/` 同步更新並一併 commit。

---

## 其他規範摘要
- **時區**：DB 使用 `timestamptz`；後端 UTC + ISO-8601；前端依需求轉本地時區。
- **進度同步**：詳見 [.persist-progress.md](.persist-progress.md)。
- **業務情境**：詳見 [scenario/](scenario/) 標註之執行層級。

更完整的架構與開發守則見：
- [後端開發規範 (TDD & 測試策略)](backend/DEVELOPMENT_GUIDELINES.md)
- [核心資料庫規格書 (Schema V9)](doc/schema.md)

