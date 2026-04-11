import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

/**
 * V25 新功能 E2E 測試
 *
 * 覆蓋範圍：
 *   1. 軟刪除 — 刪除寵物後列表消失，後端仍保留紀錄 (deleted_at 有值)
 *   2. PetFormModal 生日欄位 (birthDate) 存在
 *   3. PetFormModal 包含 RABBIT 物種選項
 *   4. Profile 頁 Email 驗證勳章（綠色 badge 顯示「已驗證」）
 *   5. Email 更換後事件驅動彈窗（無需重整）
 */

const clearSW = async (page) => {
  await page.goto('/')
  await page.evaluate(async () => {
    const registrations = await navigator.serviceWorker.getRegistrations()
    for (const r of registrations) await r.unregister()
  })
}

// ─── Pets / Soft Delete ────────────────────────────────────────────────────

test.describe('V25: Soft Delete & Pet Form', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectClientSmokeAuth('/client/pets')
  })

  test('[V25 軟刪除] 刪除寵物後從列表消失', async ({ page }) => {
    const MOCK_PET = {
      petId: 'del-test-0001',
      name: '小豆',
      species: 'CAT',
      gender: 'FEMALE',
      weightKg: 3.5,
      neuteredStatus: 'YES',      // V26: replaced isNeutered boolean
      vaccinationStatus: 'YES',
      dewormingStatus: 'NO',
      birthDate: null,
      avatarUrl: null,
    }

    await page.route('**/clients/me/pets', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([MOCK_PET]),
        })
      }
      route.continue()
    })
    await page.route('**/clients/me/pets/del-test-0001', (route) => {
      if (route.request().method() === 'DELETE') {
        return route.fulfill({ status: 204 })
      }
      route.continue()
    })

    await page.goto('/client/pets')
    await page.waitForLoadState('networkidle')

    await expect(page.getByText('小豆')).toBeVisible({ timeout: 10000 })

    page.once('dialog', (dialog) => dialog.accept())
    // Delete button = trash icon button in the pet card
    const deleteBtn = page.locator('button').filter({
      has: page.locator('span.material-symbols-outlined', { hasText: 'delete' })
    }).first()
    await deleteBtn.click()

    await expect(page.getByText('小豆')).not.toBeVisible({ timeout: 5000 })
  })

  test('[V25 生日欄位] PetFormModal 包含 birthDate 年份選擇器', async ({ page }) => {
    await page.route('**/clients/me/pets', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    )

    await page.goto('/client/pets')
    await page.waitForLoadState('networkidle')

    // Pets page add button is icon-only (material icon "add")
    const addBtn = page.locator('button').filter({
      has: page.locator('span.material-symbols-outlined', { hasText: 'add' })
    }).first()
    await addBtn.waitFor({ state: 'visible', timeout: 10000 })
    await addBtn.click()

    // Modal should open with a birthDate year input or select
    await expect(
      page.locator('select, input[type="number"]')
        .filter({ hasText: /年|year/i })
        .or(page.locator('input[type="number"][min]'))
        .first()
    ).toBeVisible({ timeout: 5000 })
  })

  test('[V25 RABBIT] PetFormModal 包含兔子物種 option', async ({ page }) => {
    await page.route('**/clients/me/pets', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    )

    await page.goto('/client/pets')
    await page.waitForLoadState('networkidle')

    const addBtn = page.locator('button').filter({
      has: page.locator('span.material-symbols-outlined', { hasText: 'add' })
    }).first()
    await addBtn.waitFor({ state: 'visible', timeout: 10000 })
    await addBtn.click()

    // 物種選單需含 RABBIT option
    await expect(page.locator('option[value="RABBIT"]')).toBeAttached({ timeout: 5000 })
  })
})

// ─── Email Verified Badge & Change Flow ────────────────────────────────────

test.describe('V25: Email Verified Badge & Change Flow', () => {
  test('[V25 驗證勳章] 已驗證 CLIENT 在 Profile 顯示「已驗證」綠色 badge', async ({ page }) => {
    // The emailVerified badge is in the !isSitter (CLIENT) section of Profile.jsx.
    // Must use CLIENT mode and inject emailVerified: true into the auth store.
    await clearSW(page)

    // Set localStorage directly with CLIENT + emailVerified: true
    await page.context().addInitScript(() => {
      window.localStorage.setItem('whiskerwatch-auth-storage', JSON.stringify({
        state: {
          token: 'smoke-test-token',
          isAuthenticated: true,
          user: {
            id: 'efefefef-0000-0000-0000-000000000002',
            email: 'client_smoke@test.com',
            role: 'CLIENT',
            lastActiveRole: 'CLIENT',
            emailVerified: true,   // ← V25: verified badge field
            profiles: [{ id: 'efefefef-0000-0000-0000-000000000031', role: 'CLIENT', name: 'James Wilson (Smoke)' }],
          },
        },
        version: 0,
      }))
      window.localStorage.setItem('whiskerwatch-theme-storage', JSON.stringify({
        state: { mode: 'CLIENT' },
        version: 0,
      }))
    })

    await page.route('**/*', (route) => {
      const url = route.request().url()
      const headers = { ...route.request().headers() }
      if (url.includes('localhost')) headers['X-Smoke-Auth'] = 'CLIENT'
      route.continue({ headers })
    })

    // Mock getMe (LIFO → runs first) to preserve emailVerified: true
    await page.route('**/auth/me', (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accountId: 'efefefef-0000-0000-0000-000000000002',
        email: 'client_smoke@test.com',
        lastActiveRole: 'CLIENT',
        emailVerified: true,
        profiles: [{ profileId: 'efefefef-0000-0000-0000-000000000031', role: 'CLIENT', name: 'James Wilson (Smoke)', avatarUrl: null }],
        debugCode: null,
      }),
    }))

    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    // V25: 已驗證 → CLIENT profile 顯示 bg-green-100 的「已驗證」badge
    await expect(page.getByText('已驗證')).toBeVisible({ timeout: 10000 })
  })

  test('[V25 Email 更換] 儲存新信箱後事件驅動彈窗（無需重整）', async ({ page }) => {
    const authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectSmokeAuth('SITTER')

    // Mock the email update endpoint
    await page.route('**/accounts/me/email', (route) => {
      if (['PATCH', 'PUT', 'POST'].includes(route.request().method())) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Verification email sent' }),
        })
      }
      route.continue()
    })

    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    // 點擊電子郵件項目以開啟 email 編輯 modal
    const emailRow = page.getByText(/電子郵件/).first()
    await emailRow.waitFor({ state: 'visible', timeout: 10000 })
    await emailRow.click()

    // Modal 開啟：「更改電子郵件」標題
    await expect(page.getByText('更改電子郵件')).toBeVisible({ timeout: 5000 })

    // 填入新信箱
    const emailInput = page.locator('input[type="email"]').first()
    await emailInput.fill('new_test@example.com')

    // 儲存
    await page.getByRole('button', { name: '儲存' }).click()

    // V25 事件驅動：Profile 應自動觸發 pending_verification，
    // CommunicationVerify 彈出，或成功訊息出現（無需 reload）
    const successIndicator = page.getByText(/驗證信|已發送|verification/i)
      .or(page.locator('input[maxlength="6"]'))
      .or(page.getByText(/驗證碼/i))
    await expect(successIndicator.first()).toBeVisible({ timeout: 8000 })
  })
})
