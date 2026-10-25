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
* **Given**: 保母 A 的方案「專業餵食」在 2026-06-01 已有其他預約（系統不做容量控管，沒有「名額」概念）。
* **When**: 飼主甲與飼主乙同時送出該日期的預約申請（帶入不同 Idempotency-Key）。
* **Then**: 系統應允許兩張訂單皆成功建立，狀態皆為 `PENDING`。
* **自動化對應**: `BookingServiceTest.ts005_01_should_AllowMultiplePendingOrders_When_ConcurrentSubmission()`

## Scenario 2: 同一張單併發接單只成功一次（狀態轉移保護）
* **Given**: 一筆 `PENDING` 訂單。
* **When**: 兩個請求同時對**同一張**單原價接單（正式接單路徑 `ConfirmOrderService.confirmOrder()` = `POST /api/orders/{id}/confirm`，`CyclicBarrier` 對齊起跑，換 5 個日期各跑一輪）。
* **Then**: 恰好一個成功、另一個經 `GlobalExceptionHandler` 換算為 **409**（`STATE_CONFLICT`，訂單列 `SELECT ... FOR UPDATE` 讓後到者讀到 `PENDING_PAYMENT`）；訂單為 `PENDING_PAYMENT`、快照只有 1 筆；行程維持 `PENDING`（SD-008，接單不改 visit 狀態）。
* **2b（不做容量控管）**：同日三張不同的 `PENDING` 單同時接單 → **三張全部成功**、各自一筆快照（PRD-005 / PRD-006：同日已有訂單不阻擋，由保母決定；方案沒有 `dailyCapacity`）。
* **自動化對應**: `BookingServiceTest.ts005_02_should_AcceptOnce_When_SameOrderConfirmedConcurrently()`、`ts005_02b_should_AcceptAllSameDayOrders_When_ConcurrentConfirm()`

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

## Scenario 6: 送單回應契約與送出後導頁 (backlog F3)
* **Given**: 飼主完成預約摘要。
* **When**: 送出預約，後端回 `201 { orderId: <uuid> }`。
* **Then**: 前端導向 `/owner/orders/{orderId}`。若回應缺 `orderId`（或不是 UUID），前端 alert「預約已送出，但無法取得訂單編號，請到「我的訂單」確認預約狀態」並導向 `/owner/orders`，不得導去 `/owner/orders/[object Object]`、不得打 `GET /api/orders/[object Object]`。
* **自動化對應**: 後端契約 `BookingServiceTest.ts005_06_should_ReturnOrderIdObject_When_BookingCreated()`（201、body 只有 `orderId` 一個 key、無 `data`）；前端 `client-booking.spec.ts` TS-005-01/02 最後一步、`public-booking-questionnaire.spec.ts` 主流程最後一步斷言導向 `/owner/orders/a1023000-...`；`public-booking-questionnaire.spec.ts`「迴歸 (F3)」以舊錯誤格式 `{ code, message, data }` 驗證錯誤提示與導向列表。

## Scenario 5: 事前問卷必填題未作答應擋下送單 (PRD-004 AC-4)
* **Given**: 保母設有 1 題必填問卷題目。
* **When**: 飼主送出預約申請，未包含該題的作答值。
* **Then**: 系統拒絕送單，錯誤訊息附題目名稱，不建立訂單。
* **自動化對應**: `BookingServiceTest.should_RejectBooking_When_RequiredQuestionUnanswered()`

## Scenario 7: 送單的飼主身分取自 token（fix-batch11 I2，安全 / BOLA）
* **Given**: 保母 A 已認證且開放預約；飼主 A、飼主 B、保母 B。請求帶真的 JWT，走完整 `SecurityConfig` filter chain；body 刻意沿用舊前端格式並填 `ownerId=<飼主 A>`。
* **When**: (1) 飼主 B 的 token 送單；(2) 飼主 B 已被保母 A 全域黑名單（PRO）時以同樣 body 送單；(3) 保母 B 的 token 送單、未登入送單；(4) 飼主 A 本人送單。
* **Then**: (1) 201，訂單 `owner_id` 是飼主 B，飼主 A 名下沒有任何訂單（body 的 `ownerId` 被忽略）；(2) 403，沒有建單（黑名單以 token 身分判斷，不能冒用別的飼主繞過）；(3) 403 / 401，沒有建單；(4) 201，訂單屬於飼主 A。前端送出的 body 只有 `answers` / `expectedTotalAmount` / `items` / `sitterId`。
* **自動化對應**: `OrderIdentityFromTokenSecurityTest.booking_*`；前端 `public-booking-questionnaire.spec.ts` 主流程「預約 body 不帶 ownerId」。

