# TS-003: 保母服務方案設定 (Service Plans)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-003 / SD-003 |
| **測試類型** | ✅ 功能測試 / ✅ 安全測試 / ✅ 彈性與補償測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | 🟢 16/17 已實作（Scenario 15 待補；此列原標「0/17 未自動化」為文件過時未同步，`ServicePlanControllerTest` 內 `ts003_01`~`ts003_14`（含 9a/9b/9c）皆已存在對應測試方法，經比對後修正） |
| **自動化路徑** | `backend/src/test/java/com/petsitter/interfaces/controller/ServicePlanControllerTest.java` |

---

## 0. 前置條件 (Prerequisites)
- **認證狀態**：所有參與測試之使用者（Sitter/Owner）皆須已登入，JWT 有效且角色符合。
- **時區標準**：所有日期欄位皆符合 `DATE` 型別格式（`YYYY-MM-DD`），以 `Asia/Taipei` 為準判定。
- **測試隔離**：每個 Scenario 執行完畢後須清除測試資料，確保相互隔離。

---

## Scenario 1: 保母成功建立常態服務方案 (Happy Path)

* **Given**: 保母 Sitter A 已登入，目前名下無任何方案。
* **When**: 保母呼叫 `POST /api/sitter/plans`，傳入常態方案（`startDate` 與 `endDate` 為 null，價格大於 0）。
* **Then**: 返回 200 OK，DB 建立一筆 `service_plans` 且 `version = 0`；`care_logs` 成功寫入一筆 `CREATE_SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 建立常態方案 | 返回 200 OK，包含 `code=200`，`message="新增成功"` | DB `service_plans` 新增 1 筆；`version = 0`，`price` 在 DB 儲存為 `Long` 整數 |
| 2 | 驗證操作日誌 | — | `care_logs` 新增 1 筆，其 `action="SERVICE_PLAN_CRUD"`, `status="CREATE_SUCCESS"`，`details` 內含 `planId` |
| **自動化對應** | `ServicePlanControllerTest.ts003_01_should_CreateNormalServicePlanSuccessfully()`；mock E2E `service-plans.spec.ts`「保母成功查詢方案列表與新增常態方案」（從 Demo 進入、填表、儲存後卡片，逐步截圖） | — | — |

---

## Scenario 1b: 方案沒有每日容量欄位 (2026-10，backlog 第 7 批)

* **Given**: 產品決策「系統不做接單容量控管」（PRD-003 / PRD-005 / PRD-006），方案已移除 `dailyCapacity`。
* **When**: 舊前端仍送 `dailyCapacity: 2` 呼叫 `POST /api/sitter/plans`；之後 `GET /api/sitter/plans`。
* **Then**: 照常 200 建立（Jackson 忽略未知欄位，部署過渡期舊前端不會 400）；回應與列表都**沒有** `dailyCapacity`；DB 舊欄位 `daily_capacity` 落 `DEFAULT 1`、`information_schema` 顯示可為 NULL（`V20261024_02`）。前端新增方案表單沒有 `sitter-plan-input-capacity`、送出 body 不帶 `dailyCapacity`、方案卡不顯示「限額 N 次/日」。
* **自動化對應**: `ServicePlanControllerTest.ts003_01b_should_NotExposeDailyCapacity_When_PlanCreated()`；mock E2E `service-plans.spec.ts`（新增方案步驟 + `apiStep` 驗 POST body）、`plan-default-tasks.spec.ts`、`sitter-plan-card-layout.spec.ts`；Journey A 步驟 7。

---

## Scenario 2: 保母成功編輯服務方案 (Happy Path)

* **Given**: DB 中已存在保母 A 且 `version = 0` 的方案（`planId = P1`）。
* **When**: 保母 A 呼叫 `PUT /api/sitter/plans/P1`，Body 帶有 `version = 0` 且修改內容。
* **Then**: 返回 200 OK，DB 中的 `version` 推進為 `1`，且 `care_logs` 寫入一筆 `UPDATE_SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | PUT 編輯方案 | 返回 200 OK，`message="修改成功"`，回傳最新資料 | DB `version` 由 0 遞增為 1；欄位內容更新成功 |
| 2 | 驗證操作日誌 | — | `care_logs` 新增 1 筆，其 `action="SERVICE_PLAN_CRUD"`, `status="UPDATE_SUCCESS"`，`details` 記錄方案異動 |
| **自動化對應** | `ServicePlanControllerTest.ts003_02_should_UpdateServicePlanSuccessfully()`；mock E2E `service-plans.spec.ts`「保母編輯方案與預設任務動態刪減」（編輯前 / 刪減任務後 / 儲存後卡片截圖 + `apiStep` 驗 PUT body） | — | — |

