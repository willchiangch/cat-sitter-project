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
| 2 | API：`POST /api/auth/switch-role {targetRole:"SITTER"}` | 切換為保母角色成功 | 後端 lazy 建立 `Profile(kycStatus=UNVERIFIED)`；此路徑全專案目前唯一測試覆蓋點 |
| 3 | UI：`/sitter/kyc` 上傳證件正面 + 自拍照片，送出 | 頁面顯示「審核中」狀態徽章 | `GET /api/sitter/kyc` 回應 `kycStatus=PENDING_REVIEW` |
| 4 | API：admin 呼叫 `GET /api/admin/kyc/pending` | 清單中可撈到剛提交的紀錄 | `recordId` 對應到該保母 |
| 5 | UI：admin 於 `/admin/kyc` 進入該筆詳情，點擊「批准認證」 | 頁面顯示審核通過訊息並導回清單 | `Profile.kycStatus` 轉為 `VERIFIED` |
| 6 | API：`PUT /api/sitter/kyc/open {isOpen:true}` | 開啟接單狀態成功（前置條件 `kycStatus=VERIFIED` 已滿足） | `Profile.isOpen=true` |
| 6.5 | API：`PUT /api/sitter/profile` 設定 `displayName`/`bio`/`tags`/`isVisible:true` | 公開檔案資料寫入成功 | `Profile.displayName` 非空字串（此欄位獨立於 `User.fullName`，未設定則公開頁 `<h1>` 是空的，實測時踩到這個坑） |
| 7 | UI：`/sitter/plans` 建立 2-3 個服務方案（不同 `applicablePetTypes`/價格） | 方案清單顯示新建立的方案 | 每筆方案 `isActive=true`；**注意**：`openModal(null)` 新增方案時 `applicablePetTypes` 預設已勾選 `CAT`，不用再手動點擊，點擊反而會取消勾選 |
| 8 | UI：以全新、未登入的瀏覽器 context 打開 `/sitter/{sitterId}/profile` | 頁面顯示步驟 6.5 設定的顯示名稱、標籤與方案清單，**不是**「保母休息中」 | `GET /api/sitter/profile/{sitterId}` 匿名查詢回應 `gated=false`；`GET /api/sitters/{sitterId}/plans` 內容包含步驟 7 建立的方案名稱 |

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
