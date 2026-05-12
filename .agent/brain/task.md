# 任務清單 - SD-016 訂單變更實作

- [x] **1. 基礎建設與模型**
    - [x] 修正 `ModificationRequest.java` 的 `payload` 型別。
    - [x] 新增 `ModificationPayloadDto` 與相關 Request DTO。
    - [x] 實作 `ModificationRequestRepository`。
    - [x] 執行 Flyway 遷移。
- [x] **2. Repository 邏輯強化 (防雷二)**
    - [x] 在 `VisitRepository` 實作 `countOccupiedCapacityWithSelfExclusion`。
- [x] **3. ModificationService 核心實作 (防雷一)**
    - [x] 實作 `proposeModification` (快照依賴)。
    - [x] 實作 `confirmModification` (排序建議鎖、行程保護)。
- [x] **4. API 介面與冪等性 (防雷三)**
    - [x] 在 `OrderController` 實作變更與確認端點。
- [x] **5. 驗證與審計**
    - [x] 撰寫 `ModificationServiceTest` 並通過 Mock 測試。
    - [x] 專案編譯通過 (BUILD SUCCESS)。
