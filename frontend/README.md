# Frontend — WhiskerWatch PWA

React 19 + Vite 7 + Tailwind CSS 4 前端應用。

## 開發指令

```bash
npm run dev            # 本地開發伺服器（預設 :5173）
npm run build          # 正式建置
npm run preview        # 預覽建置結果
npm run lint           # ESLint 檢查
```

## 測試

```bash
npm run test:e2e       # Playwright E2E 測試（需後端 smoke profile 運行於 :8081）
npm run test:e2e:ui    # Playwright UI 互動模式
```

## API 契約同步

後端有 API 異動時，執行以下指令同步 OpenAPI spec 並重新生成 TypeScript SDK：

```bash
npm run api:sync       # = api:fetch + api:generate（需後端運行）
npm run api:fetch      # 僅從後端抓 spec → backend/openapi.json
npm run api:generate   # 僅重新生成 src/services/gen/（使用現有 openapi.json）
```

生成產物位於 `src/services/gen/`：
- `types.gen.ts` — 所有 API 型別定義
- `sdk.gen.ts` — 各端點的強型別呼叫函式
- `client.gen.ts` — Axios client 設定

自訂攔截器與無法自動生成的服務邏輯請維護於 `src/services/api.ts`。
