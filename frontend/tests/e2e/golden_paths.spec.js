import { test, expect } from '@playwright/test'

test.describe('Cat Parent: Ultimate Booking Flow', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to local dev server
    // Set smoke auth context for James Wilson (Client)
    await page.setExtraHTTPHeaders({ 'X-Smoke-Auth': 'JAMES' })
    await page.goto('/')
    // Ensure we are in Client mode (Switching logic depends on current Auth state)
    // For Mock, we assume the page starts in a common or sitter state, we might need a switch link
  })

  test('should complete a multi-step booking for a regular guest (skipping questionnaire)', async ({ page }) => {
    // 1. Enter a sitter booking page (using seeded UUID)
    await page.goto('/booking/sitter/efefefef-0000-0000-0000-000000000011')

    // 2. Step 1: Select Pets
    await expect(page.getByText('誰要被照顧？')).toBeVisible()
    await page.getByText('Oliver').click()
    await page.getByRole('button', { name: '繼續' }).or(page.getByRole('button', { name: 'Continue' })).click()

    // 3. Step 2: Choose Plan & Schedule
    await expect(page.getByText('方案與時段')).toBeVisible()
    await page.getByRole('button', { name: 'STANDARD' }).click()
    // Select a dummy date
    await page.locator('input[type="date"]').fill('2026-04-10')
    await page.getByRole('button', { name: '繼續' }).or(page.getByRole('button', { name: 'Continue' })).click()

    // 4. Verification: Jump logic for Regular Guests
    await expect(page.getByText('預約確認')).toBeVisible()
    await expect(page.getByText('尊榮常客狀態')).toBeVisible()

    // 5. Finalize
    await page.getByRole('button', { name: '確認預約' }).or(page.getByRole('button', { name: 'Confirm Booking' })).click()
    await expect(page).toHaveURL(/.*orders/)
  })
})

test.describe('Cat Sitter: Strategic Decisions', () => {
  test('should perform a professional quote with price adjustment', async ({ page }) => {
    // 1. Go to Sitter Order Detail (using seeded UUID)
    await page.goto('/sitter/orders/efefefef-0000-0000-0000-000000000123')

    // 2. Review Questionnaire
    await expect(page.getByText('事前問護問卷評估')).toBeVisible()

    // 3. Open Quote Modal
    await page.getByRole('button', { name: 'Review & Quote' }).click()
    await expect(page.getByText('專業報價與調價')).toBeVisible()

    // 4. Apply Surcharge - Using first number input
    await page.locator('input[type="number"]').first().fill('200')
    await expect(page.getByText('$1200')).toBeVisible() // Base 1000 + 200

    // 5. Confirm Quote
    await page.getByRole('button', { name: 'Confirm & Send' }).click()

    // 6. Verify Status Update
    await expect(page.getByText('已送出報價，待家長付款')).toBeVisible()
  })
})
