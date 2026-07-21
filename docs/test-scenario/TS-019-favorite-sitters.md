# TS-019: 飼主我的最愛保母 (Favorite Sitters)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-019 / SD-019 |
| **測試類型** | ✅ 功能測試 / ✅ 隱私測試 |
| **優先級** | P2 (Medium) |
| **自動化狀態** | 🟢 已實作 (6/6 Scenarios) |

---

## 0. 前置條件 (Prerequisites)
- 飼主已登入；至少 1 位可搜尋到的保母帳號。

## Scenario 1: 搜尋並加入收藏
* **Given**: 飼主尚未收藏任何保母。
* **When**: 飼主搜尋保母 ID/Email 並確認加入收藏。
* **Then**: 收藏清單新增 1 筆，狀態顯示為「服務中」。
* **自動化對應**: `FavoriteSitterServiceTest.should_SearchAndAddFavorite_Successfully()`

## Scenario 2: 搜尋不存在的保母
* **Given**: 輸入不存在的保母 ID。
* **When**: 呼叫搜尋 API。
* **Then**: 回傳 404 `SITTER_NOT_FOUND`。
* **自動化對應**: `FavoriteSitterServiceTest.should_ThrowException_When_SitterNotFound()`

## Scenario 3: 保母切換休息中，收藏清單同步顯示
* **Given**: 已收藏的保母將自己的公開檔案設為休息中/隱藏。
* **When**: 飼主重新查詢收藏清單。
* **Then**: 該筆記錄 `hidden=true`，前端顯示「休息中/隱藏中」，但仍保留在清單內。
* **自動化對應**: `FavoriteSitterServiceTest.should_MarkHidden_When_SitterClosedOrInvisible()`

## Scenario 4: 移除收藏
* **Given**: 飼主已收藏某保母。
* **When**: 呼叫移除 API。
* **Then**: 該保母從收藏清單消失（邏輯刪除）。
* **自動化對應**: `FavoriteSitterServiceTest.should_RemoveFavorite_Successfully()`

## Scenario 5: 收藏數達 50 位上限
* **Given**: 飼主已收藏 50 位保母。
* **When**: 嘗試收藏第 51 位。
* **Then**: 回傳 422 `FAVORITE_LIMIT_EXCEEDED`。
* **自動化對應**: `FavoriteSitterServiceTest.should_RejectAddFavorite_When_ExceedsLimit()`

## Scenario 6 (E2E)：保母公開預約頁的愛心 toggle
* **Given**: 飼主開啟某保母的公開預約頁。
* **When**: 點擊愛心圖示。
* **Then**: 立即呼叫加入收藏 API 並反映為已收藏狀態；再次點擊則呼叫移除 API 並反映為未收藏。
* **自動化對應**: `favorite-sitters.spec.ts` 第二個測試

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 重複收藏同一保母 | 冪等成功，不重複建立 | `UNIQUE(owner_id, sitter_id)` + 先查後寫 |
| 2 | 保母帳號被刪除 | 清單標記 `removed=true` | Join `users.is_deleted` |
| 3 | 保母隱藏/休息中 | 清單標記 `hidden=true` | Join `profiles.is_open`/`is_visible` |
| 4 | 保母端查詢「誰收藏了我」 | 無此 API，功能不存在 | 隱私規則驗證（設計審查，非執行期斷言） |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [FavoriteSitterServiceTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/FavoriteSitterServiceTest.java)
- **E2E 對應**: [favorite-sitters.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/favorite-sitters.spec.ts)
- **執行指令**: `mvn test -Dtest=FavoriteSitterServiceTest`
- **最後驗證日期**: 2026-07-18
