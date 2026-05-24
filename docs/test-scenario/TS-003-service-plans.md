# TS-003: 保母服務方案設定 (Service Plans)

| 項目 | 內容 |
| :--- | :--- |
| **對應需求** | PRD-003 / SD-003 |
| **測試類型** | ✅ 功能測試 / ✅ 安全測試 / ✅ 彈性與補償測試 |
| **優先級** | P0 (Critical) |
| **自動化狀態** | ⬜ 未自動化 (0/16 Scenarios) |
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

---

## Scenario 2: 保母成功編輯服務方案 (Happy Path)

* **Given**: DB 中已存在保母 A 且 `version = 0` 的方案（`planId = P1`）。
* **When**: 保母 A 呼叫 `PUT /api/sitter/plans/P1`，Body 帶有 `version = 0` 且修改內容。
* **Then**: 返回 200 OK，DB 中的 `version` 推進為 `1`，且 `care_logs` 寫入一筆 `UPDATE_SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | PUT 編輯方案 | 返回 200 OK，`message="修改成功"`，回傳最新資料 | DB `version` 由 0 遞增為 1；欄位內容更新成功 |
| 2 | 驗證操作日誌 | — | `care_logs` 新增 1 筆，其 `action="SERVICE_PLAN_CRUD"`, `status="UPDATE_SUCCESS"`，`details` 記錄方案異動 |

---

## Scenario 3: 編輯服務方案樂觀鎖衝突 (409 VERSION_CONFLICT)

* **Given**: DB 中已存在方案 P1，當前資料庫的 `version = 1`。
* **When**: 保母 A 呼叫 `PUT /api/sitter/plans/P1` 編輯，但 Body 中傳入舊版本號 `version = 0`。
* **Then**: 返回 409 Conflict，`error = "VERSION_CONFLICT"`。DB 的 `version` 維持 1，資料無異動。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | PUT 傳入 version = 0 編輯 | 返回 409 Conflict，`error="VERSION_CONFLICT"` | DB 資料無任何變更，`version` 依然為 1，`ObjectOptimisticLockingFailureException` 被正確攔截 |

---

## Scenario 4: 保母邏輯刪除服務方案 (Happy Path)

* **Given**: DB 中已存在方案 P1。
* **When**: 保母 A 呼叫 `DELETE /api/sitter/plans/P1`。
* **Then**: 返回 200 OK，DB 中的 `is_deleted` 被更新為 `true`；`care_logs` 成功寫入一筆 `DELETE_SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | DELETE 刪除方案 P1 | 返回 200 OK，`message="刪除成功"` | DB 中 `service_plans.is_deleted` 變為 `true`（物理資料未被刪除，以利歷史訂單回溯） |
| 2 | 驗證操作日誌 | — | `care_logs` 新增 1 筆，其 `action="SERVICE_PLAN_CRUD"`, `status="DELETE_SUCCESS"` |

---

## Scenario 5: 保母方案排序調整

* **Given**: DB 中已存在保母 A 的兩個方案：P1 (`sort_order = 0`)、P2 (`sort_order = 1`)。
* **When**: 保母 A 呼叫 `POST /api/sitter/plans/sort`，傳入陣列 `planIds = [P2, P1]`。
* **Then**: 返回 200 OK，DB 中 P2 的 `sort_order` 更新為 `0`，P1 的 `sort_order` 更新為 `1`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 傳入排序陣列 [P2, P1] | 返回 200 OK，`message="修改成功"` | DB 中 `service_plans` 記錄批次更新：P2.sort_order = 0, P1.sort_order = 1 |

---

## Scenario 6: 價格無效校驗拒絕 (400 INVALID_PARAMETER)

