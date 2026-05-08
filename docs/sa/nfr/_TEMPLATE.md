# NFR-XXX: [系統/模組名稱] 非功能需求規格書

| 項目 | 內容 |
|------|------|
| 對應需求 | PRD-XXX |
| 負責人 | Will |
| 狀態 | Active |
| 優先級 | P0 (Critical) |

---

## 1. 效能與負載 (Performance & Scalability)

**思維：定義實體邊界，拒絕「越快越好」這種廢話。**

| 指標項目 | 需求目標 (SLO) | 測試情境參考 |
|----------|---------------|--------------|
| **響應時間 (Latency)** | 95% 請求 < 200ms, Max < 2s | TS-PERF-01 |
| **吞吐量 (Throughput)** | 支援 500 TPS (Transactions Per Second) | TS-PERF-02 |
| **併發能力 (Concurrency)** | 穩定支援 2,000 VUs (Constant Load) | TS-PERF-03 |
| **資料量限制** | 單一租戶資料量支援至 10TB，查詢效能不下降 | TS-DB-01 |

* **實作要求：** * 針對頻繁查詢欄位必須建立適當 Index。
    * 必須導入緩存機制 (Valkey/Redis)，快取命中率 (Hit Rate) 需 > 80%。
    * 大數據量匯出必須採用異步處理 (Cloud Pub/Sub + Cloud Run)。

---

## 2. 可用性與可靠性 (Availability & Reliability)

**思維：預想系統掛掉時的自癒能力。**

* **可用性目標：** 99.9% (月停機時間不超過 43 分鐘)。
* **RTO (復原時間目標)：** 系統崩潰後需在 15 分鐘內完成自癒或切換。
* **RPO (復原點目標)：** 資料損失不得超過 5 分鐘 (GCP Cloud SQL 自動備份機制)。
* **容錯機制：**
    * 外部 API 呼叫 (如 Vertex AI) 必須實作 **Circuit Breaker (Resilience4j)**。
    * 重試策略：採用 **Exponential Backoff**，避免對外部服務造成 DDoS。

---

## 3. 安全性 (Security)

**思維：從 API 到 DB 的全方位防禦。**

* **身份驗證 (AuthN)：** 統一使用 JWT (OAuth 2.1)，支援多租戶隔離校驗。
* **授權 (AuthZ)：** 落實 RBAC (Role-Based Access Control) 與 Data-level filter。
* **資料保護：**
    * 傳輸加密：全站強制 TLS 1.3。
    * 儲存加密：敏感欄位 (如身分證、薪資) 需在 DB 層進行 AES-256 加密。
* **稽核路徑 (Audit Trail)：** * 所有 CUD (Create, Update, Delete) 動作必須寫入 `audit_trail`，內容包含：`user_id`, `timestamp`, `ip`, `payload_diff`。

---

## 4. 可觀測性 (Observability)

**思維：不要讓 Debug 變成通靈。**

* **日誌規範：** 必須包含 `Correlation-ID`，確保能串聯跨服務的 Log 路徑。
* **監控指標：** * 必須在 GCP Cloud Monitoring 建立 Dashboard。
    * 核心指標：HTTP 5xx Rate, DB Connection Pool Usage, Memory Usage。
* **警告機制：** 錯誤率超過 1% 或 API 延遲超過 1s 時，立即發送通知。

---

## 5. 維護性與擴展性 (Maintainability)

* **程式碼質量：** Unit Test 覆蓋率需 > 80%。
* **多租戶設計：** 必須支援租戶動態擴增，Schema 設計需保留 `tenant_id` 欄位。
* **技術債約束：** 禁止使用已 Deprecated 的 Java Library，第三方套件版本需至少為次新版。