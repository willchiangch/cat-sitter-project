- [x] **SD-005: 媒合式預約引擎實作**
    - [x] 實作 `BookingController` (Idempotency-Key Header 處理)
    - [x] 實作 `ConfirmOrderService` (Advisory Lock 併發控制)
    - [x] 修正 `VisitRepository` 容量計算邏輯
    - [x] 通過 `TS-005` 併發測試

- [x] **SD-006: 保母報價與快照系統**
    - [x] 實作 `EvaluationService` (Zero-Trust 報價校驗)
    - [x] 實作 `OrderSnapshot` (契約不回溯快照)
    - [x] 實作 SaaS Gating (四級方案權限卡控)
    - [x] 補齊詳細快照欄位 (`unit_price`, `plan_title`)
    - [x] 通過 `TS-006` 快照測試

- [x] **基礎設施加固**
    - [x] 撰寫 Flyway `V2`, `V3` 遷移腳本
    - [x] 修復 Windows 環境 Testcontainers/Docker API 版本衝突 (全域配置鎖定)
    - [x] 全域異常攔截器 (GlobalExceptionHandler) 對齊業務錯誤碼
