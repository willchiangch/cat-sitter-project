import { test, expect } from '@playwright/test';

test.describe('PRD-000 註冊與忘記密碼流程', () => {
  test.setTimeout(60000);

  test('可從登入頁前往註冊頁並成功送出註冊表單', async ({ page }) => {
    await page.route('**/api/auth/register', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ accessToken: 'fake', refreshToken: 'fake', userId: '1', email: 'new@test.com', role: 'OWNER' })
      });
    });

    await page.goto('/login');
    await page.getByTestId('login-register-link').click();
    await expect(page).toHaveURL(/\/register$/);

    await page.getByTestId('register-fullname-input').fill('新使用者');
    await page.getByTestId('register-email-input').fill('new@test.com');
    await page.getByTestId('register-password-input').fill('password123');
    await page.getByTestId('register-submit-btn').click();

    await expect(page.getByTestId('register-success-banner')).toBeVisible();
  });

  test('可從登入頁前往忘記密碼頁並送出申請', async ({ page }) => {
    await page.route('**/api/auth/forgot-password', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '若該信箱已註冊，重設密碼連結將寄送至該信箱' })
      });
    });

    await page.goto('/login');
    await page.getByTestId('login-forgot-password-link').click();
    await expect(page).toHaveURL(/\/forgot-password$/);

    await page.getByTestId('forgot-password-email-input').fill('someone@test.com');
    await page.getByTestId('forgot-password-submit-btn').click();

    await expect(page.getByTestId('forgot-password-success')).toBeVisible();
  });

  test('重設密碼頁帶有效 token 時可成功提交新密碼', async ({ page }) => {
    await page.route('**/api/auth/reset-password', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '密碼已成功重設，請重新登入' })
      });
    });

    await page.goto('/reset-password?token=fake-token-123');
    await page.getByTestId('reset-password-new-password-input').fill('newpassword456');
    await page.getByTestId('reset-password-submit-btn').click();

    await expect(page.getByTestId('reset-password-success')).toBeVisible();
  });

  test('重設密碼頁缺少 token 時應提示重新申請', async ({ page }) => {
    await page.goto('/reset-password');
    await expect(page.locator('text=重新申請')).toBeVisible();
  });
});
