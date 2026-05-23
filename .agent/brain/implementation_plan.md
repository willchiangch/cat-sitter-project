# 前端設計實作計畫 (SD-021: 照護記事與媒體庫) - 第五版

> [!NOTE]
> **計畫狀態：等待審查 (UNDER REVIEW)**
> 本計畫已根據第五輪審查回饋修正：補齊了「另存為範本」確認按鈕的 `sitter-template-saveas-confirm-btn` testid，以及媒體刪除按鈕的 `sitter-carenote-media-delete-{mediaId}` testid，確保 E2E 測試百分百可達。

## User Review Required

請確認以下修訂後的核心規格：
1. **補齊 E2E testid**：
   - 另存為對話框確認按鈕：`sitter-template-saveas-confirm-btn`
   - 個別媒體刪除按鈕（hover 垃圾桶）：`sitter-carenote-media-delete-{mediaId}`

---

## Proposed Changes

### 1. API 串接與狀態管理 (Domain API & React Query)

#### [NEW] [careApi.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/api/careApi.ts)
封裝 Axios 請求呼叫後端：
- `getCareNote(sitterId, ownerId)`: `GET /api/care-notes/${sitterId}/${ownerId}`
- `saveCareNote(sitterId, ownerId, items, idempotencyKey)`: `PUT /api/care-notes/${sitterId}/${ownerId}`，手動帶入 `Idempotency-Key` Header。
- `getTemplates()`: `GET /api/care-notes/templates` (隱式依賴 JWT 中的 `Token.userId` 來過濾並返回該 Sitter 的範本)
- `createTemplate(dto, idempotencyKey)`: `POST /api/care-notes/templates`
- `updateTemplate(templateId, dto, idempotencyKey)`: `PUT /api/care-notes/templates/${templateId}`
- `deleteTemplate(templateId, idempotencyKey)`: `DELETE /api/care-notes/templates/${templateId}`
- `applyTemplate(sitterId, ownerId, templateId, idempotencyKey)`: `POST /api/care-notes/${sitterId}/${ownerId}/apply-template/${templateId}`
- `getMedia(sitterId, ownerId)`: `GET /api/care-media/${sitterId}/${ownerId}`
- `uploadMedia(sitterId, ownerId, file, caption, idempotencyKey)`: `POST /api/care-media/${sitterId}/${ownerId}` (使用 `multipart/form-data`)
- `deleteMedia(mediaId, idempotencyKey)`: `DELETE /api/care-media/${mediaId}`

#### [NEW] [useCareNote.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/hooks/useCareNote.ts)
- `useCareNoteQuery(sitterId, ownerId)`:
  - 捕捉 Axios `404 Not Found` 錯誤，若捕獲到 404，回傳預設的 6 大空白分區結構。
- `useSaveCareNoteMutation(sitterId, ownerId)`:
  - 成功後，調用 `queryClient.invalidateQueries({ queryKey: ['care-note', sitterId, ownerId] })`。
- `useTemplatesQuery()`: 查詢範本。
- `useCreateTemplateMutation()`, `useUpdateTemplateMutation()`, `useDeleteTemplateMutation()`: 範本異動，成功後自動 invalidate `['care-templates']`。
- `useApplyTemplateMutation(sitterId, ownerId)`:
  - 成功後，調用 `queryClient.invalidateQueries({ queryKey: ['care-note', sitterId, ownerId] })`。

#### [NEW] [useCareMedia.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/hooks/useCareMedia.ts)
- `useCareMediaQuery(sitterId, ownerId)`:
  - 捕捉 Axios `404 Not Found` 錯誤，並在捕獲 404 時回傳空陣列 `[]`。
- `useUploadMediaMutation(sitterId, ownerId)`: 成功後 invalidate `['care-media', sitterId, ownerId]`。
- `useDeleteMediaMutation(sitterId, ownerId)`: 成功後 invalidate `['care-media', sitterId, ownerId]`。

---

### 2. UI 元件與頁面開發 (Components & Pages)

#### [NEW] [BottomSheet.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/modals/BottomSheet.tsx)
- 使用 `React Portal` 渲染至 `document.body` 的滑出式抽屜元件。
- 採用玻璃磨砂背景 (`backdrop-filter: blur(12px)`) 與無框線底色陰影（`var(--shadow-ambient)`）。

#### [NEW] [TemplateManagerModal.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/modals/TemplateManagerModal.tsx)
- **範本建立與管理彈窗**：
  - 保母點選「管理模板」時開啟的滿版或置中 Modal（Portaled）。
  - 呈現保母現有的 `Templates`，並提供「新增/修改/刪除」功能。
  - 各操作分別綁定獨立的 idempotency key 以保證穩定性。

#### [NEW] [LightBox.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/ui/LightBox.tsx)
- **自建 LightBox 元件**，不引入任何第三方套件。
- 採用 `Portal` 渲染，使用 `position: fixed` 滿版黑色磨砂玻璃背景，點擊外部或關閉按鈕時卸載，支援展示大圖與 Caption。

