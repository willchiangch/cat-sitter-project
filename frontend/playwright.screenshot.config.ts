import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/screenshot',
  fullyParallel: false,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'off',
    video: 'off',
  },
  webServer: [
    {
      command: 'npm run dev',
      url: 'http://localhost:5173',
      reuseExistingServer: !process.env.CI,
      stdout: 'ignore',
      stderr: 'pipe',
      timeout: 120000,
    },
    {
      command: 'cd ../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=smoke',
      url: 'http://localhost:8080/api/v1/auth/me',
      reuseExistingServer: !process.env.CI,
      stdout: 'ignore',
      stderr: 'pipe',
      timeout: 180000,
    },
  ],
  projects: [
    {
      name: 'mobile',
      use: {
        ...devices['Pixel 5'],
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 2,
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
})
