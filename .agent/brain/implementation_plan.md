# API 契約同步與文件更新計畫 (Trust Circle & Whitelist)

此計畫旨在同步後端實作與 `openapi.json` 文件，確保前端生成的客戶端與後端 API 完全對齊，並移除過時的 stub 標記。

## 使用者檢查建議

> [!IMPORTANT]
> 此變更會更新 `openapi.json`，這可能會觸發前端 API 客戶端（如果有的話）的自動生成重新運行。請確認開發流程是否依賴此檔案。

## 擬議變更

### API 文件更新

#### [MODIFY] [openapi.json](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi.json)

- **基礎穩定性 (已完成)**:
    - [x] 修正 `Profile`, `SitterTrustCircle`, `SitterClientWhitelist` 的 Hibernate Proxy 序列化問題。
    - [x] 修復 `MediaController` 的多媒體資源讀取 NPE。
- **Trust Circle (信任圈)**:
    - [x] 在 `com.catsitter.api.dto.sitter` 中新增 `AddTrustCircleRequest` DTO。
    - [x] 更新 `SitterTrustCircleController.java` 以使用 `AddTrustCircleRequest`。
    - [NEW] 新增 `GET /api/v1/sitters/me/trust-circle` 節點。
    - [UPDATE] 修正 `POST /api/v1/sitters/me/trust-circle` 的請求主體 (Request Body) 為 `AddTrustCircleRequest`。
    - [UPDATE] 修正 `DELETE /api/v1/sitters/me/trust-circle/{partnerId}`，並移除不支援的 `PATCH` 方法。
    - [NEW] 在 `components/schemas` 中新增 `SitterTrustCircle` 與 `AddTrustCircleRequest` 的定義。
- **Whitelist (白名單)**:
    - [UPDATE] 確保標籤從 `api-v-1-stub-controller` 更改為正確的 `whitelist-controller`。
    - [UPDATE] 驗證 `SitterClientWhitelist` Schema 的屬性完整性。

## 開放性問題

> [!NOTE]
> 目前 `SitterTrustCircleController` 的 `POST` 接收 `Map<String, String>`，這在 OpenAPI 中通常定義為一個具體的 Request DTO。我是否應該在後端也定義一個明確的 `AddTrustCircleRequest` DTO 以提升型別安全性？

## 驗證計畫

### 手動驗證
- 使用Swagger UI（如果有的話）或 Postman 匯入新的 `openapi.json` 驗證端點定義。
- 觀察前端是否能正確識別新的 API 定義。

### 自動化測試
- 執行 `sitter-business.spec.js` 確保現有工作流不受影響。
