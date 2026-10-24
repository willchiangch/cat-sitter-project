# TS-005: 飼主預約申請 (Public Booking)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-005 / SD-005 |
| **測試類型** | ✅ 功能測試 / ✅ 併發測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | 🟡 部分實作 (4/5 Scenarios，Scenario 4 待補) |

---

## 0. 前置條件 (Prerequisites)
- **認證狀態**：所有參與測試之使用者（Owner/Sitter）皆須已登入。
- **認證 Header**：API 請求須包含 `Authorization: Bearer <Token>`。
- **基礎資料**：保母已建立至少一個服務方案。

## Scenario 1: 多人同時送單 (媒合式模型)
* **Given**: 保母 A 的方案「專業餵食」在 2026-06-01 僅剩 1 個名額。
* **When**: 飼主甲與飼主乙同時送出該日期的預約申請（帶入不同 Idempotency-Key）。
* **Then**: 系統應允許兩張訂單皆成功建立，狀態皆為 `PENDING`。
* **自動化對應**: `BookingServiceTest.ts005_01_should_AllowMultiplePendingOrders_When_ConcurrentSubmission()`

## Scenario 2: 保母併發接單 (配額鎖定)
* **Given**: 存在兩筆對應同一日期、同一保母的 `PENDING` 訂單。
* **When**: 保母嘗試同時確認這兩筆訂單（正式接單路徑 `ConfirmOrderService.confirmOrder()` = `POST /api/orders/{id}/confirm`，兩個執行緒以 `CyclicBarrier` 對齊起跑，換 5 個日期各跑一輪）。
* **Then**: 系統應利用 Advisory Lock 確保僅有一筆能成功變更為 `PENDING_PAYMENT`，另一筆應拋出 `CapacityFullException`（不得出現其他例外）；只有成功那筆有快照；兩筆的行程都維持 `PENDING`（SD-008，接單不改 visit 狀態）。
* **邊界（2b）**：`dailyCapacity=2`、三筆同日單同時接 → 恰好兩筆成功、一筆 `CapacityFullException`，`countBookedVisitsBySitterIdAndDate` = 2。
* **自動化對應**: `BookingServiceTest.ts005_02_should_PreventOverselling_When_SitterConcurrentConfirm()`、`ts005_02b_should_AcceptUpToDailyCapacity_When_ConcurrentConfirmExceedsCapacity()`

## Scenario 3: 複合方案預約驗證 (Multi-Plan / Multi-Visit)
* **Given**: 保母有方案 A ($500) 與方案 B ($800)。
* **When**: 飼主送出預約申請，包含 Item 1 (方案 A, 1/1-1/2, 每日 2 次) 與 Item 2 (方案 B, 1/3, 每日 1 次)。
* **Then**: 總額應正確計算為 `(500 * 2 * 2) + (800 * 1 * 1) = 2800`，且資料庫應產生 5 筆對應的 `Visit` 記錄（每筆皆帶有正確的 `plan_id` 與日期）。
* **自動化對應**: `BookingServiceTest.ts005_03_should_CalculateTotalCorrectly_When_MultiPlanSubmitted()`

## Scenario 4: Zero-Trust Pricing — 前端試算金額與資料庫現價不符應拒絕
* **Given**: 方案現價為 $500。
* **When**: 飼主送出的 `expectedTotalAmount` 與後端依資料庫現價重算的金額不一致（例如前端頁面快取了舊報價，保母已調整方案價格）。
* **Then**: 系統回傳 400 `PRICING_MISMATCH`，不建立訂單。
* **自動化狀態**: ⬜ 未自動化。目前 `BookingServiceTest` 僅涵蓋金額計算「正確」路徑（Scenario 3），未有測試驗證金額「不符時拒絕」的分支，為已知待補項目。

