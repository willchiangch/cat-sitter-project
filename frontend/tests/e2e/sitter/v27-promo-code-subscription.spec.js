import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

/**
 * V27 折扣碼訂閱功能 E2E 測試
 *
 * 覆蓋範圍：
 *   1. Agreement modal 顯示折扣碼輸入欄位
 *   2. 有效折扣碼 → 顯示折扣金額與實付金額
 *   3. 無效折扣碼 → 顯示錯誤訊息
 *   4. 折扣後歸零 → 按鈕變「免費啟用方案」（綠色）
 *   5. 免費啟用直接呼叫 changePlan（不走付款遮罩）
 *   6. 未輸入折扣碼 → 按鈕顯示原價
 */

const clearSW = async (page) => {
  await page.goto('/')
  await page.evaluate(async () => {
    const registrations = await navigator.serviceWorker.getRegistrations()
    for (const r of registrations) await r.unregister()
  })
}

const mockSubscriptionAsFreePlan = async (page) => {
  await page.route('**/sitters/me/subscription', (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ planId: 'FREE', status: 'ACTIVE', renewsAt: null }),
      })
    }
    // 讓 PUT 請求 pass through（或 mock 成功）
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ planId: 'STANDARD', status: 'ACTIVE', renewsAt: '2026-05-12' }),
    })
  })
}

const openAgreementModal = async (page) => {
  await page.goto('/sitter/subscription')
  await page.waitForLoadState('networkidle')
  const switchBtns = page.getByRole('button', { name: '切換' })
  await switchBtns.first().waitFor({ state: 'visible', timeout: 10000 })
  await switchBtns.first().click()
  await expect(page.getByText('服務合約與訂閱協議')).toBeVisible({ timeout: 5000 })
}

// ─── 折扣碼 UI 存在性 ─────────────────────────────────────────────────────────

test.describe('V27: 折扣碼輸入欄位', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectSmokeAuth('SITTER')
    await mockSubscriptionAsFreePlan(page)
  })

  test('[V27 UI] Agreement modal 包含折扣碼輸入框與套用按鈕', async ({ page }) => {
    await openAgreementModal(page)

    await expect(page.getByPlaceholder('輸入折扣碼')).toBeVisible()
    await expect(page.getByRole('button', { name: '套用' })).toBeVisible()
  })

  test('[V27 UI] 未輸入折扣碼時確認按鈕顯示原價', async ({ page }) => {
    await openAgreementModal(page)

    // 找到確認按鈕，確認包含金額文字
    const confirmBtn = page.getByRole('button', { name: /同意並立即支付/ })
    await expect(confirmBtn).toBeVisible()
    // 按鈕上應有 $（原始價格）
    await expect(confirmBtn).toContainText('$')
  })
})

// ─── 有效折扣碼 ───────────────────────────────────────────────────────────────

test.describe('V27: 有效折扣碼折扣顯示', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectSmokeAuth('SITTER')
    await mockSubscriptionAsFreePlan(page)
  })

  test('[V27 有效碼] 輸入有效折扣碼後顯示折扣金額', async ({ page }) => {
    // Mock validate-promo endpoint — 回傳有效折扣
    await page.route('**/subscription/validate-promo', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: true,
          message: '折扣碼有效',
          originalAmount: 499,
          discountAmount: 100,
          finalAmount: 399,
        }),
      })
    )

    await openAgreementModal(page)

    await page.getByPlaceholder('輸入折扣碼').fill('SAVE100')
    await page.getByRole('button', { name: '套用' }).click()

    // 顯示折扣資訊
    await expect(page.getByText(/折扣 \$100/)).toBeVisible({ timeout: 5000 })
    await expect(page.getByText(/實付 \$399/)).toBeVisible()
  })

  test('[V27 有效碼] 有效折扣後確認按鈕顯示折扣後實付金額', async ({ page }) => {
    await page.route('**/subscription/validate-promo', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: true,
          message: '折扣碼有效',
          originalAmount: 499,
          discountAmount: 100,
          finalAmount: 399,
        }),
      })
    )

    await openAgreementModal(page)

    await page.getByPlaceholder('輸入折扣碼').fill('SAVE100')
    await page.getByRole('button', { name: '套用' }).click()

    await expect(page.getByText(/折扣 \$100/)).toBeVisible({ timeout: 5000 })

    // 確認按鈕應更新為折扣後金額
    const confirmBtn = page.getByRole('button', { name: /同意並立即支付/ })
    await expect(confirmBtn).toBeVisible()
    await expect(confirmBtn).toContainText('399')
  })

  test('[V27 有效碼] 價格摘要區塊顯示原價（刪除線）與實付金額', async ({ page }) => {
    await page.route('**/subscription/validate-promo', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: true,
          message: '折扣碼有效',
          originalAmount: 499,
          discountAmount: 100,
          finalAmount: 399,
        }),
      })
    )

    await openAgreementModal(page)
    await page.getByPlaceholder('輸入折扣碼').fill('SAVE100')
    await page.getByRole('button', { name: '套用' }).click()

    await expect(page.getByText(/折扣 \$100/)).toBeVisible({ timeout: 5000 })

    // 原價標示存在（刪除線那個）
    await expect(page.getByText('$499').first()).toBeVisible()
    // 實付金額（價格摘要區塊的大字）
    await expect(page.getByText('$399', { exact: true })).toBeVisible()
  })
})

// ─── 無效折扣碼 ───────────────────────────────────────────────────────────────

