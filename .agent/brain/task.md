# WhiskerWatch 任務清單

> 最後更新：2026-04-06（保母公開頁 + GCS proxy + UI 修復 + DB migration 修復；Schema V18/V19）
> 規格依據：`doc/frontend-spec.md`、`doc/business-requirements.md`、`doc/sass-level-program.md`
> 測試架構：Playwright E2E + POM（`frontend/tests/`）+ JUnit Smoke（`backend/src/test/`）

---

## ✅ 已完成

- [x] 審查商業需求與 SASS 程式文件，比對現有 UI 實作落差
- [x] 產出前端 UI/UX 重構草稿計劃（implementation_plan.md）
- [x] 確認獨立「客群名單門禁管理」與修正 Top/Bottom Bar 對比問題方向
- [x] 實作 UAT 登入控制（VITE_ENABLE_PASSWORD_LOGIN toggle）
- [x] 實作人臉辨識自拍（face_photo_url）取代身分證背面上傳
- [x] 更新 Profile.java、DTO、V15 migration 與 OpenAPI spec 同步

### Phase 1 — 基礎修復 ✅

- [x] **[1A]** `frontend/src/index.css`：`:root` 加入 `--outline: #8a8c8a;`，`@theme` 加入 `--color-outline: var(--outline);`
- [x] **[1B]** `frontend/src/components/shared/BottomNavBar.jsx`：統一 5 tab 標籤（行程/訂單/收款·保母/通知/我的）；Client tab 3 路由從 `/explore` 改為 `/client/sitters`；badge 支援 Sitter/Client 分離計數
- [x] **[1C]** `frontend/src/locales/zh-TW.json`：新增 `common.tab_itinerary/tab_orders/tab_finance/tab_sitters/tab_notifications/tab_profile` 6 個短標籤鍵值
- [x] **[1D]** `frontend/tests/pages/DashboardPage.js`：確認並修正 `navigateToInboxOrOrders()` 路由為 `/sitter/orders`

### Phase 2 — 建立缺失頁面 ✅

- [x] **[2A]** 新建 `frontend/src/pages/Client/Sitters.jsx`（header + 搜尋列 + 保母卡片列表 + 空白狀態；backend API 未備妥，以 mock 先行）
- [x] **[2B]** `frontend/src/App.jsx`：新增 `import ClientSitters` + 路由 `<Route path="client/sitters" />`

### Phase 3 — Dashboard 重構 ✅

- [x] **[3A]** `frontend/src/pages/Sitter/Dashboard.jsx`：移除工具 tab bar + StatCard；mock data `time` 改為 `date`；加入 `onPanelClick` navigate
- [x] **[3B]** `frontend/src/components/sitter/UpcomingVisitCard.jsx`：加入 `onSopClick`、`onPanelClick` props；header 顯示 `date`
- [x] **[3C]** `frontend/src/pages/Client/Dashboard.jsx`：移除英文 "Recent Activity Teaser"；移除硬編碼 FAB；加入今日無預約中文空白狀態

### Phase 4 — 訂單 Tab 對齊 ✅

- [x] **[4A]** `frontend/src/pages/Sitter/Orders.jsx`：6 pill filter → 3-tab segmented control（評估中/進行中/歷史訂單）；移除返回箭頭 header；各 tab 中文空白狀態
- [x] **[4B]** `frontend/tests/pages/DashboardPage.js`：`openFirstPendingOrder()` 先點「評估中」tab 再點訂單

### Phase 5 — Finance Tab 重構 ✅

- [x] **[5A]** `frontend/src/pages/Sitter/Finance.jsx`：修復 `AnimatePresence` 未 import；加入 `activeTab` state；2-tab segmented control（待付款/收款紀錄）；移除返回箭頭 header；payout modal 保持頁面層級；英文文字全面中文化

### Phase 6 — Notifications 角色分流 ✅

- [x] **[6A]** `frontend/src/store/notificationStore.js`：mock data 加入 `role` 欄位（SITTER/CLIENT/ALL）；新增 `getNotificationsForRole(role)` 和 `getUnreadCountForRole(role)` selectors
- [x] **[6B]** `frontend/src/pages/Shared/Notifications.jsx`：import `useThemeStore`；依角色過濾通知；移除返回箭頭；英文 "Recent Updates" → 「最新通知」
- [x] **[6C]** `frontend/src/components/shared/BottomNavBar.jsx`：badge count 改用 `getUnreadCountForRole(mode)`

