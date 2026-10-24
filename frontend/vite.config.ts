import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      // 改用 virtual:pwa-register 手動註冊 (main.tsx)，偵測到新版本時強制 reload，
      // 避免部署後開著的分頁繼續吃到舊快取的 app shell 而變成空白頁
      injectRegister: false,
      includeAssets: ['favicon.ico', 'apple-touch-icon.png', 'mask-icon.svg'],
      manifest: {
        name: 'WhiskerWatch',
        short_name: 'WhiskerWatch',
        description: '貓咪保母 PWA',
        theme_color: '#f59e0b',
        icons: [
          {
            src: 'pwa-192x192.png',
            sizes: '192x192',
            type: 'image/png'
          },
          {
            src: 'pwa-512x512.png',
            sizes: '512x512',
            type: 'image/png'
          }
        ]
      },
      workbox: {
        // 核心防雷：絕對不要快取 /api/ 的網路請求（SD-FRONTEND-SPEC §2.2）。
        // - 導覽：/api 開頭的整頁導覽不回 precache 的 index.html（比對的是 pathname，有效）
        // - fetch/XHR：刻意「不」寫 runtimeCaching。SW 沒有任何 route 命中 /api 時不會 respondWith，
        //   請求直接由瀏覽器走網路，不經 SW、也不進任何 cache。
        //   過去這裡有一條 { urlPattern: /^\/api\/.*$/, handler: 'NetworkOnly' }，但 Workbox 的 RegExp route
        //   是拿完整 href（https://...）比對，^\/api 永遠不會命中，等於無效規則，已移除（行為不變）。
        //   不要把它「修好」成 ({ url }) => url.pathname.startsWith('/api/')：API 會改由 SW 代發，
        //   Playwright 的 page.route 攔不到 SW 發出的請求，E2E 的 API mock 會全部失效（.e2e-runs/20261024-1939 F1）
        navigateFallbackDenylist: [/^\/api/]
      }
    })
  ],
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      '/local-media': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
});
