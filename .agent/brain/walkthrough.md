# Backend Smoke Testing Stabilization Walkthrough

本階段工作已順利完成！我們建立了極度穩定的後端測試環境，並成功運行了全自動化的 E2E 驗證。

## 核心達成目標
1. **修復前端崩潰**：解決了 `App.jsx` 中 `ClientOrders` 導入缺失導致的頁面全白問題。
2. **穩定 Smoke Seeding**：改用 `JdbcTemplate` 重寫 `SmokeDataSeeder`，解決了 Hibernate 在手動設定 UUID 時的樂觀鎖與狀態同步問題，並實現了啟動時自動清理舊資料的功能。
3. **完善 Mock Auth 機制**：
    - 實現了針對 `smoke` profile 的 `SmokeMockAuthFilter`。
    - 支援透過 `X-Smoke-Auth` Header 動態切換 Mock 身分（Sitter vs Parent/James），大幅簡化測試流程。
    - 修復了 `JwtAuthenticationFilter` 在遇到 Mock Token 時會拋出 500 錯誤的問題。
4. **修復報價邏輯錯誤**：解決了 `QuoteModal` 與 `OrderDetail` 之間欄位對應不一致導致的後端驗證失敗。
5. **E2E 全數通過**：成功執行 Playwright 測試，驗證了完整訂單生命週期。

## 修改檔案清單

### 後端 (Spring Boot)
- [SmokeDataSeeder.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/service/SmokeDataSeeder.java)：實現具備「自我清理」功能的 Idempotent Seeder。
- [SecurityConfig.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/config/SecurityConfig.java)：配置 CORS 與 `SmokeMockAuthFilter`。
- [SmokeMockAuthFilter.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/security/SmokeMockAuthFilter.java)：[NEW] 支援多身分 Mock 驗證。
- [JwtAuthenticationFilter.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/security/JwtAuthenticationFilter.java)：增加異常捕獲以支援 Mock 環境。

### 前端 (React)
- [App.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/App.jsx)：修復組件導入。
- [DecisionModals.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/sitter/DecisionModals.jsx)：修正報價欄位傳遞結構。
- [OrderDetail.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Sitter/OrderDetail.jsx)：對齊 API 請求參數。
- [golden_paths.spec.js](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/tests/e2e/golden_paths.spec.js)：整合 `X-Smoke-Auth` 測試標頭並更新 UUID。

## 驗證結果

### E2E 測試截圖與錄影
- **報價送出成功狀態**：[final_state.png](file:///Users/will_chiang/.gemini/antigravity/brain/0c0d92ce-c7bb-4efa-b4ce-42f337d9c772/after_quote_submission_1774796752827.png)
- **測試流程錄影**：[quote_submission_debug.webp](file:///Users/will_chiang/.gemini/antigravity/brain/0c0d92ce-c7bb-4efa-b4ce-42f337d9c772/quote_submission_debug_1774796521303.webp)

> [!TIP]
> 現在您可以隨時啟動 `./mvnw spring-boot:run -Dspring-boot.run.profiles=smoke` 來獲得一個完整的測試沙盒環境，無需擔心 Token 過期或資料重複問題。

---
完成日期：2026-03-29
驗證工時：~2 小時 (含環境調教)
