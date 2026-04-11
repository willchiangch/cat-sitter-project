# 專案進度清單 (V25)

## 核心功能優化 (Completed)
- [x] 修復媒體代理與擴充寵物資料 (Phase 11)
  - [x] 修改 `vite.config.js` 將代理 port 修正為 8080
  - [x] 修改 `PetSpecies.java` 加入 RABBIT
  - [x] 修改 `Pet.java` 加入 birthDate 欄位
  - [x] 修改 `CreatePetRequest.java` 與 `PetResponse.java`
  - [x] 修改 `ClientPetService.java` 映射生日資料
  - [x] 修改 `Profile.jsx` 加入驗證狀態 UI (綠勾) 與 寵物年齡顯示
  - [x] 修改 `PetFormModal.jsx` 加入生日選擇與種類更新
  - [x] 驗證圖片是否恢復正常顯示

## 數據完整性與 DX 強化 (Completed)
- [x] 實作全系統軟刪除機制 (Phase 12)
  - [x] 資料庫遷移 V25：新增 `deleted_at` 欄位
  - [x] 資料庫遷移 V25：唯一索引改為部分索引 (Partial Index)
  - [x] 實體層級：JPA `@SQLDelete` 與 `@SQLRestriction` 整合
- [x] 開發者體驗 (DX) 升級
  - [x] 實現 `smoke` 模式下 Console 螢光色驗證碼輸出
  - [x] 優化信箱更換流程：事件驅動自動跳轉，移除 page reload
  - [x] 提升檔案上傳限制至 10MB 並修正路徑疊加 Bug

## E2E 測試基礎設施修復與補齊 (Completed — 2026-04-11)
- [x] 修正 `playwright.config.ts` backend health check URL：8081 → 8080
- [x] 修正 `SmokeMockAuthFilter.java` NEWBIE UUID 對應錯誤（`...003` → `...004`）
- [x] 補齊 `SmokeDataSeeder.java`：
  - [x] 新增 Oliver pet（UUID `...0022`）
  - [x] 新增 Order（UUID `...0030`，含 `SURCHARGE_AMOUNT`/`DISCOUNT_AMOUNT` 欄位）
  - [x] 新增 Visit（UUID `...0040`，Status 改為 `SCHEDULED`）
  - [x] 新增 VisitService（UUID `...0050`，含 `SORT_ORDER`）
  - [x] 新增 James → Sophia 白名單（`SKIP_QUESTIONNAIRE=true`）
- [x] 修正 `Dashboard.jsx` `listSitterVisits()` 缺少 `date` 參數（→ 後端 500）
- [x] 修正 `AuthPage.js` NEWBIE UUID / email（對齊 seeder）
- [x] 修正 `AuthPage.js` `completeOnboarding()`：
  - [x] `waitForURL` 加 URL 判斷防止重複等待
  - [x] 角色按鈕改用 `dispatchEvent('click')`（Framer Motion DOM detach 問題）
  - [x] `injectSmokeAuth` 加入 SW 清除（防 ERR_ABORTED）
- [x] 修正 `notificationStore.js`：加入 `window.__SMOKE_NOTIFICATIONS__` hook 供 E2E 注入
- [x] 全面改寫 `notifications.spec.js`（改用 `addInitScript` 注入通知資料）
- [x] 全面改寫 `onboarding.spec.js`（LIFO 路由順序修正 + 冪等性保證）
- [x] 新增 `v25-soft-delete-and-pets.spec.js`（5 個 V25 新功能 E2E 測試）
  - [x] 軟刪除寵物後從列表消失
  - [x] PetFormModal 包含 birthDate 年份選擇器
  - [x] PetFormModal 包含 RABBIT 物種選項
  - [x] 已驗證 CLIENT 在 Profile 顯示「已驗證」綠色 badge
  - [x] 儲存新信箱後事件驅動彈窗（無需重整）
- [x] **最終測試結果：35/35 通過**（原 30 + 新增 5）

## 待核對清單 (Upcoming)
- [ ] 考慮將 GCS 公開 URL 欄位改為後端 Proxy / Pre-signed URL
- [ ] 完整 UAT 雙權限流程走測