### Phase 7 — Profile 補全 ✅

- [x] **[7A] Critical Bug** `frontend/src/pages/Auth/Profile.jsx`：加入 `const [isLoading, setIsLoading] = useState(true)`
- [x] **[7B]** Sitter Profile：新增「接單專屬網址」section（複製 + 預覽按鈕）；新增「客群門禁管理」SettingsItem；綁定「管理訂閱」button
- [x] **[7C]** Client Profile：新增 `pets`/`showPetModal` state；pets useEffect；「我的毛孩」section（卡片 + 新增 + 管理全部）；PetFormModal 掛載

---

## ✅ 已完成 — E2E 批次（2026-04-04）

### 現有測試更新

- [x] **[E2E-1]** `frontend/tests/e2e/sitter-business.spec.js`
  - 新增 Scenario：Sitter Profile 成功渲染，可見「接單專屬網址」section（含複製/預覽按鈕）
  - 新增 Scenario：Sitter Dashboard 只顯示今日行程，不含工具 tabs

- [x] **[E2E-2]** `frontend/tests/pages/ProfilePage.js`
  - 新增 `getBookingUrlSection()` — locator 找到「接單專屬網址」section
  - 新增 `copyBookingUrl()` — 點擊複製按鈕

- [x] `frontend/tests/pages/AuthPage.js`
  - 新增 `injectClientSmokeAuth(targetUrl)` — 注入 CLIENT 角色 auth + theme（mode: CLIENT）

### 新建 POM

- [x] **[E2E-3]** `frontend/tests/pages/ClientSittersPage.js`
  - `navigate()`、`searchBySitterCode(code)`、`getSitterCards()`

- [x] **[E2E-4]** `frontend/tests/pages/FinancePage.js`
  - `navigate()`、`switchToTab(tabName)`、`getWithdrawableBalance()`
  - Finance API mock: `**/payments/payuni/sitter-summary`

### 新建 E2E Spec

- [x] **[E2E-5]** `frontend/tests/e2e/client/sitters.spec.js`
  - Scenario：Client 點擊「保母」tab → `/client/sitters` 正常渲染（不 404）
  - Scenario：搜尋保母代碼 → UI 響應（spinner 或 empty state）

- [x] **[E2E-6]** `frontend/tests/e2e/sitter/finance.spec.js`
  - Scenario：Finance 頁顯示「待付款」「收款紀錄」兩個 tabs
  - Scenario：「待付款」tab 顯示可提領餘額（$1,500）與申請提款按鈕
  - Scenario：切換到「收款紀錄」tab → 不顯示 hero balance card

- [x] **[E2E-7]** `frontend/tests/e2e/shared/notifications.spec.js`
  - Scenario：Sitter 模式 → 顯示 n2(SITTER)+n3(ALL)，不顯示 n1(CLIENT)
  - Scenario：Client 模式 → 顯示 n1(CLIENT)+n3(ALL)，不顯示 n2(SITTER)

- [x] **[E2E-8]** `frontend/tests/e2e/client/client-profile.spec.js`
  - Scenario：Client Profile 顯示「我的毛孩」section + 新增寵物/管理全部按鈕
  - Scenario：點擊「新增寵物」→ PetFormModal 開啟（顯示「新增貓咪至保險箱」標題）
  - Pets API mock: `**/clients/me/pets` → []

---

## ✅ 已完成 — Phase 8：Profile 頁面 12 項重構（2026-04-05）

> 前端深度修正，對齊業務規格與設計書

