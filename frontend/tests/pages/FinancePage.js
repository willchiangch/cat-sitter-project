export class FinancePage {
  constructor(page) {
    this.page = page
    this.pendingTab = page.getByRole('button', { name: '待付款' })
    this.historyTab = page.getByRole('button', { name: '收款紀錄' })
    this.withdrawButton = page.getByRole('button', { name: '申請提款' })
  }

  async navigate() {
    await this.page.goto('/sitter/finance')
    await this.page.waitForLoadState('networkidle')
  }

  async switchToTab(tabName) {
    await this.page.getByRole('button', { name: tabName }).click()
  }

  async getWithdrawableBalance() {
    // Hero card: h2 inside the section containing 可提領餘額 label
    const heroCard = this.page.locator('section').filter({ hasText: '可提領餘額' })
    return heroCard.locator('h2').textContent()
  }
}
