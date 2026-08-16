# TS-JOURNEY-05: 信任/門禁邊界 (Trust & Gating Boundary)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-017/SD-017（保母實名認證與資格審查）、PRD-018（保母公開檔案，見 `docs/sd/SD-018` 與 Journey A `PublicSitterProfilePage`） |
| **測試類型** | ✅ 跨模組整合測試（真後端、真資料庫，非 mock），負向測試 |
| **優先級** | P1（驗證的是既有 PRD-017 稽核修復過的邊界，走的不是正向流程） |
| **自動化狀態** | ✅ 已自動化 |
| **自動化路徑** | `frontend/e2e/journeys/trust-gating-boundary.spec.ts` |

---

## 一、測試邏輯定義 (Given / When / Then)

* **Given**：兩種「不該被信任」的保母狀態——(1) 送出 KYC 但尚未經 admin 審核通過；(2) 原本 `VERIFIED`，被 admin 停權。
* **When**：未登入訪客打開這兩種保母的匿名公開頁；另外對已停權保母，額外驗證停權「之前」就已成立的 `CONFIRMED` 訂單，停權「之後」是否仍可正常執行 Check-in。
* **Then**：兩種狀態的公開頁都應顯示模糊化內容（`gated=true`，不洩漏真實姓名/方案），但停權不影響已在途的訂單——Check-in 應正常成功。這條 journey 刻意不走正向流程，重點是把「目前程式碼的真實邊界行為」明確釘進測試，之後如果有人改動這個邊界，測試會立刻告訴你。

---

## 二、測試步驟與多維度驗證

### 測試 1：未過審保母

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 1 | API：建立保母帳號、切換角色、送出 KYC，**不呼叫 admin 審核** | Profile 停在 `kycStatus=PENDING_REVIEW` | |
| 2 | UI：全新未登入瀏覽器 context 打開 `/sitter/{sitterId}/profile` | 顯示模糊化內容，看不到真實姓名/方案/預約按鈕 | `GET /api/sitter/profile/{sitterId}` 回傳 `gated=true`，`displayName="保母休息中"` |

### 測試 2：已停權保母 + 在途訂單邊界

| 步驟 | 操作 (When) | 業務預期結果 (Then - Functional) | 技術校驗 |
| :--- | :--- | :--- | :--- |
| 1 | API：`bootstrapConfirmedOrder` 兜出一筆 `CONFIRMED` 訂單（此時保母仍是正常 `VERIFIED`） | | |
| 2 | API：Admin 呼叫 `POST /api/admin/sitters/{sitterId}/suspend` | `Profile.kycStatus → SUSPENDED`，`isOpen → false` | |
| 3 | UI：未登入瀏覽器打開同一保母的公開頁 | 顯示模糊化內容，跟未過審共用同一套 gating 邏輯 | `SitterPublicProfileServiceImpl.getPublicProfile()` 判斷式是 `!"VERIFIED".equals(kycStatus)`，`SUSPENDED` 跟 `PENDING_REVIEW` 一樣會落入這個分支 |
| 4 | API：對停權「之前」就存在的訂單呼叫 `POST /api/visits/{visitId}/start`（Check-in） | **仍應成功**，`Order/Visit → IN_PROGRESS` | `VisitReportService.startVisit()` 完全沒有檢查保母的 `kycStatus`/`isOpen`，全系統目前只有 `SitterPublicProfileServiceImpl` 這一處會檢查 `SUSPENDED` |

---

## 三、邊界條件 / 例外場景

* **停權影響範圍是刻意的、已核實的程式碼現況**：全 repo 搜尋 `SUSPENDED` 字串，只有 `SitterPublicProfileServiceImpl` 這一個地方會判斷這個狀態值；`BookingService`（新預約）、`VisitReportService`（打卡/日誌）、`ModificationService`（訂單變更）等一律不檢查。也就是說停權目前只擋「新的公開曝光/新預約」，完全不影響已成立訂單接下來的服務執行——這是否符合產品意圖，屬於業務決策，本測試只負責把現況釘住，不代表這就是「正確」的設計，必要時應由 PM/PO 確認後再決定要不要擴大停權的影響範圍。
* **不驗證的點（刻意）**：不驗證停權後「能否建立新預約」這個路徑本身（即 `isOpen=false` 如何影響 `BookingService`/前台預約頁），這部分屬於 Journey B 下單流程的延伸，若要驗證應該併入該條 journey 或另開測試，避免跟本 journey 的邊界主題混淆。

---

## 四、附註 / 復原步驟

* **資料清理**：本測試建立的帳號、訂單，皆落在 `@e2e-journey.test` 網域，由 CI 跑完後呼叫 `cleanup-e2e-journeys` 統一硬刪除。
* **前置依賴**：測試 2 沿用 `bootstrapConfirmedOrder`（Journey A+B 機械部分）。
