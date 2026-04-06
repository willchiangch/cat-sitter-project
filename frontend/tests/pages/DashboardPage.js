export class DashboardPage {
  constructor(page) {
    this.page = page
  }

  async navigateToInboxOrOrders() {
    await this.page.goto('/sitter/orders')
    await this.page.waitForLoadState('networkidle')
  }

  async openFirstPendingOrder() {
    // Click the 評估中 tab to surface PENDING/QUOTED orders
    await this.page.getByRole('button', { name: '評估中' }).click()
    await this.page.waitForLoadState('networkidle')
    // Filter for PENDING order cards only — PENDING badge = "Pending Quote" (en) / "待確認" (zh-TW).
    // Avoids clicking a QUOTED order that also appears in the 評估中 tab.
    // Use .last() to get the innermost matching div (the actual card, not its ancestors).
    const pendingCard = this.page.locator('div').filter({
      has: this.page.locator('span').filter({ hasText: /Pending Quote|待確認/ })
    }).filter({
      has: this.page.getByRole('button', { name: /Details/i })
    }).last()
    await pendingCard.getByRole('button', { name: /Details/i }).click()
    await this.page.waitForURL(/\/orders\/.*/)
  }

  async applySurchargeAndQuote(amount) {
    // Open Quote Modal
    await this.page.getByRole('button', { name: /Professional Quote|專業報價/i }).click()

    // Assuming the modal is open, wait for Surcharge Input
    const inputs = await this.page.locator('input[type="number"]').all()
    const surchargeInput = inputs[0] // based on the component spec
    await surchargeInput.fill(amount.toString())

    // Confirm Modal
    const confirmBtn = this.page.getByRole('button', { name: /Confirm & Send|確認發送/i })
    
    // submitQuote sends POST /api/v1/orders/{id}/quote (not PATCH)
    await Promise.all([
      this.page.waitForResponse(resp => resp.url().includes('/quote') && resp.request().method() === 'POST'),
      confirmBtn.click()
    ])
    
    await this.page.waitForLoadState('networkidle')
  }

  async sendToWhitelist(clientName) {
    await this.page.goto('/sitter/client-gate')
    await this.page.waitForLoadState('networkidle')
    await this.page.waitForSelector('button', { timeout: 10000 })
  }
}
