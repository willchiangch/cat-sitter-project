# 🐾 前端實作任務清單 (SD-021: 照護記事與媒體庫) - 第五版

- [x] **1. API 串接與 React Query Hooks (Infrastructure & Data Layer)**
  - [x] 實作 `frontend/src/api/careApi.ts`（支持手動傳入 `idempotencyKey` 的 HTTP 呼叫）
  - [x] 實作 `frontend/src/hooks/useCareNote.ts`
    - [x] `useCareNoteQuery` 實作 Catch 404 防禦分支並返回 6 大空白分區結構
    - [x] `useSaveCareNoteMutation` 實作儲存成功 Invalidate 緩存
    - [x] `useApplyTemplateMutation` 實作套用成功 Invalidate 緩存
    - [x] 實作範本 CRUD mutations
  - [x] 實作 `frontend/src/hooks/useCareMedia.ts`
    - [x] `useCareMediaQuery` 實作 Catch 404 防禦分支並返回空陣列 `[]`
    - [x] 管理媒體查詢、上傳與刪除

- [x] **2. 共用 UI 元件開發 (Stitch UI Layer)**
  - [x] 實作 `frontend/src/components/modals/BottomSheet.tsx`（基於 Portal 的玻璃磨砂抽屜，用於套用模板）
  - [x] 實作 `frontend/src/components/modals/TemplateManagerModal.tsx`（基於 Portal 的範本管理 Modal，包含建立、編輯與刪除，掛載對應 `sitter-template-manager-*` testids）
  - [x] 實作 `frontend/src/components/ui/LightBox.tsx`（基於 Portal 的自建固定定位放大元件）

- [x] **3. 保母端照護管理頁面實作 (Sitter CareNoteManager)**
  - [x] 建立 `frontend/src/pages/sitter/CareNoteManager.tsx`
  - [x] 實作 6 大分區項目之條目列表 (SERVICE, CONTACT, WARNING, PREFERENCE, HOSPITAL, OTHER)
  - [x] 實作條目之「新增」、「編輯」、「刪除」與「上移/下移排序」
  - [x] 實作**獨立的多組 `useRef`** 控制 `Idempotency-Key`（`saveKeyRef`, `applyKeyRef`, `uploadKeyRef`, `saveAsTemplateKeyRef`, `deleteKeyRef`）
  - [x] 實作 **`deleteKeyRef` 記憶體回收機制**（`onSuccess` 觸發 `delete deleteKeyRef.current[mediaId]`）
  - [x] 實作「另存為範本」功能（點擊帶入當前 items 並跳出輸入範本名稱的 Modal 對話框，掛載 `sitter-template-saveas-name-input` 與 `sitter-template-saveas-confirm-btn`）
  - [x] 實作「套用範本」面板與邏輯
  - [x] 實作媒體上傳區驗證（MIME type 限 `accept="image/*"`, 單檔上限 10MB, 數量 20 筆限制, 上傳中 button disabled 與 loading 狀態，刪除按鈕掛載 `sitter-carenote-media-delete-{mediaId}`）

- [x] **4. 飼主端照護檢視頁面實作 (Client CareNoteView)**
  - [x] 建立 `frontend/src/pages/client/CareNoteView.tsx`
  - [x] 實作 6 大分區之唯讀卡片排版展示
  - [x] 實作唯讀媒體庫展示與 Lightbox 放大照片牆效果

- [x] **5. 整合與路由配置 (App Integration)**
  - [x] 修改 `frontend/src/App.tsx` 的 view 導航，改用帶有 params 的物件狀態
  - [x] 設定 Mock 的 `sitterId` 與 `ownerId` 以與後端對接
  - [x] 測試雙主題 (Amber / Blue) 的視覺效果

- [x] **6. 驗證與微調 (Verification & Tuning)**
  - [x] 執行手動聯調測試，確保 API 呼叫無誤，時區與 `Idempotency-Key` 正確發送
  - [x] 獨立進行「另存範本連點防護」、「範本套用驗證（BottomSheet）」與「範本管理驗證（TemplateManagerModal）」
  - [x] 稽核 UI 以確保符合 Stitch "The Intuitive Concierge" 設計規範（No-Line 僅保留 ghost border）
  - [x] 掛載對應 `data-testid`（包括 `sitter-carenote-template-saveas`、`sitter-template-saveas-name-input`、`sitter-template-saveas-confirm-btn`、`sitter-template-manager-name-input`、`sitter-template-save-btn`、`sitter-template-edit-{templateId}`、`sitter-carenote-media-delete-{mediaId}` 等）
