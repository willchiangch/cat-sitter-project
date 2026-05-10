# 核心引擎系統設計 (SD) 完成報告

## 1. 執行摘要
本階段已完成貓咪保母 PWA 最關鍵的「核心引擎」設計。透過與 NFR (非功能性需求) 的深度對齊與顧問回覆的多次迭代，目前已產出具備生產等級 (Production-ready) 的四大模組設計文件。

## 2. 完成項目與技術重點

### [SD-005] 預約送單 (Public Booking)
- **技術點**：實作 **Sorted Advisory Locks**，從資料庫層級根除高併發下的死結與超賣風險。
- **安全性**：引入 **Zero-Trust Pricing** 校驗機制。

### [SD-006] 報價審核 (Order Evaluation)
- **技術點**：實作 **Full-Contract Snapshotting** (JSONB)，鎖定合約金額與媒體保留規則。
- **權限**：整合 SaaS 方案等級檢查 (SaaS Gating)。

### [SD-009] 結案與爭議 (Completion & Dispute)
- **技術點**：設計 **殭屍行程自動清理機制** 與 48h 自動結案 CronJob。
- **審計**：實作管理員強制結案的證據快照與二次驗證。

### [SD-016] 變更與退款 (Modification & Cancellation)
- **技術點**：實作 **快照依賴試算 (Snapshot Recalc)**，確保變更單價與原合約一致。
- **一致性**：引入線上退款 Webhook 最終一致性機制。

## 3. NFR 對齊成果
- **效能**：所有寫入操作均設計為可於 2 秒內完成回應。
- **精確度**：全系統統一採用 `BigDecimal` 計算，入庫前執行 `HALF_UP` 轉 `INT`。
- **安全性**：所有 POST 操作皆具備 `Idempotency-Key` 與 BOLA 水平越權防護。

### [TS] 測試案例標準化拆分
- **成果**：將 `TS-CORE-001` 拆分為 `TS-005`, `TS-006`, `TS-009`, `TS-016`。
- **效益**：實現 PRD 與 TS 的 1:1 可追溯性，便於後端開發與測試管理。

### [DB] 資料庫地基與防禦性優化
- **容器環境**：建立 `docker-compose.yml` (PostgreSQL 16, UTC)，移除初始化掛載以支援 Flyway。
- **Schema 實作**：完成 `V1__init_core_engine.sql`，實作了 `orders`, `visits`, `snapshots`, `logs`, `modification_requests` 等表。
- **防禦性設計**：
  - **Partial Index**：限制一單同時僅能有一個活動中的變更請求。
  - **Unique Constraint**：強制一單一快照。
  - **Decoupled Logs**：支持 `SYSTEM_CRON` 操作者紀錄。
  - **Composite Index**：優化 `(status, scheduled_at)` 行程清理效能。

## 4. 後續計畫
- 啟動 **TS (Test Scenario)** 編寫，針對狀態機進行全路徑覆蓋。
- 進行前端畫面的詳細設計 (SD-UI)。
