# WhiskerWatch: 專案現況評估與健康度報告 (V11 / 2026-04-04)

本報告更新自 V10，新增了 **Codebase 深度探索**後的具體技術發現，以及前端重構計劃（Phase 1–7）的制定狀態。

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

---

## 🔴 已確認的 Critical Bugs（2026-04-04 探索後發現）

以下為 codebase 深度探索中確認的執行期 / 路由層級 bug，需在重構工作中優先處理：

### Bug 1：Sitter Profile Runtime Crash
- **檔案**：`frontend/src/pages/Auth/Profile.jsx`
- **問題**：`useEffect` 中呼叫 `setIsLoading(true/false)`，但 `isLoading` state 從未以 `useState` 宣告。
- **影響**：Sitter 角色點擊「我的」tab 後頁面立即拋出 `ReferenceError`，無法使用。
- **修復**：加入 `const [isLoading, setIsLoading] = useState(true)` 於其他 state 宣告旁。

### Bug 2：Client「保母」Tab Live 404
- **檔案**：`frontend/src/components/shared/BottomNavBar.jsx`（Client tab 3 路由 = `/explore`）
- **問題**：`/explore` 路由在 `frontend/src/App.jsx` 中完全不存在，任何 Client 點擊「保母」tab 均得到 404。
- **修復**：新建 `pages/Client/Sitters.jsx`，在 `App.jsx` 加入路由 `client/sitters`，更新 BottomNavBar 路由。

### Bug 3：Tailwind `--color-outline` Token 缺失
- **檔案**：`frontend/src/index.css`
- **問題**：`BottomNavBar.jsx` 對 inactive 圖示使用 `text-outline`，但 `--color-outline` 未定義於 `@theme` block，致使 class 無效、inactive icon 不可見。
- **修復**：在 `:root` 加入 `--outline: #8a8c8a;`，在 `@theme` 加入 `--color-outline: var(--outline);`。

---

## 🏗️ 亟需調整的工程架構 (Architectural Adjustments)

### 1. API 契約管理缺失
- **現狀**：前端的 `services/api.js` 是純手寫的。當後端 Spring Boot 更改 DTO 屬性名稱時，前端在執行時才會報錯。
- **調整建議**：讓 Spring Boot 啟動時自動產生 YAML，直接編譯出 Frontend 定義檔，達到前後端 API 型別 100% 同步。

### 2. 靜態資源的隱私與安全漏洞 (GCS Presigned URLs)
- **現狀**：保母上傳的「身分證正面」與「人臉自拍（face_photo_url）」採用公開讀取的 GCS URL 模式。
- **調整建議**：高度隱私資料必須採用 **Pre-Signed URL**，並限制 5–15 分鐘時效。

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
- **現況**：`pages/Sitter/Finance.jsx` 已有 payout 餘額顯示與 modal，但缺乏 PENDING / HISTORY 分頁結構（已列入重構 Phase 5）。

### 4. 前端 UI/UX 嚴重脫節（進行中：重構 Phase 1–7）
- **現況**：已完成 codebase 深度探索，確認具體偏差點（見下表），並制定了 7 Phase 重構計劃。
- **計劃狀態**：`implementation_plan.md` 已更新為含 E2E 同步的完整技術計劃；`task.md` 已重寫為可執行任務清單。

| 偏差項目 | 規格要求 | 現況 |
|---------|----------|------|
| Tab 1 標籤 | 行程 | 我是保母/我是家長 |
| Tab 2 標籤 | 訂單 | 預約訂單紀錄 |
| Tab 3 (Sitter) | 收款 | 財務收益對帳 |
| Tab 3 (Client) | 保母 → `/client/sitters` | 探索專業保母 → `/explore`（404） |
| Sitter Dashboard | 只顯示今日行程 | 含工具 tab bar + StatCard 收益統計 |
| Sitter Orders | 3-tab（評估中/進行中/歷史） | 6 pill filter |
| Finance | PENDING / HISTORY tabs | 單一視圖 |
| Notifications | 依角色過濾分組 | 混合角色共用 mock data |
| Sitter Profile | 含接單網址、門禁管理等 | 缺少多個 section；有 runtime crash |
| Client Profile | 含寵物列表 | 無寵物列表、無個人資料編輯 |

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

---

## 📊 整體健康度評分 (Overall Health Score)

