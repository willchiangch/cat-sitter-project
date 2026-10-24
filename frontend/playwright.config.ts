import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // 本機上限 3：Cloud Run 縮到 0 時，5 個 worker 同時打第一發 request 會冷啟 4 台 instance、
  // 第 5 發被 GFE 以 429 abort（見 .e2e-runs/20261023-2324/triage.md F1）。CI 維持 1。
  workers: process.env.CI ? 1 : 3,

  // CI 是打正式部署 (Firebase Hosting -> Cloud Run asia-east1 -> Supabase ap-southeast-2)，
  // 真實跨區網路延遲下 5000ms 的預設 expect timeout 偶爾不夠，拉寬到 10s
  expect: {
    timeout: 10000
  },

  // 報告輸出至 report/ 資料夾
  // results.json 給 e2e agent 讀（失敗訊息、步驟樹、附件路徑），html 給人看
  reporter: [
    ['html', { outputFolder: 'report', open: 'never' }],
    ['json', { outputFile: 'report/results.json' }]
  ],

  // 測試結果儲存位置
  outputDir: 'test-results/',

  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:5173',

    // 僅開啟截圖與 Trace，關閉錄影。
    // 注意：這個設定只會在測試結束時截 `page` fixture 一張；逐步驟證據由 e2e/support/evidence.ts 的 step()/snap() 負責
    screenshot: { mode: 'on', fullPage: true },
    video: 'off',
    trace: 'on',

    dataTestIdAttribute: 'data-testid',

    // E2E 不驗 SW/PWA 行為，全域擋掉 Service Worker（.e2e-runs/20261024-1939 F1）：
    // 正式 build（CI 打的正式站、vite preview）會註冊 Workbox SW（main.tsx virtual:pwa-register），
    // 整頁導覽由 SW 的 NavigationRoute 從 precache 直接回 index.html，page.route 攔不到 SW 處理掉的請求，
    // 所以「攔 document 回 204」之類的 mock 在 dev server 綠、在正式 build 紅。dev server 不註冊 SW，
    // 全域 block 讓兩邊行為一致。同時擋掉 vite.config.ts workbox 註解所述的地雷：若之後有人讓 SW 接手 /api，
    // 所有 page.route('**/api/**') mock 會一起失效。需要測 PWA 的 spec 請自行 test.use({ serviceWorkers: 'allow' })。
    serviceWorkers: 'block'
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ],
  webServer: process.env.BASE_URL
    ? undefined
    : {
        command: 'npm run dev',
        url: 'http://localhost:5173',
        reuseExistingServer: !process.env.CI
      }
});
