import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

test.describe('Client Profile E2E', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)

    // Mock GET /clients/me/pets to return empty list (avoids API error on mount)
    await page.route('**/clients/me/pets', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([])
      })
    )

    await page.goto('/')
    await page.evaluate(async () => {
      const registrations = await navigator.serviceWorker.getRegistrations()
      for (const registration of registrations) {
        await registration.unregister()
      }
    })

    await authPage.injectClientSmokeAuth('/profile')
  })

  test('Client Profile shows 我的毛孩 section', async ({ page }) => {
    await page.waitForLoadState('networkidle')
    await expect(page.getByText('我的毛孩')).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('button', { name: '新增寵物' })).toBeVisible()
    await expect(page.getByRole('button', { name: '管理全部' })).toBeVisible()
  })

  test('Clicking 新增寵物 opens PetFormModal', async ({ page }) => {
    await page.waitForLoadState('networkidle')
    const addPetBtn = page.getByRole('button', { name: '新增寵物' })
    await addPetBtn.waitFor({ state: 'visible', timeout: 15000 })
    await addPetBtn.click()

    // PetFormModal renders h3 '新增毛孩' when isOpen=true and no initialData
    await expect(page.getByText('新增毛孩')).toBeVisible({ timeout: 5000 })
    // Modal has a Cancel button to close
    await expect(page.getByRole('button', { name: '取消' })).toBeVisible()
  })
})
