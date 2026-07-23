import { test, expect } from '@playwright/test';

test.describe('PRD-000 AC-8 帳號註銷（軟刪除）流程', () => {
  test.setTimeout(60000);

  async function loginViaRegisterFlow(page: import('@playwright/test').Page, email: string) {
    await page.route('**/api/auth/register', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OTP_SENT', email })
      });
    });
    await page.route('**/api/auth/register/verify-otp', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ accessToken: 'fake', refreshToken: 'fake', userId: '1', email, role: 'OWNER' })
      });
    });

    await page.goto('/register');
    await page.getByTestId('register-fullname-input').fill('註銷測試使用者');
    await page.getByTestId('register-email-input').fill(email);
    await page.getByTestId('register-password-input').fill('password123');
    await page.getByTestId('register-terms-checkbox').check();
    await page.getByTestId('register-submit-btn').click();
    await page.getByTestId('register-otp-input').fill('123456');
    await page.getByTestId('register-otp-submit-btn').click();
    await expect(page).toHaveURL(/\/demo/);
  }

  test('輸入正確密碼可成功註銷帳號並導回登入頁', async ({ page }) => {
    await loginViaRegisterFlow(page, 'deactivate-e2e@test.com');

    await page.route('**/api/auth/deactivate', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '帳號已成功註銷' })
      });
    });

    await page.goto('/account-settings');
    await page.getByTestId('account-settings-deactivate-btn').click();
    await page.getByTestId('account-settings-deactivate-password-input').fill('password123');
    await page.getByTestId('account-settings-deactivate-confirm-btn').click();

    await expect(page).toHaveURL(/\/login/);
    const accessToken = await page.evaluate(() => window.localStorage.getItem('accessToken'));
    expect(accessToken).toBeNull();
  });

  test('尚有未結案訂單時應顯示卡控訊息且不導頁', async ({ page }) => {
    await loginViaRegisterFlow(page, 'deactivate-blocked-e2e@test.com');

    await page.route('**/api/auth/deactivate', async (route) => {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'ACCOUNT_DEACTIVATION_BLOCKED', message: '您尚有未結案的訂單，請先完成或取消後再嘗試註銷帳號' })
      });
    });

    await page.goto('/account-settings');
    await page.getByTestId('account-settings-deactivate-btn').click();
    await page.getByTestId('account-settings-deactivate-password-input').fill('password123');
    await page.getByTestId('account-settings-deactivate-confirm-btn').click();

    await expect(page.getByTestId('account-settings-deactivate-error')).toHaveText('您尚有未結案的訂單，請先完成或取消後再嘗試註銷帳號');
    await expect(page).toHaveURL(/\/account-settings/);
  });
});
