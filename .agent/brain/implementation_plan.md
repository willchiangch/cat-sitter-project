# TS-003: 保母服務方案設定測試情境計畫

本計畫旨在撰寫 [TS-003-service-plans.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/test-scenario/TS-003-service-plans.md) 測試情境文件，以 `ts-skill` 的 Given/When/Then 邏輯與多維度技術驗證規格（包含 API 狀態碼、DB 與稽核日誌 care_logs 狀態）進行編寫。

## User Review Required

> [!IMPORTANT]
> 為了提供 100% 完整覆蓋率，本計畫將測試場景擴充並調整為以下結構：
> 1. **S1**: 保母成功建立常態服務方案 (Happy Path, version=0, **驗證 `care_logs` 寫入 `CREATE_SUCCESS`**)
> 2. **S2**: 保母成功編輯服務方案 (Happy Path, version由0變1, **驗證 `care_logs` 寫入 `UPDATE_SUCCESS`**)
> 3. **S3**: 編輯服務方案樂觀鎖衝突 (409 `VERSION_CONFLICT`)
> 4. **S4**: 保母邏輯刪除服務方案 (is_deleted=true, **驗證 `care_logs` 寫入 `DELETE_SUCCESS`**)
> 5. **S5**: 保母方案排序調整 (排序順序與 index 綁定)
> 6. **S6**: 價格無效校驗拒絕 (400 `INVALID_PARAMETER`, price <= 0, **對齊 `IllegalArgumentException` 或 `MethodArgumentNotValidException` 異常攔截**)
> 7. **S7**: 非專業版保母設定日期區間遭拒 (403 `AUTH_PLAN_LIMIT`, **明確指定由 Service 層拋出 `AuthPlanLimitException`**)
> 8. **S8**: 專業版以上保母成功設定生效日期區間 (Happy Path, **補上 `care_logs` 寫入 `CREATE_SUCCESS` 驗證**)
> 9. **S9a (區間過濾 - 未來)**：Lazy Evaluation 判定。`startDate` 在未來時，前台查詢剔除不回傳。
> 10. **S9b (區間過濾 - 逾期)**：Lazy Evaluation 判定。`endDate` 在過去時，前台查詢剔除不回傳。
> 11. **S9c (區間過濾 - 生效)**：Lazy Evaluation 判定。常態方案或在有效區間內，前台查詢正常回傳。
> 12. **S10**: 預約送單日期超出方案生效區間拒絕 (422 `PLAN_NOT_IN_RANGE`, **雙向驗證：過早預約 < `startDate` 與過晚預約 > `endDate`**)
> 13. **S11**: 越權防禦 — 編輯/刪除非自己擁有的方案 (403 IDOR 攔截，步驟中各驗證一項端點)
> 14. **S12 (404 邊界)**：編輯/刪除不存在的方案編號，返回 404 `PLAN_NOT_FOUND`。
> 15. **S13 (後台查詢)**：保母後台查詢方案列表 (GET `/api/sitter/plans`)。不論方案是否過期，皆回傳所有 `is_deleted = false` 的方案。
> 16. **S14 (角色與認證防護)**：非 SITTER 角色（如 OWNER）嘗試呼叫 POST 寫入端點返回 403；未登入匿名呼叫返回 401。

## Proposed Changes

### Documentation

#### [NEW] [TS-003-service-plans.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/test-scenario/TS-003-service-plans.md)
將會根據 `ts-skill` 的模版設計，包含以下部分：
- **測試邏輯定義**：對這 16 個精細化測試場景進行 Given / When / Then 的定義。
- **多維度驗證表**：驗證 API 狀態碼、DB `service_plans`（`version`、`is_deleted`）與 `care_logs` 狀態。
- **Edge Cases & 安全防護**：針對樂觀鎖版本衝突、時區日期 DATE 型別防護、以及角色/IDOR 越權防禦進行描述。

---

## Verification Plan

本階段為**測試情境文件更新階段**：
- 檢查更新後的設計文件是否完美符合 `ts-skill` 的合規性要求。
- 提供 Markdown 格式的完整測試情境文件。
