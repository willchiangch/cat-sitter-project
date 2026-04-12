# WhiskerWatch Bug Fixes & Feature Enhancements (V30)

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
  - `[V30 已認證]` isVerified=true 顯示「已認證保母」藍色標章
  - `[V30 審核中]` 有上傳照片但未認證顯示「審核中」
  - `[V30 未認證]` 無照片無認證顯示「未認證」
  - `[V30 合約彈窗]` 點擊切換出現「服務合約與訂閱協議」模態框
  - `[V30 合約條款]` 四項條款均顯示
  - `[V30 關閉彈窗]` 「我再考慮一下」可關閉模態框

### 同步修正的既有測試 bug
| 測試 | 根因 | 修法 |
|------|------|------|
| `client-profile.spec.js:37` Clicking 新增寵物 | 期待 `Cancel` 按鈕但實際是 `取消` | 修正為 `取消` |
| `v26-pet-health-status.spec.js:61` [V26 性別] | gender 欄位為按鈕組非 `<select>`，`option[value=UNKNOWN]` 不存在 | 改找 `getByRole('button', { name: '不清楚' })` |
| `booking-lifecycle.spec.js:24` Stage 3 ERR_ABORTED | 多步驟導航後 SW 重新註冊，`injectSmokeAuth` 的 SW 清除被 try/catch 吃掉 | Stage 3 前明確 goto `/` + evaluate 清 SW |

### 視覺對比 (Before/After)

#### 導航高亮 (修正後)
- Path: /client/pets → Active Tab: 我的 (Profile only, 不高亮行程)

#### 保母認證標章
- 已認證: 藍勾勾 + 「已認證保母」
- 審核中: 黃點 + 「審核中」
- 未認證: 灰勾 + 「未認證」

### 關鍵程式碼變更
- [Profile.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Profile.jsx)
- [SubscriptionManagement.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Sitter/SubscriptionManagement.jsx)
- [UploadController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/controller/v1/UploadController.java)
- [v30-verification-and-subscription.spec.js](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/tests/e2e/sitter/v30-verification-and-subscription.spec.js) ← 新增

> [!TIP]
> 建議在實際環境測試時，手動將資料庫中的 `is_verified` 設為 `true` 以確認藍勾勾的最終顯示效果。
