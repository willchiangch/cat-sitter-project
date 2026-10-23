# TS-JOURNEY-01: 保母上架全流程 (Sitter Onboarding)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-000/SD-000（帳號）、PRD-017/SD-017（KYC）、PRD-003/SD-003（服務方案）、PRD-018/SD-018（保母公開檔案） |
| **測試類型** | ✅ 跨模組整合測試（真後端、真資料庫，非 mock） |
| **優先級** | P0（保母上架是平台供給端的起點，任一環節斷裂則後續 Journey 皆無法進行） |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | `frontend/e2e/journeys/sitter-onboarding.spec.ts` |

---

## 一、測試邏輯定義 (Given / When / Then)

* **Given**：一個全新的、從未存在過的保母帳號（email 網域 `@e2e-journey.test`）。
* **When**：依序完成註冊（略過 OTP，見架構備註）→ 切換為保母角色 → 提交 KYC → 管理員審核通過 → 開啟接單狀態 → 上架多個服務方案。
* **Then**：任何人（包含未登入的訪客）打開這個保母的公開網址，都能看到真實的保母資料與已上架的方案清單，而不是模糊化的「保母休息中」內容。

**架構備註**：本測試略過 Email OTP 驗證環節（OTP 本身由人工測試覆蓋），改用 `/api/internal/test-data/provision-account` 直接建立「等同已通過 OTP 驗證」的帳號，之後仍走真實的 `/api/auth/login` 取得 token。

---

## 二、測試步驟與多維度驗證

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 1 | API：`provision-account` 建立帳號 + 真實 `/api/auth/login` | 登入成功取得 accessToken | 帳號 email 網域為 `@e2e-journey.test` |
| 2 | API：`POST /api/auth/switch-role {targetRole:"SITTER"}` | 切換為保母角色成功 | 後端 lazy 建立 `Profile(kycStatus=UNVERIFIED)`；此路徑全專案目前唯一測試覆蓋點。於步驟 3 開啟 `/sitter/kyc` 時攔截頁面自己發出的 `GET /api/sitter/kyc/status` 回應斷言 `UNVERIFIED`，再斷言徽章為「未認證」（徽章在 API 回來前也會 fallback 成「未認證」，所以必須先等真實回應） |
| 3 | UI：`/sitter/kyc` 上傳證件正面 + 自拍照片，送出 | 頁面顯示「審核中」狀態徽章，頂部橫幅同步為「認證資料正在審核中」、不含「尚未完成」（U3） | `GET /api/sitter/kyc` 回應 `kycStatus=PENDING_REVIEW` |
| 4 | API：admin 呼叫 `GET /api/admin/kyc/pending` | 清單中可撈到剛提交的紀錄 | `recordId` 對應到該保母 |
| 5 | UI：admin 於 `/admin/kyc` 進入該筆詳情，點擊「批准認證」 | 頁面顯示審核通過訊息並導回清單 | `Profile.kycStatus` 轉為 `VERIFIED`；UI 斷言「實名認證審核已通過！」（`AdminKycDetail` 只顯示 1.5 秒就導回清單），且詳情頁截圖前先等「批准認證」按鈕出現（詳情 API 回來才算進入詳情頁）；證件照/自拍 `admin-kyc-detail-id-front-img`/`-selfie-img` 已載入（`naturalWidth>0`）、寬度 > 250px 且右緣不超出 `<main>`（U1）；成功訊息出現時（導回清單前）`admin-kyc-detail-status-badge` 已是「已通過」（U2） |
| 6 | API：`PUT /api/sitter/kyc/open {isOpen:true}` | 開啟接單狀態成功（前置條件 `kycStatus=VERIFIED` 已滿足） | `Profile.isOpen=true` |
| 6.5 | API：`PUT /api/sitter/profile` 設定 `displayName`/`bio`/`tags`/`isVisible:true` | 公開檔案資料寫入成功 | `Profile.displayName` 非空字串（此欄位獨立於 `User.fullName`，未設定則公開頁 `<h1>` 是空的，實測時踩到這個坑） |
| 7 | UI：`/sitter/plans` 建立 2 個服務方案，寵物類型與價格都不同：`CAT`/$600、`DOG`/$900 | 方案清單顯示新建立的方案，各自卡片只顯示自己的寵物類型標籤（🐱 貓咪 / 🐶 狗狗）；價格區塊 `sitter-plan-price-*` 右緣不超出卡片（U7） | 每筆方案 `isActive=true`（於步驟 8 的匿名 plans API 斷言）；**注意**：`openModal(null)` 新增方案時 `applicablePetTypes` 預設已勾選 `CAT`，CAT 方案不用再點，非 CAT 方案要先點 `CAT` 取消再點目標類型 |
| 8 | UI：以全新、未登入的瀏覽器 context 打開 `/sitter/{sitterId}/profile` | 頁面顯示步驟 6.5 設定的顯示名稱、標籤與方案清單，**不是**「保母休息中」；每個方案列各自顯示 `NT$ 600` / `NT$ 900` | 匿名頁面全程 0 次 `POST /api/auth/login`、公開頁 API 不帶 `Authorization`、`localStorage` 無 token（`expectStillAnonymous`，C1）；以同一個匿名 context 的 request（不帶 token）查：`GET /api/sitter/profile/{sitterId}` 回應 `gated=false`、`displayName` 等於步驟 6.5 的值；`GET /api/sitters/{sitterId}/plans` 包含步驟 7 兩筆方案，各自 `isActive=true`、`price` 與 `applicablePetTypes` 與建立時一致；`defaultTasks` 依寵物類型：CAT 含「清理砂盆」，DOG 含「散步遛狗」且**不含**「清理砂盆」（U6） |

