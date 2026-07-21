# TS-020: 系統管理後台 — 內部信用指標管理 (Admin Dashboard: Trust Score)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-020（主流程 E） / SD-020 |
| **測試類型** | ✅ 功能測試 / ✅ 隱私測試 / ✅ 冪等測試 |
| **優先級** | P2 (Medium) |
| **自動化狀態** | 🟢 已實作 (3/3 Scenarios) |

> [!NOTE]
> 本文件僅涵蓋 PRD-020 主流程 E（信用指標管理）。B/C/D 流程的測試情境分別隨附於 [TS-017](TS-017-sitter-kyc.md)（KYC/停權）與 [TS-009](TS-009-order-completion.md)（爭議結案）。

---

## 0. 前置條件 (Prerequisites)
- 管理員帳號已登入；至少 1 位保母帳號（`trust_score` 預設 100）。

## Scenario 1: 手動調整信用指標並寫入稽核日誌
* **Given**: 保母目前 `trust_score = 100`。
* **When**: 管理員送出 `delta=-10`，`reason="訂單爭議判賠"`。
* **Then**: 保母分數變為 90，且產生一筆 `ADMIN_TRUST_SCORE_ADJUST` 稽核日誌，內容包含異動前後分數與原因。
* **自動化對應**: `KycTrustScoreTest.should_AdjustTrustScore_AndWriteAuditLog()`

## Scenario 2: 異動原因為空應被拒絕
* **Given**: 管理員欲調整某保母分數。
* **When**: `reason` 為空白字串。
* **Then**: 系統回傳 400，分數不變。
* **自動化對應**: `KycTrustScoreTest.should_RejectAdjustTrustScore_When_ReasonBlank()`

## Scenario 3: 低於門檻標註高風險
* **Given**: 保母分數經多次扣點後低於 60。
* **When**: 管理員查詢信用指標清單。
* **Then**: 該保母項目 `highRisk=true`。
* **自動化對應**: `KycTrustScoreTest.should_MarkHighRisk_When_ScoreBelowThreshold()`

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 調整點數 | 分數正確加減 | `profiles.trust_score` UPDATE |
| 2 | 重複送出同一 Idempotency-Key | 第二次不重複扣點 | `IdempotencyService.checkAndConsume` |
| 3 | 飼主/保母嘗試查詢自己的信用指標 | 無此 API 可呼叫（403 或 404，視路由而定） | 隱私規則驗證：僅 `/api/admin/**` 有此端點 |
| 4 | 分數低於 60 | 清單標記高風險 | Service 層計算 `highRisk`，非資料庫欄位 |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [KycTrustScoreTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/KycTrustScoreTest.java)
- **E2E 對應**: [admin-trust-scores.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/admin-trust-scores.spec.ts)
- **執行指令**: `mvn test -Dtest=KycTrustScoreTest`
- **最後驗證日期**: 2026-07-18
