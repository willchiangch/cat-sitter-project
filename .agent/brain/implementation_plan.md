# WhiskerWatch: 服務方案、信任圈與流程優化計畫 (V31)

本計畫旨在全面實體化保母的服務定價、建立保母與家長間的信任關係，並根據信任狀態優化預約流程（常客免問卷）。

## User Review Required

> [!IMPORTANT]
> **業務邏輯澄清 (Logic Separation)**：
> 1. **信任圈 (Sitter-to-Sitter)**：用於保母間轉介。保母 A 可將保母 B 加入信任圈，並向家長分享 B 的預約連結。
> 2. **常客白名單 (Sitter-to-Client)**：用於流程優化（免問卷）。保母單向將家長標記為常客，對家長隱形，影響預約流程中的問卷判定。

## Proposed Changes

### 1. 服務方案實體化 (Service Packages Realization)
#### [MODIFY] `SitterServiceService.java` & `SitterServiceController.java`
- 確保所有 CRUD 功能已完全開發並對齊前端。
#### [MODIFY] `ServicePanel.jsx` (Sitter Side)
- 將 Mock 的服務選擇器替換為從後端拉取的實體方案。

### 2. 信任與常客機制實體化 (Trust & Whitelist)
#### [MODIFY] `SitterTrustCircle.java` (Sitter-to-Sitter)
- 維持現有結構，用於管理保母間的推薦關係。
#### [NEW] `SitterClientWhitelist.java` (Sitter-to-Client)
- 專門用於「熟客優化」。欄位：`sitterProfileId`, `clientProfileId`, `skipQuestionnaire` (Boolean)。
#### [NEW] `WhitelistService.java` & `WhitelistController.java`
- 提供保母標記常客、設定免問卷的 API。
#### [MODIFY] `BookingService.java`
- 在 `createBooking` 邏輯中檢核 `Whitelist` 表，決定是否跳過問卷。

### 3. 前端 UI 實體化
#### [MODIFY] `TrustCircle.jsx` (Sitter Side)
- 從 Mock 資料切換至 `trustCircleService.listClients()`。
- 實作「切換常客狀態」與「免問卷設定」的開關。
#### [MODIFY] `BookingFlow.jsx` (Client Side)
- 呼叫新的檢核 API：若保母已將此家長設為常客，則在送出預約前隱藏問卷區塊。

---

## Open Questions

1. **反向信任**：目前規劃是「保母單向將家長設為常客」。是否需要家長端也能看到自己被哪些保母標記為「信任好友」？（建議：初期維持保母端管理即可，以簡化邏輯）。

## Verification Plan

### Automated Tests
- `TrustServiceTest`：驗證標記常客與免問卷標誌的正確性。
- `BookingServiceIntegrationTest`：驗證常客下單時，問卷狀態確實變更為 `NOT_REQUIRED`。

### Manual Verification
1. 以保母身分進入「信任圈管理」，將一位家長標記為常客並開啟「免問卷」。
2. 切換至該家長身分嘗試向該保母預約。
3. 確認預約流程中不再出現問卷網頁。
4. 送出訂單後，檢查資料庫中的 `questionnaire_status` 為 `NOT_REQUIRED`。
