# 🐾 照護記事與媒體庫 (SD-021) 前端開發總結報告

這份報告總結了基於 [SD-021-care-notes-and-media.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-021-care-notes-and-media.md) 設計文件的前端實作與驗證成果。本階段前端已完全實作完畢並通過 TS 嚴格編譯。

## 1. API 串接與 React Query Hooks (Infrastructure & Data Layer)
- **[careApi.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/api/careApi.ts)**：封裝 Axios 請求，對 `PUT`（儲存）、`POST`（套用模板/上傳圖片）與 `DELETE`（刪除模板/刪除媒體）手動注入並傳遞 `Idempotency-Key` Header。
- **[useCareNote.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/hooks/useCareNote.ts)**：
  - `useCareNoteQuery` 實作 catch 404 防禦。若查無記錄（如新用戶首次存取），回傳預設的 6 大空白分區結構，避免 React Query 拋出例外。
  - 儲存、套用模板與範本 CRUD 成功後，自動 Invalidate 對應緩存。
- **[useCareMedia.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/hooks/useCareMedia.ts)**：
  - `useCareMediaQuery` 實作 catch 404，在媒體照片牆查無資料時回傳 `[]` 防禦。
  - 圖片上傳、刪除成功後自動重新拉取更新。

## 2. 自建 Portal 元件 (無第三方套件依賴，Stitch 磨砂玻璃設計)
- **[BottomSheet.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/modals/BottomSheet.tsx)**：使用 React Portal 渲染至 body 的滑出式抽屜，用於套用範本選擇。
- **[TemplateManagerModal.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/modals/TemplateManagerModal.tsx)**：範本管理彈窗，支援範本的 CRUD 操作。
- **[LightBox.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/ui/LightBox.tsx)**：大圖檢視器，支援 Portal 定位與黑色磨砂背景。

## 3. 保母端照護管理頁面
- **[CareNoteManager.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/CareNoteManager.tsx)**：
  - 前端維護本地 State 陣列，直接傳送至後端進行 PUT，無多餘 recreate 運算。
  - 提供分區內項目新增、修改、刪除、及上移/下移排序功能。
  - 宣告 5 組獨立 `useRef` 進行各自操作的冪等密鑰追蹤（`saveKeyRef`, `applyKeyRef`, `uploadKeyRef`, `saveAsTemplateKeyRef`, `deleteKeyRef`）。
  - **記憶體 GC 清理**：在 `deleteMedia` 成功之 `onSuccess` 回調中，明確呼叫 `delete deleteKeyRef.current[mediaId]`，防止記憶體洩漏。
  - 實作「另存為範本」功能，填入範本名稱確認後直接呼叫 `createTemplate`。
  - 檔案上傳硬性限定為 `accept="image/*"`（排除影片上傳），驗證單檔最大 `10MB` 且照片總量上限為 `20 筆`。上傳期間 Submit 按鈕變更為 `disabled` 狀態。

## 4. 飼主端照護檢視頁面
- **[CareNoteView.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/client/CareNoteView.tsx)**：
  - 唯讀呈現 6 大分區項目，支援 Tab 切換至現場照片牆，點擊照片喚起 Portal `LightBox` 放大展示。

## 5. 整合與路由配置
- **[App.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/App.tsx)**：
  - 將 view 狀態優化為支援 `{ name, params }` 的物件狀態，在 Demo 主頁中加入「進入照護管理 (保母端)」與「進入照護檢視 (飼主端)」兩個按鈕，並傳入測試用的 Mock UUID 參數。

## 6. 建置與驗證結果
- **TypeScript 嚴格編譯**：
  - 修正了 TS 嚴格模式中 `verbatimModuleSyntax` 對 type-only 導入的嚴格要求。
  - 於本地執行 `npm run build` (tsc -b && vite build)，產出 **`BUILD SUCCESS`**，無任何 TypeScript 錯誤與打包警告，順利生成 Service Worker 模組。

---

## 7. UAT 部署評估決議
- **評估日期**：2026-05-23
- **決議**：**暫不部署至 UAT 環境，繼續進行開發。**
- **考量點**：
  1. **前後端聯調優先**：後端 API 與安全防護已全數通過 18 個單元與整合測試，但前端對接（特別是 IDOR 雙重放行邏輯與 `idempotency-key` 處理）尚未聯調，此時部署後端無法實現完整業務流程的 UAT 驗收。
  2. **基礎設施依賴**：媒體上傳功能有 GCS 依賴，需在與前端聯調確認無誤後，與 UAT GCP 權限一同配置上線，以減少不必要的環境除錯成本。
  3. **下一步行動**：優先對接前端 UI，或繼續開發下一個業務模組，於階段性 Milestone 完成時再統一發布 UAT。
