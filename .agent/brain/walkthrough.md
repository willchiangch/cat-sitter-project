# WhiskerWatch 開發進度紀錄 (Walkthrough)

> 最後更新：2026-04-04（新增 Codebase 深度探索與前端重構計劃制定）

---

## 📌 階段三：Codebase 深度探索 + 前端重構計劃制定 (2026-04-04)

### 背景

前一階段完成 UAT 登入控制與人臉辨識後，進行了人工端對端 UI 巡檢。結果發現：前端實作僅為空殼，元件佈局、用詞與功能嚴重偏離 `doc/frontend-spec.md` 與 `doc/business-requirements.md`。因此啟動了完整的 codebase 深度探索。

### 工作內容

#### 1. Codebase 深度探索

逐一審查了以下核心檔案，確認具體偏差點：

| 審查檔案 | 發現問題 |
|---------|---------|
| `frontend/src/App.jsx` | `/explore` 路由完全不存在 → Client「保母」tab 是 live 404 |
| `frontend/src/pages/Auth/Profile.jsx` | `setIsLoading` 被呼叫但未宣告 → Sitter profile 掛載即 crash |
| `frontend/src/index.css` | `--color-outline` 未定義 → inactive nav icon 不可見 |
| `frontend/src/pages/Sitter/Dashboard.jsx` | 含工具 tab bar + StatCard 收益統計（不屬於此處） |
| `frontend/src/pages/Sitter/Orders.jsx` | 用 6 pill filter（規格要求 3-tab segmented control） |
| `frontend/src/pages/Sitter/Finance.jsx` | 缺少 PENDING / HISTORY 分頁結構 |
| `frontend/src/store/notificationStore.js` | 混合角色 mock data（無角色分流） |
| `frontend/src/components/shared/BottomNavBar.jsx` | Tab 標籤全面偏離規格（如「我是保母」應為「行程」） |

#### 2. Critical Bugs 確認

- **Bug 1**：Sitter Profile Runtime Crash — `Profile.jsx` 缺少 `useState(isLoading)` 宣告
- **Bug 2**：Client「保母」Tab Live 404 — 路由 `/explore` 不存在
- **Bug 3**：CSS Token 缺失 — `--color-outline` 未定義導致 inactive icon 不可見

#### 3. 前端重構計劃制定（Phase 1–7）

基於探索結果，制定了 7 Phase 的重構計劃：

| Phase | 標題 | 主要工作 |
|:-----:|------|---------|
| 1 | 基礎修復 | CSS token 補全、Tab 標籤統一為行程/訂單/收款/通知/我的 |
| 2 | 建立缺失頁面 | 新建 `Client/Sitters.jsx` + 路由，消滅 live 404 |
| 3 | Dashboard 重構 | 移除 Sitter Dashboard 錯置元件、清理 Client Dashboard 英文佔位符 |
| 4 | 訂單 Tab 對齊 | Sitter Orders 6 pill → 3-tab（評估中/進行中/歷史訂單） |
| 5 | Finance Tab 重構 | 加入待付款/收款紀錄 tabs，保留 payout modal |
| 6 | Notifications 角色分流 | 依 SITTER/CLIENT 角色過濾通知、badge count 分離 |
| 7 | Profile 補全 | 修復 crash、Sitter 加接單網址/門禁管理、Client 加寵物列表 |

計劃同步包含 E2E 測試更新策略：
- 更新 3 個現有 POM（DashboardPage / ProfilePage / sitter-business.spec）
- 新建 4 個 E2E spec（sitters / finance / notifications / client-profile）
- 新建 3 個 POM（ClientSittersPage / FinancePage / 相關 helpers）

#### 4. Brain 文件同步

| 文件 | 動作 |
|------|------|
| `implementation_plan.md` | 完整重寫 — 7 Phase + E2E 測試同步計劃 |
| `task.md` | 完整重寫 — 可執行 checkbox 任務清單 |
| `project_evaluation.md` | 更新至 V11 — 新增 Critical Bugs、偏差表格、健康度評分、風險矩陣 |
| `walkthrough.md` | 本次更新 |

