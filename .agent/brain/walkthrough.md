# 實作完成確認：UAT 登入控制與人臉辨識更新

本任務已根據核准的計劃順利完成。我們成功實作了 UAT 環境的存取限制、將身分驗證流程更新為 PWA 人臉自拍，並同步了所有相關規格。

## 變更項目總覽

### 1. Frontend: 登入與註冊控管
- **[Login.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Login.jsx)**: 引入 `VITE_ENABLE_PASSWORD_LOGIN` 開關。若設為 `false` (UAT 環境)，將隱藏 Email/密碼登入區塊，強制使用者使用 Google、Facebook 或 Apple 登入。
- **[Register.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Register.jsx)**: 同樣受開關控制。若功能關閉，會自動重導向至登入頁面，防止非法進入註冊流程。
- **[.env.example](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/.env.example)**: 新增變數範本，預設值為 `true` 以維持開發環境相容性。

### 2. Frontend: 人臉辨識自拍 (PWA)
- **[Profile.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Profile.jsx)**: 
  - 移除「身分證反面」，替換為「人臉辨識(自拍)」區塊。
  - 使用 `capture="user"` 屬性，在行動裝置上會直接調用前置攝像頭，符合需求。
  - 修復了 `Profile.jsx` 的 Lint 錯誤（移除未使用的 `t` 與 `isLoading`）。

### 3. Backend & Database: 資料結構更新
- **Profile 實體**: 在 `Profile.java` 中將 `id_card_back_url` 替換為 `face_photo_url`。
- **DTO 更新**: 同步更新 `SitterProfileResponse` 與 `UpdateSitterProfileRequest`。
- **[BUG 修復]**: 修正了原本 `UpdateSitterProfileRequest` 遺漏身分證與人臉照片網址欄位，導致資料無法存入資料庫的問題。
- **Migration**: 建立 `V15__update_profile_identity_verification.sql` 執行資料庫欄位隱轉。

### 4. OpenAPI 規格同步
- **[openapi.yaml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi/openapi.yaml)**: 更新 API 手冊，確保 Contract 與實作一致。
- **[openapi.json](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi.json)**: 同步 JSON 規格書，支援前端 SDK 自動生成。

## 驗證結果

> [!TIP]
> **驗證重點：**
> 1. 設定 `VITE_ENABLE_PASSWORD_LOGIN=false` 後，登入頁面僅剩社群按鈕。
> 2. 後端已通過 `./mvnw compile` 驗證，確保實體與 Service 邏輯正確。
> 3. 前端已通過 `eslint` 檢查，無新引入的語法錯誤。

## 後後續建議
- **UAT 部署**: 部署到 UAT 時請務必在環境變數中設定 `VITE_ENABLE_PASSWORD_LOGIN=false`。
- **人工審核**: 由於目前無後台系統，請直接至 Google Cloud Storage 檢視照片，並在資料庫手動更新 `verification_status`。

[實作細節與工作任務清單參考](file:///Users/will_chiang/Widget_home/cat-sitter-project/.agent/brain/task.md)
