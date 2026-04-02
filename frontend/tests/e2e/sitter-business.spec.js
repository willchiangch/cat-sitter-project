import { test, expect } from '@playwright/test'
import { AuthPage } from '../pages/AuthPage'
import { ProfilePage } from '../pages/ProfilePage'
import { SitterToolsPage } from '../pages/SitterToolsPage'

test.describe('Sitter Professional Tools E2E Verification', () => {
  let authPage
  let profilePage
  let toolsPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    profilePage = new ProfilePage(page)
    toolsPage = new SitterToolsPage(page)

    // Clear all Service Workers to prevent PWA cache interference
    await page.goto('/')
    await page.evaluate(async () => {
      const registrations = await navigator.serviceWorker.getRegistrations();
      for (const registration of registrations) {
        await registration.unregister();
      }
    });

    // Bypass real login and inject smoke user (Sophia)
    await authPage.injectSmokeAuth('SITTER')
  })

  test('Navigation and content verification of Sitter Business Tools', async ({ page }) => {
    page.on('console', msg => console.log('BROWSER:', msg.text()));
    page.on('pageerror', err => console.log('PAGE ERROR:', err.message));
    
    const authPage = new AuthPage(page)
    // Note: injectSmokeAuth already navigated to /profile
    
    // Ensure we are NOT redirected back to login
    await expect(page).not.toHaveURL(/\/login/)
    
    const headings = await page.locator('h3').allTextContents()
    console.log('[DIAGNOSTIC] All h3 headings:', headings)
    
    await expect(profilePage.toolsSection).toBeVisible({ timeout: 15000 })

    // 2. Test Service Packages
    await profilePage.navigateToPackages()
    await expect(page).not.toHaveURL(/\/login/)
    await toolsPage.verifyPackageVisible('STANDARD')
    
    // 3. Test Questionnaires
    await profilePage.goto() 
    await expect(profilePage.toolsSection).toBeVisible()
    await profilePage.navigateToQuestionnaire()
    await expect(page).toHaveURL(/\/sitter\/questionnaire/)
    await toolsPage.verifyQuestionVisible('飲食需求')
    await toolsPage.verifyQuestionVisible('聯絡方式')

    // 4. Test Trust Circle
    await profilePage.goto() 
    await profilePage.navigateToTrustCircle()
    await expect(page).toHaveURL(/\/sitter\/trust-circle/)
    await toolsPage.verifyBuddyVisible('Buddy Sitter')
  })
})