---

## Scenario 3: 編輯服務方案樂觀鎖衝突 (409 MSG_DATA_CONCURRENCY_CONFLICT)

* **Given**: DB 中已存在方案 P1，當前資料庫的 `version = 1`。
* **When**: 保母 A 呼叫 `PUT /api/sitter/plans/P1` 編輯，但 Body 中傳入舊版本號 `version = 0`。
* **Then**: 返回 409 Conflict，`error = "MSG_DATA_CONCURRENCY_CONFLICT"`。DB 的 `version` 維持 1，資料無異動。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | PUT 傳入 version = 0 編輯 | 返回 409 Conflict，`error="MSG_DATA_CONCURRENCY_CONFLICT"` | DB 資料無任何變更，`version` 依然為 1，`ObjectOptimisticLockingFailureException` 被 `GlobalExceptionHandler` 全域攔截（此為全專案共用慣例，非本方案獨有錯誤碼） |
| **自動化對應** | `ServicePlanControllerTest.ts003_03_should_ThrowConflictException_When_OptimisticLockingFailure()`；mock E2E `service-plans.spec.ts`「樂觀鎖衝突 409」（表單紅字截圖） | — | — |

---

## Scenario 4: 保母邏輯刪除服務方案 (Happy Path)

* **Given**: DB 中已存在方案 P1。
* **When**: 保母 A 呼叫 `DELETE /api/sitter/plans/P1`。
* **Then**: 返回 200 OK，DB 中的 `is_deleted` 被更新為 `true`；`care_logs` 成功寫入一筆 `DELETE_SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | DELETE 刪除方案 P1 | 返回 200 OK，`message="刪除成功"` | DB 中 `service_plans.is_deleted` 變為 `true`（物理資料未被刪除，以利歷史訂單回溯） |
| 2 | 驗證操作日誌 | — | `care_logs` 新增 1 筆，其 `action="SERVICE_PLAN_CRUD"`, `status="DELETE_SUCCESS"` |
| **自動化對應** | `ServicePlanControllerTest.ts003_04_should_DeleteServicePlanLogically()`；mock E2E `service-plans.spec.ts`「方案排序調整與下架功能」（`trackDialogs`+`expectDialog` 接手下架確認框，下架後卡片保留並標示已下架） | — | — |

---

## Scenario 5: 保母方案排序調整

* **Given**: DB 中已存在保母 A 的兩個方案：P1 (`sort_order = 0`)、P2 (`sort_order = 1`)。
* **When**: 保母 A 呼叫 `POST /api/sitter/plans/sort`，傳入陣列 `planIds = [P2, P1]`。
* **Then**: 返回 200 OK，DB 中 P2 的 `sort_order` 更新為 `0`，P1 的 `sort_order` 更新為 `1`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 傳入排序陣列 [P2, P1] | 返回 200 OK，`message="修改成功"` | DB 中 `service_plans` 記錄批次更新：P2.sort_order = 0, P1.sort_order = 1 |
| **自動化對應** | `ServicePlanControllerTest.ts003_05_should_SortServicePlansSuccessfully()`；mock E2E `service-plans.spec.ts`「方案排序調整與下架功能」（排序前後截圖 + `apiStep` 驗送出 `[plan-2, plan-1]`） | — | — |

---

## Scenario 6: 價格無效校驗拒絕 (400 INVALID_PARAMETER)

* **Given**: 保母 A 已登入。
* **When**: 保母 A 呼叫 `POST /api/sitter/plans` 嘗試建立方案，傳入 `price = -100` 或 `price = 0`。
* **Then**: 返回 400 Bad Request，`error = "INVALID_PARAMETER"`，DB 無新增記錄。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 價格 = -100 建立方案 | 返回 400 Bad Request，`error="INVALID_PARAMETER"` | `IllegalArgumentException` 或 `MethodArgumentNotValidException` 被拋出並由 `GlobalExceptionHandler` 攔截，DB 無新增資料 |
| 2 | POST 價格 = 0 建立方案 | 返回 400 Bad Request，`error="INVALID_PARAMETER"` | 同上 |
| **自動化對應** | `ServicePlanControllerTest.ts003_06_should_RejectInvalidPrice()` | — | — |

---

## Scenario 7: 非專業版保母設定日期區間遭拒 (403 AUTH_PLAN_LIMIT)

* **Given**: 保母 B 登入，其訂閱方案為 `FREE`。
* **When**: 保母 B 呼叫 `POST /api/sitter/plans`，Body 中包含了 `startDate = "2026-06-01"` 與 `endDate = "2026-08-31"`。
* **Then**: 返回 403 Forbidden，`error = "AUTH_PLAN_LIMIT"`，DB 無新增記錄。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 帶日期區間建立方案 | 返回 403 Forbidden，`error="AUTH_PLAN_LIMIT"` | `ServicePlanService` 拋出 `AuthPlanLimitException` 並被全域攔截；DB 無新增資料 |
| **自動化對應** | `ServicePlanControllerTest.ts003_07_should_RejectDateLimit_When_FreeSitter()`；mock E2E `service-plans.spec.ts`「SaaS 日期區間 403」（表單紅字截圖） | — | — |

---

## Scenario 8: 專業版以上保母成功設定生效日期區間 (Happy Path)

* **Given**: 保母 C 登入，其訂閱方案為 `PRO`（或 `PREMIUM`）。
* **When**: 保母 C 呼叫 `POST /api/sitter/plans`，Body 中包含了 `startDate = "2026-06-01"` 與 `endDate = "2026-08-31"`。
* **Then**: 返回 200 OK，DB 成功儲存且包含 `start_date` 與 `end_date` 日期欄位；`care_logs` 寫入一筆 `CREATE_SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 帶日期區間建立方案 | 返回 200 OK，包含 `code=200` | DB 成功新增 1 筆；`start_date = 2026-06-01`, `end_date = 2026-08-31` |
| 2 | 驗證操作日誌 | — | `care_logs` 新增 1 筆，其 `action="SERVICE_PLAN_CRUD"`, `status="CREATE_SUCCESS"` |
| **自動化對應** | `ServicePlanControllerTest.ts003_08_should_AllowDateLimit_When_ProSitter()` | — | — |

