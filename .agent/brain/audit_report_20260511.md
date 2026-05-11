# Project Audit Report - 2026-05-11

## 📊 Summary
- **Overall Status**: **PARTIAL COMPLIANT**
- **Critical Issues**: 0
- **Warnings**: 1 (Missing Dockerfile)

## 🔍 Detailed Findings

### 1. 財務與精度 (Currency & Precision)
- [x] **金額儲存與計算**: **STATUS: PASS**
  - **Evidence**: `V1__init_core_engine.sql:49` (`total_amount INT`), `Order.java:39` (`Integer totalAmount`)
  - **Details**: 符合 SD-GLOBAL-SPEC 2.2，金額欄位統一使用整數存儲，避免浮點數誤差。

### 2. 時區與日期 (Timezone & Date)
- [x] **全域時區對齊**: **STATUS: PASS**
  - **Evidence**: `V1__init_core_engine.sql:16` (`TIMESTAMPTZ`), `BaseEntity.java:32` (`OffsetDateTime`), `docker-compose.yml:9` (`TZ: UTC`)
  - **Details**: 資料庫、實體類別與容器配置皆統一為 UTC 時區。

### 3. 併發與一致性 (Concurrency)
- [x] **樂觀鎖與 Advisory Lock**: **STATUS: PASS**
  - **Evidence**: `BaseEntity.java:27` (`@Version`), `BookingService.java:100` (`orderRepository.acquireAdvisoryLock`)
  - **Details**: 核心實體具備版本控管；預約確認流程正確實作 PostgreSQL Advisory Lock 確保接單不超賣。

### 4. SaaS Gating & 安全 (Security)
- [x] **權限攔截與 JWT 最小化**: **STATUS: PASS**
  - **Evidence**: `OrderController.java:56` (`@RequirePlan`), `JwtUtils.java:28` (Payload 僅包含 email/role)
  - **Details**: 符合安全規範，無狀態 JWT 實作正確，且已因應顧問意見補齊 Refresh Token 機制。

### 5. 基礎設施合規 (Infrastructure)
- [ ] **容器化配置**: **STATUS: WARNING**
  - **Evidence**: `backend/` 目錄下未發現 `Dockerfile`。
  - **Details**: 違反 SD-GLOBAL-SPEC 1.2 要求。
  - **Remediation**: 應依照 Java 21 標準建立多階段構建 (Multi-stage) 的 Dockerfile。

## 🚀 Action Items
1. [Medium Priority] **補齊 Dockerfile**: 建立 `backend/Dockerfile` 並設定多階段構建與 `TZ=UTC`。
2. [Low Priority] **單元測試擴充**: 針對 `JwtUtils` 增加單元測試以完整追溯 TS-000。
