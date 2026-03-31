export class SitterToolsPage {
  constructor(page) {
    this.page = page
    
    // Packages
    this.packageList = page.locator('div.grid-cols-1')
    this.standardPackage = page.getByText(/STANDARD/i)
    this.elitePackage = page.getByText(/ELITE/i)
    
    // Questionnaire
    this.questionList = page.locator('div.space-y-4')
    this.addQuestionBtn = page.locator('button').filter({ hasText: /Add Question|新增問題/i })
    this.dietQuestion = page.locator('text=/飲食需求|diet/i')
    this.healthQuestion = page.locator('text=/聯絡方式|contact/i')
    
    // Trust Circle
    this.buddyList = page.locator('main')
    this.buddySitter = page.locator('text=/Buddy Sitter/i')
    this.addBuddyBtn = page.locator('button').filter({ hasText: /Add|新增夥伴/i })
  }

  async verifyPackageVisible(name) {
    await this.page.getByText(name).waitFor({ state: 'visible' })
  }

  async verifyQuestionVisible(text) {
    await this.page.getByText(text).waitFor({ state: 'visible' })
  }

  async verifyBuddyVisible(name) {
    await this.page.getByText(name).waitFor({ state: 'visible' })
  }
}