---

## 三、邊界條件 / 例外場景

* **switch-role 冪等性**：同一帳號對同一 `targetRole` 重複呼叫 `switch-role` 不應重複建立 `Profile`（唯一索引 `uidx_profiles_user_type` 保護），本測試不重複呼叫，但值得在未來補一條專門的負向測試。
* **KYC 未過不能開單**：若略過步驟 5 直接呼叫步驟 6（`isOpen:true`），後端應回 403（`KycServiceImpl.updateSitterOpenStatus`），此為既有 `KycControllerTest` 覆蓋範圍，本 journey 不重複測。
* **雙重卡控獨立驗證**：`gated=false` 需要 `kycStatus=VERIFIED` 與 `isVisible=true` 同時成立；`isOpen=true` 需要 `kycStatus=VERIFIED`。兩者是分開的卡控點（`SitterPublicProfileServiceImpl.getPublicProfile` 與 `KycServiceImpl.updateSitterOpenStatus`），本 journey 依序都會經過，等於間接驗證兩處都正確放行。

---

## 四、附註 / 復原步驟

* **資料清理**：本測試建立的帳號、KYC 紀錄、服務方案皆落在 `@e2e-journey.test` 網域，由 CI 跑完後呼叫 `POST /api/internal/cron/test-data/cleanup-e2e-journeys` 統一硬刪除（含對應 GCS 上的 KYC 證件照物件）。
* **環境相依**：本測試打正式站（`BASE_URL=https://wd-pet-sitter.web.app`），需要 CI 環境變數 `INTERNAL_CRON_SECRET` 與正式環境的 `X-Internal-Secret` 一致，才能呼叫 `provision-account`。
* **共用種子帳號**：步驟 4-5 的管理員操作沿用既有 `admin@test.com` 種子帳號（密碼 `password`），未使用 `@e2e-journey.test` 網域——因為系統只有這唯一固定的管理員帳號，不需要、也不應該每次動態建立。

---

## 五、本測試發現並修復的真實 bug

