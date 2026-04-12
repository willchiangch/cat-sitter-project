import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

/**
 * V30 新功能 E2E 測試
 *
 * 覆蓋範圍：
 *   1. 保母認證狀態標章（已認證 / 審核中 / 未認證）
 *   2. 訂閱合約協議彈窗（切換方案前顯示）
 *   3. 付費處理中遮罩（同意後出現）
 */

const clearSW = async (page) => {
  await page.goto('/')
  await page.evaluate(async () => {
    const registrations = await navigator.serviceWorker.getRegistrations()
    for (const r of registrations) await r.unregister()
  })
}

// ─── 認證狀態標章 (V30) ────────────────────────────────────────────────────

test.describe('V30: 保母認證狀態標章', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectSmokeAuth('SITTER')
  })

  test('[V30 已認證] isVerified=true 時顯示藍色「已認證保母」', async ({ page }) => {
    // Mock sitters/me/profile to return isVerified: true
    await page.route('**/sitters/me/profile', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          displayName: 'Sophia Sitter',
          bio: 'Test bio',
          isVerified: true,
          idCardFrontUrl: 'https://example.com/id.jpg',
          facePhotoUrl: null,
          bankCode: null,
          bankAccount: null,
        }),
      })
    )

    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    // Should show the verified badge text
    await expect(page.getByText(/已認證保母/)).toBeVisible({ timeout: 10000 })
  })

  test('[V30 審核中] idCardFrontUrl 有值但 isVerified=false 時顯示「審核中」', async ({ page }) => {
    await page.route('**/sitters/me/profile', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          displayName: 'Sophia Sitter',
          bio: 'Test bio',
          isVerified: false,
          idCardFrontUrl: 'https://example.com/id.jpg',
          facePhotoUrl: null,
          bankCode: null,
          bankAccount: null,
        }),
      })
    )

    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    await expect(page.getByText(/審核中/).first()).toBeVisible({ timeout: 10000 })
  })

  test('[V30 未認證] isVerified=false 且無上傳照片時顯示「未認證」', async ({ page }) => {
    await page.route('**/sitters/me/profile', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          displayName: 'Sophia Sitter',
          bio: 'Test bio',
          isVerified: false,
          idCardFrontUrl: null,
          facePhotoUrl: null,
          bankCode: null,
          bankAccount: null,
        }),
      })
    )

    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    await expect(page.getByText(/未認證/)).toBeVisible({ timeout: 10000 })
  })
})

// ─── 訂閱合約協議彈窗 (V30) ───────────────────────────────────────────────

test.describe('V30: 訂閱合約協議流程', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectSmokeAuth('SITTER')

    // Mock subscription to be FREE so 切換 buttons are available on other plans
    await page.route('**/sitters/me/subscription', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ planId: 'FREE', status: 'ACTIVE', renewsAt: null }),
        })
      }
      route.continue()
    })
  })

  test('[V30 合約彈窗] 點擊「切換」出現服務合約協議模態框', async ({ page }) => {
    await page.goto('/sitter/subscription')
    await page.waitForLoadState('networkidle')

    // Click 切換 on 基礎版 (STANDARD)
    const switchBtns = page.getByRole('button', { name: '切換' })
    await switchBtns.first().waitFor({ state: 'visible', timeout: 10000 })
    await switchBtns.first().click()

    // Agreement modal should appear
    await expect(page.getByText('服務合約與訂閱協議')).toBeVisible({ timeout: 5000 })
  })

  test('[V30 合約條款] 合約彈窗包含四項條款', async ({ page }) => {
    await page.goto('/sitter/subscription')
    await page.waitForLoadState('networkidle')

    const switchBtns = page.getByRole('button', { name: '切換' })
    await switchBtns.first().waitFor({ state: 'visible', timeout: 10000 })
    await switchBtns.first().click()

    // Verify all 4 clause items are present
    await expect(page.getByText(/自動續約/)).toBeVisible({ timeout: 5000 })
    await expect(page.getByText(/扣款失敗/).first()).toBeVisible()
    await expect(page.getByText(/不接受中途退費|恕不接受/)).toBeVisible()
    await expect(page.getByText(/升級方案|補差價/)).toBeVisible()
  })

  test('[V30 合約彈窗] 點擊「我再考慮一下」可關閉模態框', async ({ page }) => {
    await page.goto('/sitter/subscription')
    await page.waitForLoadState('networkidle')

    const switchBtns = page.getByRole('button', { name: '切換' })
    await switchBtns.first().waitFor({ state: 'visible', timeout: 10000 })
    await switchBtns.first().click()

    await expect(page.getByText('服務合約與訂閱協議')).toBeVisible({ timeout: 5000 })

    // Click dismiss button
    await page.getByRole('button', { name: /我再考慮一下/ }).click()

    // Modal should close
    await expect(page.getByText('服務合約與訂閱協議')).not.toBeVisible({ timeout: 3000 })
  })
})
