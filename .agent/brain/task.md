# 開發任務進度表 (Task List)

## Phase 1: 保母端核心與設計重構 (Completed)
- [x] **1.1 設計系統重構** ✅
    - [x] 更新 `global.css` 導入 Stitch "The Intuitive Concierge" 設計標記 (Tokens)
    - [x] 實作「無框線哲學 (No-Line)」與環境光陰影 (Ambient Glow)
    - [x] 導入 `Plus Jakarta Sans` 與 `Manrope` 雙字體系統
- [x] **1.2 核心元件與佈局優化** ✅
    - [x] 重構 `AppShell` 移除實線邊框，優化行動端比例
    - [x] 重構 `Card` 元件實作分層深度感
    - [x] 更新 `StatusBadge` 高對比色標
- [x] **1.3 保母頁面重構** ✅
    - [x] `SitterOrders`: 分頁切換改為圓角 Pill 樣式
    - [x] `OrderEvalView`: 報價區塊改為背景分層與字體層級強化
- [x] **1.4 品質與審計** ✅
    - [x] 開啟 TypeScript 嚴格模式 (`strict: true`)
    - [x] 通過全域設計與非功能性需求 (NFR) 審計
    - [x] E2E 測試驗證通過

## Phase 2: 飼主端核心流程 (Next Up)
- [ ] **2.1 預約精靈 (PublicBookingPage)**
    - [ ] Step 1: 日期選擇與檔期預檢
    - [ ] Step 2: 方案選擇與毛孩護照關聯
    - [ ] Step 3: 總額試算與送出預約
- [ ] **2.2 飼主訂單管理 (ClientOrders)**
    - [ ] 訂單狀態時間軸呈現
    - [ ] 付款憑證上傳介面 (Stitch 質感)
- [ ] **2.3 E2E 驗證 (預約流程)**

## Phase 3: 保母功能 P1 (Backlog)
- [ ] **3.1 保母首頁 (Dashboard)**
- [ ] **3.2 任務面板 (ActiveService)**
- [ ] **3.3 個人設定與收款資訊**