#### [NEW] [CareNoteManager.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/CareNoteManager.tsx)
保母端照護管理頁面：
- **獨立 Ref 控制**：
  - `saveKeyRef = useRef<string | null>(null)`
  - `applyKeyRef = useRef<string | null>(null)`
  - `uploadKeyRef = useRef<string | null>(null)`
  - `saveAsTemplateKeyRef = useRef<string | null>(null)`
  - `deleteKeyRef = useRef<Record<string, string>>({})`
  - **回收機制**：在 `deleteMedia` 呼叫之 `onSuccess` 觸發時，明確呼叫 `delete deleteKeyRef.current[mediaId]`。
- **條目管理與排序**：
  - 展示 6 大分區。提供「新增項目」Input、「修改」Input、「刪除」與「上移/下移」排序按鈕（直接修改本地 state 陣列順序）。
- **模板套用/另存**：
  - 提供按鈕喚起 `BottomSheet` 進行範本套用。
  - 提供「另存為範本」按鈕：於 CareNoteManager 本地彈出名稱輸入框，以當前 state 陣列與輸入名稱呼叫 `createTemplate`（使用 `saveAsTemplateKeyRef` 控制，並掛載確認按鈕 testid）。
  - 提供「管理範本」按鈕開啟 `TemplateManagerModal` 進行範本 CRUD 操作。
- **上傳限制與防禦**：
  - 使用 `<input type="file" accept="image/*" />`。
  - 前端驗證檔案大小上限為 **10MB**，且數量上限為 **20 筆**。
  - 上傳期間 Submit 按鈕變更為 `disabled` 且顯示 "上傳中..."。

#### [NEW] [CareNoteView.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/client/CareNoteView.tsx)
飼主端唯讀瀏覽頁面：
- 唯讀呈現 6 大分區的照護卡片，提供 Tab 切換記事與媒體照片牆，點擊照片時喚起自建的 `LightBox` 元件。

#### [MODIFY] [App.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/App.tsx)
- **導航機制更新**：
  - 將 view state 改成物件。

---

### 3. E2E 測試與 data-testid 規格

所有關鍵互動元件均掛載 `data-testid`：
- `sitter-carenote-save-btn`: 儲存記事按鈕
- `sitter-carenote-add-item-{section}`: 新增條目按鈕
- `sitter-carenote-item-title`: 編輯中的標題 input
- `sitter-carenote-item-content`: 編輯中的內容 textarea
- `sitter-carenote-template-open`: 開啟套用模板 BottomSheet 按鈕
- `sitter-carenote-template-saveas`: 「另存為範本」按鈕
- `sitter-carenote-template-manage`: 開啟範本管理 Modal 按鈕
- `sitter-template-saveas-name-input`: 「另存為對話框」範本名稱 input
- `sitter-template-saveas-confirm-btn`: 「另存為對話框」確認另存按鈕
- `sitter-template-manager-name-input`: 「範本管理 Modal」範本名稱 input
- `sitter-template-save-btn`: 建立/更新範本儲存按鈕
- `sitter-template-edit-{templateId}`: 範本項目編輯按鈕
- `sitter-carenote-template-item-{templateId}`: 範本列表中的套用項目
- `sitter-carenote-template-delete-{templateId}`: 範本刪除按鈕
- `sitter-carenote-media-file`: 媒體檔案選擇 input
- `sitter-carenote-media-caption`: 媒體說明 input
- `sitter-carenote-media-submit`: 上傳按鈕
- `sitter-carenote-media-delete-{mediaId}`: 個別媒體刪除按鈕
- `client-carenote-section-{section}`: 飼主端看到的區塊

---

## Verification Plan

### Manual Verification
1. 啟動後端：進入 `backend/` 執行 `mvn spring-boot:run`。
2. 啟動前端：進入 `frontend/` 執行 `npm run dev`。
3. 進入開發環境，切換保母與飼主視角，驗證以下場景：
   - 首次存取時，確認 `useCareNoteQuery` 與 `useCareMediaQuery` 均能正確捕捉 404，不發生 React Query 崩潰，並渲染 6 大空白卡片與空照片牆。
   - 新增、修改、刪除、上移/下移，點擊儲存，確認呼叫成功（`saveKeyRef` 於 `onSuccess` 後有正常 reset，重複發送 409 時有正確攔截提示）。
   - **另存新範本驗證**：點選「另存為範本」輸入名稱，確認發送成功，並驗證連點時 `saveAsTemplateKeyRef` 有效阻攔。
   - **範本套用驗證**：開啟 `BottomSheet` 選擇範本並套用，驗證記事內容在追加後自動重新載入（Invalidate 正常）。
   - **範本管理驗證**：開啟 `TemplateManagerModal`，測試範本建立與修改；點擊範本刪除，確認範本列表重拉且 deleteKeyRef 記憶體無洩漏。
   - 選擇 10MB 以上之檔案或非圖片檔案，確認前端有主動拋出阻攔提示；確認上傳時按鈕進入 disabled 狀態且 accept 限於 `image/*`。
   - 切換至飼主端，點擊照片確認自建的固定定位 `LightBox` (Portal) 能完美渲染大圖且不引發 bundle 負擔。
   - 確保所有卡片都完美遵守 Stitch No-Line 原則，除合規的 ghost border 輔助線外無其他 border 框線。
