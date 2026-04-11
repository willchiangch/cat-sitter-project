# 全系統軟刪除與數據完整性強化 (V25)

本計畫旨在透過邏輯刪除 (Soft Delete) 機制取代物理刪除，以保護訂單歷史完整性，並同步提升開發者調試效率。

## 核心設計目標

> [!IMPORTANT]
> - **保證歷史完整性**：訂單 (Order) 與行程 (Visit) 的外鍵關聯不再因為寵物或帳號的刪除而失效。
> - **資源回收與重用**：Email 與 Slug 在邏輯刪除後應被視為「已釋出」，允許新用戶再次使用。
> - **開發調試透明化**：將驗證碼等核心 Debug 資訊直接透傳至前端 Console。

## 擬定的變更 (Executed Changes)

### 1. 資料庫遷移與索引優化

#### [NEW] [V25__add_soft_delete_support.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V25__add_soft_delete_support.sql)
- 所有核心資料表新增 `deleted_at` 欄位。
- 移除 `accounts(email)` 與 `profiles(slug)` 的物理 UNIQUE 約束。
- 新增部分唯一索引：`WHERE deleted_at IS NULL`。

### 2. 後端實體攔截 (Hibernate Integration)

#### [MODIFY] [AuditableEntity.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/common/AuditableEntity.java)
- 新增 `LocalDateTime deletedAt` 屬性。

#### [MODIFY] 核心實體 (Account, Profile, Pet, Service, Order, Visit)
- 標註 `@SQLDelete(sql = "UPDATE ... SET deleted_at = NOW() WHERE id = ?")`。
- 標註 `@SQLRestriction("deleted_at IS NULL")`。

### 3. 開發者體驗優化 (DX Upgrades)

#### [MODIFY] [CustomResponseInterceptor.js](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/services/api.js) (假設攔截位置)
- 在 `smoke` 環境下，自動攔截回應中的 `debugCode`。
- 使用螢光樣式渲染至 Console：`console.log('%c 🔑 [DEBUG] ...', 'background: #00ff00; color: #000; ...')`。

## 驗證計畫 (Verification Plan)

### 自動化測試
- 呼叫 `DELETE /api/v1/clients/me/pets/{id}`。
- 檢查資料庫：`deleted_at` 應有值且資料未消失。
- 檢查清單介面：寵物應自動消失。

### 開發流驗證
- 在 Console 觀察驗證碼輸出，確保無需切換視窗即可完成註冊/驗證流。
