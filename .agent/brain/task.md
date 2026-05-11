# SD-009 Order Completion Task List

- [x] **API 驅動排程架構** (CompletionService & OrderController)
- [x] **管理員裁決功能** (resolveDisputedOrder API & Record DTO)
- [x] **型別安全強化** (Order.items -> List<OrderItem>)
- [x] **基礎設施修復** (解決 Lombok 衝突與 Repository 語法錯誤)
- [x] **測試驗證** (CompletionServiceTest 全數通過)
- [x] **持久化同步** (執行 /persist-progress)

## 詳細子任務
- [x] **核心邏輯**
    - [x] 實作 `CompletionService` (72hr 殭屍清理 + 48hr 自動結案)
    - [x] 實作手動結案 API 與權限檢查
    - [x] 實作管理員強制裁決 API (包含財務撥款日期計算)
- [x] **資料安全性**
    - [x] 重構 `Order` 與 `OrderSnapshot` 的 `items` 欄位為 `List<OrderItem>`
    - [x] 修正 `BookingService` 與 `EvaluationService` 的 JSONB 反序列化邏輯
- [x] **系統除錯**
    - [x] 修復 `VisitRepository` 與 `OrderRepository` 的重複方法宣告
    - [x] 還原 `pom.xml` 至穩定版並解決 Lombok 編譯中斷問題
- [x] **驗證**
    - [x] 實作 `CompletionServiceTest` 並通過 3/3 測試案例
