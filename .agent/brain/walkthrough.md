# PWA 架構升級總結 (Walkthrough)

WhiskerWatch 已正式升級為 Progressive Web App，使用者可將網站安裝至手機桌面，獲得接近原生 App 的沉浸式體驗。

## 變更清單

### 1. `vite.config.js` — 引入 `vite-plugin-pwa`

render_diffs(file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/vite.config.js)

**核心設定包含：**
| 項目 | 值 | 說明 |
|------|-----|------|
| `registerType` | `autoUpdate` | 新版自動背景更新，使用者無需手動刷新 |
| `display` | `standalone` | 隱藏瀏覽器網址列 |
| `theme_color` | `#1a1a2e` | 與設計系統深色主題一致 |
| `orientation` | `portrait` | 鎖定為直立模式 |

**Workbox 快取策略：**
| 資源類型 | 策略 | 效果 |
|----------|------|------|
| `/api/v1/*` | Network-First | 優先拿即時資料，離線時使用快取 |
| Google Fonts | Cache-First | 一年長效快取，極速載入字體 |
| 圖片資源 | Cache-First | 30 天長效快取 |

### 2. `index.html` — PWA & Apple 專屬 Meta Tags

render_diffs(file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/index.html)

> [!IMPORTANT]
> `apple-mobile-web-app-capable` + `black-translucent` 這組設定是 iOS Safari 全螢幕沉浸式的關鍵。沒有這組 meta，iOS 使用者安裝到桌面後仍然會看到 Safari 的上下導覽列。

### 3. PWA 圖標資產

已產生並放置於 `public/icons/`：
- `icon-192x192.png` — Android 主畫面圖標
- `icon-512x512.png` — Android 啟動閃屏 + maskable 自適應圓角
- `apple-touch-icon.png` — iOS 桌面圖標 (180x180)

## Build 驗證結果

```
✓ 574 modules transformed
✓ built in 1.06s

PWA v1.2.0
mode      generateSW
precache  12 entries (1130.98 KiB)
files generated
  dist/sw.js
  dist/workbox-34a8ec49.js
  dist/registerSW.js
  dist/manifest.webmanifest
```

> [!TIP]
> 部署後可用 Chrome DevTools → `Application` → `Manifest` 查看完整清單，`Service Workers` 確認 SW 已 activated。也可以直接在手機瀏覽器中開啟網站，點選「加入主畫面」即可安裝！
