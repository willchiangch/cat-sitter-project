---
name: project-auditor
description: 根據系統設計 (SD) 與非功能性需求 (NFR) 檢查當前程式碼的開發合規性。每當使用者要求「審計專案」、「檢查設計對齊」、「驗證 NFR 遵守情況」或在重大功能開發完成後，請務必啟動此 Skill 進行全盤掃描。
---

# Project Auditor

此 Skill 旨在確保開發實作與 `docs/sd` (System Design) 及 `docs/sa/nfr` (Non-Functional Requirements) 高度對齊，避免產生技術債或違反核心架構原則。

## 審計流程 (Audit Workflow)

1.  **基準載入**：
    - 讀取 `docs/sd/SD-GLOBAL-SPEC.md` 獲取技術底座標準。
    - 讀取 `docs/sa/nfr/00-NFR-OVERVIEW.md` 獲取全域非功能性約束。
    - 針對特定模組，讀取對應的 `SD-xxx.md` (如 `SD-005` 針對預約流程)。

2.  **靜態掃描**：
    - 檢查程式碼結構、設定檔 (`application.yml`)、Dockerfile 與資料庫遷移腳本 (`V__xxx.sql`)。

3.  **合規性驗證**：
    - 比對下列核心指標 (Core Indicators)。

4.  **產出報告**：
    - 使用結構化的 Markdown 報告，標註 PASS/FAIL/WARNING。

## 核心審計指標 (Core Indicators)

### 1. 財務與精度 (Currency & Precision)
- **DB 欄位**：金額類欄位必須為 `INT` (或 `Integer`/`Long`)。
- **Java 計算**：中間計算過程必須使用 `BigDecimal`。
- **檢核點**：是否存在 `Double` 或 `Float` 處理金額？

### 2. 時區與日期 (Timezone & Date)
- **容器層**：Dockerfile 是否設定 `TZ=UTC`？
- **DB 層**：SQL 欄位是否使用 `TIMESTAMPTZ`？
- **Java 層**：實體欄位是否使用 `OffsetDateTime` (推薦) 或 `Instant`？
- **檢核點**：嚴禁在程式碼中使用 `LocalDateTime.now()` (無時區)。

### 3. 併發與一致性 (Concurrency)
- **樂觀鎖**：具備狀態機變更的 Entity 必須有 `@Version`。
- **分散式鎖**：排隊或佔用類業務是否使用 `PostgreSQL Advisory Locks`？
- **冪等性**：API 是否有 `idempotency_key` 機制？

### 4. SaaS Gating & 安全 (Security)
- **權限攔截**：`@RequirePlan` 註解是否正確放在 Controller？
- **JWT**：Token 是否只存放 `accountId` 與 `currentRole`？(嚴禁存放 `planType`)。
- **Actuator**：`health` 是否僅暴露有限資訊？

### 5. 基礎設施合規 (Infrastructure)
- **鏡像**：是否使用 `eclipse-temurin:21`？
- **遷移**：異動是否透過 Flyway `V__xxx.sql`？
- **日誌**：是否使用 SLF4J 輸出至 Console？

## 報告格式 (Report Structure)

```markdown
# Project Audit Report - [YYYY-MM-DD]

## 📊 Summary
- **Overall Status**: [COMPLIANT / PARTIAL / AT RISK]
- **Critical Issues**: N
- **Warnings**: M

## 🔍 Detailed Findings

### [Category: e.g., Persistence]
- [ ] **[Indicator Name]**: [STATUS: PASS/FAIL/WARNING]
  - **Evidence**: `Path/To/File.java:L123`
  - **Details**: 發現使用 Double 處理金額，違反 SD-GLOBAL-SPEC 2.2。
  - **Remediation**: 改為 Integer 儲存，計算時轉 BigDecimal。

## 🚀 Action Items
1. [High Priority] 修復 X...
2. [Medium Priority] 優化 Y...
```

## 觸發建議
當使用者說「幫我檢查看看有沒有寫歪」或「Review 目前進度是否符合規格」時，請主動執行此審計。