- [x] **[8-1]** GCS 身分驗證照片上傳後縮圖即時顯示（`uploadingField`/`uploadError` per card 狀態）
- [x] **[8-2]** 專業形象標籤（professionalLabels）可新增/刪除；財務結算資訊（bank info）可開啟 modal 編輯
- [x] **[8-3]** 「管理服務方案」按鈕點擊後頁面捲至頂部（`window.scrollTo({ top: 0 })`）
- [x] **[8-4]** ServicePackages 表單補齊：物種多選（貓/狗/鳥/鼠/兔/爬蟲/其他）、啟用切換 + 生效日期、可預約日期區間、服務時長
- [x] **[8-5]** TrustCircle 標題改為「關於信任圈」；說明文字更新；加入移除夥伴按鈕（`person_remove` icon）
- [x] **[8-6]** 「客群門禁管理」新建獨立頁面 `ClientGate.jsx`（含白名單 + 黑名單雙 tab，各有搜尋/新增/移除）
- [x] **[8-7]** Calendar sync section 排版修復（使用 `min-w-0` + `break-all` 避免元素重疊）
- [x] **[8-8]** 「管理訂閱」改為 `navigate('/sitter/subscription')` 進入站內頁面；新建 `SubscriptionManagement.jsx`
- [x] **[8-9]** Avatar 上傳雙角色支援（Sitter 呼叫 `updateSitterMe`、Client 呼叫 `updateClientMe`）；`localAvatarUrl` 即時更新
- [x] **[8-10]** 「保母預覽網頁」改為 `navigate('/booking/sitter/${slug}')` 站內路由；複製 URL 改為 `window.location.origin` 前綴
- [x] **[8-11]** Client Profile 新增「我的基本資料」section（姓名/電話可透過 modal 編輯，呼叫 `updateClientMe`）
- [x] **[8-12]** BottomNavBar：背景維持 glass-effect (navy blue)；圖示與文字改為白色；選中時加上暗金色光暈（`drop-shadow-[0_0_8px_rgba(118,86,0,0.7)]`）
- [x] 新建 `App.jsx` 路由：`/sitter/client-gate`、`/sitter/subscription`

---

## ✅ 已完成 — Phase 9：後端 API 完整同步（2026-04-05）

> 前端已先行，本階段補齊後端 API、DB Schema、E2E 測試

### DB Migration

- [x] **V16** (`V16__blacklist_subscription_dates.sql`)：
  - `subscription_plans.plan_code` VARCHAR(20) UNIQUE（FREE/STANDARD/PRO/PREMIUM）
  - 更新訂閱方案價格（0/499/899/1299）
  - `services.effective_date` DATE
  - 新建 `sitter_client_blacklists` 資料表
  - Smoke 保母 PRO 訂閱資料種子

### 新建後端檔案

- [x] `entity/SitterClientBlacklist.java`
- [x] `repository/SitterClientBlacklistRepository.java`
- [x] `dto/SitterClientBlacklistDTO.java`
- [x] `dto/sitter/SitterSubscriptionDTO.java`（record: planId, status, renewsAt）
- [x] `service/BlacklistService.java`（getBlacklistedClients / addToBlacklist / removeFromBlacklist / searchClients）
- [x] `controller/v1/BlacklistController.java`（GET / POST /clients / DELETE /clients/{id} / GET /search）
- [x] `controller/v1/SitterSubscriptionController.java`（GET / PUT / DELETE）

### 更新現有後端檔案

- [x] `entity/SubscriptionPlan.java`：加入 `planCode` 欄位
- [x] `entity/Service.java`：加入 `effectiveDate` 欄位
- [x] `repository/SubscriptionPlanRepository.java`：加入 `findByPlanCode(String)`
- [x] `repository/SitterSubscriptionRepository.java`：加入 `findBySitterProfileIdAndStatus(UUID, String)` / `findTopBySitterProfileIdOrderByCreatedAtDesc(UUID)`
- [x] `repository/ProfileRepository.java`：加入 `findByRoleTypeAndNameContainingIgnoreCase(RoleType, String)`
- [x] `service/SubscriptionService.java`：加入 `getCurrentSubscription` / `changePlan` / `cancelSubscription`
- [x] `service/WhitelistService.java`：加入 `addToWhitelist` / `searchClients`
- [x] `controller/v1/WhitelistController.java`：加入 `POST /clients` / `GET /search`
- [x] `dto/sitter/ServicePlanResponse.java`：加入 `bookableStartDate` / `bookableEndDate` / `effectiveDate`
- [x] `dto/sitter/CreateServiceRequest.java`：加入同三個日期欄位（nullable）
- [x] `dto/sitter/UpdateServiceRequest.java`：加入同三個日期欄位（nullable）
- [x] `service/SitterServiceService.java`：`mapToResponse` / `createService` / `updateService` 同步日期欄位
- [x] `dto/sitter/AddTrustCircleRequest.java`：`trustedSitterId` 改名為 `sitterProfileId`（對齊前端 API 呼叫）
- [x] `controller/v1/SitterTrustCircleController.java`：使用 `getSitterProfileId()`
- [x] `controller/v1/ApiV1StubController.java`：移除已實作的 `GET /sitters/me/subscription` 與 `POST /sitters/me/subscription/checkout` stub

### 新建後端 Smoke 測試

