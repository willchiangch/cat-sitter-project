import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

/**
 * V26 新功能 E2E 測試
 *
 * 覆蓋範圍：
 *   1. PetFormModal 顯示三個健康狀態欄位（結紮/疫苗/驅蟲），各含 YES/NO/NOT_REQUIRED 三個按鈕
 *   2. PetFormModal 性別欄位包含「不詳 (Unknown)」選項
 *   3. ServicePackages 服務方案支援 RABBIT 物種勾選
 *   4. Pets 列表顯示 calculateAge 計算出的年齡（birthDate 有值時）
 */

const clearSW = async (page) => {
  await page.goto('/')
  await page.evaluate(async () => {
    const registrations = await navigator.serviceWorker.getRegistrations()
    for (const r of registrations) await r.unregister()
  })
}

// ─── PetFormModal Health Status (V26) ─────────────────────────────────────

test.describe('V26: PetFormModal Health Status Fields', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectClientSmokeAuth('/client/pets')
    // Mock GET /pets to return empty list so we don't depend on seeder
    await page.route('**/clients/me/pets', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    )
    await page.goto('/client/pets')
    await page.waitForLoadState('networkidle')
    // Open PetFormModal
    const addBtn = page.locator('button').filter({
      has: page.locator('span.material-symbols-outlined', { hasText: 'add' })
    }).first()
    await addBtn.waitFor({ state: 'visible', timeout: 10000 })
    await addBtn.click()
    // Wait for modal to open
    await expect(page.getByText('新增毛孩')).toBeVisible({ timeout: 5000 })
  })

  test('[V26 健康狀態] PetFormModal 顯示「結紮」三選一按鈕', async ({ page }) => {
    // neuteredStatus: YES / NO / NOT_REQUIRED buttons
    await expect(page.getByRole('button', { name: '有' }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: '沒有' }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: '不需要' }).first()).toBeVisible()
  })

  test('[V26 健康狀態] PetFormModal 包含三組健康狀態欄位標籤', async ({ page }) => {
    // Labels for all 3 status groups
    await expect(page.getByText('結紮')).toBeVisible()
    await expect(page.getByText('定期打疫苗')).toBeVisible()
    await expect(page.getByText('定期點驅蟲藥')).toBeVisible()
  })

  test('[V26 性別] PetFormModal 性別包含「不詳」選項', async ({ page }) => {
    await expect(page.locator('option[value="UNKNOWN"]')).toBeAttached({ timeout: 5000 })
  })
})

// ─── Pets List calculateAge (V26) ─────────────────────────────────────────

test.describe('V26: Pets List Age Display', () => {
  test('[V26 年齡顯示] birthDate 有值時列表顯示 calculateAge 結果', async ({ page }) => {
    const authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectClientSmokeAuth('/client/pets')

    const MOCK_PET = {
      petId: 'age-test-0001',
      name: 'AgeTestPet',
      species: 'CAT',
      gender: 'MALE',
      weightKg: 4.0,
      neuteredStatus: 'YES',
      vaccinationStatus: 'YES',
      dewormingStatus: 'NO',
      birthDate: '2022-01-01',  // Should produce "3歲 ..." on 2026-04-11
      avatarUrl: null,
    }

    await page.route('**/clients/me/pets', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([MOCK_PET]),
      })
    )

    await page.goto('/client/pets')
    await page.waitForLoadState('networkidle')

    await expect(page.getByText('AgeTestPet')).toBeVisible({ timeout: 10000 })
    // calculateAge('2022-01-01') on 2026-04-11 → "4歲 3個月"
    await expect(page.getByText(/\d+歲/)).toBeVisible({ timeout: 5000 })
  })
})

// ─── ServicePackages RABBIT (V26) ─────────────────────────────────────────

test.describe('V26: ServicePackages supports RABBIT', () => {
  test('[V26 RABBIT] 服務方案編輯介面包含 RABBIT 物種選項', async ({ page }) => {
    const authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectSmokeAuth('SITTER')

    // Mock services list
    await page.route('**/sitters/me/services', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([]),
        })
      }
      route.continue()
    })

    await page.goto('/sitter/service-packages')
    await page.waitForLoadState('networkidle')

    // Open create new package form
    const newPkgBtn = page.getByRole('button', { name: /新增|add|建立/i }).first()
    await newPkgBtn.waitFor({ state: 'visible', timeout: 10000 })
    await newPkgBtn.click()

    // Species checkboxes should include RABBIT label (兔)
    await expect(page.getByText('兔').first()).toBeVisible({ timeout: 5000 })
  })
})