---

## Scenario 9a: 飼主端 Lazy Evaluation 生效日期區間過濾 — 未來方案隱藏

* **Given**: 保母 C 名下有兩個方案：P1 (常態無日期限制)、P2 (開放預約起訖日為 `"2026-06-01"` ~ `"2026-08-31"`)。系統當前時間為 `2026-05-24`。
* **When**: 飼主呼叫 `GET /api/sitters/{sitterId}/plans` 查詢保母 C 的方案。
* **Then**: 返回 200 OK，列表中僅包含 P1，P2 方案被自動排除隱藏。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | GET 查詢保母 C 的方案 | 返回 200 OK，列表大小為 1 | 列表中只回傳 `P1`；`P2` 因 `currentDate (2026-05-24) < startDate (2026-06-01)` 被排除 |
| **自動化對應** | `ServicePlanControllerTest.ts003_09a_should_HideFuturePlanForOwner()` | — | — |

---

## Scenario 9b: 飼主端 Lazy Evaluation 生效日期區間過濾 — 逾期方案隱藏

* **Given**: 保母 C 名下有兩個方案：P1 (常態無日期限制)、P3 (開放預約起訖日為 `"2026-03-01"` ~ `"2026-05-20"`)。系統當前時間為 `2026-05-24`。
* **When**: 飼主呼叫 `GET /api/sitters/{sitterId}/plans` 查詢保母 C 的方案。
* **Then**: 返回 200 OK，列表中僅包含 P1，P3 方案被自動排除隱藏。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | GET 查詢保母 C 的方案 | 返回 200 OK，列表大小為 1 | 列表中只回傳 `P1`；`P3` 因 `currentDate (2026-05-24) > endDate (2026-05-20)` 被排除 |
| **自動化對應** | `ServicePlanControllerTest.ts003_09b_should_HideExpiredPlanForOwner()` | — | — |

---

## Scenario 9c: 飼主端 Lazy Evaluation 生效日期區間過濾 — 有效區間方案顯示

* **Given**: 保母 C 名下有兩個方案：P1 (常態無日期限制)、P4 (開放預約起訖日為 `"2026-05-01"` ~ `"2026-06-30"`)。系統當前時間為 `2026-05-24`。
* **When**: 飼主呼叫 `GET /api/sitters/{sitterId}/plans` 查詢保母 C 的方案。
* **Then**: 返回 200 OK，列表同時包含 P1 與 P4。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | GET 查詢保母 C 的方案 | 返回 200 OK，列表大小為 2 | 列表中同時包含 `P1` 與 `P4` (在區間內) ；按 `sort_order` 排序 |
| **自動化對應** | `ServicePlanControllerTest.ts003_09c_should_ShowActivePlanForOwner()` | — | — |

