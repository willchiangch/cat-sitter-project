# TS-010: 信任圈與轉介機制 (Trust Circle & Referral)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-010 / SD-010 |
| **測試類型** | ✅ 功能測試 / ✅ 權限測試 / ✅ 隱私測試 |
| **優先級** | P1 (High) |
| **自動化狀態** | 🟢 已實作 (5/5 Scenarios) |

---

## 0. 前置條件 (Prerequisites)
- 至少 2 名保母帳號（A、B）、1 名飼主帳號、1 名第三方保母（用於「非信任圈成員」情境）。

## Scenario 1: 雙方同意後信任圈成立
* **Given**: 保母 A 對保母 B 發送信任請求（狀態 `PENDING`）。
* **When**: 保母 B 呼叫回應 API 並帶 `accept=true`。
* **Then**: 關係狀態轉為 `ACCEPTED`，且 A、B 雙方查詢「我的信任圈」都能看到對方。
* **自動化對應**: `TrustCircleAndReferralTest.should_EstablishTrust_When_BothPartiesAgree()`

## Scenario 2: 移除信任關係後雙方清單同步消失
* **Given**: A、B 已是 `ACCEPTED` 信任關係。
* **When**: 任一方呼叫移除 API。
* **Then**: 雙方的信任圈清單都不再顯示對方（邏輯刪除）。
* **自動化對應**: `TrustCircleAndReferralTest.should_RemoveFromBothLists_When_RelationshipRemoved()`

## Scenario 3: 轉介給非信任圈成員應被拒絕
* **Given**: 保母 A 與保母 C 之間不存在任何信任關係。
* **When**: 保母 A 嘗試發起轉介給保母 C。
* **Then**: 系統回傳 403 `NOT_IN_TRUST_CIRCLE`，不寫入 `referral_logs`。
* **自動化對應**: `TrustCircleAndReferralTest.should_RejectReferral_When_NotInTrustCircle()`

## Scenario 4: 信任圈內轉介成功並雙向通知
* **Given**: 保母 A 與保母 B 為 `ACCEPTED` 信任關係，保母 A 婉拒某訂單。
* **When**: 保母 A 選擇保母 B 發起轉介，並填寫推薦語。
* **Then**: `referral_logs` 新增 1 筆；飼主收到「保母推薦」通知且完整包含推薦語（AC-4）；保母 B 收到「收到轉介」通知。
* **自動化對應**: `TrustCircleAndReferralTest.should_CreateReferral_AndNotifyBoth_WhenInTrustCircle()`

## Scenario 5: 已被信任圈成員列入黑名單的飼主，不出現在轉介候選名單
* **Given**: 保母 A 的信任圈成員保母 B 已將飼主甲列入黑名單。
* **When**: 保母 A 針對飼主甲查詢轉介候選名單。
* **Then**: 候選名單中不包含保母 B（黑名單前置過濾，PRD-010 主流程 B.2）。
* **自動化對應**: `TrustCircleAndReferralTest.should_ExcludeBlacklistedSitter_FromReferralCandidates()`

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 發送信任請求 | 狀態 PENDING | `UNIQUE(requester_id, target_id)` 防重複 |
| 2 | 對方同意 | 狀態 ACCEPTED，雙向可見 | `findAcceptedBySitterId` 雙向查詢 |
| 3 | 轉介非信任圈對象 | 403 拒絕 | Service 層重新驗證，不信任前端候選清單 |
| 4 | 轉介信任圈對象 | 寫入 log + 雙向通知 | `notifications.category='REFERRAL'` CHECK 約束已放行 |
| 5 | 候選名單含已拉黑對象 | 前置過濾排除 | `GatekeeperService.isBlocked` 呼叫時機在候選查詢與送出時各驗證一次 |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [TrustCircleAndReferralTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/TrustCircleAndReferralTest.java)
- **E2E 對應**: [trust-circle-and-referral.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/trust-circle-and-referral.spec.ts)
- **執行指令**: `mvn test -Dtest=TrustCircleAndReferralTest`
- **最後驗證日期**: 2026-07-18
