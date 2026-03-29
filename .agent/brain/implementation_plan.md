# 社群登入 (Social Login) 實作計畫

本計畫旨在為 WhiskerWatch 導入 Google、Apple 與 Facebook/Meta 的社群登入功能，提升使用者體驗並確保帳號安全性與聯絡有效性。

## 使用者評論與決策 (User Review Required)

> [!IMPORTANT]
> **身分與通訊解耦策略**：社群登入 (OAuth) 僅用於帳號鑑定身分。後續的 **Email 驗證** 將作為獨立流程，主要用於確保雙方聯絡通透，不與註冊流程綁定，避免在第一步流失使用者。

> [!TIP]
> **Email 為唯一識別**：若不同 Provider (Google/FB) 使用相同 Email，系統將嘗試進行帳號合併，保留同一組使用者 Profiles。

## 擬議變更 (Proposed Changes)

### 1. 後端基礎設施 (Backend Infrastructure)

#### [MODIFY] [pom.xml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/pom.xml)
- 新增 `spring-boot-starter-oauth2-client` 依賴。

#### [MODIFY] [OAuthProvider.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/enums/OAuthProvider.java)
- 新增 `FACEBOOK` 列舉值。

#### [NEW] `CustomOAuth2UserService.java`
- 繼承 `DefaultOAuth2UserService`。
- 核心邏輯：從 OAuth2User 中提取 Email 與 Provider ID，在資料庫中搜尋或建立 `Account`。

#### [NEW] `OAuth2AuthenticationSuccessHandler.java`
- 繼承 `SimpleUrlAuthenticationSuccessHandler`。
- 登入成功後：
    1. 生成 JWT Token。
    2. 將 Token 以 Cookie 或 Query Parameter 方式傳回前端（例如：`/login/callback?token=xxx`）。

#### [MODIFY] [SecurityConfig.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/config/SecurityConfig.java)
- 配置 `.oauth2Login()`。
- 設定 `userInfoEndpoint` 指向 `CustomOAuth2UserService`。
- 設定 `successHandler` 指向 `OAuth2AuthenticationSuccessHandler`。

### 2. 前端開發 (Frontend Development)

#### [NEW] `LoginPage.jsx`
- 建立現代化的登入頁面，包含：
    - 「使用 Google 繼續」按鈕 (Brand Colors)。
    - 「使用 Facebook 繼續」按鈕。
    - 「使用 Apple 繼續」按鈕。
- 串接後端 `/oauth2/authorization/{provider}` 端點。

#### [NEW] `LoginCallback.jsx`
- 處理登入成功後的跳轉。
- 從 URL 提取 JWT 並存入儲存空間。
- **Conditional Redirect**：若使用者尚未完成「通訊 Email 驗證」，則導向一個輕量化的補完頁面或在 Dashboard 顯示警示。

#### [NEW] `CommunicationVerify.jsx`
- 獨立的 Email 驗證 UI。
- 提供「發送驗證信」與「輸入驗證碼/點擊連結」的互動。

---

## 開放問題 (Open Questions)

- **Apple ID 實作**：Apple Login 通常需要額外配置私鑰 (.p8) 與驗證網域。第一階段優先完成 Google/FB，穩定後再獨立處理 Apple。
- **Resend 整合**：已確認使用 **Resend API Key**。我們將透過後端 Service 直接呼叫 Resend REST API，發送驗證碼/連結給使用者，不依賴傳統 JavaMailSender。

## 驗證計畫 (Verification Plan)

### 自動化測試
- 使用 `MockMvc` 測試 OAuth2 登入成功後的 JWT 簽發邏輯。
- 確認不同 Provider 的 Email 衝突處理。

### 手動驗證
1. 點擊前端「使用 Google 登入」。
2. 完成 Google 授權。
3. 確認正確重導回 Dashboard 且登入身分生效。
4. 檢查資料庫 `accounts` 表是否正確儲存 `oauth_provider` 與 `oauth_id`。
