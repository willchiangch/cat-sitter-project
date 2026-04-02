export class AuthPage {
  constructor(page) {
    this.page = page
    this.onboardingForm = page.locator('form')
  }

  async injectSmokeAuth(role) {
    // 1. Injects the X-Smoke-Auth header ONLY for internal API/app requests
    // This avoids breaking CORS for external domains like Google Fonts/CDNs
    await this.page.route('**/*', (route) => {
      const url = route.request().url()
      const headers = { ...route.request().headers() }
      
      // Only inject for our own domain
      if (url.includes('localhost') || url.startsWith('/')) {
        headers['X-Smoke-Auth'] = role
      }
      
      route.continue({ headers })
    })

    // 2. Injects mock localStorage state at the context level
    // This runs before any page scripts, ensuring Zustand sees the token on mount
    await this.page.context().addInitScript((r) => {
      const authState = {
        state: {
          token: 'smoke-test-token',
          isAuthenticated: true,
          user: {
            id: 'efefefef-0000-0000-0000-000000000001',
            email: 'sitter_smoke@test.com',
            role: 'SITTER',
            lastActiveRole: 'SITTER',
            profiles: [
              {
                id: 'efefefef-0000-0000-0000-000000000011',
                role: 'SITTER',
                name: 'Sophia (Smoke Test)'
              }
            ]
          }
        },
        version: 0
      };
      window.localStorage.setItem('whiskerwatch-auth-storage', JSON.stringify(authState));
    }, role);
    
    // 3. Just go to the target page; no need to reload or go to '/' first
    await this.page.goto('/profile')
    await this.page.waitForLoadState('load')
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
