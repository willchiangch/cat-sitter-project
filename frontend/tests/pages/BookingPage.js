// Smoke sitter profile ID (Sophia) — matches SmokeDataSeeder UUID 000011
const SOPHIA_SITTER_PROFILE_ID = 'efefefef-0000-0000-0000-000000000011'

export class BookingPage {
  constructor(page) {
    this.page = page
  }

  async startBookingAsClient(sitterUsername) {
    // Navigate directly to the booking flow with Sophia's smoke sitter profile ID.
    // A public /:sitterSlug profile route is not yet implemented in App.jsx;
    // until it is, we bypass it here and go straight to /booking/sitter/:id.
    await this.page.goto(`/booking/sitter/${SOPHIA_SITTER_PROFILE_ID}`)
    await this.page.waitForLoadState('networkidle')
  }

  async step1SelectCat(catNameRegex) {
    // Wait for pet list to load (petService.list() is async)
    await this.page.waitForSelector('button', { timeout: 10000 })
    await this.page.getByText(catNameRegex).click()
    await this.clickNextStep()
  }

  async step2SelectPlan(planNameRegex) {
    // Select the plan (BookingFlow shows Chinese plan names: 單次照護 / 全方位照護)
    await this.page.getByText(planNameRegex).click()

    // Fill in a booking date so the final submission payload is valid
    const dateInput = this.page.locator('input[type="date"]')
    if (await dateInput.count() > 0) {
      const tomorrow = new Date()
      tomorrow.setDate(tomorrow.getDate() + 1)
      const dateStr = tomorrow.toISOString().split('T')[0]
      await dateInput.fill(dateStr)
    }

    await this.clickNextStep()
  }

  async checkIsVIPQuestionnaireSkipped() {
    // zh-TW: "...已為您免除本次照護問卷填寫" | en: "VIP Status: Based on mutual trust..."
    const vipBanner = this.page.getByText(/免除本次照護問卷填寫|VIP Status/i)
    try {
      await vipBanner.waitFor({ state: 'visible', timeout: 5000 })
      return true
    } catch {
      return false
    }
  }

  async submitBooking() {
    // Clicks the "Confirm Booking" footer button at step 4 and awaits the POST order API
    const confirmBtn = this.page.getByRole('button', { name: /Confirm Booking|確認預約/i })
    await Promise.all([
      this.page.waitForResponse(
        resp => resp.url().includes('/api/v1/orders') && resp.request().method() === 'POST' && [200, 201].includes(resp.status())
      ),
      confirmBtn.click()
    ])
    await this.page.waitForURL(/\/client\/orders.*/, { timeout: 15000 })
  }

  async fillOutQuestionnaireIfRequired() {
    // Fill textareas gracefully
    const textareas = await this.page.locator('textarea').all()
    for (const area of textareas) {
       await area.fill('N/A (Automated Playwright Response)')
    }
    await this.clickNextStep()
  }

  async step4ConfirmBooking() {
    const confirmBtn = this.page.getByRole('button', { name: /Confirm Booking|送出預約/i })

    await Promise.all([
      this.page.waitForResponse(resp => resp.url().includes('/api/v1/orders') && resp.request().method() === 'POST' && [200, 201].includes(resp.status())),
      confirmBtn.click()
    ])

    // Validate we landed on the order success / detail page
    await this.page.waitForURL(/\/orders\/.*/)
  }

  async clickNextStep() {
    await this.page.getByRole('button', { name: /Continue|下一步/i }).click()
  }
}
