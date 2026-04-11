import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'
import { BookingPage } from '../../pages/BookingPage'
import { DashboardPage } from '../../pages/DashboardPage'

test.describe('VIP Client Booking Lifecycle & Quotes', () => {
  let authPage, bookingPage, dashPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    bookingPage = new BookingPage(page)
    dashPage = new DashboardPage(page)

    // Clear service workers to avoid PWA cache interference across multi-step flows
    await page.goto('/')
    await page.evaluate(async () => {
      const registrations = await navigator.serviceWorker.getRegistrations()
      for (const registration of registrations) {
        await registration.unregister()
      }
    })
  })

  test('should skip questionnaire for whitelisted clients and successfully compute quote surcharge', async ({ page }) => {
    // Stage 1: Sitter adds Client to Whitelist
    await test.step('Sitter grants VIP status', async () => {
      await authPage.injectSmokeAuth('SITTER')
      await dashPage.sendToWhitelist('James Client')
      // Note: Ideally the smoke db has this already or the seeder ensures James is whitelisted for this sitter
    })

    // Stage 2: Client starts booking and skips questionnaire
    await test.step('Client triggers VIP Fast-Pass', async () => {
      await authPage.injectSmokeAuth('JAMES')
      
      // Navigate to sitter's public profile link (assuming "anna-smith" for smoke seeder)
      await bookingPage.startBookingAsClient('anna-smith')
      await bookingPage.step1SelectCat(/Fluffy|貓咪/)
      await bookingPage.step2SelectPlan(/30.*|單次/)
      
      // Assert: Jumped to Step 4 instantly, showing VIP banner
      const isVipSkipped = await bookingPage.checkIsVIPQuestionnaireSkipped()
      expect(isVipSkipped).toBe(true)

      await bookingPage.submitBooking()
    })

    // Stage 3: Sitter processes quote with Surcharge
    await test.step('Sitter processes and surcharges order', async () => {
      await authPage.injectSmokeAuth('SITTER')
      await dashPage.navigateToInboxOrOrders()
      await dashPage.openFirstPendingOrder()
      
      // Modifies quote to add $200
      await dashPage.applySurchargeAndQuote(200)

      // Confirm status pushed to UI
      await expect(page.getByText(/Awaiting Payment|待付款/)).toBeVisible()
    })
  })
})
