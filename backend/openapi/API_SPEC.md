# Cat Sitter API Spec (V1)

對應主規格：`backend/openapi/openapi.yaml`

## Swagger 協作方式

在 `backend/openapi` 執行：

```bash
docker compose -f swagger-compose.yml up
```

打開：

- Swagger UI: `http://localhost:8081`
- 載入規格來源：`/openapi.yaml`（同資料夾掛載）

---

## Domain 與狀態

- `booking_status`: `PENDING | QUOTED | CONFIRMED | COMPLETED | CANCELLED`
- `payment_status`: `UNPAID | PROOF_UPLOADED | PAID | REFUNDED`
- `visit_status`: `SCHEDULED | IN_PROGRESS | DONE | CANCELLED`
- `trust_circle_status`: `ACTIVE | BLOCKED`
- `subscription_status`: `ACTIVE | EXPIRED | CANCELLED`

---

## API 清單與業務邏輯

### 1) Auth

- `POST /api/v1/auth/register`
  - 建立帳號 + 初始角色 profile（SITTER 或 CLIENT）
  - 回傳 `accessToken/refreshToken`
- `POST /api/v1/auth/login`
  - 憑證驗證成功後回 token
- `POST /api/v1/auth/refresh`
  - 以 refresh token 換新 access token
- `POST /api/v1/auth/logout`
  - refresh token 作廢
- `GET /api/v1/auth/me`
  - 取目前登入者 account + 關聯的所有 profiles (含 role, name, avatar 等)

### 2) 保母 Onboarding

- `GET/PUT /api/v1/sitters/me/profile`
  - 編輯服務區域、頭像、自介
- `POST/GET /api/v1/sitters/me/services`
  - 新增/查詢服務方案
- `PUT/DELETE /api/v1/sitters/me/services/{serviceId}`
  - 修改/下架方案
- `POST/GET /api/v1/sitters/me/questionnaires`
  - 新增/列出問卷題目
- `PUT /api/v1/sitters/me/questionnaires/{questionId}`
  - 修改問卷題目
- `PATCH /api/v1/sitters/me/questionnaires/reorder`
  - 調整題目排序
- `GET /api/v1/sitters/{sitterSlug}/booking-preview` (公開)
  - 給飼主的公開預約預覽（方案 + 問卷）
- `GET /api/v1/sitters/{sitterSlug}/availability/public` (公開)
  - 判斷是否可預約（額滿、訂閱過期、人工關閉）

### 3) 飼主與毛孩

- `GET/PUT /api/v1/clients/me/profile`
  - 飼主個資維護
- `POST/GET /api/v1/clients/me/pets`
  - 建立/列出毛孩檔案
- `GET/PUT/DELETE /api/v1/clients/me/pets/{petId}`
  - 查詢/修改/刪除毛孩

### 4) 預約與訂單生命週期

- `POST /api/v1/bookings`
  - 飼主建立預約（含時間、服務、毛孩、問卷答案）
  - `booking_status = PENDING`, `payment_status = UNPAID`
- `GET /api/v1/bookings/{bookingId}`
  - 查單
- `PATCH /api/v1/bookings/{bookingId}/reschedule`
  - 僅報價前可改期
- `GET /api/v1/sitters/me/bookings?status=...`
  - 保母依狀態拉評估單
- `POST /api/v1/bookings/{bookingId}/quote`
  - 保母報價（加價/折扣/備註）
  - `booking_status = QUOTED`
- `POST /api/v1/bookings/{bookingId}/payment-proofs`
  - 飼主上傳付款證明
  - `payment_status = PROOF_UPLOADED`
- `POST /api/v1/bookings/{bookingId}/payments/confirm-offline`
  - 保母確認到帳
  - `payment_status = PAID`, `booking_status = CONFIRMED`
- `POST /api/v1/bookings/{bookingId}/cancel`
  - 取消單（依角色與狀態決定可否取消）

### 5) 信任圈與轉介

- `GET /api/v1/sitters/me/trust-circle/search?q=...`
  - 搜尋可加入夥伴
- `POST /api/v1/sitters/me/trust-circle`
  - 建立信任圈關係
- `PATCH /api/v1/sitters/me/trust-circle/{partnerId}`
  - 更新關係狀態（ACTIVE/BLOCKED）
- `DELETE /api/v1/sitters/me/trust-circle/{partnerId}`
  - 移除關係
- `POST /api/v1/bookings/{bookingId}/referrals`
  - 將預約轉介給夥伴
- `GET /api/v1/bookings/{bookingId}/referrals`
  - 查轉介紀錄

### 6) 服務執行

- `GET /api/v1/sitters/me/visits?date=YYYY-MM-DD`
  - 保母當日行程
- `GET /api/v1/visits/{visitId}`
  - 單次服務詳情
- `PATCH /api/v1/visits/{visitId}/checklist`
  - 更新 SOP 勾選狀態
- `POST /api/v1/visits/{visitId}/checklist/custom-items`
  - 新增自訂項目（如臨時餵藥）
- `POST /api/v1/visits/{visitId}/media`
  - 上傳服務照片/影片 metadata
- `PUT /api/v1/visits/{visitId}/notes`
  - 保母備註
- `POST /api/v1/visits/{visitId}/complete`
  - 單次服務完成，`visit_status = DONE`
- `GET /api/v1/clients/me/visits/{visitId}/live-status`
  - 飼主即時查看 SOP 進度
- `POST /api/v1/orders/{orderId}/complete`
  - 全部 visit 完成後，飼主結案
  - `booking_status = COMPLETED`

### 7) 收款、訂閱、通知

- `GET /api/v1/sitters/me/payouts?month=YYYY-MM`
  - 月收款摘要（含已付款訂單）
- `GET /api/v1/sitters/me/subscription`
  - 訂閱方案狀態
- `POST /api/v1/sitters/me/subscription/checkout`
  - 建立續約/升級結帳會話 (導向 PayUni)
- `POST /api/v1/payments/payuni/webhook` (公開)
  - PayUni 金流回調入口；驗證 Token 並更新訂閱狀態
- `POST /api/v1/payments/payuni/mock-callback` (Internal/Testing)
  - 模擬 PayUni 回傳流程，方便開發測試
- `GET /api/v1/notifications`
  - 拉通知清單
- `PATCH /api/v1/notifications/{notificationId}/read`
  - 已讀

---

## 角色授權矩陣（實作準則）

- `SITTER only`: `sitters/me/**`, `bookings/{id}/quote`, `bookings/{id}/payments/confirm-offline`, `bookings/{id}/referrals`, `sitters/me/payouts`, `sitters/me/subscription/**`
- `CLIENT only`: `clients/me/**`, `bookings`(create), `bookings/{id}/payment-proofs`, `clients/me/visits/{visitId}/live-status`, `orders/{id}/complete`
- `BOTH`（但需 ownership 檢查）: `bookings/{id}`, `bookings/{id}/cancel`
- `PUBLIC`: `sitters/{slug}/booking-preview`, `sitters/{slug}/availability/public`, `payments/webhooks/ecpay`

---

## 技術落地注意（和 skill 對齊）

- Controller 保持 thin，只做 `@Valid` + authz，商業邏輯放 Service。
- 付款 webhook 必須做冪等（`payment_webhook_events` unique key）。
- API 錯誤格式統一 `ErrorResponse`，不要回堆疊訊息。
- 所有時間欄位維持 UTC ISO-8601；DB 仍用 `timestamptz`。
- 建議 PR gate：`mvn verify` + dependency CVE scan + OpenAPI lint。
