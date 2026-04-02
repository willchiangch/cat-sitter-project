export class ProfilePage {
  constructor(page) {
    this.page = page
    this.nameText = page.locator('h2.text-2xl')
    this.sitterBadge = page.locator('span').filter({ hasText: /Professional|專業/ }).first()
    this.verifiedIcon = page.locator('span.material-symbols-outlined').filter({ hasText: /verified/i }).first()
    
    // Professional Tools Locators - Updated to match SettingsItem (Button)
    this.toolsSection = page.getByText(/專業經營工具|Professional Tools/i)
    this.managePackagesBtn = page.getByRole('button', { name: /管理服務方案/i })
    this.questionnaireBtn = page.getByRole('button', { name: /預約問卷設定/i })
    this.trustCircleBtn = page.getByRole('button', { name: /信任圈夥伴/i })
  }

  async goto() {
    await this.page.goto('/profile')
    // Wait for the name to ensure profile data is loaded partially
    await this.page.waitForLoadState('networkidle')
  }

  async verifySitterStatus() {
    // DIAGNOSTIC: Check if we were redirected
    const currentUrl = this.page.url()
    if (currentUrl.includes('/login') || currentUrl.includes('/role-selection')) {
      throw new Error(`Auth failed! Redirected to ${currentUrl}. Check localStorage injection.`)
    }

    // Wait for any h2 to ensure the skeleton is gone
    await this.page.locator('h2').first().waitFor({ state: 'visible', timeout: 15000 })
    
    // Check for professional badge
    await this.page.locator('span').filter({ hasText: /Professional|專業/ }).first().waitFor({ state: 'visible' })
  }

  async navigateToPackages() {
    await this.managePackagesBtn.waitFor({ state: 'visible' })
    await this.managePackagesBtn.click()
    // SPA-resilient wait for URL change
    await this.page.waitForFunction(() => window.location.pathname.includes('/sitter/service-packages'), { timeout: 30000 })
  }

  async navigateToQuestionnaire() {
    await this.questionnaireBtn.waitFor({ state: 'visible' })
    await this.questionnaireBtn.click()
    await this.page.waitForFunction(() => window.location.pathname.includes('/sitter/questionnaire'), { timeout: 30000 })
  }

  async navigateToTrustCircle() {
    await this.trustCircleBtn.waitFor({ state: 'visible' })
    await this.trustCircleBtn.click()
    await this.page.waitForFunction(() => window.location.pathname.includes('/sitter/trust-circle'), { timeout: 30000 })
  }
}