---

## Scenario 10: 預約送單日期超出方案生效區間拒絕 (422 PLAN_NOT_IN_RANGE)

* **Given**: 方案 P4 生效區間為 `"2026-05-01"` ~ `"2026-06-30"`。
* **When**: 飼主呼叫 `POST /api/orders/booking` 送出預約（原文件誤寫為 `/api/bookings`，已依 `OrderController` 實際掛載路徑修正）。
* **Then**: 當預約日期小於 `startDate` 或大於 `end_date` 時，均返回 422 Unprocessable Entity，`error = "PLAN_NOT_IN_RANGE"`。預約建立失敗，DB 無新增訂單。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 建立 4/15 的預約 (過早) | 返回 422 Unprocessable Entity，`error="PLAN_NOT_IN_RANGE"` | `ServicePlanException` 拋出；因 `2026-04-15 < startDate (2026-05-01)` 阻守；DB 無新訂單 |
| 2 | POST 建立 7/5 的預約 (過晚) | 返回 422 Unprocessable Entity，`error="PLAN_NOT_IN_RANGE"` | `ServicePlanException` 拋出；因 `2026-07-05 > endDate (2026-06-30)` 阻守；DB 無新訂單 |
| **自動化對應** | `ServicePlanControllerTest.ts003_10_should_RejectBooking_When_DateOutOfRange()` | — | — |

---

## Scenario 11: 越權防禦 — 編輯/刪除非自己擁有的方案 (403)

* **Given**: 方案 P1 屬於保母 A，保母 B（非擁有者）已登入。
* **When**: 保母 B 嘗試編輯或刪除方案 P1。
* **Then**: 返回 403 Forbidden，DB 資料無異動。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 保母 B 呼叫 PUT 編輯方案 P1 | 返回 403 Forbidden | IDOR 權限攔截成功；DB 資料無更動 |
| 2 | 保母 B 呼叫 DELETE 刪除方案 P1 | 返回 403 Forbidden | 同上 |
| **自動化對應** | `ServicePlanControllerTest.ts003_11_should_RejectEditAndDelete_When_NotOwner()` | — | — |

---

## Scenario 12: 編輯/刪除不存在的方案編號防禦 (404 PLAN_NOT_FOUND)

* **Given**: DB 中不存在 `planId = P99`。
* **When**: 保母 A 呼叫 `PUT` 或 `DELETE` 對方案 P99 操作。
* **Then**: 返回 404 Not Found，`error = "PLAN_NOT_FOUND"`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | PUT 編輯方案 P99 | 返回 404 Not Found，`error="PLAN_NOT_FOUND"` | `ServicePlanException` 拋出與攔截，動態讀取 HTTP 404 |
| 2 | DELETE 刪除方案 P99 | 返回 404 Not Found，`error="PLAN_NOT_FOUND"` | 同上 |
| **自動化對應** | `ServicePlanControllerTest.ts003_12_should_Return404_When_PlanDoesNotExist()` | — | — |

---

## Scenario 13: 保母後台查詢方案列表 (不限期過濾)

* **Given**: 保母 C 名下有三個方案：P1 (常態)、P2 (未來：2026-06-01 開放)、P3 (已過期：2026-05-20 過期)，且 `is_deleted = false`。
* **When**: 保母 C 呼叫 `GET /api/sitter/plans` 查詢自己所有的方案。
* **Then**: 返回 200 OK，列表大小為 3（回傳所有未邏輯刪除的方案，不進行生效日期過濾）。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | GET 查詢保母 C 後台方案 | 返回 200 OK，列表大小為 3 | 列表完整包含 `P1`、`P2`、`P3`；無 Lazy Evaluation 日期過濾 |
| **自動化對應** | `ServicePlanControllerTest.ts003_13_should_ReturnAllPlansForSitterBackend()` | — | — |

---

## Scenario 14: 角色與認證門禁防護 (401/403)

* **Given**: 系統已啟動 API 角色攔截。
* **When**: 
  - 1. 飼主（角色為 `OWNER`）呼叫 `POST /api/sitter/plans` (保母專用寫入)。
  - 2. 未登入之訪客呼叫 `POST /api/sitter/plans`。
* **Then**: 
  - 1. 飼主呼叫返回 403 Forbidden。
  - 2. 訪客呼叫返回 401 Unauthorized。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | OWNER 角色呼叫 POST | 返回 403 Forbidden | Spring Security `JWTAuthFilter` 攔截，無權限寫入 |
