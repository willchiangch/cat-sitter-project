# 登入頁面與身分驗證修改計劃

本修改計劃旨在達成以下兩個主要目標：
1. **UAT環境隱藏密碼登入**：以環境變數 / 功能開關方式，讓特定部署環境（如 UAT）隱藏密碼登入與註冊區塊，僅開放社交平台登入（Google, Apple, Facebook 等），同時保留開發環境及 E2E 測試所需的帳密登入入口。
2. **人臉認證(自拍)取代身分證反面**：修改保母身份驗證流程，不再要求提交「身分證反面」，改為要求上傳「人臉辨識照片(Face Photo)」，並於行動物件/PWA調用原生相機，後續由人工於GCS平台與資料庫手動審核。

## User Review Required

> [!WARNING]
> 現有程式碼 Bug 提醒：
> 在評估時發現，目前前端上傳身分證照片 (呼叫 `profileService.updateSitterMe`) 時，後端的 `UpdateSitterProfileRequest` DTO 和 `SitterProfileService` 其實 **沒有** 處理 `idCardFrontUrl` 和 `idCardBackUrl`，導致上傳照片後，後端會直接忽略該網址，無法寫入資料庫。本次修改將會一併修正這個資料綁定遺漏的 Bug。

## Proposed Changes

---

### Frontend 修改

#### [MODIFY] [Login.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Login.jsx)
- 引入前端環境變數（例如 `import.meta.env.VITE_ENABLE_PASSWORD_LOGIN`）。
- 預設值為 `true`（當環境變數不存在時），以避免影響本地端與 E2E 測試。
- 若在 UAT 環境設為 `"false"`，則隱藏 `email`, `password` 輸入框、**提交按鈕** 以及「沒有帳號？註冊」等區塊，只留「社群登入 (使用Google繼續等)」。

#### [MODIFY] [Register.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Register.jsx)
- 使用相同的環境變數 `VITE_ENABLE_PASSWORD_LOGIN`，若為 `"false"` 時，直接隱藏註冊輸入框，若有需要也可以導向其他提示畫面或隱藏註冊表單。但由於註冊頁面目前沒有提供社群登入按鈕，我們會將前端的註冊頁入口也依據開關做調整，或是在 `Register.jsx` 加入社群綁定的按鈕。

#### [MODIFY] [Profile.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Profile.jsx)
- 在身分驗證的區塊中，修改左側為「證件正面」，右側改為「人臉辨識(自拍)」。
- 移除 `idCardBackUrl` 相關邏輯。
- 在人臉辨識上傳的 `input` 加入 `capture="user"` 以及 `accept="image/*"` 屬性。在行動裝置的 PWA 中，這會直接開啟前置鏡頭進行拍攝，完美符合需求。
- 更新上傳處理將欄位改為對應到 `facePhotoUrl`。

---

### Backend 修改

#### [MODIFY] [Profile.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/Profile.java)
- 新增欄位：`@Column(name = "face_photo_url", length = 1024) private String facePhotoUrl;`。
- 保留 `idCardBackUrl` 欄位或將其標為廢棄，為確保資料庫整潔，我們將透過 Flyway migration 直接移除它。

#### [MODIFY] [SitterProfileResponse.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/dto/sitter/SitterProfileResponse.java)
- 移除 `idCardBackUrl`。
- 新增 `facePhotoUrl`。

#### [MODIFY] [UpdateSitterProfileRequest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/dto/sitter/UpdateSitterProfileRequest.java)
- 新增 `@Size(max = 1024) String idCardFrontUrl`。
- 新增 `@Size(max = 1024) String facePhotoUrl`。
*(原本遺漏了這些欄位導致前端儲存不進資料庫)*

#### [MODIFY] [SitterProfileService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/service/SitterProfileService.java)
- 在 `updateSitterProfile` 方法中，將 request 傳入的 `idCardFrontUrl` 及 `facePhotoUrl` 對應寫回 DB entity 內。
- 修正原本無處理的 bug。

#### [NEW] [V15__update_profile_identity_verification.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V15__update_profile_identity_verification.sql)
- 撰寫資料庫移轉腳本以匹配 JPA 的更動：
  ```sql
  ALTER TABLE profiles ADD COLUMN face_photo_url VARCHAR(1024);
  ALTER TABLE profiles DROP COLUMN id_card_back_url;
  ```

---

### OpenAPI 規格同步

#### [MODIFY] [openapi.yaml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi/openapi.yaml)
- 在 `SitterProfile` 與 `UpdateSitterProfileRequest` 補上 `facePhotoUrl` 等新欄位，維持 API Contract 一致性。

#### [MODIFY] [openapi.json](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi.json)
- 同步更新 JSON 格式規格書，確保前端 SDK 生成工具（`openapi-ts`）能正確抓取新屬性。

---

## Open Questions

1. **對於註冊頁 (Register.jsx) 的處理方式：**
   若關閉密碼登入，該頁面目前沒有「使用社群註冊」的按鈕，因為登入跟註冊似乎都可以使用同一個 OAuth Endpoint 進行。是否在 `Register.jsx` 隱藏表單後直接顯示與 `Login.jsx` 相同的社群登入按鈕？或者針對 UAT 直接在路由將 `/register` 重導向到 `/login`？

2. **確認 Bug 修復：**
   本次將一起修復原前後端串接的「上傳照片後未存進資料庫」的問題，此舉是否符合您的預期？

## Verification Plan

### 自動測試
- 若有 E2E 測試，將確保在未設定變數 (預設啟用) 情況下 `sitter-business.spec.js` 執行的自動化測試不會壞掉。

### 人工驗證
- 準備 `.env.uat` 或將 `VITE_ENABLE_PASSWORD_LOGIN=false` 設定啟動 Frontend。
- 觀察登入畫面，確認是否只包含 Google, Meta 等按紐，無帳密登入入口。
- PWA / 手機端登入後，點擊「身分證驗證 - 自拍」，確認是否啟動了手機相機。
- 透過前台完成照片上傳後，檢視 DB `profiles` 表是否成功紀錄了 `id_card_front_url` 以及 `face_photo_url`。
