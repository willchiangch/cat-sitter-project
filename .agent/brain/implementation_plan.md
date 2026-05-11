# 預約模型修正：從「搶佔式」改為「媒合式」

## 問題分析

你的直覺完全正確，而且 **PRD-005 早就明確定義了這個行為**：

> [!IMPORTANT]
> PRD-005 第 69 行：「所選日期已有其他訂單排候 → 系統不阻擋送出，飼主可正常送單。實際檔期衝突由保母在審核階段 (PRD-006) 決定接受或拒絕。」
>
> PRD-005 第 77 行：「檔期不在飼主送單時鎖定，而是在訂單進入 `CONFIRMED` 狀態時才正式佔用。」

**目前程式碼的問題**：`BookingService.createBooking()` 在送單階段就執行了配額檢查並拋出 `CapacityFullException`，這直接違反了 PRD-005 的設計意圖。

### 正確的業務模型

```
飼主送單 (createBooking)          保母接單 (confirmOrder)
─────────────────────────────    ────────────────────────────
✅ 允許多人對同一保母送單          ⛔ 配額滿時擋下
✅ 不檢查配額                      ✅ Advisory Lock 在這裡用
✅ 只做冪等性防重複                 ✅ 檢查 CONFIRMED 訂單數 vs dailyCapacity
✅ 狀態 → PENDING                  ✅ 狀態 PENDING → PENDING_PAYMENT
```

---

## Proposed Changes

### 1. Application 層

#### [MODIFY] [BookingService.java](file:///d:/myproject/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/BookingService.java)
- **移除** Advisory Lock 與配額檢查邏輯
- `createBooking()` 簡化為：驗證方案存在 → 建立 PENDING 訂單 → 建立 Visits
- 保留 Idempotency Key (DB UNIQUE 約束自然防重複)

#### [NEW] ConfirmOrderService.java
- 新建 `com.petsitter.application.service.ConfirmOrderService`
- 實作 `confirmOrder(UUID sitterId, UUID orderId)` 方法：
  1. 驗證訂單屬於該保母 (BOLA 防護)
  2. 驗證訂單狀態為 `PENDING`
  3. **取得 Advisory Lock** (Sorted Keys)
  4. **配額檢查**：計算該日期 `CONFIRMED` 狀態的訂單數 vs `dailyCapacity`
  5. 若配額已滿 → 拋出 `CapacityFullException`
  6. 若有空位 → 更新狀態為 `PENDING_PAYMENT`

---

### 2. Repository 層

#### [MODIFY] [VisitRepository.java](file:///d:/myproject/cat-sitter-project/backend/src/main/java/com/petsitter/domain/repository/VisitRepository.java)
- 修改查詢：只計算 `CONFIRMED` 或 `PENDING_PAYMENT` 狀態的訂單（不計算 `PENDING`）

#### [MODIFY] [OrderRepository.java](file:///d:/myproject/cat-sitter-project/backend/src/main/java/com/petsitter/domain/repository/OrderRepository.java)
- 新增 `findByIdAndSitterId()` 方法 (BOLA 防護)

---

### 3. 測試層

#### [MODIFY] [BookingServiceTest.java](file:///d:/myproject/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/BookingServiceTest.java)
- **TS-005-01 (重寫)**：驗證「兩個飼主同時送單 → 兩個都成功建立 PENDING 訂單」
- **TS-005-02 (新增)**：驗證「保母併發確認兩筆訂單 → 僅一筆成功進入 PENDING_PAYMENT」

---

### 4. 文件層

#### [MODIFY] [TS-005-public-booking.md](file:///d:/myproject/cat-sitter-project/docs/test-scenario/TS-005-public-booking.md)
- Scenario 1 修正為「多人同時送單皆成功」
- Scenario 新增「保母併發確認時的配額鎖定」
- 更新自動化追溯

#### [MODIFY] [SD-005-public-booking.md](file:///d:/myproject/cat-sitter-project/docs/sd/SD-005-public-booking.md)
- 序列圖中的「Capacity Check」步驟需移除或標註為「延遲至 SD-006 確認階段」
- 明確標註 Advisory Lock 的使用時機是「保母確認訂單時」而非「飼主送單時」

> [!WARNING]
> SD-005 的序列圖第 86 行目前寫著「檢核所有日期配額 (Capacity Check)」，這與 PRD-005 矛盾。需修正。

---

## 不需變動的部分

| 元件 | 原因 |
|:---|:---|
| `AdvisoryLockService` | 邏輯不變，只是搬到 `ConfirmOrderService` 呼叫 |
| `ServicePlan` / `ServicePlanRepository` | 結構不變，配額欄位依然有用 |
| `Order` Entity | 結構不變 |
| DB Migration | 不需改，表結構不變 |
| `testcontainers.properties` | 不需改 |

---

## Verification Plan

### Automated Tests
```bash
mvn clean test -Dtest=BookingServiceTest -Dgroups="TS-005"
```

預期結果：
- `TS-005-01`：兩個送單都成功 (2 success, 0 fail)
- `TS-005-02`：保母確認時只有 1 個成功 (1 success, 1 CapacityFullException)
