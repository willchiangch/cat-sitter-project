# Walkthrough - SD-016 訂單變更與退款實作

本模組已完成開發並通過邏輯驗證，重點在於實現了「雙向變更」的穩定性與「財務快照」的一致性。

## 核心變更
1.  **獨立協商機制**：使用 `modification_requests` 表進行非同步協商，避免主訂單狀態機混亂。
2.  **快照差額試算**：金額計算完全依賴 `order_snapshots`，不受市場調價影響。
3.  **行程動態重建**：
    - **安全刪除**：僅刪除 `PENDING` 行程。
    - **歷史保護**：完整保留 `DONE` 行程與其時間戳。
4.  **死結與併發防禦**：
    - **Advisory Locks**：對所有涉及日期進行 `sorted().distinct()` 加鎖。
    - **Partial Index**：確保一個訂單同時只能有一個進行中的變更請求。

## 驗證結果
- **單元測試**：通過 `ModificationServiceTest` 驗證了加價、減價及行程保護邏輯。
- **編譯測試**：後端專案編譯通過。

## 待辦事項
- 前端對接 `/api/orders/{orderId}/modify` 與 `/api/orders/{orderId}/modification/confirm`。
- 退款核銷流程需配合 GCP Storage 憑證上傳測試。
