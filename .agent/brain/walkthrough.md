# TS-003: 保母服務方案設定 測試情境文件撰寫 Walkthrough (更新修正版)

我們已成功修正並建立了完整的 [TS-003-service-plans.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/test-scenario/TS-003-service-plans.md) 測試情境文件，完全符合 `PRD-003` / `SD-003` 的要求與 `ts-skill` 的 Given/When/Then 多維度驗證規範，並解決了所有細微的技術精度問題。

---

## 本次更新與修正要點

1. **價格無效校驗例外類型修正 (S6)**：
   - 將價格無效 (price <= 0) 的例外技術指標描述修正為：拋出 `IllegalArgumentException`（手動校驗）或 `MethodArgumentNotValidException`（Bean Validation），並由 `GlobalExceptionHandler` 攔截，回傳 400 `INVALID_PARAMETER`。
2. **S7 拋出層精準化**：
   - 根據 `SD-003` 技術決策，S7 (非專業版設定日期) 屬性級 Gating 為 Service 層進行卡控。將 Technical Metric 指標精確描述為由 `ServicePlanService` 拋出 `AuthPlanLimitException`。
3. **S8 操作日誌 care_logs 驗證補齊**：
   - S8 與 S1 同樣是新增方案的 Happy Path，因此在 S8 的多維度驗證表中補上 Step 2，驗證 `care_logs` 正確寫入一筆 `CREATE_SUCCESS` 記錄。
4. **S10 雙向預約日期時程防禦**：
   - 補齊雙向時程防衛測試。在 S10 驗證表中設計兩個步驟：
     - Step 1: 預約服務日期過早（`2026-04-15 < startDate 2026-05-01`）被 `ServicePlanException` 阻守，返回 422 `PLAN_NOT_IN_RANGE`。
     - Step 2: 預約服務日期過晚（`2026-07-05 > endDate 2026-06-30`）被 `ServicePlanException` 阻守，返回 422 `PLAN_NOT_IN_RANGE`。