## Scenario 8: 預約的 petIds 必須全屬 token 飼主（fix-batch12 J3，安全 / IDOR 預防）
* **Given**: 保母 A 已認證且開放預約；飼主 A 有兩隻毛孩、飼主 B 有一隻；另有飼主 A 已刪除的毛孩。請求帶真 JWT 走完整 filter chain。
* **When**: 飼主 A 送單，(1) `petIds` = [A 的毛孩, B 的毛孩]；(2) `petIds` = [A 已刪除的毛孩]、或亂數 UUID；(3) `petIds` = [A 的兩隻毛孩]。
* **Then**: (1) 403，沒有建單；(2) 400，沒有建單；(3) 201，`OrderItem.petIds` 原樣存入兩隻。
* **自動化對應**: `OrderIdentityFromTokenSecurityTest.booking_otherOwnersPet_403` / `booking_unknownOrDeletedPet_400` / `booking_ownPets_201`。

---

## 驗證矩陣
| 步驟 | 動作 | 預期功能結果 | 技術校驗 (DB/NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 併發送出預約 | 多筆 PENDING 成功 | 檢查 `orders` 記錄是否存在多筆且 ID 不同。 |
| 2 | 同一張單併發確認接單 | 僅一勝，另一個 409 | 訂單列 `SELECT ... FOR UPDATE`；快照 1 筆。不同張的同日單同時接 → 全部成功（不做容量控管）。 |
| 3 | 檢查狀態變更 | 成功者轉為 `PENDING_PAYMENT` | 狀態機轉換正確性驗證。 |
| 3.5 | 他人 / 錯角色送單、body 冒填 ownerId | 訂單一律掛在 token 身分名下；保母 403、未登入 401 | `@PreAuthorize("hasRole('OWNER')")` + `TokenContext`；`BookingRequest` 已無 `ownerId` 欄位（Scenario 7） |
| 3.6 | petIds 夾帶別人的 / 不存在的毛孩 | 403 / 400，不建單 | `BookingService.requirePetsOwnedBy`（Scenario 8） |
| 4 | 飼主進入「選擇服務方案」頁 | 只顯示 `GET /api/sitters/{id}/plans` 回傳的方案，不得出現任何寫死的佔位方案 | 方案卡數量 = API 回傳數量、名稱逐一對上；頁面不含「尊榮包月」「此方案目前檔期已滿」（`frontend/e2e/client-booking.spec.ts` 迴歸 (P2)） |

---

## 自動化實作追溯 (Traceability)
- **測試專案**: `backend`
- **測試類別**: [BookingServiceTest](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/test/java/com/petsitter/application/service/BookingServiceTest.java)
- **執行指令**: `mvn test -Dtest=BookingServiceTest,OrderIdentityFromTokenSecurityTest`
- **前端迴歸**: [client-booking.spec.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/e2e/client-booking.spec.ts)「迴歸 (P2)」（mock API，`npx playwright test e2e/client-booking.spec.ts -g P2`）
- **mock E2E 前置（2026-10，Backlog 第 4 批 E1）**：`client-booking.spec.ts` 以假 token 進 Demo，必須先呼叫 `e2e/support/mockSession.ts` 的 `mockDemoSession()` 攔 AppHeader 每頁都打的通知 / KYC 狀態 API，並 mock `GET /api/owner/favorites`（預約頁愛心）。漏攔的 API 打到真後端會 401 → axios refresh 也 401 → 清掉 accessToken，下一次換頁被 `RequireAuth`（C1 起訂閱 location）導去 `/login?redirect=...`。這是測試 mock 不完整，不是產品 bug：真實使用者只有 refresh token 真的失效時才會被清 token 並導去登入。
- **漏 mock 偵測（2026-10-24，backlog 第 6 批 N9）**：`mockDemoSession()` 加上 context 層 `/api/` catch-all（回 501 並記錄）後，`client-booking.spec.ts` 現形 3 組過去打到後端的請求：`GET /api/sitter/profile/{id}`、`GET /api/sitters/{id}/questions`（預約頁靠正式站種子保母的真實資料才渲染得出來）、送出後導去的 `GET /api/orders/{id}`（詳情頁 401 → 截圖是錯誤畫面）。三者已補 mock，導頁步驟另加斷言 `order-status-badge` 為「待評估」，且 `order-total-amount` 等於本次預約金額（`mockOwnerOrderDetail(page, id, status, totalAmount)`：TS-005-01 $3,200、TS-005-02 $5,200、問卷主流程 $500；驗收 R6，過去寫死 600 讓截圖金額與預約內容對不上）；`test.afterEach` 斷言沒有漏 mock（SD-FRONTEND-SPEC §4.1）。
- **最後驗證日期**: 2026-10-24

---

## 本測試發現並修復的真實 bug
- **選擇方案頁寫死「尊榮包月 $15,000/月・此方案目前檔期已滿」（2026-10，驗收 P2）**：`PublicBookingPage.tsx` 在真實方案清單後面固定多渲染一張註解為「檔期不足模擬」的灰色卡片，是 2026-05 設計稿/展示用的 mock 佔位，沒有對應任何資料。正式站每一位保母的下單頁都會出現這張卡，飼主會以為保母有包月方案、只是剛好額滿。PRD-005/SD-005 都沒有包月方案或「方案層級檔期已滿」的需求（SD-005 §5：檔期已滿於送單階段不阻擋，由保母接單時判斷），`ServicePlan` 也沒有計費週期欄位，因此直接移除，不改成資料驅動。迴歸測試見驗證矩陣第 4 步。
- **併發接單測試測的是死碼（2026-10，backlog F2；測試盲點，非產品超賣）**：`ts005_02` 原本呼叫 `BookingService.confirmBooking()`，但這個方法沒有任何 controller 呼叫，正式接單走的是 `ConfirmOrderService.confirmOrder()`（鎖的實作也不同：舊死碼用 `hashtext` 雙鍵、正式路徑用排序後的 bigint 鍵），而且死碼會把 visit 設成 `CONFIRMED`，違反 SD-008 狀態機。等於正式路徑的超賣保護完全沒有自動化覆蓋。改測正式路徑後：有鎖 → 綠（連跑 3 次皆綠）；暫時拿掉 `advisoryLockService.acquireLocks()` → `ts005_02`、`ts005_02b` 第 1 輪就紅（兩張都接成功），證明測試確實擋得住超賣、而正式路徑的保護是有效的。死碼與 `OrderRepository.acquireAdvisoryLock` 一併刪除；`ConfirmOrderService` 撈行程從 `findAll().stream().filter()` 全表掃描改 `findByOrderId`。（2026-10-24 backlog G1：鎖 + count 段落抽成 `CapacityGuard.lockAndCheck()`，原價接單與議價報價共用。**2026-10-24 backlog 第 7 批依產品決策整個移除容量控管**，`ts005_02/02b` 改寫為同一張單的狀態轉移保護，見下一條。）
- **送出預約後導去 `/owner/orders/[object Object]`（2026-10，backlog F3）**：`PublicBookingPage.tsx` 以 `res.orderId || res` 取訂單編號，回應格式不符時把整個物件塞進網址。真實後端回 `{ orderId }` 所以正式站沒踩到，但 `client-booking.spec.ts`、`public-booking-questionnaire.spec.ts` 的 mock 一直回錯誤格式 `{ code, message, data }`，trace 有 `GET /api/orders/[object%20Object]` 400，因為測試沒斷言送出後的頁面而沒紅。修正：移除 fallback、格式不符明確提示並導向訂單列表；兩支 mock 改成真實格式 201 `{ orderId }` 並補導頁斷言；後端補契約測試。舊前端跑「迴歸 (F3)」→ 紅（沒有提示、導去 `[object Object]`）。
- **移除容量鎖後，同一張單併發接單的後到者回 400 而非 409（2026-10-24，backlog 第 7 批）**：產品決策「系統不做接單容量控管」後移除 `CapacityGuard`（保母 + 日期 advisory lock + 鎖內 count）。改寫後的 `ts005_02`（同一張單兩個請求同時接單）在沒有其他保護時第 1 輪就紅：兩邊都讀到 `PENDING`，後到者 flush 時撞 `uk_order_snapshot_order_id` → `DataIntegrityViolationException` → `GlobalExceptionHandler` 回 **400 `DATA_ERROR`「資料處理異常」**（資料仍一致、只有一張快照，但回應錯誤，前端會顯示成資料錯誤而非「狀態已變更」）。原本的 advisory lock 只序列化了兩個請求，後到者拿的仍是鎖前讀到的舊實體，同樣會撞 unique。修法：`OrderRepository.findByIdAndSitterIdForUpdate`（`PESSIMISTIC_WRITE`），接單與報價都以此讀訂單，後到者讀到 `PENDING_PAYMENT` → 409 `STATE_CONFLICT`。修正後連跑 3 次皆綠。

- **預約頁保母卡片長名稱 + 愛心溢出（2026-10-24，驗收 N3 / backlog 第 6 批 G5）**：名稱欄 `flex: 1` 沒有 `minWidth: 0`，Journey 的長顯示名稱（含時間戳）把它撐到 249px，愛心被擠到殼外（x≈871，內容區右緣 811）。改 `minWidth: 0` + 名稱 `overflowWrap: anywhere`、頭像與愛心 `flexShrink: 0`。迴歸：`ui-layout-contrast.spec.ts` N3（名稱、愛心落在 `client-booking-sitter-card` 內，`<main>` 沒被撐寬）。
- **預約頁保母卡名稱被擠成 6 行、日期欄被擠成「2026 / 年 / 10/25」（2026-10-24，驗收 N14）**：N3 修好溢出，但頭像 80px、名稱、愛心仍擠同一列，名稱欄只剩約 100px；沒有頭像時指向外部 `via.placeholder.com`，載不到就是寫著「150 x 150」的灰圓。排程卡的「照護對象」區塊是 `width: 100%` 的第三個 flex 子元素卻沒有換行，跟日期欄搶同一列。修正：保母卡改成上列「頭像（64px，無頭像用名稱首字）+ 愛心」、下列名稱佔滿卡片寬；排程列 `flexWrap: wrap`、日期欄 `minWidth: 0`、照護對象 `flexBasis: 100%`。迴歸：`ui-layout-contrast.spec.ts` N14（名稱 ≤ 2 行、不打 placeholder 服務、照護對象在日期欄下方且不重疊、年份單行）。
- **任何已登入者都能以別的飼主名義下單（2026-10-25，fix-batch11 I2，安全 / BOLA）**：I1 修完後全後端掃描身分欄位時發現。`BookingService.createBooking` 的訂單飼主取自 request body 的 `ownerId`，`POST /api/orders/booking` 也沒有 `@PreAuthorize`；飼主 UUID 在保母端訂單詳情就看得到。保母或任何人都能替別的飼主建單（飼主收到莫名的預約、保母端看到假訂單），被保母黑名單的飼主也能冒用別人的 `ownerId` 繞過黑名單檢查（`isBlocked(sitterId, ownerId)` 用的是 body 的值）。修法：`BookingRequest` 移除 `ownerId`，`createBooking(ownerId, request)` 由 controller 傳入 `TokenContext.getUserId()`，端點加 `@PreAuthorize("hasRole('OWNER')")`；舊前端 body 多帶的 `ownerId` 被 Jackson 忽略。迴歸：Scenario 7。
- **預約的 petIds 沒有驗證歸屬（2026-10-25，第 11 批另外發現 #4 / fix-batch12 J3，安全）**：`BookingService` 原樣把 `items[].petIds` 存進 `OrderItem.petIds`，飼主可以把別人的毛孩 UUID（保母端訂單詳情、記事本都看得到）塞進自己的訂單；目前訂單詳情不依 petIds 回傳毛孩資料，但之後任何「照 petIds 顯示毛孩」的功能都會直接變成 IDOR。修法：建單前以 `PetRepository.findAllById` 檢查，不存在 / 已刪除 400、屬於別的飼主 403。迴歸：Scenario 8，舊程式碼 2 支紅（皆 201）。
