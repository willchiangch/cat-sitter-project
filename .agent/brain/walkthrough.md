# WhiskerWatch Bug Fixes & Feature Enhancements (V31)

本次更新解決了多項 UI 交互異常，修復了後端 API 路徑映射問題，並強化了保母端的身分驗證視覺回饋與訂閱流程。

## 1. 核心 Bug 修復 (Core Bug Fixes)

### 導航高亮修正
- **問題**：在「管理我的毛孩」等子頁面時，底部導航列會錯誤地同時高亮「行程」與「我的」。
- **修正**：在 [BottomNavBar.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/shared/BottomNavBar.jsx) 中為 `/client` 與 `/sitter` 加入 `end` 屬性，確保僅在路徑完全匹配時才高亮 Dashboard。

### 後端上傳失敗 (500 Error)
- **問題**：保母上傳證件 (`identity/front`) 時因為目錄結構包含斜線導致控制器路徑匹配失敗。
- **修正**：重構 [UploadController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/controller/v1/UploadController.java)，使用 Ant-style 匹配 (`/**`) 與 `HttpServletRequest` 解析，支援任意深度的目錄結構。

### 銀行資訊儲存 (Race Condition)
- **問題**：React 狀態閉包導致連續調用 `handleUpdate` 時，銀行代碼會覆蓋銀行帳號（或反之）。
- **修正**：在 [Profile.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Profile.jsx) 中將多個更新請求合併為單次 API 調用。

### 頁面視覺優化
- **保母預覽**：自傳區域限制最大高度並加入捲軸。
- **在地化**：預約頁面的寵物種類 (Species) 已完全翻譯為繁體中文。
- **React Key**：修復了 `ServicePackages.jsx` 中列表渲染缺失/重複 Key 的警告。

---

## 2. 新增功能 (New Features)

### 保母認證狀態標章
- 為保母新增了「未認證 / 審核中 / 已認證」三段式狀態邏輯。
- 在保母個人資料與 Dashboard 名字旁加入 **藍色認證勾勾** (Verified Badge)。
- 狀態變化邏輯：
    - `未認證`：尚未上傳身分證件照片。
    - `審核中`：已上傳照片，等待後端改寫 `isVerified` 旗標。
    - `已認證`：`isVerified` 為 True，顯示藍勾勾。

### 訂閱合約與支付模擬
- **合約協議彈窗**：切換方案前新增合約說明，條款包括自動續約告知、未繳費降級規則、不支援退費以及升級補差價規則。
- **支付動畫**：加入「付費處理中...」視覺遮罩，提升系統真實感與操作回饋。

---

## 3. 驗證結果 (Verification)

### E2E 測試結果（V30 完成後）
- **總計**：46 tests，**46/46 全數通過**
- **新增 V30 測試**：`tests/e2e/sitter/v30-verification-and-subscription.spec.js`（6 tests，全數通過）

### E2E 測試結果（V31 完成後）
- **總計**：57 tests，**57/57 全數通過**
- **新增 V31 測試**：`tests/e2e/sitter/v27-promo-code-subscription.spec.js`（11 tests，全數通過）
  - `[V27 UI]` Agreement modal 包含折扣碼輸入框與套用按鈕
  - `[V27 UI]` 未輸入折扣碼時確認按鈕顯示原價
  - `[V27 有效碼]` 輸入有效折扣碼後顯示折扣金額
  - `[V27 有效碼]` 有效折扣後確認按鈕顯示折扣後實付金額
  - `[V27 有效碼]` 價格摘要區塊顯示原價（刪除線）與實付金額
  - `[V27 無效碼]` 無效折扣碼顯示錯誤訊息
  - `[V27 無效碼]` 無效折扣碼後確認按鈕仍顯示原價
  - `[V27 免費]` 折扣後金額為 0 時按鈕變為「免費啟用方案」
  - `[V27 免費]` 免費啟用點擊後直接成功（不出現付費遮罩）
  - `[V27 免費]` 免費啟用後方案更新為目標方案（PUT 被呼叫）
  - `[V27 鍵盤]` 在折扣碼輸入框按 Enter 觸發驗證

---

## 4. V31 新功能詳細說明

### 折扣碼訂閱系統

**後端架構**
- `V27 migration`：`subscription_change_logs` table + `promo_codes` 新增 `discount_type`（FIXED/PERCENT）/ `discount_percent`
- `SubscriptionChangeLog` entity：記錄 fromPlanCode / toPlanCode / changeType / promoCodeUsed / original/discount/finalAmount
- `PromoCodeService`：`calculateFinalAmount()` 支援固定金額與百分比折扣，最低限 0
- `SubscriptionService.changePlan()`：接受可選的 `promoCode`，若 `finalAmount <= 0` 直接免費啟用並以 `FREE_REDEMPTION` 記入 log
- 新 endpoint：`POST /api/v1/sitters/me/subscription/validate-promo` → `PromoValidationResponse`

**前端 UX 流程**
1. 點「切換方案」→ Agreement modal 出現
2. 輸入折扣碼 → 點「套用」或按 Enter 觸發驗證 API
3. 有效碼：顯示 `折扣 $X，實付 $Y` + 價格摘要區塊（刪除線原價 → 實付金額）
4. 無效碼：顯示錯誤訊息，按鈕維持原價
5. 實付 = 0：確認按鈕變綠色「免費啟用方案」，跳過付款遮罩直接呼叫 changePlan
6. 實付 > 0：正常走付款流程

### 關鍵程式碼變更（V31）
- [V27 migration SQL](backend/src/main/resources/db/migration/V27__subscription_change_logs_and_promo_improvements.sql) ← 新增
- [SubscriptionChangeLog.java](backend/src/main/java/com/catsitter/api/entity/SubscriptionChangeLog.java) ← 新增
- [SubscriptionChangeLogRepository.java](backend/src/main/java/com/catsitter/api/repository/SubscriptionChangeLogRepository.java) ← 新增
- [PromoCode.java](backend/src/main/java/com/catsitter/api/entity/PromoCode.java) — 加 discountType / discountPercent
- [PromoCodeService.java](backend/src/main/java/com/catsitter/api/service/PromoCodeService.java) — 加計算方法
- [SubscriptionService.java](backend/src/main/java/com/catsitter/api/service/SubscriptionService.java) — changePlan 支援折扣 + log；新增 validatePromo
- [SitterSubscriptionController.java](backend/src/main/java/com/catsitter/api/controller/v1/SitterSubscriptionController.java) — 新增 validate-promo endpoint
- [SubscriptionManagement.jsx](frontend/src/pages/Sitter/SubscriptionManagement.jsx) — 折扣碼 UI
- [api.ts](frontend/src/services/api.ts) — 新增 validatePromo、changePlan 支援 promoCode
- [v27-promo-code-subscription.spec.js](frontend/tests/e2e/sitter/v27-promo-code-subscription.spec.js) ← 新增

> [!TIP]
> 建議在實際環境測試時，手動將資料庫中的 `is_verified` 設為 `true` 以確認藍勾勾的最終顯示效果。
> 折扣碼需先在後台（或直接 SQL）插入 `promo_codes` 資料表，`discount_type` 可填 `FIXED` 或 `PERCENT`。
