# WhiskerWatch: 專案現況評估與健康度報告 (V19 / 2026-04-06)

本報告更新自 V16，反映 **保母公開頁 + GCS Proxy 架構 + UI 多項修復 + DB migration 修復（Schema V18/V19）** 後的最新狀態。

---

## 🟢 系統亮點與已防禦範圍 (Current Strengths)

### 1. 穩定且極速的開發體驗
- **Hybrid Testing 防護網**：成功分離了高成本的跨角色 E2E (Playwright) 與低成本的元件邏輯測試 (Vitest)，CI/CD 執行時間大幅縮短，Flaky Tests 降至最低。
- **PWA 桌面級體驗**：iOS/Android 已全部支援沉浸式安裝，並整合了 Workbox 網路降級快取策略 (Network-First & Cache-First)。
- **E2E 測試現況**：現有 3 個 Playwright spec（onboarding、sitter-business、booking-lifecycle）搭配 5 個 POM 檔，採用 X-Smoke-Auth header 注入 JWT，測試結構穩健。

### 2. 核心業務邏輯的成熟度
- 雙軌行事曆同步、多檔案媒體持久化、VIP 熟客免問卷白名單、雙重角色帳號體系等都已經具備後端實體化 (Realization) 支援。
- OpenAPI spec 已同步，前端 SDK 由 `@hey-api/openapi-ts` 自動生成。
- 設計 Token 系統完整（CSS 變數 + Tailwind 4 `@theme` block），`.mode-sitter` / `.mode-client` 主色分離清晰。

### 3. 前端與後端對齊完成（Phase 1–10）
所有 10 個 Phase 均已完成：
- 3 個 Critical Bugs 已修復（CSS token / 路由 404 / Profile crash / navigate 未實例化）
- 5 個主要頁面架構已對齊規格（Dashboard / Orders / Finance / Notifications / Profile）
- 設計系統統一（tab 標籤、空白狀態、返回箭頭移除、BottomNavBar 白字金暈）
- E2E-1 至 E2E-8 全部通過（8 個 spec/POM 新建或更新）；**16/16 tests pass**
- Profile 頁面 12 項重構完成（Phase 8）
- 後端 API 完整同步：黑名單 CRUD、訂閱管理 CRUD、服務方案日期欄位、白名單搜尋/新增（Phase 9）
- DB Schema V16：`plan_code`、`effective_date`、`sitter_client_blacklists`
- 新增後端 Smoke Test：`SubscriptionSmokeTest`、`BlacklistSmokeTest`
- 新增前端 E2E：`sitter/subscription.spec.js`、`sitter/client-gate.spec.js`
- 保母公開頁 `/s/:slug`（Phase 10）
- GCS Proxy 架構：bucket 私有，圖片由後端 proxy 存取（Phase 10）
- DB migration 修復：V14/V16/V18/V19（Phase 10）
- 全域 CSS error token 補齊，修復多處灰色 UI（Phase 10）

---

## 🟡 待完成項目

### 測試驗證
- `./mvnw test -Dtest=com.catsitter.api.smoke.*` — 確認新 smoke test 通過
- `npm run test:e2e` — E2E 含新 spec（Phase 10 尚未新增測試）
- `npm run api:sync` — 重新生成 TypeScript SDK（slug / professionalLabels 等新欄位型別同步）

### 手動 UAT 驗證
- 手動切換 Sitter/Client 角色，確認 5 tab 標籤正確顯示
- 逐 tab 確認功能符合 `doc/frontend-spec.md` 規格
- 驗證訂閱方案切換、黑名單管理、身分照片上傳縮圖、保母公開頁

### Phase 10 尚未新增的測試
- `SitterPublicPage` 的 E2E spec（`/s/:slug` 公開路由）
- `StorageController` 新 upload endpoint 的 smoke test
- `BookingPreviewResponse` 加 `professionalLabels` 的 smoke test

---

## ✅ Critical Bugs — 全部已修復

### Bug 1：Sitter Profile Runtime Crash ✅ 已修復
- **檔案**：`frontend/src/pages/Auth/Profile.jsx`
- **問題**：`useEffect` 中呼叫 `setIsLoading(true/false)`，但 `isLoading` state 從未以 `useState` 宣告。
- **修復（Phase 7A）**：加入 `const [isLoading, setIsLoading] = useState(true)`。

### Bug 2：Client「保母」Tab Live 404 ✅ 已修復
- **檔案**：`frontend/src/components/shared/BottomNavBar.jsx`（Client tab 3 路由 = `/explore`）
- **問題**：`/explore` 路由在 `App.jsx` 中完全不存在。
- **修復（Phase 1B + 2A + 2B）**：新建 `pages/Client/Sitters.jsx`，加入路由 `client/sitters`，更新 BottomNavBar 路由。

