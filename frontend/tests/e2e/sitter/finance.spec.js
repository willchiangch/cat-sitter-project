import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'
import { FinancePage } from '../../pages/FinancePage'

const MOCK_FINANCE_SUMMARY = {
  withdrawableBalance: 1500,
  pendingBalance: 300,
  totalRevenue: 5000,
  activeOrderCount: 2,
  averageOrderValue: 2500,
  recentTransactions: []
}

test.describe('Sitter Finance Tab E2E', () => {
  let authPage, financePage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    financePage = new FinancePage(page)

    await page.goto('/')
    await page.evaluate(async () => {
      const registrations = await navigator.serviceWorker.getRegistrations()
      for (const registration of registrations) {
        await registration.unregister()
      }
    })

    await authPage.injectSmokeAuth('SITTER')

    // Register finance mock AFTER injectSmokeAuth: Playwright processes routes LIFO,
    // so this specific mock takes priority over the catch-all **/* header-injection handler.
    await page.route('**/payments/payuni/sitter-summary', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_FINANCE_SUMMARY)
      })
    )
  })

  test('Finance page shows 待付款 and 收款紀錄 tabs', async ({ page }) => {
    await financePage.navigate()
    await expect(financePage.pendingTab).toBeVisible({ timeout: 10000 })
    await expect(financePage.historyTab).toBeVisible()
  })

  test('待付款 tab shows withdrawable balance and 申請提款 button', async ({ page }) => {
    await financePage.navigate()

    // Default tab is 待付款 — balance hero card should be visible
    await expect(page.getByText('可提領餘額')).toBeVisible({ timeout: 10000 })
    await expect(financePage.withdrawButton).toBeVisible()

    const balance = await financePage.getWithdrawableBalance()
    expect(balance).toContain('1,500')
  })

  test('Switching to 收款紀錄 tab shows history content', async ({ page }) => {
    await financePage.navigate()
    await financePage.switchToTab('收款紀錄')

    // On empty mock data, empty state should be shown (不顯示待付款 hero card)
    await expect(page.getByText('可提領餘額')).not.toBeVisible()
  })
})
