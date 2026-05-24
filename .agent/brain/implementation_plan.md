# SD-003: 保母服務方案設定設計文件修正計畫

本計畫旨在針對審查所發現的 5 個具體問題（3 個 FAIL 與 2 個 WARNING）進行 [SD-003-service-plans.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-003-service-plans.md) 的文件更新與設計對齊。

## User Review Required

> [!IMPORTANT]
> 1. **Migration 衝突修正**：
>    - 原 Flyway 命名 `V3__...` 與已有的 `V3__add_snapshot_details.sql` 衝突。將修正命名為 **`V20260524_01__add_service_plan_columns.sql`**。
> 2. **SaaS Gating 設計決策說明 (Conscious Exception)**：
>    - 因 AOP `@RequirePlan` 只能在 API 方法層進行卡控，無法精確判斷 Body 內是否傳入 `startDate` / `endDate` 欄位（特定屬性欄位級的門禁）。
>    - 將在 SD 文件中新增 **「技術設計決策 (Design Decisions)」** 區塊，顯式說明此例外：「允許在 Service 層執行方案等級 (PlanTier) 的檢核，此為意識到的設計特例，非無意疏漏。」
> 3. **異常 Handler 與動態 HttpStatus**：
>    - 在 SD 文件的類別變更中，明定實作時需同步修改 `GlobalExceptionHandler.java`：
>      - 補上對 `ServicePlanException.class` 的攔截處理。**不寫死 422**，而是改為動態讀取 `ResponseEntity.status(ex.getStatus())`，以相容 `PLAN_NOT_FOUND` (404) 與 `PLAN_NOT_IN_RANGE` (422) 的情形。
>      - **樂觀鎖防衛**：補上 `@ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)`，回傳 409 Conflict 且 error 欄位為 `VERSION_CONFLICT`，修補 codebase 歷史遺留漏洞，使 JPA 樂觀鎖衝突不致噴出 500。
> 4. **API Response 價格與 Version 型別修正**：
>    - 修正 API Response 設計範例：
>      - 將 `price` 欄位的值由 `500.0000` 改為整數 `500`，確保與 Java Long 欄位及技術憲法（INT/BIGINT 儲存）一致。
>      - **Version 初始值**：POST 新建方案後，JPA 初始化的 `@Version` 應為 **`0`** (原本寫 1)；而第一次 PUT 修改成功後版本推進至 **`1`** (原本寫 2)，完全對齊 Hibernate `@Version` 行為。
> 5. **錯誤碼風格與 Codebase 對齊**：
>    - 捨棄 codebase 內無對應定義的 `MSG_DATA_` 虛擬碼，統一改用與當前 `GlobalExceptionHandler` 一致的平坦大寫字串，特別與現有 `AUTH_PLAN_LIMIT` 對齊：
>      - `MSG_DATA_F11` → **`PLAN_NOT_FOUND`**
>      - `MSG_DATA_PLAN_LIMIT` → **`AUTH_PLAN_LIMIT`** (對齊 L57 現存定義)
>      - `MSG_DATA_VERSION_CONFLICT` → **`VERSION_CONFLICT`**
>      - `MSG_PLAN_NOT_IN_RANGE` → **`PLAN_NOT_IN_RANGE`**

## Proposed Changes

### Documentation

#### [MODIFY] [SD-003-service-plans.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sd/SD-003-service-plans.md)
進行以下修正：
- **Flyway 腳本命名** 變更為 `V20260524_01__add_service_plan_columns.sql`。
- **序列圖** 的 SaaS Gating 流程加註 `(技術設計決策: 進階屬性級門禁)`。
- 新增 **「技術設計決策 (Design Decisions)」** 區塊，說明 Service 層進行 SaaS Gating 的考量。
- 修改 **「資料模型變更」**，補上 `ServicePlanException` (動態 HttpStatus) 與 `ObjectOptimisticLockingFailureException` 於 `GlobalExceptionHandler.java` 內的新增攔截處理說明。
- 修改 **API 設計範例中的 `price`** 為 `500`（整數）。
- 修改 **API 設計範例中的 `version`**，POST 成功回傳 `0`，PUT 成功回傳 `1`。
- 修改 **異常代碼對應表**，全部轉換為與當前系統一致的大寫平坦字串，並確認採用 `AUTH_PLAN_LIMIT`。

---

## Verification Plan

本階段為**設計文件更新與審查階段**：
- 檢查變更後的設計文件是否完美符合 `sd-skill` 的合規性要求。
- 檢查設計文件是否完全沒有語法與格式問題，且對齊 codebase 的真實狀態。