### Bug 3：Tailwind `--color-outline` Token 缺失 ✅ 已修復
- **檔案**：`frontend/src/index.css`
- **問題**：`BottomNavBar.jsx` 對 inactive 圖示使用 `text-outline`，但 `--color-outline` 未定義。
- **修復（Phase 1A）**：在 `:root` 加入 `--outline: #8a8c8a;`，在 `@theme` 加入 `--color-outline: var(--outline);`。

---

## 🏗️ 亟需調整的工程架構 (Architectural Adjustments)

### 1. API 契約管理缺失
- **現狀**：前端的 `services/api.js` 是純手寫的。當後端 Spring Boot 更改 DTO 屬性名稱時，前端在執行時才會報錯。
- **調整建議**：讓 Spring Boot 啟動時自動產生 YAML，直接編譯出 Frontend 定義檔，達到前後端 API 型別 100% 同步。

### 2. 靜態資源的隱私與安全 (GCS Proxy) ✅ 已改善
- **原狀**：GCS bucket 公開 → 任何人可直接存取圖片 URL（403 因未設定）。
- **現狀（Phase 10）**：後端 proxy `/api/v1/media/**`，bucket 保持私有；`/api/v1/media/identity/**` 需登入。
- **剩餘工作**：目前所有非 identity 圖片（頭像、寵物照）是 permitAll，屬於合理設計（公開資訊）。未來若有更細粒度控管需求（例如只有訂單相關方可看寵物照），需加 ownership check。

### 3. CI/CD 管線未掛載最新防線 (Pipeline Gaps)
- **現狀**：Vitest 與 Playwright POM 已建置，但 `.github/workflows` 若沒有更新，PR 就不會被攔截。
- **調整建議**：更新 GitHub Actions，強制要求合併 `main` 前必須通過 `npm run test` 與 Playwright 檢查。

---

## 🚀 亟需補齊的核心業務功能 (Feature Adjustments)

### 1. 實時通知系統 (Real-time Notifications / WebSockets)
- **痛點**：保母點選報價後，飼主目前必須手動重新整理頁面才能看到訂單變化。
- **解法**：導入 SSE (Server-Sent Events) 或 Spring WebSockets + 前端 Zustand Listener。

### 2. 動態問卷編輯器 (Sitter Questionnaire Editor)
- **現況**：後端 Schema 已備妥，`pages/Sitter/QuestionnaireEditor.jsx` 頁面檔案存在，但 UI 完整度需確認。

### 3. 提現與金流閉環 (Financial Payouts)
- **現況**：`pages/Sitter/Finance.jsx` 已完成 PENDING/HISTORY 分頁結構（Phase 5），payout modal 正常運作。管理訂閱按鈕已綁定，但實際 Stripe billing portal 整合尚待。

### 4. 前端 UI/UX 對齊（Phase 1–7 已完成）
- **現況**：所有規格偏差已修復，詳見下表。

| 偏差項目 | 規格要求 | 修復狀態 |
|---------|----------|:-------:|
| Tab 1 標籤 | 行程 | ✅ Phase 1 |
| Tab 2 標籤 | 訂單 | ✅ Phase 1 |
| Tab 3 (Sitter) | 收款 | ✅ Phase 1 |
| Tab 3 (Client) | 保母 → `/client/sitters` | ✅ Phase 1+2 |
| Sitter Dashboard | 只顯示今日行程 | ✅ Phase 3 |
| Sitter Orders | 3-tab（評估中/進行中/歷史） | ✅ Phase 4 |
| Finance | PENDING / HISTORY tabs | ✅ Phase 5 |
| Notifications | 依角色過濾分組 | ✅ Phase 6 |
| Sitter Profile | 接單網址、門禁管理、無 crash | ✅ Phase 7 |
| Client Profile | 寵物列表 | ✅ Phase 7 |
| 保母公開頁 | `/s/:slug` 一頁式不需登入 | ✅ Phase 10 |
| IME 輸入法 | 標籤輸入法支援 + 確認儲存 | ✅ Phase 10 |
| GCS 圖片存取 | Proxy 架構，bucket 私有 | ✅ Phase 10 |
| CSS error token | 黑名單/錯誤紅色正常顯示 | ✅ Phase 10 |
| scroll to top | 改用 main container（正確 scroll target）| ✅ Phase 10 |
| 自我介紹欄位 | onBlur 自動儲存 textarea | ✅ Phase 10 |

