import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

// Smoke notification seeds (injected via window.__SMOKE_NOTIFICATIONS__ before page load)
const SMOKE_NOTIFICATIONS = [
  { id: '1', role: 'CLIENT', title: '照片已上傳',   body: '保母已上傳本次服務照片', read: false, time: '10m ago' },
  { id: '2', role: 'SITTER', title: '新預約申請',   body: '您有一筆新預約請求', read: false, time: '5m ago' },
  { id: '3', role: 'ALL',    title: '帳號安全性提醒', body: '您的帳號已從新裝置登入', read: false, time: '1h ago' },
]

test.describe('Notifications Role Filtering E2E', () => {
  let authPage

  const setup = async (page, role, targetUrl) => {
    authPage = new AuthPage(page)

    // 1. Clear service workers to prevent PWA cache interference
    await page.goto('/')
    await page.evaluate(async () => {
      const registrations = await navigator.serviceWorker.getRegistrations()
      for (const registration of registrations) {
        await registration.unregister()
      }
    })

    // 2. Seed notifications before any page script runs
    await page.addInitScript((notifications) => {
      window.__SMOKE_NOTIFICATIONS__ = notifications
    }, SMOKE_NOTIFICATIONS)

    // 3. Inject smoke auth and navigate
    if (role === 'SITTER') {
      await authPage.injectSmokeAuth('SITTER')
    } else {
      await authPage.injectClientSmokeAuth(targetUrl)
    }
  }

  test('Sitter mode — only SITTER and ALL notifications are shown', async ({ page }) => {
    await setup(page, 'SITTER', '/notifications')

    await page.goto('/notifications')
    await page.waitForLoadState('networkidle')

    // SITTER notifications visible
    await expect(page.getByText('新預約申請')).toBeVisible({ timeout: 10000 })    // n2 (SITTER)
    await expect(page.getByText('帳號安全性提醒')).toBeVisible()                   // n3 (ALL)

    // CLIENT-only notification must NOT be visible
    await expect(page.getByText('照片已上傳')).not.toBeVisible()                   // n1 (CLIENT)
  })

  test('Client mode — only CLIENT and ALL notifications are shown', async ({ page }) => {
    await setup(page, 'CLIENT', '/notifications')
    await page.waitForLoadState('networkidle')

    // CLIENT notifications visible
    await expect(page.getByText('照片已上傳')).toBeVisible({ timeout: 10000 })    // n1 (CLIENT)
    await expect(page.getByText('帳號安全性提醒')).toBeVisible()                   // n3 (ALL)

    // SITTER-only notification must NOT be visible
    await expect(page.getByText('新預約申請')).not.toBeVisible()                   // n2 (SITTER)
  })
})
