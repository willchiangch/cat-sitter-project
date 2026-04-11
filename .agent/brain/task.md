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

## 待核對清單 (Upcoming)
- [ ] 考慮將 GCS 公開 URL 欄位改為後端 Proxy / Pre-signed URL
- [ ] 完整 UAT 雙權限流程走測
