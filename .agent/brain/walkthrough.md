# Pet Sitter Booking Engine Implementation Walkthrough

## 今日核心變更

### 1. 媒合式預約模型 (Marketplace Booking)
- **邏輯**：預約時僅建立 `PENDING` 訂單，不扣配額。只有當保母執行 `confirmOrder` 時才觸發 Advisory Lock 並計算剩餘容量。
- **關鍵檔案**：
    - [ConfirmOrderService.java](file:///d:/myproject/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/ConfirmOrderService.java)
    - [AdvisoryLockService.java](file:///d:/myproject/cat-sitter-project/backend/src/main/java/com/petsitter/infrastructure/lock/AdvisoryLockService.java)

### 2. 契約快照 (Contract Snapshotting)
- **邏輯**：在報價 (Quote) 階段將方案名稱、單價、總額、媒體保留天數永久存入 `order_snapshots`。
- **安全性**：實作了 **Zero-Trust Pricing**，由後端重新計算金額，不信任前端傳入的總價。
- **關鍵檔案**：
    - [EvaluationService.java](file:///d:/myproject/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/EvaluationService.java)
    - [OrderSnapshot.java](file:///d:/myproject/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/OrderSnapshot.java)

### 3. 環境與合規性修正
- **Docker Fix**：透過修改 `~/.testcontainers.properties` 解決了 Windows 環境下 Docker API 版本 (1.32 vs 1.44) 的相容性問題。
- **Precision Fix**：金額計算全數改為 `BigDecimal`。

## 驗證結果
- **TS-005**：併發接單測試 **PASS**。
- **TS-006**：快照與方案權限測試 **PASS**。
