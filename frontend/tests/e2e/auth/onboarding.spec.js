import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

test.describe('Onboarding & Advanced Integrations', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
  })

  test('should enforce onboarding for NEWBIE role via App.jsx interception', async ({ page }) => {
    // Act: Inject smoke auth simulating a Google sign-in for a brand new account
    await authPage.injectSmokeAuth('NEWBIE')

    // Assert: Check the global interceptor redirected to /onboarding
    await expect(page).toHaveURL(/\/onboarding/)
    
    // Assert: Title should be visible
    await expect(page.getByText(/Weclome|歡迎/i)).toBeVisible()
    
    // Act: Complete onboarding
    await authPage.completeOnboarding('Whisker Sitter E2E', 'SITTER')

    // Assert: Onboarding was successful and redirected to home/dashboard
    await expect(page).toHaveURL(/\/(?!onboarding).*/)
    await expect(page.getByText('Whisker Sitter E2E')).toBeVisible()
  })
})
