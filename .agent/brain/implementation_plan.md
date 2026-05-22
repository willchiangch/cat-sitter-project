# SD-021: 保母飼主記事本與媒體庫實作計畫

此計畫旨在為專案 `cat-sitter-project` 實作保母與飼主之間長期共用的「照護記事本與媒體庫」功能。此功能與特定訂單解耦，屬於保母與飼主雙方配對關係的長期儲存空間。

## User Review Required

> [!IMPORTANT]
> **架構技術決策：覆蓋式更新 (Recreate-on-Save)**
> 為了簡化前端與後端在拖拽排序、刪除條目、新增條目時的複雜狀態 Diff 與序號重算，本計畫在 `saveCareNote` 接口採用 **Recreate-on-Save** 模式：
> - 儲存時，前端直接發送完整且已排序的條目陣列。
> - 後端在同一個事務中，先實體/邏輯刪除該 `care_note_id` 下所有舊條目，再重新寫入新條目，自動按順序分配遞增的 `sort_order`。
> - 對於通常少於 100 條條目的照護記事而言，此操作的 DB 開銷極小，但能 100% 避免併發排序衝突或序號中斷的問題。

> [!WARNING]
> **檔案上傳與通知相依性**
> - 本功能之上傳媒體 API 將使用現有的 GCS 上傳機制（`file-upload`），上傳至 GCS 的 `media/care/` 路徑下。
> - 本功能的站內通知系統目前將採用 Mock 的 `NotificationService` 進行實作，以便後續無縫對接正式的通知中心模組。

## Proposed Changes

### 1. 資料庫變更 (Database Migration)

#### [NEW] [V20260522_01__create_care_notes_and_media.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V20260522_01__create_care_notes_and_media.sql)
- 建立 `care_notes`、`care_note_items`、`care_note_templates`、`care_note_template_items`、`care_media` 5 張資料表。
- 設定對應的外鍵約束與 UNIQUE 約束。
- 建立適當的索引（`sitter_id`, `owner_id`）以優化查詢效率。

### 2. 後端核心實作 (Backend Core)

#### [NEW] [CareNote.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/CareNote.java)
- 繼承 `BaseEntity`。包含 `sitterId` (UUID)、`ownerId` (UUID) 與 `careNoteItems` (One-to-Many 關聯，CascadeAll)。

#### [NEW] [CareNoteItem.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/CareNoteItem.java)
- 繼承 `BaseEntity`。包含 `sectionType` (String/Enum)、`title` (String)、`content` (String)、`sortOrder` (Integer)。

#### [NEW] [CareNoteTemplate.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/CareNoteTemplate.java)
- 繼承 `BaseEntity`。包含 `sitterId`、`name` 以及關聯的模板條目。

#### [NEW] [CareNoteTemplateItem.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/CareNoteTemplateItem.java)
- 繼承 `BaseEntity`。包含模板條目的明細內容。

#### [NEW] [CareMedia.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/CareMedia.java)
- 繼承 `BaseEntity`。包含 `sitterId`、`ownerId`、`caption`、`mediaUrl` 和 `mediaType`。

#### [NEW] [Repositories](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/repository/)
- 新增各 Entity 對應的 `JpaRepository` 介面（含根據 `sitterId` / `ownerId` 的查詢方法）。

#### [NEW] [CareNoteService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/CareNoteService.java)
- `getCareNote(sitterId, ownerId)`: 查詢並組裝記事本。
- `saveCareNote(sitterId, ownerId, dto)`: 覆蓋式儲存記事本，自動重置 `sort_order`，並呼叫通知。
- `createTemplate(sitterId, dto)`: 檢查模板數量上限是否達 3 個，未達則新增，否則拋出異常。
- `updateTemplate(sitterId, templateId, dto)`: 覆蓋現有模板內容。
- `applyTemplate(sitterId, ownerId, templateId)`: 將模板條目追加 (Append) 到當前記事本，接續原最大 `sort_order`。

#### [NEW] [CareMediaService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/CareMediaService.java)
- `uploadMedia(sitterId, ownerId, file, caption)`: 校驗現有媒體數是否達 20 筆上限，若未達則上傳至 GCS 並寫入 DB，觸發通知。
- `deleteMedia(sitterId, mediaId)`: 邏輯刪除媒體。

#### [NEW] [CareNoteController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/CareNoteController.java)
- 提供記事本查詢、儲存、模板管理及套用之 endpoints。

#### [NEW] [CareMediaController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/CareMediaController.java)
- 提供媒體庫上傳與刪除之 endpoints。

### 3. 前端界面與 UI/UX (Frontend React)

#### [NEW] [CareNotesAndMedia.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/sitter/CareNotesAndMedia.tsx) (保母端)
- **視覺風格**：採用 Stitch "The Intuitive Concierge" 風格，優雅淡雅，無框線設計。
- 提供 6 大項的卡片式摺疊展開。
- 支援條目的拖拽排序 (Dnd) 與行內快速編輯/刪除。
- 整合「儲存為範本」按鈕：模板達 3 筆上限時，以 Modal 提示要求保母選擇覆蓋既有範本。
- 提供媒體上傳區（限制 20 張，進度條/預覽），支援編輯說明字元。

#### [NEW] [CareNotesAndMediaView.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/client/CareNotesAndMediaView.tsx) (飼主端)
- 全唯讀檢視界面，供飼主隨時查閱保母留下的照護重點與環境說明媒體。

## Verification Plan

### Automated Tests
- **API 整合測試**：
  - 驗證 `POST /api/care-notes/templates` 上限為 3 個。
  - 驗證 `POST /api/care-media/{ownerId}` 上限為 20 筆。
  - 驗證套用模板時的追加排序邏輯。
- **Playwright E2E 測試**：
  - 撰寫 `e2e/care-notes-media.spec.ts`。
  - 模擬保母端編輯、拖拽排序、儲存。
  - 模擬保母儲存為範本（測試 3 個上限時的 Modal 覆蓋流程）。
  - 模擬飼主端登入，確認頁面唯讀，無編輯權限，且順利看到保母建立的內容。