* **Given**: 保母 A 已登入。
* **When**: 保母 A 呼叫 `POST /api/sitter/plans` 嘗試建立方案，傳入 `price = -100` 或 `price = 0`。
* **Then**: 返回 400 Bad Request，`error = "INVALID_PARAMETER"`，DB 無新增記錄。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 價格 = -100 建立方案 | 返回 400 Bad Request，`error="INVALID_PARAMETER"` | `IllegalArgumentException` 或 `MethodArgumentNotValidException` 被拋出並由 `GlobalExceptionHandler` 攔截，DB 無新增資料 |
| 2 | POST 價格 = 0 建立方案 | 返回 400 Bad Request，`error="INVALID_PARAMETER"` | 同上 |

---

## Scenario 7: 非專業版保母設定日期區間遭拒 (403 AUTH_PLAN_LIMIT)

* **Given**: 保母 B 登入，其訂閱方案為 `FREE`。
* **When**: 保母 B 呼叫 `POST /api/sitter/plans`，Body 中包含了 `startDate = "2026-06-01"` 與 `endDate = "2026-08-31"`。
* **Then**: 返回 403 Forbidden，`error = "AUTH_PLAN_LIMIT"`，DB 無新增記錄。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 帶日期區間建立方案 | 返回 403 Forbidden，`error="AUTH_PLAN_LIMIT"` | `ServicePlanService` 拋出 `AuthPlanLimitException` 並被全域攔截；DB 無新增資料 |

---

## Scenario 8: 專業版以上保母成功設定生效日期區間 (Happy Path)

* **Given**: 保母 C 登入，其訂閱方案為 `PRO`（或 `PREMIUM`）。
* **When**: 保母 C 呼叫 `POST /api/sitter/plans`，Body 中包含了 `startDate = "2026-06-01"` 與 `endDate = "2026-08-31"`。
* **Then**: 返回 200 OK，DB 成功儲存且包含 `start_date` 與 `end_date` 日期欄位；`care_logs` 寫入一筆 `CREATE_SUCCESS`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 帶日期區間建立方案 | 返回 200 OK，包含 `code=200` | DB 成功新增 1 筆；`start_date = 2026-06-01`, `end_date = 2026-08-31` |
| 2 | 驗證操作日誌 | — | `care_logs` 新增 1 筆，其 `action="SERVICE_PLAN_CRUD"`, `status="CREATE_SUCCESS"` |

---

## Scenario 9a: 飼主端 Lazy Evaluation 生效日期區間過濾 — 未來方案隱藏

* **Given**: 保母 C 名下有兩個方案：P1 (常態無日期限制)、P2 (開放預約起訖日為 `"2026-06-01"` ~ `"2026-08-31"`)。系統當前時間為 `2026-05-24`。
* **When**: 飼主呼叫 `GET /api/sitters/{sitterId}/plans` 查詢保母 C 的方案。
* **Then**: 返回 200 OK，列表中僅包含 P1，P2 方案被自動排除隱藏。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | GET 查詢保母 C 的方案 | 返回 200 OK，列表大小為 1 | 列表中只回傳 `P1`；`P2` 因 `currentDate (2026-05-24) < startDate (2026-06-01)` 被排除 |

---

## Scenario 9b: 飼主端 Lazy Evaluation 生效日期區間過濾 — 逾期方案隱藏

* **Given**: 保母 C 名下有兩個方案：P1 (常態無日期限制)、P3 (開放預約起訖日為 `"2026-03-01"` ~ `"2026-05-20"`)。系統當前時間為 `2026-05-24`。
* **When**: 飼主呼叫 `GET /api/sitters/{sitterId}/plans` 查詢保母 C 的方案。
* **Then**: 返回 200 OK，列表中僅包含 P1，P3 方案被自動排除隱藏。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | GET 查詢保母 C 的方案 | 返回 200 OK，列表大小為 1 | 列表中只回傳 `P1`；`P3` 因 `currentDate (2026-05-24) > endDate (2026-05-20)` 被排除 |

---

## Scenario 9c: 飼主端 Lazy Evaluation 生效日期區間過濾 — 有效區間方案顯示