---

## 📋 已完成項目記錄

| 項目 | 完成日期 |
|------|---------|
| Hybrid Testing 架構（Vitest + Playwright POM） | ≤ 2026-03-30 |
| PWA 全面升級（Workbox / iOS 安裝支援） | ≤ 2026-03-30 |
| UAT 登入控制（VITE_ENABLE_PASSWORD_LOGIN） | 2026-03-30 |
| 人臉辨識自拍（face_photo_url）取代身分證背面 | 2026-03-30 |
| V15 migration + DTO + OpenAPI spec 同步 | 2026-03-30 |
| Codebase 深度探索 + 具體 Bug 清查 | 2026-04-04 |
| 前端重構計劃（Phase 1–7）制定與 brain 同步 | 2026-04-04 |
| **Phase 1–7 Source Code 全部完成** | **2026-04-04** |
| **E2E 批次執行完成（14/16 pass，E2E-1 至 E2E-8 全通過）** | **2026-04-04** |
| **後端 Smoke 修復 + POM 全面更新（16/16 pass）** | **2026-04-04** |

---

## 📊 整體健康度評分 (Overall Health Score)

| 維度 | V11 分數 | V12 分數 | V13 分數 | V14 分數 | 說明 |
|------|:-------:|:-------:|:-------:|:-------:|------|
| **後端穩定度** | 8 | 8 | 8 | 8 | 未變動，Spring Boot + JPA 實體化完整。 |
| **前端完成度** | 3 | 7 | 8 | 8 | 全部 16/16 E2E 通過，無殘餘前端 bug。 |
| **測試覆蓋率** | 6 | 6 | 7 | 9 | 16/16 E2E pass；後端 Smoke data + POM 全面校準。 |
| **DevOps / CI** | 5 | 5 | 5 | 5 | GitHub Actions 未更新，PWA 部署尚未全自動化。 |
| **安全性** | 4 | 4 | 4 | 4 | GCS 公開 URL 洩漏風險仍存在。 |
| **UX / 設計一致性** | 3 | 8 | 8 | 8 | Tab 標籤統一、空白狀態補齊、英文佔位符清除、design token 修復。 |
| **文件完整度** | 7 | 8 | 9 | 9 | brain/ 文件同步至 V14，含 16/16 結果與技術發現。 |

> **V11 綜合健康度：5.1 / 10**
> **V12 綜合健康度：6.6 / 10**
> **V13 綜合健康度：7.0 / 10**
> **V14 綜合健康度：7.3 / 10**
>
> 16/16 E2E 全通過後，測試覆蓋率維度顯著提升。剩餘差距主要為 CI 管線未更新與 GCS 安全性。

---

## ⚠️ 風險矩陣 (Risk Matrix)

| 風險項目 | 嚴重度 | 發生機率 | 緩解策略 |
|---------|:------:|:-------:|---------|
| GCS 公開 URL 洩漏身分證 / 自拍照 | 🔴 Critical | 高 | 導入 Pre-Signed URL（短期）+ CDN 簽名（長期） |
| CI pipeline 缺少前端測試 gate | 🟡 Medium | 中 | 更新 GitHub Actions workflow |
| 無即時通知（WebSocket / SSE） | 🟡 Medium | — | V2 排程，目前以 polling 暫代 |
| Client/Sitters 搜尋 API 未實作 | 🟡 Low | — | 目前 mock，等後端 clientSitterService 備妥後接入 |

---

## 🎯 建議優先級排序（V12 更新後）

```
最佳執行順序：

1. ★ 手動 UAT 驗證        — 依 doc/frontend-spec.md 逐 tab 確認
   ↳ 預估工時：1 小時

2.   後端 smoke 帳號修復   — SmokeDataSeeder + SmokeMockAuthFilter（NEWBIE UUID）
   ↳ 解決 auth/onboarding.spec.js 失敗
   ↳ 預估工時：1 小時

3.   公開保母 profile 路由  — App.jsx 加入 /:sitterSlug 或更新 BookingPage POM
   ↳ 解決 sitter/booking-lifecycle.spec.js 失敗
   ↳ 預估工時：2 小時

4.   安全性改善            — GCS Pre-Signed URL 導入
   ↳ 預估工時：3 小時

5.   CI 強化              — GitHub Actions 加入 Vitest + Playwright gate
   ↳ 預估工時：1 小時

6.   即時通知 PoC          — SSE 或 WebSocket 選型與原型
   ↳ 預估工時：4 小時（V2 規劃）
```

> **下一步行動：手動 UAT 驗證（`task.md` 最終驗證區塊）**
