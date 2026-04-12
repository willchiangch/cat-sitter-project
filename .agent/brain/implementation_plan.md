# Documentation Synchronization Plan (V30)

此計畫旨在同步專案所有技術文件，確保資料庫 Schema、API 規格書、README 以及 AI 開發進度紀錄完全符合最新的程式碼變更。

## Proposed Changes

### 1. Database Schema Documentation
#### [MODIFY] [schema.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/doc/schema.md)
- **Profiles 表**：新增 `id_card_front_url`, `face_photo_url`, `bank_code`, `bank_account`, `is_verified` (BOOLEAN), `bio_summary` (TEXT)。
- **Pets 表**：新增 `neutered_status`, `vaccination_status`, `deworming_status` (均為 Enum), `gender` (Enum: MALE, FEMALE, UNKNOWN)。
- **Subscriptions**：確保 `plan_code` 欄位已記錄在 `subscription_plans` 中。

### 2. API Specification
#### [MODIFY] [openapi.yaml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi/openapi.yaml)
- **Pet Schema**：同步 `V26` 的健康狀態欄位與 `gender` 欄位。
- **SitterProfile Schema**：同步身分驗證欄位、銀行資訊及 `isVerified` 旗標。
- **UpdateSitterProfileRequest**：確保可更新欄位與實作一致。

### 3. Project Overview
#### [MODIFY] [README.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/README.md)
- 更新版本號至 **V30**。
- 新增「身分驗證標章與流程」功能說明。
- 新增「訂閱合約協議流程」功能說明。
- 更新 API 端點速查表，加入身分照片與銀行資訊的支援說明。

### 4. Agent Brain Sync
#### [MODIFY] [.agent/brain/](file:///Users/will_chiang/Widget_home/cat-sitter-project/.agent/brain/)
- 同步最新的 `implementation_plan.md`, `task.md`, `walkthrough.md` 至該目錄，以保證跨 Session 的開發連續性。

## Verification Plan
- 檢查 `schema.md` 中的欄位是否與 `V26`, `V16` 遷移腳本及 `Entity` 類別完全一致。
- 使用 `npm run api:generate` (若環境允許) 驗證 `openapi.yaml` 的語法正確性。
- 檢查 `README.md` 的版本號與功能清單是否準確描述了本次的 9 項異動。
