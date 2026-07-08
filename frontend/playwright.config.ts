import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,

  // CI 是打正式部署 (Firebase Hosting -> Cloud Run asia-east1 -> Supabase ap-southeast-2)，
  // 真實跨區網路延遲下 5000ms 的預設 expect timeout 偶爾不夠，拉寬到 10s
  expect: {
    timeout: 10000
  },

  // 報告輸出至 report/ 資料夾
  reporter: [['html', { outputFolder: 'report', open: 'never' }]],
  
  // 測試結果儲存位置
  outputDir: 'test-results/',

  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:5173',
    
    // 僅開啟截圖與 Trace，關閉錄影
    screenshot: 'on',
    video: 'off',
    trace: 'on', 
    
    dataTestIdAttribute: 'data-testid'
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ],
  webServer: process.env.BASE_URL ? undefined : {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI
  }
});