| 維度 | 分數 (0–10) | 說明 |
|------|:-----------:|------|
| **後端穩定度** | 8 | Spring Boot + JPA 實體化完整、DTO 體系清晰、OpenAPI spec 已同步。Flyway migration 一路到 V15 無衝突。 |
| **前端完成度** | 3 | 框架到位（React 19 / TW4 / Zustand），但 UI 組件嚴重偏離規格。3 個 Critical Bugs 待修。Phase 1–7 重構尚未啟動。 |
| **測試覆蓋率** | 6 | Vitest 元件測試 + Playwright POM E2E 架構穩健，但覆蓋範圍僅限 onboarding / sitter-business / booking-lifecycle。前端重構後需同步擴充至少 4 個新 spec。 |
| **DevOps / CI** | 5 | GitHub Actions 存在但未掛載完整前端測試 gate。PWA / Workbox 已配置但產品部署尚未完全自動化。 |
| **安全性** | 4 | OAuth2 社群登入已整合，JWT 機制完整，但 GCS 公開 URL 洩漏隱私資料風險高。尚無 CSP / rate limiting / Pre-Signed URL 策略。 |
| **UX / 設計一致性** | 3 | Design Token 系統存在（CSS 變數 + `@theme`），但多處元件繞過 token 使用硬編碼色值、英文佔位符。Tab 標籤全面偏離規格。 |
| **文件完整度** | 7 | business-requirements.md / frontend-spec.md / OpenAPI spec 保持同步。brain/ 目錄持續更新。缺少 CONTRIBUTING.md 與 ARCHITECTURE.md。 |

> **綜合健康度：5.1 / 10**  
> 後端成熟但前端嚴重拖後腿。執行 Phase 1–7 重構後預估可拉升至 7.5+。

---

## ⚠️ 風險矩陣 (Risk Matrix)

| 風險項目 | 嚴重度 | 發生機率 | 緩解策略 |
|---------|:------:|:-------:|---------|
| Sitter Profile crash 導致使用者無法存取個人頁 | 🔴 Critical | 100%（已重現） | Phase 7A — 加入缺失的 `useState` 宣告 |
| Client「保母」tab 404 打擊信任感 | 🔴 Critical | 100%（已重現） | Phase 2 — 新建頁面 + 路由 |
| GCS 公開 URL 洩漏身分證 / 自拍照 | 🔴 Critical | 高 | 導入 Pre-Signed URL（短期）+ CDN 簽名（長期） |
| Tab 標籤偏離規格造成 UAT 負面回饋 | 🟡 High | 高 | Phase 1 — 術語統一 |
| E2E 測試未涵蓋新頁面，重構後回歸風險 | 🟡 High | 中 | 每個 Phase 同步新增 / 更新 E2E spec |
| CI pipeline 缺少前端測試 gate | 🟡 Medium | 中 | 更新 GitHub Actions workflow |
| 無即時通知（WebSocket / SSE） | 🟡 Medium | — | V2 排程，目前以 polling 暫代 |

---

## 🎯 建議優先級排序

```
最佳執行順序（依風險與依賴關係）：

1. ★ Critical Bug Hotfix  — Profile crash (7A) + CSS token (1A)
   ↳ 解鎖所有後續頁面工作
   ↳ 預估工時：30 分鐘

2. ★ Phase 1 完整        — Tab 標籤統一 + BottomNavBar 路由修正
   ↳ 解鎖 Phase 2 路由、Phase 6 badge
   ↳ 預估工時：1 小時

3. ★ Phase 2             — Client/Sitters.jsx 新建 + App.jsx 路由
   ↳ 消滅 live 404
   ↳ 預估工時：1.5 小時

4.   Phase 3             — Dashboard 清理（移除錯置元件）
   ↳ 預估工時：1 小時

5.   Phase 4             — Sitter Orders 3-tab 對齊
   ↳ 預估工時：1 小時

6.   Phase 5             — Finance tab 重構
   ↳ 預估工時：1 小時

7.   Phase 6             — Notifications 角色分流
   ↳ 預估工時：1.5 小時

8.   Phase 7B/7C         — Profile 功能補全
   ↳ 預估工時：2 小時

9.   E2E 全跑 + 修正    — 最終回歸驗證
   ↳ 預估工時：1 小時
```

> **總預估工時：~10.5 小時**（不含安全性改善與 CI 更新）

---

## 📌 下一步行動方針

1. **立即執行**：Critical Bug Hotfix（7A + 1A），讓 Sitter Profile 和 nav icon 恢復正常。
2. **本週目標**：完成 Phase 1–4，涵蓋所有路由修正、Tab 標籤統一、Dashboard 清理、Orders 對齊。
3. **本週延伸**：Phase 5–7 按序執行，同步撰寫 E2E 測試。
4. **下週規劃**：
   - 安全性改善：GCS Pre-Signed URL 導入
   - CI 強化：GitHub Actions 加入 Vitest + Playwright gate
   - 即時通知 PoC：SSE 或 WebSocket 選型與原型
5. **持續維護**：每完成一個 Phase，更新 `task.md` 的 checkbox 和 `walkthrough.md` 的變更記錄。
