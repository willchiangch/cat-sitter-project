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

  test('Sitter Profile renders with booking URL section visible', async ({ page }) => {
    await profilePage.goto()
    await page.waitForLoadState('networkidle')
    const section = await profilePage.getBookingUrlSection()
    await expect(section).toBeVisible({ timeout: 15000 })
    await expect(section.getByRole('button', { name: '複製網址' })).toBeVisible()
    await expect(section.getByRole('button', { name: '預覽頁面' })).toBeVisible()
  })

  test('Sitter Dashboard shows today schedule without tool tab bar', async ({ page }) => {
    await page.goto('/sitter')
    await page.waitForLoadState('networkidle')
    // Must not be redirected to login
    await expect(page).not.toHaveURL(/\/login/)
    // UpcomingVisitCard (today's schedule) should be visible
    await expect(page.getByText('Oliver').first()).toBeVisible({ timeout: 10000 })
    // Tool tab bar buttons should NOT exist on the dashboard page
    await expect(page.getByRole('button', { name: /Questionnaire|問卷設定/i })).not.toBeVisible()
    await expect(page.getByRole('button', { name: /Services|服務方案/i })).not.toBeVisible()
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

  test('Subscription management page is accessible', async ({ page }) => {
    await page.goto('/sitter/subscription')
    await page.waitForLoadState('networkidle')

    // Page title
    await expect(page.getByText(/訂閱方案管理/i)).toBeVisible({ timeout: 10000 })

    // All four plans
    await expect(page.getByText('免費版').first()).toBeVisible()
    await expect(page.getByText('基礎版').first()).toBeVisible()
    await expect(page.getByText('專業版').first()).toBeVisible()
    await expect(page.getByText('頂級版').first()).toBeVisible()
  })

  test('Client gate page shows whitelist and blacklist tabs', async ({ page }) => {
    await page.goto('/sitter/client-gate')
    await page.waitForLoadState('networkidle')

    await expect(page.getByRole('button', { name: /白名單/i })).toBeVisible({ timeout: 10000 })
    await expect(page.getByRole('button', { name: /黑名單/i })).toBeVisible()
  })
})
