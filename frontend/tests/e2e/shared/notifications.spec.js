import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

// Mock notification data (matches notificationStore.js):
//   n1: role=CLIENT, title='照片已上傳'
//   n2: role=SITTER, title='新預約申請'
//   n3: role=ALL,    title='帳號安全性提醒'

test.describe('Notifications Role Filtering E2E', () => {
  let authPage

  const clearServiceWorkers = async (page) => {
    await page.goto('/')
    await page.evaluate(async () => {
      const registrations = await navigator.serviceWorker.getRegistrations()
      for (const registration of registrations) {
        await registration.unregister()
      }
    })
  }

  test('Sitter mode — only SITTER and ALL notifications are shown', async ({ page }) => {
    authPage = new AuthPage(page)
    await clearServiceWorkers(page)
    await authPage.injectSmokeAuth('SITTER')

    await page.goto('/notifications')
    await page.waitForLoadState('networkidle')

    // SITTER notifications visible
    await expect(page.getByText('新預約申請')).toBeVisible({ timeout: 10000 })    // n2 (SITTER)
    await expect(page.getByText('帳號安全性提醒')).toBeVisible()                   // n3 (ALL)

    // CLIENT-only notification must NOT be visible
    await expect(page.getByText('照片已上傳')).not.toBeVisible()                   // n1 (CLIENT)
  })

  test('Client mode — only CLIENT and ALL notifications are shown', async ({ page }) => {
    authPage = new AuthPage(page)
    await clearServiceWorkers(page)
    await authPage.injectClientSmokeAuth('/notifications')
    await page.waitForLoadState('networkidle')

    // CLIENT notifications visible
    await expect(page.getByText('照片已上傳')).toBeVisible({ timeout: 10000 })    // n1 (CLIENT)
    await expect(page.getByText('帳號安全性提醒')).toBeVisible()                   // n3 (ALL)

    // SITTER-only notification must NOT be visible
    await expect(page.getByText('新預約申請')).not.toBeVisible()                   // n2 (SITTER)
  })
})