test.describe('V27: 無效折扣碼處理', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectSmokeAuth('SITTER')
    await mockSubscriptionAsFreePlan(page)
  })

  test('[V27 無效碼] 無效折扣碼顯示錯誤訊息', async ({ page }) => {
    await page.route('**/subscription/validate-promo', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: false,
          message: '折扣碼無效或已過期',
          originalAmount: 499,
          discountAmount: 0,
          finalAmount: 499,
        }),
      })
    )

    await openAgreementModal(page)

    await page.getByPlaceholder('輸入折扣碼').fill('BADCODE')
    await page.getByRole('button', { name: '套用' }).click()

    await expect(page.getByText(/折扣碼無效或已過期/)).toBeVisible({ timeout: 5000 })
  })

  test('[V27 無效碼] 無效折扣碼後確認按鈕仍顯示原價', async ({ page }) => {
    await page.route('**/subscription/validate-promo', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: false,
          message: '折扣碼無效或已過期',
          originalAmount: 499,
          discountAmount: 0,
          finalAmount: 499,
        }),
      })
    )

    await openAgreementModal(page)
    await page.getByPlaceholder('輸入折扣碼').fill('BADCODE')
    await page.getByRole('button', { name: '套用' }).click()

    await expect(page.getByText(/折扣碼無效或已過期/)).toBeVisible({ timeout: 5000 })

    // 確認按鈕仍為原價（不應顯示 $0 或免費啟用）
    const confirmBtn = page.getByRole('button', { name: /同意並立即支付/ })
    await expect(confirmBtn).toBeVisible()
  })
})

// ─── 免費啟用（折扣歸零）────────────────────────────────────────────────────

test.describe('V27: 折扣後 0 元免費啟用', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectSmokeAuth('SITTER')
    await mockSubscriptionAsFreePlan(page)
  })

  test('[V27 免費] 折扣後金額為 0 時按鈕變為「免費啟用方案」', async ({ page }) => {
    await page.route('**/subscription/validate-promo', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: true,
          message: '折扣碼有效',
          originalAmount: 499,
          discountAmount: 499,
          finalAmount: 0,
        }),
      })
    )

    await openAgreementModal(page)
    await page.getByPlaceholder('輸入折扣碼').fill('FREE100')
    await page.getByRole('button', { name: '套用' }).click()

    await expect(page.getByText(/折扣 \$499/)).toBeVisible({ timeout: 5000 })

    // 按鈕應變為免費啟用
    await expect(page.getByRole('button', { name: '免費啟用方案' })).toBeVisible()
    // 原本的「同意並立即支付」不應再出現
    await expect(page.getByRole('button', { name: /同意並立即支付/ })).not.toBeVisible()
  })

  test('[V27 免費] 免費啟用點擊後直接成功（不出現付費遮罩）', async ({ page }) => {
    await page.route('**/subscription/validate-promo', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: true,
          message: '折扣碼有效',
          originalAmount: 499,
          discountAmount: 499,
          finalAmount: 0,
        }),
      })
    )

    await openAgreementModal(page)
    await page.getByPlaceholder('輸入折扣碼').fill('FREE100')
    await page.getByRole('button', { name: '套用' }).click()

    await expect(page.getByText(/折扣 \$499/)).toBeVisible({ timeout: 5000 })

    // 點擊免費啟用
    await page.getByRole('button', { name: '免費啟用方案' }).click()

    // 付費處理中遮罩 **不應** 出現（因為是免費）
    await expect(page.getByText('付費處理中...')).not.toBeVisible()
  })

  test('[V27 免費] 免費啟用後方案更新為目標方案', async ({ page }) => {
    let putCalled = false

    await page.route('**/subscription/validate-promo', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: true,
          message: '折扣碼有效',
          originalAmount: 499,
          discountAmount: 499,
          finalAmount: 0,
        }),
      })
    )

    await page.route('**/sitters/me/subscription', (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ planId: 'FREE', status: 'ACTIVE', renewsAt: null }),
        })
      }
      if (method === 'PUT') {
        putCalled = true
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ planId: 'STANDARD', status: 'ACTIVE', renewsAt: '2026-05-12' }),
        })
      }
      route.continue()
    })

    await openAgreementModal(page)
    await page.getByPlaceholder('輸入折扣碼').fill('FREE100')
    await page.getByRole('button', { name: '套用' }).click()

    await expect(page.getByText(/折扣 \$499/)).toBeVisible({ timeout: 5000 })
    await page.getByRole('button', { name: '免費啟用方案' }).click()

    // Modal 應關閉
    await expect(page.getByText('服務合約與訂閱協議')).not.toBeVisible({ timeout: 5000 })

    // PUT 應被呼叫
    expect(putCalled).toBe(true)
  })
})

// ─── Enter 鍵觸發驗證 ─────────────────────────────────────────────────────────

test.describe('V27: 鍵盤操作', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await clearSW(page)
    await authPage.injectSmokeAuth('SITTER')
    await mockSubscriptionAsFreePlan(page)
  })

  test('[V27 鍵盤] 在折扣碼輸入框按 Enter 觸發驗證', async ({ page }) => {
    let validateCalled = false

    await page.route('**/subscription/validate-promo', (route) => {
      validateCalled = true
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: true,
          message: '折扣碼有效',
          originalAmount: 499,
          discountAmount: 50,
          finalAmount: 449,
        }),
      })
    })

    await openAgreementModal(page)
    await page.getByPlaceholder('輸入折扣碼').fill('ENTER10')
    await page.getByPlaceholder('輸入折扣碼').press('Enter')

    await expect(page.getByText(/折扣 \$50/)).toBeVisible({ timeout: 5000 })
    expect(validateCalled).toBe(true)
  })
})
