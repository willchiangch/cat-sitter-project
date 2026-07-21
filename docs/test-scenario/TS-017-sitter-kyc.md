# TS-017: 保母實名認證與資格審查 (KYC)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-017 / SD-017 |
| **測試類型** | ✅ 功能測試 / ✅ 狀態機測試 / ✅ 權限測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | 🟢 已實作 (11/11 Scenarios) |

---

## 0. 前置條件 (Prerequisites)
- 保母帳號已登入；管理員帳號已登入（用於審核類情境）。

## Scenario 1: 保母提交 KYC 資料成功
* **Given**: 保母 `kycStatus = UNVERIFIED`，尚未提交過認證資料。
* **When**: 上傳身分證與自拍照並送出。
* **Then**: 建立一筆 `PENDING_REVIEW` 的 KYC 紀錄。
* **自動化對應**: `KycControllerTest.should_SubmitKyc_Successfully()`

## Scenario 2: 重複提交應被攔截
* **Given**: 保母已是 `PENDING_REVIEW` 或 `VERIFIED`。
* **When**: 再次呼叫提交 API。
* **Then**: 系統拒絕重複提交。
* **自動化對應**: `KycControllerTest.should_BlockDuplicateSubmit_WhenAlreadySubmittedOrVerified()`

## Scenario 3: 已停權保母不可重新提交
* **Given**: 保母 `kycStatus = SUSPENDED`。
* **When**: 嘗試提交 KYC 資料。
* **Then**: 系統拒絕，須由管理員先執行解除停權。
* **自動化對應**: `KycControllerTest.should_BlockSubmit_WhenSitterIsSuspended()`

## Scenario 4: 查詢自己的審查狀態
* **Given**: 保母已提交過 KYC。
* **When**: 呼叫查詢狀態 API。
* **Then**: 正確回傳目前 `kycStatus`。
* **自動化對應**: `KycControllerTest.should_GetKycStatus_Successfully()`

## Scenario 5: 未實名認證保母無法開啟接單狀態
* **Given**: 保母 `kycStatus != VERIFIED`。
* **When**: 嘗試將 `isOpen` 設為 `true`。
* **Then**: 系統拒絕（403），需先通過審查（SD-017 §1.3 卡控矩陣）。
* **自動化對應**: `KycControllerTest.should_BlockOpenBooking_WhenSitterNotVerified()`

## Scenario 6: 管理員查詢待審核清單
* **Given**: 存在多筆 `PENDING_REVIEW` 紀錄。
* **When**: 管理員呼叫待審核清單 API。
* **Then**: 正確回傳全部待審核項目。
* **自動化對應**: `KycControllerTest.should_GetPendingKycRecords_ForAdmin()`

## Scenario 7: 管理員核准 KYC
* **Given**: 存在一筆 `PENDING_REVIEW` 紀錄。
* **When**: 管理員執行核准。
* **Then**: 狀態轉為 `VERIFIED`，保母可進一步開啟接單狀態。
* **自動化對應**: `KycControllerTest.should_ApproveKyc_Successfully()`

## Scenario 8: 管理員退件並強制關閉接單
* **Given**: 保母 `PENDING_REVIEW` 且 `isOpen = true`。
* **When**: 管理員執行退件。
* **Then**: 狀態退回，且系統強制將 `isOpen` 設為 `false`，避免退件後仍可被預約。
* **自動化對應**: `KycControllerTest.should_RejectKyc_AndForceCloseSitterBooking()`

## Scenario 9: 管理員強制停權 / 解除停權
* **Given**: 保母目前 `VERIFIED`。
* **When**: 管理員執行停權，之後再執行解除停權。
* **Then**: 停權後狀態轉 `SUSPENDED` 且無法接單；解除停權後狀態轉回 `VERIFIED`。
* **自動化對應**: `KycControllerTest.should_SuspendSitter_Successfully()`, `KycControllerTest.should_UnsuspendSitter_Successfully()`

## Scenario 10: 未實名認證保母的公開頁面與預約攔截 (PRD-017 AC-4)
* **Given**: 保母 `kycStatus = UNVERIFIED`（尚未提交或審核中），公開檔案原設定為開放接單、可見。
* **When**: 訪客（未登入或飼主）查詢該保母的公開檔案頁 `GET /api/sitter/profile/{sitterId}`。
* **Then**: 回傳 `gated=true`，`displayName`/`bio` 皆被模糊化，行為與「停權中」保母一致，不得正常顯示或被預約——修正前的落差是僅停權會被攔截，未實名認證的保母仍會正常曝光。
* **自動化對應**: `SitterPublicProfileControllerTest.testGetProfileGating()`（含 A2 子情境）

## Scenario 11: 停權保母的公開頁面模糊化
* **Given**: 保母 `kycStatus = SUSPENDED`。
* **When**: 訪客查詢公開檔案頁。
* **Then**: 回傳 `gated=true`，`displayName="保母休息中"`，`bio=""`。
* **自動化對應**: `SitterPublicProfileControllerTest.testGetProfileGating()`（含 A 子情境）

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 提交/重複提交 KYC | 首次成功、重複拒絕 | `kyc_status` 狀態機卡控 |
| 2 | 未實名開啟接單 | 拒絕 403 | `isOpen && kycStatus != VERIFIED` 卡控 |
| 3 | 管理員退件 | 狀態退回 + 強制關閉接單 | 同一交易內 `kyc_status`+`is_open` 一併更新 |
| 4 | 訪客查詢未實名/停權保母公開頁 | 一律 `gated=true` | `SitterPublicProfileServiceImpl` gating 邏輯，兩種狀態同一分支處理 |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [KycControllerTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/KycControllerTest.java), [SitterPublicProfileControllerTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/interfaces/controller/SitterPublicProfileControllerTest.java)
- **執行指令**: `mvn test -Dtest=KycControllerTest,SitterPublicProfileControllerTest`
- **最後驗證日期**: 2026-07-18
