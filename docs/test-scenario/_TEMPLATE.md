# TS-NNN: [情境名稱]

| 項目 | 內容 |
|------|------|
| 狀態 | Draft |
| 對應 PRD | PRD-NNN（旅程 TS 列出所有涉及的 PRD） |
| 對應 SD | SD-XXX（取 API、錯誤碼、testid） |
| 類型 | 模組（mock 後端）/ 旅程（打真後端） |
| 自動化路徑 | `frontend/e2e/xxx.spec.ts` 或 `frontend/e2e/journeys/xxx.spec.ts` |
| 自動化狀態 | ⬜ 未自動化 / ✅ 已自動化 |

> TS 只依 PRD 與 SD 撰寫，**不得參考程式碼**——照程式寫出的 TS 只會把 bug 驗證成規格。
> 類型選擇：要驗證狀態轉移、權限、冪等、跨角色互動 → 旅程（真後端）；只驗證單一畫面行為與版面 → 模組（mock，且必須 `assertNoUnmockedApi`）。

---

## 1. 前置資料

> 不寫死 UUID。每個 test 自己建資料、可獨立重跑。

| 代號 | 角色 | 建立方式 | 狀態 |
|------|------|---------|------|
| `owner` | OWNER | `provision-account`（`@e2e-journey.test`） | — |
| `sitter` | SITTER | `provision-account`，方案 PRO，KYC VERIFIED，已設收款帳號 | — |
| `order` | — | `owner` 向 `sitter` 預約 → `sitter` 報價 | `PENDING_PAYMENT` |

清除：`cleanup-e2e-journeys`（新的資料型態清不掉時列在 §5）。

## 2. 測試步驟

> 步驟 ID 用 `{前綴}-NN`，e2e spec 的 `step()` / `apiStep()` 標籤必須以同一 ID 開頭，e2e-acceptance 靠它對證據。
> 「API 斷言」寫具體值（HTTP 狀態、錯誤碼、欄位值），不寫「回應成功」。

| 步驟 | AC | 角色 | 操作 | 畫面預期 | API / 資料斷言 | 證據 |
|------|----|------|------|---------|---------------|------|
| P-01 | AC-1.1 | owner | 開啟 `/owner/orders/{order}`，點 `owner-order-btn-upload-proof` | 顯示免責勾選框、後五碼欄位 | — | 截圖 |
| P-02 | AC-1.1 | owner | 勾免責、填 `12345`、選圖、送出 | 狀態徽章 `owner-order-status-badge` 顯示「待核對」 | `GET /api/orders/{order}` → `status=PAID`、`hasPaymentProof=true` | 截圖 + API JSON |
| P-03 | EX-2 | owner | 以同一 `Idempotency-Key` 重送 | — | 200、回應與 P-02 相同；`order_logs` 只有 1 筆 `PAYMENT_PROOF_SUBMITTED` | API JSON |

## 3. 權限與例外

> 每個 PRD EX-n 至少一列；越權一律用另一個真實帳號打 API。

| 步驟 | EX / AC | 操作 | 預期 |
|------|---------|------|------|
| P-E1 | EX-1 | 另一個 owner 對 `order` 上傳憑證 | 403 `FORBIDDEN`，`order.status` 不變 |

## 4. 不在 E2E 覆蓋的項目

| AC / EX | 原因 | 由誰覆蓋 |
|---------|------|---------|
| EX-3（GCS 失敗） | 正式站無法注入故障 | 後端整合測試 `XxxIntegrationTest#gcsFailure` |
| （真實 Email 內容） | Resend 測試模式只寄專案擁有者 | 手動驗證 |

## 5. 已知限制 / 待補

## 6. 本測試發現並修復的真實 bug

| 日期 | 步驟 | 現象 | 修正 commit |
|------|------|------|------------|

## 7. 變更紀錄

| 日期 | 變更 | 原因 |
|------|------|------|