| 2 | 訪客匿名呼叫 POST | 返回 401 Unauthorized | Spring Security 拒絕未認證請求 |
| **自動化對應** | `ServicePlanControllerTest.ts003_14_should_EnforceRoleAndAuthenticationGating()` | — | — |

---

## Scenario 15: 適用寵物類型須涵蓋 PRD-002 全部 8 種寵物（跨模組一致性）

* **Given**: 保母 A 已登入；飼主端建立寵物時可選擇 `CAT`/`DOG`/`BIRD`/`MOUSE`/`RABBIT`/`REPTILE`/`INSECT`/`OTHER` 共 8 種。
* **When**: 保母 A 於「服務方案設定」頁新增/編輯方案時，開啟「適用寵物類型」選項。
* **Then**: 選項清單應完整涵蓋上述 8 種，而非僅有 `CAT`/`DOG` 兩種——修正前的落差是保母養非貓狗寵物的飼主，永遠找不到方案可以預約。
* **技術背景**: `service_plans.applicable_pet_types` 為不受 DB CHECK 約束的 `JSONB` 陣列，此為純前端選項清單缺陷（`SitterPlans.tsx`），非後端驗證邏輯問題。
* **自動化狀態**: ⬜ 未自動化。`ServicePlanControllerTest`/`service-plans.spec.ts` 目前皆僅以 `["CAT"]` 作為測試資料，未驗證其餘 7 種類型的選取與送出，為已知待補項目。

## Scenario 16: 新增方案的預設 SOP 依適用寵物給 (2026-10，Backlog 第 3 批 U6 迴歸)

* **Given**: 保母 A 於「服務方案設定」點「新增方案」（預設勾選 `CAT`）。
* **When**: 取消 `CAT`、改勾 `DOG`；再加勾 `CAT`；手動修改任務後再切換寵物；送出。
* **Then**: 初始為貓咪模板；只勾 `DOG` 時為「基本餵食 / 散步遛狗 / 清理排泄物 / 更換新鮮水」，**不含**「清理砂盆」；多選取聯集；手動改過的任務不被覆蓋；`POST /api/sitter/plans` 的 `defaultTasks` 與畫面一致（SD-003 §0.1）。
* **自動化狀態**: ✅ `frontend/e2e/plan-default-tasks.spec.ts`（mock）；正式站由 Journey A 步驟 8 斷言公開方案 API 的 `defaultTasks`。
* **證據（2026-10-24，驗收 R2）**：表單在 modal 內自己捲動，整頁截圖只拍得到 modal 上半部；步驟 1~4 截圖前把「預設照護 SOP 工作項目」捲到 modal 中央（同時看得到適用寵物勾選與整串任務）。送出步驟改斷言 modal 關閉、列表出現新方案卡片；POST body 以 `apiStep` 留 JSON。過去第 5 張截圖是「目前尚無任何服務方案」：mock 的 `GET /api/sitter/plans` 永遠回 `[]`，送出後 invalidate 重抓就是空清單——是 mock 缺口不是時序，現在 POST 後 GET 會回新方案。

---

## 本測試發現並修復的真實 bug

**狗狗方案的預設 SOP 有「清理砂盆」（2026-10，驗收 N1 / Backlog 第 3 批 U6）**：`SitterPlans.openModal(null)` 不論勾選哪種寵物，預設任務一律寫死「基本餵食 / 清理砂盆 / 更換新鮮水」。Journey A 建立的 DOG 方案公開後，`defaultTasks` 仍是貓砂相關任務。修法與迴歸見 Scenario 16。

**方案卡價格溢出卡片右邊界（2026-10，驗收 N2 / Backlog 第 3 批 U7）**：方案名稱含無法斷行的長英數字串時，卡片左欄不縮、右側價格 `flex-shrink:0`，價格被擠出卡片。修法見 SD-003 §0.2；迴歸 `frontend/e2e/sitter-plan-card-layout.spec.ts`（逐張斷言價格區塊落在卡片內、卡片無水平溢出）。

**每日容量 `dailyCapacity` 是規格沒要求的設計，依產品決策移除（2026-10-24，backlog 第 7 批）**：SD 層自行加入的「每日接單容量限制」與 PRD-005（同日已有訂單不阻擋，由保母審核決定）、PRD-006（保母依自身檔期審核、最終決定權）矛盾，且前後兩批為了它補了 `CapacityGuard` 與「算趟還是算單」的待決策。產品決策為系統不做任何接單容量控管，`ServicePlan.dailyCapacity`、DTO 驗證、方案表單欄位與卡片徽章一併移除。迴歸見 Scenario 1b。
