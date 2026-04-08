export class AuthPage {
  constructor(page) {
    this.page = page
    this.onboardingForm = page.locator('form')
  }

  async injectSmokeAuth(role) {
    // Clear any previously registered **/* route handlers to avoid LIFO stacking
    // across multiple injectSmokeAuth calls within the same test (e.g. booking-lifecycle stages)
    await this.page.unroute('**/*')

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
    // This runs before any page scripts, ensuring Zustand sees the token on mount.
    // Multiple addInitScript calls accumulate — later scripts run after earlier ones,
    // so the most recently added script wins for auth state.
    await this.page.context().addInitScript((r) => {
      const isNewbie = r === 'NEWBIE'
      const isJames  = r === 'JAMES'

      let authState
      if (isJames) {
        authState = {
          state: {
            token: 'smoke-test-token',
            isAuthenticated: true,
            user: {
              id: 'efefefef-0000-0000-0000-000000000002',
              email: 'james@example.com',
              role: 'CLIENT',
              lastActiveRole: 'CLIENT',
              profiles: [
                { id: 'efefefef-0000-0000-0000-000000000002', role: 'CLIENT', name: 'James Wilson' }
              ]
            }
          },
          version: 0
        }
        // Set CLIENT theme mode so BottomNavBar renders Client tabs
        window.localStorage.setItem('whiskerwatch-theme-storage', JSON.stringify({
          state: { mode: 'CLIENT' },
          version: 0
        }))
      } else {
        authState = {
          state: {
            token: 'smoke-test-token',
            isAuthenticated: true,
            user: {
              id: isNewbie ? 'efefefef-0000-0000-0000-000000000003' : 'efefefef-0000-0000-0000-000000000001',
              email: isNewbie ? 'newbie@example.com' : 'sophia@example.com',
              role: isNewbie ? 'NEWBIE' : 'SITTER',
              lastActiveRole: isNewbie ? null : 'SITTER',
              profiles: isNewbie ? [] : [
                {
                  id: 'efefefef-0000-0000-0000-000000000001',
                  role: 'SITTER',
                  name: 'Sophia Sitter'
                }
              ]
            }
          },
          version: 0
        }
      }

      window.localStorage.setItem('whiskerwatch-auth-storage', JSON.stringify(authState))
      // Simulate the token that LoginCallback sets directly under 'token' key.
      // Onboarding.jsx reads this via localStorage.getItem('token') after completeOnboarding.
      window.localStorage.setItem('token', 'smoke-test-token')
    }, role)

    // 3. Navigate to a page to trigger the init script (hard navigation required)
    const targetUrl = role === 'JAMES' ? '/client' : '/profile'
    await this.page.goto(targetUrl)
    await this.page.waitForLoadState('load')
  }

  async injectClientSmokeAuth(targetUrl = '/client') {
    await this.page.unroute('**/*')

    await this.page.route('**/*', (route) => {
      const url = route.request().url()
      const headers = { ...route.request().headers() }
      if (url.includes('localhost') || url.startsWith('/')) {
        headers['X-Smoke-Auth'] = 'CLIENT'
      }
      route.continue({ headers })
    })

    await this.page.context().addInitScript(() => {
      window.localStorage.setItem('whiskerwatch-auth-storage', JSON.stringify({
        state: {
          token: 'smoke-test-token',
          isAuthenticated: true,
          user: {
            id: 'efefefef-0000-0000-0000-000000000002',
            email: 'james@example.com',
            role: 'CLIENT',
            lastActiveRole: 'CLIENT',
            profiles: [{ id: 'efefefef-0000-0000-0000-000000000002', role: 'CLIENT', name: 'James Wilson' }]
          }
        },
        version: 0
      }))
      window.localStorage.setItem('whiskerwatch-theme-storage', JSON.stringify({
        state: { mode: 'CLIENT' },
        version: 0
      }))
    })

    await this.page.goto(targetUrl)
    await this.page.waitForLoadState('load')
  }

  async completeOnboarding(displayName, roleType) {
    // Wait for App.jsx full interception redirect to complete
    await this.page.waitForURL(/\/onboarding$/, { timeout: 10000 })

    // Step 1: Select role — use force:true because Framer Motion AnimatePresence
    // briefly detaches elements during enter/exit animations
    if (roleType === 'SITTER') {
      await this.page.getByRole('button', { name: /冒險者/i }).click({ force: true })
    } else {
      await this.page.getByRole('button', { name: /召喚師/i }).click({ force: true })
    }

    // Step 2: Fill display name (input appears after role selection)
    const input = this.page.getByPlaceholder(/貓咪守護者|顯示名稱/i)
    await input.waitFor({ state: 'visible', timeout: 10000 })
    await input.fill(displayName)

    // Submit and await backend profile creation
    const submitBtn = this.page.getByRole('button', { name: /開啟冒險旅程/i })

    // Promise.all to prevent race conditions during form submission
    await Promise.all([
      this.page.waitForResponse(resp => resp.url().includes('/auth/complete-onboarding') && resp.request().method() === 'POST' && [200, 201].includes(resp.status())),
      submitBtn.click()
    ])

    // Wait for the app to unlock and redirect home
    await this.page.waitForURL('/' , { timeout: 15000 })
  }
}
