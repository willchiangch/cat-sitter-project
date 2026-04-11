import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

// Stable NEWBIE state — no profiles, null lastActiveRole
const NEWBIE_USER = {
  accountId: 'efefefef-0000-0000-0000-000000000004',
  email: 'newbie_smoke@test.com',
  lastActiveRole: null,
  emailVerified: false,
  profiles: [],
  debugCode: null,
}

// State returned by /auth/complete-onboarding after successful onboarding
const POST_ONBOARDING_USER = {
  accountId: 'efefefef-0000-0000-0000-000000000004',
  email: 'newbie_smoke@test.com',
  lastActiveRole: 'SITTER',
  emailVerified: false,
  profiles: [{ profileId: 'new-profile-id', role: 'SITTER', name: 'Whisker Sitter E2E', avatarUrl: null }],
  debugCode: null,
}

test.describe('Onboarding & Advanced Integrations', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
  })

  test('should enforce onboarding for NEWBIE role via App.jsx interception', async ({ page }) => {
    // Step 1: Inject smoke auth (adds **/* route handler that injects X-Smoke-Auth header)
    await authPage.injectSmokeAuth('NEWBIE')

    // Step 2: Add specific mocks AFTER injectSmokeAuth — Playwright processes routes LIFO,
    // so handlers added LATER run FIRST. This ensures these specific fulfills
    // take priority over the **/* continue-with-header handler.
    await page.route('**/auth/complete-onboarding', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(POST_ONBOARDING_USER),
    }))
    await page.route('**/auth/me', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(NEWBIE_USER),
    }))

    // Step 3: Re-navigate so that the next getMe call uses the mock and
    //         needsOnboarding stays true regardless of DB state from prior runs.
    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    // Assert: App.jsx interceptor redirected to /onboarding
    await expect(page).toHaveURL(/\/onboarding/)
    await expect(page.getByText(/歡迎/i)).toBeVisible()

    // Act: Complete onboarding
    await authPage.completeOnboarding('Whisker Sitter E2E', 'SITTER')

    // Assert: Redirected out of onboarding into the app
    await expect(page).toHaveURL(/\/(sitter|client)/, { timeout: 15000 })
    await expect(page).not.toHaveURL(/\/onboarding/)
    await expect(page).not.toHaveURL(/\/login/)
  })
})
