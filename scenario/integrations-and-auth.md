# 測試情境：進階整合與認證授權 (Integrations & Auth)

## 情境 1：社交登入與全站 Onboarding 攔截
**角色**：新使用者 (Newbie)
**預備條件**：未曾註冊過的新帳號。
1. **[Playwright E2E] 第三方授權**：點擊 Google 登入，經由 OAuth2 Callback 成功換取 JWT。
2. **[Playwright E2E] 攔截跳轉**：登入完成後，因 `profiles` 長度為 0，`App.jsx` 自動攔截並導航至 `/onboarding`。
3. **[Playwright E2E] 完成身分設定**：
    - 填寫顯示名稱「Whisker 新手保母」。
    - 選擇主要身分「我是貓咪保母」。
    - 點擊「完成設定」，預期後端建立 Profile 並將狀態設為登入中，隨後自動解鎖進入首頁。

## 情境 2：Google 行事曆自動同步與管理
**角色**：保母 (Sitter)
**預備條件**：已擁有保母身分。
1. **[Playwright E2E] 前往設定區塊**：進入「我的」-> 「個人基本資料」，往下捲動至「行事曆同步」。
2. **[Playwright E2E] 申請授權**：點擊「連結 Google 行事曆」，經由 OAuth 核准後，導向 `CalendarSyncResult` 頁面並播放成功動畫跳轉回來。
3. **[Vitest RTL] 驗證連結結果 (元件狀態)**：
    - 介面狀態顯示「同步中」。
    - 包含 iCal 專屬訂閱網址。
4. **[Vitest RTL] 重置與中斷 (按鈕邏輯)**：
    - 點擊「重置授權碼」，確認 iCal 網址產生了新 Token。
    - 點擊「斷開連結」，確認狀態返回「未連結」且清除網址。

## 情境 3：Email 信箱白名單驗證 (Communication Verify)
**角色**：保母或飼主 (All Users)
1. **[Vitest RTL] 請求驗證**：在 Profile 最上方點擊「請驗證您的 Email 信箱」橫幅 (驗證 Banner 正確渲染)。
2. **[Playwright E2E] 發送驗證信**：系統透過 Resend 發出動態 6 碼驗證碼。
3. **[Playwright E2E] 驗證通關**：填入正確數字並點擊「驗證」，後端 `is_email_verified` 轉為 `true`，解除鎖定並不再顯示警告橫幅。

## 情境 4：VIP 熟客免除問卷 (Sitter-Client Whitelist)
**角色**：保母 (Sitter) 與 飼主 (Client)
1. **[Vitest RTL] 保母加入名單 (表格邏輯)**：保母進入「熟客名單」頁面，將經常委託的家長 James 加入，並勾選「免除問卷填寫」。
2. **[Playwright E2E] 飼主快速通關 (跨頁與預約流程)**：
    - James 點擊該保母的預約連結。
    - 選擇貓咪與服務時間後，點擊繼續。
    - **預期結果**：直接跳過原本的「事前提問與注意事項 (Questionnaire)」步驟，介面顯示「尊榮常客狀態：考量到您與保母的長期信任，已為您免除本次照護問卷填寫。」並直達預約確認頁面。
