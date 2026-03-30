export class BookingPage {
  constructor(page) {
    this.page = page
    this.baseUrl = '/sitter' // assuming routing like /username
  }

  async startBookingAsClient(sitterUsername) {
    await this.page.goto(`/${sitterUsername}`)
    await this.page.waitForLoadState('networkidle')
    
    // Attempt clicking main book button
    const bookBtn = this.page.getByRole('button', { name: /book now|立即預約|發起預約/i })
    await bookBtn.click()
    
    // Ensure we reached the order process
    await this.page.waitForURL(/\/order\/.*/)
  }

  async step1SelectCat(catNameRegex) {
    // wait for cat selection UI
    await this.page.getByText(catNameRegex).click()
    await this.clickNextStep()
  }

  async step2SelectPlan(planNameRegex) {
    // Select the plan
    await this.page.getByText(planNameRegex).click()
    await this.clickNextStep()
  }

  async checkIsVIPQuestionnaireSkipped() {
    // Fast verification if the app recognized the whitelist status
    const vipBanner = this.page.getByText(/VIP.*免除本次照護問卷填寫/i)
    return await vipBanner.isVisible()
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