- [x] `smoke/SubscriptionSmokeTest.java`：GET→PUT(PRO)→DELETE→GET(CANCELLED) 完整流程
- [x] `smoke/BlacklistSmokeTest.java`：empty→add→list(1)→remove→empty 完整流程

### 新建前端 E2E 測試

- [x] `e2e/sitter/subscription.spec.js`（4 tests：導航 / 方案卡片 / 目前方案 / 月年切換）
- [x] `e2e/sitter/client-gate.spec.js`（4 tests：導航 / 白名單 tab / 黑名單切換 / 搜尋欄位）
- [x] `e2e/sitter-business.spec.js`：追加 2 個 test（subscription page / client gate tabs）
- [x] `pages/DashboardPage.js`：`sendToWhitelist` 更新為導向 `/sitter/client-gate`

---

## 最終驗證

- [x] `npm run test:e2e` — **16/16 pass**（全部通過，含後端 + POM 修復）
- [x] 手動切換 Sitter/Client 角色，確認 5 tab 標籤：行程 / 訂單 / 收款(保母) / 通知 / 我的
- [x] 手動逐 tab 確認功能符合 `doc/frontend-spec.md` 所列規格
- [ ] **待執行**：`npm run test:e2e` 重跑含新 subscription / client-gate spec
- [ ] **待執行**：`./mvnw test -Dtest=com.catsitter.api.smoke.*` 確認 SubscriptionSmokeTest + BlacklistSmokeTest 通過
- [ ] **待執行**：`npm run api:sync` 重新生成 OpenAPI SDK

---

## ✅ 已完成 — Phase 10：保母公開頁 + GCS Proxy + UI 修復（2026-04-06）

### 新建檔案

- [x] `frontend/src/pages/Public/SitterPublicPage.jsx` — 一頁式保母公開頁（`/s/:slug`，不需登入）
- [x] `backend/src/main/resources/db/migration/V18__add_is_email_verified_to_accounts.sql`
- [x] `backend/src/main/resources/db/migration/V19__add_verification_codes.sql`

### 修改檔案

- [x] `frontend/src/App.jsx` — 新增 `/s/:slug` 公開路由 + `SitterPublicPage` import
- [x] `frontend/src/pages/Auth/Profile.jsx` — 接單網址改 `/s/{slug}`；加自我介紹 textarea；scroll 改用 main container；Sitter name edit；Client profile email + save fix
- [x] `frontend/src/pages/Sitter/QuestionnaireEditor.jsx` — scroll to top 改 main；移除 🐱 貓咪 badge
- [x] `frontend/src/pages/Sitter/TrustCircle.jsx` — scroll to top 改 main
- [x] `frontend/src/pages/Sitter/ClientGate.jsx` — scroll to top 改 main
- [x] `frontend/src/pages/Sitter/ServicePackages.jsx` — scroll to top 改 main
- [x] `frontend/src/pages/Sitter/SubscriptionManagement.jsx` — hero card text 顏色修正（highlight flag）
- [x] `frontend/src/pages/Client/Pets.jsx` — scroll to top 改 main；加返回按鈕；標題改「我的毛孩」
- [x] `frontend/src/services/api.ts` — storageService 改 multipart POST
- [x] `frontend/src/index.css` — 補齊 error / surface-container / on-surface-variant / secondary token
- [x] `backend/.../dto/sitter/BookingPreviewResponse.java` — `SitterPublicProfile` 加 `professionalLabels`
- [x] `backend/.../dto/sitter/SitterProfileResponse.java` — 加 `slug` 欄位
- [x] `backend/.../service/BookingPreviewService.java` — 傳入 `profile.getProfessionalLabels()`
- [x] `backend/.../service/SitterProfileService.java` — 傳入 `profile.getSlug()`；身分證/人臉照改 `getUrl()`
- [x] `backend/.../service/storage/GcsStorageService.java` — `getUrl()` 改回傳 proxy 路徑；實作 `load()` 從 GCS 讀 bytes
- [x] `backend/.../controller/v1/StorageController.java` — upload 回傳相對路徑
- [x] `backend/.../config/SecurityConfig.java` — `/api/v1/media/identity/**` authenticated；`/api/v1/media/**` permitAll
- [x] `backend/src/main/resources/application.yml` — 加 `spring.security.oauth2.client` registration
- [x] `backend/src/main/resources/db/migration/V14__add_smoke_test_newbie.sql` — 移除 `is_email_verified` 欄位
- [x] `backend/src/main/resources/db/migration/V16__blacklist_subscription_dates.sql` — INSERT 補 `id/created_at/updated_at`

