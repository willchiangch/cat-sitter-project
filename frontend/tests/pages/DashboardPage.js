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
    // Click the first order item to navigate to detail
    await this.page.getByText('待報價').first().click()
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
    
    // Wait for the backend patch API
    await Promise.all([
      this.page.waitForResponse(resp => resp.url().includes('/api/v1/orders/') && resp.request().method() === 'PATCH'),
      confirmBtn.click()
    ])
    
    await this.page.waitForLoadState('networkidle')
  }

  async sendToWhitelist(clientName) {
    await this.page.goto('/profile')
    await this.page.getByText(/Whitelist|熟客名單/i).click()

    // Add logic here based on actual table interactions
    // Wait for whitelist api call...
    const addBtn = this.page.getByRole('button', { name: /Add|加入名單/i })
    await addBtn.click()
    await this.page.getByPlaceholder('client name').fill(clientName)
    const submitBtn = this.page.getByRole('button', { name: /Confirm|確定/i })

    await Promise.all([
      this.page.waitForResponse(resp => resp.url().includes('/api/v1/sitter/whitelist') && resp.request().method() === 'POST'),
      submitBtn.click()
    ])
  }
}
