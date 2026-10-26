# API 文件

- `openapi.json`：springdoc `/v3/api-docs` 快照
- `index.html`：用 Redocly 從 `openapi.json` 產生的靜態文件

## 重新產生

```bash
# 1. 匯出 openapi.json（Testcontainers 起 local profile，不需要另外開後端）
cd backend
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=OpenApiExportTest -Dopenapi.export=true -Dsurefire.failIfNoSpecifiedTests=false

# 2. 產生 index.html
cd ..
npx --yes @redocly/cli@1.34.5 build-docs docs/api/openapi.json -o docs/api/index.html
```

## 注意

- 快照是用 `local` profile 產生的，所以會包含只在本機啟用的端點：`POST /api/sitter/gatekeeper/subscription/mock`（`@Profile("local")`），正式站不存在，會回 404。
- 正式站（`prod` profile）不開放 `/v3/api-docs` 與 Swagger UI。