---

## 新增/修改檔案速查表

### Phase 8–9 新增/修改

| 操作 | 檔案 | 階段 |
|------|------|:----:|
| 新建 | `frontend/src/pages/Sitter/ClientGate.jsx` | 8-6 |
| 新建 | `frontend/src/pages/Sitter/SubscriptionManagement.jsx` | 8-8 |
| 新建 | `frontend/src/pages/Sitter/ServicePackages.jsx`（完整重寫） | 8-4 |
| 修改 | `frontend/src/pages/Auth/Profile.jsx` | 8-1~12 |
| 修改 | `frontend/src/pages/Sitter/TrustCircle.jsx` | 8-5 |
| 修改 | `frontend/src/components/shared/BottomNavBar.jsx` | 8-12 |
| 修改 | `frontend/src/App.jsx` | 8-6,8-8 |
| 修改 | `frontend/src/services/api.ts` | 9 |
| 新建 | `backend/.../entity/SitterClientBlacklist.java` | 9 |
| 新建 | `backend/.../repository/SitterClientBlacklistRepository.java` | 9 |
| 新建 | `backend/.../dto/SitterClientBlacklistDTO.java` | 9 |
| 新建 | `backend/.../dto/sitter/SitterSubscriptionDTO.java` | 9 |
| 新建 | `backend/.../service/BlacklistService.java` | 9 |
| 新建 | `backend/.../controller/v1/BlacklistController.java` | 9 |
| 新建 | `backend/.../controller/v1/SitterSubscriptionController.java` | 9 |
| 新建 | `backend/src/main/resources/db/migration/V16__blacklist_subscription_dates.sql` | 9 |
| 新建 | `backend/.../smoke/SubscriptionSmokeTest.java` | 9 |
| 新建 | `backend/.../smoke/BlacklistSmokeTest.java` | 9 |
| 新建 | `frontend/tests/e2e/sitter/subscription.spec.js` | 9 |
| 新建 | `frontend/tests/e2e/sitter/client-gate.spec.js` | 9 |
| 修改 | `frontend/tests/e2e/sitter-business.spec.js` | 9 |
| 修改 | `frontend/tests/pages/DashboardPage.js` | 9 |

### Phase 1–7 原始檔案（參考）

| 操作 | 檔案 | 狀態 |
|------|------|:----:|
| 修改 | `frontend/src/index.css` | ✅ |
| 修改 | `frontend/src/components/shared/BottomNavBar.jsx` | ✅ |
| 修改 | `frontend/src/locales/zh-TW.json` | ✅ |
| 新建 | `frontend/src/pages/Client/Sitters.jsx` | ✅ |
| 修改 | `frontend/src/App.jsx` | ✅ |
| 修改 | `frontend/src/pages/Sitter/Dashboard.jsx` | ✅ |
| 修改 | `frontend/src/components/sitter/UpcomingVisitCard.jsx` | ✅ |
| 修改 | `frontend/src/pages/Client/Dashboard.jsx` | ✅ |
| 修改 | `frontend/src/pages/Sitter/Orders.jsx` | ✅ |
| 修改 | `frontend/src/pages/Sitter/Finance.jsx` | ✅ |
| 修改 | `frontend/src/store/notificationStore.js` | ✅ |
| 修改 | `frontend/src/pages/Shared/Notifications.jsx` | ✅ |
| 修改 | `frontend/src/pages/Auth/Profile.jsx` | ✅ |
| 修改 | `frontend/tests/pages/DashboardPage.js` | ✅ |
| 修改 | `frontend/tests/pages/AuthPage.js` | ✅ |
| 修改 | `frontend/tests/pages/ProfilePage.js` | ✅ |
| 修改 | `frontend/tests/e2e/sitter-business.spec.js` | ✅ |
| 新建 | `frontend/tests/e2e/client/sitters.spec.js` | ✅ |
| 新建 | `frontend/tests/pages/ClientSittersPage.js` | ✅ |
| 新建 | `frontend/tests/e2e/sitter/finance.spec.js` | ✅ |
| 新建 | `frontend/tests/pages/FinancePage.js` | ✅ |
| 新建 | `frontend/tests/e2e/shared/notifications.spec.js` | ✅ |
| 新建 | `frontend/tests/e2e/client/client-profile.spec.js` | ✅ |
