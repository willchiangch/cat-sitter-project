export class ClientSittersPage {
  constructor(page) {
    this.page = page
    this.header = page.getByRole('heading', { name: '我的保母' })
    this.searchInput = page.getByPlaceholder('輸入保母代碼')
    this.submitButton = page.locator('form button[type="submit"]')
    this.emptyState = page.getByText('還沒有加入任何保母')
  }

  async navigate() {
    await this.page.goto('/client/sitters')
    await this.page.waitForLoadState('networkidle')
  }

  async searchBySitterCode(code) {
    await this.searchInput.fill(code)
    await this.submitButton.click()
  }

  async getSitterCards() {
    // Each sitter card is a motion.div with h3 inside the main list area
    return this.page.locator('main').locator('h3').all()
  }
}
