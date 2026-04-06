import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

test.describe('Sitter Profile Page', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await authPage.injectSmokeAuth('SITTER')
  })

  test('Business tools are visible even when sitter API fails', async ({ page }) => {
    // Simulate getSitterMe failing (backend unreachable / 500)
    await page.route('**/sitters/me/profile', (route) =>
      route.fulfill({ status: 500, body: 'Internal Server Error' })
    )
    await page.route('**/sitters/me/calendar/status', (route) =>
      route.fulfill({ status: 500, body: 'Internal Server Error' })
    )

    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    // Business tools must be visible regardless of sitterData loading state
    await expect(page.getByText('管理服務方案')).toBeVisible({ timeout: 10000 })
    await expect(page.getByText('預約問卷設定')).toBeVisible()
    await expect(page.getByText('信任圈夥伴')).toBeVisible()
    await expect(page.getByText('客群門禁管理')).toBeVisible()
  })

  test('Business tools are visible when sitter API succeeds', async ({ page }) => {
    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    await expect(page.getByText('管理服務方案')).toBeVisible({ timeout: 10000 })
    await expect(page.getByText('預約問卷設定')).toBeVisible()
    await expect(page.getByText('信任圈夥伴')).toBeVisible()
    await expect(page.getByText('客群門禁管理')).toBeVisible()
  })

  test('Clicking 管理服務方案 navigates to service-packages', async ({ page }) => {
    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    await page.getByText('管理服務方案').click()
    await expect(page).toHaveURL(/\/sitter\/service-packages/, { timeout: 5000 })
  })

  test('Clicking 信任圈夥伴 navigates to trust-circle', async ({ page }) => {
    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    await page.getByText('信任圈夥伴').click()
    await expect(page).toHaveURL(/\/sitter\/trust-circle/, { timeout: 5000 })
  })
})
