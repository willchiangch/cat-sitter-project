# 🐾 照護記事、日誌與多媒體回報 (SD-021 & SD-022) 開發總結報告

這份報告總結了基於 [SD-021](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-021-care-notes-and-media.md) 與 [SD-022](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-022-care-log.md) 設計文件的實作與驗證成果。目前後端與前端皆已百分之百實作完畢，且後端整合測試與前端生產打包均全數成功通過。

## 0. SD-022 行程照護日誌與回報 (後端與前端實作成果)
- **資料庫遷移與快照補齊 (Flyway)**：
  - 建立 [V20260523_03__create_visit_service_reports.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V20260523_03__create_visit_service_reports.sql)，新增日誌主表與多媒體表，並擴充 `order_snapshots` 補齊 `max_videos` 與 `plan_tier` 欄位以支援 SaaS 配額與路徑計算。
- **Entity 與 DTO 設計**：
  - 新增 [VisitServiceReport.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/VisitServiceReport.java) 及 [ServiceReportMedia.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/ServiceReportMedia.java)，透過 `BaseEntity` 引入自動審計與樂觀鎖。
  - 新增 [VisitServiceReportDto.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/dto/VisitServiceReportDto.java) 及 [ReportMediaDto.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/dto/ReportMediaDto.java)。
- **業務邏輯層 (VisitReportService)**：
  - 實作暫存文字草稿、媒體上傳（包含方案快照限制卡控、照片 ≤1MB、影片 ≤50MB/15-30s 等前端與後端防禦）、媒體邏輯刪除、送出日誌（CAS 更新 status 狀態防禦與非同步通知飼主）、角色安全分流（飼主隔離未送出草稿，回傳 404 MSG_DATA_F11）。
  - 修復 **JPA save() 判定與 id 分配漏洞**：不手動分配 `ServiceReportMedia` 的實體 id，交由 JPA 在 `save` 時以 new 實體狀態觸發 `INSERT`，確保自動審計的 `createdAt` 能被順利填入。
- **控制器與安全遮罩 (VisitReportController & VisitReportException)**：
  - 暴露端點並配合 `@RequirePlan(PlanTier.FREE)`。新增 `VisitReportException` 處理器以實現精確的代碼與狀態碼回傳。
- **後端 API 整合測試**：
  - 建立 [VisitReportControllerTest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/VisitReportControllerTest.java)，測試覆蓋 7 個核心業務與安全防禦情境，測試全數 `BUILD SUCCESS` 通過！
- **前端 API 串接與頁面**：
  - 建立 [visitReportApi.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/api/visitReportApi.ts) 與 React Query Hooks [useVisitReport.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/hooks/useVisitReport.ts)（安全捕獲 404）。
  - 實作 [VisitReportManager.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/VisitReportManager.tsx) 與 [VisitReportView.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/client/VisitReportView.tsx)。前端驗收指標（文字 1000 字元限制與統計、檔案上傳 input disabled 與格式檢核、點擊媒體 LightBox 放大、草稿逾期灰底唯讀等）全部實現。
  - 前端通過 `npm run build` TypeScript 嚴格編譯！

---


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

---

## 8. SD-022 設計修正 (L-1 & L-2 STILL OPEN 議題)
- **L-1: 文字與圖不一致修正**：
  - 更新 [SD-022-care-log.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-022-care-log.md) 第 18 行描述，移除「兩個 API操作」字眼，指出暫存文字規格詳見 §4.1。
  - 在 §4.1 補齊「權限與業務校驗」規範，明訂：權限攔截、行程狀態檢核、逾期檢核 (Lazy Evaluation)、狀態校驗 (DRAFT-only)、版本防禦 (Optimistic Lock) 與冪等性防護。
- **L-2: 400 error mapping 圖像驗證修正**：
  - 調整序列圖 Step 3.5 為 `mediaType` 分支判斷（IMAGE 限制 JPG/PNG/WebP 且 ≤1MB；VIDEO 限制 MP4/MOV、15~30s 且 ≤50MB）。
  - 更新 §4.6 錯誤訊息映射中 `MSG_DATA_INVALID_MEDIA` 欄位，明確納入圖片與影片的規格限制。

