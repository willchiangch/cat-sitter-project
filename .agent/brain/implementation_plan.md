# SD-022 行程照護日誌與多媒體回報系統 Review 修復計畫

本計畫針對 Review 回饋，落實對前端 React Query 背景重新 fetch、影片長度驗證 race condition、樂觀鎖版權傳遞、DOM 命令式操作、時區與 BottomSheet 動畫等 Issues 的安全防護與修正。

## User Review Required

請確認以下調整細節：
- **QueryClient 安全配置**：將前端 `main.tsx` 的 `QueryClient` 加上全域 `defaultOptions` 防禦，預設 `staleTime` 為 5 分鐘，避免任何編輯畫面在背景 Refetch 時覆蓋用戶輸入。

---

## Proposed Changes

### 前端優化與防禦

#### [MODIFY] [main.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/main.tsx)
- 在 `new QueryClient()` 注入 defaultOptions，全域配置 `staleTime: 5 * 60 * 1000` (5 分鐘)。

#### [MODIFY] [useCareMedia.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/hooks/useCareMedia.ts)
- `useCareMediaQuery` 補上 `staleTime: 5 * 60 * 1000` 以維護媒體讀取的一致性與快取安全性。

---

## Verification Plan

### 自動化與手動驗證
1. **後端整合測試**：執行 `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn clean test` 確保所有測試正常運作。
2. **編譯驗證**：在 `frontend` 目錄執行 `npm run build` 確保 TypeScript 編譯無誤。
3. **UAT 手動驗證**：
   - 保母端文字編輯時，切換 Tab 重新進入，確認草稿不會被背景 refetch 覆蓋清空。
   - 上傳 10 秒短片，確認前端立即阻攔並顯示「影片長度必須介於 15 至 30 秒之間」，且不殘留錯誤的 `mediaFile` 狀態。