**管理員審核詳情頁看不到證件照（2026-10，驗收證據 / Backlog 第 3 批 U1）**：`AdminKycDetail` 用 `gridTemplateColumns: '400px 1fr'` 兩欄排版，但 `AppShell` 是 390px 行動版寬度（`--max-width-mobile`）、外層又疊了 1.5rem 內距，左欄 400px 就超出可用寬度，右欄的證件照/自拍整個被 `AppShell` 的 `overflow:hidden` 裁掉，步驟 5 的截圖只看得到基本資料。管理員看不到照片就無從比對。修法：改成單欄（基本資料 → 媒體預覽 → 審核決策），照片各佔整列寬度，移除重複的左右內距。迴歸：步驟 5 斷言照片寬度與邊界；mock `e2e/admin-kyc-detail.spec.ts`。
**核准後徽章在導回清單前仍是 PENDING（2026-10，驗收 P3 / Backlog 第 3 批 U2）**：`AdminKycDetail` 核准/駁回成功後只顯示成功訊息、1.5 秒後導回清單，詳情資料沒有更新，這段時間徽章仍是「待審核 (PENDING)」、按鈕也還能再按，看起來像沒生效。修法：成功後直接把本頁的 `kycStatus` 更新為 `APPROVED`/`REJECTED`（後端已提交，不需重抓），並鎖住審核按鈕。迴歸：步驟 5 斷言成功訊息出現時徽章為「已通過」；mock `e2e/admin-kyc-detail.spec.ts` U2（另斷言按鈕 disabled、review 只送一次）。
**KYC 送出後頂部橫幅仍顯示「尚未完成認證」（2026-10，驗收 P4 / Backlog 第 3 批 U3）**：`AppHeader` 的 KYC 橫幅自己在換頁時打 `GET /api/sitter/kyc/status`，`/sitter/kyc` 頁面另外打一次；送出 KYC 後停在同一頁，頁面徽章已是「審核中」，橫幅仍是送出前的「⚠️ 您尚未完成保母實名認證」。另外 Header 的 `catch` 一律當 `UNVERIFIED`，API 失敗也會掛這條橫幅。修法：兩邊共用 React Query `['kyc','status']`（`useKyc.ts`），頁面取得新狀態即寫回快取；取不到狀態不顯示橫幅（SD-014 §6.1）。迴歸：步驟 3 斷言橫幅；mock `e2e/sitter-kyc-banner.spec.ts`（修前 2/2 失敗，訊息即「Received: ⚠️ 您尚未完成保母實名認證…」）。
**狗狗方案的預設 SOP 有「清理砂盆」（2026-10，驗收 N1 / Backlog 第 3 批 U6）**：`SitterPlans.openModal(null)` 不論勾哪種寵物，預設任務都寫死「基本餵食 / 清理砂盆 / 更換新鮮水」，步驟 7 建立的 DOG 方案公開出去也帶著「清理砂盆」。修法：`src/components/plans/defaultTasks.ts` 依寵物類型給模板（多選取聯集），新增方案時勾選寵物會跟著換，保母手動改過任務後不再覆蓋（SD-003 §前端）。迴歸：步驟 8 API 斷言；mock `e2e/plan-default-tasks.spec.ts`。
**方案卡價格溢出卡片右邊界（2026-10，驗收 N2 / Backlog 第 3 批 U7）**：步驟 7 的方案名稱帶時間戳（`Journey方案A-常態照護-1792780917874`），長串英數無法斷行；卡片頂部左欄沒有 `min-width:0`，右側價格又是 `flex-shrink:0`，結果「$ 600 / 60 分鐘」被擠出卡片右緣。修法：左欄 `flex:1; min-width:0`、名稱 `overflow-wrap:anywhere`，價格與時長改上下兩行（SD-003 §0.2）。迴歸：步驟 7 斷言價格右緣；mock `e2e/sitter-plan-card-layout.spec.ts`（修前右緣 801px > 卡片 787px）。