---

## 9. Review 修正與 Bug 修復成果
- **H-1 (React Query 背景重新 fetch 覆蓋用戶編輯)**：
  - 在 `main.tsx` 中將 `QueryClient` 配置 `defaultOptions.queries`，全域預設 `staleTime: 5 * 60 * 1000` (5 分鐘) 並關閉 `refetchOnWindowFocus`。這防止了用戶切換視窗時 React Query 自動發送 refetch，導致 useEffect 重新觸發並覆蓋未儲存草稿的問題。
  - 此外，亦於 `useCareMediaQuery` 加上 `staleTime: 5 * 60 * 1000`。
- **M-1 (影片異步驗證 Race Condition)**：
  - 確保影片長度檢核的 `setMediaFile(file)` 被安全限制於 `onloadedmetadata` 的合法異步成功回調中，且在檢驗失敗時提供 `setUploadError`，不殘留未驗證的 media 狀態。
- **M-2 (媒體樂觀鎖傳遞錯誤)**：
  - 後端 `ReportMediaDto` 補齊 `version` 欄位，前端 `ReportMedia` 介面補齊 `version: number`，且點擊邏輯刪除時傳入 `item.version` 進行併發鎖定。
- **M-3 (DOM 命令式操作)**：
  - 移除使用 `document.getElementById` 命令式清除 input value，改以 `useRef` 進行 React 聲明式控制。
- **L-1 (時區格式化)**：
  - 時區指定為 `Asia/Taipei` 渲染，確保日誌提交時間在台灣時區準確呈現。
- **L-2 (BottomSheet SSR Guard)**：
  - 移除多餘的 mounted SSR guard，直接以 `!isOpen` return null 渲染。
- **L-3 (動畫 `@keyframes` 動態注入)**：
  - 動畫全部抽離至全域 `global.css` 控制。
- **修正後端測試併發與外鍵錯誤**：
  - **外鍵衝突修正**：修正 `V20260523_02__add_seed_users.sql` 引入 `subscriptions` 種子數據後，導致測試 `setUp` 執行 `userRepository.deleteAll()` 時的外鍵約束衝突。在 `BookingServiceTest`, `CompletionServiceTest`, `ModificationServiceIntegrationTest`, `AuthControllerTest` 的 `setUp` 注入 `SubscriptionRepository` 並在第一步優先調用 `deleteAll()` 清空。
  - **新快照欄位非空約束修正**：修補因 `order_snapshots` 新增 `max_videos` 與 `plan_tier` 導致 `OrderEvaluationTest` 在 builder 建立時丟出 non-null 違反。在 `OrderSnapshot` 實體上追加 Lombok `@Builder.Default` 預設為 `0` 及 `"FREE"`。
  - **變更併發防禦測試修正**：修復了 `ModificationServiceIntegrationTest` 併發測試，將併發防護中可能丟出的 `IllegalStateException` 和 `ObjectOptimisticLockingFailureException` (樂觀鎖) 一併計入 failureCount。
- **M-1' (影片 onerror 驗證遺漏)**：
  - 在 `VisitReportManager.tsx` 影片長度檢驗邏輯中新增 `video.onerror`，防止受損影片無法觸發 `onloadedmetadata` 而導致 URL 洩漏與無法報錯的異常狀態。
- **L-2' (驗證失敗後 file input 未重置)**：
  - 於檔案驗證失敗之所有分支，加入 `fileInputRef.current.value = ''` 重置處理，確保使用者下次點擊相同的損壞/錯誤檔案時，依舊能順利觸發 `onChange` 事件進行後續操作。
- **L-3' (useTemplatesQuery 缺 staleTime)**：
  - 在 `useCareNote.ts` 中對 `useTemplatesQuery` 加上 `staleTime: 5 * 60 * 1000` (5分鐘) 防禦，降低非必要的重新 fetch。
