# Walkthrough - Core Security & Auth Implementation

後端安全與認證機制已完成實作並通過 TDD 驗證。

## 變更內容

### 基礎配置更新
- **Spring Boot 降級**：將 `pom.xml` 與 `README.md` 中的 Spring Boot 版本改回穩定版 `3.4.3`。
- **Auditing 修復**：將 `AuditableEntity` 的日期類型從 `OffsetDateTime` 改為 `Instant`，解決 JPA Auditing 的核心類型轉換錯誤。

### 安全框架實作
- **SecurityConfig**：設定為無狀態 (Stateless) 模式，禁用 CSRF。
- **JWT 整合**：實作 `JwtService` 與 `JwtAuthenticationFilter` 處理 Token 產生與請求攔截。
- **錯誤處理**：加入 `AuthenticationEntryPoint` 確保未授權存取時會正確回傳 `401 Unauthorized` 狀態碼。

### 規格文件同步
- **`README.md`**：更新技術棧表格與安全架構概覽。
- **`DEVELOPMENT_GUIDELINES.md`**：新增「安全與認證」開發規範，明確 JWT 處理與 TDD 要求。
- **`openapi/openapi.yaml`**：同步認證 Schema 並加入 `bearerAuth` 描述，確保前後端規格一致。

## 驗證結果

### 自動化測試
執行 `AuthControllerTest` 整合測試，驗證註冊、登入及未授權攔截流程：
- `shouldRegisterNewUser`: **PASS**
- `shouldLoginExistingUser`: **PASS**
- `shouldReturn401WhenAccessingMeWithoutToken`: **PASS**

```bash
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 身份識別與模式切換 (Identity & Identity Management)

實作了 `/api/v1/auth/me` 接口，回傳帳戶關聯的所有 Profile，支援前端角色切換（飼主/保母模式）。

### 關鍵功能
- **Multi-Profile Response**: 回傳包含 `profiles` 列表的 DTO，內含 `profileId`, `role`, `name` 等。
- **權限基礎**：後端根據 Token 中的 AccountID 自動識別身份，不需要額外手動切換狀態。

---

## 訂單報價與支付管理 (Booking Quote & Payment)

實作了保母報價與支付證明處理流程。保母可以在 PENDING 狀態下提交報價，飼主上傳支付證明後，保母可以確認線下收款。

### 關鍵功能
- `POST /api/v1/bookings/{id}/quote`: 保母提交報價，變更狀態為 `QUOTED`。
- `POST /api/v1/bookings/{id}/payment-proofs`: 飼主上傳支付證明 metadata。
- `POST /api/v1/bookings/{id}/payments/confirm-offline`: 保母確認收款，變更狀態為 `PAID` 並將訂單設為 `CONFIRMED`。

---

## 行程與任務清單管理 (Visit & Checklist Management)

實作了保母執行到府服務時的核心邏輯，包含查看行程、更新 SOP 任務清單及完成服務。

### 關鍵功能
- `GET /api/v1/sitters/me/visits`: 保母按日期查看自己的服務行程。
- `GET /api/v1/visits/{id}`: 查看行程詳情與任務清單現況。
- `PATCH /api/v1/visits/{id}/checklist`: 更新任務完成狀態。
- `POST /api/v1/visits/{id}/complete`: 將單次行程標記為完成 (`DONE`)。

---

## 驗證結果 (Verification Status)

### 測試執行狀況
- `AuthControllerTest`: 4 案例全數通過。
- `BookingControllerTest`: 4 案例全數通過。
- `VisitControllerTest`: 3 案例全數通過。

```bash
./mvnw test -Dtest=AuthControllerTest,BookingControllerTest,VisitControllerTest
```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
