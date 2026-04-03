# 任務清單：UAT 登入開關與人臉辨識修改

- [x] 1. Frontend 修改
  - [x] 1.1 更新 `Login.jsx` 隱藏 UAT 期間的註冊與密碼登入
  - [x] 1.2 更新 `Register.jsx` 加入環境變數防呆機制與社群按紐
  - [x] 1.3 更新 `Profile.jsx` 將身分證反面改為 PWA 人臉自拍與 `facePhotoUrl`
- [x] 2. Backend 修改
  - [x] 2.1 修改 `Profile.java` 實體 (新增 `facePhotoUrl`，不使用 `idCardBackUrl`)
  - [x] 2.2 修改 `SitterProfileResponse.java` 回傳 DTO
  - [x] 2.3 修改 `UpdateSitterProfileRequest.java` 接收參數 DTO (修復未綁定 Bug)
  - [x] 2.4 修改 `SitterProfileService.java` 服務層儲存邏輯 (解決未儲存 Bug)
- [x] 3. DataBase Migration 修改
  - [x] 3.1 增加 `V15__update_profile_identity_verification.sql`
- [x] 4. OpenAPI 規格同步
  - [x] 4.1 更新 `openapi.yaml`
  - [x] 4.2 更新 `openapi.json`
- [x] 5. 進行驗證測試