## Scenario 5: 事前問卷必填題未作答應擋下送單 (PRD-004 AC-4)
* **Given**: 保母設有 1 題必填問卷題目。
* **When**: 飼主送出預約申請，未包含該題的作答值。
* **Then**: 系統拒絕送單，錯誤訊息附題目名稱，不建立訂單。
* **自動化對應**: `BookingServiceTest.should_RejectBooking_When_RequiredQuestionUnanswered()`

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 併發送出預約 | 多筆 PENDING 成功 | 檢查 `orders` 記錄是否存在多筆且 ID 不同。 |
| 2 | 併發確認接單 | 僅一勝，其餘敗 | **Advisory Lock**: 檢查接單時是否成功攔截。 |
| 3 | 檢查狀態變更 | 成功者轉為 `PENDING_PAYMENT` | 狀態機轉換正確性驗證。 |
| 4 | 飼主進入「選擇服務方案」頁 | 只顯示 `GET /api/sitters/{id}/plans` 回傳的方案，不得出現任何寫死的佔位方案 | 方案卡數量 = API 回傳數量、名稱逐一對上；頁面不含「尊榮包月」「此方案目前檔期已滿」（`frontend/e2e/client-booking.spec.ts` 迴歸 (P2)） |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [BookingServiceTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/BookingServiceTest.java)
- **執行指令**: `mvn test -Dtest=BookingServiceTest`
- **前端迴歸**: [client-booking.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/client-booking.spec.ts)「迴歸 (P2)」（mock API，`npx playwright test e2e/client-booking.spec.ts -g P2`）
- **mock E2E 前置（2026-10，Backlog 第 4 批 E1）**：`client-booking.spec.ts` 以假 token 進 Demo，必須先呼叫 `e2e/support/mockSession.ts` 的 `mockDemoSession()` 攔 AppHeader 每頁都打的通知 / KYC 狀態 API，並 mock `GET /api/owner/favorites`（預約頁愛心）。漏攔的 API 打到真後端會 401 → axios refresh 也 401 → 清掉 accessToken，下一次換頁被 `RequireAuth`（C1 起訂閱 location）導去 `/login?redirect=...`。這是測試 mock 不完整，不是產品 bug：真實使用者只有 refresh token 真的失效時才會被清 token 並導去登入。
- **最後驗證日期**: 2026-10-24

---

## 本測試發現並修復的真實 bug
- **選擇方案頁寫死「尊榮包月 $15,000/月・此方案目前檔期已滿」（2026-10，驗收 P2）**：`PublicBookingPage.tsx` 在真實方案清單後面固定多渲染一張註解為「檔期不足模擬」的灰色卡片，是 2026-05 設計稿/展示用的 mock 佔位，沒有對應任何資料。正式站每一位保母的下單頁都會出現這張卡，飼主會以為保母有包月方案、只是剛好額滿。PRD-005/SD-005 都沒有包月方案或「方案層級檔期已滿」的需求（SD-005 §5：檔期已滿於送單階段不阻擋，由保母接單時判斷），`ServicePlan` 也沒有計費週期欄位，因此直接移除，不改成資料驅動。迴歸測試見驗證矩陣第 4 步。
- **併發接單測試測的是死碼（2026-10，backlog F2；測試盲點，非產品超賣）**：`ts005_02` 原本呼叫 `BookingService.confirmBooking()`，但這個方法沒有任何 controller 呼叫，正式接單走的是 `ConfirmOrderService.confirmOrder()`（鎖的實作也不同：舊死碼用 `hashtext` 雙鍵、正式路徑用排序後的 bigint 鍵），而且死碼會把 visit 設成 `CONFIRMED`，違反 SD-008 狀態機。等於正式路徑的超賣保護完全沒有自動化覆蓋。改測正式路徑後：有鎖 → 綠（連跑 3 次皆綠）；暫時拿掉 `advisoryLockService.acquireLocks()` → `ts005_02`、`ts005_02b` 第 1 輪就紅（兩張都接成功），證明測試確實擋得住超賣、而正式路徑的保護是有效的。死碼與 `OrderRepository.acquireAdvisoryLock` 一併刪除；`ConfirmOrderService` 撈行程從 `findAll().stream().filter()` 全表掃描改 `findByOrderId`。