### 產出物

- [implementation_plan.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/.agent/brain/implementation_plan.md) — 7 Phase 重構計劃（含改動清單、驗證方式、E2E 策略）
- [task.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/.agent/brain/task.md) — 可執行任務清單（Phase 1–7 各子任務）
- [project_evaluation.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/.agent/brain/project_evaluation.md) — V11 健康度報告（含評分、風險矩陣、優先級排序）

### 下一步

立即開始 Phase 1（Critical Bug Hotfix + 基礎修復），按 `task.md` 的 checkbox 順序逐一執行。

---

## 📌 階段二：UAT 登入控制與人臉辨識更新 (2026-03-30)

本任務根據核准的計劃順利完成。成功實作了 UAT 環境的存取限制、將身分驗證流程更新為 PWA 人臉自拍，並同步了所有相關規格。

### 變更項目

#### 1. Frontend：登入與註冊控管
- [Login.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Login.jsx)：引入 `VITE_ENABLE_PASSWORD_LOGIN` 開關。若設為 `false` (UAT 環境)，將隱藏 Email/密碼登入區塊，強制使用者使用 Google、Facebook 或 Apple 登入。
- [Register.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Register.jsx)：同樣受開關控制。若功能關閉，會自動重導向至登入頁面。
- [.env.example](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/.env.example)：新增變數範本，預設值為 `true`。

#### 2. Frontend：人臉辨識自拍 (PWA)
- [Profile.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Profile.jsx)：
  - 移除「身分證反面」，替換為「人臉辨識(自拍)」區塊。
  - 使用 `capture="user"` 屬性，在行動裝置上直接調用前置攝像頭。
  - 修復了 Lint 錯誤（移除未使用的 `t` 與 `isLoading`）。

#### 3. Backend & Database：資料結構更新
- **Profile 實體**：在 `Profile.java` 中將 `id_card_back_url` 替換為 `face_photo_url`。
- **DTO 更新**：同步更新 `SitterProfileResponse` 與 `UpdateSitterProfileRequest`。
- **Bug 修復**：修正了 `UpdateSitterProfileRequest` 遺漏身分證與人臉照片網址欄位的問題。
- **Migration**：建立 `V15__update_profile_identity_verification.sql`。

#### 4. OpenAPI 規格同步
- [openapi.yaml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi/openapi.yaml)：更新 API 手冊。
- [openapi.json](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi.json)：同步 JSON 規格書。

### 驗證結果

> [!TIP]
> 1. 設定 `VITE_ENABLE_PASSWORD_LOGIN=false` 後，登入頁面僅剩社群按鈕（已臨時加入開發測試快速登入按鈕供 UAT 使用）。
> 2. 後端已通過 `./mvnw compile` 驗證。
> 3. 前端已通過 `eslint` 檢查。
> 4. 解決了 CORS 阻擋問題，允許 `5174`/`5175` 等常見 Vite local port。

> [!WARNING]
> **人工端對端測試 (QA) 結果：發現重大架構落差**
> 在完成 UAT 驗證後進行了人工 UI 巡檢，發現目前前端實作僅為空殼，促成了階段三的 Codebase 深度探索。

---

## 📌 階段一：專案初始建置與核心架構 (≤ 2026-03-30)

| 完成項目 | 說明 |
|---------|------|
| Hybrid Testing 架構 | 成功分離 Playwright POM E2E 與 Vitest 元件測試 |
| PWA 全面升級 | iOS/Android 沉浸式安裝 + Workbox 快取策略 |
| 雙軌行事曆同步 | Google Calendar + iCal 整合 |
| 多檔案媒體持久化 | GCS 上傳 + URL 儲存 |
| VIP 熟客免問卷白名單 | Trust Circle 白名單機制 |
| 雙重角色帳號體系 | Sitter / Client 切換 + 獨立 profile |
| OpenAPI spec 自動生成 | `@hey-api/openapi-ts` 前端 SDK |
