# PWA 架構升級計畫 (Progressive Web App Implementation)

> [!WARNING]
> 經盤點目前的系統設定 (`index.html`, `vite.config.js`, `public/`)，**WhiskerWatch 目前尚未具備任何 PWA 的核心要素**。
> 為了實現「將網站安裝至手機桌面，並帶來接近原生的 App 體驗」，我們需要全面引入 PWA Manifest Generator 常規的最佳實踐。

## User Review Required

以下是為達到「優質 PWA 設計標準」所需進行的技術調整，在我們開始實作前，請問是否同意按照此清單進行？（另外請確認：Logo Icon 是否目前已有，或者我可以使用暫時的 SVG 代替？）

## Proposed Changes

要讓這個專案轉型成符合標準的高級 PWA，我們需要在以下四個層面進行設計調整：

### 1. 核心設定檔與建置生態 (Build Ecosystem)

我們需要引入業界標準的 `vite-plugin-pwa` 套件，讓 Vite 自動幫我們化繁為簡（自動壓制圖片、自動產生 Service Worker）。

#### [MODIFY] `frontend/vite.config.js`
- 引入並設定 `VitePWA` 插件。
- 設定 `registerType: 'autoUpdate'`，讓新版本的網站能在背景自動更新。
- 定義 `manifest` 的核心 JSON 設定（App 名稱、深/淺色主題顏色切換、顯示模式 `standalone` 等）。

### 2. PWA Manifest 與圖標資產 (Assets & Metadata)

為了讓安裝至手機桌面時看起來足夠「高級」與原生：

#### [MODIFY] `frontend/index.html`
在 `<head>` 中必須補充以下原生設計所需的 Meta Tags：
- `theme-color`：對齊我們目前的設計系統顏色 (Surface)。
- `apple-touch-icon`：針對 iOS 系統的專屬安裝圖示設定。
- `<meta name="apple-mobile-web-app-capable" content="yes">`：強制隱藏 Safari 的上下導覽列，達到真正的全螢幕沉浸感體驗。

#### [NEW] `frontend/public/icons/`
- PWA 要求至少提供 `192x192` 與 `512x512` 兩種尺寸的圖標以支援 `maskable` (適應性圓角)。
- （實作時會先建立一組暫時向量圖標佔位）

### 3. Service Worker 快取與離線體驗 (Offline Experience)

網路不穩或是切換至飛航模式時，PWA 不能直接顯示小恐龍 (Chrome Dino)。

#### [MODIFY] `frontend/vite.config.js`
- 在 PWA plugin 設定中配置 `workbox` 策略。
- 針對 API 請求（如 `/api/v1/profiles`）加入 Network-First 策略。
- 針對靜態資源（CSS、字體、圖片）加入 Cache-First 策略以大幅改善載入效能。

### 4. 註冊提示與安裝 UI (Prompt UI)

PWA 大多是被動觸發安裝。但在高級應用中，我們會希望在適當的時候引導使用者主動下載（A2HS: Add To Home Screen）。

#### [NEW] `frontend/src/components/pwa/InstallPrompt.jsx`
- （可選階段）在登入後 (Dashboard) 的某個區域，出現優雅的 Toast 或按鈕：「將管家加入主畫面，享受更快體驗」，並攔截 `beforeinstallprompt` 事件。

---

## Verification Plan

### Automated Tests
1. **Lighthouse PWA 審查**：啟動 `npm run build`，並利用 Chrome Lighthouse 功能，確保 PWA 分數達到 100 分。
2. **服務器部署檢查**：啟動 Live Server，使用 Chrome 開發人員工具 (`Application` > `Manifest` & `Service Workers`) 確保 Service worker 出現 `activated and is running` 的綠燈狀態，並且 Installable 狀態被正確觸發。