* **Given**: 保母 C 名下有兩個方案：P1 (常態無日期限制)、P4 (開放預約起訖日為 `"2026-05-01"` ~ `"2026-06-30"`)。系統當前時間為 `2026-05-24`。
* **When**: 飼主呼叫 `GET /api/sitters/{sitterId}/plans` 查詢保母 C 的方案。
* **Then**: 返回 200 OK，列表同時包含 P1 與 P4。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | GET 查詢保母 C 的方案 | 返回 200 OK，列表大小為 2 | 列表中同時包含 `P1` 與 `P4` (在區間內) ；按 `sort_order` 排序 |

---

## Scenario 10: 預約送單日期超出方案生效區間拒絕 (422 PLAN_NOT_IN_RANGE)

* **Given**: 方案 P4 生效區間為 `"2026-05-01"` ~ `"2026-06-30"`。
* **When**: 飼主呼叫 `POST /api/bookings` 送出預約。
* **Then**: 當預約日期小於 `startDate` 或大於 `end_date` 時，均返回 422 Unprocessable Entity，`error = "PLAN_NOT_IN_RANGE"`。預約建立失敗，DB 無新增訂單。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | POST 建立 4/15 的預約 (過早) | 返回 422 Unprocessable Entity，`error="PLAN_NOT_IN_RANGE"` | `ServicePlanException` 拋出；因 `2026-04-15 < startDate (2026-05-01)` 阻守；DB 無新訂單 |
| 2 | POST 建立 7/5 的預約 (過晚) | 返回 422 Unprocessable Entity，`error="PLAN_NOT_IN_RANGE"` | `ServicePlanException` 拋出；因 `2026-07-05 > endDate (2026-06-30)` 阻守；DB 無新訂單 |

---

## Scenario 11: 越權防禦 — 編輯/刪除非自己擁有的方案 (403)

* **Given**: 方案 P1 屬於保母 A，保母 B（非擁有者）已登入。
* **When**: 保母 B 嘗試編輯或刪除方案 P1。
* **Then**: 返回 403 Forbidden，DB 資料無異動。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | 保母 B 呼叫 PUT 編輯方案 P1 | 返回 403 Forbidden | IDOR 權限攔截成功；DB 資料無更動 |
| 2 | 保母 B 呼叫 DELETE 刪除方案 P1 | 返回 403 Forbidden | 同上 |

---

## Scenario 12: 編輯/刪除不存在的方案編號防禦 (404 PLAN_NOT_FOUND)

* **Given**: DB 中不存在 `planId = P99`。
* **When**: 保母 A 呼叫 `PUT` 或 `DELETE` 對方案 P99 操作。
* **Then**: 返回 404 Not Found，`error = "PLAN_NOT_FOUND"`。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | PUT 編輯方案 P99 | 返回 404 Not Found，`error="PLAN_NOT_FOUND"` | `ServicePlanException` 拋出與攔截，動態讀取 HTTP 404 |
| 2 | DELETE 刪除方案 P99 | 返回 404 Not Found，`error="PLAN_NOT_FOUND"` | 同上 |

---

## Scenario 13: 保母後台查詢方案列表 (不限期過濾)

* **Given**: 保母 C 名下有三個方案：P1 (常態)、P2 (未來：2026-06-01 開放)、P3 (已過期：2026-05-20 過期)，且 `is_deleted = false`。
* **When**: 保母 C 呼叫 `GET /api/sitter/plans` 查詢自己所有的方案。
* **Then**: 返回 200 OK，列表大小為 3（回傳所有未邏輯刪除的方案，不進行生效日期過濾）。

| Step | Action | Functional Result | Technical Metric (NFR) |
| :--- | :--- | :--- | :--- |
| 1 | GET 查詢保母 C 後台方案 | 返回 200 OK，列表大小為 3 | 列表完整包含 `P1`、`P2`、`P3`；無 Lazy Evaluation 日期過濾 |

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
