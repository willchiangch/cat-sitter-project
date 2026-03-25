# Backend Development Guidelines

為了確保後端開發的一致性與品質，所有開發人員（含 AI 助理）應遵循以下原則。

## 1. 測試驅動開發 (TDD)
- **測試先行**：開發新功能或修復 Bug 前，應先撰寫失敗的測試案例。
- **覆蓋率目標**：目標單元測試與整合測試的總覆蓋率應達到 **80% 以上**（由 JaCoCo 監控）。
- **CI 驗證**：提交前應執行 `./mvnw verify` 確保測試全數通過並產出報告。

## 2. 測試架構與策略
- **單元測試 (Unit Test)**：針對商業邏輯，使用 `JUnit 5` + `Mockito`。
- **Repository 測試**：使用 `@DataJpaTest` 進行 Data Layer Slice Test。
- **整合測試 (Integration Test)**：核心流程使用 `@SpringBootTest` 並指定 `@ActiveProfiles("test")`。
- **測試環境**：
    - 本地預設使用 **H2 記憶體資料庫** (需啟用 `application-test.yml`)。
    - 若需針對 PostgreSQL 特性（如 JSONB）進行真實驗證，則使用 **Testcontainers** (需具備 Docker)。
- **測試資料管理**：統一使用 `TestDataFactory` 來建構 Domain Entity，避免在測試案例中充斥冗長的 setup 代碼。

## 3. 資料庫與 Entity 規範
- **跨資料庫相容性**：
    - 嚴禁在 `@Column` 中使用 `columnDefinition = "jsonb"`，避免導致 H2 等非 PG 資料庫無法建立 Schema。
    - JSON 欄位應使用 `@JdbcTypeCode(SqlTypes.JSON)`，Hibernate 6 會自動根據 Dialect 進行對應（PG 轉 `jsonb`，H2 轉 `VARCHAR`）。
- **稽核紀錄**：
    - Entity 應繼承 `AuditableEntity` 以自動紀錄 `created_at` / `updated_at`。
    - 進行 Repository 測試時，需在測試類別中 `@Import` 自訂的 `AuditConfig` 以模擬日期連。

## 4. 常用的 Maven 指令
- 跑測試並產出報告：`./mvnw verify`
- 報告路徑：`backend/target/site/jacoco/index.html`
