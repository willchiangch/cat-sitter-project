export class AuthPage {
  constructor(page) {
    this.page = page
    this.onboardingForm = page.locator('form')
  }

  async injectSmokeAuth(role) {
    // Injects the X-Smoke-Auth header at the browser context level
    // This allows seamless testing of the simulated OAuth callback states 
    // without actually doing Google/Apple redirects.
    await this.page.context().setExtraHTTPHeaders({
      'X-Smoke-Auth': role
    })
    
    // Navigate home to trigger the interceptors 
    await this.page.goto('/')
    await this.page.waitForLoadState('networkidle')
  }

  async completeOnboarding(displayName, roleType) {
    // Wait for App.jsx full interception redirect to complete
    await this.page.waitForURL(/\/onboarding$/, { timeout: 10000 })
    
    // Fill the display name input
    const input = this.page.getByPlaceholder(/Anna|顯示名稱/i)
    await input.waitFor({ state: 'visible' })
    await input.fill(displayName)

    // Select primary role
    if (roleType === 'SITTER') {
      await this.page.getByText(/professional cat sitter|我是貓咪保母/i).click()
    } else {
      await this.page.getByText(/trusted cat caregiver|我是家長/i).click()
    }

    // Submit and await backend profile creation
    const submitBtn = this.page.getByRole('button', { name: /Complete Setup|完成設定/i })
    
    // Promise.all to prevent race conditions during form submission
    await Promise.all([
      this.page.waitForResponse(resp => resp.url().includes('/api/v1/profiles') && resp.request().method() === 'POST' && [200, 201].includes(resp.status())),
      submitBtn.click()
    ])

    // Wait for the app to unlock and redirect home
    await this.page.waitForURL('/' , { timeout: 15000 }) 
  }
}
