# Phase 2: 飼主端核心流程 - 預約精靈 (Public Booking Wizard)

實作飼主預約流程，採用 3-Step Wizard 設計，並嚴格遵循 Stitch "The Intuitive Concierge" 的高端社論感美學。

## 使用者評論與回饋 (User Review Required)
- **Step 1 交互**: 採用點擊選取日期範圍，或是單日選取？預計先實作多日點選。
- **Step 2 配置**: 方案與毛孩的關聯性。

## 擬議變更

### [NEW] [PublicBookingPage.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/client/PublicBookingPage.tsx)
- 實作分步導引（Stepper）：
    - **Step 1 (Dates)**: 日曆選擇器，使用 Tonal Layering 標示選中日期。
    - **Step 2 (Plan & Pets)**: 
        - 方案卡片（使用 `card-layered`）。
        - 毛孩選擇（多選圓框）。
    - **Step 3 (Review)**: 總結試算與備註，使用 Signature CTA (Gradient) 送出。
- 狀態管理：使用本地 `useState` 暫存預約資訊，最後透過 `idempotency_key` 送出。

### [MODIFY] [App.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/App.tsx)
- 加入 `booking` 路由視圖切換。

### [NEW] [booking.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/types/booking.ts)
- 定義預約相關 Interface。

## 驗證計畫

### 自動化測試
- 新增 `booking-wizard.spec.ts` 驗證三步驟流程。
- 驗證 `Idempotency-Key` 是否隨 Request 發送。

### 手動驗證
- 檢查三步驟切換的動畫與呼吸感（留白比例）。
- 確認在 Client (Blue) 主題下的視覺正確性。
